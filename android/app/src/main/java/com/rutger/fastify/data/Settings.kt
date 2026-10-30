package com.rutger.fastify.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small user preferences (SharedPreferences), observable. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _autoplay = MutableStateFlow(prefs.getBoolean(KEY_AUTOPLAY, true))

    /** Keep playing recommendations when the queue runs out (Spotify's "Autoplay"). */
    val autoplay: StateFlow<Boolean> get() = _autoplay

    fun setAutoplay(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOPLAY, on).apply()
        _autoplay.value = on
    }

    private companion object {
        const val KEY_AUTOPLAY = "autoplay"
    }
}
