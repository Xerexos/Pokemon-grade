package com.pokegrade.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pokegrade.app.AiState
import com.pokegrade.app.MainViewModel
import com.pokegrade.app.SearchState
import com.pokegrade.app.analysis.Analysis
import com.pokegrade.core.auth.CheckStatus
import com.pokegrade.core.auth.Verdict
import com.pokegrade.core.grading.GradeResult
import com.pokegrade.core.pricing.PriceEstimator

private val GOOD = Color(0xFF16A34A)
private val WARN = Color(0xFFD97706)
private val BAD = Color(0xFFDC2626)

@Composable
fun ResultScreen(a: Analysis, vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Results", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::openSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
        Header(a, vm)
        SearchDialog(vm)
        GradeCard(a.grade)
        AuthenticityCard(a, vm)
        PriceCard(a, vm)
        AiCard(vm)
        Button(onClick = vm::scanAnother, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Scan another card")
        }
        Text(
            "Grades and authenticity are estimates from a single photo of the front. " +
                "Only a professional grading company (PSA, BGS, CGC) can certify a card.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun Header(a: Analysis, vm: MainViewModel) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            a.cardBitmap.asImageBitmap(), "Your card",
            Modifier.width(110.dp).height(154.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val c = a.card
            if (c != null) {
                Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${c.set.name} · #${c.number}/${c.set.printedTotal ?: "?"}", style = MaterialTheme.typography.bodyMedium)
                c.rarity?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            } else {
                Text(a.text.name ?: "Unknown card", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(a.lookupError ?: "No database match found.", style = MaterialTheme.typography.bodySmall, color = WARN)
            }
            TextButton(onClick = vm::openSearch) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Search for the card")
            }
            if (a.candidates.size > 1) {
                Box {
                    TextButton(onClick = { menu = true }) { Text("Other close matches (${a.candidates.size - 1})") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        a.candidates.forEach { cand ->
                            DropdownMenuItem(
                                text = { Text("${cand.name} — ${cand.set.name} #${cand.number}") },
                                leadingIcon = {
                                    AsyncImage(cand.images.small, null, Modifier.size(width = 30.dp, height = 42.dp))
                                },
                                onClick = { menu = false; vm.chooseCard(cand) },
                            )
                        }
                    }
                }
            }
        }
        a.card?.images?.small?.let {
            AsyncImage(it, "Official card", Modifier.width(70.dp).height(98.dp).clip(RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun GradeCard(g: GradeResult) {
    Section("Condition grade") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (g.reliable) gradeColor(g.overall) else Color.Gray),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (g.reliable) fmtGrade(g.overall) else "?", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                if (g.reliable) {
                    Text(g.label(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Centering ${g.centering.lrLabel()} L/R · ${g.centering.tbLabel()} T/B", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Couldn't grade this photo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Retake it to get a grade", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!g.reliable) {
            Spacer(Modifier.height(8.dp))
            g.reliabilityIssues.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = WARN) }
            Spacer(Modifier.height(8.dp))
            Text("Measurements below are low-confidence:", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        SubGrade("Centering", g.subGrades.centering)
        SubGrade("Corners", g.subGrades.corners)
        SubGrade("Edges", g.subGrades.edges)
        SubGrade("Surface", g.subGrades.surface)
        if (g.notes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            g.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SubGrade(name: String, v: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.width(84.dp), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { (v / 10.0).toFloat() },
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = gradeColor(v),
        )
        Text(fmtGrade(v), Modifier.width(44.dp).padding(start = 8.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AuthenticityCard(a: Analysis, vm: MainViewModel) {
    val r = a.authenticity
    val (color, icon) = when (r.verdict) {
        Verdict.LIKELY_AUTHENTIC -> GOOD to Icons.Default.CheckCircle
        Verdict.SUSPICIOUS -> WARN to Icons.Default.Warning
        Verdict.LIKELY_FAKE -> BAD to Icons.Default.Error
        Verdict.INCONCLUSIVE -> Color.Gray to Icons.Default.Help
    }
    Section("Real or fake?") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(r.verdict.label, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Confidence ${(r.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        r.checks.forEach { c ->
            val (ci, cc) = statusIcon(c.status)
            Row(Modifier.padding(vertical = 3.dp)) {
                Icon(ci, null, tint = cc, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(c.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BackSection(a, vm)
        Spacer(Modifier.height(6.dp))
        Text(
            "Also try at home: real cards have a black layer inside (shine a phone light through — fakes glow evenly).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PriceCard(a: Analysis, vm: MainViewModel) {
    val card = a.card
    Section("Market value") {
        if (card == null || a.price == null) {
            Text("Couldn't identify the card, so no price is available. Try a sharper photo or pick the right card above.")
            return@Section
        }
        if (a.authenticity.verdict == Verdict.LIKELY_FAKE) {
            Text("This card looks fake — a counterfeit has no collector value. Price shown is for a genuine copy.", color = BAD, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
        }
        val slab = vm.showSlabValue
        val grade = if (a.grade.reliable) a.grade.overall else null
        val p = remember(card.id, grade, slab) { PriceEstimator.estimate(card, grade, gradedSlab = slab && grade != null) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    p.estimatedValueUsd?.let { "$" + PriceEstimator.fmt(it) } ?: "—",
                    fontSize = 32.sp, fontWeight = FontWeight.Bold,
                )
                p.estimatedRangeUsd?.let {
                    Text("Range $${PriceEstimator.fmt(it.first)} – $${PriceEstimator.fmt(it.second)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (slab) "Graded slab" else "Raw card", style = MaterialTheme.typography.labelMedium)
                Switch(checked = slab && grade != null, enabled = grade != null, onCheckedChange = { vm.showSlabValue = it })
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        p.rawMarketUsd?.let { PriceRow("Near-mint market (${p.variant ?: "?"})", "$" + PriceEstimator.fmt(it)) }
        p.rawLowUsd?.let { PriceRow("TCGplayer low", "$" + PriceEstimator.fmt(it)) }
        p.rawHighUsd?.let { PriceRow("TCGplayer high", "$" + PriceEstimator.fmt(it)) }
        p.cardmarketTrendEur?.let { PriceRow("Cardmarket trend", "€" + PriceEstimator.fmt(it)) }
        p.updatedAt?.let { PriceRow("Prices updated", it) }
        Spacer(Modifier.height(6.dp))
        Text(p.explanation, style = MaterialTheme.typography.bodySmall)
        val ctx = LocalContext.current
        p.tcgplayerUrl?.let { url ->
            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("View on TCGplayer") }
        }
    }
}

@Composable
private fun BackSection(a: Analysis, vm: MainViewModel) {
    val back = a.back
    if (back == null) {
        OutlinedButton(onClick = vm::scanBack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FlipToBack, null); Spacer(Modifier.width(8.dp)); Text("Scan the back too")
        }
        Text(
            "Fake backs are often a lighter or purple-tinted blue, or blurry.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(back.bitmap.asImageBitmap(), "Card back", Modifier.width(56.dp).height(78.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Back scanned", fontWeight = FontWeight.SemiBold)
            Text("Back checks are included in the result above.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::scanBack) { Text("Rescan back") }
        }
    }
    val hasRef = remember(vm.referenceVersion) { vm.settings.referenceBack != null }
    TextButton(onClick = vm::saveBackAsReference) {
        Text(if (hasRef) "This card is genuine: replace my reference back" else "This card is genuine: save as my reference back")
    }
}

@Composable
private fun SearchDialog(vm: MainViewModel) {
    val s = vm.search as? SearchState.Open ?: return
    AlertDialog(
        onDismissRequest = vm::closeSearch,
        confirmButton = { TextButton(onClick = vm::closeSearch) { Text("Close") } },
        title = { Text("Search for the card") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        s.query, vm::updateQuery,
                        label = { Text("Card name") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = vm::runSearch) { Icon(Icons.Default.Search, "Search") }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    s.loading -> CircularProgressIndicator(Modifier.size(24.dp))
                    s.error != null -> Text(s.error, color = WARN, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.height(360.dp)) {
                    items(s.results, key = { it.id }) { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.pickSearchResult(b) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(b.smallImage, null, Modifier.width(44.dp).height(62.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(b.name, fontWeight = FontWeight.SemiBold)
                                Text("${b.id.substringBeforeLast('-')} · #${b.localId}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PriceRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AiCard(vm: MainViewModel) {
    Section("AI second opinion") {
        if (!vm.settings.hasAi) {
            Text(
                "Add your Anthropic API key in Settings to have Claude's vision model inspect the card " +
                    "and compare it with the official scan.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = vm::openSettings) { Text("Open Settings") }
            return@Section
        }
        when (val s = vm.ai) {
            AiState.Idle -> OutlinedButton(onClick = vm::runAi) { Text("Ask Claude to inspect this card") }
            AiState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Inspecting… this can take up to a minute.")
            }
            is AiState.Failed -> {
                Text(s.message, color = BAD)
                TextButton(onClick = vm::runAi) { Text("Retry") }
            }
            is AiState.Done -> {
                val r = s.result
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(gradeColor(r.overallGrade)),
                        contentAlignment = Alignment.Center,
                    ) { Text(fmtGrade(r.overallGrade), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(GradeResult.gradeLabel(r.overallGrade), fontWeight = FontWeight.Bold)
                        val (txt, col) = when (r.authenticity) {
                            "likely_authentic" -> "Likely authentic" to GOOD
                            "likely_fake" -> "Likely fake" to BAD
                            "suspicious" -> "Suspicious" to WARN
                            else -> "Inconclusive" to Color.Gray
                        }
                        Text("$txt (${(r.authenticityConfidence * 100).toInt()}%)", color = col)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Centering ${fmtGrade(r.centering)} · Corners ${fmtGrade(r.corners)} · Edges ${fmtGrade(r.edges)} · Surface ${fmtGrade(r.surface)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(r.summary, style = MaterialTheme.typography.bodyMedium)
                (r.conditionNotes + r.authenticityNotes).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

private fun statusIcon(s: CheckStatus): Pair<ImageVector, Color> = when (s) {
    CheckStatus.PASS -> Icons.Default.CheckCircle to GOOD
    CheckStatus.WARN -> Icons.Default.Warning to WARN
    CheckStatus.FAIL -> Icons.Default.Error to BAD
    CheckStatus.SKIPPED -> Icons.Default.Help to Color.Gray
}

private fun gradeColor(g: Double) = when {
    g >= 9 -> GOOD
    g >= 7 -> Color(0xFF65A30D)
    g >= 5 -> WARN
    else -> BAD
}

private fun fmtGrade(g: Double) = if (g % 1.0 == 0.0) g.toInt().toString() else "%.1f".format(g)
