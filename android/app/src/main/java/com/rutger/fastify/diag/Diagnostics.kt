package com.rutger.fastify.diag

import android.net.Uri
import com.rutger.fastify.AppContainer
import com.rutger.fastify.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request

data class ProbeResult(val name: String, val ok: Boolean, val detail: String, val ms: Long)

/** Probes every way of talking to Spotify, so one shared log shows what works. */
class Diagnostics(private val c: AppContainer) {

    suspend fun run(onResult: (ProbeResult) -> Unit): List<ProbeResult> {
        val results = mutableListOf<ProbeResult>()
        var user: String? = null
        suspend fun probe(name: String, block: suspend () -> String) {
            val start = System.currentTimeMillis()
            val r = try {
                ProbeResult(name, true, withTimeout(20_000) { block() }, System.currentTimeMillis() - start)
            } catch (e: Throwable) {
                ProbeResult(name, false, "${e.javaClass.simpleName}: ${e.message}", System.currentTimeMillis() - start)
            }
            AppLog.i("Diag", "${if (r.ok) "OK  " else "FAIL"} ${r.name} (${r.ms} ms): ${r.detail}")
            results += r
            onResult(r)
        }

        probe("Librespot session") { c.core.username().also { user = it }.let { "user=$it" } }
        probe("Web API /me (OAuth token)") { webApi("/me", c.auth.token()) }
        probe("Web API search (OAuth token)") { webApi("/search?q=daft%20punk&type=track&limit=5", c.auth.token()) }
        probe("Web API /me/playlists (OAuth token)") { webApi("/me/playlists?limit=5", c.auth.token()) }
        probe("Web API /me (keymaster token)") {
            webApi("/me", c.core.accessToken(AuthManager.SCOPES.joinToString(",")).token)
        }
        val u = user?.let(Uri::encode) ?: "unknown"
        probe("spclient rootlist") {
            spclient("/playlist/v2/user/$u/rootlist?decorate=revision,attributes,length,owner&from=0&length=10")
        }
        probe("spclient recently-played") {
            spclient("/recently-played/v3/user/$u/recently-played?format=json&limit=5&filter=default")
        }
        probe("spclient searchview") {
            spclient("/searchview/km/v4/search/daft%20punk?limit=5&entityVersion=2&catalogue=premium")
        }
        return results
    }

    private suspend fun spclient(path: String): String {
        val bytes = c.core.spclientGet(path)
        val text = bytes.toString(Charsets.UTF_8).filter { it.isLetterOrDigit() || it in " {}[]\":,._-/" }
        return "${bytes.size} bytes: " + text.take(200)
    }

    private suspend fun webApi(path: String, token: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.spotify.com/v1$path").header("Authorization", "Bearer $token").build()
        c.http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            val retry = resp.header("Retry-After")?.let { " retry-after=${it}s" }.orEmpty()
            val summary = "HTTP ${resp.code}$retry: ${body.take(200).replace("\n", " ")}"
            if (!resp.isSuccessful) error(summary)
            summary
        }
    }
}
