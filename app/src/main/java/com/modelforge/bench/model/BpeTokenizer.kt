package com.modelforge.bench.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File

@Serializable
data class TokenizerData(
    val version: Int = 1,
    val merges: List<List<Int>> = emptyList()
)

/**
 * Byte-level BPE, byte-for-byte compatible with ModelForge's tokenizer.
 * The first 256 ids are raw bytes and merge `i` gets id `256 + i`, so scoring
 * uses exactly the tokenization the model was trained with. A mismatch here
 * would silently change every perplexity number.
 */
class BpeTokenizer private constructor(
    private val mergePairs: List<Pair<Int, Int>>
) {
    val vocabSize: Int get() = BASE + mergePairs.size
    val mergeCount: Int get() = mergePairs.size

    private val ranks: HashMap<Long, Int> = HashMap<Long, Int>(mergePairs.size * 2).apply {
        mergePairs.forEachIndexed { index, (a, b) -> put(key(a, b), index) }
    }

    private val tokenBytes: Array<ByteArray?> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val table = arrayOfNulls<ByteArray>(vocabSize)
        for (i in 0 until 256) table[i] = byteArrayOf(i.toByte())
        mergePairs.forEachIndexed { index, (a, b) ->
            val left = table.getOrNull(a)
            val right = table.getOrNull(b)
            if (left != null && right != null) table[BASE + index] = left + right
        }
        table
    }

    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val out = GrowableInts(text.length)
        for (chunk in splitChunks(text)) {
            val bytes = chunk.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) continue
            var symbols = IntArray(bytes.size) { bytes[it].toInt() and 0xff }
            while (symbols.size > 1) {
                var bestRank = Int.MAX_VALUE
                var bestPair = 0L
                for (i in 0 until symbols.size - 1) {
                    val key = key(symbols[i], symbols[i + 1])
                    val rank = ranks[key] ?: continue
                    if (rank < bestRank) {
                        bestRank = rank
                        bestPair = key
                    }
                }
                if (bestRank == Int.MAX_VALUE) break
                val a = (bestPair ushr 32).toInt()
                val b = (bestPair and 0xffffffffL).toInt()
                val merged = IntArray(symbols.size)
                var count = 0
                var i = 0
                while (i < symbols.size) {
                    if (i + 1 < symbols.size && symbols[i] == a && symbols[i + 1] == b) {
                        merged[count++] = BASE + bestRank
                        i += 2
                    } else {
                        merged[count++] = symbols[i]
                        i++
                    }
                }
                symbols = merged.copyOf(count)
            }
            for (symbol in symbols) out.add(symbol)
        }
        return out.toArray()
    }

    fun decode(ids: IntArray): String {
        if (ids.isEmpty()) return ""
        val bytes = ByteArrayOutputStream(ids.size * 2)
        for (id in ids) {
            if (id < 0) continue
            val token = if (id < 256) byteArrayOf(id.toByte()) else tokenBytes.getOrNull(id)
            if (token != null) bytes.write(token)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    fun decode(ids: List<Int>): String = decode(ids.toIntArray())

    /** UTF-8 byte length of one token, used for tokenizer-independent BPB. */
    fun tokenByteSize(id: Int): Int = when {
        id in 0..255 -> 1
        else -> tokenBytes.getOrNull(id)?.size ?: 0
    }

    fun toJson(): String = json.encodeToString(
        TokenizerData(merges = mergePairs.map { listOf(it.first, it.second) })
    )

    /** Fraction of raw UTF-8 bytes saved by tokenization. Higher is cheaper. */
    fun compressionRatio(text: String): Double {
        val raw = text.toByteArray(Charsets.UTF_8).size
        if (raw == 0) return 0.0
        return 1.0 - encode(text).size.toDouble() / raw.toDouble()
    }

    /**
     * Longest prefix of [text] that encodes to at most [maxTokens] tokens.
     * Used to cut held-out text on token boundaries instead of mid-byte.
     */
    fun prefixWithin(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        val ids = encode(text)
        if (ids.size <= maxTokens) return text
        // Decoding an exact prefix of the token stream is both faster and more
        // faithful than re-encoding substrings until one fits.
        return decode(IntArray(maxTokens) { ids[it] })
    }

    override fun toString(): String = "BpeTokenizer(vocab=$vocabSize, merges=$mergeCount)"

    companion object {
        const val BASE = 256

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private fun key(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

        private fun splitChunks(text: String): Sequence<String> = sequence {
            if (text.isEmpty()) return@sequence
            var start = 0
            var currentWhitespace = text[0].isWhitespace()
            for (i in 1 until text.length) {
                val ws = text[i].isWhitespace()
                if (ws != currentWhitespace) {
                    yield(text.substring(start, i))
                    start = i
                    currentWhitespace = ws
                }
            }
            yield(text.substring(start))
        }

        fun fromJson(text: String): BpeTokenizer {
            val data = json.decodeFromString<TokenizerData>(text)
            val merges = ArrayList<Pair<Int, Int>>(data.merges.size)
            for ((index, pair) in data.merges.withIndex()) {
                require(pair.size == 2) { "merge $index is not a pair" }
                val a = pair[0]
                val b = pair[1]
                require(a >= 0 && a < BASE + index) { "merge $index refers to a future token" }
                require(b >= 0 && b < BASE + index) { "merge $index refers to a future token" }
                merges.add(a to b)
            }
            return BpeTokenizer(merges)
        }

        fun load(file: File): BpeTokenizer = fromJson(file.readText(Charsets.UTF_8))
    }

    private class GrowableInts(capacity: Int) {
        private var data = IntArray(maxOf(16, capacity))
        private var size = 0

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): IntArray = data.copyOf(size)
    }
}
