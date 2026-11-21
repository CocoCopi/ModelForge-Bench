package com.modelforge.bench.eval

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * One multiple-choice question in a form every supported dataset can be mapped
 * onto. `answer` is always a zero-based index into [choices], no matter whether
 * the source used a letter, an index, or a label.
 */
data class McItem(
    val id: String,
    val category: String,
    val question: String,
    val choices: List<String>,
    val answer: Int,
    val source: String = "",
    val group: String = ""
)

data class BenchmarkPack(
    val name: String,
    val provenance: String,
    val items: List<McItem>
) {
    val categories: List<String> get() = items.map { it.category }.distinct().sorted()
}

class BenchmarkFormatException(message: String) : Exception(message)

/**
 * Importer for the JSONL formats people actually have on disk. It is a single
 * pass with per-line validation: a bad line names its number and the reason,
 * rather than silently dropping items and shrinking the denominator.
 *
 * Supported shapes:
 *  - Bench native:  {"question","choices":[str],"answer":int,"category"}
 *  - ARC / OpenBookQA: {"question","choices":[{"text","label"}],"answerKey":"A"}
 *  - MMLU:          {"question","choices":[str],"answer":int}
 *  - HellaSwag:     {"ctx","endings":[str],"label":int}
 *  - PIQA:          {"goal","sol1","sol2","label":int}
 *  - Generic:       {"prompt","options":[str],"answer":int}
 */
object BenchmarkImporter {

    fun import(text: String, fileName: String = "benchmark.jsonl"): BenchmarkPack {
        val items = ArrayList<McItem>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
            val element = try {
                Json.parseToJsonElement(line)
            } catch (t: Throwable) {
                throw BenchmarkFormatException(
                    "$fileName line ${index + 1}: not valid JSON (${t.message ?: "parse error"})."
                )
            }
            val obj = element as? JsonObject
                ?: throw BenchmarkFormatException("$fileName line ${index + 1}: expected a JSON object.")
            items.add(parseItem(obj, "$fileName line ${index + 1}"))
        }
        if (items.isEmpty()) {
            throw BenchmarkFormatException("$fileName contains no usable questions.")
        }
        val name = fileName.substringAfterLast('/').removeSuffix(".jsonl").ifBlank { "Imported benchmark" }
        return BenchmarkPack(name, "Imported from $fileName", items)
    }

    fun import(file: File): BenchmarkPack = import(file.readText(Charsets.UTF_8), file.name)

    private fun parseItem(obj: JsonObject, where: String): McItem {
        val answerKey = string(obj, "answerKey")

        val question = firstString(obj, "question", "prompt", "goal", "ctx", "context")
            ?: throw BenchmarkFormatException("$where: no question text.")

        val choicesElement = first(obj, "choices", "options", "endings")
        var choices: List<String> = choicesElement?.let { parseChoices(it, where) } ?: emptyList()

        if (choices.isEmpty()) {
            val sol1 = string(obj, "sol1")
            val sol2 = string(obj, "sol2")
            if (sol1 != null && sol2 != null) choices = listOf(sol1, sol2)
        }
        if (choices.size < 2) {
            throw BenchmarkFormatException("$where: needs at least two choices.")
        }

        val rawAnswer = answerKey ?: string(obj, "answer", "label")
            ?: throw BenchmarkFormatException("$where: no answer or label.")

        val answer = resolveAnswer(rawAnswer, choices)
            ?: throw BenchmarkFormatException(
                "$where: answer '$rawAnswer' does not match any choice."
            )

        val category = firstString(obj, "category", "subject", "topic")?.trim().orEmpty()
            .ifBlank { "general" }
        val id = firstString(obj, "id", "qid")?.trim().orEmpty()
            .ifBlank { "item-$where".replace(Regex("[^A-Za-z0-9-]"), "-") }
        val source = firstString(obj, "source", "dataset").orEmpty()

        return McItem(
            id = id,
            category = category,
            question = question.trim(),
            choices = choices.map { it.trim() },
            answer = answer,
            source = source
        )
    }

    private fun parseChoices(element: JsonElement, where: String): List<String> {
        val array = element as? JsonArray
            ?: throw BenchmarkFormatException("$where: choices must be an array.")
        return array.map { choice ->
            when (choice) {
                is JsonPrimitive -> choice.content
                is JsonObject -> {
                    // ARC stores {"text": ..., "label": ...}.
                    (choice["text"] as? JsonPrimitive)?.content
                        ?: (choice["choice"] as? JsonPrimitive)?.content
                        ?: throw BenchmarkFormatException("$where: choice object has no text.")
                }
                else -> throw BenchmarkFormatException("$where: unsupported choice value.")
            }
        }
    }

    private fun resolveAnswer(raw: String, choices: List<String>): Int? {
        raw.toIntOrNull()?.let { index ->
            return if (index in choices.indices) index else null
        }
        val trimmed = raw.trim()
        if (trimmed.length == 1) {
            val upper = trimmed.uppercase()[0]
            if (upper in 'A'..'Z') {
                val index = upper - 'A'
                if (index in choices.indices) return index
            }
        }
        val exact = choices.indexOfFirst { it.equals(trimmed, ignoreCase = true) }
        if (exact >= 0) return exact
        // Some datasets wrap the answer in extra punctuation; fall back to a
        // normalised comparison without touching the stored choice text.
        val normalised = normalise(trimmed)
        val loose = choices.indexOfFirst { normalise(it) == normalised }
        return if (loose >= 0) loose else null
    }

    private fun normalise(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun first(obj: JsonObject, vararg keys: String): JsonElement? {
        for (key in keys) obj[key]?.let { if (it !is JsonPrimitive || it.content.isNotBlank()) return it }
        return null
    }

    private fun string(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val element = obj[key] as? JsonPrimitive ?: continue
            if (element.isString || element.intOrNull != null) return element.content
        }
        return null
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? = string(obj, *keys)
}

/**
 * Prompt conventions shared by every multiple-choice run, documented in the
 * report so a reader can reproduce the exact string the model saw.
 */
object McPrompt {
    fun text(item: McItem): String = buildString {
        append("Question: ")
        append(item.question.trim())
        append('\n')
        item.choices.forEachIndexed { index, choice ->
            append(('A' + index))
            append(". ")
            append(choice.trim())
            append('\n')
        }
        append("Answer:")
    }

    fun continuation(choice: String): String = " " + choice.trim()
}

object BenchmarkPacks {
    const val SMOKE_ASSET = "benchmarks/commonsense_smoke.jsonl"

    /**
     * The bundled pack is authored for Bench (balanced answer positions, four
     * everyday categories). It is a smoke test, not a substitute for a public
     * benchmark; the report says so explicitly.
     */
    fun smoke(context: Context): BenchmarkPack = context.assets.open(SMOKE_ASSET)
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
        .let { BenchmarkImporter.import(it, "commonsense_smoke.jsonl") }
            .copy(
                name = "Bench smoke pack",
                provenance = "Authored for ModelForge Bench (34 items, balanced answers). " +
                    "Suitable for sanity checks, not for model ranking."
            )
}
