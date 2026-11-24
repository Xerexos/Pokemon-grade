package com.pokegrade.core.grading

import kotlinx.serialization.Serializable

@Serializable
data class CardBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

@Serializable
data class Centering(
    /** Share of the horizontal border on the left side, e.g. 0.55 = 55/45. */
    val leftRatio: Double,
    /** Share of the vertical border on the top side. */
    val topRatio: Double,
    val borderLeftPx: Int,
    val borderRightPx: Int,
    val borderTopPx: Int,
    val borderBottomPx: Int,
) {
    fun lrLabel() = ratioLabel(leftRatio)
    fun tbLabel() = ratioLabel(topRatio)

    /** The worse of the two axes, expressed as the larger side, e.g. 0.62 for 62/38. */
    fun worstSkew(): Double = maxOf(skew(leftRatio), skew(topRatio))

    companion object {
        fun skew(r: Double) = maxOf(r, 1 - r)
        fun ratioLabel(r: Double): String {
            val a = Math.round(r * 100).toInt()
            return "$a/${100 - a}"
        }
    }
}

@Serializable
data class SubGrades(
    val centering: Double,
    val corners: Double,
    val edges: Double,
    val surface: Double,
)

@Serializable
data class GradeResult(
    val overall: Double,
    val subGrades: SubGrades,
    val centering: Centering,
    val bounds: CardBounds,
    /** Per-corner wear score 0 (pristine) .. 1 (heavy), order TL, TR, BL, BR. */
    val cornerWear: List<Double>,
    /** Per-edge wear 0..1, order top, right, bottom, left. */
    val edgeWear: List<Double>,
    val surfaceDefectRatio: Double,
    val photoQuality: PhotoQuality,
    val notes: List<String>,
    /** False when the photo can't be graded with any confidence. */
    val reliable: Boolean = true,
    val reliabilityIssues: List<String> = emptyList(),
) {
    /** PSA-style label for a numeric grade. */
    fun label(): String = gradeLabel(overall)

    companion object {
        fun gradeLabel(g: Double): String = when {
            g >= 9.75 -> "GEM MINT 10"
            g >= 9.0 -> "MINT 9"
            g >= 8.0 -> "NM-MT 8"
            g >= 7.0 -> "NM 7"
            g >= 6.0 -> "EX-MT 6"
            g >= 5.0 -> "EX 5"
            g >= 4.0 -> "VG-EX 4"
            g >= 3.0 -> "VG 3"
            g >= 2.0 -> "GOOD 2"
            else -> "POOR 1"
        }
    }
}

@Serializable
data class PhotoQuality(
    val sharpness: Double,
    val brightness: Double,
    val glareRatio: Double,
    val cardFillRatio: Double,
    val acceptable: Boolean,
    val problems: List<String>,
)
