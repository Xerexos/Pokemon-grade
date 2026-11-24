package com.pokegrade.core.grading

import com.pokegrade.core.image.ColorMath
import com.pokegrade.core.image.RgbImage
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device computer-vision grader.
 *
 * Works on a photo of a single card on a contrasting background, roughly
 * aligned with the camera guide frame. It measures the four things graders
 * look at — centering, corners, edges, surface — and maps them onto a
 * PSA-style 1–10 scale.
 *
 * Measurements are made on the card's printed border, which is a flat
 * colour on almost every Pokémon card (yellow, silver, or for some promos
 * black/blue). Anything that deviates from that colour near the outline is
 * treated as wear (whitening, chips, dings); anything that deviates inside
 * the border is treated as a surface defect (scratches, print lines, dents).
 */
class CardGrader(private val config: Config = Config()) {

    data class Config(
        /** Long side of the working image, in px. */
        val workingSize: Int = 1200,
        /** RGB distance from the border colour that counts as a defect. */
        val wearThreshold: Double = 62.0,
        val surfaceThreshold: Double = 48.0,
        /** Pokémon cards are 63 x 88 mm. */
        val cardAspect: Double = 63.0 / 88.0,
    )

    fun grade(photo: RgbImage): GradeResult {
        val img = normalize(photo)
        val notes = mutableListOf<String>()

        val bounds = findCardBounds(img, notes)
        val cw = bounds.width.toDouble()

        val sides = measureBorders(img, bounds)
        val centering = Centering(
            leftRatio = sides.left.width / max(1.0, sides.left.width + sides.right.width),
            topRatio = sides.top.width / max(1.0, sides.top.width + sides.bottom.width),
            borderLeftPx = sides.left.width.roundToInt(),
            borderRightPx = sides.right.width.roundToInt(),
            borderTopPx = sides.top.width.roundToInt(),
            borderBottomPx = sides.bottom.width.roundToInt(),
        )
        val minBorder = listOf(sides.left, sides.right, sides.top, sides.bottom).minOf { it.width }
        if (minBorder < cw * 0.012) {
            notes += "Couldn't find a clear border on one side — centering may be unreliable (full-art or borderless card?)."
        }

        val cornerWear = measureCorners(img, bounds, sides)
        val edgeWear = measureEdges(img, bounds, sides)
        val surface = measureSurface(img, bounds, sides)
        val quality = assessPhoto(img, bounds)

        val sub = SubGrades(
            centering = round1(centeringScore(centering.worstSkew())),
            corners = round1(wearScore(cornerWear.max() * 0.6 + cornerWear.average() * 0.4, CORNER_CURVE)),
            edges = round1(wearScore(edgeWear.max() * 0.5 + edgeWear.average() * 0.5, EDGE_CURVE)),
            surface = round1(wearScore(surface, SURFACE_CURVE)),
        )

        val weighted = sub.centering * 0.25 + sub.corners * 0.30 + sub.edges * 0.25 + sub.surface * 0.20
        val lowest = minOf(sub.centering, sub.corners, sub.edges, sub.surface)
        // A card can't grade much higher than its weakest attribute.
        val overall = (floor(min(weighted, lowest + 1.0) * 2) / 2).coerceIn(1.0, 10.0)

        if (sub.centering < 9) notes += "Centering ${centering.lrLabel()} L/R, ${centering.tbLabel()} T/B."
        cornerWear.forEachIndexed { i, w ->
            if (w > 0.06) notes += "${CORNER_NAMES[i]} corner shows wear/whitening."
        }
        edgeWear.forEachIndexed { i, w ->
            if (w > 0.05) notes += "${EDGE_NAMES[i]} edge shows whitening or chipping."
        }
        if (surface > 0.03) notes += "Possible scratches, print lines or marks on the surface."
        notes += quality.problems

        // Refuse to give a confident grade when the measurements can't be trusted.
        val issues = mutableListOf<String>()
        if (lastAspectError > 0.06) {
            issues += "Couldn't find a clean card outline. Take the card out of any sleeve, toploader or case and lay it flat on a dark, plain surface."
        }
        if (quality.cardFillRatio < 0.2) issues += "The card is too small in the photo."
        if (quality.sharpness < 25) issues += "The photo is too blurry to see wear."
        if (quality.glareRatio > 0.06) issues += "Too much glare on the card."
        if (sub.corners <= 3.0 && sub.edges <= 3.0) {
            issues += "Every corner and edge measured as damaged, which usually means a sleeve, glare or the background confused the measurement."
        }

        return GradeResult(
            reliable = issues.isEmpty(),
            reliabilityIssues = issues,
            overall = overall,
            subGrades = sub,
            centering = centering,
            bounds = bounds,
            cornerWear = cornerWear.map(::round3),
            edgeWear = edgeWear.map(::round3),
            surfaceDefectRatio = round3(surface),
            photoQuality = quality,
            notes = notes,
        )
    }

