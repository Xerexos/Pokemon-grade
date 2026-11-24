package com.pokegrade.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pokegrade.app.analysis.Analysis
import com.pokegrade.app.analysis.CardAnalyzer
import com.pokegrade.app.data.Settings
import com.pokegrade.core.ai.AiInspection
import com.pokegrade.core.catalog.TcgCard
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Camera : Screen
    data class Working(val message: String) : Screen
    data class Result(val analysis: Analysis) : Screen
    data class Error(val message: String) : Screen
    data object Settings : Screen
}

sealed interface AiState {
    data object Idle : AiState
    data object Running : AiState
    data class Done(val result: AiInspection) : AiState
    data class Failed(val message: String) : AiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)

    var screen by mutableStateOf<Screen>(Screen.Camera)
        private set
    var ai by mutableStateOf<AiState>(AiState.Idle)
        private set
    /** Show the value as a professionally graded slab instead of a raw card. */
    var showSlabValue by mutableStateOf(false)

    private var analyzer = CardAnalyzer(settings.pokemonTcgApiKey)
    private var last: Analysis? = null

    fun onPhoto(photo: Bitmap) {
        ai = AiState.Idle
        screen = Screen.Working("Starting…")
        viewModelScope.launch {
            try {
                val result = analyzer.analyze(photo) { screen = Screen.Working(it) }
                last = result
                screen = Screen.Result(result)
                if (settings.autoAi && settings.hasAi) runAi()
            } catch (e: Exception) {
                screen = Screen.Error(e.message ?: "Something went wrong analysing the photo.")
            }
        }
    }

    fun chooseCard(card: TcgCard) {
        val current = last ?: return
        ai = AiState.Idle
        viewModelScope.launch {
            val updated = analyzer.withCard(current, card) { }
            last = updated
            screen = Screen.Result(updated)
        }
    }

    fun runAi() {
        val current = last ?: return
        if (!settings.hasAi) return
        ai = AiState.Running
        viewModelScope.launch {
            ai = try {
                AiState.Done(analyzer.aiInspect(settings.anthropicApiKey, current))
            } catch (e: Exception) {
                AiState.Failed(e.message ?: "AI request failed.")
            }
        }
    }

    fun openSettings() { screen = Screen.Settings }

    fun saveSettings(anthropicKey: String, tcgKey: String, autoAi: Boolean) {
        settings.anthropicApiKey = anthropicKey
        settings.pokemonTcgApiKey = tcgKey
        settings.autoAi = autoAi
        analyzer = CardAnalyzer(settings.pokemonTcgApiKey)
        back()
    }

    fun back() {
        screen = last?.let { if (screen is Screen.Settings) Screen.Result(it) else null } ?: Screen.Camera
    }

    fun scanAnother() {
        last = null
        ai = AiState.Idle
        screen = Screen.Camera
    }
}
