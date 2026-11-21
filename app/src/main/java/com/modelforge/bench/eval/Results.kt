package com.modelforge.bench.eval

import com.modelforge.bench.core.DeviceProfile
import com.modelforge.bench.model.ModelConfig
import kotlinx.serialization.Serializable

@Serializable
enum class RunMode { SMOKE, STANDARD, DEEP }

@Serializable
enum class RunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

@Serializable
data class RunSettings(
    val mcLimit: Int = 200,
    val corpusTokenLimit: Int = 4096,
    val perfRuns: Int = 5,
    val sustainedSeconds: Int = 20,
    val seed: Long = 42L,
    val contextTokens: Int = 0
) {
    companion object {
        fun forMode(mode: RunMode, seed: Long = 42L): RunSettings = when (mode) {
            RunMode.SMOKE -> RunSettings(
                mcLimit = 34, corpusTokenLimit = 1024, perfRuns = 3,
                sustainedSeconds = 0, seed = seed
            )
            RunMode.STANDARD -> RunSettings(
                mcLimit = 200, corpusTokenLimit = 4096, perfRuns = 5,
                sustainedSeconds = 20, seed = seed
            )
            RunMode.DEEP -> RunSettings(
                mcLimit = 1000, corpusTokenLimit = 8192, perfRuns = 8,
                sustainedSeconds = 60, seed = seed
            )
        }
    }
}

@Serializable
data class PerplexityResult(
    val dataset: String,
    val tokens: Int,
    val bytes: Long,
    val windows: Int,
    val meanNatsPerToken: Double,
    val perplexity: Double,
    val bitsPerByte: Double,
    val ciLow: Double,
    val ciHigh: Double
)

@Serializable
data class CategoryScore(
    val category: String,
    val correct: Int,
    val total: Int,
    val accuracy: Double,
    val ciLow: Double,
    val ciHigh: Double
)

@Serializable
data class McResult(
    val pack: String,
    val provenance: String,
    val items: Int,
    val correct: Int,
    val accuracy: Double,
    val ciLow: Double,
    val ciHigh: Double,
    val chanceAccuracy: Double,
    val scoring: String,
    val categories: List<CategoryScore>,
    val meanConfidence: Double,
    val ece: Double
)

@Serializable
data class RobustnessResult(
    val baseAccuracy: Double,
    val reversedOrderAccuracy: Double,
    val reversedDelta: Double,
    val prefixedAccuracy: Double,
    val prefixedDelta: Double,
    val flipRate: Double,
    val evaluated: Int,
    val notes: String
)

@Serializable
data class ContaminationResult(
    val method: String,
    val originalLoss: Double,
    val perturbedLoss: Double,
    val memorizationGap: Double,
    val tokens: Int,
    val interpretation: String
)

@Serializable
data class GenerationSample(
    val prompt: String,
    val output: String,
    val tokens: Int,
    val stopReason: String
)

@Serializable
data class GenerationResult(
    val samples: List<GenerationSample>,
    val distinct1: Double,
    val distinct2: Double,
    val repetitionRate: Double,
    val averageTokens: Double,
    val emptyOutputs: Int,
    val invalidText: Int
)

@Serializable
data class TokenizerResult(
    val vocabulary: Int,
    val merges: Int,
    val compression: Double,
    val tokensPerWord: Double,
    val samples: Int,
    val note: String
)

@Serializable
data class QualityResult(
    val perplexity: PerplexityResult? = null,
    val multipleChoice: McResult? = null,
    val robustness: RobustnessResult? = null,
    val contamination: ContaminationResult? = null,
    val generation: GenerationResult? = null,
    val tokenizer: TokenizerResult? = null,
    val warnings: List<String> = emptyList()
)

@Serializable
data class Throughput(
    val p50: Double,
    val p95: Double,
    val mean: Double,
    val samples: Int
)

@Serializable
data class SustainedResult(
    val seconds: Double,
    val earlyTokensPerSecond: Double,
    val lateTokensPerSecond: Double,
    val dropPercent: Double,
    val startBatteryTempC: Double? = null,
    val endBatteryTempC: Double? = null
)

@Serializable
data class PerformanceResult(
    val loadMs: Double,
    val weightsBytes: Long,
    val contextLength: Int,
    val promptTokens: Int,
    val ttftMs: Throughput,
    val prefillTokensPerSecond: Double,
    val decode: Throughput,
    val rssAfterLoadMb: Double,
    val peakRssMb: Double,
    val sustained: SustainedResult? = null,
    val runs: Int
)

@Serializable
data class EnergyResult(
    val measured: Boolean,
    val method: String,
    val joules: Double,
    val tokens: Int,
    val joulesPerToken: Double,
    val milliwattHours: Double,
    val averageWatts: Double,
    val wasCharging: Boolean,
    val note: String
)

@Serializable
data class ApiPriceRow(
    val name: String,
    val inputPerMillion: Double,
    val outputPerMillion: Double,
    val note: String = ""
) {
    /** A 25% input / 75% output blend, stated in the report. */
    fun blendedPerMillion(inputShare: Double = 0.25): Double =
        inputPerMillion * inputShare + outputPerMillion * (1.0 - inputShare)
}

@Serializable
data class CostResult(
    val electricityPerKwh: Double,
    val hardwarePrice: Double,
    val lifetimeHours: Double,
    val energyCostPerMillion: Double,
    val amortisedCostPerMillion: Double,
    val deviceCostPerMillion: Double,
    val apiRows: List<ApiPriceRow>,
    val cheapestApiName: String? = null,
    val cheapestApiPerMillion: Double? = null,
    val qualityPerDollar: Double? = null,
    val note: String
)

@Serializable
data class ScorePart(
    val name: String,
    val score: Double,
    val weight: Double,
    val detail: String
)

@Serializable
data class ScoreResult(
    val parts: List<ScorePart>,
    val composite: Double,
    val grade: String,
    val verdict: String,
    val detail: String
)

@Serializable
data class BenchRun(
    val id: String,
    val modelId: String,
    val modelName: String,
    val modelSha256: String,
    val modelOrigin: String,
    val config: ModelConfig,
    val paramCount: Long,
    val weightsKind: String,
    val bits: Int,
    val device: DeviceProfile,
    val mode: RunMode,
    val seed: Long,
    val settings: RunSettings,
    val startedAt: Long,
    val finishedAt: Long,
    val durationMs: Long,
    val status: RunStatus,
    val engineVersion: String,
    val quality: QualityResult? = null,
    val performance: PerformanceResult? = null,
    val energy: EnergyResult? = null,
    val cost: CostResult? = null,
    val score: ScoreResult? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList()
) {
    val headline: String
        get() = score?.let { "${it.grade} · ${String.format("%.0f", it.composite)}/100" } ?: "No score"
}
