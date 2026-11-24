package com.pokegrade.core.grading

import kotlin.math.floor

/**
 * Grading companies the app knows about. Used for labelling uploads, showing
 * the estimated grade the way each company would print it, and for rough
 * graded-slab values.
 *
 * Resale multipliers are relative to the same grade from PSA, which has the
 * largest buyer pool. They're ballpark figures, not market data.
 */
enum class Grader(
    val displayName: String,
    /** Smallest step on the company's scale (1.0 = whole grades only). */
    val step: Double,
    val hasSubgrades: Boolean,
    /** Name printed for a perfect card, when the company has one above "Gem Mint 10". */
    val pristineLabel: String?,
    /** Rough resale value vs the same grade from PSA. */
    val resaleVsPsa: Double,
    /** Public cert lookup page; {cert} is replaced. Null where we couldn't confirm the URL. */
    val certLookupUrl: String?,
    val note: String,
) {
    PSA("PSA", 0.5, false, null, 1.0, "https://www.psacard.com/cert/{cert}",
        "Largest resale market. Whole grades plus a few half grades."),
    BGS("Beckett (BGS)", 0.5, true, "Black Label Pristine 10", 0.9, null,
        "Half-point grades with four subgrades; Black Label needs all four at 10."),
    CGC("CGC", 0.5, true, "Pristine 10", 0.7, "https://www.cgccards.com/certlookup/{cert}/",
        "Half-point grades; Pristine 10 above Gem Mint 10."),
    SGC("SGC", 0.5, false, "Pristine 10", 0.65, null,
        "Half-point grades; popular for vintage."),
    TAG("TAG", 0.5, true, "Pristine 10", 0.6, null,
        "Computer-vision grading with a 1,000-point score behind the 1–10 grade."),
    ACE("ACE", 1.0, true, null, 0.55, null,
        "UK grader with subgrades; strongest in UK/EU markets."),
    DSG("Diamond Service Grading (DSG)", 0.5, true, null, 0.45, null,
        "Newer boutique grader (est. 2020) with four subgrades; small resale market."),
    AGS("AGS", 0.5, true, null, 0.45, null, "Automated grading with subgrades."),
    HGA("HGA", 0.5, true, null, 0.45, null, "Boutique grader with subgrades and custom labels."),
    GMA("GMA", 0.5, false, null, 0.35, null, "Budget grader."),
    MNT("MNT", 0.5, true, null, 0.4, null, "Canadian grader with subgrades."),
    OTHER("Other", 0.5, false, null, 0.35, null, "Another grading company.");

    fun certUrl(cert: String): String? = certLookupUrl?.replace("{cert}", cert.trim())

    companion object {
        fun fromCode(code: String?): Grader? = entries.firstOrNull { it.name.equals(code, ignoreCase = true) }
    }
}

/** How a given company would likely print our estimate. */
data class GraderEstimate(val grader: Grader, val grade: Double, val label: String, val subgrades: SubGrades?)

object GraderScales {

    /**
     * Converts the app's estimate into each company's scale. The app's
     * measurements are PSA-style; other companies use half points and
     * subgrades, and some have a "Pristine" tier above Gem Mint.
     */
    fun estimate(g: GradeResult, grader: Grader): GraderEstimate {
        val s = g.subGrades
        val raw = minOf(
            s.centering * 0.25 + s.corners * 0.30 + s.edges * 0.25 + s.surface * 0.20,
            minOf(s.centering, s.corners, s.edges, s.surface) + 1.0,
        )
        // PSA mostly gives whole grades; others use half points.
        val grade = when {
            grader == Grader.PSA -> floor(g.overall)
            else -> (floor(raw / grader.step) * grader.step).coerceIn(1.0, 10.0)
        }
        val allPerfect = listOf(s.centering, s.corners, s.edges, s.surface).all { it >= 9.9 }
        val label = when {
            grade >= 10.0 && allPerfect && grader.pristineLabel != null -> grader.pristineLabel
            grade >= 10.0 -> "Gem Mint 10"
            else -> "${gradeName(grade)} ${fmt(grade)}"
        }
        return GraderEstimate(grader, grade, label, if (grader.hasSubgrades) roundSubgrades(s) else null)
    }

    private fun roundSubgrades(s: SubGrades) = SubGrades(
        centering = floor(s.centering * 2) / 2,
        corners = floor(s.corners * 2) / 2,
        edges = floor(s.edges * 2) / 2,
        surface = floor(s.surface * 2) / 2,
    )

    private fun gradeName(g: Double) = when {
        g >= 9 -> "Mint"
        g >= 8 -> "NM-MT"
        g >= 7 -> "NM"
        g >= 6 -> "EX-MT"
        g >= 5 -> "EX"
        g >= 4 -> "VG-EX"
        g >= 3 -> "VG"
        g >= 2 -> "Good"
        else -> "Poor"
    }

    fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
}
