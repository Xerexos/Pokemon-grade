package com.pokegrade.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pokegrade.app.ui.CameraScreen
import com.pokegrade.app.ui.ConsentScreen
import com.pokegrade.app.ui.ResultScreen
import com.pokegrade.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PokeGradeTheme { App(vm) } }
    }
}

@Composable
fun App(vm: MainViewModel) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = vm.screen) {
            Screen.Consent -> {
                val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                ConsentScreen(
                    datasetAvailable = vm.dataset.available,
                    onAccept = vm::acceptPolicy,
                    onDecline = { activity?.finish() },
                )
            }
            Screen.Camera -> CameraScreen(onPhoto = vm::onPhoto, onSettings = vm::openSettings)
            Screen.BackCamera -> {
                BackHandler { vm.back() }
                CameraScreen(
                    onPhoto = vm::onBackPhoto,
                    onSettings = vm::openSettings,
                    hint = "Flip the card over and place its BACK inside the frame on a dark surface.",
                )
            }
            is Screen.Working -> Busy(s.message)
            is Screen.Result -> {
                BackHandler { vm.scanAnother() }
                ResultScreen(s.analysis, vm)
            }
            is Screen.Error -> {
                BackHandler { vm.scanAnother() }
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = vm::scanAnother) { Text("Try again") }
                }
            }
            Screen.Settings -> {
                BackHandler { vm.back() }
                SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun Busy(message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun PokeGradeTheme(content: @Composable () -> Unit) {
    val yellow = Color(0xFFF5CD28)
    val blue = Color(0xFF2563EB)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = yellow, onPrimary = Color(0xFF1F2937), secondary = Color(0xFF93C5FD))
    } else {
        lightColorScheme(primary = blue, secondary = Color(0xFFB45309))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
