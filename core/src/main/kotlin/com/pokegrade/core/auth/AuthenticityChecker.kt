package com.pokegrade.core.auth

import com.pokegrade.core.catalog.CardTextParser
import com.pokegrade.core.catalog.ParsedCardText
import com.pokegrade.core.catalog.TcgCard
import com.pokegrade.core.image.ColorMath
import com.pokegrade.core.image.RgbImage
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

@Serializable
enum class CheckStatus { PASS, WARN, FAIL, SKIPPED }

@Serializable
enum class Verdict(val label: String) {
    LIKELY_AUTHENTIC("Likely authentic"),
    SUSPICIOUS("Suspicious — inspect closely"),
    LIKELY_FAKE("Likely fake"),
    INCONCLUSIVE("Inconclusive"),
}

@Serializable
data class AuthCheck(val name: String, val status: CheckStatus, val detail: String, val weight: Double)

@Serializable
data class AuthenticityReport(
    val verdict: Verdict,
    /** 0..1 confidence in the verdict. */
    val confidence: Double,
    val checks: List<AuthCheck>,
)

/**
 * Compares the photographed card with the official database entry and scan.
 *
 * What it can catch from a photo: made-up cards, wrong HP / attacks / set
 * numbers, swapped artwork, and the washed-out or over-saturated colours
 * typical of counterfeit printing. What it can't: card stock, the light
 * test, texture on modern full-arts. A "likely authentic" result is not a
 * guarantee.
 */
object AuthenticityChecker {

