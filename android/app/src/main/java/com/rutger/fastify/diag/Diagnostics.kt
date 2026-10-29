package com.rutger.fastify.diag

import com.rutger.fastify.AppContainer
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
        var firstPlaylist: String? = null
        probe("Playlists (rootlist)") {
            val p = c.core.playlists()
            firstPlaylist = p.firstOrNull { it.length > 0u }?.id
            "${p.size} playlists, first: " + p.take(3).joinToString { "${it.name} (${it.length})" }
        }
        firstPlaylist?.let { id ->
            probe("Playlist tracks") { c.core.playlistTracks(id).let { "${it.size} tracks, first: ${it.firstOrNull()?.title}" } }
        }
        probe("Liked songs (collection)") { c.core.likedTracks().let { "${it.size} tracks, first: ${it.firstOrNull()?.title}" } }
        probe("Saved albums (collection)") { c.core.savedAlbums().let { "${it.size} albums, first: ${it.firstOrNull()?.name}" } }
        probe("Search (context-resolve)") {
            c.core.searchTracks("daft punk").let { "${it.size} tracks: " + it.take(3).joinToString { t -> t.title } }
        }
        probe("Web API /me (OAuth token, expected 429)") { webApi("/me", c.auth.token()) }
        return results
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
