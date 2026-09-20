package com.snaptab.app.data.repository

import android.os.Build
import com.snaptab.app.BuildConfig
import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.local.SnapTabDatabase
import com.snaptab.app.data.local.TokenStore
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: SnapTabApi,
    private val tokenStore: TokenStore,
    private val database: SnapTabDatabase
) {

    val isSignedIn: Flow<Boolean> = tokenStore.isSignedIn

    /** Which methods the server actually offers, so the UI does not show a dead Google button. */
    suspend fun availableMethods(): ApiResult<AuthMethodsResponse> = apiCall { api.authMethods() }

    suspend fun startOtp(contact: String): ApiResult<OtpStartResponse> =
        apiCall { api.startOtp(OtpStartRequest(contact.trim())) }

    suspend fun verifyOtp(contact: String, code: String, name: String? = null): ApiResult<UserDto?> =
        apiCall {
            api.verifyOtp(
                OtpVerifyRequest(
                    contact = contact.trim(),
                    code = code.trim(),
                    name = name?.trim()?.ifBlank { null },
                    device = describeDevice()
                )
            )
        }.map { response ->
            persist(response)
            response.user
        }

    suspend fun signInWithGoogle(idToken: String): ApiResult<UserDto?> =
        apiCall { api.signInWithGoogle(GoogleSignInRequest(idToken, describeDevice())) }
            .map { response ->
                persist(response)
                response.user
            }

    /** Signs out of this device only. The local cache goes with it. */
    suspend fun signOut(): ApiResult<Unit> {
        val result = apiCall { api.logout() }
        // Clear locally whatever the server said: a failed call must not leave the user
        // stuck in a session they asked to end.
        clearLocalState()
        return if (result is ApiResult.Failure && !result.isOffline && result.httpStatus != 401) {
            result
        } else {
            ApiResult.Success(Unit)
        }
    }

    suspend fun signOutEverywhere(): ApiResult<Unit> {
        val result = apiCall { api.logoutEverywhere() }
        clearLocalState()
        return result.map { }
    }

    suspend fun deleteAccount(): ApiResult<Unit> {
        val result = apiCall { api.deleteAccount() }
        if (result is ApiResult.Success) clearLocalState()
        return result.map { }
    }

    private suspend fun persist(response: AuthResponse) {
        tokenStore.saveSession(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            expiresInSeconds = response.expiresIn,
            userId = response.user?.id,
            userName = response.user?.name,
            currency = response.user?.currency
        )
    }

    private suspend fun clearLocalState() {
        tokenStore.clearSession()
        database.expenses().clear()
        database.alerts().clear()
        database.pendingAlerts().clear()
        database.groups().clear()
        database.monthlySummaries().clear()
    }

    private suspend fun describeDevice() = DeviceRequest(
        installId = tokenStore.installId(),
        platform = "ANDROID",
        appVersion = BuildConfig.VERSION_NAME,
        osVersion = "Android ${Build.VERSION.RELEASE}",
        model = "${Build.MANUFACTURER} ${Build.MODEL}".take(64),
        smsEnabled = tokenStore.isSmsEnabledNow()
    )
}
