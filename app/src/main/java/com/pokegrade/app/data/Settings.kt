package com.pokegrade.app.data

import android.content.Context

/** API keys and preferences, stored privately on the device. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("pokegrade", Context.MODE_PRIVATE)

    var anthropicApiKey: String
        get() = prefs.getString(KEY_ANTHROPIC, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_ANTHROPIC, v.trim()).apply()

    var pokemonTcgApiKey: String
        get() = prefs.getString(KEY_TCG, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_TCG, v.trim()).apply()

    /** Run the AI second opinion automatically after every scan. */
    var autoAi: Boolean
        get() = prefs.getBoolean(KEY_AUTO_AI, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_AI, v).apply()

    val hasAi get() = anthropicApiKey.isNotBlank()

    private companion object {
        const val KEY_ANTHROPIC = "anthropic_key"
        const val KEY_TCG = "pokemontcg_key"
        const val KEY_AUTO_AI = "auto_ai"
    }
}
