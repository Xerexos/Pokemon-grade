package com.pokegrade.app.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pokegrade.core.ai.AiInspection
import com.pokegrade.core.ai.ClaudeCardInspector
import com.pokegrade.core.auth.AuthCheck
import com.pokegrade.core.auth.AuthenticityChecker
import com.pokegrade.core.auth.AuthenticityReport
import com.pokegrade.core.auth.BackChecker
import com.pokegrade.core.auth.BackFeatures
import com.pokegrade.core.auth.CheckStatus
import com.pokegrade.core.catalog.CardBrief
import com.pokegrade.core.catalog.CardCatalog
import com.pokegrade.core.catalog.CardRanker
import com.pokegrade.core.catalog.CardTextParser
import com.pokegrade.core.catalog.OcrLine
import com.pokegrade.core.catalog.ParsedCardText
import com.pokegrade.core.catalog.TcgCard
import com.pokegrade.core.catalog.TcgDexClient
import com.pokegrade.core.grading.CardGrader
import com.pokegrade.core.grading.GradeResult
import com.pokegrade.core.grading.SlabLabel
import com.pokegrade.core.grading.SlabLabelParser
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.pokegrade.core.image.RgbImage
import com.pokegrade.core.pricing.PriceEstimate
import com.pokegrade.core.pricing.PriceEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

data class BackScan(
    val bitmap: Bitmap,
    val features: BackFeatures,
    val checks: List<AuthCheck>,
    val outlineOk: Boolean,
)

data class Analysis(
    val cardBitmap: Bitmap,
    val grade: GradeResult,
    val text: ParsedCardText,
    val candidates: List<TcgCard>,
    val card: TcgCard?,
    val authenticity: AuthenticityReport,
    val price: PriceEstimate?,
    val lookupError: String? = null,
    val back: BackScan? = null,
    /** Set when the card is in a graded slab and we could read the label. */
    val slab: SlabLabel? = null,
)

/**
 * The whole pipeline: computer-vision grading → OCR → database lookup →
 * authenticity checks → price. Everything runs on the phone except the card
 * lookup (TCGdex) and the optional AI opinion.
 */
class CardAnalyzer(private val catalog: CardCatalog = TcgDexClient()) {

    private val grader = CardGrader()
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val korean = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val referenceCache = HashMap<String, RgbImage?>()

    suspend fun analyze(photo: Bitmap, onProgress: (String) -> Unit): Analysis {
        // Downscale to the grader's working size so its bounds map 1:1 onto this bitmap.
        val working = withContext(Dispatchers.Default) { downscale(photo, GRADER_SIZE) }

        // Is it in a graded slab? Read the label (label text is small, so use a
        // high-resolution copy) and only measure the card below it.
        onProgress("Looking for a slab label…")
        val hi = downscale(photo, 2400)
        var slab = recognize(latin, InputImage.fromBitmap(hi, 0), hi)?.let(SlabLabelParser::parse)

        onProgress("Measuring centering, corners, edges and surface…")
        var offsetY = slabOffset(slab, working.height)
        var (grade, cardBitmap) = measure(photo, working, offsetY)

        onProgress("Reading card text…")
        var (text, lines) = readText(cardBitmap)

        // Second chance: if the card crop still contains a label (e.g. the label
        // text was too small in the whole photo), cut it off and measure again.
        if (slab == null) {
            SlabLabelParser.parse(lines)?.let { inCrop ->
                slab = inCrop
                offsetY = (grade.bounds.top + inCrop.labelBottom * grade.bounds.height + 0.01 * working.height)
                    .toInt().coerceIn(0, working.height * 2 / 3)
                measure(photo, working, offsetY).let { grade = it.first; cardBitmap = it.second }
                readText(cardBitmap).let { text = it.first; lines = it.second }
            }
        }

        onProgress("Looking up ${text.name ?: "card"}…")
        var lookupError: String? = null
        val ranked = try {
            withContext(Dispatchers.IO) { catalog.findCandidates(text) }
        } catch (e: Exception) {
            lookupError = "Card lookup failed (${e.message ?: "network error"}). Check your connection."
            emptyList()
        }

        val candidates = if (ranked.size > 1) {
            onProgress("Comparing with official cards…")
            pickByPhoto(cardBitmap, ranked, text)
        } else ranked
        return finish(cardBitmap, grade, text, candidates, candidates.firstOrNull(), lookupError, null, onProgress).copy(slab = slab)
    }

