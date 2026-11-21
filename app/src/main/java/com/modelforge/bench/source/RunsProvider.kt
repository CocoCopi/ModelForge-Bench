package com.modelforge.bench.source

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.modelforge.bench.data.ModelRepository
import com.modelforge.bench.data.RunStore

/**
 * Read-only run history for ModelForge.
 *
 * ModelForge uses this to show every version in a series next to its measured
 * scores and to suggest what the next version should improve. Only completed,
 * aggregated numbers are exposed; raw generation samples and device data stay
 * inside Bench. The provider is guarded by a dangerous permission and rejects
 * every write.
 */
class RunsProvider : ContentProvider() {

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "runs", RUNS)
        addURI(AUTHORITY, "runs/*", RUN)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val context = context ?: throw IllegalStateException("Provider is not attached.")
        val runs = RunStore(context).runs()
        val repository = ModelRepository(context)
        val wanted = when (matcher.match(uri)) {
            RUNS -> runs
            RUN -> {
                val id = uri.pathSegments.getOrNull(1)
                runs.filter { it.id == id }
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        val cursor = MatrixCursor(COLUMNS)
        wanted.forEach { run ->
            val sourceId = runCatching { repository.find(run.modelId)?.sourceId }.getOrNull().orEmpty()
            val weakest = run.score?.parts?.minByOrNull { it.score }
            cursor.addRow(
                arrayOf(
                    run.id,
                    run.modelId,
                    run.modelName,
                    sourceId,
                    run.modelSha256,
                    run.finishedAt,
                    run.mode.name,
                    run.status.name,
                    run.score?.composite ?: -1.0,
                    run.score?.grade ?: "",
                    run.quality?.multipleChoice?.accuracy ?: -1.0,
                    run.quality?.perplexity?.perplexity ?: -1.0,
                    run.performance?.decode?.p50 ?: -1.0,
                    run.cost?.deviceCostPerMillion ?: -1.0,
                    weakest?.name ?: "",
                    weakest?.score ?: -1.0,
                    run.score?.verdict ?: "",
                    run.durationMs
                )
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        RUNS -> "vnd.android.cursor.dir/vnd.com.modelforge.bench.runs"
        RUN -> "vnd.android.cursor.item/vnd.com.modelforge.bench.runs"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.modelforge.bench.runs"
        const val PERMISSION = "com.modelforge.bench.permission.READ_RUNS"
        private const val RUNS = 1
        private const val RUN = 2

        /** Column names are the ModelForge contract; do not rename them. */
        val COLUMNS = arrayOf(
            "id",
            "model_id",
            "model_name",
            "source_id",
            "model_sha256",
            "finished_at",
            "mode",
            "status",
            "composite",
            "grade",
            "mc_accuracy",
            "perplexity",
            "decode_tps",
            "cost_per_million",
            "weakest_part",
            "weakest_score",
            "verdict",
            "duration_ms"
        )

        const val STATUS_COMPLETED = "COMPLETED"
    }
}
