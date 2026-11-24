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
import com.pokegrade.app.data.DatasetUploader
import com.pokegrade.app.data.ScanLabel
import com.pokegrade.app.data.Settings
import com.pokegrade.core.ai.AiInspection
import com.pokegrade.core.catalog.CardBrief
import com.pokegrade.core.catalog.TcgCard
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Consent : Screen
    data object Camera : Screen
    data object BackCamera : Screen
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

sealed interface UploadState {
    data object Idle : UploadState
    data object Uploading : UploadState
    data class Done(val scanId: String) : UploadState
    data class Failed(val message: String) : UploadState
}

sealed interface SearchState {
    data object Closed : SearchState
    data class Open(
        val query: String = "",
        val language: String = "en",
        val loading: Boolean = false,
        val results: List<CardBrief> = emptyList(),
        val error: String? = null,
    ) : SearchState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)

    val dataset = DatasetUploader(app)

    var screen by mutableStateOf<Screen>(
        if (settings.acceptedPolicyVersion < POLICY_VERSION) Screen.Consent else Screen.Camera,
    )
        private set
    var upload by mutableStateOf<UploadState>(UploadState.Idle)
        private set
    var shareScans by mutableStateOf(settings.shareScans)
        private set
    /** Anonymous install ID, shown in Settings for deletion requests. */
    var anonymousId by mutableStateOf<String?>(null)
        private set
    var settingsMessage by mutableStateOf<String?>(null)
        private set
    var ai by mutableStateOf<AiState>(AiState.Idle)
        private set
    var search by mutableStateOf<SearchState>(SearchState.Closed)
        private set
    /** Show the value as a professionally graded slab instead of a raw card. */
    var showSlabValue by mutableStateOf(false)
    /** Bumped when the reference back changes, so the UI re-reads it. */
    var referenceVersion by mutableStateOf(0)
        private set

    private val analyzer = CardAnalyzer()
    private var last: Analysis? = null

    // ---------------------------------------------------------- consent

    fun acceptPolicy(share: Boolean) {
        settings.acceptedPolicyVersion = POLICY_VERSION
        updateShareScans(share)
        screen = Screen.Camera
    }

    fun updateShareScans(on: Boolean) {
        settings.shareScans = on
        shareScans = on
        if (on) loadAnonymousId()
    }

    fun loadAnonymousId() {
        if (!dataset.available) return
        viewModelScope.launch { anonymousId = runCatching { dataset.anonymousId() }.getOrNull() }
    }

    // ---------------------------------------------------------- dataset

    fun uploadScan(label: ScanLabel) {
        val current = last ?: return
        upload = UploadState.Uploading
        viewModelScope.launch {
            upload = try {
                UploadState.Done(dataset.upload(current, label))
            } catch (e: Exception) {
                UploadState.Failed(e.message ?: "Upload failed.")
            }
        }
    }

    fun deleteMyScans() {
        settingsMessage = "Deleting…"
        viewModelScope.launch {
            settingsMessage = try {
                "Deleted ${dataset.deleteMine()} uploaded scan(s)."
            } catch (e: Exception) {
                "Couldn't delete: ${e.message}"
            }
        }
    }

    fun onPhoto(photo: Bitmap) {
        ai = AiState.Idle
        upload = UploadState.Idle
        screen = Screen.Working("Starting…")
        viewModelScope.launch {
            try {
                val result = analyzer.analyze(photo) { screen = Screen.Working(it) }
                show(result)
                if (settings.autoAi && settings.hasAi) runAi()
            } catch (e: Exception) {
                screen = Screen.Error(e.message ?: "Something went wrong analysing the photo.")
            }
        }
    }

    fun chooseCard(card: TcgCard) {
        val current = last ?: return
        ai = AiState.Idle
        viewModelScope.launch { show(analyzer.withCard(current, card)) }
    }

    // ------------------------------------------------------------ search

    fun openSearch() {
        search = SearchState.Open(
            query = last?.text?.name.orEmpty(),
            language = last?.card?.language ?: last?.text?.languages?.firstOrNull() ?: "en",
        )
        if ((search as SearchState.Open).query.isNotBlank()) runSearch()
    }

    fun closeSearch() { search = SearchState.Closed }

    fun updateQuery(q: String) {
        val s = search as? SearchState.Open ?: return
        search = s.copy(query = q)
    }

    fun updateSearchLanguage(lang: String) {
        val s = search as? SearchState.Open ?: return
        search = s.copy(language = lang, results = emptyList())
        runSearch()
    }

    fun runSearch() {
        val s = search as? SearchState.Open ?: return
        if (s.query.isBlank()) return
        search = s.copy(loading = true, error = null)
        viewModelScope.launch {
            search = try {
                val results = analyzer.search(s.query, s.language)
                s.copy(loading = false, results = results, error = if (results.isEmpty()) "No cards named \"${s.query}\"." else null)
            } catch (e: Exception) {
                s.copy(loading = false, error = "Search failed: ${e.message}")
            }
        }
    }

    fun pickSearchResult(brief: CardBrief) {
        val current = last ?: return
        search = SearchState.Closed
        ai = AiState.Idle
        screen = Screen.Working("Loading ${brief.name}…")
        viewModelScope.launch {
            try {
                show(analyzer.withCard(current, analyzer.getCard(brief.id, brief.language)))
            } catch (e: Exception) {
                show(current)
            }
        }
    }

    // -------------------------------------------------------------- back

    fun scanBack() { screen = Screen.BackCamera }

    fun onBackPhoto(photo: Bitmap) {
        val current = last ?: return
        screen = Screen.Working("Checking the back…")
        viewModelScope.launch {
            try {
                show(analyzer.withBack(current, photo, settings.referenceBack))
            } catch (e: Exception) {
                screen = Screen.Error(e.message ?: "Couldn't analyse the back.")
            }
        }
    }

    /** Save the current back as the user's known-genuine reference. */
    fun saveBackAsReference() {
        val current = last ?: return
        val back = current.back ?: return
        settings.referenceBack = back.features
        referenceVersion++
        // Re-run the back checks against the new reference.
        viewModelScope.launch {
            show(analyzer.withBack(current, back.bitmap, back.features))
        }
    }

    fun clearReference() {
        settings.referenceBack = null
        referenceVersion++
    }

    // ---------------------------------------------------------------- AI

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

    // ---------------------------------------------------------- navigation

    fun openSettings() {
        settingsMessage = null
        if (shareScans) loadAnonymousId()
        screen = Screen.Settings
    }

    fun saveSettings(anthropicKey: String, autoAi: Boolean) {
        settings.anthropicApiKey = anthropicKey
        settings.autoAi = autoAi
        back()
    }

    fun back() {
        screen = last?.let { Screen.Result(it) } ?: Screen.Camera
    }

    fun scanAnother() {
        last = null
        ai = AiState.Idle
        search = SearchState.Closed
        screen = Screen.Camera
    }

    companion object {
        /** Bump when PRIVACY.md changes so everyone re-accepts. */
        const val POLICY_VERSION = 1
    }

    private fun show(a: Analysis) {
        last = a
        screen = Screen.Result(a)
    }
}
