package com.rutger.fastify.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Network up/down for the app log, one line per change: the default network's transport and
 * whether it has internet, and whether Android blocks it for the app (Doze, data saver), which
 * looks like "offline" to the core (DNS lookups fail). While down, [AppLog] holds back repeated
 * errors. [state] feeds the core's reconnect coordinator (FastifyCore.setNetwork), unsettled.
 */
object NetworkLog {
    /** Default network usable by the app (available, not blocked) and validated; null = unknown. */
    data class State(val available: Boolean, val validated: Boolean)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private var last: String? = null
    private var caps: NetworkCapabilities? = null
    private var blocked = false
    private var available = false
    private val main = Handler(Looper.getMainLooper())
    private val reportLater = Runnable { report() }

    fun start(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        // No callback at all = no network: report that too.
        main.postDelayed(reportLater, SETTLE_MS)
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { AppLog.w("Network", "network callback failed", it) }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update { available = true }
        override fun onLost(network: Network) = update { available = false; caps = null; blocked = false }
        override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) = update { available = true; caps = c }
        override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) = update { blocked = isBlocked }
    }

    /** Report once the state settled (it flaps at process start: down, blocked, up). */
    @Synchronized
    private fun update(change: () -> Unit) {
        change()
        val usable = available && !blocked
        _state.value = State(usable, usable && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        main.removeCallbacks(reportLater)
        main.postDelayed(reportLater, SETTLE_MS)
    }

    @Synchronized
    private fun report() {
        val c = caps
        val online = available && !blocked
        val what = when {
            !available -> "network down"
            blocked -> "network blocked for the app (Doze / data saver)"
            c == null -> "network up"
            else -> {
                val via = listOfNotNull(
                    "wifi".takeIf { c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                    "cellular".takeIf { c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                    "ethernet".takeIf { c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
                    "vpn".takeIf { c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
                ).joinToString("+").ifEmpty { "other" }
                val internet = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                "network up: $via" + if (internet) "" else " (no internet yet)"
            }
        }
        // Capabilities change often (signal, bandwidth): only the summary counts.
        if (what == last) return
        last = what
        AppLog.network(online, what)
    }

    private const val SETTLE_MS = 1_000L
}
