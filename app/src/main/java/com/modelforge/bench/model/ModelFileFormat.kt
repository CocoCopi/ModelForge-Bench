package com.modelforge.bench.model

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Header reader and integrity check for ModelForge's two on-device formats:
 *
 *  - `.mfck`  full-precision checkpoint ("MFCK"): config, step, then every
 *             parameter as [rows, cols, weights, (m, v when optimizer state is
 *             present)]. `best.mfck` is weights-only, `last.mfck` has AdamW state.
 *  - `.mfpk`  packed deployment file ("MFPK"): config, 2/3/4/8-bit linear
 *             weights with per-group scales, everything else fp32.
 *
 * Reading the header is cheap and proves the file is complete before the native
 * engine allocates anything. A truncated download then fails with a clear
 * message instead of a segfault or a silently garbage score.
 */
enum class ModelFileKind { CHECKPOINT, PACKED }

data class ModelFileInfo(
    val kind: ModelFileKind,
    val version: Int,
    val config: ModelConfig,
    val paramCount: Long,
    val step: Long = 0L,
    val hasOptimizer: Boolean = false,
    val qatBits: Int = 0,
    val qatGroup: Int = 0,
    val bits: Int = 0,
    val group: Int = 0,
    val fileBytes: Long = 0L
) {
    val isQuantized: Boolean get() = kind == ModelFileKind.PACKED
    val displayName: String
        get() = when {
            kind == ModelFileKind.PACKED -> "Packed ${bits}-bit (group $group)"
            hasOptimizer -> "Checkpoint with optimizer state"
            else -> "Checkpoint (weights only)"
        }
}

class ModelFormatException(message: String) : Exception(message)

object ModelFileFormat {
    const val MAGIC_CHECKPOINT = 0x4D46434B // "MFCK"
    const val MAGIC_PACKED = 0x4D46504B     // "MFPK"
    const val CHECKPOINT_HEADER_BYTES = 64
    const val PACKED_HEADER_BYTES = 68

    fun detect(file: File): ModelFileKind? {
        if (!file.isFile || file.length() < 8) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = readIntLE(raf)
                when (magic) {
                    MAGIC_CHECKPOINT -> ModelFileKind.CHECKPOINT
                    MAGIC_PACKED -> ModelFileKind.PACKED
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun inspect(file: File): ModelFileInfo {
        if (!file.isFile) throw ModelFormatException("${file.name} does not exist")
        val kind = detect(file)
            ?: throw ModelFormatException(
                "${file.name} is not a ModelForge model. Expected a .mfck checkpoint " +
                    "or a .mfpk packed file."
            )
        return when (kind) {
            ModelFileKind.CHECKPOINT -> inspectCheckpoint(file)
            ModelFileKind.PACKED -> inspectPacked(file)
        }
    }

    private fun inspectCheckpoint(file: File): ModelFileInfo {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < CHECKPOINT_HEADER_BYTES) {
                throw ModelFormatException("Checkpoint header is truncated.")
            }
            val magic = readIntLE(raf)
            val version = readIntLE(raf)
            val vocab = readIntLE(raf)
            val context = readIntLE(raf)
            val dModel = readIntLE(raf)
            val layers = readIntLE(raf)
            val heads = readIntLE(raf)
            val dFf = readIntLE(raf)
            val tied = readIntLE(raf)
            val step = readLongLE(raf)
            val hasOpt = readIntLE(raf)
            val qatBits = readIntLE(raf)
            val qatGroup = readIntLE(raf)
            val paramCount = readLongLE(raf)

            val config = ModelConfig(
                vocabSize = vocab, contextLength = context, dModel = dModel,
                layers = layers, heads = heads, dFf = dFf,
                tiedEmbeddings = tied != 0
            )
            if (magic != MAGIC_CHECKPOINT) throw ModelFormatException("Bad checkpoint magic.")
            if (version != 1 && version != 2) {
                throw ModelFormatException("Unsupported checkpoint version $version.")
            }
            if (!config.isValid) throw ModelFormatException("Checkpoint config is not valid.")
            val legacy = if (version == 1) context.toLong() * dModel else 0L
            val expected = config.expectedParamCount() + legacy
            if (expected != paramCount) {
                throw ModelFormatException(
                    "Checkpoint parameter count $paramCount does not match its config ($expected)."
                )
            }
            val withOptimizer = hasOpt != 0
            val expectedBytes = CHECKPOINT_HEADER_BYTES + checkpointPayloadBytes(
                config, withOptimizer, legacy
            )
            if (raf.length() < expectedBytes) {
                throw ModelFormatException(
                    "Checkpoint is truncated: ${file.length()} of $expectedBytes bytes. " +
                        "The model may still be exporting; try again."
                )
            }
            return ModelFileInfo(
                kind = ModelFileKind.CHECKPOINT,
                version = version,
                config = config,
                paramCount = paramCount,
                step = step,
                hasOptimizer = withOptimizer,
                qatBits = qatBits,
                qatGroup = qatGroup,
                fileBytes = file.length()
            )
        }
    }

