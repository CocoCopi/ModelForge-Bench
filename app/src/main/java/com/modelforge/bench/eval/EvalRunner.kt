package com.modelforge.bench.eval

import android.content.Context
import com.modelforge.bench.core.DeviceProfile
import com.modelforge.bench.core.currentRssBytes
import com.modelforge.bench.data.InstalledModel
import com.modelforge.bench.data.ModelRepository
import com.modelforge.bench.data.WeightsKind
import com.modelforge.bench.engine.BenchEngine
import com.modelforge.bench.model.BpeTokenizer
import com.modelforge.bench.model.ModelFileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.exp
import kotlin.random.Random

/**
 * Runs the full commercial suite against one installed model.
 *
 * Sections are isolated: a model without a tokenizer still gets performance,
 * energy and cost numbers, and a failed robustness pass does not discard a
 * completed perplexity result. Warnings travel with the run and appear in the
 * report, so a partial result is never presented as a complete one.
 */
class EvalRunner(
    private val context: Context,
    private val repository: ModelRepository
) {

    data class Progress(val stage: String, val fraction: Double, val detail: String = "")

    private data class Verdict(val item: McItem, val predicted: Int, val confidence: Double)

    suspend fun run(
        model: InstalledModel,
        settings: RunSettings,
        mode: RunMode,
        costSettings: CostSettings = CostSettings(),
        benchmarkPack: BenchmarkPack? = null,
        onProgress: (Progress) -> Unit = {}
    ): BenchRun {
        val startedAt = System.currentTimeMillis()
        val startNanos = System.nanoTime()
        val runId = "run-$startedAt-" + Random.nextInt(1000, 9999)
        val device = DeviceProfile.collect(context)
        val warnings = ArrayList<String>()
        var engine: BenchEngine? = null

        try {
            onProgress(Progress("Loading model", 0.01))
            val weights = repository.weightsFile(model)
            if (!weights.isFile) {
                throw IllegalStateException(
                    "The model file is missing. Delete and re-import this model."
                )
            }
            val loaded = withContext(Dispatchers.IO) {
                val t0 = System.nanoTime()
                val kind = if (model.kind == WeightsKind.CHECKPOINT) {
                    ModelFileKind.CHECKPOINT
                } else {
                    ModelFileKind.PACKED
                }
                val opened = BenchEngine.open(weights, kind)
                opened to (System.nanoTime() - t0) / 1_000_000.0
            }
            engine = loaded.first
            val loadMs = loaded.second
            val config = engine.config
            val rssAfterLoad = currentRssBytes()

            val tokenizer = withContext(Dispatchers.IO) { repository.tokenizer(model) }
            var usableTokenizer = tokenizer
            if (tokenizer == null) {
                warnings.add("No tokenizer: the quality suite was skipped.")
            } else if (tokenizer.vocabSize != config.vocabSize) {
                warnings.add(
                    "Tokenizer vocabulary (${tokenizer.vocabSize}) does not match the model " +
                        "(${config.vocabSize}): the quality suite was skipped."
                )
                usableTokenizer = null
            }

            var quality: QualityResult? = null
            if (usableTokenizer != null) {
                quality = evaluateQuality(
                    engine = engine,
                    tokenizer = usableTokenizer,
                    config = config,
                    settings = settings,
                    mode = mode,
                    benchmarkPack = benchmarkPack,
                    warnings = warnings,
                    onProgress = onProgress
                )
            }

            onProgress(Progress("Measuring throughput", 0.72))
            val perfAndEnergy = evaluatePerformance(
                engine = engine,
                tokenizer = usableTokenizer,
                config = config,
                settings = settings,
                loadMs = loadMs,
                rssAfterLoad = rssAfterLoad,
                weightsBytes = model.bytes,
                warnings = warnings,
                onProgress = onProgress
            )
            val performance = perfAndEnergy.first
            val energy = perfAndEnergy.second

            val qualityScore = Scoring.qualityScore(quality)
            val cost = CostModel.compute(energy, performance, qualityScore, costSettings)
            val score = Scoring.score(quality, performance, energy, cost)

            if (settings.mcLimit in 1..99) {
                warnings.add(
                    "Multiple-choice accuracy used ${settings.mcLimit} items; " +
                        "the confidence interval shows how wide that estimate is."
                )
            }

            onProgress(Progress("Finishing", 0.99))
            val finishedAt = System.currentTimeMillis()
            return BenchRun(
                id = runId,
                modelId = model.id,
                modelName = model.name,
                modelSha256 = model.sha256,
                modelOrigin = model.origin.name,
                config = config,
                paramCount = model.paramCount,
                weightsKind = model.displayKind,
                bits = model.bits,
                device = device,
                mode = mode,
                seed = settings.seed,
                settings = settings,
                startedAt = startedAt,
                finishedAt = finishedAt,
                durationMs = (System.nanoTime() - startNanos) / 1_000_000,
                status = RunStatus.COMPLETED,
                engineVersion = BenchEngine.engineVersion,
                quality = quality,
                performance = performance,
                energy = energy,
                cost = cost,
                score = score,
                warnings = warnings.distinct()
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val message = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
            return BenchRun(
                id = runId,
                modelId = model.id,
                modelName = model.name,
                modelSha256 = model.sha256,
                modelOrigin = model.origin.name,
                config = model.config,
                paramCount = model.paramCount,
                weightsKind = model.displayKind,
                bits = model.bits,
                device = device,
                mode = mode,
                seed = settings.seed,
                settings = settings,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                durationMs = (System.nanoTime() - startNanos) / 1_000_000,
                status = RunStatus.FAILED,
                engineVersion = BenchEngine.engineVersion,
                error = "Evaluation failed: $message",
                warnings = warnings
            )
        } finally {
            engine?.close()
        }
    }

    // -----------------------------------------------------------------------
    // Quality
    // -----------------------------------------------------------------------

    private suspend fun evaluateQuality(
        engine: BenchEngine,
        tokenizer: BpeTokenizer,
        config: com.modelforge.bench.model.ModelConfig,
        settings: RunSettings,
        mode: RunMode,
        benchmarkPack: BenchmarkPack?,
        warnings: MutableList<String>,
        onProgress: (Progress) -> Unit
    ): QualityResult {
        val texts = withContext(Dispatchers.IO) { corpusTexts() }
        val windowTokens = if (settings.contextTokens > 0) settings.contextTokens
        else minOf(config.contextLength, 256).coerceAtLeast(16)

        val perplexity = runCatching {
            onProgress(Progress("Scoring held-out text", 0.06))
            evaluatePerplexity(engine, tokenizer, texts, windowTokens, settings.corpusTokenLimit)
        }.onFailure { warnings.add("Perplexity failed: ${it.message ?: "unknown error"}") }
            .getOrNull()
        if (perplexity == null) {
            warnings.add("Perplexity could not be computed from the available held-out text.")
        } else if (perplexity.windows < 5) {
            warnings.add(
                "Perplexity used only ${perplexity.windows} windows; treat the interval as wide."
            )
        }

        val pack = benchmarkPack ?: runCatching { BenchmarkPacks.smoke(context) }.getOrNull()
        var mc: McResult? = null
        var verdicts: List<Verdict> = emptyList()
        if (pack == null) {
            warnings.add("The bundled benchmark pack could not be read.")
        } else {
            val result = runCatching {
                evaluateMultipleChoice(
                    engine, tokenizer, pack, settings.mcLimit, onProgress
                )
            }.onFailure { warnings.add("Multiple-choice suite failed: ${it.message ?: "unknown error"}") }
                .getOrNull()
            if (result != null) {
                mc = result.first
                verdicts = result.second
            }
        }

        val robustness = if (verdicts.isNotEmpty()) {
            runCatching {
                onProgress(Progress("Testing robustness", 0.56))
                evaluateRobustness(engine, tokenizer, verdicts)
            }.onFailure { warnings.add("Robustness suite failed: ${it.message ?: "unknown error"}") }
                .getOrNull()
        } else null

        val contamination = runCatching {
            onProgress(Progress("Checking for memorisation", 0.62))
            evaluateContamination(engine, tokenizer, texts, settings.seed)
        }.onFailure { warnings.add("Contamination check failed: ${it.message ?: "unknown error"}") }
            .getOrNull()

        val generation = runCatching {
            onProgress(Progress("Checking generation", 0.66))
            evaluateGeneration(engine, tokenizer, assetsPrompts(), config.contextLength)
        }.onFailure { warnings.add("Generation check failed: ${it.message ?: "unknown error"}") }
            .getOrNull()

        val tokenizerResult = runCatching {
            tokenizerStats(tokenizer, texts)
        }.getOrNull()

        if (mode == RunMode.SMOKE) {
            warnings.add(
                "Smoke mode: a small item count and short runs. Use Standard or Deep for a " +
                    "number you would quote externally."
            )
        }

        return QualityResult(
            perplexity = perplexity,
            multipleChoice = mc,
            robustness = robustness,
            contamination = contamination,
            generation = generation,
            tokenizer = tokenizerResult,
            warnings = warnings.toList()
        )
    }

    private suspend fun evaluatePerplexity(
        engine: BenchEngine,
        tokenizer: BpeTokenizer,
        texts: List<Pair<String, String>>,
        windowTokens: Int,
        tokenLimit: Int
    ): PerplexityResult? {
        val windowLosses = ArrayList<Double>()
        val windowWeights = ArrayList<Int>()
        var totalNats = 0.0
        var predicted = 0
        var bytes = 0L
        var consumed = 0

        for ((_, text) in texts) {
            currentCoroutineContext().ensureActive()
            val ids = tokenizer.encode(text)
            if (ids.size < 4) continue
            var offset = 0
            while (offset + 2 <= ids.size && consumed < tokenLimit) {
                val end = minOf(ids.size, offset + windowTokens)
                val window = ids.copyOfRange(offset, end)
                if (window.size >= 2) {
                    val input = window.copyOfRange(0, window.size - 1)
                    val target = window.copyOfRange(1, window.size)
                    val loss = engine.loss(input, target, 1, input.size)
                    if (loss.isFinite() && loss >= 0f) {
                        totalNats += loss.toDouble() * input.size
                        var windowBytes = 0L
                        for (i in target.indices) windowBytes += tokenizer.tokenByteSize(target[i])
                        bytes += windowBytes
                        predicted += input.size
                        windowLosses.add(loss.toDouble())
                        windowWeights.add(input.size)
                    }
                    consumed += window.size
                }
                offset = end
            }
        }

        if (predicted < 8 || bytes <= 0L) return null
        val meanNats = totalNats / predicted
        val ci = Metrics.bootstrapMeanCi(windowLosses.toDoubleArray(), seed = 7L)
        return PerplexityResult(
            dataset = texts.joinToString(", ") { it.first },
            tokens = predicted,
            bytes = bytes,
            windows = windowLosses.size,
            meanNatsPerToken = meanNats,
            perplexity = exp(meanNats),
            bitsPerByte = Metrics.bitsPerByte(totalNats, bytes),
            ciLow = exp(ci.low),
            ciHigh = exp(ci.high)
        )
    }

    private suspend fun evaluateMultipleChoice(
        engine: BenchEngine,
        tokenizer: BpeTokenizer,
        pack: BenchmarkPack,
        limit: Int,
        onProgress: (Progress) -> Unit
    ): Pair<McResult, List<Verdict>>? {
        val items = if (limit > 0) pack.items.take(limit) else pack.items
        if (items.isEmpty()) return null
        val verdicts = ArrayList<Verdict>(items.size)
        val confidences = ArrayList<Double>(items.size)
        val correctFlags = ArrayList<Boolean>(items.size)

        for ((index, item) in items.withIndex()) {
            currentCoroutineContext().ensureActive()
            val scores = scoreChoices(engine, tokenizer, McPrompt.text(item), item.choices)
            if (scores == null) continue
            var best = 0
            for (i in scores.indices) if (scores[i] > scores[best]) best = i
            val probabilities = Metrics.softmax(scores)
            val confidence = probabilities[best].coerceIn(0.0, 1.0)
            verdicts.add(Verdict(item, best, confidence))
            confidences.add(confidence)
            correctFlags.add(best == item.answer)
            if (index % 4 == 0) {
                onProgress(
                    Progress(
                        "Multiple-choice questions",
                        0.30 + 0.24 * (index.toDouble() / items.size),
                        "${index + 1}/${items.size}"
                    )
                )
            }
        }

        if (verdicts.isEmpty()) return null
        val correct = correctFlags.count { it }
        val interval = Metrics.wilson(correct, verdicts.size)
        val chance = 1.0 / verdicts.first().item.choices.size
        val categories = verdicts.groupBy { it.item.category }.map { (category, group) ->
            val right = group.count { it.predicted == it.item.answer }
            val ci = Metrics.wilson(right, group.size)
            CategoryScore(category, right, group.size, right.toDouble() / group.size, ci.low, ci.high)
        }.sortedBy { it.category }

        val result = McResult(
            pack = pack.name,
            provenance = pack.provenance,
            items = verdicts.size,
            correct = correct,
            accuracy = correct.toDouble() / verdicts.size,
            ciLow = interval.low,
            ciHigh = interval.high,
            chanceAccuracy = chance,
            scoring = "Choice log-likelihood, normalised per continuation token. " +
                "Prompt ends with \"Answer:\".",
            categories = categories,
            meanConfidence = Metrics.mean(confidences.toDoubleArray()),
            ece = Metrics.ece(confidences.toDoubleArray(), correctFlags.toBooleanArray())
        )
        return result to verdicts
    }

    private suspend fun evaluateRobustness(
        engine: BenchEngine,
        tokenizer: BpeTokenizer,
        base: List<Verdict>
    ): RobustnessResult? {
        val evaluated = base.take(100)
        if (evaluated.isEmpty()) return null

        var reversedCorrect = 0
        var prefixedCorrect = 0
        val prefixedPredictions = IntArray(evaluated.size) { -1 }
        var compared = 0

        for ((index, verdict) in evaluated.withIndex()) {
            currentCoroutineContext().ensureActive()
            val item = verdict.item

            val reversed = item.choices.reversed()
            val reversedScores = scoreChoices(
                engine, tokenizer, McPrompt.text(item.copy(choices = reversed)), reversed
            )
            if (reversedScores != null) {
                var best = 0
                for (i in reversedScores.indices) if (reversedScores[i] > reversedScores[best]) best = i
                val originalIndex = reversed.lastIndex - best
                if (originalIndex == item.answer) reversedCorrect++
            }

            val prefixedPrompt = "Read the question carefully.\n" + McPrompt.text(item)
            val prefixedScores = scoreChoices(engine, tokenizer, prefixedPrompt, item.choices)
            if (prefixedScores != null) {
                var best = 0
                for (i in prefixedScores.indices) if (prefixedScores[i] > prefixedScores[best]) best = i
                prefixedPredictions[index] = best
                if (best == item.answer) prefixedCorrect++
                compared++
            }
        }

        val baseAccuracy = base.count { it.predicted == it.item.answer }.toDouble() / base.size
        val reversedAccuracy = reversedCorrect.toDouble() / evaluated.size
        val prefixedAccuracy = if (compared > 0) prefixedCorrect.toDouble() / compared else Double.NaN

        val basePredictions = IntArray(evaluated.size) { evaluated[it].predicted }
        val flips = Metrics.flipRate(basePredictions, prefixedPredictions)

        return RobustnessResult(
            baseAccuracy = baseAccuracy,
            reversedOrderAccuracy = reversedAccuracy,
            reversedDelta = reversedAccuracy - baseAccuracy,
            prefixedAccuracy = prefixedAccuracy,
            prefixedDelta = if (prefixedAccuracy.isFinite()) prefixedAccuracy - baseAccuracy else Double.NaN,
            flipRate = if (flips.isFinite()) flips else 0.0,
            evaluated = evaluated.size,
            notes = "Reversing the choice order removes position bias. " +
                "The prefix test measures sensitivity to a harmless instruction. " +
                "Evaluated on up to 100 items."
        )
    }

    private suspend fun evaluateContamination(
        engine: BenchEngine,
        tokenizer: BpeTokenizer,
        texts: List<Pair<String, String>>,
        seed: Long
    ): ContaminationResult? {
        val combined = texts.joinToString(" ") { it.second }
        val ids = tokenizer.encode(combined)
        val take = minOf(ids.size, 512)
        if (take < 96) return null
        val window = ids.copyOfRange(0, take)
        val input = window.copyOfRange(0, window.size - 1)
        val target = window.copyOfRange(1, window.size)
        val original = engine.loss(input, target, 1, input.size)
        if (!original.isFinite()) return null

        val shuffled = window.copyOf()
        val random = Random(seed)
        for (i in shuffled.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = tmp
        }
        val shuffledInput = shuffled.copyOfRange(0, shuffled.size - 1)
        val shuffledTarget = shuffled.copyOfRange(1, shuffled.size)
        val perturbed = engine.loss(shuffledInput, shuffledTarget, 1, shuffledInput.size)
        if (!perturbed.isFinite()) return null

        val gap = perturbed.toDouble() - original.toDouble()
        val interpretation = when {
            gap > 0.75 -> "The model fits the held-out text much better than shuffled tokens. " +
                "That is expected for any language model, but a very large gap can indicate " +
                "the text is close to training data. Confirm against the training corpus."
            gap > 0.25 -> "The model shows a normal preference for real text over shuffled tokens."
            else -> "The model barely distinguishes real from shuffled text; it has learned " +
                "little sequential structure."
        }
        return ContaminationResult(
            method = "Held-out text vs. seeded token shuffle (same unigram distribution).",
            originalLoss = original.toDouble(),
            perturbedLoss = perturbed.toDouble(),
            memorizationGap = gap,
            tokens = input.size,
            interpretation = interpretation
        )
    }

    private suspend fun evaluateGeneration(
        engine: BenchEngine,
        tokenizer: BpeTokenizer,
        prompts: List<String>,
        contextLength: Int
    ): GenerationResult? {
        if (prompts.isEmpty()) return null
        val maxNew = minOf(48, (contextLength / 2).coerceAtLeast(8))
        val samples = ArrayList<GenerationSample>(prompts.size)
        val allIds = ArrayList<Int>()

        for (prompt in prompts.take(4)) {
            currentCoroutineContext().ensureActive()
            val ids = tokenizer.encode(prompt)
            if (ids.isEmpty()) continue
            val generation = engine.generate(ids, maxNew, temperature = 0f, topK = 1, seed = 42L)
            val text = tokenizer.decode(generation.ids)
            val stopReason = if (generation.ids.size < maxNew) "context limit" else "max tokens"
            samples.add(GenerationSample(prompt, text, generation.ids.size, stopReason))
            generation.ids.forEach { allIds.add(it) }
            engine.clearStop()
        }
        if (samples.isEmpty()) return null

        val idsArray = allIds.toIntArray()
        val distinct1 = Metrics.distinctN(idsArray, 1)
        val distinct2 = Metrics.distinctN(idsArray, 2)
        val repetition = (1.0 - distinct1).coerceIn(0.0, 1.0)
        val invalid = samples.count { it.output.contains('\uFFFD') }
        return GenerationResult(
            samples = samples,
            distinct1 = distinct1,
            distinct2 = distinct2,
            repetitionRate = repetition,
            averageTokens = samples.map { it.tokens }.average(),
            emptyOutputs = samples.count { it.output.isBlank() },
            invalidText = invalid
        )
    }

    private fun tokenizerStats(tokenizer: BpeTokenizer, texts: List<Pair<String, String>>): TokenizerResult? {
        if (texts.isEmpty()) return null
        var compression = 0.0
        var tokens = 0L
        var words = 0L
        var counted = 0
        for ((_, text) in texts) {
            if (text.isBlank()) continue
            compression += tokenizer.compressionRatio(text)
            tokens += tokenizer.encode(text).size
            words += text.split(Regex("\\s+")).count { it.isNotBlank() }
            counted++
        }
        if (counted == 0 || words == 0L) return null
        return TokenizerResult(
            vocabulary = tokenizer.vocabSize,
            merges = tokenizer.mergeCount,
            compression = compression / counted,
            tokensPerWord = tokens.toDouble() / words,
            samples = counted,
            note = "Compression is the share of raw UTF-8 bytes merged away. " +
                "Fewer tokens per word means lower cost at the same text volume."
        )
    }

    // -----------------------------------------------------------------------
    // Performance and energy
    // -----------------------------------------------------------------------

    private suspend fun evaluatePerformance(
        engine: BenchEngine,
        tokenizer: BpeTokenizer?,
        config: com.modelforge.bench.model.ModelConfig,
        settings: RunSettings,
        loadMs: Double,
        rssAfterLoad: Long,
        weightsBytes: Long,
        warnings: MutableList<String>,
        onProgress: (Progress) -> Unit
    ): Pair<PerformanceResult, EnergyResult> {
        val promptIds = if (tokenizer != null) {
            tokenizer.encode("The quick brown fox jumps over the lazy dog. " +
                "A benchmark should measure work, not luck. ").take(64).toIntArray()
        } else {
            IntArray(32) { (it * 7 + 3) % config.vocabSize.coerceAtLeast(2) }
        }
        val safePrompt = if (promptIds.isEmpty()) IntArray(1) { 1 } else promptIds
        engine.clearStop()

        // Warm up caches and CPU frequency scaling before recording.
        runCatching { engine.generate(safePrompt, 8, 0f, 1, 1f, settings.seed) }

        val ttfts = ArrayList<Double>(settings.perfRuns)
        val decodeRates = ArrayList<Double>(settings.perfRuns)
        val prefillRates = ArrayList<Double>(settings.perfRuns)
        var peakRss = rssAfterLoad
        var measuredTokens = 0
        var sustained: SustainedResult? = null
        var sustainedTokens = 0

        val sampler = PowerSampler(context)
        sampler.start()
        try {
            for (run in 0 until settings.perfRuns.coerceAtLeast(1)) {
                currentCoroutineContext().ensureActive()
                val generation = engine.generate(
                    safePrompt, 32, temperature = 0f, topK = 1, topP = 1f,
                    seed = settings.seed + run
                )
                ttfts.add(generation.timeToFirstTokenMs)
                decodeRates.add(generation.decodeTokensPerSecond)
                if (generation.prefillNanos > 0L) {
                    prefillRates.add(
                        safePrompt.size * 1_000_000_000.0 / generation.prefillNanos
                    )
                }
                measuredTokens += generation.decodeTokens
                peakRss = maxOf(peakRss, currentRssBytes())
                onProgress(
                    Progress(
                        "Measuring throughput",
                        0.72 + 0.14 * ((run + 1).toDouble() / settings.perfRuns.coerceAtLeast(1)),
                        "run ${run + 1}/${settings.perfRuns.coerceAtLeast(1)}"
                    )
                )
            }

            if (settings.sustainedSeconds > 0) {
                onProgress(Progress("Sustained load", 0.86))
                val start = System.nanoTime()
                var total = 0
                val marks = ArrayList<Pair<Double, Int>>()
                marks.add(0.0 to 0)
                var guard = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
                    if (elapsed >= settings.sustainedSeconds || guard++ > 2000) break
                    val generation = engine.generate(
                        safePrompt, 24, temperature = 0f, topK = 1, topP = 1f,
                        seed = settings.seed + 1000 + guard
                    )
                    if (generation.decodeTokens <= 0) break
                    total += generation.decodeTokens
                    measuredTokens += generation.decodeTokens
                    peakRss = maxOf(peakRss, currentRssBytes())
                    marks.add((System.nanoTime() - start) / 1_000_000_000.0 to total)
                }
                sustainedTokens = total
                val duration = (System.nanoTime() - start) / 1_000_000_000.0
                if (duration > 1.0 && total > 0) {
                    val half = duration / 2.0
                    var tokensAtHalf = 0
                    for (i in 1 until marks.size) {
                        val (time, tokens) = marks[i]
                        if (time >= half) {
                            val (prevTime, prevTokens) = marks[i - 1]
                            val span = (time - prevTime).coerceAtLeast(1e-9)
                            val fraction = ((half - prevTime) / span).coerceIn(0.0, 1.0)
                            tokensAtHalf = (prevTokens + (tokens - prevTokens) * fraction).toInt()
                            break
                        }
                    }
                    val early = if (half > 0) tokensAtHalf / half else 0.0
                    val late = if (duration > half) (total - tokensAtHalf) / (duration - half) else 0.0
                    val drop = if (early > 0) (early - late) / early * 100.0 else 0.0
                    sustained = SustainedResult(
                        seconds = duration,
                        earlyTokensPerSecond = early,
                        lateTokensPerSecond = late,
                        dropPercent = drop
                    )
                }
            }
        } finally {
            // stop() is deliberately outside the cancellation path so a user
            // cancel still records a valid energy sample in logs.
            val sample = sampler.stop()
            lastPowerSample = sample
        }

        if (measuredTokens <= 0) {
            warnings.add("Generation produced no tokens; throughput numbers are unavailable.")
        }
        val ttftArray = ttfts.toDoubleArray()
        val decodeArray = decodeRates.filter { it > 0.0 }.toDoubleArray()
        val prefillArray = prefillRates.toDoubleArray()

        val performance = PerformanceResult(
            loadMs = loadMs,
            weightsBytes = weightsBytes,
            contextLength = config.contextLength,
            promptTokens = safePrompt.size,
            ttftMs = Throughput(
                p50 = Metrics.percentile(ttftArray, 0.5),
                p95 = Metrics.percentile(ttftArray, 0.95),
                mean = Metrics.mean(ttftArray),
                samples = ttftArray.size
            ),
            prefillTokensPerSecond = Metrics.mean(prefillArray),
            decode = Throughput(
                p50 = Metrics.percentile(decodeArray, 0.5),
                p95 = Metrics.percentile(decodeArray, 0.95),
                mean = Metrics.mean(decodeArray),
                samples = decodeArray.size
            ),
            rssAfterLoadMb = rssAfterLoad / 1048576.0,
            peakRssMb = peakRss / 1048576.0,
            sustained = sustained,
            runs = settings.perfRuns
        )
        val sample = lastPowerSample ?: PowerSampler.Sample(
            false, 0.0, 0.0, 0.0, null, null, false, "estimated"
        )
        val tokens = measuredTokens.coerceAtLeast(1)
        val energy = EnergyResult(
            measured = sample.measured,
            method = sample.method,
            joules = sample.joules,
            tokens = tokens,
            joulesPerToken = sample.joules / tokens,
            milliwattHours = sample.milliwattHours,
            averageWatts = sample.averageWatts,
            wasCharging = sample.wasCharging,
            note = buildString {
                append(if (sample.measured) "Measured from the battery interface." else
                    "Estimated from ${CostModel.ESTIMATED_WATTS} W nominal package power; " +
                        "this device did not expose a usable counter.")
                if (sample.wasCharging) append(" The device was charging, so energy was estimated.")
                append(" Covers ${tokens} decoded tokens.")
            }
        )
        // Attach battery temperatures to the sustained result after sampling.
        val sustainedWithTemps = sustained?.copy(
            startBatteryTempC = sample.batteryStartC,
            endBatteryTempC = sample.batteryEndC
        )
        lastPowerSample = null
        return performance.copy(sustained = sustainedWithTemps) to energy
    }

    @Volatile
    private var lastPowerSample: PowerSampler.Sample? = null

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private suspend fun scoreChoices(
        engine: BenchEngine,
        tokenizer: BpeTokenizer,
        prompt: String,
        choices: List<String>
    ): DoubleArray? {
        var promptIds = tokenizer.encode(prompt)
        if (promptIds.isEmpty()) return null
        val context = engine.config.contextLength
        if (promptIds.size > context - 4) {
            promptIds = promptIds.copyOfRange(promptIds.size - (context - 4), promptIds.size)
        }
        val scores = DoubleArray(choices.size) { NEGATIVE_INFINITY }
        var usable = 0
        for (index in choices.indices) {
            currentCoroutineContext().ensureActive()
            var continuation = tokenizer.encode(McPrompt.continuation(choices[index]))
            if (continuation.isEmpty()) continue
            val room = context - promptIds.size
            if (room <= 0) continue
            if (continuation.size > room) continuation = continuation.copyOfRange(0, room)
            if (continuation.isEmpty()) continue
            val logProbs = engine.tokenLogProbs(promptIds, continuation)
            if (logProbs.isEmpty()) continue
            var sum = 0.0
            for (value in logProbs) sum += value.toDouble()
            scores[index] = sum / logProbs.size
            usable++
        }
        return if (usable >= 2) scores else null
    }

    private fun assetsPrompts(): List<String> = runCatching {
        val names = context.assets.list("samples")?.filter { it.endsWith(".txt") }.orEmpty()
        names.sorted().mapNotNull { name ->
            context.assets.open("samples/$name").bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLine()?.trim()?.takeIf { it.length > 12 }
            }
        }
    }.getOrDefault(emptyList())

    /**
     * Held-out text used for perplexity. Bundled samples are authored for Bench;
     * any `corpus_*.txt` copied next to an imported model is added, which lets a
     * team evaluate on its own domain text.
     */
    private fun corpusTexts(): List<Pair<String, String>> {
        val texts = ArrayList<Pair<String, String>>()
        val names = context.assets.list("samples")?.filter { it.endsWith(".txt") }.orEmpty().sorted()
        for (name in names) {
            runCatching {
                val text = context.assets.open("samples/$name")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (text.isNotBlank()) texts.add(name to text)
            }
        }
        // Corpus files stored with an imported model.
        val modelsRoot = File(context.filesDir, "models")
        modelsRoot.listFiles { file -> file.isDirectory }?.forEach { dir ->
            dir.listFiles { file -> file.isFile && file.name.startsWith("corpus") && file.name.endsWith(".txt") }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    runCatching {
                        val text = file.readText(Charsets.UTF_8)
                        if (text.isNotBlank()) texts.add("corpus:${file.name}" to text)
                    }
                }
        }
        return texts
    }

    private companion object {
        const val NEGATIVE_INFINITY = -Double.MAX_VALUE
    }
}
