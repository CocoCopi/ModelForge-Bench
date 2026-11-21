package com.modelforge.bench.data

import android.content.Context
import com.modelforge.bench.core.BenchJson
import com.modelforge.bench.eval.CostSettings
import com.modelforge.bench.eval.RunMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class AppSettings(
    val cost: CostSettings = CostSettings(),
    val defaultMode: RunMode = RunMode.STANDARD,
    val seed: Long = 42L,
    val lastModelId: String? = null,
    val selectedBenchmarkId: String? = null,
    val keepScreenOn: Boolean = true
)

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("bench_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val raw = prefs.getString(KEY, null) ?: return AppSettings()
        return runCatching { BenchJson.decodeFromString<AppSettings>(raw) }
            .getOrElse { AppSettings() }
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY, BenchJson.encodeToString(settings))
            .apply()
    }

    private companion object {
        const val KEY = "settings_json"
    }
}
