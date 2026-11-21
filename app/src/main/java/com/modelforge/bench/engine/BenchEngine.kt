package com.modelforge.bench.engine

import com.modelforge.bench.model.ModelConfig
import com.modelforge.bench.model.ModelFileKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A loaded, inference-only model. All native calls are serialized: the engine
 * has one forward cache, so two coroutines scoring at once would otherwise
 * corrupt each other. The mutex is fair enough for a benchmark that runs its
 * stages in sequence.
 */
class BenchEngine private constructor(
    private val handle: Long,
    val config: ModelConfig,
    val file: File,
    val kind: ModelFileKind
) : Closeable {

    private val lock = Mutex()
    private val closed = AtomicBoolean(false)

    data class Generation(
        val ids: IntArray,
        val prefillNanos: Long,
        val decodeNanos: Long
    ) {
        val decodeTokens: Int get() = ids.size
        val decodeTokensPerSecond: Double
            get() = if (decodeNanos <= 0L || ids.isEmpty()) 0.0
            else ids.size * 1_000_000_000.0 / decodeNanos
        val timeToFirstTokenMs: Double get() = prefillNanos / 1_000_000.0
    }

    suspend fun loss(tokens: IntArray, targets: IntArray, batch: Int, seq: Int): Float =
        lock.withLock {
            checkOpen()
            val value = NativeBridge.nativeLoss(handle, tokens, targets, batch, seq)
            if (value < 0f) throw EngineException(lastError() ?: "loss failed")
            value
        }

    /** Sum of log p(continuation | prefix), teacher forced in one pass. */
    suspend fun continueLogProb(prefix: IntArray, continuation: IntArray): Float =
        lock.withLock {
            checkOpen()
            val value = NativeBridge.nativeContinueLogProb(handle, prefix, continuation)
            if (value == -1f || value.isNaN()) throw EngineException(lastError() ?: "scoring failed")
            value
        }

    suspend fun tokenLogProbs(prefix: IntArray, continuation: IntArray): FloatArray =
        lock.withLock {
            checkOpen()
            NativeBridge.nativeTokenLogProbs(handle, prefix, continuation)
                ?: throw EngineException(lastError() ?: "scoring failed")
        }

    suspend fun generate(
        prompt: IntArray,
        maxNew: Int,
        temperature: Float = 0f,
        topK: Int = 0,
        topP: Float = 1f,
        seed: Long = 42L
    ): Generation = lock.withLock {
        checkOpen()
        val timing = LongArray(2)
        val ids = NativeBridge.nativeGenerateTimed(
            handle, prompt, maxNew, temperature, topK, topP, seed, timing
        ) ?: throw EngineException(lastError() ?: "generation failed")
        Generation(ids, timing[0], timing[1])
    }

    /**
     * The one long native call that can be interrupted from another coroutine.
     * The flag is checked between tokens inside the engine.
     */
    fun requestStop() {
        if (!closed.get()) NativeBridge.nativeAbort(handle)
    }

    fun clearStop() {
        if (!closed.get()) NativeBridge.nativeClearAbort(handle)
    }

    private fun lastError(): String? = NativeBridge.nativeLastError(handle)

    private fun checkOpen() {
        if (closed.get()) throw EngineException("The model has been closed.")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            NativeBridge.nativeFree(handle)
        }
    }

    companion object {
        val engineVersion: String by lazy {
            runCatching { NativeBridge.nativeVersion() }.getOrDefault("unavailable")
        }

        /** Loads a `.mfck` or `.mfpk` file. Blocking: call on Dispatchers.IO. */
        fun open(file: File, kind: ModelFileKind, withOptimizer: Boolean = false): BenchEngine {
            val handle = when (kind) {
                ModelFileKind.CHECKPOINT ->
                    NativeBridge.nativeLoadCheckpoint(file.absolutePath, withOptimizer)
                ModelFileKind.PACKED ->
                    NativeBridge.nativeLoadPacked(file.absolutePath)
            }
            if (handle == 0L) {
                throw EngineException(
                    "The engine could not load ${file.name}. The file may be corrupt " +
                        "or written by an incompatible version."
                )
            }
            val config = ModelConfig.fromNative(NativeBridge.nativeConfig(handle))
                ?: run {
                    NativeBridge.nativeFree(handle)
                    throw EngineException("The engine returned no configuration.")
                }
            return BenchEngine(handle, config, file, kind)
        }
    }
}

class EngineException(message: String) : Exception(message)
