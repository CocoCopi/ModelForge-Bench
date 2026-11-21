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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.modelforge.bench.BuildConfig
import com.modelforge.bench.core.DeviceProfile
import com.modelforge.bench.data.AppSettings
import com.modelforge.bench.engine.BenchEngine
import com.modelforge.bench.eval.ApiPriceRow
import com.modelforge.bench.eval.CostSettings
import com.modelforge.bench.eval.RunMode

@Composable
fun SettingsScreen(
    state: UiState,
    onSettings: (AppSettings) -> Unit,
    onCost: (CostSettings) -> Unit,
    onMode: (RunMode) -> Unit,
    onSeed: (Long) -> Unit
) {
    val context = LocalContext.current
    val device = remember { DeviceProfile.collect(context) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(
            "Settings",
            "Cost inputs are yours. Bench never invents a vendor price, and it never sends anything off this device."
        )

        SectionCard(
            "Defaults",
            null,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Text("Run depth", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RunMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.settings.defaultMode == mode,
                        onClick = { onMode(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            NumberField(
                label = "Random seed",
                value = state.settings.seed.toString(),
                onCommit = { value -> value.toLongOrNull()?.let(onSeed) }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Keep screen on during runs", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Useful when watching a long evaluation on a bench.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.settings.keepScreenOn,
                    onCheckedChange = { onSettings(state.settings.copy(keepScreenOn = it)) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard(
            "Cost model",
            "Electricity, hardware price and lifetime decide the cost per million tokens. " +
                "Change them to your own numbers before quoting the report.",
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            NumberField(
                label = "Electricity price per kWh",
                value = state.settings.cost.electricityPerKwh.toString(),
                onCommit = { value ->
                    value.toDoubleOrNull()?.takeIf { it >= 0.0 }?.let {
                        onCost(state.settings.cost.copy(electricityPerKwh = it))
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            NumberField(
                label = "Device purchase price",
                value = state.settings.cost.hardwarePrice.toString(),
                onCommit = { value ->
                    value.toDoubleOrNull()?.takeIf { it >= 0.0 }?.let {
                        onCost(state.settings.cost.copy(hardwarePrice = it))
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            NumberField(
                label = "Usable device lifetime (hours)",
                value = state.settings.cost.lifetimeHours.toString(),
                onCommit = { value ->
                    value.toDoubleOrNull()?.takeIf { it > 0.0 }?.let {
                        onCost(state.settings.cost.copy(lifetimeHours = it))
                    }
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionCard(
            "Hosted API reference",
            "Placeholder tiers, not claims about any vendor. Enter the contract rates you actually pay.",
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            state.settings.cost.apiRows.forEachIndexed { index, row ->
                ApiRowEditor(row) { updated ->
                    val rows = state.settings.cost.apiRows.toMutableList()
                    rows[index] = updated
                    onCost(state.settings.cost.copy(apiRows = rows))
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionCard(
            "About",
            null,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            AboutRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            AboutRow("Engine", BenchEngine.engineVersion)
            AboutRow("Device", device.summary())
            AboutRow("SoC", device.soc)
            AboutRow("ABI", device.abi)
            Spacer(Modifier.height(8.dp))
            Text(
                "ModelForge Bench has no INTERNET permission. Models, benchmarks, runs and reports " +
                    "stay in app-private storage until you explicitly share a report.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Apache-2.0. Scores are reproducible measurements on this device, " +
                    "not claims about any other hardware.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= 12 && input.all { it.isDigit() || it == '.' }) {
                text = input
                onCommit(input)
            }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun ApiRowEditor(row: ApiPriceRow, onChange: (ApiPriceRow) -> Unit) {
    Column {
        Text(row.name, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "Input / 1M",
                value = row.inputPerMillion.toString(),
                onCommit = { value ->
                    value.toDoubleOrNull()?.takeIf { it >= 0.0 }?.let {
                        onChange(row.copy(inputPerMillion = it))
                    }
                },
                modifier = Modifier.weight(1f)
            )
            NumberField(
                label = "Output / 1M",
                value = row.outputPerMillion.toString(),
                onCommit = { value ->
                    value.toDoubleOrNull()?.takeIf { it >= 0.0 }?.let {
                        onChange(row.copy(outputPerMillion = it))
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.7f))
    }
}
