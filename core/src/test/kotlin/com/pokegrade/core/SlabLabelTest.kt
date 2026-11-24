package com.pokegrade.core

import com.pokegrade.core.catalog.CardRanker
import com.pokegrade.core.catalog.CardSet
import com.pokegrade.core.catalog.CardTextParser
import com.pokegrade.core.catalog.OcrLine
import com.pokegrade.core.catalog.TcgCard
import com.pokegrade.core.grading.SlabLabelParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlabLabelTest {

    /** Text as it appears on the DSG Captain Pikachu slab in the screenshot. */
    private val dsgPikachu = listOf(
        OcrLine("2025 POKEMON GEM PACK VOL.1", 0.08, 0.03, 0.02),
        OcrLine("CHN", 0.08, 0.05, 0.02),
        OcrLine("CAPTAIN PIKACHU", 0.08, 0.07, 0.02),
        OcrLine("#701 ENERGY REV HOLO", 0.08, 0.09, 0.02),
        OcrLine("GEM MINT", 0.72, 0.035, 0.02),
        OcrLine("10", 0.75, 0.06, 0.035),
        OcrLine("8169090106", 0.72, 0.105, 0.015),
        OcrLine("DIAMOND SERVICE GRADING", 0.3, 0.125, 0.015),
        OcrLine("船长皮卡丘", 0.2, 0.27, 0.03),
        OcrLine("HP70", 0.75, 0.27, 0.02),
        OcrLine("伏特攻击", 0.3, 0.6, 0.02),
    )

    @Test
    fun readsDsgLabel() {
        val s = SlabLabelParser.parse(dsgPikachu)
        println(s)
        assertNotNull(s)
        assertEquals("DSG", s.company)
        assertEquals(10.0, s.grade)
        assertEquals("GEM MINT", s.gradeWords)
        assertEquals("8169090106", s.certNumber)
        assertTrue("CAPTAIN PIKACHU" in s.description)
        assertTrue(s.labelBottom in 0.13..0.2, "label bottom ${s.labelBottom}")
    }

    @Test
    fun readsPsaInlineGrade() {
        val s = SlabLabelParser.parse(listOf(
            OcrLine("2023 POKEMON SV 151 #199 CHARIZARD EX", 0.05, 0.03, 0.02),
            OcrLine("GEM MT 10", 0.7, 0.03, 0.02),
            OcrLine("PSA", 0.75, 0.06, 0.02),
            OcrLine("84512345", 0.7, 0.09, 0.02),
        ))
        println(s)
        assertNotNull(s)
        assertEquals("PSA", s.company)
        assertEquals(10.0, s.grade)
        assertEquals("84512345", s.certNumber)
    }

    @Test
    fun rawCardIsNotASlab() {
        assertNull(SlabLabelParser.parse(listOf(
            OcrLine("BASIC Pikachu HP60", 0.05, 0.04, 0.04),
            OcrLine("Weakness Resistance Retreat", 0.1, 0.85, 0.02),
            OcrLine("025/165", 0.05, 0.95, 0.015),
        )))
        // TAG TEAM card text must not look like the TAG grading company.
        assertNull(SlabLabelParser.parse(listOf(OcrLine("TAG TEAM Pikachu & Zekrom-GX HP240", 0.05, 0.04, 0.04))))
    }

    @Test
    fun simplifiedChineseDetected() {
        assertEquals("zh-cn", CardTextParser.detectLanguages("喷火龙V 利爪劈开 火焰旋涡").first())
        assertEquals("zh-tw", CardTextParser.detectLanguages("噴火龍V 火焰旋渦 擊").first())
    }

    @Test
    fun numberOnlyMatchWithWrongHpIsDropped() {
        val murkrow = TcgCard(id = "ex10-30", name = "Murkrow", hp = "70", number = "30",
            set = CardSet(id = "ex10", name = "Unseen Forces", printedTotal = 115))
        val text = CardTextParser.parse(listOf(OcrLine("HP220", 0.75, 0.04, 0.03), OcrLine("030/115", 0.05, 0.95, 0.015)))
        assertTrue(!CardRanker.plausible(murkrow, text))
    }

    /** What OCR actually managed in the second screenshot: no logo, no "10". */
    @Test
    fun detectsSlabFromGradeWordsAndCertOnly() {
        val lines = listOf(
            OcrLine("2025 POKEMON GEM PACK VOL.1", 0.08, 0.02, 0.02),
            OcrLine("CAPTAIN PIKACHU", 0.08, 0.06, 0.02),
            OcrLine("GEM MINT", 0.72, 0.03, 0.02),
            OcrLine("8169090106", 0.72, 0.1, 0.015),
            OcrLine("皮卡丘", 0.2, 0.27, 0.03),
        )
        val s = SlabLabelParser.parse(lines)
        println(s)
        assertNotNull(s)
        assertEquals("8169090106", s.certNumber)
        assertEquals("GEM MINT", s.gradeWords)
        assertEquals(10.0, s.grade)
        // And the parser must not call the card "GEM MINT".
        val t = CardTextParser.parse(listOf(OcrLine("GEM MINT", 0.7, 0.03, 0.05), OcrLine("船长皮卡丘", 0.2, 0.08, 0.03)))
        assertEquals("船长皮卡丘", t.name)
    }
}
