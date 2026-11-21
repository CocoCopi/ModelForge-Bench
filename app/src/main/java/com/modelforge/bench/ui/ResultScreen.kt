package com.modelforge.bench.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modelforge.bench.core.Format
import com.modelforge.bench.eval.BenchRun
import com.modelforge.bench.eval.RunStatus

@Composable
fun ResultScreen(
    run: BenchRun,
    onShare: (String) -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showAllSamples by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(
            run.modelName,
            "${run.mode.name.lowercase().replaceFirstChar { it.uppercase() }} run · " +
                Format.date(run.finishedAt) + " · " + Format.duration(run.durationMs)
        )

        if (run.status != RunStatus.COMPLETED) {
            Card(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (run.status == RunStatus.CANCELLED) "Run cancelled" else "Run failed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        run.error ?: "No scores were produced.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onHistory,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
            ) { Text("Back to history") }
            return
        }

        Card(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(run.score?.composite, run.score?.grade ?: "—")
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        run.score?.verdict ?: "No composite score",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        run.score?.detail ?: "The run did not produce enough sections for a composite.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Mono("model sha256 " + run.modelSha256.take(16) + "…")
                }
            }
        }

        if (run.warnings.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Card(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Read this before quoting the score",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    run.warnings.forEach { warning ->
                        Text(
                            "• $warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        val score = run.score
        if (score != null) {
            Spacer(Modifier.height(16.dp))
            SectionCard(
                "Score breakdown",
                "Every part is published with its weight. Missing sections are removed from the denominator, not scored as zero.",
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                score.parts.forEach { part ->
                    MetricBar(
                        label = "${part.name} · weight ${part.weight.toInt()}",
                        fraction = part.score / 100.0,
                        valueText = String.format("%.0f", part.score)
                    )
                    Text(
                        part.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        val quality = run.quality
        if (quality != null) {
            quality.perplexity?.let { ppl ->
                Spacer(Modifier.height(16.dp))
                SectionCard("Perplexity", ppl.dataset, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Perplexity", String.format("%.2f", ppl.perplexity), "lower is better", Modifier.weight(1f))
                        StatTile("Bits / byte", String.format("%.3f", ppl.bitsPerByte), "vocab-independent", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("95% CI", "${String.format("%.2f", ppl.ciLow)}–${String.format("%.2f", ppl.ciHigh)}", "bootstrap", Modifier.weight(1f))
                        StatTile("Tokens", ppl.tokens.toString(), "${ppl.windows} windows", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Mono("mean ${String.format("%.4f", ppl.meanNatsPerToken)} nats/token over ${Format.bytes(ppl.bytes)}")
                }
            }

            quality.multipleChoice?.let { mc ->
                Spacer(Modifier.height(16.dp))
                SectionCard(
                    "Multiple choice",
                    "${mc.pack} · ${mc.provenance}",
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            "Accuracy",
                            Format.percent(mc.accuracy),
                            "${mc.correct}/${mc.items} · chance ${Format.percent(mc.chanceAccuracy)}",
                            Modifier.weight(1f)
                        )
                        StatTile("ECE", String.format("%.3f", mc.ece), "calibration", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            "95% CI",
                            "${Format.percent(mc.ciLow)}–${Format.percent(mc.ciHigh)}",
                            "Wilson",
                            Modifier.weight(1f)
                        )
                        StatTile(
                            "Mean confidence",
                            Format.percent(mc.meanConfidence),
                            mc.scoring,
                            Modifier.weight(1f)
                        )
                    }
                    if (mc.categories.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text("By category", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        mc.categories.forEach { category ->
                            MetricBar(
                                label = "${category.category} (${category.correct}/${category.total})",
                                fraction = category.accuracy,
                                valueText = Format.percent(category.accuracy)
                            )
                            Text(
                                "95% CI ${Format.percent(category.ciLow)}–${Format.percent(category.ciHigh)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }

            quality.robustness?.let { robust ->
                Spacer(Modifier.height(16.dp))
                SectionCard(
                    "Robustness",
                    robust.notes,
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Base", Format.percent(robust.baseAccuracy), "${robust.evaluated} items", Modifier.weight(1f))
                        StatTile("Flip rate", Format.percent(robust.flipRate), "answer changes", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Reversed order", Format.percent(robust.reversedOrderAccuracy), signed(robust.reversedDelta), Modifier.weight(1f))
                        StatTile("Prefix added", Format.percent(robust.prefixedAccuracy), signed(robust.prefixedDelta), Modifier.weight(1f))
                    }
                }
            }

            quality.contamination?.let { contamination ->
                Spacer(Modifier.height(16.dp))
                SectionCard(
                    "Contamination check",
                    contamination.method,
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Memorisation gap", String.format("%.4f", contamination.memorizationGap), "nats/token", Modifier.weight(1f))
                        StatTile("Tokens", contamination.tokens.toString(), "held-out", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Mono("original ${String.format("%.4f", contamination.originalLoss)} · perturbed ${String.format("%.4f", contamination.perturbedLoss)}")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        contamination.interpretation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            quality.generation?.let { generation ->
                Spacer(Modifier.height(16.dp))
                SectionCard(
                    "Generation quality",
                    "Free-running samples with a fixed seed. Repetition is measured, not vibes.",
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Distinct-1", String.format("%.3f", generation.distinct1), "unique unigrams", Modifier.weight(1f))
                        StatTile("Distinct-2", String.format("%.3f", generation.distinct2), "unique bigrams", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Repetition", Format.percent(generation.repetitionRate), "repeated trigrams", Modifier.weight(1f))
                        StatTile("Avg length", String.format("%.1f", generation.averageTokens), "tokens", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Empty", generation.emptyOutputs.toString(), "outputs", Modifier.weight(1f))
                        StatTile("Invalid UTF-8", generation.invalidText.toString(), "outputs", Modifier.weight(1f))
                    }
                    if (generation.samples.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        val shown = if (showAllSamples) generation.samples else generation.samples.take(2)
                        shown.forEach { sample ->
                            Text(
                                sample.prompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                sample.output.ifBlank { "(empty)" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${sample.tokens} tokens · ${sample.stopReason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        if (generation.samples.size > 2) {
                            TextButton(onClick = { showAllSamples = !showAllSamples }) {
                                Text(if (showAllSamples) "Show fewer samples" else "Show all ${generation.samples.size} samples")
                            }
                        }
                    }
                }
            }

            quality.tokenizer?.let { tokenizer ->
                Spacer(Modifier.height(16.dp))
                SectionCard("Tokenizer", tokenizer.note, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Vocabulary", tokenizer.vocabulary.toString(), "${tokenizer.merges} merges", Modifier.weight(1f))
                        StatTile("Compression", String.format("%.2f", tokenizer.compression), "chars per token", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Tokens / word", String.format("%.2f", tokenizer.tokensPerWord), "lower is cheaper", Modifier.weight(1f))
                        StatTile("Samples", tokenizer.samples.toString(), "measured", Modifier.weight(1f))
                    }
                }
            }
        }

        val perf = run.performance
        if (perf != null) {
            Spacer(Modifier.height(16.dp))
            SectionCard("Performance", "Cold-start timings from this device, not a datasheet.", modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Decode p50", Format.tps(perf.decode.p50), "${perf.runs} runs", Modifier.weight(1f))
                    StatTile("Decode p95", Format.tps(perf.decode.p95), "worst case", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("TTFT p50", Format.ms(perf.ttftMs.p50), "time to first token", Modifier.weight(1f))
                    StatTile("Prefill", Format.tps(perf.prefillTokensPerSecond), "${perf.promptTokens} prompt tokens", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Peak RSS", "${String.format("%.0f", perf.peakRssMb)} MB", "after load ${String.format("%.0f", perf.rssAfterLoadMb)} MB", Modifier.weight(1f))
                    StatTile("Load time", Format.ms(perf.loadMs), Format.bytes(perf.weightsBytes), Modifier.weight(1f))
                }
                perf.sustained?.let { sustained ->
                    Spacer(Modifier.height(12.dp))
                    Text("Sustained load", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Early", Format.tps(sustained.earlyTokensPerSecond), "${String.format("%.0f", sustained.seconds)}s", Modifier.weight(1f))
                        StatTile("Late", Format.tps(sustained.lateTokensPerSecond), "after warm-up", Modifier.weight(1f))
                        StatTile("Drop", Format.percent(sustained.dropPercent / 100.0), "throughput", Modifier.weight(1f))
                    }
                    if (sustained.startBatteryTempC != null || sustained.endBatteryTempC != null) {
                        Spacer(Modifier.height(8.dp))
                        Mono(
                            "battery " +
                                (sustained.startBatteryTempC?.let { String.format("%.1f°C", it) } ?: "—") +
                                " → " +
                                (sustained.endBatteryTempC?.let { String.format("%.1f°C", it) } ?: "—")
                        )
                    }
                }
            }
        }

        val energy = run.energy
        if (energy != null) {
            Spacer(Modifier.height(16.dp))
            SectionCard("Energy", energy.note, modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("J / token", String.format("%.4f", energy.joulesPerToken), if (energy.measured) "measured" else "modelled", Modifier.weight(1f))
                    StatTile("mWh", String.format("%.3f", energy.milliwattHours), "${energy.tokens} tokens", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Average power", String.format("%.2f W", energy.averageWatts), energy.method, Modifier.weight(1f))
                    StatTile("Charging", if (energy.wasCharging) "yes" else "no", "during run", Modifier.weight(1f))
                }
            }
        }

        val cost = run.cost
        if (cost != null) {
            Spacer(Modifier.height(16.dp))
            SectionCard("Cost", cost.note, modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Device / 1M", Format.money(cost.deviceCostPerMillion), "energy + hardware", Modifier.weight(1f))
                    StatTile("Quality / $", cost.qualityPerDollar?.let { String.format("%.1f", it) } ?: "—", "higher is better", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Energy / 1M", Format.money(cost.energyCostPerMillion), "@ ${Format.money(cost.electricityPerKwh)}/kWh", Modifier.weight(1f))
                    StatTile("Amortised / 1M", Format.money(cost.amortisedCostPerMillion), "${cost.lifetimeHours.toInt()} h life", Modifier.weight(1f))
                }
                if (cost.apiRows.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Hosted API reference", style = MaterialTheme.typography.labelLarge)
                    cost.apiRows.forEach { row ->
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(row.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                Format.money(row.blendedPerMillion()) + " / 1M",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (row.note.isNotBlank()) {
                            Text(
                                row.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard("Provenance", null, modifier = Modifier.padding(horizontal = 20.dp)) {
            KeyRow("Model", run.modelName)
            KeyRow("Weights", "${run.weightsKind} · ${Format.params(run.paramCount)}" + if (run.bits > 0) " · ${run.bits}-bit" else "")
            KeyRow("Config", "d${run.config.dModel} · ${run.config.layers}L · ${run.config.heads}H · ctx ${run.config.contextLength} · vocab ${run.config.vocabSize}")
            KeyRow("Device", run.device.summary())
            KeyRow("Mode", "${run.mode.name} · seed ${run.seed} · ${run.settings.mcLimit} MC items")
            KeyRow("Engine", run.engineVersion)
            KeyRow("Started", Format.date(run.startedAt))
            KeyRow("Duration", Format.duration(run.durationMs))
            Spacer(Modifier.height(4.dp))
            Mono(run.modelSha256)
        }

        Spacer(Modifier.height(20.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onShare("pdf") }, modifier = Modifier.weight(1f)) { Text("PDF") }
                OutlinedButton(onClick = { onShare("html") }, modifier = Modifier.weight(1f)) { Text("HTML") }
                OutlinedButton(onClick = { onShare("csv") }, modifier = Modifier.weight(1f)) { Text("CSV") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onShare("json") }, modifier = Modifier.weight(1f)) { Text("JSON") }
                OutlinedButton(onClick = { onShare("all") }, modifier = Modifier.weight(1f)) { Text("Share all") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onHistory, modifier = Modifier.weight(1f)) { Text("History") }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text("Delete run") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this run?") },
            text = { Text("The run record and its generated reports are removed from Bench.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun KeyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(86.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

private fun signed(value: Double): String {
    val percent = value * 100.0
    return when {
        kotlin.math.abs(percent) < 0.05 -> "±0.0 pts"
        percent > 0 -> String.format("+%.1f pts", percent)
        else -> String.format("%.1f pts", percent)
    }
}
