package com.snaptab.app.data.remote

import com.snaptab.app.data.local.TokenStore
import com.snaptab.app.data.remote.dto.AuthResponse
import com.snaptab.app.data.remote.dto.RefreshRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Refreshes the access token on a 401 and replays the request, so the user is never
 * bounced to the sign-in screen for an expired token.
 *
 * The refresh call is made with a bare client rather than the injected Retrofit
 * instance, because going back through this authenticator would recurse. A mutex
 * means five requests failing at once produce one refresh, not five — and since the
 * server rotates refresh tokens and treats reuse as a breach, five concurrent
 * refreshes would revoke the user's entire session.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStore: TokenStore,
    private val baseUrlProvider: Provider<String>,
    private val json: Json
) : Authenticator {

    private val refreshMutex = Mutex()
    private val bareClient by lazy { OkHttpClient.Builder().build() }

    override fun authenticate(route: Route?, response: Response): Request? {
        // Already retried once with a fresh token and still 401: stop, or we loop.
        if (responseCount(response) >= 2) return null
        if (response.request.url.encodedPath.endsWith("/auth/refresh")) return null

        val staleToken = response.request.header("Authorization")?.removePrefix("Bearer ")?.trim()

        return runBlocking {
            refreshMutex.withLock {
                val current = tokenStore.accessToken()
                // Another request refreshed while we waited on the lock: reuse its token.
                if (!current.isNullOrBlank() && current != staleToken) {
                    return@withLock response.request.newBuilder()
                        .header("Authorization", "Bearer $current")
                        .build()
                }

                val refreshToken = tokenStore.refreshToken() ?: return@withLock null
                val refreshed = refresh(refreshToken) ?: run {
                    // The refresh token is dead; drop the session so the UI shows sign-in.
                    tokenStore.clearSession()
                    return@withLock null
                }

                tokenStore.saveSession(
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken,
                    expiresInSeconds = refreshed.expiresIn
                )

                response.request.newBuilder()
                    .header("Authorization", "Bearer ${refreshed.accessToken}")
                    .build()
            }
        }
    }

    private fun refresh(refreshToken: String): AuthResponse? = runCatching {
        val base = baseUrlProvider.get().trimEnd('/')
        val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$base/v1/auth/refresh")
            .post(body)
            .build()

        bareClient.newCall(request).execute().use { httpResponse ->
            if (!httpResponse.isSuccessful) return@runCatching null
            val text = httpResponse.body?.string() ?: return@runCatching null
            json.decodeFromString(AuthResponse.serializer(), text)
        }
    }.getOrNull()

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count += 1
            prior = prior.priorResponse
        }
        return count
    }
}
