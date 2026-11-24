package com.pokegrade.core.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaUrlImageSource
import com.anthropic.models.beta.messages.MessageCreateParams
import com.pokegrade.core.catalog.TcgCard
import com.pokegrade.core.grading.GradeResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration

@Serializable
data class AiInspection(
    @SerialName("overall_grade") val overallGrade: Double,
    val centering: Double,
    val corners: Double,
    val edges: Double,
    val surface: Double,
    val authenticity: String,
    @SerialName("authenticity_confidence") val authenticityConfidence: Double,
    @SerialName("card_name") val cardName: String,
    @SerialName("set_name") val setName: String,
    @SerialName("card_number") val cardNumber: String,
    @SerialName("condition_notes") val conditionNotes: List<String>,
    @SerialName("authenticity_notes") val authenticityNotes: List<String>,
    val summary: String,
)

class AiInspectionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Optional second opinion from Claude's vision model. Sends the card photo
 * (plus the official scan, when we have one) together with the on-device
 * measurements and gets back a structured grade + authenticity read.
 *
 * Requires the user's own Anthropic API key (entered in Settings).
 */
class ClaudeCardInspector(apiKey: String, private val model: String = DEFAULT_MODEL) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(180))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param jpegBase64 the card photo, JPEG, base64 (no line breaks). Keep the long side ≤ 1568 px.
     */
    fun inspect(jpegBase64: String, card: TcgCard?, onDevice: GradeResult?): AiInspection {
        val content = mutableListOf<BetaContentBlockParam>()
        content += BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text("Photo of the card to grade:").build())
        content += BetaContentBlockParam.ofImage(
            BetaImageBlockParam.builder()
                .source(
                    BetaBase64ImageSource.builder()
                        .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                        .data(jpegBase64)
                        .build()
                )
                .build()
        )
        val refUrl = card?.let { it.images.large ?: it.images.small }
        if (card != null && refUrl != null) {
            content += BetaContentBlockParam.ofText(
                BetaTextBlockParam.builder().text("Official scan of ${card.name} (${card.set.name} #${card.number}) for comparison:").build()
            )
            content += BetaContentBlockParam.ofImage(
                BetaImageBlockParam.builder().source(BetaUrlImageSource.builder().url(refUrl).build()).build()
            )
        }
        content += BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(buildPrompt(card, onDevice)).build())

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
            .addUserMessageOfContentBlockParams(content)
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.HIGH)
                    .format(BetaJsonOutputFormat.builder().schema(schema()).build())
                    .build()
            )
            // If a safety classifier declines, retry on a fallback model server-side.
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacksDefault()
            .build()

        val response = try {
            client.beta().messages().create(params)
        } catch (e: Exception) {
            throw AiInspectionException("AI request failed: ${e.message}", e)
        }

        val stop = response.stopReason().map { it.asString() }.orElse("")
        if (stop == "refusal") throw AiInspectionException("The AI declined to analyse this image.")
        if (stop == "max_tokens") throw AiInspectionException("The AI response was cut off. Try again.")

        val text = response.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        if (text.isBlank()) throw AiInspectionException("The AI returned no result.")
        return try {
            json.decodeFromString(AiInspection.serializer(), text)
        } catch (e: Exception) {
            throw AiInspectionException("Couldn't read the AI result.", e)
        }
    }

    private fun buildPrompt(card: TcgCard?, g: GradeResult?) = buildString {
        appendLine("Grade this Pokémon card's condition on the PSA 1–10 scale and assess whether it is authentic.")
        if (card != null) {
            appendLine()
            appendLine("Database match: ${card.name}, ${card.set.name} (${card.set.series}), #${card.number}/${card.set.printedTotal}, rarity ${card.rarity}, HP ${card.hp}.")
            if (card.attacks.isNotEmpty()) appendLine("Official attacks: ${card.attacks.joinToString { it.name }}.")
        }
        if (g != null) {
            appendLine()
            appendLine("On-device measurements (computer vision, may be thrown off by glare or angle):")
            appendLine("- Centering L/R ${g.centering.lrLabel()}, T/B ${g.centering.tbLabel()}")
            appendLine("- Corner wear (TL,TR,BL,BR, 0=clean): ${g.cornerWear}")
            appendLine("- Edge wear (top,right,bottom,left): ${g.edgeWear}")
            appendLine("- Surface defect ratio on border: ${g.surfaceDefectRatio}")
            appendLine("- Photo issues: ${g.photoQuality.problems.ifEmpty { listOf("none") }}")
        }
        appendLine()
        appendLine("Use the measurements as hints, but trust what you can see. Only the front is visible, so say so if the back could change the grade.")
        appendLine("For authenticity, compare font, colour, holo pattern, energy symbols, text spelling, borders and layout against the official scan.")
        appendLine("If the photo is too blurry or glary to judge something, lower your confidence and say what to reshoot.")
    }

    private fun schema(): BetaJsonOutputFormat.Schema {
        fun num(desc: String) = mapOf("type" to "number", "description" to desc)
        fun str(desc: String) = mapOf("type" to "string", "description" to desc)
        fun strList(desc: String) = mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to desc)
        val props = linkedMapOf<String, Any>(
            "overall_grade" to num("Overall PSA-style grade, 1-10, may use .5 steps"),
            "centering" to num("Centering subgrade 1-10"),
            "corners" to num("Corners subgrade 1-10"),
            "edges" to num("Edges subgrade 1-10"),
            "surface" to num("Surface subgrade 1-10"),
            "authenticity" to mapOf(
                "type" to "string",
                "enum" to listOf("likely_authentic", "suspicious", "likely_fake", "inconclusive"),
            ),
            "authenticity_confidence" to num("0-1"),
            "card_name" to str("Card name as printed"),
            "set_name" to str("Set name, or empty if unknown"),
            "card_number" to str("Collector number as printed, e.g. 025/198"),
            "condition_notes" to strList("Specific flaws seen, each short"),
            "authenticity_notes" to strList("Specific authenticity observations, each short"),
            "summary" to str("Two-sentence plain-English summary for a collector"),
        )
        return BetaJsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(props))
            .putAdditionalProperty("required", JsonValue.from(props.keys.toList()))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"

        private const val SYSTEM_PROMPT =
            "You are an experienced Pokémon TCG card grader and authenticator, familiar with PSA, BGS and CGC " +
                "standards and with common counterfeit tells. You are looking at a phone photo, so be honest about " +
                "what a photo can and cannot show. Be calibrated: most raw pack-fresh modern cards grade 8-10; " +
                "visible whitening, soft corners or scratches pull the grade down quickly."
    }
}