    private fun slabOffset(slab: SlabLabel?, height: Int): Int =
        if (slab == null) 0 else ((slab.labelBottom + 0.01) * height).toInt().coerceIn(0, height * 2 / 3)

    /** Grade the part of the photo below [offsetY] and crop the card at full resolution. */
    private suspend fun measure(photo: Bitmap, working: Bitmap, offsetY: Int): Pair<GradeResult, Bitmap> {
        val area = if (offsetY > 0) crop(working, 0, offsetY, working.width, working.height - offsetY) else working
        val grade = withContext(Dispatchers.Default) { grader.grade(area.toRgbImage()) }
        // Bounds are in the working image; crop the card from the full-resolution photo
        // (capped at 2000 px) so text reading, the AI check and uploads get the detail.
        val k = photo.width.toDouble() / working.width
        val card = downscale(
            crop(
                photo,
                (grade.bounds.left * k).toInt(), ((grade.bounds.top + offsetY) * k).toInt(),
                (grade.bounds.width * k).toInt(), (grade.bounds.height * k).toInt(),
            ),
            2000,
        )
        return grade to card
    }

    /** Re-run authenticity and price for a different database match chosen by the user. */
    suspend fun withCard(a: Analysis, card: TcgCard, onProgress: (String) -> Unit = {}): Analysis {
        val candidates = if (a.candidates.any { it.id == card.id }) a.candidates else listOf(card) + a.candidates
        return finish(a.cardBitmap, a.grade, a.text, candidates, card, null, a.back, onProgress).copy(slab = a.slab)
    }

    suspend fun search(name: String, language: String): List<CardBrief> =
        withContext(Dispatchers.IO) { catalog.searchByName(name, language) }

    suspend fun getCard(id: String, language: String): TcgCard = withContext(Dispatchers.IO) { catalog.getCard(id, language) }

    /** Scan the back and fold its checks into the authenticity verdict. */
    suspend fun withBack(a: Analysis, photo: Bitmap, reference: BackFeatures?): Analysis {
        val back = withContext(Dispatchers.Default) {
            val located = grader.locateCard(downscale(photo, GRADER_SIZE).toRgbImage())
            val features = BackChecker.features(located.card)
            val checks = if (a.card?.language in ASIAN_BACKS) {
                // Japanese/Chinese/Korean cards use a different back design.
                mutableListOf(AuthCheck("Back design", CheckStatus.SKIPPED, "The back check only knows the international (English/European) back.", 1.5))
            } else BackChecker.check(features, reference).toMutableList()
            if (!located.outlineOk) {
                checks.add(0, AuthCheck("Back photo", CheckStatus.WARN, "Couldn't find the card's outline clearly; results may be off. Use a dark background.", 0.5))
            }
            BackScan(located.card.toBitmap(), features, checks, located.outlineOk)
        }
        return finish(a.cardBitmap, a.grade, a.text, a.candidates, a.card, a.lookupError, back) {}.copy(slab = a.slab)
    }

