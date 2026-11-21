package com.modelforge.bench.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
fun HistoryScreen(
    state: UiState,
    onOpen: (BenchRun) -> Unit,
    onCompare: () -> Unit
) {
    var modelFilter by remember { mutableStateOf<String?>(null) }
    val runs = state.runs.filter { modelFilter == null || it.modelId == modelFilter }
    val modelNames = state.runs.map { it.modelId to it.modelName }.distinctBy { it.first }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
        item {
            ScreenHeader(
                "History",
                "Every run keeps its model hash, device profile and seed. If two scores differ, the reason is in the record."
            )
        }
        if (modelNames.size > 1) {
            item {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        FilterChipCompat("All models", modelFilter == null) { modelFilter = null }
                    }
                    items(modelNames, key = { it.first }) { (id, name) ->
                        FilterChipCompat(name, modelFilter == id) { modelFilter = id }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        if (runs.isEmpty()) {
            item {
                EmptyState(
                    "No runs yet",
                    "Import a model and start a Smoke run. It takes minutes and produces a real, citable report."
                )
            }
        } else {
            items(runs, key = { it.id }) { run ->
                RunCard(run, onOpen)
            }
        }
        if (state.runs.any { it.status == RunStatus.COMPLETED }) {
            item {
                OutlinedButton(
                    onClick = onCompare,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .fillMaxWidth()
                ) { Text("Compare models") }
            }
        }
    }
}

@Composable
private fun RunCard(run: BenchRun, onOpen: (BenchRun) -> Unit) {
    Card(
        onClick = { onOpen(run) },
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    run.modelName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${run.mode.name.lowercase().replaceFirstChar { it.uppercase() }} · " +
                        Format.date(run.finishedAt) + " · " + Format.duration(run.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(Format.params(run.paramCount))
                        append(" · ")
                        append(run.weightsKind)
                        run.quality?.perplexity?.let {
                            append(" · ppl ")
                            append(String.format("%.2f", it.perplexity))
                        }
                        run.quality?.multipleChoice?.let {
                            append(" · MC ")
                            append(Format.percent(it.accuracy))
                        }
                        run.performance?.let {
                            append(" · ")
                            append(Format.tps(it.decode.p50))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (run.status == RunStatus.COMPLETED) {
                    Text(
                        String.format("%.0f", run.score?.composite ?: 0.0),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        run.score?.grade ?: "—",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Badge(
                        run.status.name.lowercase(),
                        if (run.status == RunStatus.CANCELLED) BadgeTone.WARN else BadgeTone.BAD
                    )
                }
            }
        }
        if (!run.warnings.isEmpty() && run.status == RunStatus.COMPLETED) {
            Text(
                "Partial: ${run.warnings.first()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun FilterChipCompat(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    )
}
