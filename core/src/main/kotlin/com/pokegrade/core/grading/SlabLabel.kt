package com.pokegrade.core.grading

import com.pokegrade.core.catalog.OcrLine
import kotlinx.serialization.Serializable

/** What's printed on a grading company's slab label. */
@Serializable
data class SlabLabel(
    val company: String?,
    val grade: Double?,
    /** e.g. "GEM MINT", "PRISTINE", "BLACK LABEL". */
    val gradeWords: String?,
    val certNumber: String?,
    /** Label lines describing the card (year, set, name, number). */
    val description: List<String>,
    /** Bottom edge of the label, as a fraction of the photo height. */
    val labelBottom: Double,
) {
    val grader: Grader? get() = Grader.fromCode(company)
    val summary: String
        get() = buildString {
            append(grader?.displayName ?: company ?: "Graded slab")
            gradeWords?.let { append(" $it") }
            grade?.let { append(" ${GraderScales.fmt(it)}") }
        }
}

/**
 * Finds a slab label in OCR output of the whole photo (coordinates 0..1 of
 * the photo). Returns null when the photo doesn't look like a slab.
 */
object SlabLabelParser {

    /** Text on labels that identifies the company. Longest phrases first. */
    private val COMPANY_WORDS = listOf(
        "DIAMOND SERVICE GRADING" to "DSG",
        "DIAMOND SERVICE" to "DSG",
        "SERVICE GRADING" to "DSG",
        "PROFESSIONAL SPORTS AUTHENTICATOR" to "PSA",
        "BECKETT" to "BGS",
        "CGC" to "CGC", "PSA" to "PSA", "BGS" to "BGS", "SGC" to "SGC", "TAG" to "TAG",
        "ACE GRADING" to "ACE", "AGS" to "AGS", "HGA" to "HGA", "GMA" to "GMA", "MNT" to "MNT", "DSG" to "DSG",
    )

    private val GRADE_WORDS = listOf(
        "BLACK LABEL", "PRISTINE", "GEM MINT", "GEM MT", "GEM-MT", "MINT+", "MINT", "NM-MT", "NM MT",
        "NEAR MINT", "EX-MT", "EX MT", "VG-EX", "VERY GOOD", "EXCELLENT", "GOOD", "FAIR", "POOR", "AUTHENTIC",
    )

    private val NUMBER = Regex("""^(10|[1-9](?:\.5)?)$""")
    private val INLINE_GRADE = Regex("""(BLACK LABEL|PRISTINE|GEM\s*-?\s*MI?N?T|MINT\+?|NM-?\s*MT|NEAR MINT|EX-?\s*MT|VG-?EX|VERY GOOD|EXCELLENT|GOOD|FAIR|POOR)\s*(10|[1-9](?:\.5)?)\b""")
    private val CERT = Regex("""\b(\d{7,12})\b""")

    fun parse(lines: List<OcrLine>): SlabLabel? {
        // Labels sit at the top of the slab.
        val top = lines.filter { it.top < 0.4 }
        if (top.isEmpty()) return null
        val upper = top.map { it to it.text.uppercase().trim() }

        val companyHit = upper.firstNotNullOfOrNull { (l, t) ->
            COMPANY_WORDS.firstOrNull { (w, _) ->
                Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(t) &&
                    // "TAG TEAM" is a card type, not the TAG grading company.
                    !(w == "TAG" && t.contains("TAG TEAM"))
            }?.let { l to it.second }
        }
        val cert = upper.firstNotNullOfOrNull { (l, t) ->
            CERT.findAll(t).map { it.value }.filterNot { it.length == 4 }.maxByOrNull { it.length }?.let { l to it }
        }

        // Grade: "GEM MINT 10" on one line, or the words and the number on separate lines.
        var grade: Double? = null
        var gradeWords: String? = null
        var gradeLine: OcrLine? = null
        for ((l, t) in upper) {
            INLINE_GRADE.find(t)?.let { m ->
                grade = m.groupValues[2].toDouble()
                gradeWords = normalizeWords(m.groupValues[1])
                gradeLine = l
            }
            if (grade != null) break
        }
        if (grade == null) {
            val wordLine = upper.firstOrNull { (_, t) -> GRADE_WORDS.any { t == it || t.startsWith("$it ") } }
            if (wordLine != null) {
                gradeWords = normalizeWords(GRADE_WORDS.first { wordLine.second == it || wordLine.second.startsWith("$it ") })
                // Nearest standalone number to the grade words.
                val num = upper.filter { (_, t) -> NUMBER.matches(t) }
                    .minByOrNull { (l, _) -> distance(l, wordLine.first) }
                if (num != null && distance(num.first, wordLine.first) < 0.15) {
                    grade = num.second.toDouble()
                    gradeLine = num.first
                }
                gradeLine = gradeLine ?: wordLine.first
            }
        }

        // It's a slab if we found a company name, or a grade together with a cert number.
        // It's a slab if we found a company name, or grade words / a grade together with
        // a cert number. (Logos like DSG's script are often unreadable.)
        if (companyHit == null && !(cert != null && (grade != null || gradeWords != null))) return null

        val anchors = listOfNotNull(companyHit?.first, cert?.first, gradeLine)
        val anchorBottom = anchors.maxOf { it.top + it.height }
        val anchorTop = anchors.minOf { it.top }
        // Other label lines: Latin text in the label band (year, set, card name).
        val labelLines = upper.filter { (l, t) ->
            l.top >= anchorTop - 0.05 && l.top <= anchorBottom + 0.02 &&
                t.count { it in 'A'..'Z' } >= 3 && t.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        }
        val bottom = (labelLines.map { it.first } + anchors).maxOf { it.top + it.height }
        val description = labelLines
            .map { it.second }
            .filter { t -> COMPANY_WORDS.none { (w, _) -> t.contains(w) } && !CERT.containsMatchIn(t) && INLINE_GRADE.find(t) == null && GRADE_WORDS.none { t == it } }

        // The big grade digit is often a stylised graphic OCR can't read; the words
        // printed next to it still pin it down.
        val company = companyHit?.second
        val finalGrade = grade ?: gradeWords?.let { gradeFromWords(it, company) }

        return SlabLabel(
            company = company,
            grade = finalGrade,
            gradeWords = gradeWords,
            certNumber = cert?.second,
            description = description,
            labelBottom = bottom.coerceIn(0.0, 0.6),
        )
    }

    /** Standard label wording for each grade. Beckett prints "Gem Mint" on a 9.5. */
    fun gradeFromWords(words: String, company: String?): Double? = when (words.uppercase()) {
        "BLACK LABEL", "PRISTINE" -> 10.0
        "GEM MINT" -> if (company == "BGS") 9.5 else 10.0
        "MINT+" -> 9.5
        "MINT" -> 9.0
        "NM-MT", "NM MT" -> 8.0
        "NEAR MINT" -> 7.0
        "EX-MT", "EX MT" -> 6.0
        "EXCELLENT" -> 5.0
        "VG-EX" -> 4.0
        "VERY GOOD" -> 3.0
        "GOOD" -> 2.0
        "FAIR" -> 1.5
        "POOR" -> 1.0
        else -> null
    }

    private fun normalizeWords(w: String) = w.replace(Regex("""\s+"""), " ").replace("GEM MT", "GEM MINT").replace("GEM-MT", "GEM MINT").trim()

    private fun distance(a: OcrLine, b: OcrLine): Double {
        val dy = (a.top + a.height / 2) - (b.top + b.height / 2)
        val dx = a.left - b.left
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