    private suspend fun finish(
        cardBitmap: Bitmap,
        grade: GradeResult,
        text: ParsedCardText,
        candidates: List<TcgCard>,
        card: TcgCard?,
        lookupError: String?,
        back: BackScan?,
        onProgress: (String) -> Unit,
    ): Analysis {
        onProgress("Checking authenticity…")
        val reference = card?.let { loadReference(it) }
        val auth = withContext(Dispatchers.Default) {
            AuthenticityChecker.check(
                text, card, cardBitmap.toRgbImage(), reference,
                lookupFailed = lookupError != null,
                extraChecks = back?.checks.orEmpty(),
            )
        }
        val price = card?.let { PriceEstimator.estimate(it, if (grade.reliable) grade.overall else null) }
        return Analysis(cardBitmap, grade, text, candidates, card, auth, price, lookupError, back)
    }

    /**
     * Several printings can share a name, HP and attacks (e.g. regular vs
     * full-art). Among the equally good text matches, pick the one whose
     * official image looks most like the photo.
     */
    private suspend fun pickByPhoto(photo: Bitmap, ranked: List<TcgCard>, text: ParsedCardText): List<TcgCard> {
        val top = ranked.take(4)
        val scores = top.associate { c ->
            val ref = loadReference(c)
            c.id to (ref?.let { AuthenticityChecker.layoutSimilarity(photo.toRgbImage(), it) } ?: -1.0)
        }
        val textRank = CardRanker.rank(top, text)
        // Keep text ranking as the main signal; the photo breaks ties and near-ties.
        val ordered = top.sortedByDescending { c -> (top.size - textRank.indexOf(c)) * 0.15 + (scores[c.id] ?: -1.0) }
        return ordered + ranked.drop(4)
    }

    suspend fun aiInspect(apiKey: String, a: Analysis): AiInspection = withContext(Dispatchers.IO) {
        ClaudeCardInspector(apiKey).inspect(toJpegBase64(a.cardBitmap), a.card, a.grade, a.back?.bitmap?.let(::toJpegBase64))
    }

