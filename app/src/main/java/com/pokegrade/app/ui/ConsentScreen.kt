package com.pokegrade.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Reads the bundled privacy policy text. */
@Composable
fun rememberPolicyText(): String {
    val context = LocalContext.current
    return remember { context.assets.open("PRIVACY.md").bufferedReader().use { it.readText() } }
}

/**
 * Shown on first launch and whenever the policy version changes. The app
 * can't be used until the policy is accepted. Sharing scans is a separate,
 * unticked-by-default choice.
 */
@Composable
fun ConsentScreen(
    datasetAvailable: Boolean,
    onAccept: (shareScans: Boolean) -> Unit,
    onDecline: () -> Unit,
) {
    val policy = rememberPolicyText()
    var agreed by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp).padding(top = 28.dp)) {
        Text("Before you start", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Please read and accept the privacy policy.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(6.dp))
        Text(
            policy,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        )
        Spacer(Modifier.padding(4.dp))
        CheckRow(agreed, { agreed = it }, "I have read and agree to the privacy policy")
        if (datasetAvailable) {
            CheckRow(share, { share = it }, "Optional: let me upload labelled scans to the shared dataset (you can change this in Settings)")
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text("Decline & exit") }
            Button(onClick = { onAccept(share) }, enabled = agreed, modifier = Modifier.weight(1f)) { Text("Accept") }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
