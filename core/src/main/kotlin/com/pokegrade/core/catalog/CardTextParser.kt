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
    private val BARE_HP = Regex("""^(\d{2,3})$""")
    private val HP_BEFORE = Regex("""\b(\d{2,3})\s*HP\b""", RegexOption.IGNORE_CASE)

    /** Lines near the name that are never the name. */
    private val NOT_NAME = listOf(
        "evolves", "trainer", "item", "supporter", "stadium", "energy",
        "pokémon tool", "pokemon tool", "put ", "illus", "hp", "ability", "weakness", "resistance",
        "retreat", "tera", "rule", "pokémon ex rule", "pokemon ex rule",
    )

    /** Stage text printed on the same line, before the name. */
    private val STAGE_PREFIX = Regex(
        """^(basic|stage\s*[12]|restored|break|v-union|vmax|vstar|level-up|baby)\b[\s:.\-]*""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The stylised "ex" / "EX" / "GX" logo is often read as part of the
     * name: "GenesectOx", "Genesect0x", "Charizard©x", "SceptileEX".
     */
    private val GLUED_SUFFIX = Regex("""^(\p{L}{3,}?[a-z])([Oo0©@e]x|EX|GX|Gx|V|VMAX|VSTAR)$""")

    /** Suffix words that follow a Pokémon's name. */
    private val SUFFIXES = setOf("ex", "gx", "v", "vmax", "vstar", "break", "lv.x", "prime", "legend", "star")

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
            // Modern cards print a tiny "HP" that OCR often drops: a lone
            // multiple of 10 in the top-right corner is the HP.
            ?: lines.filter { it.top < 0.12 && it.left > 0.5 }
                .firstNotNullOfOrNull { BARE_HP.find(it.text.trim())?.groupValues?.get(1)?.toIntOrNull() }
                ?.takeIf { it % 10 == 0 && it in 30..400 }

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

    /** Remove stage words, HP and stray symbols; normalise the ex/GX suffix. */
    fun cleanName(raw: String): String {
        var s = raw
            .replace(HP_AFTER, "")
            .replace(HP_BEFORE, "")
            .replace(Regex("""[^\p{L}\p{N}'.\-© ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        while (true) {
            val stripped = s.replace(STAGE_PREFIX, "").trim()
            if (stripped == s) break
            s = stripped
        }
        val words = s.split(' ').filter { it.isNotBlank() }.toMutableList()
        if (words.isEmpty()) return ""
        GLUED_SUFFIX.find(words[0])?.let { m ->
            val suffix = m.groupValues[2]
            val fixed = if (suffix.length == 2 && suffix[0] in "Oo0©@") "ex" else suffix
            words[0] = m.groupValues[1]
            words.add(1, fixed)
        }
        // Keep the name and any suffix words; drop trailing noise (e.g. a stray "170").
        val out = mutableListOf(words[0])
        for (w in words.drop(1)) {
            if (w.lowercase().removeSuffix(".") in SUFFIXES || w.first().isUpperCase() && w.all { it.isLetter() || it == '\'' || it == '.' }) out += w
            else break
        }
        return out.joinToString(" ").replace("©x", "ex")
    }

    /** Names to search for, most specific first: "Genesect ex", then "Genesect". */
    fun nameQueries(name: String?): List<String> {
        if (name.isNullOrBlank()) return emptyList()
        val base = name.split(' ').first()
        return listOf(name, base).distinct().filter { it.length >= 3 }
    }

    /** "025" -> "25", "TG05" -> "TG05" (the API stores numbers without leading zeros). */
    fun normalizeNumber(n: String): String {
        val m = Regex("""^([A-Za-z]*)0*(\d+[a-z]?)$""").find(n) ?: return n
        val (prefix, digits) = m.destructured
        return if (prefix.isEmpty()) digits else prefix.uppercase() + m.value.removePrefix(prefix)
    }
}
