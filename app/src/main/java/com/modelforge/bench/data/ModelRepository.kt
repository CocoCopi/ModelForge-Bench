package com.modelforge.bench.data

import android.content.Context
import android.net.Uri
import com.modelforge.bench.core.Hashing
import com.modelforge.bench.model.BpeTokenizer
import com.modelforge.bench.model.ModelConfig
import com.modelforge.bench.model.ModelFileFormat
import com.modelforge.bench.model.ModelFileKind
import com.modelforge.bench.model.SourceManifest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile

class ModelImportException(message: String) : Exception(message)

/**
 * Installs models into `filesDir/models/<id>/` atomically. A partial import can
 * never be listed: everything is written to a hidden staging directory, the
 * header and the tokenizer are validated, and only then is the directory
 * renamed into place.
 */
class ModelRepository(private val context: Context) {

    private val root = File(context.filesDir, "models")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun models(): List<InstalledModel> {
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val meta = File(dir, META)
            if (!meta.isFile) return@mapNotNull null
            runCatching { json.decodeFromString<InstalledModel>(meta.readText()) }.getOrNull()
        }.sortedByDescending { it.importedAt }
    }

    fun find(id: String): InstalledModel? = models().firstOrNull { it.id == id }

    fun weightsFile(model: InstalledModel): File =
        File(modelDir(model.id), model.weightsFile)

    fun tokenizerFile(model: InstalledModel): File? =
        model.tokenizerFile?.let { File(modelDir(model.id), it) }?.takeIf { it.isFile }

    fun manifestFile(model: InstalledModel): File? =
        model.manifestFile?.let { File(modelDir(model.id), it) }?.takeIf { it.isFile }

    fun modelDir(id: String): File = File(root, id)

    fun delete(id: String): Boolean = runCatching { modelDir(id).deleteRecursively() }.getOrDefault(false)

    fun tokenizer(model: InstalledModel): BpeTokenizer? =
        tokenizerFile(model)?.let { runCatching { BpeTokenizer.load(it) }.getOrNull() }

    /** Imports a `.mfbench` zip bundle (what ModelForge shares). */
    fun importBundle(
        bundle: File,
        origin: ModelOrigin,
        sourceId: String = "",
        nameOverride: String? = null,
        preferPacked: Boolean = true,
        onProgress: (Float) -> Unit = {}
    ): InstalledModel {
        if (!bundle.isFile) throw ModelImportException("The bundle was not found.")
        if (bundle.length() > MAX_BUNDLE_BYTES) {
            throw ModelImportException("The bundle is larger than ${MAX_BUNDLE_BYTES / (1 shl 30)} GB.")
        }
        val staging = File(root, ".bundle-" + UUID.randomUUID())
        staging.mkdirs()
        try {
            extractSafely(bundle, staging, onProgress)
            val files = staging.walkTopDown().filter { it.isFile }.toList()
            val manifest = files.firstOrNull {
                it.name.equals("model.json", true) || it.name.equals("manifest.json", true)
            }
            val tokenizer = files.firstOrNull { it.name.equals("tokenizer.json", true) }
            val checkpoints = files.filter { it.extension.equals("mfck", true) }
            val packed = files.filter { it.extension.equals("mfpk", true) }
            val weights = pickWeights(checkpoints, packed, preferPacked)
                ?: throw ModelImportException(
                    "The bundle has no .mfck checkpoint or .mfpk packed model."
                )
            return installFromFiles(
                weights = weights,
                tokenizer = tokenizer,
                manifest = manifest,
                origin = origin,
                sourceId = sourceId,
                nameOverride = nameOverride,
                onProgress = { onProgress(0.8f + it * 0.2f) }
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Imports a single weights file. A tokenizer and manifest are optional, but
     * quality benchmarks stay disabled until a tokenizer is present.
     */
    fun importWeights(
        input: InputStream,
        displayName: String,
        origin: ModelOrigin,
        sourceId: String = "",
        tokenizer: InputStream? = null,
        manifest: InputStream? = null,
        onProgress: (Float) -> Unit = {}
    ): InstalledModel {
        val staging = File(root, ".incoming-" + UUID.randomUUID())
        staging.mkdirs()
        try {
            val weights = File(staging, "weights" + guessExtension(displayName))
            copy(input, weights, onProgress)
            val tok = tokenizer?.let { copy(it, File(staging, "tokenizer.json")) }
            val man = manifest?.let { copy(it, File(staging, "model.json")) }
            return finalize(staging, weights, tok, man, origin, sourceId, displayName, emptyList())
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    fun importWeightsFile(
        file: File,
        origin: ModelOrigin,
        sourceId: String = "",
        onProgress: (Float) -> Unit = {}
    ): InstalledModel = file.inputStream().use {
        importWeights(it, file.name, origin, sourceId, onProgress = onProgress)
    }

    private fun installFromFiles(
        weights: File,
        tokenizer: File?,
        manifest: File?,
        origin: ModelOrigin,
        sourceId: String,
        nameOverride: String?,
        onProgress: (Float) -> Unit
    ): InstalledModel {
        val staging = File(root, ".incoming-" + UUID.randomUUID())
        staging.mkdirs()
        try {
            val weightCopy = File(staging, "weights" + weights.extension.let { if (it.isBlank()) "" else ".$it" })
            weights.inputStream().use { copy(it, weightCopy, onProgress) }
            val tok = tokenizer?.let { src ->
                File(staging, "tokenizer.json").also { dest -> src.inputStream().use { copy(it, dest) } }
            }
            val man = manifest?.let { src ->
                File(staging, "model.json").also { dest -> src.inputStream().use { copy(it, dest) } }
            }
            return finalize(staging, weightCopy, tok, man, origin, sourceId,
                nameOverride ?: weights.nameWithoutExtension, emptyList())
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun finalize(
        staging: File,
        weights: File,
        tokenizer: File?,
        manifest: File?,
        origin: ModelOrigin,
        sourceId: String,
        suggestedName: String,
        notes: List<String>
    ): InstalledModel {
        val info = ModelFileFormat.inspect(weights)
        val notesOut = ArrayList(notes)

        var sourceManifest: SourceManifest? = null
        if (manifest != null) {
            sourceManifest = runCatching {
                json.decodeFromString<SourceManifest>(manifest.readText())
            }.getOrNull()
        }

        // The tokenizer must describe exactly the vocabulary the weights use.
        var tokenizerOk = false
        if (tokenizer != null) {
            val tokenizerJson = runCatching { BpeTokenizer.fromJson(tokenizer.readText()) }.getOrNull()
            if (tokenizerJson == null) {
                notesOut.add("The tokenizer file could not be parsed; quality tests are disabled.")
            } else if (tokenizerJson.vocabSize != info.config.vocabSize) {
                notesOut.add(
                    "Tokenizer vocabulary (${tokenizerJson.vocabSize}) does not match the model " +
                        "(${info.config.vocabSize}); quality tests are disabled."
                )
            } else {
                tokenizerOk = true
            }
        } else {
            notesOut.add("No tokenizer was supplied; quality tests are disabled.")
        }

        if (info.kind == ModelFileKind.CHECKPOINT && info.hasOptimizer) {
            notesOut.add("This checkpoint also contains AdamW optimizer state; it is used read-only.")
        }

        val baseName = sourceManifest?.name?.takeIf { it.isNotBlank() }
            ?: suggestedName.removeSuffix(".mfck").removeSuffix(".mfpk").removeSuffix(".zip")
                .ifBlank { "Model" }
        val name = baseName.take(80)
        val id = slug(name) + "-" + System.currentTimeMillis()
        val digest = Hashing.sha256(weights)
        val model = InstalledModel(
            id = id,
            name = name,
            origin = origin,
            sourceId = sourceId,
            creator = sourceManifest?.creator.orEmpty(),
            modelCreatedAt = sourceManifest?.createdAt ?: 0L,
            importedAt = System.currentTimeMillis(),
            weightsFile = weights.name,
            tokenizerFile = if (tokenizerOk) tokenizer?.name else null,
            manifestFile = manifest?.name,
            kind = if (info.kind == ModelFileKind.PACKED) WeightsKind.PACKED else WeightsKind.CHECKPOINT,
            config = info.config,
            paramCount = info.paramCount,
            bytes = weights.length(),
            sha256 = digest,
            withOptimizer = info.hasOptimizer,
            bits = info.bits,
            group = info.group,
            sourceManifest = sourceManifest,
            notes = notesOut
        )
        File(staging, META).writeText(json.encodeToString(model), Charsets.UTF_8)
        val target = modelDir(id)
        root.mkdirs()
        if (!staging.renameTo(target)) {
            // Cross-directory rename can fail on some filesystems; copy and
            // only remove the source after the destination is complete.
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        return model
    }

    private fun pickWeights(
        checkpoints: List<File>,
        packed: List<File>,
        preferPacked: Boolean
    ): File? {
        if (preferPacked && packed.isNotEmpty()) {
            return packed.maxByOrNull { it.nameWithoutExtension.filter(Char::isDigit).toIntOrNull() ?: 0 }
                ?: packed.first()
        }
        val best = checkpoints.firstOrNull { it.name.contains("best", true) }
        val any = checkpoints.firstOrNull { !it.name.contains("last", true) }
        if (preferPacked) return packed.firstOrNull() ?: best ?: any ?: checkpoints.firstOrNull()
        return best ?: any ?: checkpoints.firstOrNull() ?: packed.firstOrNull()
    }

    private fun extractSafely(zip: File, target: File, onProgress: (Float) -> Unit) {
        ZipFile(zip).use { archive ->
            val entries = archive.entries().toList()
            if (entries.size > MAX_ENTRIES) throw ModelImportException("The bundle has too many files.")
            var declared = 0L
            for (entry in entries) {
                if (entry.isDirectory) continue
                declared += entry.size.coerceAtLeast(0L)
                if (declared > MAX_UNCOMPRESSED_BYTES) {
                    throw ModelImportException("The bundle expands to more than 4 GB.")
                }
            }
            var written = 0L
            val total = entries.sumOf { it.size.coerceAtLeast(0L) }.coerceAtLeast(1L)
            for (entry in entries) {
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("/") || name.contains("..") || name.contains(":")) {
                    throw ModelImportException("The bundle contains an unsafe path.")
                }
                val dest = File(target, name)
                if (!dest.canonicalPath.startsWith(target.canonicalPath)) {
                    throw ModelImportException("The bundle contains an unsafe path.")
                }
                if (entry.isDirectory) {
                    dest.mkdirs()
                    continue
                }
                dest.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress((written.toDouble() / total).toFloat().coerceIn(0f, 0.8f))
                        }
                    }
                }
            }
        }
    }

    private fun copy(input: InputStream, target: File, onProgress: (Float) -> Unit = {}): File {
        target.parentFile?.mkdirs()
        input.use { source ->
            target.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
        onProgress(0.5f)
        return target
    }

    private fun guessExtension(name: String): String = when {
        name.endsWith(".mfpk", true) -> ".mfpk"
        name.endsWith(".mfck", true) -> ".mfck"
        else -> ".bin"
    }

    private fun slug(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "model" }
        .take(32)

    companion object {
        private const val META = "bench_model.json"
        private const val MAX_BUNDLE_BYTES = 3L * 1024 * 1024 * 1024
        private const val MAX_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_ENTRIES = 2_000
    }
}
