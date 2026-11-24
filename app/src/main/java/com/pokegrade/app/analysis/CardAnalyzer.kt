package com.pokegrade.app.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pokegrade.core.ai.AiInspection
import com.pokegrade.core.ai.ClaudeCardInspector
import com.pokegrade.core.auth.AuthenticityChecker
import com.pokegrade.core.auth.AuthenticityReport
import com.pokegrade.core.catalog.CardTextParser
import com.pokegrade.core.catalog.OcrLine
import com.pokegrade.core.catalog.ParsedCardText
import com.pokegrade.core.catalog.PokemonTcgClient
import com.pokegrade.core.catalog.TcgCard
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

data class Analysis(
    val cardBitmap: Bitmap,
    val grade: GradeResult,
    val text: ParsedCardText,
    val candidates: List<TcgCard>,
    val card: TcgCard?,
    val authenticity: AuthenticityReport,
    val price: PriceEstimate?,
    val lookupError: String? = null,
)

/**
 * The whole pipeline: computer-vision grading → OCR → database lookup →
 * authenticity checks → price. All steps run on-device except the card
 * lookup (Pokémon TCG API) and the optional AI opinion.
 */
class CardAnalyzer(private val tcgApiKey: String?) {

    private val grader = CardGrader()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val tcg get() = PokemonTcgClient(tcgApiKey)
    private val referenceCache = HashMap<String, RgbImage?>()

    suspend fun analyze(photo: Bitmap, onProgress: (String) -> Unit): Analysis {
        onProgress("Measuring centering, corners, edges and surface…")
        // Downscale to the grader's working size so its bounds map 1:1 onto this bitmap.
        val working = withContext(Dispatchers.Default) { downscale(photo, GRADER_SIZE) }
        val grade = withContext(Dispatchers.Default) { grader.grade(working.toRgbImage()) }

        val b = grade.bounds
        val left = b.left.coerceIn(0, working.width - 2)
        val top = b.top.coerceIn(0, working.height - 2)
        val cardBitmap = Bitmap.createBitmap(
            working, left, top,
            b.width.coerceIn(1, working.width - left),
            b.height.coerceIn(1, working.height - top),
        )

        onProgress("Reading card text…")
        val text = readText(cardBitmap)

        onProgress("Looking up ${text.name ?: "card"} in the Pokémon TCG database…")
        var lookupError: String? = null
        val candidates = try {
            withContext(Dispatchers.IO) { tcg.findCandidates(text) }
        } catch (e: Exception) {
            lookupError = "Card lookup failed: ${e.message ?: "network error"}"
            emptyList()
        }

        val card = candidates.firstOrNull()
        return finish(cardBitmap, grade, text, candidates, card, lookupError, onProgress)
    }

    /** Re-run authenticity and price for a different database match chosen by the user. */
    suspend fun withCard(a: Analysis, card: TcgCard, onProgress: (String) -> Unit): Analysis =
        finish(a.cardBitmap, a.grade, a.text, a.candidates, card, a.lookupError, onProgress)

    private suspend fun finish(
        cardBitmap: Bitmap,
        grade: GradeResult,
        text: ParsedCardText,
        candidates: List<TcgCard>,
        card: TcgCard?,
        lookupError: String?,
        onProgress: (String) -> Unit,
    ): Analysis {
        onProgress("Comparing with the official card…")
        val reference = card?.let { loadReference(it) }
        val auth = withContext(Dispatchers.Default) {
            AuthenticityChecker.check(text, card, cardBitmap.toRgbImage(), reference)
        }
        val price = card?.let { PriceEstimator.estimate(it, grade.overall) }
        return Analysis(cardBitmap, grade, text, candidates, card, auth, price, lookupError)
    }

    suspend fun aiInspect(apiKey: String, a: Analysis): AiInspection = withContext(Dispatchers.IO) {
        val jpeg = downscale(a.cardBitmap, 1568)
        val bytes = ByteArrayOutputStream().also { jpeg.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        ClaudeCardInspector(apiKey).inspect(b64, a.card, a.grade)
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
    }
}

fun Bitmap.toRgbImage(): RgbImage {
    val bmp = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    val px = IntArray(bmp.width * bmp.height)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    return RgbImage(bmp.width, bmp.height, px)
}
