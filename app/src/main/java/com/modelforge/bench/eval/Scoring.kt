package com.modelforge.bench.eval

import kotlin.math.log10

/**
 * The composite score is a transparent weighted rubric, not a benchmark
 * authority. Each part publishes its own 0-100 score and the rule used to
 * derive it, so a reader can disagree with a weight and still use the data.
 */
object Scoring {

    private const val W_QUALITY = 45.0
    private const val W_CALIBRATION = 5.0
    private const val W_ROBUSTNESS = 5.0
    private const val W_GENERATION = 5.0
    private const val W_EFFICIENCY = 15.0
    private const val W_MEMORY = 10.0
    private const val W_COST = 15.0

    /** Quality-only score, used by the cost model before the composite exists. */
    fun qualityScore(quality: QualityResult?): Double? {
        val mc = quality?.multipleChoice
        val ppl = quality?.perplexity
        val mcComponent = mc?.let {
            val chance = it.chanceAccuracy.coerceIn(0.0, 0.9)
            val span = (1.0 - chance).coerceAtLeast(0.1)
            ((it.accuracy - chance) / span).coerceIn(0.0, 1.0) * 100.0
        }
        val bpbComponent = ppl?.bitsPerByte?.takeIf { it.isFinite() }?.let { bpb ->
            // 0.8 bits/byte is excellent for a small model; 4.0 is near uniform.
            ((4.0 - bpb) / (4.0 - 0.8)).coerceIn(0.0, 1.0) * 100.0
        }
        return when {
            mcComponent != null && bpbComponent != null -> mcComponent * 0.75 + bpbComponent * 0.25
            mcComponent != null -> mcComponent
            bpbComponent != null -> bpbComponent
            else -> null
        }
    }

    fun score(
        quality: QualityResult?,
        performance: PerformanceResult,
        energy: EnergyResult?,
        cost: CostResult?
    ): ScoreResult {
        val parts = ArrayList<ScorePart>(7)

        qualityScore(quality)?.let { value ->
            parts.add(
                ScorePart(
                    "Quality", value, W_QUALITY,
                    qualityDetail(quality)
                )
            )
        }

        quality?.multipleChoice?.ece?.takeIf { it.isFinite() }?.let { ece ->
            val value = (100.0 * (1.0 - ece / 0.40)).coerceIn(0.0, 100.0)
            parts.add(ScorePart("Calibration", value, W_CALIBRATION,
                "ECE ${"%.3f".format(ece)} (0 is perfect)"))
        }

        quality?.robustness?.let { r ->
            val value = (
                100.0 * (1.0 - r.flipRate.coerceIn(0.0, 1.0) * 0.8)
                    - 100.0 * (kotlin.math.abs(r.reversedDelta) + kotlin.math.abs(r.prefixedDelta))
                ).coerceIn(0.0, 100.0)
            parts.add(ScorePart("Robustness", value, W_ROBUSTNESS,
                "flips ${"%.1f".format(r.flipRate * 100)}%, reorder ${signed(r.reversedDelta)}, prefix ${signed(r.prefixedDelta)}"))
        }

        quality?.generation?.let { g ->
            val value = (g.distinct1 * 100.0).coerceIn(0.0, 100.0)
            parts.add(ScorePart("Generation", value, W_GENERATION,
                "distinct-1 ${"%.2f".format(g.distinct1)}, empty ${g.emptyOutputs}"))
        }

        val tps = performance.decode.p50
        val decodeComponent = logSpan(tps, 2.0, 300.0)
        val ttft = performance.ttftMs.p50
        val ttftComponent = (100.0 * (1.0 - (ttft - 50.0) / 2950.0)).coerceIn(0.0, 100.0)
        val efficiency = decodeComponent * 0.75 + ttftComponent * 0.25
        parts.add(ScorePart("Efficiency", efficiency, W_EFFICIENCY,
            "${"%.1f".format(tps)} tok/s p50, ${"%.0f".format(ttft)} ms TTFT"))

        val ratio = if (performance.weightsBytes > 0)
            performance.peakRssMb * 1024.0 * 1024.0 / performance.weightsBytes else Double.MAX_VALUE
        val memoryScore = ((3.0 - ratio) / 1.5 * 100.0).coerceIn(0.0, 100.0)
        parts.add(ScorePart("Memory", memoryScore, W_MEMORY,
            "${"%.0f".format(performance.peakRssMb)} MB peak for ${"%.0f".format(performance.weightsBytes / 1048576.0)} MB weights"))

        cost?.let { c ->
            val value = inverseLogSpan(c.deviceCostPerMillion, 0.01, 10.0)
            parts.add(ScorePart("Cost", value, W_COST,
                "${money(c.deviceCostPerMillion)} per 1M tokens on-device"))
        }

        // Renormalise when a section is missing (for example no tokenizer).
        val totalWeight = parts.sumOf { it.weight }
        val composite = if (totalWeight > 0.0)
            parts.sumOf { it.score * it.weight } / totalWeight else 0.0

        val grade = when {
            composite >= 85.0 -> "A"
            composite >= 75.0 -> "B+"
            composite >= 65.0 -> "B"
            composite >= 55.0 -> "C+"
            composite >= 45.0 -> "C"
            composite >= 35.0 -> "D"
            else -> "F"
        }
        val verdict = when {
            composite >= 80.0 -> "Production candidate"
            composite >= 65.0 -> "Promising"
            composite >= 50.0 -> "Experimental"
            else -> "Not recommended"
        }
        val weakest = parts.minByOrNull { it.score }
        val detail = buildString {
            append("Weighted rubric across ${parts.size} sections. ")
            if (weakest != null) {
                append("Weakest: ${weakest.name} (${"%.0f".format(weakest.score)}). ")
            }
            append("The score compares models measured by Bench on the same device; ")
            append("it is not an absolute capability claim.")
        }

        return ScoreResult(parts, composite, grade, verdict, detail)
    }

    private fun qualityDetail(quality: QualityResult?): String {
        val mc = quality?.multipleChoice
        val ppl = quality?.perplexity
        return buildList {
            if (mc != null) add("MC ${"%.1f".format(mc.accuracy * 100)}% (${mc.correct}/${mc.items})")
            if (ppl != null) add("PPL ${"%.2f".format(ppl.perplexity)} · ${"%.2f".format(ppl.bitsPerByte)} BPB")
        }.joinToString(" · ").ifBlank { "no quality data" }
    }

    /** 0 at [low], 100 at [high], logarithmic between. */
    private fun logSpan(value: Double, low: Double, high: Double): Double {
        if (!value.isFinite() || value <= low) return 0.0
        if (value >= high) return 100.0
        return ((log10(value) - log10(low)) / (log10(high) - log10(low)) * 100.0)
            .coerceIn(0.0, 100.0)
    }

    /** Inverse of [logSpan]: high cost maps to a low score. */
    private fun inverseLogSpan(value: Double, cheap: Double, expensive: Double): Double {
        if (!value.isFinite() || value <= cheap) return 100.0
        if (value >= expensive) return 0.0
        return ((log10(expensive) - log10(value)) / (log10(expensive) - log10(cheap)) * 100.0)
            .coerceIn(0.0, 100.0)
    }

    private fun signed(value: Double): String =
        if (value >= 0.0) "+${"%.1f".format(value * 100)}pp" else "${"%.1f".format(value * 100)}pp"

    private fun money(value: Double): String = when {
        !value.isFinite() -> "—"
        value >= 1.0 -> "$%.2f".format(value)
        value >= 0.01 -> "$%.3f".format(value)
        else -> "$%.5f".format(value)
    }
}
