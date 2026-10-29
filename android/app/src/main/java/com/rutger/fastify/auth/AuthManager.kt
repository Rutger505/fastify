package com.rutger.fastify.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.rutger.fastify.core.FastifyCore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OAuth (authorization code + PKCE) with Spotify's desktop client id, the same flow librespot uses.
 * No developer app needed. The redirect lands on a loopback HTTP server inside the app.
 *
 * The access token feeds both the Web API (library, search) and the first librespot login;
 * librespot then stores reusable credentials so later launches skip OAuth entirely.
 */
class AuthManager(
    context: Context,
    private val http: () -> OkHttpClient,
    private val core: () -> FastifyCore,
) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _loggedIn = MutableStateFlow(prefs.getString(KEY_REFRESH, null) != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn
    val isLoggedIn: Boolean get() = _loggedIn.value

    @Volatile private var accessToken: String? = prefs.getString(KEY_ACCESS, null)
    @Volatile private var expiresAt: Long = prefs.getLong(KEY_EXPIRES, 0)

    /** Runs the whole login. [openBrowser] must show the URL (Custom Tab). */
    suspend fun login(openBrowser: suspend (Uri) -> Unit) = withContext(Dispatchers.IO) {
        val verifier = randomToken(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomToken(16)
        val url = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .build()

        val code = ServerSocket(REDIRECT_PORT, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5 * 60 * 1000
            withContext(Dispatchers.Main) { openBrowser(url) }
            awaitCode(server, state)
        }
        val tokens = requestToken(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", CLIENT_ID)
                .add("code_verifier", verifier)
                .build(),
        )
        val refresh = tokens.refreshToken ?: error("Spotify returned no refresh token")
        store(tokens.accessToken, tokens.expiresIn, refresh)
        com.rutger.fastify.diag.AppLog.i("Auth", "OAuth ok, scopes granted: ${tokens.scope}")
        core().login(tokens.accessToken)
        com.rutger.fastify.diag.AppLog.i("Auth", "librespot login ok")
        _loggedIn.value = true
    }

    /** A valid Web API token; refreshes when needed. */
    suspend fun token(forceRefresh: Boolean = false): String = mutex.withLock {
        val cached = accessToken
        if (!forceRefresh && cached != null && System.currentTimeMillis() < expiresAt - 60_000) {
            return cached
        }
        withContext(Dispatchers.IO) {
            val refresh = prefs.getString(KEY_REFRESH, null) ?: throw NotLoggedInException()
            try {
                val t = requestToken(
                    FormBody.Builder()
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refresh)
                        .add("client_id", CLIENT_ID)
                        .build(),
                )
                store(t.accessToken, t.expiresIn, t.refreshToken ?: refresh)
                t.accessToken
            } catch (e: Exception) {
                // Fall back to a keymaster token through the librespot session.
                com.rutger.fastify.diag.AppLog.w("Auth", "token refresh failed, falling back to keymaster", e)
                val t = core().accessToken(SCOPES.joinToString(","))
                accessToken = t.token
                expiresAt = t.expiresAtMs.toLong()
                t.token
            }
        }
    }

    fun logout() {
        prefs.edit().clear().apply()
        accessToken = null
        expiresAt = 0
        runCatching { core().logout() }
        _loggedIn.value = false
    }

    private fun store(access: String, expiresIn: Long, refresh: String) {
        accessToken = access
        expiresAt = System.currentTimeMillis() + expiresIn * 1000
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putLong(KEY_EXPIRES, expiresAt)
            .putString(KEY_REFRESH, refresh)
            .apply()
    }

    private fun requestToken(body: FormBody): TokenResponse {
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        http().newCall(request).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("token endpoint ${resp.code}: $text")
            return json.decodeFromString(TokenResponse.serializer(), text)
        }
    }

    /** Accept loopback connections until the /login redirect arrives. */
    private fun awaitCode(server: ServerSocket, expectedState: String): String {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                error("Login timed out")
            }
            socket.use { s ->
                val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine().orEmpty()
                val path = line.split(" ").getOrNull(1).orEmpty()
                val uri = Uri.parse("http://127.0.0.1$path")
                if (uri.path != "/login") {
                    s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return@use
                }
                val ok = uri.getQueryParameter("state") == expectedState && uri.getQueryParameter("code") != null
                val body = if (ok) SUCCESS_HTML else FAILURE_HTML
                val bytes = body.toByteArray()
                s.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes,
                )
                s.getOutputStream().flush()
                if (!ok) error(uri.getQueryParameter("error") ?: "Login was not completed")
                return uri.getQueryParameter("code")!!
            }
        }
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("refresh_token") val refreshToken: String? = null,
        val scope: String? = null,
    )

    class NotLoggedInException : IllegalStateException("Not logged in")

    companion object {
        private const val TAG = "FastifyAuth"
        /** Spotify desktop client (librespot's KEYMASTER_CLIENT_ID). */
        const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
        private const val REDIRECT_PORT = 8898
        private const val REDIRECT_URI = "http://127.0.0.1:8898/login"
        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        val SCOPES = listOf(
            "streaming",
            "user-read-private",
            "user-read-email",
            "playlist-read-private",
            "playlist-read-collaborative",
            "user-library-read",
            "user-library-modify",
            "user-read-recently-played",
            "user-top-read",
            "user-follow-read",
        )
        private const val KEY_ACCESS = "access"
        private const val KEY_EXPIRES = "expires"
        private const val KEY_REFRESH = "refresh"

        private val SUCCESS_HTML = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width">
            <meta http-equiv="refresh" content="0;url=fastify://auth"></head>
            <body style="background:#121212;color:#fff;font-family:sans-serif;text-align:center;padding-top:30vh">
            <h2>Logged in ⚡</h2><p><a style="color:#1ed760" href="fastify://auth">Back to Fastify</a></p>
            </body></html>
        """.trimIndent()
        private val FAILURE_HTML = """
            <!doctype html><html><body style="background:#121212;color:#fff;font-family:sans-serif;text-align:center;padding-top:30vh">
            <h2>Login failed</h2><p><a style="color:#1ed760" href="fastify://auth">Back to Fastify</a></p></body></html>
        """.trimIndent()

        private fun randomToken(bytes: Int): String =
            base64Url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

        private fun base64Url(data: ByteArray): String =
            Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
