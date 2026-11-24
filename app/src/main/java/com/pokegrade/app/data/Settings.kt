package com.pokegrade.app.data

import android.content.Context
import com.pokegrade.core.auth.BackFeatures
import kotlinx.serialization.json.Json

/** API key, preferences and the user's reference back, stored privately on the device. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("pokegrade", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var anthropicApiKey: String
        get() = prefs.getString(KEY_ANTHROPIC, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_ANTHROPIC, v.trim()).apply()

    /** Run the AI second opinion automatically after every scan. */
    var autoAi: Boolean
        get() = prefs.getBoolean(KEY_AUTO_AI, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_AI, v).apply()

    /** Measurements from the back of a card the user knows is genuine. */
    var referenceBack: BackFeatures?
        get() = prefs.getString(KEY_REF_BACK, null)?.let {
            runCatching { json.decodeFromString(BackFeatures.serializer(), it) }.getOrNull()
        }
        set(v) = prefs.edit().apply {
            if (v == null) remove(KEY_REF_BACK) else putString(KEY_REF_BACK, json.encodeToString(BackFeatures.serializer(), v))
        }.apply()

    val hasAi get() = anthropicApiKey.isNotBlank()

    private companion object {
        const val KEY_ANTHROPIC = "anthropic_key"
        const val KEY_AUTO_AI = "auto_ai"
        const val KEY_REF_BACK = "reference_back"
    }
}
