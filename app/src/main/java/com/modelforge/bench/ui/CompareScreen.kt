package com.modelforge.bench.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modelforge.bench.core.Format
import com.modelforge.bench.eval.BenchRun
import com.modelforge.bench.eval.RunStatus

@Composable
fun CompareScreen(
    state: UiState,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onOpen: (BenchRun) -> Unit
) {
    val latest = state.latestByModel()
    val modelIds = latest.keys.toList().sortedBy { latest[it]?.modelName ?: "" }
    val selected = state.compareIds.filter { it in latest }
    val shown = if (selected.isNotEmpty()) selected else modelIds.take(2)

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(
            "Compare",
            "Newest completed run per model. Same device, same rubric, same seed — the only honest way to rank them."
        )

        if (modelIds.isEmpty()) {
            EmptyState(
                "Nothing to compare",
                "Finish at least one benchmark run. Two or more models unlock the full comparison table."
            )
            return
        }

        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(modelIds, key = { it }) { id ->
                FilterChip(
                    selected = id in shown,
                    onClick = { onToggle(id) },
                    label = {
                        Text(
                            latest[id]?.modelName ?: id,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
            item {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
        Spacer(Modifier.height(12.dp))

        val runs = shown.mapNotNull { latest[it] }
        if (runs.isEmpty()) {
            Text(
                "Select at least one model.",
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            return
        }

        SectionCard(
            "Composite score",
            "Weighted rubric out of 100. The score is a summary, not a benchmark authority.",
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            ScoreChart(runs)
            Spacer(Modifier.height(10.dp))
            runs.forEach { run ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(run.modelName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(
                        String.format("%.1f · %s", run.score?.composite ?: 0.0, run.score?.grade ?: "—"),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard(
            "Side by side",
            "Scroll the table sideways. Deltas are measured against the first selected model.",
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            ComparisonTable(runs)
        }

        val trends = runs.filter { run ->
            state.runs.count { it.modelId == run.modelId && it.status == RunStatus.COMPLETED } > 1
        }
        if (trends.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionCard(
                "Improvement over time",
                "Chronological runs of the same model, so a new training checkpoint can be judged against its predecessor.",
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                trends.forEach { latestRun ->
                    val history = state.runs
                        .filter { it.modelId == latestRun.modelId && it.status == RunStatus.COMPLETED }
                        .sortedBy { it.startedAt }
                    if (history.size < 2) return@forEach
                    Text(latestRun.modelName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    history.forEachIndexed { index, run ->
                        val previous = history.getOrNull(index - 1)
                        val delta = if (previous != null) {
                            (run.score?.composite ?: 0.0) - (previous.score?.composite ?: 0.0)
                        } else {
                            null
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    Format.date(run.finishedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    buildString {
                                        run.quality?.perplexity?.let {
                                            append("ppl ")
                                            append(String.format("%.2f", it.perplexity))
                                        }
                                        run.quality?.multipleChoice?.let {
                                            append(" · MC ")
                                            append(Format.percent(it.accuracy))
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                String.format("%.1f", run.score?.composite ?: 0.0),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Badge(
                                when {
                                    delta == null -> "baseline"
                                    delta >= 0 -> String.format("+%.1f", delta)
                                    else -> String.format("%.1f", delta)
                                },
                                when {
                                    delta == null -> BadgeTone.NEUTRAL
                                    delta > 0 -> BadgeTone.GOOD
                                    delta < 0 -> BadgeTone.BAD
                                    else -> BadgeTone.NEUTRAL
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onOpen(history.last()) }) {
                        Text("Open latest report")
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun ScoreChart(runs: List<BenchRun>) {
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        val rowHeight = size.height / runs.size.coerceAtLeast(1)
        runs.forEachIndexed { index, run ->
            val value = ((run.score?.composite ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
            val barHeight = rowHeight * 0.42f
            val top = rowHeight * index + (rowHeight - barHeight) / 2f
            drawRoundRect(
                color = track,
                topLeft = Offset(0f, top),
                size = Size(size.width, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f, barHeight / 2f)
            )
            drawRoundRect(
                color = bar,
                topLeft = Offset(0f, top),
                size = Size(size.width * value, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHeight / 2f, barHeight / 2f)
            )
        }
    }
}

@Composable
private fun ComparisonTable(runs: List<BenchRun>) {
    val baseline = runs.firstOrNull()
    val rows = listOf(
        CompareRow("Composite", { run -> String.format("%.1f", run.score?.composite ?: 0.0) },
            { run -> run.score?.composite }),
        CompareRow("MC accuracy", { run -> run.quality?.multipleChoice?.let { Format.percent(it.accuracy) } ?: "—" },
            { run -> run.quality?.multipleChoice?.accuracy?.times(100) }),
        CompareRow("Perplexity", { run -> run.quality?.perplexity?.let { String.format("%.2f", it.perplexity) } ?: "—" },
            { run -> run.quality?.perplexity?.perplexity }),
        CompareRow("Bits / byte", { run -> run.quality?.perplexity?.let { String.format("%.3f", it.bitsPerByte) } ?: "—" },
            { run -> run.quality?.perplexity?.bitsPerByte }),
        CompareRow("Decode tok/s", { run -> run.performance?.let { Format.tps(it.decode.p50) } ?: "—" },
            { run -> run.performance?.decode?.p50 }),
        CompareRow("TTFT p50", { run -> run.performance?.let { Format.ms(it.ttftMs.p50) } ?: "—" },
            { run -> run.performance?.ttftMs?.p50 }),
        CompareRow("Peak RSS MB", { run -> run.performance?.let { String.format("%.0f", it.peakRssMb) } ?: "—" },
            { run -> run.performance?.peakRssMb }),
        CompareRow("J / token", { run -> run.energy?.let { String.format("%.4f", it.joulesPerToken) } ?: "—" },
            { run -> run.energy?.joulesPerToken }),
        CompareRow("Cost / 1M", { run -> run.cost?.let { Format.money(it.deviceCostPerMillion) } ?: "—" },
            { run -> run.cost?.deviceCostPerMillion }),
        CompareRow("Run date", { run -> Format.date(run.finishedAt) }, { null })
    )

    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.width(104.dp)) {
            TableCell("Metric", header = true)
            rows.forEach { TableCell(it.label, header = false) }
        }
        runs.forEach { run ->
            Column(Modifier.width(112.dp)) {
                TableCell(run.modelName, header = true)
                rows.forEach { row ->
                    val value = row.value(run)
                    val delta = baseline?.let { base ->
                        if (run.id == base.id) null
                        else row.numeric(run)?.let { current ->
                            row.numeric(base)?.let { reference -> current - reference }
                        }
                    }
                    TableCell(value, delta = delta, header = false)
                }
            }
        }
    }
}

private data class CompareRow(
    val label: String,
    val value: (BenchRun) -> String,
    val numeric: (BenchRun) -> Double?
)

@Composable
private fun TableCell(text: String, delta: Double? = null, header: Boolean) {
    Column(
        Modifier
            .width(104.dp)
            .padding(vertical = 6.dp)
    ) {
        Text(
            text,
            style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (delta != null) {
            Text(
                String.format("%+.2f", delta),
                style = MaterialTheme.typography.bodySmall,
                color = if (delta >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}
