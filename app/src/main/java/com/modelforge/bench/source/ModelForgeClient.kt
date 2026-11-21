package com.modelforge.bench.source

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.io.InputStream

/**
 * Client for the read-only content provider shipped by ModelForge 2.4.1+.
 *
 * The provider is protected by `com.modelforge.app.permission.READ_MODELS`.
 * Bench asks for it once; after that, models appear automatically and can be
 * streamed without the user picking files. If ModelForge is missing or older,
 * [availability] says so and the UI falls back to importing a file or bundle.
 */
class ModelForgeClient(private val context: Context) {

    enum class Availability {
        READY,
        NOT_INSTALLED,
        NEEDS_PERMISSION,
        NOT_SUPPORTED,
        ERROR
    }

    data class ForgeModel(
        val id: String,
        val name: String,
        val creator: String,
        val createdAt: Long,
        val params: Long,
        val hasBest: Boolean,
        val hasLast: Boolean,
        val bestBytes: Long,
        val lastBytes: Long,
        val tokenizerBytes: Long,
        val packedBits: Int,
        val packedBytes: Long
    ) {
        val bestWeightsBytes: Long get() = if (hasBest) bestBytes else lastBytes
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrElse { false }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun availability(): Availability {
        if (!isInstalled()) return Availability.NOT_INSTALLED
        if (!hasPermission()) return Availability.NEEDS_PERMISSION
        return try {
            if (list().isEmpty()) Availability.NOT_SUPPORTED else Availability.READY
        } catch (_: SecurityException) {
            Availability.NEEDS_PERMISSION
        } catch (_: IllegalArgumentException) {
            Availability.NOT_SUPPORTED
        } catch (_: Exception) {
            Availability.ERROR
        }
    }

    fun list(): List<ForgeModel> {
        val uri = Uri.parse("content://$AUTHORITY/models")
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idxId = cursor.getColumnIndexOrThrow("id")
            val idxName = cursor.getColumnIndexOrThrow("name")
            val idxCreator = cursor.getColumnIndex("creator")
            val idxCreated = cursor.getColumnIndex("created")
            val idxParams = cursor.getColumnIndex("params")
            val idxBest = cursor.getColumnIndex("has_best")
            val idxLast = cursor.getColumnIndex("has_last")
            val idxBestBytes = cursor.getColumnIndex("best_bytes")
            val idxLastBytes = cursor.getColumnIndex("last_bytes")
            val idxTokBytes = cursor.getColumnIndex("tokenizer_bytes")
            val idxPackedBits = cursor.getColumnIndex("packed_bits")
            val idxPackedBytes = cursor.getColumnIndex("packed_bytes")
            val out = ArrayList<ForgeModel>(cursor.count)
            while (cursor.moveToNext()) {
                out.add(
                    ForgeModel(
                        id = cursor.getString(idxId),
                        name = cursor.getString(idxName) ?: cursor.getString(idxId),
                        creator = idxCreator?.let { cursor.getString(it) }.orEmpty(),
                        createdAt = idxCreated?.let { cursor.getLong(it) } ?: 0L,
                        params = idxParams?.let { cursor.getLong(it) } ?: 0L,
                        hasBest = idxBest?.let { cursor.getInt(it) } == 1,
                        hasLast = idxLast?.let { cursor.getInt(it) } == 1,
                        bestBytes = idxBestBytes?.let { cursor.getLong(it) } ?: 0L,
                        lastBytes = idxLastBytes?.let { cursor.getLong(it) } ?: 0L,
                        tokenizerBytes = idxTokBytes?.let { cursor.getLong(it) } ?: 0L,
                        packedBits = idxPackedBits?.let { cursor.getInt(it) } ?: 0,
                        packedBytes = idxPackedBytes?.let { cursor.getLong(it) } ?: 0L
                    )
                )
            }
            out
        } ?: emptyList()
    }

    fun openManifest(id: String): InputStream? = open("models/$id/manifest")

    fun openTokenizer(id: String): InputStream? = open("models/$id/tokenizer")

    fun openCheckpoint(id: String, which: String = "best"): InputStream? =
        open("models/$id/checkpoint?which=$which")

    fun openPacked(id: String, bits: Int): InputStream? = open("models/$id/packed?bits=$bits")

    /** Held-out training text, used by the contamination check when present. */
    fun openCorpus(id: String, index: Int): InputStream? = open("models/$id/corpus?index=$index")

    private fun open(path: String): InputStream? = runCatching {
        context.contentResolver.openInputStream(Uri.parse("content://$AUTHORITY/$path"))
    }.getOrNull()

    companion object {
        const val PACKAGE = "com.modelforge.app"
        const val AUTHORITY = "com.modelforge.app.models"
        const val PERMISSION = "com.modelforge.app.permission.READ_MODELS"
    }
}
