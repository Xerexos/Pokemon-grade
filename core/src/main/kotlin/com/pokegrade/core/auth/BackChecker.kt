package com.pokegrade.core.auth

import com.pokegrade.core.image.ColorMath
import com.pokegrade.core.image.RgbImage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measurements of a card back (English/international design: blue swirl
 * border, red-and-white Poké Ball, yellow "Pokémon" logo).
 *
 * Colours are white-balanced against the white half of the Poké Ball, so
 * the same card photographed under warm or cool light gives similar numbers.
 */
@Serializable
data class BackFeatures(
    /** Border colour after white balance, 0..1 per channel. */
    val borderR: Double,
    val borderG: Double,
    val borderB: Double,
    val borderHue: Double,
    val borderSat: Double,
    val borderVal: Double,
    /** Share of the centre that is Poké Ball red. */
    val redFraction: Double,
    /** Share of the upper-left area that is logo yellow. */
    val yellowFraction: Double,
    /** Share of the whole back that is blue. */
    val blueFraction: Double,
    /** Fine print detail in the swirl pattern (blurry reprints score low). */
    val detail: Double,
    /** Contrast between the light and dark blues of the swirl. */
    val swirlContrast: Double,
) {
    fun vector(): DoubleArray {
        val h = Math.toRadians(borderHue)
        return doubleArrayOf(
            borderR, borderG, borderB, sin(h), cos(h), borderSat, borderVal,
            redFraction, yellowFraction, blueFraction, detail, swirlContrast,
        )
    }

    companion object {
        val NAMES = listOf(
            "borderR", "borderG", "borderB", "hueSin", "hueCos", "borderSat", "borderVal",
            "redFraction", "yellowFraction", "blueFraction", "detail", "swirlContrast",
        )
    }
}

/** Logistic-regression model trained by `tools` on labelled real/fake backs. */
@Serializable
data class BackModel(
    val featureNames: List<String>,
    val mean: List<Double>,
    val std: List<Double>,
    val weights: List<Double>,
    val bias: Double,
    val trainedOn: String = "",
    val testAccuracy: Double? = null,
) {
    /** Probability that the back is fake. */
    fun probabilityFake(f: BackFeatures): Double {
        val v = f.vector()
        var z = bias
        for (i in v.indices) z += weights[i] * (v[i] - mean[i]) / max(1e-6, std[i])
        return 1 / (1 + exp(-z))
    }
}

object BackChecker {

    private const val W = 252
    private const val H = 352

