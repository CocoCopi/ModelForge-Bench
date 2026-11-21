package com.modelforge.bench.eval

import kotlinx.serialization.Serializable

/**
 * Commercial inputs are user-editable. The defaults are deliberately generic
 * price tiers rather than claims about a named vendor's current list price:
 * an out-of-date competitor price would make the whole comparison dishonest.
 * The Settings screen lets a team enter the contract rates it actually pays.
 */
@Serializable
data class CostSettings(
    val electricityPerKwh: Double = 0.20,
    val hardwarePrice: Double = 400.0,
    val lifetimeHours: Double = 3.0 * 365.0 * 8.0,
    val apiRows: List<ApiPriceRow> = defaultApiRows()
) {
    companion object {
        fun defaultApiRows(): List<ApiPriceRow> = listOf(
            ApiPriceRow(
                name = "Budget hosted API",
                inputPerMillion = 0.15,
                outputPerMillion = 0.60,
                note = "Placeholder tier — replace with your provider's price"
            ),
            ApiPriceRow(
                name = "Mid-tier hosted API",
                inputPerMillion = 1.00,
                outputPerMillion = 3.00,
                note = "Placeholder tier — replace with your provider's price"
            ),
            ApiPriceRow(
                name = "Frontier hosted API",
                inputPerMillion = 5.00,
                outputPerMillion = 15.00,
                note = "Placeholder tier — replace with your provider's price"
            )
        )
    }
}

object CostModel {

    /** Output-heavy blend used to compare a local token with an API token. */
    const val OUTPUT_SHARE = 0.75

    fun compute(
        energy: EnergyResult?,
        performance: PerformanceResult,
        qualityScore: Double?,
        settings: CostSettings
    ): CostResult {
        val decodeTps = performance.decode.p50.takeIf { it.isFinite() && it > 0.0 } ?: 1.0

        // Energy per token: measured when the device exposes it, otherwise a
        // documented estimate from a nominal package power at this throughput.
        val joulesPerToken = energy?.joulesPerToken?.takeIf { it.isFinite() && it > 0.0 }
            ?: (ESTIMATED_WATTS / decodeTps)

        val kwhPerMillion = joulesPerToken * 1_000_000.0 / 3_600_000.0
        val energyCostPerMillion = kwhPerMillion * settings.electricityPerKwh

        val lifetimeSeconds = settings.lifetimeHours.coerceAtLeast(1.0) * 3600.0
        val tokensInLifetime = lifetimeSeconds * decodeTps
        val amortisedCostPerMillion =
            if (tokensInLifetime > 0.0) settings.hardwarePrice / tokensInLifetime * 1_000_000.0 else 0.0

        val deviceCostPerMillion = energyCostPerMillion + amortisedCostPerMillion
        val apiRows = settings.apiRows.filter { it.name.isNotBlank() }
        val cheapest = apiRows.minByOrNull { it.blendedPerMillion(1.0 - OUTPUT_SHARE) }

        val qualityPerDollar = qualityScore?.let { score ->
            if (deviceCostPerMillion > 0.0) score / deviceCostPerMillion else null
        }

        val note = buildString {
            append("Energy ")
            append(if (energy?.measured == true) "measured on this device" else "estimated (device does not expose a power counter)")
            append(". Hardware amortised over ${settings.lifetimeHours.toInt()} h of use at ")
            append("${"%.1f".format(decodeTps)} tok/s. API comparison uses a ")
            append("${(OUTPUT_SHARE * 100).toInt()}% output blend; edit prices in Settings.")
        }

        return CostResult(
            electricityPerKwh = settings.electricityPerKwh,
            hardwarePrice = settings.hardwarePrice,
            lifetimeHours = settings.lifetimeHours,
            energyCostPerMillion = energyCostPerMillion,
            amortisedCostPerMillion = amortisedCostPerMillion,
            deviceCostPerMillion = deviceCostPerMillion,
            apiRows = apiRows,
            cheapestApiName = cheapest?.name,
            cheapestApiPerMillion = cheapest?.blendedPerMillion(1.0 - OUTPUT_SHARE),
            qualityPerDollar = qualityPerDollar,
            note = note
        )
    }

    /**
     * A conservative nominal package power for a phone running one CPU-bound
     * model. Used only when the battery interface cannot be sampled, and always
     * labelled as an estimate in the report.
     */
    const val ESTIMATED_WATTS = 3.5
}
