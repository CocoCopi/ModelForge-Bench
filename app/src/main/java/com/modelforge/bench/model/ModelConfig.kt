package com.modelforge.bench.model

import kotlinx.serialization.Serializable

/**
 * The architecture of a ModelForge model. Field names match the `config.json`
 * written by ModelForge, so a manifest or config file can be read directly.
 * The parameter formula must stay identical to `ModelConfig::param_count()` in
 * the C++ engine; [expectedParamCount] is used to validate a checkpoint header
 * before a single weight is loaded.
 */
@Serializable
data class ModelConfig(
    val vocabSize: Int = 0,
    val contextLength: Int = 0,
    val dModel: Int = 0,
    val layers: Int = 0,
    val heads: Int = 0,
    val dFf: Int = 0,
    val tiedEmbeddings: Boolean = true,
    val targetParams: Long = 0L,
    val bits: Int = 32,
    val qat: Boolean = false,
    val architectureId: String = "gpt-text"
) {
    val headDim: Int get() = if (heads > 0) dModel / heads else 0
    val isValid: Boolean
        get() = tiedEmbeddings && vocabSize > 0 && contextLength > 0 && dModel > 0 &&
            layers > 0 && heads > 0 && dModel % heads == 0 && dFf > 0

    fun expectedParamCount(): Long {
        if (!isValid) return 0L
        val d = dModel.toLong()
        val df = dFf.toLong()
        val v = vocabSize.toLong()
        val l = layers.toLong()
        val perLayer = 4L * d * d + 2L * d * df + 9L * d + df
        return v * d + l * perLayer + 2L * d
    }

    /** Linear weights are the quantizable ones. */
    fun linearWeightCount(): Long {
        if (!isValid) return 0L
        val d = dModel.toLong()
        val df = dFf.toLong()
        return layers.toLong() * (4L * d * d + 2L * d * df)
    }

    fun describe(): String = buildString {
        append("${formatParams(expectedParamCount())} params")
        append(" · d$dModel · ${layers}L · ${heads}H · ctx $contextLength")
        append(" · vocab $vocabSize")
    }

    companion object {
        /** Order matches `nativeConfig` in bench_jni.cpp. */
        fun fromNative(values: IntArray?): ModelConfig? {
            if (values == null || values.size < 7) return null
            return ModelConfig(
                vocabSize = values[0],
                contextLength = values[1],
                dModel = values[2],
                layers = values[3],
                heads = values[4],
                dFf = values[5],
                tiedEmbeddings = values[6] != 0
            )
        }

        fun formatParams(count: Long): String = when {
            count >= 1_000_000_000 -> String.format("%.2fB", count / 1_000_000_000.0)
            count >= 1_000_000 -> String.format("%.2fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
