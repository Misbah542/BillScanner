package com.snaptab.app.data.repository

import android.os.Build
import com.snaptab.app.BuildConfig
import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.local.TokenStore
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.DeviceRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the server's idea of this device current: its push token, and whether the user
 * has granted SMS reading here. The server needs the second one to know whether to
 * expect alerts from this phone at all.
 */
@Singleton
class DeviceRepository @Inject constructor(
    private val api: SnapTabApi,
    private val tokenStore: TokenStore
) {

    suspend fun registerPushToken(pushToken: String): ApiResult<Unit> = register(pushToken = pushToken)

    suspend fun setSmsEnabled(enabled: Boolean): ApiResult<Unit> {
        tokenStore.setSmsEnabled(enabled)
        return register(smsEnabled = enabled)
    }

    suspend fun register(
        pushToken: String? = null,
        smsEnabled: Boolean? = null
    ): ApiResult<Unit> {
        // Not signed in yet: the device is registered as part of the sign-in call instead.
        if (tokenStore.refreshToken().isNullOrBlank()) return ApiResult.Success(Unit)

        return apiCall {
            api.registerDevice(
                DeviceRequest(
                    installId = tokenStore.installId(),
                    platform = "ANDROID",
                    pushToken = pushToken,
                    appVersion = BuildConfig.VERSION_NAME,
                    osVersion = "Android ${Build.VERSION.RELEASE}",
                    model = "${Build.MANUFACTURER} ${Build.MODEL}".take(64),
                    smsEnabled = smsEnabled ?: tokenStore.isSmsEnabledNow()
                )
            )
        }.map { }
    }
}
