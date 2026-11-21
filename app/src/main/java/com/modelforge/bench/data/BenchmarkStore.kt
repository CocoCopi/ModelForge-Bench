package com.modelforge.bench.data

import android.content.Context
import com.modelforge.bench.core.BenchJson
import com.modelforge.bench.eval.BenchmarkImporter
import com.modelforge.bench.eval.BenchmarkPack
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.UUID

@Serializable
data class StoredPackInfo(
    val id: String,
    val name: String,
    val provenance: String,
    val items: Int,
    val categories: List<String>,
    val createdAt: Long
)

/**
 * Imported JSONL benchmarks become canonical pack files under
 * `filesDir/benchmarks/<id>/`. Re-serialising on import means every evaluation
 * runs through the same parser regardless of the source dataset's shape.
 */
class BenchmarkStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "benchmarks")

    fun list(): List<StoredPackInfo> {
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val meta = File(dir, META)
            if (!meta.isFile) return@mapNotNull null
            runCatching { BenchJson.decodeFromString<StoredPackInfo>(meta.readText()) }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun load(id: String): BenchmarkPack? {
        val dir = File(root, id)
        val file = File(dir, PACK)
        if (!file.isFile) return null
        val info = runCatching {
            BenchJson.decodeFromString<StoredPackInfo>(File(dir, META).readText())
        }.getOrNull()
        return runCatching {
            BenchmarkImporter.import(file.readText(Charsets.UTF_8), file.name)
                .copy(name = info?.name ?: file.name, provenance = info?.provenance ?: "Imported pack")
        }.getOrNull()
    }

    fun save(pack: BenchmarkPack): StoredPackInfo {
        val id = "pack-" + UUID.randomUUID().toString().take(8)
        val dir = File(root, id)
        dir.mkdirs()
        val file = File(dir, PACK)
        file.writeText(serialise(pack), Charsets.UTF_8)
        val info = StoredPackInfo(
            id = id,
            name = pack.name,
            provenance = pack.provenance,
            items = pack.items.size,
            categories = pack.categories,
            createdAt = System.currentTimeMillis()
        )
        File(dir, META).writeText(BenchJson.encodeToString(info), Charsets.UTF_8)
        return info
    }

    fun delete(id: String): Boolean = runCatching {
        File(root, id).deleteRecursively()
    }.getOrDefault(false)

    /**
     * The canonical line format is intentionally the same shape Bench accepts,
     * so an exported pack can be edited and imported again.
     */
    fun serialise(pack: BenchmarkPack): String = buildString {
        for (item in pack.items) {
            append(
                buildJsonObject {
                    put("id", JsonPrimitive(item.id))
                    put("category", JsonPrimitive(item.category))
                    put("question", JsonPrimitive(item.question))
                    putJsonArray("choices") { item.choices.forEach { add(JsonPrimitive(it)) } }
                    put("answer", JsonPrimitive(item.answer))
                    put("source", JsonPrimitive(item.source))
                }.toString()
            )
            append('\n')
        }
    }

    fun exportFile(pack: BenchmarkPack, target: File) {
        target.parentFile?.mkdirs()
        target.writeText(serialise(pack), Charsets.UTF_8)
    }

    private companion object {
        const val META = "pack.json"
        const val PACK = "pack.jsonl"
    }
}
