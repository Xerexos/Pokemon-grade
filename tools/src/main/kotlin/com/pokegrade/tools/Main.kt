package com.pokegrade.tools

import com.pokegrade.core.auth.BackChecker
import com.pokegrade.core.auth.BackFeatures
import com.pokegrade.core.grading.CardGrader
import com.pokegrade.core.image.RgbImage
import kotlinx.serialization.json.Json
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Developer tools that run the app's analysis on folders of labelled photos.
 *
 *   ./gradlew :tools:run --args="backs  samples/backs   [--write-model]"
 *   ./gradlew :tools:run --args="grades samples/graded"
 *
 * backs:  expects <dir>/real/ and <dir>/fake/ — prints how well each feature
 *         separates them, trains a logistic-regression model, reports held-out
 *         accuracy, and with --write-model saves it to core's resources so the
 *         app uses it.
 * grades: expects <dir>/<grade>/ (e.g. 10/, 9/, 8.5/) or a CSV with
 *         filename,grade — prints how far the app's grade is from the real one.
 */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "backs" -> backs(File(args.getOrElse(1) { "samples/backs" }), args.contains("--write-model"))
        "grades" -> grades(File(args.getOrElse(1) { "samples/graded" }))
        else -> println("usage: backs <dir> [--write-model] | grades <dir>")
    }
}

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

private fun images(dir: File): List<File> =
    dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in IMAGE_EXT }.sorted().toList()

private fun load(f: File): RgbImage? = runCatching {
    val bi = ImageIO.read(f) ?: return null
    val px = IntArray(bi.width * bi.height)
    bi.getRGB(0, 0, bi.width, bi.height, px, 0, bi.width)
    RgbImage(bi.width, bi.height, px)
}.getOrNull()

// ------------------------------------------------------------------ backs

private fun backs(dir: File, writeModel: Boolean) {
    val locator = CardGrader()
    fun featuresOf(sub: String): List<Pair<File, BackFeatures>> = images(File(dir, sub)).mapNotNull { f ->
        val img = load(f) ?: return@mapNotNull null.also { println("  skip unreadable ${f.name}") }
        val located = locator.locateCard(img)
        // Dataset photos are often tight crops; fall back to the whole image.
        val card = if (located.outlineOk) located.card else img
        f to BackChecker.features(card)
    }
    val real = featuresOf("real")
    val fake = featuresOf("fake")
    println("Loaded ${real.size} real and ${fake.size} fake backs from $dir")
    if (real.size < 5 || fake.size < 5) { println("Need at least 5 of each."); return }

    println("\nFeature separation (Cohen's d; |d| > 0.8 is a strong signal):")
    BackFeatures.NAMES.forEachIndexed { j, name ->
        val a = real.map { it.second.vector()[j] }
        val b = fake.map { it.second.vector()[j] }
        val d = (b.average() - a.average()) / sqrt((variance(a) + variance(b)) / 2).coerceAtLeast(1e-9)
        println("  %-15s real %.3f ± %.3f   fake %.3f ± %.3f   d=%+.2f".format(name, a.average(), sqrt(variance(a)), b.average(), sqrt(variance(b)), d))
    }

    // 5-fold cross-validation.
    val all = real.map { it to false } + fake.map { it to true }
    val shuffled = all.shuffled(Random(42))
    var correct = 0
    var tp = 0; var fp = 0; var fn = 0
    for (k in 0 until 5) {
        val test = shuffled.filterIndexed { i, _ -> i % 5 == k }
        val train = shuffled.filterIndexed { i, _ -> i % 5 != k }
        val m = BackChecker.train(train.filter { !it.second }.map { it.first.second }, train.filter { it.second }.map { it.first.second })
        for ((x, isFake) in test) {
            val pred = m.probabilityFake(x.second) >= 0.5
            if (pred == isFake) correct++
            if (pred && isFake) tp++
            if (pred && !isFake) fp++
            if (!pred && isFake) fn++
        }
    }
    val acc = correct.toDouble() / all.size
    println("\n5-fold cross-validated accuracy: %.1f%%  (fakes caught %d/%d, real cards wrongly flagged %d/%d)"
        .format(acc * 100, tp, fake.size, fp, real.size))

    val model = BackChecker.train(real.map { it.second }, fake.map { it.second }, label = "${dir.name}: ${real.size} real, ${fake.size} fake")
        .copy(testAccuracy = Math.round(acc * 1000) / 1000.0)
    println("\nWeights (positive = pushes towards fake):")
    model.featureNames.zip(model.weights).sortedByDescending { abs(it.second) }.forEach { (n, w) -> println("  %-15s %+.3f".format(n, w)) }

    if (writeModel) {
        val out = File("core/src/main/resources/back_model.json")
        out.parentFile.mkdirs()
        out.writeText(Json { prettyPrint = true }.encodeToString(com.pokegrade.core.auth.BackModel.serializer(), model))
        println("\nWrote ${out.path} — rebuild the app to use it.")
    }
}

private fun variance(v: List<Double>): Double {
    val m = v.average()
    return v.sumOf { (it - m) * (it - m) } / v.size
}

// ----------------------------------------------------------------- grades

private fun grades(dir: File) {
    val labelled: List<Pair<File, Double>> = run {
        val csv = File(dir, "labels.csv")
        if (csv.exists()) {
            csv.readLines().drop(1).mapNotNull { line ->
                val cols = line.split(',')
                val g = cols.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                File(dir, cols[0].trim()).takeIf { it.exists() }?.let { it to g }
            }
        } else {
            dir.listFiles().orEmpty().filter { it.isDirectory }.flatMap { sub ->
                val g = sub.name.removePrefix("psa").toDoubleOrNull() ?: return@flatMap emptyList()
                images(sub).map { it to g }
            }
        }
    }
    if (labelled.isEmpty()) { println("No labelled images found in $dir (use <grade>/ folders or labels.csv)."); return }

    val grader = CardGrader()
    val rows = labelled.mapNotNull { (f, truth) ->
        val img = load(f) ?: return@mapNotNull null
        val r = grader.grade(img)
        Triple(f, truth, r)
    }
    val reliable = rows.filter { it.third.reliable }
    println("Graded ${rows.size} photos; ${reliable.size} were gradable (${rows.size - reliable.size} rejected as unreliable).")
    if (reliable.isEmpty()) return
    val errs = reliable.map { it.third.overall - it.second }
    println("Mean absolute error: %.2f grades".format(errs.map(::abs).average()))
    println("Within 1 grade: %.0f%%".format(100.0 * errs.count { abs(it) <= 1.0 } / errs.size))
    println("Bias: %+.2f (positive = app grades too high)".format(errs.average()))
    println("\nBy true grade:")
    reliable.groupBy { it.second }.toSortedMap(compareByDescending { it }).forEach { (g, rs) ->
        println("  %-5s n=%-4d app avg %.1f   corners %.1f edges %.1f surface %.1f centering %.1f".format(
            g.toString(), rs.size, rs.map { it.third.overall }.average(),
            rs.map { it.third.subGrades.corners }.average(), rs.map { it.third.subGrades.edges }.average(),
            rs.map { it.third.subGrades.surface }.average(), rs.map { it.third.subGrades.centering }.average(),
        ))
    }
}
