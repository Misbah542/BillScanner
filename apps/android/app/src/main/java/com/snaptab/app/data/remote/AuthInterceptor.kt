package com.snaptab.app.data.remote

import com.snaptab.app.data.local.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the access token. The auth endpoints are skipped, so signing in and
 * refreshing never send a stale token that would be rejected before the body is read.
 */
@Singleton
class AuthInterceptor @Inject constructor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        if (path.contains("/auth/otp/") || path.endsWith("/auth/refresh") ||
            path.endsWith("/auth/google") || path.endsWith("/auth/methods") ||
            path.endsWith("/v1/config")
        ) {
            return chain.proceed(request)
        }

        // OkHttp interceptors are blocking by contract; this reads from DataStore, which
        // is a fast local read on a background thread OkHttp already owns.
        val token = runBlocking { tokenStore.accessToken() }
        val authorized = if (token.isNullOrBlank()) {
            request
        } else {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        }
        return chain.proceed(authorized)
    }
}
