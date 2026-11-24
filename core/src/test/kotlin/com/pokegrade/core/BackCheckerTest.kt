package com.pokegrade.core

import com.pokegrade.core.auth.BackChecker
import com.pokegrade.core.auth.CheckStatus
import com.pokegrade.core.grading.CardGrader
import com.pokegrade.core.image.RgbImage
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Synthetic card back: blue swirl, Poké Ball in the centre, yellow logo top-left. */
object SyntheticBack {
    fun draw(
        blue: Triple<Int, Int, Int> = Triple(30, 75, 165),
        light: Triple<Int, Int, Int> = Triple(70, 130, 215),
        warmLight: Boolean = false,
        blur: Boolean = false,
        seed: Int = 3,
    ): RgbImage {
        val w = 630; val h = 880
        val rnd = Random(seed)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val t = (sin(x / 9.0 + y / 23.0) + sin(y / 7.0 - x / 31.0)) / 2
            val mix = if (blur) 0.5 + 0.15 * t else 0.5 + 0.5 * t
            var r = (blue.first + (light.first - blue.first) * mix).toInt()
            var g = (blue.second + (light.second - blue.second) * mix).toInt()
            var b = (blue.third + (light.third - blue.third) * mix).toInt()
            // Poké Ball
            val cx = w * 0.5; val cy = h * 0.52; val rad = w * 0.28
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            if (d < rad) {
                if (y < cy - 6) { r = 220; g = 30; b = 40 } else if (y > cy + 6) { r = 245; g = 245; b = 240 } else { r = 20; g = 20; b = 20 }
            }
            // Logo
            if (x in (w * 0.12).toInt()..(w * 0.62).toInt() && y in (h * 0.10).toInt()..(h * 0.22).toInt()) { r = 250; g = 205; b = 30 }
            if (warmLight) { r = (r * 1.0).toInt(); g = (g * 0.9).toInt(); b = (b * 0.75).toInt() }
            fun j(v: Int) = (v + rnd.nextInt(-3, 4)).coerceIn(0, 255)
            px[y * w + x] = RgbImage.rgb(j(r), j(g), j(b))
        }
        return RgbImage(w, h, px)
    }
}

class BackCheckerTest {
    private val genuine = BackChecker.features(SyntheticBack.draw())

    @Test
    fun genuineBackPasses() {
        println("genuine: $genuine")
        val checks = BackChecker.check(genuine, model = null)
        println(checks)
        assertTrue(checks.first { it.name == "Back design" }.status == CheckStatus.PASS)
        assertTrue(checks.first { it.name == "Back blue shade" }.status == CheckStatus.PASS)
    }

    @Test
    fun whiteBalanceCancelsWarmLight() {
        val warm = BackChecker.features(SyntheticBack.draw(warmLight = true, seed = 9))
        println("warm: $warm")
        val checks = BackChecker.check(warm, reference = genuine, model = null)
        println(checks)
        assertTrue(checks.first { it.name == "Matches your genuine back" }.status == CheckStatus.PASS)
    }

    @Test
    fun purpleLightFakeIsFlaggedAgainstReference() {
        val fake = BackChecker.features(SyntheticBack.draw(blue = Triple(80, 70, 170), light = Triple(150, 140, 230), blur = true))
        println("fake: $fake")
        val checks = BackChecker.check(fake, reference = genuine, model = null)
        println(checks)
        assertTrue(checks.first { it.name == "Matches your genuine back" }.status == CheckStatus.FAIL)
    }

    @Test
    fun frontIsNotABack() {
        val front = BackChecker.features(SyntheticCard.draw().crop(80, 80, 710, 959))
        val checks = BackChecker.check(front, model = null)
        assertTrue(checks.first { it.name == "Back design" }.status != CheckStatus.PASS)
    }

    @Test
    fun trainsModelThatSeparatesClasses() {
        val rnd = Random(1)
        fun jitter(v: Int) = (v + rnd.nextInt(-12, 13)).coerceIn(0, 255)
        val real = (0 until 25).map { BackChecker.features(SyntheticBack.draw(blue = Triple(jitter(30), jitter(75), jitter(165)), seed = it)) }
        val fake = (0 until 25).map {
            BackChecker.features(SyntheticBack.draw(blue = Triple(jitter(75), jitter(80), jitter(175)), light = Triple(150, 150, 230), blur = it % 2 == 0, seed = 100 + it))
        }
        val model = BackChecker.train(real.take(20), fake.take(20))
        val correct = real.drop(20).count { model.probabilityFake(it) < 0.5 } + fake.drop(20).count { model.probabilityFake(it) >= 0.5 }
        println("held-out correct $correct/10")
        assertTrue(correct >= 9)
    }

    @Test
    fun locatesBackOnTable() {
        val back = SyntheticBack.draw()
        val w = back.width + 160; val h = back.height + 160
        val px = IntArray(w * h) { SyntheticCard.TABLE }
        for (y in 0 until back.height) for (x in 0 until back.width) px[(y + 80) * w + x + 80] = back.argb(x, y)
        val located = CardGrader().locateCard(RgbImage(w, h, px))
        println("located ${located.bounds} ok=${located.outlineOk}")
        assertTrue(located.outlineOk)
    }
}