    /** A card found in a photo: the cropped card and whether its outline looked right. */
    data class LocatedCard(val card: RgbImage, val bounds: CardBounds, val outlineOk: Boolean)

    /** Find and crop the card (used for backs, where no grading is needed). */
    fun locateCard(photo: RgbImage): LocatedCard {
        val img = normalize(photo)
        val b = findCardBounds(img, mutableListOf())
        return LocatedCard(img.crop(b.left, b.top, b.right, b.bottom), b, lastAspectError <= 0.06)
    }

    // ---------------------------------------------------------------- setup

    private fun normalize(photo: RgbImage): RgbImage {
        val long = max(photo.width, photo.height)
        if (long <= config.workingSize) return photo
        val s = config.workingSize.toDouble() / long
        return photo.resize((photo.width * s).roundToInt(), (photo.height * s).roundToInt())
    }

    // ---------------------------------------------------------- card bounds

    /** Strongest colour change between the neighbours of (x,y) along one axis. */
    private fun grad(img: RgbImage, x: Int, y: Int, horizontal: Boolean): Int {
        val a: Int
        val b: Int
        if (horizontal) {
            a = img.argb(max(0, x - 1), y); b = img.argb(min(img.width - 1, x + 1), y)
        } else {
            a = img.argb(x, max(0, y - 1)); b = img.argb(x, min(img.height - 1, y + 1))
        }
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db))
    }

    internal fun findCardBounds(img: RgbImage, notes: MutableList<String>): CardBounds {
        val w = img.width
        val h = img.height
        val col = DoubleArray(w)
        for (x in 0 until w) {
            var s = 0.0
            var y = (h * 0.3).toInt()
            while (y < h * 0.7) { s += grad(img, x, y, true); y += 2 }
            col[x] = s
        }
        val row = DoubleArray(h)
        for (y in 0 until h) {
            var s = 0.0
            var x = (w * 0.3).toInt()
            while (x < w * 0.7) { s += grad(img, x, y, false); x += 2 }
            row[y] = s
        }
        val cs = smooth(col)
        val rs = smooth(row)

        // Candidate edges for each side, then pick the rectangle that is
        // strongest *and* shaped like a card (63:88). This skips the edges of
        // sleeves, toploaders and cases, and the inner border/art frame.
        val lc = peaks(cs, 0, (w * 0.45).toInt())
        val rc = peaks(cs, (w * 0.55).toInt(), w)
        val tc = peaks(rs, 0, (h * 0.45).toInt())
        val bc = peaks(rs, (h * 0.55).toInt(), h)

        var best: CardBounds? = null
        var bestScore = 0.0
        var bestAspectErr = 1.0
        for (l in lc) for (r in rc) for (t in tc) for (bo in bc) {
            val bw = r.pos + 1 - l.pos
            val bh = bo.pos + 1 - t.pos
            if (bw < w * 0.3 || bh < h * 0.3) continue
            val err = abs(bw.toDouble() / bh - config.cardAspect) / config.cardAspect
            val strength = (l.norm + r.norm + t.norm + bo.norm) / 4
            val area = bw.toDouble() * bh / (w.toDouble() * h)
            val score = strength * exp(-(err / 0.035) * (err / 0.035)) * sqrt(area)
            if (score > bestScore) {
                bestScore = score; bestAspectErr = err
                best = CardBounds(l.pos, t.pos, r.pos + 1, bo.pos + 1)
            }
        }
        val b = best ?: CardBounds(0, 0, w, h)
        val aspect = b.width.toDouble() / max(1, b.height)
        lastAspectError = if (best == null) 1.0 else bestAspectErr
        if (best == null) {
            notes += "Couldn't find the card's edges."
        } else if (bestAspectErr > 0.06) {
            notes += "Card outline looks skewed (aspect ${"%.2f".format(aspect)}). Hold the phone flat above the card."
        }
        return b
    }

    private data class Peak(val pos: Int, val norm: Double)

    /** Up to 6 strongest local maxima in [from, to), normalised to the window's max. */
    private fun peaks(p: DoubleArray, from: Int, to: Int): List<Peak> {
        if (to - from < 5) return emptyList()
        var mx = 0.0
        for (i in from until to) mx = max(mx, p[i])
        val mean = (from until to).sumOf { p[it] } / (to - from)
        if (mx <= 0 || mx < mean * 2.0) return emptyList()
        val found = (from + 1 until to - 1)
            .filter { p[it] >= p[it - 1] && p[it] >= p[it + 1] && p[it] >= mx * 0.25 && p[it] >= mean * 1.8 }
            .sortedByDescending { p[it] }
        val picked = mutableListOf<Int>()
        for (i in found) {
            if (picked.none { abs(it - i) < 4 }) picked += i
            if (picked.size == 6) break
        }
        return picked.map { Peak(it, p[it] / mx) }
    }

    /** Aspect error of the last detected outline (0 = perfect card shape). */
    private var lastAspectError = 0.0

    private fun smooth(a: DoubleArray): DoubleArray = DoubleArray(a.size) { i ->
        var s = 0.0; var n = 0
        for (j in i - 2..i + 2) if (j in a.indices) { s += a[j]; n++ }
        s / n
    }

    // --------------------------------------------------------------- borders

    internal data class Side(val width: Double, val color: Int, val profile: List<Double>)
    internal data class Sides(val left: Side, val right: Side, val top: Side, val bottom: Side) {
        val all get() = listOf(top, right, bottom, left)
    }

    /**
     * For each side, sample the border colour just inside the outline and
     * walk inward until the colour changes — that distance is the border width.
     */
    internal fun measureBorders(img: RgbImage, b: CardBounds): Sides {
        val cw = b.width.toDouble()
        val refFrom = max(2, (cw * 0.014).roundToInt())
        val refTo = max(refFrom + 2, (cw * 0.024).roundToInt())
        val maxDepth = (cw * 0.2).roundToInt()

        fun side(lines: Int, pixelAt: (line: Int, depth: Int) -> Int?): Side {
            val refSamples = ArrayList<Int>()
            for (l in 0 until lines) for (d in refFrom..refTo) pixelAt(l, d)?.let(refSamples::add)
            val ref = ColorMath.medianColor(refSamples)
            val widths = ArrayList<Double>()
            for (l in 0 until lines) {
                var run = 0
                var found = -1
                for (d in refFrom..maxDepth) {
                    val p = pixelAt(l, d) ?: break
                    if (ColorMath.distance(p, ref) > config.wearThreshold) {
                        run++
                        if (run >= 3) { found = d - 2; break }
                    } else run = 0
                }
                widths += if (found > 0) found.toDouble() else Double.NaN
            }
            val valid = widths.filter { !it.isNaN() }.sorted()
            val med = if (valid.isEmpty()) 0.0 else valid[valid.size / 2]
            return Side(med, ref, widths)
        }

        val n = 48
        fun inRange(x: Int, y: Int) = x in 0 until img.width && y in 0 until img.height
        fun along(start: Int, len: Int, l: Int) = start + (len * (0.2 + 0.6 * l / (n - 1))).roundToInt()

        val left = side(n) { l, d ->
            val x = b.left + d; val y = along(b.top, b.height, l)
            if (inRange(x, y)) img.argb(x, y) else null
        }
        val right = side(n) { l, d ->
            val x = b.right - 1 - d; val y = along(b.top, b.height, l)
            if (inRange(x, y)) img.argb(x, y) else null
        }
        val top = side(n) { l, d ->
            val y = b.top + d; val x = along(b.left, b.width, l)
            if (inRange(x, y)) img.argb(x, y) else null
        }
        val bottom = side(n) { l, d ->
            val y = b.bottom - 1 - d; val x = along(b.left, b.width, l)
            if (inRange(x, y)) img.argb(x, y) else null
        }
        return Sides(left, right, top, bottom)
    }

    // --------------------------------------------------------------- corners

    /** Returns wear 0..1 for TL, TR, BL, BR. */
    internal fun measureCorners(img: RgbImage, b: CardBounds, s: Sides): List<Double> {
        val cw = b.width.toDouble()
        val radius = cw * 0.048            // printed corner radius ~3 mm on a 63 mm card
        val margin = max(2.0, cw * 0.008)  // ignore the anti-aliased outline itself
        val patch = (cw * 0.09).roundToInt()

        data class Corner(val x0: Int, val y0: Int, val sx: Int, val sy: Int, val ref: Int, val depthX: Double, val depthY: Double)
        val corners = listOf(
            Corner(b.left, b.top, 1, 1, mix(s.left.color, s.top.color), s.left.width, s.top.width),
            Corner(b.right - 1, b.top, -1, 1, mix(s.right.color, s.top.color), s.right.width, s.top.width),
            Corner(b.left, b.bottom - 1, 1, -1, mix(s.left.color, s.bottom.color), s.left.width, s.bottom.width),
            Corner(b.right - 1, b.bottom - 1, -1, -1, mix(s.right.color, s.bottom.color), s.right.width, s.bottom.width),
        )
        return corners.map { c ->
            var worn = 0; var total = 0
            val bandX = max(margin + 2, c.depthX * 0.85)
            val bandY = max(margin + 2, c.depthY * 0.85)
            for (dy in 0 until patch) for (dx in 0 until patch) {
                val x = c.x0 + c.sx * dx
                val y = c.y0 + c.sy * dy
                if (x !in 0 until img.width || y !in 0 until img.height) continue
                // Distance inside the ideal rounded outline.
                val inside = insideRoundedCorner(dx + 0.5, dy + 0.5, radius)
                if (inside < 1.0) continue
                // Only look at the printed border, not the artwork.
                if (dx > bandX && dy > bandY) continue
                total++
                val p = img.argb(x, y)
                // Right at the outline the background bleeds in, so only
                // whitening counts there; further in, any big change does.
                if (if (inside < margin) isWhitening(p, c.ref) else isWear(p, c.ref)) worn++
            }
            if (total == 0) 0.0 else worn.toDouble() / total
        }
    }

    /** Signed depth of a point inside a rounded-rectangle corner at the origin. */
    private fun insideRoundedCorner(dx: Double, dy: Double, r: Double): Double {
        return if (dx < r && dy < r) {
            r - sqrt((r - dx) * (r - dx) + (r - dy) * (r - dy))
        } else min(dx, dy)
    }

    private fun mix(a: Int, b: Int): Int = RgbImage.rgb(
        (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2,
        (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2,
        ((a and 0xFF) + (b and 0xFF)) / 2,
    )

    /**
     * Wear on a border = noticeably different from the border colour *and*
     * either lighter (whitening, the most common flaw) or a big shift
     * (chip / exposed background / dirt).
     */
    private fun isWear(p: Int, ref: Int): Boolean {
        val d = ColorMath.distance(p, ref)
        if (d < config.wearThreshold) return false
        return isWhitening(p, ref) || d > config.wearThreshold * 1.8
    }

    /** Paler than the border: lost colour without getting darker (or got clearly lighter). */
    private fun isWhitening(p: Int, ref: Int): Boolean {
        if (ColorMath.distance(p, ref) < config.wearThreshold) return false
        val hp = ColorMath.hsv(p)
        val hr = ColorMath.hsv(ref)
        val washedOut = hr[1] > 0.3 && hp[1] < hr[1] * 0.6 && hp[2] >= hr[2] - 0.08
        val brighter = hp[2] > hr[2] + 0.08 && hp[1] < 0.25
        return washedOut || brighter
    }

    // ----------------------------------------------------------------- edges

    /** Wear 0..1 for top, right, bottom, left. */
    internal fun measureEdges(img: RgbImage, b: CardBounds, s: Sides): List<Double> {
        val cw = b.width.toDouble()
        val margin = max(2, (cw * 0.008).roundToInt())
        val band = max(3, (cw * 0.016).roundToInt())

        fun strip(side: Side, sample: (along: Double, depth: Int) -> Pair<Int, Int>): Double {
            var worn = 0; var total = 0
            var t = 0.1
            while (t <= 0.9) {
                for (d in 1 until min(margin + band, max(margin + 1, (side.width * 0.8).toInt()))) {
                    val (x, y) = sample(t, d)
                    if (x !in 0 until img.width || y !in 0 until img.height) continue
                    total++
                    val p = img.argb(x, y)
                    if (if (d < margin) isWhitening(p, side.color) else isWear(p, side.color)) worn++
                }
                t += 0.004
            }
            val whitening = if (total == 0) 0.0 else worn.toDouble() / total
            // Chips/dents make the border width jump around; a straight
            // trend is just perspective, so measure residuals around a line fit.
            val irregular = profileIrregularity(side.profile) / max(1.0, side.width)
            return min(1.0, whitening + max(0.0, irregular - 0.08) * 0.5)
        }

        val top = strip(s.top) { t, d -> (b.left + (b.width * t).toInt()) to (b.top + d) }
        val right = strip(s.right) { t, d -> (b.right - 1 - d) to (b.top + (b.height * t).toInt()) }
        val bottom = strip(s.bottom) { t, d -> (b.left + (b.width * t).toInt()) to (b.bottom - 1 - d) }
        val left = strip(s.left) { t, d -> (b.left + d) to (b.top + (b.height * t).toInt()) }
        return listOf(top, right, bottom, left)
    }

    private fun profileIrregularity(profile: List<Double>): Double {
        val pts = profile.mapIndexedNotNull { i, v -> if (v.isNaN()) null else i.toDouble() to v }
        if (pts.size < 8) return 0.0
        val mx = pts.sumOf { it.first } / pts.size
        val my = pts.sumOf { it.second } / pts.size
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        val slope = if (sxx == 0.0) 0.0 else pts.sumOf { (it.first - mx) * (it.second - my) } / sxx
        val residuals = pts.map { abs(it.second - (my + slope * (it.first - mx))) }.sorted()
        return residuals[residuals.size / 2] // median absolute residual
    }

    // --------------------------------------------------------------- surface

    /** Fraction of border-area pixels with scratches/marks. */
    internal fun measureSurface(img: RgbImage, b: CardBounds, s: Sides): Double {
        val cw = b.width.toDouble()
        val inset = max(3, (cw * 0.022).roundToInt())
        var bad = 0; var total = 0

        fun scan(side: Side, sample: (along: Double, depth: Int) -> Pair<Int, Int>) {
            val maxD = (side.width * 0.8).toInt()
            if (maxD <= inset) return
            var t = 0.12
            while (t <= 0.88) {
                for (d in inset until maxD) {
                    val (x, y) = sample(t, d)
                    if (x !in 1 until img.width - 1 || y !in 1 until img.height - 1) continue
                    total++
                    val p = img.argb(x, y)
                    val lap = abs(4 * img.luma(x, y) - img.luma(x - 1, y) - img.luma(x + 1, y) -
                        img.luma(x, y - 1) - img.luma(x, y + 1))
                    if (ColorMath.distance(p, side.color) > config.surfaceThreshold || lap > 40) bad++
                }
                t += 0.004
            }
        }
        scan(s.top) { t, d -> (b.left + (b.width * t).toInt()) to (b.top + d) }
        scan(s.right) { t, d -> (b.right - 1 - d) to (b.top + (b.height * t).toInt()) }
        scan(s.bottom) { t, d -> (b.left + (b.width * t).toInt()) to (b.bottom - 1 - d) }
        scan(s.left) { t, d -> (b.left + d) to (b.top + (b.height * t).toInt()) }
        return if (total == 0) 0.0 else bad.toDouble() / total
    }

    // ---------------------------------------------------------- photo check

    internal fun assessPhoto(img: RgbImage, b: CardBounds): PhotoQuality {
        val card = img.crop(b.left, b.top, b.right, b.bottom)
        val small = if (card.width > 500) card.resize(500, max(1, card.height * 500 / card.width)) else card
        val l = small.lumaArray()
        val w = small.width
        val h = small.height
        var sum = 0.0; var sumSq = 0.0; var n = 0
        var glare = 0
        var bright = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = 4 * l[i] - l[i - 1] - l[i + 1] - l[i - w] - l[i + w]
            sum += lap; sumSq += lap * lap; n++
            bright += l[i]
            val p = small.pixels[i]
            if (((p shr 16) and 0xFF) > 245 && ((p shr 8) and 0xFF) > 245 && (p and 0xFF) > 245) glare++
        }
        val mean = sum / max(1, n)
        val sharp = sumSq / max(1, n) - mean * mean
        val brightness = bright / max(1, n)
        val glareRatio = glare.toDouble() / max(1, n)
        val fill = (b.width.toDouble() * b.height) / (img.width.toDouble() * img.height)

        val problems = mutableListOf<String>()
        if (sharp < 40) problems += "Photo looks blurry — hold steady and tap to focus."
        if (brightness < 60) problems += "Photo is too dark — add more light."
        if (brightness > 225) problems += "Photo is over-exposed."
        if (glareRatio > 0.03) problems += "Glare detected — tilt the light or card slightly to remove reflections."
        if (fill < 0.3) problems += "Card is small in the frame — move the camera closer."
        return PhotoQuality(
            sharpness = round1(sharp),
            brightness = round1(brightness),
            glareRatio = round3(glareRatio),
            cardFillRatio = round3(fill),
            acceptable = problems.isEmpty(),
            problems = problems,
        )
    }

    // ---------------------------------------------------------------- scales

    companion object {
        val CORNER_NAMES = listOf("Top-left", "Top-right", "Bottom-left", "Bottom-right")
        val EDGE_NAMES = listOf("Top", "Right", "Bottom", "Left")

        /** PSA front-centering tolerances: (worst side share → grade). */
        private val CENTERING_CURVE = listOf(
            0.50 to 10.0, 0.55 to 10.0, 0.60 to 9.0, 0.65 to 8.0, 0.70 to 7.0,
            0.80 to 6.0, 0.85 to 5.0, 0.90 to 3.0, 1.0 to 1.0,
        )
        private val CORNER_CURVE = listOf(
            0.0 to 10.0, 0.015 to 9.5, 0.04 to 8.5, 0.08 to 7.0, 0.15 to 5.0, 0.3 to 3.0, 0.5 to 1.0,
        )
        private val EDGE_CURVE = listOf(
            0.0 to 10.0, 0.012 to 9.5, 0.035 to 8.5, 0.07 to 7.0, 0.14 to 5.0, 0.28 to 3.0, 0.5 to 1.0,
        )
        private val SURFACE_CURVE = listOf(
            0.0 to 10.0, 0.01 to 9.5, 0.03 to 8.5, 0.06 to 7.0, 0.12 to 5.0, 0.25 to 3.0, 0.45 to 1.0,
        )

        fun centeringScore(worstSkew: Double) = interpolate(CENTERING_CURVE, worstSkew)
        private fun wearScore(v: Double, curve: List<Pair<Double, Double>>) = interpolate(curve, v)

        private fun interpolate(curve: List<Pair<Double, Double>>, v: Double): Double {
            if (v <= curve.first().first) return curve.first().second
            for (i in 1 until curve.size) {
                val (x1, y1) = curve[i]
                if (v <= x1) {
                    val (x0, y0) = curve[i - 1]
                    return y0 + (y1 - y0) * (v - x0) / (x1 - x0)
                }
            }
            return curve.last().second
        }

        private fun round1(v: Double) = Math.round(v * 10) / 10.0
        private fun round3(v: Double) = Math.round(v * 1000) / 1000.0
    }
}
