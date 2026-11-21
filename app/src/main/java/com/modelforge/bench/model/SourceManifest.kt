package com.modelforge.bench.model

import kotlinx.serialization.Serializable

/**
 * The subset of ModelForge's `model.json` that matters for evaluation and for
 * a reproducible report. Unknown keys are ignored, so a future ModelForge can
 * add fields without breaking Bench.
 */
@Serializable
data class SourceDataset(
    val datasetId: String = "",
    val config: String = "",
    val split: String = "",
    val columns: List<String> = emptyList(),
    val weight: Float = 0f,
    val rows: Long = 0L,
    val bytes: Long = 0L,
    val license: String? = null,
    val nonCommercial: Boolean = false
)

@Serializable
data class TrainingSettingsSummary(
    val steps: Int = 0,
    val batchSize: Int = 0,
    val learningRate: Float = 0f,
    val seqLength: Int = 0,
    val weightDecay: Float = 0f,
    val corpusChars: Int = 0
)

@Serializable
data class SourceManifest(
    val id: String = "",
    val name: String = "",
    val creator: String = "",
    val createdAt: Long = 0L,
    val config: ModelConfig = ModelConfig(),
    val settings: TrainingSettingsSummary = TrainingSettingsSummary(),
    val datasets: List<SourceDataset> = emptyList(),
    val tokenizerTrained: Boolean = false,
    val languages: List<String> = emptyList()
) {
    val licenses: List<String>
        get() = datasets.mapNotNull { it.license?.takeIf { l -> l.isNotBlank() } }.distinct()

    val hasNonCommercialData: Boolean
        get() = datasets.any { it.nonCommercial }
}
