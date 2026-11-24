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
    /** TCGdex language codes to search, most likely first (e.g. ["ja"], ["de", "en"]). */
    val languages: List<String> = listOf("en"),
)

/**
 * Pulls the card name, HP and collector number out of ML Kit OCR output.
 */
object CardTextParser {
    private val NUMBER = Regex("""\b([A-Z]{0,4}\d{1,4}[a-z]?)\s*/\s*([A-Z]{0,4}\d{1,4})\b""")
    private val PROMO = Regex("""\b(SWSH\d{3}|SM\d{2,3}|XY\d{2,3}|BW\d{2,3}|SVP?\s?\d{2,3}|HGSS\d{2})\b""")
    // HP is "KP" in German, "PV" in French, "PS" in Spanish/Italian/Portuguese.
    private val HP_AFTER = Regex("""\b(?:HP|KP|PV|PS)\s*(\d{2,3})\b""", RegexOption.IGNORE_CASE)
    private val BARE_HP = Regex("""^(\d{2,3})$""")
    private val HP_BEFORE = Regex("""\b(\d{2,3})\s*(?:HP|KP|PV|PS)\b""", RegexOption.IGNORE_CASE)

    /** Lines near the name that are never the name. */
    private val NOT_NAME = listOf(
        "evolves", "trainer", "item", "supporter", "stadium", "energy",
        "pokémon tool", "pokemon tool", "put ", "illus", "hp", "ability", "weakness", "resistance",
        "retreat", "tera", "rule", "pokémon ex rule", "pokemon ex rule",
        // Slab label words.
        "gem mint", "gem mt", "mint", "pristine", "black label", "nm-mt", "near mint", "authentic",
        "pokemon", "pokémon", "diamond service", "professional sports", "beckett", "certified",
    )

    /** Stage text printed on the same line, before the name (several languages). */
    private val STAGE_PREFIX = Regex(
        """^(basic|stage\s*[12]|restored|break|v-union|vmax|vstar|level-up|baby|""" +
            // de / fr / es / it / pt / id
            """basis|phase\s*[12]|de\s+base|base|niveau\s*[12]|b[áa]sico|fase\s*[12]|est[áa]gio\s*[12]|dasar|tahap\s*[12])\b[\s:.\-]*""" +
            // ja / ko / zh: no word boundaries in these scripts
            """|^(たね|[12１２]進化|기본|[12]진화|基礎|基础|[12]階進化|[12]阶进化)[\s:.\-]*""",
        RegexOption.IGNORE_CASE,
    )

    /** Words printed on every card, used to tell the language apart. */
    private val LANGUAGE_WORDS = mapOf(
        "en" to listOf("weakness", "resistance", "retreat", "ability", "evolves from", "illus"),
        "fr" to listOf("faiblesse", "résistance", "retraite", "talent", "évolue de"),
        "de" to listOf("schwäche", "resistenz", "rückzug", "fähigkeit", "entwickelt sich aus"),
        "es" to listOf("debilidad", "resistencia", "retirada", "habilidad", "evoluciona de"),
        "it" to listOf("debolezza", "resistenza", "ritirata", "abilità", "si evolve da"),
        "pt" to listOf("fraqueza", "resistência", "recuo", "habilidade", "evolui de"),
        "id" to listOf("kelemahan", "ketahanan", "mundur", "kemampuan"),
    )

