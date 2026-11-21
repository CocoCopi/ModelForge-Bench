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
import com.modelforge.bench.data.InstalledModel
import com.modelforge.bench.model.ModelConfig
import com.modelforge.bench.source.ModelForgeClient

@Composable
fun ScreenHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ModelsScreen(
    state: UiState,
    onOpen: (Screen) -> Unit,
    onPickBundle: () -> Unit,
    onPickWeights: () -> Unit,
    onImportForge: (ModelForgeClient.ForgeModel) -> Unit,
    onRequestForgePermission: () -> Unit,
    onRefreshForge: () -> Unit,
    onDelete: (InstalledModel) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<InstalledModel?>(null) }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
    ) {
        item {
            ScreenHeader(
                "Models",
                "Everything is scored on this phone, offline. Import a model, run the suite, compare the receipts."
            )
        }
        item {
            ImportCard(
                onPickBundle = onPickBundle,
                onPickWeights = onPickWeights
            )
        }
        item {
            ForgeCard(
                availability = state.forgeAvailability,
                models = state.forgeModels,
                importedIds = state.importedForgeIds,
                busy = state.busy,
                onImport = onImportForge,
                onRequestPermission = onRequestForgePermission,
                onRefresh = onRefreshForge
            )
        }
        if (state.models.isEmpty()) {
            item {
                EmptyState(
                    "No models yet",
                    "Add a .mfck checkpoint or .mfpk packed model. Bench also accepts a .mfbench bundle from ModelForge."
                )
            }
        } else {
            item {
                Text(
                    "Installed",
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(state.models, key = { it.id }) { model ->
                InstalledModelCard(
                    model = model,
                    latest = state.latestByModel()[model.id]?.headline,
                    onBenchmark = { onOpen(Screen.Setup(model.id)) },
                    onDelete = { pendingDelete = model }
                )
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${toDelete.name}?") },
            text = {
                Text(
                    "The copied weights, tokenizer and manifest are removed from Bench. " +
                        "Models owned by ModelForge are not touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(toDelete)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ImportCard(onPickBundle: () -> Unit, onPickWeights: () -> Unit) {
    Card(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Add a model",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Import a bundle with its tokenizer so quality, perplexity and calibration can run. " +
                    "Weights alone still get speed, memory and energy numbers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onPickBundle, modifier = Modifier.weight(1f)) {
                    Text("Bundle")
                }
                OutlinedButton(onClick = onPickWeights, modifier = Modifier.weight(1f)) {
                    Text("Weights")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Bundle: .mfbench or .zip · Weights: .mfck or .mfpk",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun ForgeCard(
    availability: ModelForgeClient.Availability,
    models: List<ModelForgeClient.ForgeModel>,
    importedIds: Set<String>,
    busy: Boolean,
    onImport: (ModelForgeClient.ForgeModel) -> Unit,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    val pending = models.filter { it.id !in importedIds }
    if (availability == ModelForgeClient.Availability.READY && pending.isEmpty()) return

    Spacer(Modifier.height(14.dp))
    Card(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ModelForge", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            when (availability) {
                ModelForgeClient.Availability.READY -> {
                    Text(
                        "Copy a model straight from ModelForge. Bench keeps its own snapshot, " +
                            "so a later retrain cannot change a published score.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (pending.isEmpty()) {
                        Text(
                            "Every ModelForge model has been copied.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    pending.forEach { model ->
                        ForgeRow(model, busy, onImport)
                    }
                }
                ModelForgeClient.Availability.NEEDS_PERMISSION -> {
                    Text(
                        "Bench needs the read-only model permission declared by ModelForge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = onRequestPermission, enabled = !busy) {
                        Text("Grant read access")
                    }
                }
                ModelForgeClient.Availability.NOT_INSTALLED -> {
                    Text(
                        "ModelForge 2.4.1 or newer can stream models here without exporting a file. " +
                            "Until then, use Bundle or Weights.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ModelForgeClient.Availability.NOT_SUPPORTED -> {
                    Text(
                        "This ModelForge build does not expose the model provider yet. " +
                            "Update ModelForge, or import a bundle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ModelForgeClient.Availability.ERROR -> {
                    Text(
                        "ModelForge is installed but the provider could not be read. " +
                            "Check that Bench has the model permission, then refresh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRequestPermission) { Text("Check permission") }
                        TextButton(onClick = onRefresh) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForgeRow(
    model: ModelForgeClient.ForgeModel,
    busy: Boolean,
    onImport: (ModelForgeClient.ForgeModel) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val kind = when {
                model.hasBest || model.hasLast -> "checkpoint"
                model.packedBits > 0 -> "${model.packedBits}-bit packed"
                else -> "no weights"
            }
            Text(
                "${ModelConfig.formatParams(model.params)} params · $kind · " +
                    Format.bytes(model.bestWeightsBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(
            onClick = { onImport(model) },
            enabled = !busy && (model.hasBest || model.hasLast || model.packedBits > 0)
        ) { Text("Copy") }
    }
}

@Composable
private fun InstalledModelCard(
    model: InstalledModel,
    latest: String?,
    onBenchmark: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Badge(model.displayKind)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${Format.params(model.paramCount)} · ${Format.bytes(model.bytes)} · " +
                    "d${model.config.dModel} · ${model.config.layers}L · ctx ${model.config.contextLength}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(
                    if (model.hasTokenizer) "tokenizer" else "no tokenizer",
                    if (model.hasTokenizer) BadgeTone.GOOD else BadgeTone.WARN
                )
                Badge(
                    when (model.origin) {
                        com.modelforge.bench.data.ModelOrigin.MODELFORGE -> "from ModelForge"
                        com.modelforge.bench.data.ModelOrigin.SHARED -> "shared"
                        else -> "imported"
                    },
                    if (model.origin == com.modelforge.bench.data.ModelOrigin.MODELFORGE) {
                        BadgeTone.INFO
                    } else {
                        BadgeTone.NEUTRAL
                    }
                )
                if (model.withOptimizer) Badge("optimizer state", BadgeTone.WARN)
            }
            Spacer(Modifier.height(8.dp))
            Mono("sha256 " + model.sha256.take(16) + "…")
            if (latest != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Latest: $latest",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (model.notes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                model.notes.forEach { note ->
                    Text(
                        "• $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onBenchmark) { Text("Run benchmark") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
