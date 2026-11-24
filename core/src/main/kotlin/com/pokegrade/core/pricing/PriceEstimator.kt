package com.pokegrade.core.pricing

import com.pokegrade.core.catalog.TcgCard
import kotlinx.serialization.Serializable

@Serializable
data class PriceEstimate(
    /** Which printing the price is for, e.g. "holofoil". */
    val variant: String?,
    /** Raw (ungraded, near-mint) TCGplayer market price in USD. */
    val rawMarketUsd: Double?,
    val rawLowUsd: Double?,
    val rawHighUsd: Double?,
    /** Cardmarket 30-day average in EUR, when available. */
    val cardmarketTrendEur: Double?,
    /** Value adjusted for the estimated grade. */
    val estimatedValueUsd: Double?,
    val estimatedRangeUsd: Pair<Double, Double>?,
    val gradeMultiplier: Double,
    val source: String,
    val updatedAt: String?,
    val tcgplayerUrl: String?,
    val explanation: String,
)

/**
 * Turns the card's market price into an estimate for this copy.
 *
 * Only the raw (ungraded) price is real market data. Graded values are
 * estimated with typical multipliers for a professionally graded copy,
 * because no free API publishes graded sales. Treat them as a ballpark.
 */
object PriceEstimator {

    /** Typical graded/raw ratios for modern Pokémon cards. (grade floor → multiplier, low, high) */
    private val MULTIPLIERS = listOf(
        10.0 to Triple(3.5, 2.0, 8.0),
        9.0 to Triple(1.6, 1.2, 2.5),
        8.0 to Triple(1.1, 0.85, 1.4),
        7.0 to Triple(0.85, 0.7, 1.0),
        6.0 to Triple(0.65, 0.5, 0.8),
        5.0 to Triple(0.5, 0.35, 0.6),
        3.0 to Triple(0.35, 0.2, 0.45),
        1.0 to Triple(0.2, 0.1, 0.3),
    )

    /** Raw, ungraded copies sell at a discount once they show wear. */
    private fun rawConditionFactor(grade: Double) = when {
        grade >= 8.0 -> 1.0   // Near Mint
        grade >= 6.0 -> 0.8   // Lightly played
        grade >= 4.0 -> 0.6   // Moderately played
        grade >= 2.0 -> 0.4   // Heavily played
        else -> 0.25          // Damaged
    }

    private val VARIANT_PREFERENCE = listOf(
        "1stEditionHolofoil", "holofoil", "1stEditionNormal", "normal", "unlimitedHolofoil", "reverseHolofoil", "unlimited",
    )

    fun estimate(card: TcgCard, grade: Double, gradedSlab: Boolean = false, variantHint: String? = null): PriceEstimate {
        val prices = card.tcgplayer?.prices.orEmpty()
        val variant = variantHint?.takeIf { prices.containsKey(it) }
            ?: VARIANT_PREFERENCE.firstOrNull { prices[it]?.market != null }
            ?: prices.keys.firstOrNull()
        val p = variant?.let { prices[it] }
        val cm = card.cardmarket?.prices
        val raw = p?.market ?: p?.mid ?: cm?.trendPrice?.let { it * EUR_TO_USD }

        val (mult, lo, hi) = if (gradedSlab) {
            MULTIPLIERS.first { grade >= it.first }.second
        } else {
            val f = rawConditionFactor(grade); Triple(f, f * 0.85, f * 1.1)
        }

        val explanation = buildString {
            if (raw == null) {
                append("No recent market sales found for this card.")
            } else if (gradedSlab) {
                append("Raw near-mint market price is $${fmt(raw)}. ")
                append("A PSA ${grade.toInt()} copy typically sells for about ${fmt(mult)}× raw, ")
                append("but graded premiums vary a lot by card — check recent sold listings before buying or selling.")
            } else {
                append("Raw near-mint market price is $${fmt(raw)}. ")
                append("At an estimated grade of ${fmt(grade)} this copy is worth about ${(mult * 100).toInt()}% of that as an ungraded card.")
            }
        }

        return PriceEstimate(
            variant = variant,
            rawMarketUsd = raw,
            rawLowUsd = p?.low,
            rawHighUsd = p?.high,
            cardmarketTrendEur = cm?.trendPrice,
            estimatedValueUsd = raw?.let { round2(it * mult) },
            estimatedRangeUsd = raw?.let { round2(it * lo) to round2(it * hi) },
            gradeMultiplier = mult,
            source = if (p != null) "TCGplayer" else if (cm != null) "Cardmarket" else "none",
            updatedAt = card.tcgplayer?.updatedAt ?: card.cardmarket?.updatedAt,
            tcgplayerUrl = card.tcgplayer?.url,
            explanation = explanation,
        )
    }

    /** Rough conversion used only when TCGplayer has no data. */
    private const val EUR_TO_USD = 1.08

    private fun round2(v: Double) = Math.round(v * 100) / 100.0
    fun fmt(v: Double) = if (v >= 100) "%,.0f".format(v) else "%,.2f".format(v)
}