    private fun toJpegBase64(bmp: Bitmap): String {
        val jpeg = downscale(bmp, 1568)
        val bytes = ByteArrayOutputStream().also { jpeg.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Reads the card with the Latin model and, if the text looks Asian (or the
     * Latin model finds almost nothing), with the Japanese, Chinese or Korean
     * model. Those models download through Play services on first use; if one
     * isn't ready yet we just fall back to what we have.
     */
    private suspend fun readText(card: Bitmap): Pair<ParsedCardText, List<OcrLine>> = coroutineScope {
        val image = InputImage.fromBitmap(card, 0)
        // Run every script's reader: English text on a card (or label) mustn't
        // hide Asian text. Each also reads Latin, so the richest result wins.
        val latinJob = async { recognize(latin, image, card) }
        val jaJob = async { recognize(japanese, image, card) }
        val zhJob = async { recognize(chinese, image, card) }
        val koJob = async { recognize(korean, image, card) }
        val latinLines = latinJob.await() ?: emptyList()
        val ja = jaJob.await()
        val zh = zhJob.await()
        val ko = koJob.await()

        val readers = linkedMapOf("latin" to latinLines)
        ja?.let { readers["ja"] = it }
        zh?.let { readers["zh"] = it }
        ko?.let { readers["ko"] = it }
        var text = CardTextParser.combine(readers)

        // Names on foil cards are small, low-contrast text. If an Asian card's name
        // wasn't read, read the name strip again, contrast-boosted and enlarged.
        val asianScript = cjkCount(readers.values.flatten()) + hangulCount(readers.values.flatten()) >= 2
        if (asianScript && (text.name == null || text.name!!.none { Character.isIdeographic(it.code) || it.code in 0x3040..0x30FF || it.code in 0xAC00..0xD7AF })) {
            val strip = headerStrip(card)
            val sImg = InputImage.fromBitmap(strip, 0)
            val extra = listOfNotNull(
                recognize(chinese, sImg, strip)?.let { "zh" to it },
                recognize(japanese, sImg, strip)?.let { "ja" to it },
                recognize(korean, sImg, strip)?.let { "ko" to it },
            )
            if (extra.isNotEmpty()) {
                // Map strip coordinates back onto the card (the strip is its top 18%).
                val merged = LinkedHashMap(readers)
                for ((k, lines) in extra) {
                    merged[k] = lines.map { it.copy(top = it.top * HEADER, height = it.height * HEADER) } + readers[k].orEmpty()
                }
                text = CardTextParser.combine(merged)
            }
        }
        // Labels are English even on Asian cards, so check the Latin lines for one too.
        text to readers.values.flatten().distinct()
    }

    /** Top of the card (name + HP), grayscale, contrast-stretched and enlarged for OCR. */
    private fun headerStrip(card: Bitmap): Bitmap {
        val h = (card.height * HEADER).toInt().coerceAtLeast(1)
        val strip = crop(card, 0, 0, card.width, h).toRgbImage()
        val luma = strip.lumaArray()
        val sorted = luma.sorted()
        val lo = sorted[(sorted.size * 0.03).toInt()]
        val hi = sorted[(sorted.size * 0.97).toInt().coerceAtMost(sorted.size - 1)]
        val range = (hi - lo).coerceAtLeast(1.0)
        val px = IntArray(luma.size) { i ->
            val v = ((luma[i] - lo) / range * 255).toInt().coerceIn(0, 255)
            RgbImage.rgb(v, v, v)
        }
        val boosted = RgbImage(strip.width, strip.height, px).toBitmap()
        val scale = (1600.0 / boosted.width).coerceIn(1.0, 2.5)
        return Bitmap.createScaledBitmap(boosted, (boosted.width * scale).toInt(), (boosted.height * scale).toInt(), true)
    }

    private fun kanaCount(lines: List<OcrLine>) = lines.sumOf { l ->
        l.text.count {
            val sc = Character.UnicodeScript.of(it.code)
            sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA
        }
    }

    private suspend fun recognize(r: TextRecognizer, image: InputImage, card: Bitmap): List<OcrLine>? = try {
        val result: Text = r.process(image).await()
        val w = card.width.toDouble()
        val h = card.height.toDouble()
        result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(line.text, box.left / w, box.top / h, box.height() / h)
        }
    } catch (e: Exception) {
        null // model not downloaded yet, or recognition failed
    }

    private fun cjkCount(lines: List<OcrLine>) = lines.sumOf { l ->
        l.text.count {
            val sc = Character.UnicodeScript.of(it.code)
            sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA
        }
    }

    private fun hangulCount(lines: List<OcrLine>) = lines.sumOf { l ->
        l.text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL }
    }

    private suspend fun loadReference(card: TcgCard): RgbImage? {
        val url = card.images.small ?: card.images.large ?: return null
        referenceCache[url]?.let { return it }
        val img = withContext(Dispatchers.IO) {
            try {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.toRgbImage()
            } catch (e: Exception) {
                null
            }
        }
        referenceCache[url] = img
        return img
    }

    companion object {
        private const val GRADER_SIZE = 1200 // CardGrader.Config.workingSize
        /** Share of the card height that holds the name and HP. */
        private const val HEADER = 0.18
        private val ASIAN_BACKS = setOf("ja", "ko", "zh-tw", "zh-cn", "th", "id")

        fun downscale(bmp: Bitmap, longSide: Int): Bitmap {
            val long = max(bmp.width, bmp.height)
            if (long <= longSide) return bmp
            val s = longSide.toDouble() / long
            return Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true)
        }

        private fun crop(src: Bitmap, left: Int, top: Int, width: Int, height: Int): Bitmap {
            val l = left.coerceIn(0, src.width - 2)
            val t = top.coerceIn(0, src.height - 2)
            return Bitmap.createBitmap(src, l, t, width.coerceIn(1, src.width - l), height.coerceIn(1, src.height - t))
        }
    }
}

fun Bitmap.toRgbImage(): RgbImage {
    val bmp = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    val px = IntArray(bmp.width * bmp.height)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    return RgbImage(bmp.width, bmp.height, px)
}

fun RgbImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
