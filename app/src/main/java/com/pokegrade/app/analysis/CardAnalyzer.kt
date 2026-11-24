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

        // Is it in a graded slab? Read the label and only measure the card below it.
        onProgress("Looking for a slab label…")
        val slab = recognize(latin, InputImage.fromBitmap(working, 0), working)?.let(SlabLabelParser::parse)
        val offsetY = if (slab != null) ((slab.labelBottom + 0.01) * working.height).toInt().coerceIn(0, working.height / 2) else 0
        val area = if (offsetY > 0) crop(working, 0, offsetY, working.width, working.height - offsetY) else working

        onProgress("Measuring centering, corners, edges and surface…")
        val grade = withContext(Dispatchers.Default) { grader.grade(area.toRgbImage()) }
        // Bounds are in the working image; crop the card from the full-resolution photo
        // (capped at 2000 px) so text reading, the AI check and uploads get the detail.
        val k = photo.width.toDouble() / working.width
        val cardBitmap = downscale(
            crop(
                photo,
                (grade.bounds.left * k).toInt(), ((grade.bounds.top + offsetY) * k).toInt(),
                (grade.bounds.width * k).toInt(), (grade.bounds.height * k).toInt(),
            ),
            2000,
        )

        onProgress("Reading card text…")
        val text = readText(cardBitmap)

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
    private suspend fun readText(card: Bitmap): ParsedCardText = coroutineScope {
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

        val best = when {
            ja != null && kanaCount(ja) >= 2 -> ja
            ko != null && hangulCount(ko) >= 2 -> ko
            zh != null && cjkCount(zh) >= 2 -> zh
            ja != null && cjkCount(ja) >= 2 -> ja
            else -> latinLines
        }
        CardTextParser.parse(best)
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
