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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.modelforge.bench.core.Format
import com.modelforge.bench.data.StoredPackInfo

@Composable
fun BenchmarksScreen(
    state: UiState,
    onImport: () -> Unit,
    onDelete: (StoredPackInfo) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<StoredPackInfo?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(
            "Benchmarks",
            "Bench ships a small authored pack for sanity checks. Real ranking needs real data — import the public sets you are licensed to use."
        )

        Card(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Import a JSONL pack",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "One JSON object per line. ARC, MMLU, HellaSwag and PIQA exports are recognised " +
                        "automatically, including sol1/sol2 endings and letter or numeric answer keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose .jsonl file")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard(
            "Bundled smoke pack",
            "34 questions across general knowledge, technical, narrative, science and dialogue. " +
                "Authored for Bench with balanced answer positions.",
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Text(
                "Use it to verify that a model, tokenizer and engine agree. " +
                    "It is deliberately not presented as a public leaderboard score: " +
                    "34 items cannot separate two good models.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.packs.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Imported packs",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(6.dp))
            state.packs.forEach { pack ->
                Card(
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 5.dp)
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(pack.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${pack.items} items · imported ${Format.date(pack.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            pack.provenance,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (pack.categories.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                pack.categories.take(4).forEach { Badge(it) }
                                if (pack.categories.size > 4) {
                                    Badge("+${pack.categories.size - 4} more", BadgeTone.INFO)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { pendingDelete = pack }) { Text("Delete pack") }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard(
            "Scoring method",
            null,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Method(
                "Answer scoring",
                "Each choice is scored as a continuation with teacher forcing. " +
                    "The highest total log-probability wins, with a length-normalised option for long answers."
            )
            Method(
                "Uncertainty",
                "Accuracy ships with a Wilson 95% interval. Perplexity ships with a bootstrap interval over windows."
            )
            Method(
                "Calibration",
                "Expected calibration error uses the model's own softmax confidence, " +
                    "so a confident wrong answer costs more than a hesitant one."
            )
            Method(
                "Robustness",
                "Reversing the choices and adding a neutral prefix should not change the answer. " +
                    "The deltas and flip rate are printed in the report."
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionCard(
            "Licensing",
            null,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Text(
                "Public benchmarks have their own licenses and terms. Bench does not silently bundle " +
                    "third-party test items: you import the files you are entitled to use, and the " +
                    "provenance string you see here is copied into every report. " +
                    "Keep the original attribution with any published score.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    val pack = pendingDelete
    if (pack != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${pack.name}?") },
            text = { Text("Runs that already used this pack keep their recorded provenance and scores.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(pack)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Method(title: String, body: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
