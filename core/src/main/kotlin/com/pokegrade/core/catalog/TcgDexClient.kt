package com.pokegrade.core.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A search hit before we fetch full details. */
@Serializable
data class CardBrief(val id: String, val name: String, val localId: String, val image: String?, val language: String = "en") {
    val smallImage get() = image?.let { "$it/low.jpg" }
}

/**
 * Client for the free TCGdex API (https://tcgdex.dev): card data, official
 * scans, and daily TCGplayer (USD) + Cardmarket (EUR) prices. No key needed.
 */
class TcgDexClient(
    private val apiRoot: String = "https://api.tcgdex.net/v2",
    private val maxDetails: Int = 6,
) : CardCatalog {

    private fun base(lang: String) = "$apiRoot/$lang"

    private val json = Json { ignoreUnknownKeys = true }

    override fun findCandidates(text: ParsedCardText): List<TcgCard> {
        // Try the detected language first; European prints share English numbering,
        // so English is a useful fallback for them.
        for (lang in text.languages.ifEmpty { listOf("en") }.take(4)) {
            val found = findIn(lang, text)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun findIn(lang: String, text: ParsedCardText): List<TcgCard> {
        val number = text.number?.let(CardTextParser::normalizeNumber)
        val total = text.setTotal?.trimStart('0')?.toIntOrNull()

        val briefs = LinkedHashMap<String, CardBrief>()
        for (n in CardTextParser.nameQueries(text.name)) {
            val hits = searchByName(n, lang)
            if (hits.isNotEmpty()) { hits.forEach { briefs[it.id] = it }; break }
        }
        if (number != null && total != null) {
            // TCGdex's localId filter is a "contains" match, so filter exactly here.
            list(lang, "localId=${enc(number)}&set.cardCount.official=$total")
                .filter { sameNumber(it.localId, number) }
                .forEach { briefs[it.id] = it }
        }
        if (briefs.isEmpty()) return emptyList()

        // Fetch details for the most promising hits only.
        val shortlist = briefs.values.sortedByDescending { b ->
            (if (number != null && sameNumber(b.localId, number)) 4 else 0) +
                (if (text.name != null && b.name.equals(text.name, true)) 1 else 0)
        }.take(maxDetails)

        val cards = shortlist.mapNotNull { runCatching { getCard(it.id, lang) }.getOrNull() }
            .filter { CardRanker.plausible(it, text) }
        return CardRanker.rank(cards, text)
    }

    override fun searchByName(name: String, language: String): List<CardBrief> =
        if (name.isBlank()) emptyList() else list(language, "name=${enc(name.trim())}")

    override fun getCard(id: String, language: String): TcgCard =
        parseCard(json.parseToJsonElement(get("${base(language)}/cards/${enc(id)}")).jsonObject, language)

    private fun list(lang: String, query: String): List<CardBrief> {
        val arr = json.parseToJsonElement(get("${base(lang)}/cards?$query"))
        if (arr !is JsonArray) return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            CardBrief(o.str("id") ?: return@mapNotNull null, o.str("name").orEmpty(), o.str("localId").orEmpty(), o.str("image"), lang)
        }
    }

    internal fun parseCard(o: JsonObject, language: String = "en"): TcgCard {
        val set = o["set"]?.jsonObject
        val count = set?.get("cardCount") as? JsonObject
        val image = o.str("image")
        val pricing = o["pricing"] as? JsonObject
        val tp = pricing?.get("tcgplayer") as? JsonObject
        val cm = pricing?.get("cardmarket") as? JsonObject

        val tpPrices = tp?.entries
            ?.filter { it.value is JsonObject }
            ?.associate { (k, v) ->
                val p = v.jsonObject
                variantKey(k) to TcgPrice(
                    low = p.num("lowPrice"), mid = p.num("midPrice"), high = p.num("highPrice"),
                    market = p.num("marketPrice"), directLow = p.num("directLowPrice"),
                )
            }.orEmpty()
        val productId = tp?.values?.firstNotNullOfOrNull { (it as? JsonObject)?.str("productId") }

        return TcgCard(
            id = o.str("id").orEmpty(),
            language = language,
            name = o.str("name").orEmpty(),
            supertype = o.str("category"),
            subtypes = listOfNotNull(o.str("stage"), o.str("suffix")),
            hp = o.str("hp"),
            number = o.str("localId").orEmpty(),
            rarity = o.str("rarity"),
            artist = o.str("illustrator"),
            set = CardSet(
                id = set?.str("id").orEmpty(),
                name = set?.str("name").orEmpty(),
                printedTotal = count?.num("official")?.toInt(),
                total = count?.num("total")?.toInt(),
            ),
            images = CardImages(small = image?.let { "$it/low.jpg" }, large = image?.let { "$it/high.png" }),
            attacks = (o["attacks"] as? JsonArray).orEmpty().mapNotNull { a ->
                (a as? JsonObject)?.str("name")?.let { Attack(it, a.jsonObject.str("damage"), a.jsonObject.str("effect")) }
            },
            abilities = (o["abilities"] as? JsonArray).orEmpty().mapNotNull { a ->
                (a as? JsonObject)?.str("name")?.let { Ability(it, a.jsonObject.str("effect")) }
            },
            tcgplayer = if (tp == null) null else TcgPlayer(
                url = productId?.let { "https://www.tcgplayer.com/product/$it" },
                updatedAt = tp.str("updated")?.take(10),
                prices = tpPrices,
            ),
            cardmarket = if (cm == null) null else CardMarket(
                url = null,
                updatedAt = cm.str("updated")?.take(10),
                prices = CardMarketPrices(
                    averageSellPrice = cm.num("avg"), lowPrice = cm.num("low"), trendPrice = cm.num("trend"),
                    avg7 = cm.num("avg7"), avg30 = cm.num("avg30"), reverseHoloTrend = cm.num("trend-holo"),
                ),
            ),
        )
    }

    /** TCGdex uses "reverse-holofoil"; the rest of the app uses TCGplayer's camelCase names. */
    private fun variantKey(k: String) = when (k) {
        "reverse-holofoil" -> "reverseHolofoil"
        "1st-edition-holofoil" -> "1stEditionHolofoil"
        "1st-edition" -> "1stEditionNormal"
        "unlimited-holofoil" -> "unlimitedHolofoil"
        else -> k
    }

    private fun get(url: String): String = Http.get(url)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun sameNumber(a: String, b: String) =
        CardTextParser.normalizeNumber(a).equals(CardTextParser.normalizeNumber(b), ignoreCase = true)
}

/** Card lookup backend. */
interface CardCatalog {
    fun findCandidates(text: ParsedCardText): List<TcgCard>
    fun searchByName(name: String, language: String = "en"): List<CardBrief>
    fun getCard(id: String, language: String = "en"): TcgCard
}

/** Languages TCGdex supports, with display names. */
val CARD_LANGUAGES = linkedMapOf(
    "en" to "English", "ja" to "Japanese", "fr" to "French", "de" to "German", "es" to "Spanish",
    "it" to "Italian", "pt" to "Portuguese", "ko" to "Korean", "zh-tw" to "Chinese (Trad.)",
    "zh-cn" to "Chinese (Simp.)", "id" to "Indonesian", "th" to "Thai",
)

object CardRanker {
    /**
     * A number match alone isn't enough: "030/115" exists in many sets. Drop
     * candidates whose HP contradicts the card unless the name also matches.
     */
    fun plausible(c: TcgCard, text: ParsedCardText): Boolean {
        val nameMatch = text.name != null && (c.name.contains(text.name, true) || text.name.contains(c.name, true))
        val hpConflict = text.hp != null && c.hp?.toIntOrNull() != null && c.hp.toIntOrNull() != text.hp
        return nameMatch || !hpConflict
    }

    fun rank(cards: List<TcgCard>, text: ParsedCardText): List<TcgCard> =
        cards.sortedByDescending { c ->
            var s = 0
            if (text.hp != null && c.hp?.toIntOrNull() == text.hp) s += 3
            if (text.number != null &&
                CardTextParser.normalizeNumber(c.number).equals(CardTextParser.normalizeNumber(text.number), true)) s += 4
            if (text.setTotal != null && c.set.printedTotal == text.setTotal.trimStart('0').toIntOrNull()) s += 3
            if (text.name != null && c.name.equals(text.name, true)) s += 2
            // "ex" (modern) vs "EX" (2012-2016) are different cards.
            if (text.name != null && c.name == text.name) s += 1
            val lower = text.allText.lowercase()
            s += c.attacks.count { lower.contains(it.name.lowercase()) }
            s
        }
}

/** Tiny HTTP helper with retries on server errors (card APIs can be flaky). */
object Http {
    fun get(url: String, headers: Map<String, String> = emptyMap(), attempts: Int = 3): String {
        var last: Exception? = null
        repeat(attempts) { i ->
            if (i > 0) Thread.sleep(400L * i)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach(conn::setRequestProperty)
            try {
                val code = conn.responseCode
                if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty().take(200)
                last = TcgApiException("HTTP $code from ${URL(url).host} $err")
                if (code in 400..499 && code != 429) throw last!!
            } catch (e: IOException) {
                last = e
            } finally {
                conn.disconnect()
            }
        }
        throw last ?: TcgApiException("Request failed")
    }
}

private fun JsonObject.str(key: String): String? = when (val v = this[key]) {
    null, is JsonNull -> null
    is JsonPrimitive -> v.content
    else -> null
}

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
