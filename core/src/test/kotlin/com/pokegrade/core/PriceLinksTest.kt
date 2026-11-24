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

    /** Text exactly as the app read the Hisuian Growlithe (from the user's screenshot). */
    @Test
    fun realGrowlitheOcr() {
        val all = "SEENo.0058 LNRD3 A:0.8m :22.7kg\ntaZRRD3 t 105\nOx2\nH Con5C 0205/07%\n2026 Pokémon/Nintendo / Creatures/GAME FREAK.\n" +
            "HP80\n40\n全国監No.0058 放明宝可ジ身高:0.8m 体重22.7kg\n将埼上的発技坊放干奔牌区。\n焼光\n鈴文只宝可夢也造成105書。\n。x2l\n础\n洗翠卡帶狗\n" +
            "全国国鲨o.0058 故听宝可 身高: 0.8m体重:22.7kg\n将场上的竞技场放于弃牌区。\n烧光\n给这只宝可梦也造成10伤害。\n日las5C」 0205/070\n猛撞\n以維想这見\n" +
            "소No.0058 A고희 a:0.Bm l: 22.7kg\n13토효었H퓨후즈.\n승모코미tE10154"
        val langs = CardTextParser.detectLanguages(all)
        println(langs)
        assertEquals("zh-cn", langs.first())
        val text = CardTextParser.parse(listOf(
            OcrLine("洗翠卡帶狗", 0.18, 0.035, 0.03),
            OcrLine("HP80", 0.74, 0.03, 0.03),
            OcrLine("日las5C」 0205/070", 0.05, 0.96, 0.012),
        ) + all.lines().map { OcrLine(it, 0.3, 0.5, 0.01) })
        assertEquals("0205", text.number)
        assertEquals("070", text.setTotal)
        assertEquals("Hisuian Growlithe", EnglishNames.translate("洗翠卡帶狗"))
        val q = PriceLinks.query(text, null, null)
        println(q)
        assertEquals("pokemon chinese Hisuian Growlithe 0205", q)
    }
}
