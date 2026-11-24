package com.pokegrade.core.catalog

import kotlinx.serialization.Serializable

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
    /** TCGdex language code, e.g. "en", "ja", "de". */
    val language: String = "en",
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

class TcgApiException(message: String) : RuntimeException(message)