    fun check(
        text: ParsedCardText,
        card: TcgCard?,
        cardPhoto: RgbImage,
        reference: RgbImage?,
    ): AuthenticityReport {
        val checks = mutableListOf<AuthCheck>()

        // 1. Does the card exist?
        checks += when {
            card != null -> AuthCheck("Card exists in official database", CheckStatus.PASS, "Matched ${card.name} · ${card.set.name} #${card.number}", 3.0)
            text.name == null && text.number == null ->
                AuthCheck("Card exists in official database", CheckStatus.SKIPPED, "Couldn't read the card's text.", 3.0)
            else -> AuthCheck(
                "Card exists in official database", CheckStatus.FAIL,
                "No official card matches \"${text.name ?: "?"}\" ${text.number ?: ""}${text.setTotal?.let { "/$it" } ?: ""}. Made-up cards are a common fake.",
                3.0,
            )
        }

        if (card != null) {
            // 2. Printed set total.
            val printed = text.setTotal?.trimStart('0')?.toIntOrNull()
            checks += if (printed == null || card.set.printedTotal == null) {
                AuthCheck("Set number format", CheckStatus.SKIPPED, "Set number not readable.", 2.0)
            } else if (printed == card.set.printedTotal) {
                AuthCheck("Set number format", CheckStatus.PASS, "${text.number}/${text.setTotal} matches ${card.set.name}.", 2.0)
            } else {
                AuthCheck("Set number format", CheckStatus.FAIL, "Card says /${text.setTotal} but ${card.set.name} has ${card.set.printedTotal} cards.", 2.0)
            }

            // 3. HP.
            val realHp = card.hp?.toIntOrNull()
            checks += when {
                realHp == null || text.hp == null -> AuthCheck("HP value", CheckStatus.SKIPPED, "HP not readable or not applicable.", 2.0)
                realHp == text.hp -> AuthCheck("HP value", CheckStatus.PASS, "HP $realHp matches.", 2.0)
                else -> AuthCheck("HP value", CheckStatus.FAIL, "Card shows HP ${text.hp}, official card has HP $realHp.", 2.0)
            }

            // 4. Attack / ability names.
            val names = card.attacks.map { it.name } + card.abilities.map { it.name }
            val lower = normalize(text.allText)
            if (names.isEmpty() || text.allText.length < 40) {
                checks += AuthCheck("Attack & ability text", CheckStatus.SKIPPED, "Not enough readable text.", 1.5)
            } else {
                val found = names.filter { lower.contains(normalize(it)) }
                checks += when {
                    found.size == names.size -> AuthCheck("Attack & ability text", CheckStatus.PASS, "Found ${found.joinToString()}.", 1.5)
                    found.isNotEmpty() -> AuthCheck("Attack & ability text", CheckStatus.PASS, "Found ${found.size} of ${names.size}: ${found.joinToString()}.", 1.5)
                    else -> AuthCheck("Attack & ability text", CheckStatus.WARN, "Expected ${names.joinToString()} but couldn't find them. Check spelling on the card.", 1.5)
                }
            }
        }

        // 5–7. Compare against the official scan.
        if (reference != null) {
            checks += compareImages(cardPhoto, reference)
        } else {
            checks += AuthCheck("Artwork & colour match", CheckStatus.SKIPPED, "No reference image available.", 2.5)
        }

        return summarize(checks)
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    // ----------------------------------------------------------- image tests

    private const val GW = 32
    private const val GH = 45

    internal fun compareImages(photo: RgbImage, ref: RgbImage): List<AuthCheck> {
        val a = grayWorld(photo.resize(GW, GH))
        val b = grayWorld(ref.resize(GW, GH))

        // Artwork/layout: correlation of luminance on a coarse grid.
        val corr = correlation(a.lumaArray(), b.lumaArray())
        val layout = when {
            corr >= 0.72 -> AuthCheck("Artwork & layout match", CheckStatus.PASS, "Layout matches the official scan (similarity ${pct(corr)}).", 2.5)
            corr >= 0.5 -> AuthCheck("Artwork & layout match", CheckStatus.WARN, "Partial match with the official scan (similarity ${pct(corr)}). Could be glare, angle, or a different print.", 2.5)
            else -> AuthCheck("Artwork & layout match", CheckStatus.FAIL, "Doesn't look like the official card (similarity ${pct(corr)}).", 2.5)
        }

        // Colour saturation: fakes are often washed-out or over-saturated.
        val satA = meanSaturation(a)
        val satB = meanSaturation(b)
        val ratio = satA / max(0.01, satB)
        val sat = when {
            ratio in 0.75..1.3 -> AuthCheck("Colour saturation", CheckStatus.PASS, "Colour intensity is ${pct(ratio)} of the official print.", 1.5)
            ratio in 0.6..1.5 -> AuthCheck("Colour saturation", CheckStatus.WARN, "Colours are ${if (ratio < 1) "duller" else "stronger"} than the official print (${pct(ratio)}).", 1.5)
            else -> AuthCheck("Colour saturation", CheckStatus.FAIL, "Colours are much ${if (ratio < 1) "duller" else "stronger"} than the official print (${pct(ratio)}) — common in counterfeits.", 1.5)
        }

        // Border hue.
        val hueA = ColorMath.hsv(borderColor(a))
        val hueB = ColorMath.hsv(borderColor(b))
        val border = if (hueA[1] < 0.15 || hueB[1] < 0.15) {
            // Silver / white / black borders: compare brightness instead of hue.
            val dv = abs(hueA[2] - hueB[2])
            if (dv < 0.25) AuthCheck("Border colour", CheckStatus.PASS, "Border tone matches.", 1.5)
            else AuthCheck("Border colour", CheckStatus.WARN, "Border is ${if (hueA[2] > hueB[2]) "lighter" else "darker"} than the official card.", 1.5)
        } else {
            val dh = ColorMath.hueDistance(hueA[0], hueB[0])
            when {
                dh < 12 -> AuthCheck("Border colour", CheckStatus.PASS, "Border hue matches (off by ${dh.toInt()}°).", 1.5)
                dh < 22 -> AuthCheck("Border colour", CheckStatus.WARN, "Border hue is a little off (${dh.toInt()}°).", 1.5)
                else -> AuthCheck("Border colour", CheckStatus.FAIL, "Border colour is clearly different (${dh.toInt()}°).", 1.5)
            }
        }
        return listOf(layout, sat, border)
    }

    /** Divide each channel by its mean so lighting colour casts cancel out. */
    private fun grayWorld(img: RgbImage): RgbImage {
        var r = 0.0; var g = 0.0; var b = 0.0
        for (p in img.pixels) { r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
        val n = img.pixels.size.toDouble()
        val avg = (r + g + b) / (3 * n)
        val fr = avg / max(1.0, r / n); val fg = avg / max(1.0, g / n); val fb = avg / max(1.0, b / n)
        return RgbImage(img.width, img.height, IntArray(img.pixels.size) { i ->
            val p = img.pixels[i]
            RgbImage.rgb(
                (((p shr 16) and 0xFF) * fr).toInt().coerceIn(0, 255),
                (((p shr 8) and 0xFF) * fg).toInt().coerceIn(0, 255),
                ((p and 0xFF) * fb).toInt().coerceIn(0, 255),
            )
        })
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) {
            val x = a[i] - ma; val y = b[i] - mb
            num += x * y; da += x * x; db += y * y
        }
        return if (da == 0.0 || db == 0.0) 0.0 else num / sqrt(da * db)
    }

    private fun meanSaturation(img: RgbImage) = img.pixels.map { ColorMath.hsv(it)[1] }.average()

    private fun borderColor(img: RgbImage): Int {
        val s = ArrayList<Int>()
        for (y in 0 until img.height) for (x in 0 until img.width) {
            if (x == 1 || x == img.width - 2 || y == 1 || y == img.height - 2) s += img.argb(x, y)
        }
        return ColorMath.medianColor(s)
    }

    private fun pct(v: Double) = "${(v * 100).toInt()}%"

    // --------------------------------------------------------------- verdict

    private fun summarize(checks: List<AuthCheck>): AuthenticityReport {
        val ran = checks.filter { it.status != CheckStatus.SKIPPED }
        val totalWeight = ran.sumOf { it.weight }
        val risk = ran.sumOf {
            when (it.status) {
                CheckStatus.FAIL -> it.weight
                CheckStatus.WARN -> it.weight * 0.4
                else -> 0.0
            }
        }
        if (totalWeight < 3.0) {
            return AuthenticityReport(Verdict.INCONCLUSIVE, 0.3, checks)
        }
        val riskRatio = risk / totalWeight
        val verdict = when {
            risk >= 3.0 || riskRatio >= 0.35 -> Verdict.LIKELY_FAKE
            risk >= 1.2 || riskRatio >= 0.12 -> Verdict.SUSPICIOUS
            else -> Verdict.LIKELY_AUTHENTIC
        }
        val coverage = (totalWeight / 12.5).coerceIn(0.0, 1.0)
        val confidence = when (verdict) {
            Verdict.LIKELY_AUTHENTIC -> 0.5 + 0.4 * coverage * (1 - riskRatio)
            Verdict.LIKELY_FAKE -> 0.5 + 0.45 * riskRatio.coerceAtMost(1.0)
            else -> 0.5
        }
        return AuthenticityReport(verdict, Math.round(confidence * 100) / 100.0, checks)
    }

    /** Exposed for UI: the parser's normaliser. */
    fun numberMatches(text: ParsedCardText, card: TcgCard) =
        text.number != null && CardTextParser.normalizeNumber(text.number).equals(card.number, ignoreCase = true)
}
