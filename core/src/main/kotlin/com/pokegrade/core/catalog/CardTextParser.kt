package com.pokegrade.core.catalog

import kotlinx.serialization.Serializable

/** One line of OCR output. Coordinates are normalised to the card (0..1). */
@Serializable
data class OcrLine(val text: String, val left: Double, val top: Double, val height: Double)

@Serializable
data class ParsedCardText(
    val name: String?,
    val hp: Int?,
    /** Collector number as printed, e.g. "025", "TG05", "SWSH050". */
    val number: String?,
    /** Printed set size from "025/198", if present. */
    val setTotal: String?,
    val allText: String,
)

/**
 * Pulls the card name, HP and collector number out of ML Kit OCR output.
 */
object CardTextParser {
    private val NUMBER = Regex("""\b([A-Z]{0,4}\d{1,3}[a-z]?)\s*/\s*([A-Z]{0,4}\d{1,3})\b""")
    private val PROMO = Regex("""\b(SWSH\d{3}|SM\d{2,3}|XY\d{2,3}|BW\d{2,3}|SVP?\s?\d{2,3}|HGSS\d{2})\b""")
    private val HP_AFTER = Regex("""\bHP\s*(\d{2,3})\b""", RegexOption.IGNORE_CASE)
    private val HP_BEFORE = Regex("""\b(\d{2,3})\s*HP\b""", RegexOption.IGNORE_CASE)

    /** Words printed near the name that are not the name. */
    private val NOT_NAME = listOf(
        "basic", "stage", "evolves", "trainer", "item", "supporter", "stadium", "energy",
        "pokémon tool", "pokemon tool", "put ", "illus", "hp", "ability", "weakness", "resistance",
        "retreat", "tera", "mega", "break", "restored",
    )

    fun parse(lines: List<OcrLine>): ParsedCardText {
        val all = lines.joinToString("\n") { it.text }

        // Collector number: prefer the bottom of the card.
        val numberMatch = lines.filter { it.top > 0.75 }.firstNotNullOfOrNull { NUMBER.find(it.text) }
            ?: NUMBER.find(all)
        val promo = if (numberMatch == null) PROMO.find(all.uppercase()) else null

        val hp = lines.filter { it.top < 0.2 }.firstNotNullOfOrNull {
            HP_AFTER.find(it.text) ?: HP_BEFORE.find(it.text)
        }?.groupValues?.get(1)?.toIntOrNull()
            ?: (HP_AFTER.find(all) ?: HP_BEFORE.find(all))?.groupValues?.get(1)?.toIntOrNull()

        return ParsedCardText(
            name = guessName(lines),
            hp = hp,
            number = numberMatch?.groupValues?.get(1) ?: promo?.value?.replace(" ", ""),
            setTotal = numberMatch?.groupValues?.get(2),
            allText = all,
        )
    }

    private fun guessName(lines: List<OcrLine>): String? {
        val candidates = lines
            .filter { it.top < 0.16 }
            .map { it to cleanName(it.text) }
            .filter { (_, n) ->
                n.length >= 3 && n.count { it.isLetter() } >= 3 &&
                    NOT_NAME.none { n.lowercase().startsWith(it) }
            }
        // The name is the tallest text in the header area.
        return candidates.maxByOrNull { it.first.height }?.second
    }

    /** Remove HP / stray symbols but keep name suffixes like "ex", "V", "VMAX", "GX". */
    fun cleanName(raw: String): String = raw
        .replace(HP_AFTER, "")
        .replace(HP_BEFORE, "")
        .replace(Regex("""[^\p{L}\p{N}'.\- ]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** "025" -> "25", "TG05" -> "TG05" (the API stores numbers without leading zeros). */
    fun normalizeNumber(n: String): String {
        val m = Regex("""^([A-Za-z]*)0*(\d+[a-z]?)$""").find(n) ?: return n
        val (prefix, digits) = m.destructured
        return if (prefix.isEmpty()) digits else prefix.uppercase() + m.value.removePrefix(prefix)
    }
}
