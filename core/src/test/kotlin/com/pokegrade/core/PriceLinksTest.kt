package com.pokegrade.core

import com.pokegrade.core.catalog.CardTextParser
import com.pokegrade.core.catalog.OcrLine
import com.pokegrade.core.grading.SlabLabelParser
import com.pokegrade.core.pricing.EnglishNames
import com.pokegrade.core.pricing.PriceLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriceLinksTest {
    @Test
    fun translatesNames() {
        assertEquals("Hisuian Growlithe", EnglishNames.translate("洗翠 卡蒂狗"))
        assertEquals("Hisuian Growlithe", EnglishNames.translate("洗翠卡蒂狗"))
        assertEquals("Charizard V", EnglishNames.translate("喷火龙V"))
        assertEquals("Pikachu ex", EnglishNames.translate("ピカチュウex"))
        assertEquals("Growlithe", EnglishNames.translate("가디"))
        assertEquals("Umbreon VMAX", EnglishNames.translate("Nachtara VMAX"))
        assertEquals("Pikachu", EnglishNames.translate("船长皮卡丘")?.removePrefix("Captain "))
    }

    @Test
    fun queryForRawChineseCard() {
        val text = CardTextParser.parse(listOf(
            OcrLine("基础", 0.06, 0.02, 0.015),
            OcrLine("洗翠 卡蒂狗", 0.18, 0.035, 0.03),
            OcrLine("w80円", 0.74, 0.03, 0.045),
            OcrLine("烧光", 0.38, 0.58, 0.02),
            OcrLine("020/107", 0.15, 0.96, 0.012),
        ))
        val q = PriceLinks.query(text, null, null)
        println(q)
        assertEquals("pokemon chinese Hisuian Growlithe 020", q)
        assertTrue(PriceLinks.links(q).first().url.contains("LH_Sold=1"))
    }

    @Test
    fun queryForSlab() {
        val lines = listOf(
            OcrLine("2025 POKEMON GEM PACK VOL.1", 0.08, 0.02, 0.02),
            OcrLine("CHN", 0.08, 0.04, 0.02),
            OcrLine("CAPTAIN PIKACHU", 0.08, 0.06, 0.02),
            OcrLine("#701 ENERGY REV HOLO", 0.08, 0.08, 0.02),
            OcrLine("GEM MINT", 0.72, 0.03, 0.02),
            OcrLine("8169090106", 0.72, 0.1, 0.015),
            OcrLine("DIAMOND SERVICE GRADING", 0.3, 0.12, 0.015),
        )
        val slab = SlabLabelParser.parse(lines)
        val text = CardTextParser.parse(listOf(OcrLine("船长皮卡丘", 0.2, 0.05, 0.03)))
        val q = PriceLinks.query(text, null, slab)
        println(q)
        assertEquals("pokemon chinese Captain Pikachu 701 DSG 10", q)
    }
}
