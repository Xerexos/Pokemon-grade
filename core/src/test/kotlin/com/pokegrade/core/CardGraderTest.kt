package com.pokegrade.core

import com.pokegrade.core.grading.CardGrader
import com.pokegrade.core.image.RgbImage
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Draws a synthetic Pokémon-style card: yellow border, busy artwork, on a dark table. */
object SyntheticCard {
    val YELLOW = RgbImage.rgb(245, 205, 40)
    val TABLE = RgbImage.rgb(40, 42, 48)
    val WHITE = RgbImage.rgb(250, 250, 245)

    fun draw(
        cardW: Int = 630,
        borderL: Int = 32, borderR: Int = 32, borderT: Int = 32, borderB: Int = 32,
        whiteCorners: Int = 0,
        whiteEdge: Boolean = false,
        scratches: Int = 0,
        seed: Int = 1,
    ): RgbImage {
        val cardH = (cardW * 88.0 / 63.0).toInt()
        val pad = 80
        val w = cardW + pad * 2
        val h = cardH + pad * 2
        val rnd = Random(seed)
        val px = IntArray(w * h) { TABLE }
        val radius = cardW * 0.048
        for (y in 0 until cardH) for (x in 0 until cardW) {
            // Rounded corners.
            val dx = minOf(x + 0.5, cardW - x - 0.5)
            val dy = minOf(y + 0.5, cardH - y - 0.5)
            if (dx < radius && dy < radius &&
                sqrt((radius - dx) * (radius - dx) + (radius - dy) * (radius - dy)) > radius) continue
            val inBorder = x < borderL || x >= cardW - borderR || y < borderT || y >= cardH - borderB
            val c = if (inBorder) YELLOW else {
                // Busy "artwork".
                val r = (128 + 100 * kotlin.math.sin(x / 17.0 + y / 29.0)).toInt().coerceIn(0, 255)
                val g = (90 + 80 * kotlin.math.cos(y / 13.0)).toInt().coerceIn(0, 255)
                val b = (150 + 90 * kotlin.math.sin((x + y) / 23.0)).toInt().coerceIn(0, 255)
                RgbImage.rgb(r, g, b)
            }
            px[(y + pad) * w + (x + pad)] = jitter(c, rnd)
        }
        // Corner whitening: white wedge in each corner.
        if (whiteCorners > 0) {
            for (y in 0 until whiteCorners) for (x in 0 until whiteCorners - y) {
                for ((cx, cy) in listOf(x to y, cardW - 1 - x to y, x to cardH - 1 - y, cardW - 1 - x to cardH - 1 - y)) {
                    val i = (cy + pad) * w + (cx + pad)
                    if (px[i] != TABLE) px[i] = WHITE
                }
            }
        }
        if (whiteEdge) {
            for (x in cardW / 4 until cardW * 3 / 4) for (d in 0 until 7) {
                if (rnd.nextDouble() < 0.6) px[(d + pad) * w + (x + pad)] = WHITE
            }
        }
        repeat(scratches) { s ->
            val y0 = pad + 16 + s * 2
            for (x in pad + cardW / 5 until pad + cardW * 4 / 5) px[(y0 + (x % 3) / 2) * w + x] = WHITE
        }
        return RgbImage(w, h, px)
    }

    private fun jitter(c: Int, rnd: Random): Int {
        fun ch(v: Int) = (v + rnd.nextInt(-4, 5)).coerceIn(0, 255)
        return RgbImage.rgb(ch((c shr 16) and 0xFF), ch((c shr 8) and 0xFF), ch(c and 0xFF))
    }
}

class CardGraderTest {
    private val grader = CardGrader()

    @Test
    fun findsCardOutline() {
        val img = SyntheticCard.draw()
        val r = grader.grade(img)
        assertTrue(abs(r.bounds.left - 80) <= 3, "left ${r.bounds.left}")
        assertTrue(abs(r.bounds.top - 80) <= 3, "top ${r.bounds.top}")
        assertTrue(abs(r.bounds.width - 630) <= 6, "width ${r.bounds.width}")
    }

    /** Card inside a toploader-shaped frame (76x101 mm) on the table. */
    private fun inToploader(): RgbImage {
        val card = SyntheticCard.draw()
        val w = card.width + 60
        val h = card.height + 70
        val px = IntArray(w * h) { SyntheticCard.TABLE }
        // Light plastic frame around the card, brighter than the table.
        val fw = ((card.height - 160) * 76.0 / 101.0).toInt() + 40
        val fh = card.height - 160 + 60
        val fl = (w - fw) / 2; val ft = (h - fh) / 2
        for (y in ft until ft + fh) for (x in fl until fl + fw) px[y * w + x] = RgbImage.rgb(90, 95, 105)
        for (y in 0 until card.height) for (x in 0 until card.width) {
            val p = card.argb(x, y)
            if (p != SyntheticCard.TABLE) px[(y + 35) * w + (x + 30)] = p
        }
        return RgbImage(w, h, px)
    }

