package com.modelforge.bench.data

import android.content.Context
import com.modelforge.bench.core.BenchJson
import com.modelforge.bench.eval.BenchRun
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Runs live in `filesDir/runs/<id>/run.json`; generated reports sit beside them
 * in `reports/`. Writes are atomic, so an interrupted save leaves the previous
 * complete run intact rather than a half-written JSON file.
 */
class RunStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "runs")

    fun runs(): List<BenchRun> {
        val dirs = root.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?: return emptyList()
        return dirs.mapNotNull { dir -> loadFile(File(dir, RUN_FILE)) }
            .sortedByDescending { it.startedAt }
    }

    fun load(id: String): BenchRun? = loadFile(File(File(root, id), RUN_FILE))

    fun save(run: BenchRun): File {
        val dir = File(root, run.id)
        dir.mkdirs()
        val target = File(dir, RUN_FILE)
        val tmp = File(dir, "$RUN_FILE.tmp")
        tmp.writeText(BenchJson.encodeToString(run), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText(), Charsets.UTF_8)
            tmp.delete()
        }
        prune()
        return target
    }

    fun delete(id: String): Boolean = runCatching {
        File(root, id).deleteRecursively()
    }.getOrDefault(false)

    fun reportDir(id: String): File = File(File(root, id), "reports").apply { mkdirs() }

    fun runDir(id: String): File = File(root, id)

    private fun loadFile(file: File): BenchRun? {
        if (!file.isFile) return null
        return runCatching {
            BenchJson.decodeFromString<BenchRun>(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun prune() {
        val dirs = root.listFiles { file -> file.isDirectory } ?: return
        if (dirs.size <= MAX_RUNS) return
        dirs.sortedBy { it.lastModified() }
            .take(dirs.size - MAX_RUNS)
            .forEach { it.deleteRecursively() }
    }

    companion object {
        private const val RUN_FILE = "run.json"
        private const val MAX_RUNS = 500
    }
}