    private fun inspectPacked(file: File): ModelFileInfo {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < PACKED_HEADER_BYTES) {
                throw ModelFormatException("Packed header is truncated.")
            }
            val magic = readIntLE(raf)
            val version = readIntLE(raf)
            val vocab = readIntLE(raf)
            val context = readIntLE(raf)
            val dModel = readIntLE(raf)
            val layers = readIntLE(raf)
            val heads = readIntLE(raf)
            val dFf = readIntLE(raf)
            val tied = readIntLE(raf)
            val bits = readIntLE(raf)
            val group = readIntLE(raf)
            val paramCount = readLongLE(raf)
            val packedBytes = readLongLE(raf)
            val scaleCount = readLongLE(raf)

            val config = ModelConfig(
                vocabSize = vocab, contextLength = context, dModel = dModel,
                layers = layers, heads = heads, dFf = dFf,
                tiedEmbeddings = tied != 0, bits = bits, qat = bits in 2..8
            )
            if (magic != MAGIC_PACKED) throw ModelFormatException("Bad packed magic.")
            if (version != 1 && version != 2) {
                throw ModelFormatException("Unsupported packed version $version.")
            }
            if (!config.isValid) throw ModelFormatException("Packed config is not valid.")
            if (bits !in setOf(2, 3, 4, 8)) {
                throw ModelFormatException("Unsupported packed width ${bits} bits.")
            }
            if (group <= 0) throw ModelFormatException("Packed group size must be positive.")
            val legacy = if (version == 1) context.toLong() * dModel else 0L
            val expectedParams = config.expectedParamCount() + legacy
            if (paramCount != expectedParams) {
                throw ModelFormatException(
                    "Packed parameter count $paramCount does not match its config ($expectedParams)."
                )
            }
            val expectedBytes = PACKED_HEADER_BYTES +
                packedPayloadBytes(config, bits, group, legacy)
            if (raf.length() < expectedBytes) {
                throw ModelFormatException(
                    "Packed model is truncated: ${file.length()} of $expectedBytes bytes."
                )
            }
            if (packedBytes <= 0 || scaleCount <= 0) {
                throw ModelFormatException("Packed model has no quantized weights.")
            }
            return ModelFileInfo(
                kind = ModelFileKind.PACKED,
                version = version,
                config = config,
                paramCount = paramCount,
                bits = bits,
                group = group,
                fileBytes = file.length()
            )
        }
    }

    /** Exact byte count of the parameter body of a checkpoint. */
    fun checkpointPayloadBytes(
        config: ModelConfig,
        withOptimizer: Boolean,
        legacyPositions: Long = 0L
    ): Long {
        val shapes = paramShapes(config)
        var total = 0L
        if (legacyPositions > 0) total += 8 + legacyPositions * 4L
        for ((rows, cols) in shapes) {
            val n = rows.toLong() * cols
            total += 8 + n * 4L * (if (withOptimizer) 3L else 1L)
        }
        return total
    }

    /** Exact byte count of the body of a packed model. */
    fun packedPayloadBytes(
        config: ModelConfig,
        bits: Int,
        group: Int,
        legacyPositions: Long = 0L
    ): Long {
        val d = config.dModel.toLong()
        val df = config.dFf.toLong()
        val v = config.vocabSize.toLong()
        var total = 4L * (v * d + 2L * d) // embeddings + final norm
        if (legacyPositions > 0) total += 4L * legacyPositions
        val perLayerNorm = 4L * d
        val linears = listOf(d to d, d to d, d to d, d to d, df to d, d to df)
        var perLayer = perLayerNorm
        for ((out, inp) in linears) {
            val groups = (inp + group - 1) / group
            perLayer += 4L * out // bias
            perLayer += (out * inp * bits + 7) / 8
            perLayer += 4L * out * groups
        }
        total += config.layers.toLong() * perLayer
        return total
    }

    /**
     * Parameter order written by `GPT::save_checkpoint`, used for the size
     * check only.
     */
    private fun paramShapes(config: ModelConfig): List<Pair<Int, Int>> {
        val d = config.dModel
        val df = config.dFf
        val out = ArrayList<Pair<Int, Int>>(4 + config.layers * 18)
        out.add(config.vocabSize to d)
        out.add(1 to d)
        out.add(1 to d)
        repeat(config.layers) {
            out.add(1 to d); out.add(1 to d); out.add(1 to d); out.add(1 to d)
            out.add(d to d); out.add(1 to d)
            out.add(d to d); out.add(1 to d)
            out.add(d to d); out.add(1 to d)
            out.add(d to d); out.add(1 to d)
            out.add(df to d); out.add(1 to df)
            out.add(d to df); out.add(1 to d)
        }
        return out
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readLongLE(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }
}
