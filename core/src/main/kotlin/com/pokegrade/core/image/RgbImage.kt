package com.pokegrade.core.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Platform-independent image: packed ARGB pixels, row-major.
 * The Android app converts a Bitmap into this so all analysis code
 * can run (and be unit-tested) on a plain JVM.
 */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixel count ${pixels.size} != ${width}x$height" }
    }

    fun argb(x: Int, y: Int): Int = pixels[y * width + x]
    fun r(x: Int, y: Int) = (argb(x, y) shr 16) and 0xFF
    fun g(x: Int, y: Int) = (argb(x, y) shr 8) and 0xFF
    fun b(x: Int, y: Int) = argb(x, y) and 0xFF

    /** Luma 0..255 (Rec. 601). */
    fun luma(x: Int, y: Int): Double {
        val p = argb(x, y)
        return 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
    }

    fun crop(left: Int, top: Int, right: Int, bottom: Int): RgbImage {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(l + 1, width)
        val b = bottom.coerceIn(t + 1, height)
        val w = r - l
        val h = b - t
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(pixels, (t + y) * width + l, out, y * w, w)
        return RgbImage(w, h, out)
    }

    /** Nearest-neighbour-free box resize (area average); good enough for analysis. */
    fun resize(newW: Int, newH: Int): RgbImage {
        val out = IntArray(newW * newH)
        val sx = width.toDouble() / newW
        val sy = height.toDouble() / newH
        for (y in 0 until newH) {
            val y0 = (y * sy).toInt()
            val y1 = max(y0 + 1, min(height, ((y + 1) * sy).toInt()))
            for (x in 0 until newW) {
                val x0 = (x * sx).toInt()
                val x1 = max(x0 + 1, min(width, ((x + 1) * sx).toInt()))
                var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val p = pixels[yy * width + xx]
                    rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF; n++
                }
                out[y * newW + x] = (0xFF shl 24) or ((rs / n).toInt() shl 16) or ((gs / n).toInt() shl 8) or (bs / n).toInt()
            }
        }
        return RgbImage(newW, newH, out)
    }

    fun lumaArray(): DoubleArray = DoubleArray(width * height) { i ->
        val p = pixels[i]
        0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
    }

    companion object {
        fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        fun filled(w: Int, h: Int, argb: Int) = RgbImage(w, h, IntArray(w * h) { argb })
    }
}

/** HSV helpers: hue 0..360, saturation/value 0..1. */
object ColorMath {
    fun hsv(argb: Int): DoubleArray {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0.0 -> 0.0
            mx == r -> 60.0 * (((g - b) / d) % 6.0)
            mx == g -> 60.0 * (((b - r) / d) + 2.0)
            else -> 60.0 * (((r - g) / d) + 4.0)
        }.let { if (it < 0) it + 360.0 else it }
        val s = if (mx == 0.0) 0.0 else d / mx
        return doubleArrayOf(h, s, mx)
    }

    /** Euclidean RGB distance, 0..~441. */
    fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    fun hueDistance(h1: Double, h2: Double): Double {
        val d = abs(h1 - h2) % 360.0
        return if (d > 180) 360 - d else d
    }

    /** Median colour of a list of ARGB pixels (per channel). */
    fun medianColor(samples: List<Int>): Int {
        if (samples.isEmpty()) return 0
        fun med(sel: (Int) -> Int): Int = samples.map(sel).sorted()[samples.size / 2]
        return RgbImage.rgb(
            med { (it shr 16) and 0xFF },
            med { (it shr 8) and 0xFF },
            med { it and 0xFF },
        )
    }
}
