package com.pokegrade.core

import com.pokegrade.core.auth.AuthenticityChecker
import com.pokegrade.core.auth.CheckStatus
import com.pokegrade.core.auth.Verdict
import com.pokegrade.core.catalog.CardImages
import com.pokegrade.core.catalog.CardSet
import com.pokegrade.core.catalog.CardTextParser
import com.pokegrade.core.catalog.OcrLine
import com.pokegrade.core.catalog.TcgCard
import com.pokegrade.core.catalog.TcgPlayer
import com.pokegrade.core.catalog.TcgPrice
import com.pokegrade.core.catalog.Attack
import com.pokegrade.core.image.RgbImage
import com.pokegrade.core.pricing.PriceEstimator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogAndAuthTest {

    private val ocr = listOf(
        OcrLine("BASIC", 0.05, 0.03, 0.02),
        OcrLine("Pikachu", 0.12, 0.04, 0.045),
        OcrLine("HP 60", 0.7, 0.045, 0.03),
        OcrLine("Thunder Jolt 30", 0.1, 0.62, 0.03),
        OcrLine("weakness resistance retreat", 0.1, 0.85, 0.02),
        OcrLine("Illus. Atsuko Nishida 025/198", 0.05, 0.94, 0.015),
    )

    private val pikachu = TcgCard(
        id = "sv1-25", name = "Pikachu", hp = "60", number = "25", rarity = "Common",
        set = CardSet(id = "sv1", name = "Scarlet & Violet", series = "Scarlet & Violet", printedTotal = 198),
        images = CardImages("https://images.pokemontcg.io/sv1/25.png", "https://images.pokemontcg.io/sv1/25_hires.png"),
        attacks = listOf(Attack("Thunder Jolt", "30")),
        tcgplayer = TcgPlayer(prices = mapOf("normal" to TcgPrice(low = 0.1, market = 0.4, high = 3.0), "reverseHolofoil" to TcgPrice(market = 1.2))),
    )

    @Test
    fun parsesNameHpAndNumber() {
        val t = CardTextParser.parse(ocr)
        assertEquals("Pikachu", t.name)
        assertEquals(60, t.hp)
        assertEquals("025", t.number)
        assertEquals("198", t.setTotal)
        assertEquals("25", CardTextParser.normalizeNumber("025"))
        assertEquals("TG05", CardTextParser.normalizeNumber("TG05"))
    }

    @Test
    fun parsesExSuffixAndSecretRare() {
        val t = CardTextParser.parse(listOf(
            OcrLine("Charizard ex", 0.1, 0.04, 0.05),
            OcrLine("HP330", 0.7, 0.04, 0.03),
            OcrLine("223/197", 0.05, 0.95, 0.015),
        ))
        assertEquals("Charizard ex", t.name)
        assertEquals(330, t.hp)
        assertEquals("223", t.number)
    }

    @Test
    fun pricesRawAndGraded() {
        val raw = PriceEstimator.estimate(pikachu, grade = 9.0)
        assertEquals("normal", raw.variant)
        assertEquals(0.4, raw.rawMarketUsd)
        assertEquals(0.4, raw.estimatedValueUsd)
        val slab = PriceEstimator.estimate(pikachu, grade = 10.0, gradedSlab = true)
        assertTrue(slab.estimatedValueUsd!! > 1.0)
        val played = PriceEstimator.estimate(pikachu, grade = 5.0)
        assertTrue(played.estimatedValueUsd!! < 0.4)
    }

    @Test
    fun genuineCardPassesChecks() {
        val photo = SyntheticCard.draw().crop(80, 80, 710, 959)
        val ref = SyntheticCard.draw(seed = 7).crop(80, 80, 710, 959)
        val report = AuthenticityChecker.check(CardTextParser.parse(ocr), pikachu, photo, ref)
        println(report)
        assertEquals(Verdict.LIKELY_AUTHENTIC, report.verdict)
    }

    @Test
    fun wrongHpAndUnknownSetIsFlagged() {
        val fakeText = CardTextParser.parse(listOf(
            OcrLine("Pikachu", 0.12, 0.04, 0.045),
            OcrLine("HP 250", 0.7, 0.045, 0.03),
            OcrLine("Thunder Jolt 30 lorem ipsum dolor sit amet", 0.1, 0.62, 0.03),
            OcrLine("025/150", 0.05, 0.94, 0.015),
        ))
        val photo = SyntheticCard.draw().crop(80, 80, 710, 959)
        val report = AuthenticityChecker.check(fakeText, pikachu, photo, photo)
        println(report)
        assertEquals(Verdict.LIKELY_FAKE, report.verdict)
        assertTrue(report.checks.any { it.name == "HP value" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun washedOutColoursAreFlagged() {
        val ref = SyntheticCard.draw().crop(80, 80, 710, 959)
        // Desaturate heavily, like a cheap reprint.
        val dull = RgbImage(ref.width, ref.height, IntArray(ref.pixels.size) { i ->
            val p = ref.pixels[i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val l = (r + g + b) / 3
            RgbImage.rgb((l + (r - l) / 4), (l + (g - l) / 4), (l + (b - l) / 4))
        })
        val checks = AuthenticityChecker.compareImages(dull, ref)
        println(checks)
        assertTrue(checks.first { it.name == "Colour saturation" }.status == CheckStatus.FAIL)
    }

    @Test
    fun noDatabaseMatchAloneIsInconclusive() {
        val photo = SyntheticCard.draw().crop(80, 80, 710, 959)
        val report = AuthenticityChecker.check(CardTextParser.parse(ocr), null, photo, null)
        assertEquals(Verdict.INCONCLUSIVE, report.verdict)
    }

    @Test
    fun lookupFailureIsNotEvidenceOfFake() {
        val photo = SyntheticCard.draw().crop(80, 80, 710, 959)
        val report = AuthenticityChecker.check(CardTextParser.parse(ocr), null, photo, null, lookupFailed = true)
        assertEquals(Verdict.INCONCLUSIVE, report.verdict)
        assertTrue(report.checks.none { it.status == CheckStatus.FAIL })
    }

    @Test
    fun cleansStylisedExAndStagePrefix() {
        assertEquals("Genesect ex", CardTextParser.cleanName("GenesectOx"))
        assertEquals("Genesect ex", CardTextParser.cleanName("Genesect0x 220"))
        assertEquals("Sceptile EX", CardTextParser.cleanName("BASIC Sceptile EX HP170"))
        assertEquals("Sceptile EX", CardTextParser.cleanName("SceptileEX"))
        assertEquals("Charizard ex", CardTextParser.cleanName("STAGE 2 Charizard ex"))
        assertEquals("Mega Venusaur ex", CardTextParser.cleanName("Mega Venusaur ex"))
        assertEquals("Pikachu", CardTextParser.cleanName("Pikachu"))
        assertEquals(listOf("Genesect ex", "Genesect"), CardTextParser.nameQueries("Genesect ex"))
        val t = CardTextParser.parse(listOf(OcrLine("BASIC Sceptile EX", 0.05, 0.03, 0.04), OcrLine("7/98", 0.8, 0.95, 0.01)))
        assertEquals("Sceptile EX", t.name)
        assertEquals("7", t.number)
        assertEquals("98", t.setTotal)
    }

    @Test
    fun detectsCardLanguage() {
        assertEquals(listOf("ja"), CardTextParser.detectLanguages("たね ピカチュウ HP60"))
        assertEquals(listOf("ko"), CardTextParser.detectLanguages("기본 피카츄 HP60"))
        assertEquals("zh-tw", CardTextParser.detectLanguages("基礎 皮卡丘 HP60").first())
        assertEquals(listOf("de", "en"), CardTextParser.detectLanguages("Nachtara VMAX Schwäche Resistenz Rückzug"))
        assertEquals(listOf("fr", "en"), CardTextParser.detectLanguages("Faiblesse Résistance Retraite"))
        assertEquals(listOf("en"), CardTextParser.detectLanguages("Weakness Resistance Retreat"))
    }

    @Test
    fun parsesJapaneseCard() {
        val t = CardTextParser.parse(listOf(
            OcrLine("たね", 0.05, 0.03, 0.02),
            OcrLine("ピカチュウ", 0.15, 0.04, 0.045),
            OcrLine("HP60", 0.7, 0.04, 0.03),
            OcrLine("025/165", 0.05, 0.95, 0.015),
        ))
        assertEquals("ピカチュウ", t.name)
        assertEquals(60, t.hp)
        assertEquals("025", t.number)
        assertEquals(listOf("ja"), t.languages)
        assertEquals("ピカチュウ", CardTextParser.cleanName("たね ピカチュウ"))
        assertEquals(listOf("ピカチュウex", "ピカチュウ"), CardTextParser.nameQueries("ピカチュウex"))
        assertEquals("Nachtara VMAX", CardTextParser.cleanName("BASIS Nachtara VMAX"))
        assertEquals(310, CardTextParser.parse(listOf(OcrLine("Nachtara VMAX KP310", 0.1, 0.04, 0.04))).hp)
        assertEquals(60, CardTextParser.parse(listOf(OcrLine("Pikachu PV 60", 0.1, 0.04, 0.04))).hp)
        assertEquals("Nachtara VMAX", CardTextParser.parse(listOf(OcrLine("BASIS Nachtara VMAX KP310", 0.1, 0.04, 0.04))).name)
    }
}
