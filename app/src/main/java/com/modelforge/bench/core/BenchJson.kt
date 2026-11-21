package com.modelforge.bench.core

import kotlinx.serialization.json.Json

/**
 * One JSON configuration for every persisted artifact. Unknown keys are
 * ignored so a newer build can read older runs, and defaults are encoded so a
 * report is self-describing even if the app's defaults later change.
 */
val BenchJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    allowSpecialFloatingPointValues = true
}
