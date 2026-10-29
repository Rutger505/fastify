package com.rutger.fastify.data

import com.rutger.fastify.auth.AuthManager
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
        repeat(4) { attempt ->
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + auth.token(forceRefresh))
                .build()
            http().newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> return@withContext resp.body.string()
                    resp.code == 401 && !forceRefresh -> forceRefresh = true
                    resp.code == 429 || resp.code >= 500 -> {
                        val wait = resp.header("Retry-After")?.toLongOrNull()?.times(1000)
                            ?: (500L shl attempt)
                        delay(wait.coerceAtMost(10_000))
                    }
                    else -> throw ApiException(resp.code, "Spotify API ${resp.code} for ${request.url.encodedPath}")
                }
            }
        }
        throw ApiException(429, "Spotify API kept failing for $url")
    }

    companion object {
        const val BASE = "https://api.spotify.com/v1"
    }
}
