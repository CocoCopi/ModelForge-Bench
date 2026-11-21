package com.modelforge.bench.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.modelforge.bench.core.Format
import com.modelforge.bench.data.StoredPackInfo
import com.modelforge.bench.eval.RunMode
import com.modelforge.bench.eval.RunSettings

@Composable
fun SetupScreen(
    model: com.modelforge.bench.data.InstalledModel,
    settings: com.modelforge.bench.data.AppSettings,
    packs: List<StoredPackInfo>,
    onStart: (RunMode, String?) -> Unit,
    onPickBenchmark: () -> Unit
) {
    var mode by remember { mutableStateOf(settings.defaultMode) }
    var seedText by remember { mutableStateOf(settings.seed.toString()) }
    var packId by remember { mutableStateOf<String?>(null) }
    var packMenu by remember { mutableStateOf(false) }
    val packName = packs.firstOrNull { it.id == packId }?.name ?: "Bundled smoke pack (34 items)"

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(
            "Run benchmark",
            "Each stage is isolated, so a missing tokenizer or a stopped robustness pass never hides the numbers that did complete."
        )

        Card(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Format.params(model.paramCount)} · ${model.displayKind} · " +
                        "ctx ${model.config.contextLength} · vocab ${model.config.vocabSize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (model.hasTokenizer) {
                        "Tokenizer verified: quality, calibration and robustness will run."
                    } else {
                        "No tokenizer: only speed, memory, energy and cost will run."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (model.hasTokenizer) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard(
            "Depth",
            "Longer runs narrow the confidence intervals; they do not change the rubric.",
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            RunMode.entries.forEach { candidate ->
                ModeRow(candidate, mode == candidate) { mode = candidate }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard(
            "Question pack",
            "The bundled pack is authored for Bench. Import MMLU, ARC, HellaSwag or PIQA JSONL for real comparisons.",
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            OutlinedButton(onClick = { packMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(packName)
            }
            DropdownMenu(expanded = packMenu, onDismissRequest = { packMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Bundled smoke pack (34 items)") },
                    onClick = {
                        packId = null
                        packMenu = false
                    }
                )
                packs.forEach { pack ->
                    DropdownMenuItem(
                        text = { Text("${pack.name} (${pack.items} items)") },
                        onClick = {
                            packId = pack.id
                            packMenu = false
                        }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onPickBenchmark, modifier = Modifier.fillMaxWidth()) {
                Text("Import a benchmark file…")
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard(
            "Run configuration",
            null,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            val runSettings = RunSettings.forMode(mode, seedText.toLongOrNull() ?: 42L)
            ConfigRow("Multiple-choice items", runSettings.mcLimit.toString())
            ConfigRow("Held-out corpus tokens", runSettings.corpusTokenLimit.toString())
            ConfigRow("Throughput repetitions", runSettings.perfRuns.toString())
            ConfigRow(
                "Sustained load",
                if (runSettings.sustainedSeconds > 0) "${runSettings.sustainedSeconds}s" else "off (smoke)"
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = seedText,
                onValueChange = { value ->
                    if (value.all { it.isDigit() } && value.length <= 10) seedText = value
                },
                label = { Text("Random seed") },
                supportingText = { Text("Same seed + same model = reproducible prompt sampling.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))
        Card(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Before you start",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Keep the phone on a hard surface, ideally charging. " +
                        "A sustained run heats the SoC and later stages will be slower; " +
                        "Bench reports the drop instead of hiding it. Expect roughly 2–10 minutes for Smoke, " +
                        "20–60 minutes for Standard and 1–3 hours for Deep on a mid-range phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onStart(mode, packId) },
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Start ${mode.name.lowercase().replaceFirstChar { it.uppercase() }} run")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Runs in a foreground service with a wake lock. You can leave the app; " +
                "progress stays in the notification.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}

@Composable
private fun ModeRow(mode: RunMode, selected: Boolean, onSelect: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        label = {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    when (mode) {
                        RunMode.SMOKE -> "Smoke"
                        RunMode.STANDARD -> "Standard"
                        RunMode.DEEP -> "Deep"
                    },
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when (mode) {
                        RunMode.SMOKE -> "34 questions · no sustained load · sanity check"
                        RunMode.STANDARD -> "200 questions · 20s sustained · recommended"
                        RunMode.DEEP -> "1,000 questions · 60s sustained · tight intervals"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    )
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