    @Test
    fun ignoresToploaderFrame() {
        val r = grader.grade(inToploader())
        println("toploader: bounds=${r.bounds} reliable=${r.reliable} ${r.subGrades}")
        assertTrue(abs(r.bounds.left - 110) <= 4, "left ${r.bounds.left}")
        assertTrue(abs(r.bounds.width - 630) <= 8, "width ${r.bounds.width}")
        assertTrue(abs(r.bounds.height - 879) <= 8, "height ${r.bounds.height}")
    }

    @Test
    fun nonCardShapeIsUnreliable() {
        // A wide rectangle that can't be a card.
        val img = SyntheticCard.draw().let { c -> RgbImage(c.height, c.width, IntArray(c.pixels.size) { i ->
            val x = i % c.height; val y = i / c.height; c.argb(y, x) }) }
        val r = grader.grade(img)
        println("sideways: reliable=${r.reliable} issues=${r.reliabilityIssues}")
        assertTrue(!r.reliable)
    }

    @Test
    fun perfectCardGradesHigh() {
        val r = grader.grade(SyntheticCard.draw())
        println("perfect: ${r.overall} ${r.subGrades} centering ${r.centering.lrLabel()} ${r.centering.tbLabel()} notes=${r.notes}")
        assertEquals("50/50", r.centering.lrLabel())
        assertEquals("50/50", r.centering.tbLabel())
        assertTrue(r.overall >= 9.5, "overall ${r.overall}")
        assertTrue(r.reliable, "issues ${r.reliabilityIssues}")
    }

    @Test
    fun detectsOffCenter() {
        val r = grader.grade(SyntheticCard.draw(borderL = 45, borderR = 19))
        println("off-center: ${r.overall} ${r.subGrades} ${r.centering}")
        val left = r.centering.leftRatio
        assertTrue(abs(left - 45.0 / 64.0) < 0.04, "left ratio $left")
        assertTrue(r.subGrades.centering <= 7.5, "centering ${r.subGrades.centering}")
        assertTrue(r.overall < 9.0)
    }

    @Test
    fun detectsCornerWhitening() {
        val clean = grader.grade(SyntheticCard.draw())
        val worn = grader.grade(SyntheticCard.draw(whiteCorners = 34))
        println("worn corners: ${worn.overall} ${worn.subGrades} wear=${worn.cornerWear}")
        assertTrue(worn.subGrades.corners < clean.subGrades.corners - 1.0)
        assertTrue(worn.overall < clean.overall)
    }

    @Test
    fun detectsEdgeWhitening() {
        val worn = grader.grade(SyntheticCard.draw(whiteEdge = true))
        println("worn edge: ${worn.overall} ${worn.subGrades} edges=${worn.edgeWear}")
        assertTrue(worn.edgeWear[0] > worn.edgeWear[2] + 0.05, "top edge ${worn.edgeWear}")
        assertTrue(worn.subGrades.edges < 9.0)
    }

    @Test
    fun detectsScratches() {
        val r = grader.grade(SyntheticCard.draw(scratches = 4))
        println("scratched: ${r.overall} ${r.subGrades} surface=${r.surfaceDefectRatio}")
        assertTrue(r.subGrades.surface < 9.5, "surface ${r.subGrades.surface}")
    }
}

class GraderScalesTest {
    @Test
    fun convertsToEachCompanysScale() {
        val grader = com.pokegrade.core.grading.CardGrader()
        val perfect = grader.grade(SyntheticCard.draw())
        val bgs = com.pokegrade.core.grading.GraderScales.estimate(perfect, com.pokegrade.core.grading.Grader.BGS)
        val cgc = com.pokegrade.core.grading.GraderScales.estimate(perfect, com.pokegrade.core.grading.Grader.CGC)
        val psa = com.pokegrade.core.grading.GraderScales.estimate(perfect, com.pokegrade.core.grading.Grader.PSA)
        println("$psa\n$bgs\n$cgc")
        assertEquals("Gem Mint 10", psa.label)
        assertEquals("Black Label Pristine 10", bgs.label)
        assertEquals("Pristine 10", cgc.label)
        assertTrue(bgs.subgrades != null && psa.subgrades == null)

        val worn = grader.grade(SyntheticCard.draw(whiteCorners = 34))
        val dsg = com.pokegrade.core.grading.GraderScales.estimate(worn, com.pokegrade.core.grading.Grader.DSG)
        val ace = com.pokegrade.core.grading.GraderScales.estimate(worn, com.pokegrade.core.grading.Grader.ACE)
        println("$dsg\n$ace")
        assertTrue(dsg.grade < 10 && ace.grade % 1.0 == 0.0)
    }
}
