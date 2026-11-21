package com.modelforge.bench.eval

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Small, dependency-free statistics used by the evaluation suite. Every number
 * in a report comes from one of these functions, so the definitions here are
 * part of the product, not an implementation detail.
 */
object Metrics {

    data class Interval(val low: Double, val high: Double)

    fun mean(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        var sum = 0.0
        for (v in values) sum += v
        return sum / values.size
    }

    fun stdev(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        var acc = 0.0
        for (v in values) {
            val d = v - m
            acc += d * d
        }
        return sqrt(acc / (values.size - 1))
    }

    fun median(values: DoubleArray): Double = percentile(values, 0.5)

    /**
     * Linear-interpolated percentile. [p] is a fraction in 0..1. Throughput
     * numbers are reported as p50/p95 rather than a mean so one thermal stall
     * cannot flatter a model.
     */
    fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sortedArray()
        if (sorted.size == 1) return sorted[0]
        val clamped = p.coerceIn(0.0, 1.0)
        val position = clamped * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val fraction = position - lower
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
    }

    /** Wilson score interval for a binomial proportion. */
    fun wilson(successes: Int, total: Int, z: Double = 1.96): Interval {
        if (total <= 0) return Interval(0.0, 0.0)
        val n = total.toDouble()
        val phat = successes.coerceIn(0, total) / n
        val z2 = z * z
        val denominator = 1.0 + z2 / n
        val centre = (phat + z2 / (2.0 * n)) / denominator
        val margin = z * sqrt(phat * (1.0 - phat) / n + z2 / (4.0 * n * n)) / denominator
        return Interval(
            low = (centre - margin).coerceIn(0.0, 1.0),
            high = (centre + margin).coerceIn(0.0, 1.0)
        )
    }

    /**
     * Expected calibration error over equal-width confidence bins. A model that
     * says 70% and is right 70% of the time scores 0. Lower is better.
     */
    fun ece(confidences: DoubleArray, correct: BooleanArray, bins: Int = 10): Double {
        if (confidences.isEmpty() || confidences.size != correct.size) return Double.NaN
        val count = IntArray(bins)
        val confidenceSum = DoubleArray(bins)
        val correctSum = DoubleArray(bins)
        for (i in confidences.indices) {
            val c = confidences[i].coerceIn(0.0, 1.0)
            val bin = ((c * bins).toInt()).coerceIn(0, bins - 1)
            count[bin]++
            confidenceSum[bin] += c
            correctSum[bin] += if (correct[i]) 1.0 else 0.0
        }
        var total = 0.0
        for (b in 0 until bins) {
            if (count[b] == 0) continue
            val avgConfidence = confidenceSum[b] / count[b]
            val accuracy = correctSum[b] / count[b]
            total += (count[b].toDouble() / confidences.size) *
                kotlin.math.abs(accuracy - avgConfidence)
        }
        return total
    }

    /** Softmax over already-computed scores. Numerically stable. */
    fun softmax(scores: DoubleArray): DoubleArray {
        if (scores.isEmpty()) return DoubleArray(0)
        var max = scores[0]
        for (v in scores) if (v > max) max = v
        val out = DoubleArray(scores.size)
        var sum = 0.0
        for (i in scores.indices) {
            out[i] = exp(scores[i] - max)
            sum += out[i]
        }
        if (!(sum > 0.0) || !sum.isFinite()) {
            return DoubleArray(scores.size) { 1.0 / scores.size }
        }
        for (i in out.indices) out[i] /= sum
        return out
    }

    /** Bits per byte is the tokenizer-independent form of a language-model loss. */
    fun bitsPerByte(totalNats: Double, bytes: Long): Double {
        if (bytes <= 0L || !totalNats.isFinite()) return Double.NaN
        return totalNats / (ln(2.0) * bytes.toDouble())
    }

    fun perplexity(meanNatsPerToken: Double): Double =
        if (!meanNatsPerToken.isFinite()) Double.NaN else exp(meanNatsPerToken)

    /**
     * Bootstrap confidence interval for a mean. Used for perplexity, where the
     * unit of resampling is a held-out window, and for latency aggregates.
     */
    fun bootstrapMeanCi(
        values: DoubleArray,
        iterations: Int = 400,
        confidence: Double = 0.95,
        seed: Long = 1234L
    ): Interval {
        if (values.isEmpty()) return Interval(Double.NaN, Double.NaN)
        if (values.size == 1) return Interval(values[0], values[0])
        val random = Random(seed)
        val means = DoubleArray(iterations)
        for (i in 0 until iterations) {
            var sum = 0.0
            for (j in values.indices) sum += values[random.nextInt(values.size)]
            means[i] = sum / values.size
        }
        val alpha = (1.0 - confidence) / 2.0
        return Interval(percentile(means, alpha), percentile(means, 1.0 - alpha))
    }

    /** Fraction of adjacent prediction pairs that changed. */
    fun flipRate(first: IntArray, second: IntArray): Double {
        if (first.size != second.size || first.isEmpty()) return Double.NaN
        var flips = 0
        for (i in first.indices) if (first[i] != second[i]) flips++
        return flips.toDouble() / first.size
    }

    fun distinctN(ids: IntArray, n: Int): Double {
        if (ids.size < n || n <= 0) return 0.0
        val seen = HashSet<Long>()
        for (i in 0..ids.size - n) {
            var key = 0L
            for (j in 0 until n) key = key * 1_000_003L + ids[i + j]
            seen.add(key)
        }
        return seen.size.toDouble() / (ids.size - n + 1)
    }
}