    /** The bundled model, if one has been trained (core/src/main/resources/back_model.json). */
    val bundledModel: BackModel? by lazy {
        BackChecker::class.java.getResourceAsStream("/back_model.json")?.use { s ->
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(BackModel.serializer(), s.reader().readText()) }.getOrNull()
        }
    }

    /** @param back photo of the card back, already cropped to the card. */
    fun features(back: RgbImage): BackFeatures {
        val img = back.resize(W, H)

        // White reference: brightest low-saturation pixels in the centre (the Poké Ball's white half).
        val whites = ArrayList<Int>()
        for (y in (H * 0.30).toInt() until (H * 0.75).toInt()) for (x in (W * 0.25).toInt() until (W * 0.75).toInt()) {
            val p = img.argb(x, y)
            if (ColorMath.hsv(p)[1] < 0.2) whites += p
        }
        val white = if (whites.size > 50) {
            val sorted = whites.sortedByDescending { lum(it) }
            ColorMath.medianColor(sorted.take(max(10, sorted.size / 6)))
        } else RgbImage.rgb(255, 255, 255)
        val wr = max(40, (white shr 16) and 0xFF) / 255.0
        val wg = max(40, (white shr 8) and 0xFF) / 255.0
        val wb = max(40, white and 0xFF) / 255.0
        fun balanced(p: Int): Int = RgbImage.rgb(
            ((((p shr 16) and 0xFF) / wr).toInt()).coerceIn(0, 255),
            ((((p shr 8) and 0xFF) / wg).toInt()).coerceIn(0, 255),
            (((p and 0xFF) / wb).toInt()).coerceIn(0, 255),
        )

        // Border band: 2–5 % in from every edge.
        val d0 = (W * 0.02).toInt(); val d1 = (W * 0.05).toInt()
        val border = ArrayList<Int>()
        for (y in 0 until H) for (x in 0 until W) {
            val d = minOf(x, y, W - 1 - x, H - 1 - y)
            if (d in d0..d1) border += balanced(img.argb(x, y))
        }
        val b = ColorMath.medianColor(border)
        val bh = ColorMath.hsv(b)

        var red = 0; var centre = 0
        for (y in (H * 0.25).toInt() until (H * 0.75).toInt()) for (x in (W * 0.2).toInt() until (W * 0.8).toInt()) {
            centre++
            val h = ColorMath.hsv(balanced(img.argb(x, y)))
            if ((h[0] >= 340 || h[0] <= 20) && h[1] > 0.45 && h[2] > 0.3) red++
        }
        var yellow = 0; var logoArea = 0
        for (y in (H * 0.04).toInt() until (H * 0.38).toInt()) for (x in (W * 0.04).toInt() until (W * 0.75).toInt()) {
            logoArea++
            val h = ColorMath.hsv(balanced(img.argb(x, y)))
            if (h[0] in 38.0..70.0 && h[1] > 0.45 && h[2] > 0.5) yellow++
        }
        var blue = 0
        val swirlVals = ArrayList<Double>()
        for (p in img.pixels) {
            val h = ColorMath.hsv(balanced(p))
            if (h[0] in 185.0..255.0 && h[1] > 0.3) { blue++; swirlVals += h[2] }
        }
        val swirlContrast = if (swirlVals.size < 20) 0.0 else {
            val s = swirlVals.sorted()
            s[(s.size * 0.9).toInt()] - s[(s.size * 0.1).toInt()]
        }

        // Print detail: mean |Laplacian| over blue swirl pixels, relative to their contrast.
        val l = img.lumaArray()
        var lap = 0.0; var n = 0
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            val i = y * W + x
            val h = ColorMath.hsv(img.pixels[i])
            if (h[0] !in 185.0..255.0 || h[1] < 0.3) continue
            lap += abs(4 * l[i] - l[i - 1] - l[i + 1] - l[i - W] - l[i + W]); n++
        }
        val detail = if (n == 0) 0.0 else (lap / n) / max(1.0, swirlContrast * 255)

        return BackFeatures(
            borderR = r3(((b shr 16) and 0xFF) / 255.0),
            borderG = r3(((b shr 8) and 0xFF) / 255.0),
            borderB = r3((b and 0xFF) / 255.0),
            borderHue = r3(bh[0]), borderSat = r3(bh[1]), borderVal = r3(bh[2]),
            redFraction = r3(red.toDouble() / max(1, centre)),
            yellowFraction = r3(yellow.toDouble() / max(1, logoArea)),
            blueFraction = r3(blue.toDouble() / img.pixels.size),
            detail = r3(detail),
            swirlContrast = r3(swirlContrast),
        )
    }

    /**
     * @param reference features of a back the user knows is genuine, photographed
     *   the same way. The strongest check when available.
     */
    fun check(f: BackFeatures, reference: BackFeatures? = null, model: BackModel? = bundledModel): List<AuthCheck> {
        val out = mutableListOf<AuthCheck>()

        // 1. Is this the standard back at all?
        out += when {
            f.blueFraction > 0.3 && f.redFraction > 0.01 ->
                AuthCheck("Back design", CheckStatus.PASS, "Blue swirl border and Poké Ball found.", 1.5)
            f.blueFraction < 0.15 ->
                AuthCheck("Back design", CheckStatus.FAIL, "This doesn't look like the standard Pokémon card back (only ${pct(f.blueFraction)} blue). Japanese cards have a different back.", 1.5)
            else ->
                AuthCheck("Back design", CheckStatus.WARN, "Back design only partly matches (blue ${pct(f.blueFraction)}, red ${pct(f.redFraction)}). Retake without glare.", 1.5)
        }

        // 2. Border shade. Genuine backs are a fairly deep, saturated blue;
        //    many fakes are lighter, greyer or purple-tinted.
        val hueOk = f.borderHue in 200.0..238.0
        val hueClose = f.borderHue in 190.0..250.0
        out += when {
            hueOk && f.borderSat > 0.45 && f.borderVal < 0.85 ->
                AuthCheck("Back blue shade", CheckStatus.PASS, "Border blue looks right (hue ${f.borderHue.toInt()}°).", 1.0)
            !hueClose ->
                AuthCheck("Back blue shade", CheckStatus.FAIL, "Border colour is off (hue ${f.borderHue.toInt()}°): ${hueWord(f.borderHue)}.", 1.0)
            else ->
                AuthCheck("Back blue shade", CheckStatus.WARN, "Border blue is ${if (f.borderVal >= 0.85 || f.borderSat <= 0.45) "lighter / greyer" else "a slightly different hue"} than usual. Compare with a card you know is real.", 1.0)
        }

        // 3. Against the user's own known-genuine back.
        if (reference != null) {
            val dh = ColorMath.hueDistance(f.borderHue, reference.borderHue)
            val dv = abs(f.borderVal - reference.borderVal)
            val ds = abs(f.borderSat - reference.borderSat)
            val dDetail = if (reference.detail > 0) f.detail / reference.detail else 1.0
            val problems = buildList {
                if (dh > 7) add("blue hue differs by ${dh.toInt()}°")
                if (dv > 0.08) add(if (f.borderVal > reference.borderVal) "border is lighter" else "border is darker")
                if (ds > 0.1) add(if (f.borderSat < reference.borderSat) "colours are duller" else "colours are stronger")
                if (dDetail < 0.7) add("print looks blurrier")
                if (abs(f.redFraction - reference.redFraction) > 0.04) add("Poké Ball red area differs")
            }
            out += when {
                problems.isEmpty() -> AuthCheck("Matches your genuine back", CheckStatus.PASS, "Colour and print detail match your reference card.", 2.5)
                problems.size == 1 && dh <= 12 && dv <= 0.14 -> AuthCheck("Matches your genuine back", CheckStatus.WARN, "Small difference from your reference: ${problems.joinToString()}.", 2.5)
                else -> AuthCheck("Matches your genuine back", CheckStatus.FAIL, "Differs from your reference card: ${problems.joinToString()}.", 2.5)
            }
        } else {
            out += AuthCheck("Matches your genuine back", CheckStatus.SKIPPED, "Scan the back of a card you know is real and save it as your reference for a much stronger check.", 2.5)
        }

        // 4. Trained model, if one is bundled.
        if (model != null) {
            val p = model.probabilityFake(f)
            out += when {
                p < 0.3 -> AuthCheck("Back print model", CheckStatus.PASS, "Model trained on real/fake backs: ${pct(1 - p)} likely genuine.", 3.0)
                p < 0.6 -> AuthCheck("Back print model", CheckStatus.WARN, "Model trained on real/fake backs is unsure (${pct(p)} fake).", 3.0)
                else -> AuthCheck("Back print model", CheckStatus.FAIL, "Model trained on real/fake backs: ${pct(p)} likely fake.", 3.0)
            }
        }
        return out
    }

    private fun hueWord(h: Double) = when {
        h < 190 -> "too green/teal"
        h > 250 -> "too purple"
        else -> "unusual"
    }

    private fun lum(p: Int) = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
    private fun pct(v: Double) = "${(v * 100).toInt()}%"
    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0

    /** Train a logistic regression on labelled features (used by the tools module). */
    fun train(real: List<BackFeatures>, fake: List<BackFeatures>, l2: Double = 0.01, epochs: Int = 3000, lr: Double = 0.1, label: String = ""): BackModel {
        val xs = (real + fake).map { it.vector() }
        val ys = real.map { 0.0 } + fake.map { 1.0 }
        val d = xs.first().size
        val mean = DoubleArray(d) { j -> xs.sumOf { it[j] } / xs.size }
        val std = DoubleArray(d) { j -> sqrt(xs.sumOf { (it[j] - mean[j]) * (it[j] - mean[j]) } / xs.size).coerceAtLeast(1e-6) }
        val z = xs.map { x -> DoubleArray(d) { j -> (x[j] - mean[j]) / std[j] } }
        val w = DoubleArray(d)
        var bias = 0.0
        // Balance classes so a lopsided dataset doesn't bias the model.
        val posW = xs.size / (2.0 * max(1, fake.size))
        val negW = xs.size / (2.0 * max(1, real.size))
        repeat(epochs) {
            val gw = DoubleArray(d); var gb = 0.0
            for (i in z.indices) {
                var s = bias
                for (j in 0 until d) s += w[j] * z[i][j]
                val p = 1 / (1 + exp(-s))
                val cw = if (ys[i] == 1.0) posW else negW
                val err = (p - ys[i]) * cw
                for (j in 0 until d) gw[j] += err * z[i][j]
                gb += err
            }
            for (j in 0 until d) w[j] -= lr * (gw[j] / z.size + l2 * w[j])
            bias -= lr * gb / z.size
        }
        return BackModel(BackFeatures.NAMES, mean.toList(), std.toList(), w.toList(), bias, label)
    }
}
