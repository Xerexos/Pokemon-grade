package com.pokegrade.core

import com.pokegrade.core.catalog.CardTextParser
import com.pokegrade.core.catalog.OcrLine
import com.pokegrade.core.catalog.TcgDexClient
import com.pokegrade.core.pricing.PriceEstimator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hits the real TCGdex API. Runs only with LIVE_API=1. */
class TcgDexLiveTest {
    private val live = System.getenv("LIVE_API") == "1"
    private val client = TcgDexClient()

    @Test
    fun findsSceptileEx() {
        if (!live) return
        val text = CardTextParser.parse(listOf(
            OcrLine("BASIC Sceptile EX", 0.05, 0.03, 0.04),
            OcrLine("HP170", 0.7, 0.03, 0.03),
            OcrLine("Sleep Poison", 0.3, 0.6, 0.02),
            OcrLine("Unseen Claw", 0.3, 0.7, 0.02),
            OcrLine("7/98", 0.85, 0.95, 0.01),
        ))
        val cards = client.findCandidates(text)
        println(cards.map { "${it.id} ${it.name} hp=${it.hp} ${it.set.name} ${it.number}/${it.set.printedTotal}" })
        assertEquals("xy7-7", cards.first().id)
        val price = PriceEstimator.estimate(cards.first(), 9.0)
        println(price)
        assertTrue(price.rawMarketUsd != null)
    }

    @Test
    fun findsGenesectFromMisreadName() {
        if (!live) return
        val text = CardTextParser.parse(listOf(
            OcrLine("GenesectOx", 0.12, 0.03, 0.045),
            OcrLine("220", 0.75, 0.03, 0.03),
        ))
        println(text)
        val cards = client.findCandidates(text)
        println(cards.map { "${it.id} ${it.name} hp=${it.hp} ${it.set.name} ${it.number}/${it.set.printedTotal}" })
        assertTrue(cards.isNotEmpty() && cards.first().name.startsWith("Genesect ex"))
    }

    @Test
    fun findsByNumberWhenNameUnreadable() {
        if (!live) return
        val text = CardTextParser.parse(listOf(OcrLine("7/98", 0.85, 0.95, 0.01)))
        val cards = client.findCandidates(text)
        println(cards.map { "${it.id} ${it.name}" })
        assertTrue(cards.any { it.id == "xy7-7" })
    }

    @Test
    fun findsJapaneseCard() {
        if (!live) return
        val text = CardTextParser.parse(listOf(
            OcrLine("たね ピカチュウ", 0.05, 0.03, 0.045),
            OcrLine("HP60", 0.7, 0.04, 0.03),
            OcrLine("025/165", 0.05, 0.95, 0.015),
        ))
        val cards = client.findCandidates(text)
        println(cards.map { "${it.language} ${it.id} ${it.name} ${it.set.name} cm=${it.cardmarket?.prices?.trendPrice}" })
        assertEquals("SV2a-025", cards.first().id)
        assertEquals("ja", cards.first().language)
        val price = PriceEstimator.estimate(cards.first(), 9.0)
        println(price)
        assertTrue(price.rawMarketUsd != null)
    }

    @Test
    fun findsGermanCard() {
        if (!live) return
        val text = CardTextParser.parse(listOf(
            OcrLine("Nachtara VMAX", 0.12, 0.04, 0.045),
            OcrLine("KP310", 0.7, 0.04, 0.03),
            OcrLine("Schwäche Resistenz Rückzug", 0.1, 0.85, 0.02),
            OcrLine("215/203", 0.05, 0.95, 0.015),
        ))
        val cards = client.findCandidates(text)
        println(cards.map { "${it.language} ${it.id} ${it.name} tcp=${it.tcgplayer?.prices?.keys}" })
        assertEquals("swsh7-215", cards.first().id)
        assertEquals("de", cards.first().language)
    }
}
