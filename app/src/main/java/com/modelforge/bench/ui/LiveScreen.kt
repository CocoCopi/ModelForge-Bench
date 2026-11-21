package com.modelforge.bench.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.modelforge.bench.core.Format
import com.modelforge.bench.service.EvalLive
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(
    live: EvalLive,
    onCancel: () -> Unit,
    onOpenHistory: () -> Unit
) {
    var confirmCancel by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val elapsed = if (live.startedAt > 0L) {
        (now - live.startedAt).coerceAtLeast(0L)
    } else {
        0L
    }

    Column(Modifier.padding(20.dp)) {
        ScreenHeader(
            if (live.running) "Recording" else "Run finished",
            if (live.running) {
                "You can leave this screen. Android keeps the evaluation alive in the notification."
            } else {
                "The report is saved in History."
            }
        )

        Spacer(Modifier.height(12.dp))
        Text(
            live.modelName.ifBlank { "Evaluation" },
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(18.dp))
        Text(
            live.stage.ifBlank { if (live.running) "Starting" else "Idle" },
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = live.fraction.toFloat().coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
            strokeCap = StrokeCap.Round,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${(live.fraction * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                Format.duration(elapsed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (live.detail.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                live.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionCard("What is happening") {
            Stage("Loading model", "Checks the checkpoint header, parameter count and quantisation before a single score is computed.")
            Stage("Scoring held-out text", "Perplexity, bits per byte and a bootstrap confidence interval.")
            Stage("Multiple choice", "Teacher-forced answer scoring with per-item confidence and expected calibration error.")
            Stage("Robustness", "Choice order is reversed and a neutral prefix is added; the accuracy delta is reported, not hidden.")
            Stage("Contamination", "Compares loss on held-out text against a perturbed copy to flag possible memorisation.")
            Stage("Generation", "Free-running samples measure repetition, empty output and invalid UTF-8.")
            Stage("Measuring throughput", "Time to first token, prefill rate, decode p50/p95, then a sustained load.")
            Stage("Finishing", "Energy, cost per million tokens and the weighted composite score.")
        }

        Spacer(Modifier.height(20.dp))
        if (live.running) {
            OutlinedButton(
                onClick = { confirmCancel = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Cancel run")
            }
        } else {
            Button(
                onClick = onOpenHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Open history")
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel this run?") },
            text = {
                Text(
                    "The current stage is abandoned and a cancelled entry is saved. " +
                        "Completed stages are not scored as a finished report."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    onCancel()
                }) { Text("Cancel run") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("Keep running") }
            }
        )
    }
}

@Composable
private fun Stage(title: String, body: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
