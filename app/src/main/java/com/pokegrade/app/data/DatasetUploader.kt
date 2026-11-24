package com.pokegrade.app.data

import android.content.Context
import android.graphics.Bitmap
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.pokegrade.app.analysis.Analysis
import com.pokegrade.app.analysis.CardAnalyzer
import com.pokegrade.core.auth.BackFeatures
import com.pokegrade.core.grading.GradeResult
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream

/** What the user knows about a card, from a source outside the app. */
enum class LabelType(val title: String, val description: String) {
    GRADED_SLAB("In a graded slab", "PSA, Beckett, CGC, SGC, TAG, ACE, DSG and more"),
    PULLED_GENUINE("I pulled it from a pack", "Known genuine"),
    CONFIRMED_FAKE("Confirmed fake", "You know this card is counterfeit"),
}

data class ScanLabel(
    val type: LabelType,
    val company: String? = null,
    val grade: Double? = null,
    val certNumber: String? = null,
    /** Free-text company name when company is OTHER. */
    val companyName: String? = null,
)

/**
 * Uploads labelled scans to the app owner's Firebase project (Cloud
 * Firestore only, so it fits the free Spark plan). Photos are stored as
 * compressed JPEG bytes in their own documents, separate from the metadata.
 *
 * Inactive unless the app was built with a google-services.json.
 */
class DatasetUploader(private val context: Context) {

    val available: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    private val json = Json { encodeDefaults = true }

    /** Anonymous per-install ID (Firebase anonymous auth). */
    suspend fun anonymousId(): String? {
        if (!available) return null
        val auth = FirebaseAuth.getInstance()
        return (auth.currentUser ?: auth.signInAnonymously().await().user)?.uid
    }

    /** @return the new scan's document ID. */
    suspend fun upload(a: Analysis, label: ScanLabel): String {
        check(available) { "Dataset uploads aren't set up in this build." }
        val uid = anonymousId() ?: error("Couldn't sign in to upload.")
        val db = FirebaseFirestore.getInstance()
        val scanRef = db.collection("scans").document()
        val id = scanRef.id

        val images = mutableMapOf<String, String>()
        suspend fun putImage(name: String, bmp: Bitmap) {
            val docId = "${id}_$name"
            db.collection("scanImages").document(docId).set(
                mapOf("uid" to uid, "scanId" to id, "kind" to name, "jpeg" to Blob.fromBytes(jpeg(bmp))),
            ).await()
            images[name] = docId
        }
        putImage("front", a.cardBitmap)
        a.back?.let { putImage("back", it.bitmap) }

        val card = a.card
        val doc = mapOf(
            "uid" to uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "appVersion" to appVersion(),
            "label" to mapOf(
                "type" to label.type.name,
                "company" to label.company,
                "grade" to label.grade,
                "companyName" to label.companyName?.trim()?.take(30),
                "certNumber" to label.certNumber?.uppercase()?.filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' },
                // Filled in later by the owner's tools after checking the cert.
                "verified" to false,
            ),
            "card" to card?.let {
                mapOf("id" to it.id, "language" to it.language, "name" to it.name, "set" to it.set.name, "number" to it.number, "rarity" to it.rarity)
            },
            "ocr" to mapOf("name" to a.text.name, "hp" to a.text.hp, "number" to a.text.number, "setTotal" to a.text.setTotal),
            "grade" to toFirestore(json.encodeToJsonElement(GradeResult.serializer(), a.grade)),
            "back" to a.back?.features?.let { toFirestore(json.encodeToJsonElement(BackFeatures.serializer(), it)) },
            "appVerdict" to mapOf(
                "verdict" to a.authenticity.verdict.name,
                "confidence" to a.authenticity.confidence,
            ),
            "images" to images,
        )
        scanRef.set(doc).await()
        return id
    }

    /** Delete everything this install uploaded. @return number of scans removed. */
    suspend fun deleteMine(): Int {
        if (!available) return 0
        val uid = anonymousId() ?: return 0
        val db = FirebaseFirestore.getInstance()
        var removed = 0
        for (col in listOf("scanImages", "scans")) {
            val docs = db.collection(col).whereEqualTo("uid", uid).get().await()
            for (d in docs.documents) {
                d.reference.delete().await()
                if (col == "scans") removed++
            }
        }
        return removed
    }

    /** Keep each image well under Firestore's 1 MB document limit. */
    private fun jpeg(bmp: Bitmap): ByteArray {
        var b = CardAnalyzer.downscale(bmp, 1000)
        var quality = 88
        while (true) {
            val bytes = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (bytes.size < 700_000 || quality <= 50) return bytes
            quality -= 12
            if (quality <= 64) b = CardAnalyzer.downscale(b, 800)
        }
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun toFirestore(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { toFirestore(it.value) }
        is JsonArray -> e.map { toFirestore(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            else -> e.doubleOrNull
        }
    }
}
