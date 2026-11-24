package com.pokegrade.core.pricing

import com.pokegrade.core.catalog.CardTextParser
import com.pokegrade.core.catalog.ParsedCardText
import com.pokegrade.core.catalog.TcgCard
import com.pokegrade.core.grading.SlabLabel
import java.net.URLEncoder

/** Turns Pokémon names in other languages into English, for searching English marketplaces. */
object EnglishNames {

    private val species: Map<String, String> by lazy {
        EnglishNames::class.java.getResourceAsStream("/species_names.tsv")?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
        }.orEmpty()
    }

    /** Regional forms printed before the name. */
    private val REGION_PREFIX = listOf(
        "洗翠" to "Hisuian", "阿罗拉" to "Alolan", "阿羅拉" to "Alolan", "伽勒尔" to "Galarian", "伽勒爾" to "Galarian",
        "帕底亚" to "Paldean", "帕底亞" to "Paldean",
        "ヒスイ" to "Hisuian", "アローラ" to "Alolan", "ガラル" to "Galarian", "パルデア" to "Paldean",
        "히스이" to "Hisuian", "알로라" to "Alolan", "가라르" to "Galarian", "팔데아" to "Paldean",
        "Hisui-" to "Hisuian", "Alola-" to "Alolan", "Galar-" to "Galarian", "Paldea-" to "Paldean",
    )

    /** Regional forms printed after the name (French/Spanish/Italian/Portuguese). */
    private val REGION_SUFFIX = listOf(
        Regex("""\s+d[ei]\s+Hisui$""", RegexOption.IGNORE_CASE) to "Hisuian",
        Regex("""\s+d[ei]\s+Alola$""", RegexOption.IGNORE_CASE) to "Alolan",
        Regex("""\s+d[ei]\s+Galar$""", RegexOption.IGNORE_CASE) to "Galarian",
        Regex("""\s+d[ei]\s+Paldea$""", RegexOption.IGNORE_CASE) to "Paldean",
    )

    private val CARD_SUFFIX = Regex("""\s*(ex|EX|GX|V|VMAX|VSTAR|V-UNION|BREAK)$""")

    /**
     * "洗翠 卡蒂狗" -> "Hisuian Growlithe", "ピカチュウex" -> "Pikachu ex",
     * "Glurak-ex" -> "Charizard ex". Returns null if the name isn't a known Pokémon.
     */
    fun translate(name: String): String? {
        var s = name.trim()
        var suffix = ""
        CARD_SUFFIX.find(s)?.let { suffix = " " + it.groupValues[1]; s = s.removeRange(it.range).trim() }
        s = s.trimEnd('-', ' ')
        var region = ""
        for ((p, en) in REGION_PREFIX) if (s.startsWith(p)) { region = "$en "; s = s.removePrefix(p).trim(); break }
        for ((r, en) in REGION_SUFFIX) r.find(s)?.let { region = "$en "; s = s.removeRange(it.range).trim() }
        if (s.isEmpty()) return null
        val english = species[s]
            ?: species.entries.firstOrNull { it.key.equals(s, ignoreCase = true) }?.value
            // Names can carry extra words ("Team Rocket's ..."); try the longest known name inside.
            ?: species.keys.filter { it.length >= 2 && s.contains(it) }.maxByOrNull { it.length }?.let { species[it] }
            ?: return null
        return "$region$english$suffix"
    }
}

/** Links to marketplaces where people sell the card, searched for the right words. */
data class PriceLink(val label: String, val url: String)

object PriceLinks {

    private val LANGUAGE_WORD = mapOf(
        "ja" to "japanese", "ko" to "korean", "zh-tw" to "chinese", "zh-cn" to "chinese", "fr" to "french",
        "de" to "german", "es" to "spanish", "it" to "italian", "pt" to "portuguese", "id" to "indonesian", "th" to "thai",
    )

    /** The search phrase, e.g. "pokemon chinese Hisuian Growlithe 020" or "pokemon chinese Captain Pikachu 701 DSG 10". */
    fun query(text: ParsedCardText, card: TcgCard?, slab: SlabLabel?): String {
        val lang = card?.language ?: text.languages.firstOrNull() ?: "en"
        val labelName = slab?.description?.firstOrNull { line ->
            line.none { it.isDigit() } && line.length > 3 && line !in setOf("CHN", "JPN", "KOR", "ENG")
        }
        val name = when {
            card != null && lang == "en" -> card.name
            card != null -> EnglishNames.translate(card.name) ?: card.name
            labelName != null -> labelName.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            text.name != null -> EnglishNames.translate(text.name) ?: text.name
            else -> null
        }
        val number = card?.number
            ?: slab?.description?.firstNotNullOfOrNull { Regex("""#\s*([A-Z]{0,4}\d{1,4})""").find(it)?.groupValues?.get(1) }
            // As printed ("020"): that's how sellers write it in titles.
            ?: text.number
        val parts = mutableListOf("pokemon")
        LANGUAGE_WORD[lang]?.let(parts::add)
        name?.let(parts::add)
        number?.let(parts::add)
        if (slab != null) {
            (slab.grader?.name ?: slab.company)?.let(parts::add)
            slab.grade?.let { parts += if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
        }
        return parts.joinToString(" ")
    }

    fun links(query: String): List<PriceLink> {
        val q = URLEncoder.encode(query, "UTF-8")
        // 183454 = eBay "CCG Individual Cards".
        return listOf(
            PriceLink("eBay sold", "https://www.ebay.com/sch/i.html?_nkw=$q&_sacat=183454&LH_Sold=1&LH_Complete=1"),
            PriceLink("eBay for sale", "https://www.ebay.com/sch/i.html?_nkw=$q&_sacat=183454"),
            PriceLink("PriceCharting", "https://www.pricecharting.com/search-products?q=$q&type=prices"),
        )
    }
}
