package com.modelforge.bench.data

import com.modelforge.bench.model.ModelConfig
import com.modelforge.bench.model.ModelFileKind
import com.modelforge.bench.model.SourceManifest
import kotlinx.serialization.Serializable

enum class ModelOrigin { MODELFORGE, IMPORT, SHARED }

enum class WeightsKind { CHECKPOINT, PACKED }

/**
 * A model copied into Bench's own storage. Bench never mutates the source:
 * ModelForge keeps its model directory exactly as it was, and a report stays
 * reproducible even if the source model is later deleted or retrained.
 */
@Serializable
data class InstalledModel(
    val id: String,
    val name: String,
    val origin: ModelOrigin,
    val sourceId: String = "",
    val creator: String = "",
    val modelCreatedAt: Long = 0L,
    val importedAt: Long = 0L,
    val weightsFile: String,
    val tokenizerFile: String? = null,
    val manifestFile: String? = null,
    val kind: WeightsKind,
    val config: ModelConfig,
    val paramCount: Long,
    val bytes: Long,
    val sha256: String,
    val withOptimizer: Boolean = false,
    val bits: Int = 0,
    val group: Int = 0,
    val sourceManifest: SourceManifest? = null,
    val notes: List<String> = emptyList()
) {
    val displayKind: String
        get() = when {
            kind == WeightsKind.PACKED -> "Packed ${bits}-bit"
            withOptimizer -> "Checkpoint + optimizer"
            else -> "Checkpoint"
        }

    val hasTokenizer: Boolean get() = tokenizerFile != null
    val isModelForge: Boolean get() = origin == ModelOrigin.MODELFORGE
}
