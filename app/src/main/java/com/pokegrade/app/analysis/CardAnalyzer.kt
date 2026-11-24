package com.pokegrade.app.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
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
)

/**
 * The whole pipeline: computer-vision grading → OCR → database lookup →
 * authenticity checks → price. Everything runs on the phone except the card
 * lookup (TCGdex) and the optional AI opinion.
 */
class CardAnalyzer(private val catalog: CardCatalog = TcgDexClient()) {

    private val grader = CardGrader()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val referenceCache = HashMap<String, RgbImage?>()

    suspend fun analyze(photo: Bitmap, onProgress: (String) -> Unit): Analysis {
        onProgress("Measuring centering, corners, edges and surface…")
        // Downscale to the grader's working size so its bounds map 1:1 onto this bitmap.
        val working = withContext(Dispatchers.Default) { downscale(photo, GRADER_SIZE) }
        val grade = withContext(Dispatchers.Default) { grader.grade(working.toRgbImage()) }
        val cardBitmap = crop(working, grade.bounds.left, grade.bounds.top, grade.bounds.width, grade.bounds.height)

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
        return finish(cardBitmap, grade, text, candidates, candidates.firstOrNull(), lookupError, null, onProgress)
    }

    /** Re-run authenticity and price for a different database match chosen by the user. */
    suspend fun withCard(a: Analysis, card: TcgCard, onProgress: (String) -> Unit = {}): Analysis {
        val candidates = if (a.candidates.any { it.id == card.id }) a.candidates else listOf(card) + a.candidates
        return finish(a.cardBitmap, a.grade, a.text, candidates, card, null, a.back, onProgress)
    }

    suspend fun search(name: String): List<CardBrief> = withContext(Dispatchers.IO) { catalog.searchByName(name) }

    suspend fun getCard(id: String): TcgCard = withContext(Dispatchers.IO) { catalog.getCard(id) }

    /** Scan the back and fold its checks into the authenticity verdict. */
    suspend fun withBack(a: Analysis, photo: Bitmap, reference: BackFeatures?): Analysis {
        val back = withContext(Dispatchers.Default) {
            val located = grader.locateCard(downscale(photo, GRADER_SIZE).toRgbImage())
            val features = BackChecker.features(located.card)
            val checks = BackChecker.check(features, reference).toMutableList()
            if (!located.outlineOk) {
                checks.add(0, AuthCheck("Back photo", CheckStatus.WARN, "Couldn't find the card's outline clearly; results may be off. Use a dark background.", 0.5))
            }
            BackScan(located.card.toBitmap(), features, checks, located.outlineOk)
        }
        return finish(a.cardBitmap, a.grade, a.text, a.candidates, a.card, a.lookupError, back) {}
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

    private suspend fun readText(card: Bitmap): ParsedCardText {
        val result = recognizer.process(InputImage.fromBitmap(card, 0)).await()
        val w = card.width.toDouble()
        val h = card.height.toDouble()
        val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(line.text, box.left / w, box.top / h, box.height() / h)
        }
        return CardTextParser.parse(lines)
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
