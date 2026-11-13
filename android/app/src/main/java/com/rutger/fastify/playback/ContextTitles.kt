package com.rutger.fastify.playback

import android.content.SharedPreferences

/**
 * Display names of play contexts (spotify:playlist:... -> "Your Top Songs 2025"), recorded when
 * a context is played from a page, for the player header. Contexts started elsewhere (Connect)
 * fall back to the library, see [com.rutger.fastify.ui.screens.contextTitle].
 */
class ContextTitles(private val prefs: SharedPreferences) {
    fun get(contextUri: String): String? = prefs.getString(contextUri, null)

    fun set(contextUri: String, title: String) {
        if (title.isNotBlank() && get(contextUri) != title) prefs.edit().putString(contextUri, title).apply()
    }
}
