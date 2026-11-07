package com.rutger.fastify.playback

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shuffle mode per context (spotify:playlist:..., album, artist, collection), not global:
 * playing a context starts in the mode it was last left in (off for new ones).
 */
class ShuffleStore(private val prefs: SharedPreferences) {
    /** Contexts with shuffle on; absent = off. */
    private val _on = MutableStateFlow(prefs.all.filterValues { it == true }.keys.toSet())
    val on: StateFlow<Set<String>> = _on

    fun get(contextUri: String): Boolean = contextUri in _on.value

    @Synchronized
    fun set(contextUri: String, on: Boolean) {
        if (get(contextUri) == on) return
        _on.value = if (on) _on.value + contextUri else _on.value - contextUri
        prefs.edit().apply { if (on) putBoolean(contextUri, true) else remove(contextUri) }.apply()
    }
}