    /** TCGdex language codes, most likely first. */
    fun detectLanguages(text: String): List<String> {
        val kana = text.count { Character.UnicodeBlock.of(it) in KANA }
        val hangul = text.count { Character.UnicodeBlock.of(it) in HANGUL }
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val thai = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.THAI }
        // Readers hallucinate other scripts (the Korean reader "sees" Hangul in Chinese
        // text), so judge by proportions, not by any single character.
        return when {
            kana >= 2 && kana >= han * 0.15 -> listOf("ja")
            hangul >= 2 && hangul >= han * 0.5 -> listOf("ko")
            thai >= 2 -> listOf("th")
            han >= 2 -> {
                // Simplified (mainland) vs Traditional (Taiwan/HK) Chinese.
                val simplified = text.count { it in SIMPLIFIED }
                val traditional = text.count { it in TRADITIONAL }
                if (simplified > traditional) listOf("zh-cn", "zh-tw", "ja") else listOf("zh-tw", "zh-cn", "ja")
            }
            else -> {
                val lower = text.lowercase()
                val scores = LANGUAGE_WORDS.mapValues { (_, words) -> words.count { lower.contains(it) } }
                val best = scores.filter { it.value > 0 }.entries.sortedByDescending { it.value }.map { it.key }
                (best + "en").distinct()
            }
        }
    }

    /** Common card-text characters that differ between Simplified and Traditional Chinese. */
    private const val SIMPLIFIED = "龙击发进阶伤对战场宝梦为从张个时将后气种连选这们备给让该点损态础烧闪电恶钢虫飞冲无动级极灵斗体卡奖弃换输赢术护伙变"
    private const val TRADITIONAL = "龍擊發進階傷對戰場寶夢為從張個時將後氣種連選這們備給讓該點損態礎燒閃電惡鋼蟲飛衝無動級極靈鬥體獎棄換輸贏術護夥變"

    private val KANA = setOf(Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA, Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS)
    private val HANGUL = setOf(Character.UnicodeBlock.HANGUL_SYLLABLES, Character.UnicodeBlock.HANGUL_JAMO, Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO)

    private fun isCjk(s: String) = s.any {
        val sc = Character.UnicodeScript.of(it.code)
        sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA ||
            sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL
    }

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
            // Misread label around the number, e.g. "w80円": take the number if the
            // rest of the line is just a few stray characters.
            ?: lines.filter { it.top < 0.12 && it.left > 0.5 }
                .firstNotNullOfOrNull { l ->
                    val m = Regex("""(?<!\d)(\d{2,3})(?!\d)""").find(l.text) ?: return@firstNotNullOfOrNull null
                    val rest = l.text.replace(m.value, "").count { !it.isWhitespace() }
                    m.groupValues[1].toIntOrNull()?.takeIf { rest <= 4 && it % 10 == 0 && it in 30..400 }
                }

        return ParsedCardText(
            name = guessName(lines),
            hp = hp,
            number = numberMatch?.groupValues?.get(1) ?: promo?.value?.replace(" ", ""),
            setTotal = numberMatch?.groupValues?.get(2),
            allText = all,
            languages = detectLanguages(all),
        )
    }

    private fun guessName(lines: List<OcrLine>): String? {
        val candidates = lines
            .filter { it.top < 0.16 }
            // The HP sits in the top-right and contains a number ("HP80", often misread
            // as "w80円"); the name is on the left and never has a two-digit number.
            .filter { !(it.left >= 0.5 && Regex("""\d{2,}""").containsMatchIn(it.text)) }
            .map { it to cleanName(it.text) }
            .filter { (_, n) ->
                // CJK names can be just two characters (e.g. 喵喵).
                (if (isCjk(n)) n.count { it.isLetter() } >= 2 else n.length >= 3 && n.count { it.isLetter() } >= 3) &&
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
            val cjkWord = isCjk(w) && w.all { it.isLetter() }
            if (w.lowercase().removeSuffix(".") in SUFFIXES || cjkWord ||
                w.first().isUpperCase() && w.all { it.isLetter() || it == '\'' || it == '.' }) out += w
            else break
        }
        return out.joinToString(" ").replace("©x", "ex")
    }

    /**
     * Combine what several OCR readers saw ("latin", "ja", "zh", "ko"). Each is
     * good at different things: the Latin reader gets numbers and English text,
     * the Asian readers get names in their script. Take the name from the reader
     * that matches the card's script and the numbers from whichever read them.
     */
    fun combine(readers: Map<String, List<OcrLine>>): ParsedCardText {
        val parsed = readers.mapValues { parse(it.value) }
        val allText = readers.values.flatten().joinToString("\n") { it.text }
        val languages = detectLanguages(allText)
        val order = when (languages.first()) {
            "ja" -> listOf("ja", "zh", "ko", "latin")
            "ko" -> listOf("ko", "ja", "zh", "latin")
            "zh-cn", "zh-tw" -> listOf("zh", "ja", "ko", "latin")
            else -> listOf("latin", "ja", "zh", "ko")
        }
        val asian = languages.first() in setOf("ja", "ko", "zh-cn", "zh-tw", "th")
        // For Asian cards, a name in Latin letters is a misread; prefer a CJK one.
        val name = order.firstNotNullOfOrNull { r -> parsed[r]?.name?.takeIf { !asian || isCjk(it) } }
            ?: order.firstNotNullOfOrNull { parsed[it]?.name }
        val numberFrom = (listOf("latin") + order).distinct()
        val withNumber = numberFrom.firstNotNullOfOrNull { r -> parsed[r]?.takeIf { it.number != null } }
        return ParsedCardText(
            name = name,
            hp = numberFrom.firstNotNullOfOrNull { parsed[it]?.hp },
            number = withNumber?.number,
            setTotal = withNumber?.setTotal,
            allText = allText,
            languages = languages,
        )
    }

    /** Names to search for, most specific first: "Genesect ex", then "Genesect". */
    fun nameQueries(name: String?): List<String> {
        if (name.isNullOrBlank()) return emptyList()
        val base = name.split(' ').first()
        // "ピカチュウex" -> also try "ピカチュウ".
        val noSuffix = base.replace(Regex("""(?<=[^\x00-\x7F])(ex|EX|GX|V|VMAX|VSTAR)$"""), "")
        return listOf(name, base, noSuffix).distinct().filter { it.length >= (if (isCjk(it)) 2 else 3) }
    }

    /** "025" -> "25", "TG05" -> "TG05" (the API stores numbers without leading zeros). */
    fun normalizeNumber(n: String): String {
        val m = Regex("""^([A-Za-z]*)0*(\d+[a-z]?)$""").find(n) ?: return n
        val (prefix, digits) = m.destructured
        return if (prefix.isEmpty()) digits else prefix.uppercase() + m.value.removePrefix(prefix)
    }
}
