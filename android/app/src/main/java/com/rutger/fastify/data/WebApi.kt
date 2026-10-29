package com.rutger.fastify.data

import com.rutger.fastify.auth.AuthManager
import com.rutger.fastify.diag.AppLog
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class ApiException(val code: Int, message: String) : IOException(message)

/** Minimal Spotify Web API client with token refresh and 429 back-off. */
class WebApi(private val http: () -> OkHttpClient, private val auth: AuthManager) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    suspend fun <T> get(pathOrUrl: String, serializer: KSerializer<T>): T =
        json.decodeFromString(serializer, getRaw(pathOrUrl))

    suspend fun getRaw(pathOrUrl: String): String = withContext(Dispatchers.IO) {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else BASE + pathOrUrl
        var forceRefresh = false
        repeat(3) { attempt ->
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + auth.token(forceRefresh))
                .build()
            val start = System.currentTimeMillis()
            http().newCall(request).execute().use { resp ->
                val ms = System.currentTimeMillis() - start
                if (resp.isSuccessful) {
                    AppLog.i("WebApi", "GET ${request.url.encodedPath} -> ${resp.code} ($ms ms)")
                    return@withContext resp.body.string()
                }
                val body = resp.body.string().take(300).replace("\n", " ")
                val retryAfter = resp.header("Retry-After")?.toLongOrNull()
                val msg = "HTTP ${resp.code} for GET ${request.url.encodedPath}" +
                    (retryAfter?.let { " (Retry-After ${it}s)" } ?: "") + ": $body"
                AppLog.w("WebApi", "$msg ($ms ms, attempt ${attempt + 1})")
                when {
                    resp.code == 401 && !forceRefresh -> forceRefresh = true
                    // Long Retry-After = blocked/quota, not a blip: fail now so the UI shows it.
                    resp.code == 429 && (retryAfter ?: 0) > 5 -> throw ApiException(429, msg)
                    resp.code == 429 || resp.code >= 500 -> delay(retryAfter?.times(1000) ?: (500L shl attempt))
                    else -> throw ApiException(resp.code, msg)
                }
            }
        }
        throw ApiException(429, "Spotify API kept failing for $url")
    }

    companion object {
        const val BASE = "https://api.spotify.com/v1"
    }
}
