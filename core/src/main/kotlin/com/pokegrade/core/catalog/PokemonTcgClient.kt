package com.pokegrade.core.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Serializable
data class TcgPrice(
    val low: Double? = null,
    val mid: Double? = null,
    val high: Double? = null,
    val market: Double? = null,
    val directLow: Double? = null,
)

@Serializable
data class TcgPlayer(
    val url: String? = null,
    val updatedAt: String? = null,
    /** Keys like "normal", "holofoil", "reverseHolofoil", "1stEditionHolofoil". */
    val prices: Map<String, TcgPrice> = emptyMap(),
)

@Serializable
data class CardMarketPrices(
    val averageSellPrice: Double? = null,
    val lowPrice: Double? = null,
    val trendPrice: Double? = null,
    val avg7: Double? = null,
    val avg30: Double? = null,
    val reverseHoloTrend: Double? = null,
)

@Serializable
data class CardMarket(
    val url: String? = null,
    val updatedAt: String? = null,
    val prices: CardMarketPrices? = null,
)

@Serializable
data class CardSet(
    val id: String,
    val name: String,
    val series: String? = null,
    val printedTotal: Int? = null,
    val total: Int? = null,
    val releaseDate: String? = null,
)

@Serializable
data class CardImages(val small: String? = null, val large: String? = null)

@Serializable
data class Attack(val name: String, val damage: String? = null, val text: String? = null)

@Serializable
data class Ability(val name: String, val text: String? = null)

@Serializable
data class TcgCard(
    val id: String,
    val name: String,
    val supertype: String? = null,
    val subtypes: List<String> = emptyList(),
    val hp: String? = null,
    val number: String,
    val rarity: String? = null,
    val artist: String? = null,
    val set: CardSet,
    val images: CardImages = CardImages(),
    val attacks: List<Attack> = emptyList(),
    val abilities: List<Ability> = emptyList(),
    val tcgplayer: TcgPlayer? = null,
    val cardmarket: CardMarket? = null,
)

@Serializable
private data class CardsResponse(val data: List<TcgCard> = emptyList(), @SerialName("totalCount") val total: Int = 0)

/**
 * Client for the free Pokémon TCG API (https://pokemontcg.io), which returns
 * card data, official scans and daily TCGplayer / Cardmarket prices.
 * An API key is optional but raises the rate limit.
 */
class PokemonTcgClient(
    private val apiKey: String? = null,
    private val baseUrl: String = "https://api.pokemontcg.io/v2",
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Best guesses first. Tries progressively looser queries. */
    fun findCandidates(text: ParsedCardText, limit: Int = 12): List<TcgCard> {
        val name = text.name?.let(::sanitize)?.takeIf { it.isNotBlank() }
        val number = text.number?.let(CardTextParser::normalizeNumber)?.let(::sanitize)
        val total = text.setTotal?.trimStart('0')?.toIntOrNull()

        val queries = buildList {
            if (name != null && number != null && total != null) add("""name:"$name*" number:"$number" set.printedTotal:$total""")
            if (name != null && number != null) add("""name:"$name*" number:"$number"""")
            if (number != null && total != null) add("""number:"$number" set.printedTotal:$total""")
            if (name != null) add("""name:"$name"""")
            if (name != null) add("""name:"${name.split(' ').first()}*"""")
        }
        for (q in queries) {
            val found = search(q, limit)
            if (found.isNotEmpty()) return rank(found, text)
        }
        return emptyList()
    }

    fun search(query: String, pageSize: Int = 12): List<TcgCard> {
        val url = "$baseUrl/cards?q=${URLEncoder.encode(query, "UTF-8")}&pageSize=$pageSize&orderBy=-set.releaseDate"
        val body = get(url)
        return json.decodeFromString(CardsResponse.serializer(), body).data
    }

    private fun rank(cards: List<TcgCard>, text: ParsedCardText): List<TcgCard> =
        cards.sortedByDescending { c ->
            var s = 0
            if (text.hp != null && c.hp?.toIntOrNull() == text.hp) s += 3
            if (text.number != null && c.number.equals(CardTextParser.normalizeNumber(text.number), true)) s += 4
            if (text.setTotal != null && c.set.printedTotal == text.setTotal.trimStart('0').toIntOrNull()) s += 3
            if (text.name != null && c.name.equals(text.name, true)) s += 2
            val lower = text.allText.lowercase()
            s += c.attacks.count { lower.contains(it.name.lowercase()) }
            s
        }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        apiKey?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("X-Api-Key", it) }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw TcgApiException("Pokémon TCG API returned HTTP $code ${err.take(200)}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sanitize(s: String) = s.replace("\"", "").replace("*", "").trim()
}

class TcgApiException(message: String) : RuntimeException(message)
