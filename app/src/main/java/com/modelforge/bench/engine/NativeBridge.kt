package com.modelforge.bench.engine

/**
 * Raw JNI surface. Only [BenchEngine] should call this; every method that
 * takes a handle is an inference entry point and is guarded by a Kotlin lock.
 */
internal object NativeBridge {
    init {
        System.loadLibrary("modelforgebench")
    }

    external fun nativeVersion(): String

    external fun nativeLoadCheckpoint(path: String, withOptimizer: Boolean): Long
    external fun nativeLoadPacked(path: String): Long
    external fun nativeFree(handle: Long)

    external fun nativeConfig(handle: Long): IntArray?
    external fun nativeParamCount(handle: Long): Long

    external fun nativeLoss(
        handle: Long,
        tokens: IntArray,
        targets: IntArray,
        batch: Int,
        seq: Int
    ): Float

    external fun nativeContinueLogProb(
        handle: Long,
        prefix: IntArray,
        continuation: IntArray
    ): Float

    external fun nativeTokenLogProbs(
        handle: Long,
        prefix: IntArray,
        continuation: IntArray
    ): FloatArray?

    external fun nativeGenerateTimed(
        handle: Long,
        prompt: IntArray,
        maxNew: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Long,
        timingOut: LongArray
    ): IntArray?

    external fun nativeAbort(handle: Long): Boolean
    external fun nativeClearAbort(handle: Long): Boolean
    external fun nativeLastError(handle: Long): String?
}
