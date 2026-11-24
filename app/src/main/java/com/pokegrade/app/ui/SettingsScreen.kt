package com.pokegrade.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pokegrade.app.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var anthropic by remember { mutableStateOf(vm.settings.anthropicApiKey) }
    var auto by remember { mutableStateOf(vm.settings.autoAi) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("AI second opinion (optional)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Paste an Anthropic API key from console.anthropic.com to let Claude inspect your card photos. " +
                "Each inspection is billed to your Anthropic account (typically a few cents). " +
                "The photo is sent to Anthropic only when you ask for an AI opinion.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            anthropic, { anthropic = it },
            label = { Text("Anthropic API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Run AI inspection automatically after each scan", Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { auto = it })
        }

        Text("Reference back", style = MaterialTheme.typography.titleMedium)
        val hasRef = remember(vm.referenceVersion) { vm.settings.referenceBack != null }
        Text(
            if (hasRef) "Saved. Backs you scan are compared with your known-genuine card."
            else "None saved. Scan the back of a card you know is real and tap \"Save as my genuine reference\".",
            style = MaterialTheme.typography.bodySmall,
        )
        if (hasRef) OutlinedButton(onClick = vm::clearReference) { Text("Clear reference back") }

        Text("Shared dataset", style = MaterialTheme.typography.titleMedium)
        if (!vm.dataset.available) {
            Text("Not set up in this build of the app.", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Upload labelled scans to help improve grading and fake detection", Modifier.weight(1f))
                Switch(checked = vm.shareScans, onCheckedChange = vm::updateShareScans)
            }
            vm.anonymousId?.let { Text("Your anonymous ID: $it", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = vm::deleteMyScans) { Text("Delete my uploaded scans") }
            vm.settingsMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        var showPolicy by remember { mutableStateOf(false) }
        TextButton(onClick = { showPolicy = !showPolicy }) { Text(if (showPolicy) "Hide privacy policy" else "Privacy policy") }
        if (showPolicy) Text(rememberPolicyText(), style = MaterialTheme.typography.bodySmall)

        Text(
            "Card data and prices come from the free TCGdex database (TCGplayer and Cardmarket prices, updated daily).",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::back) { Text("Cancel") }
            Button(onClick = { vm.saveSettings(anthropic, auto) }) { Text("Save") }
        }
    }
}
