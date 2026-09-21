package com.snaptab.app.data.repository

import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.local.TokenStore
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val api: SnapTabApi,
    private val tokenStore: TokenStore
) {

    suspend fun me(): ApiResult<UserDto> = apiCall { api.me() }.map { it.user }

    suspend fun updateProfile(
        name: String? = null,
        currency: String? = null,
        timezone: String? = null
    ): ApiResult<UserDto> =
        apiCall {
            api.updateMe(
                UpdateMeRequest(
                    name = name?.trim()?.ifBlank { null },
                    currency = currency,
                    timezone = timezone
                )
            )
        }.map { it.user }

    /**
     * Turning the "keep the message text" setting off also tells the server to forget
     * what it already kept — the setting means what it says, retroactively.
     */
    suspend fun setKeepAlertBodies(keep: Boolean): ApiResult<UserDto> =
        apiCall { api.updateMe(UpdateMeRequest(keepAlertBodies = keep)) }
            .map { response ->
                tokenStore.setKeepAlertBodies(keep)
                response.user
            }

    suspend fun lookup(contact: String): ApiResult<LookupResponse> =
        apiCall { api.lookup(contact.trim()) }

    /** People the user has split with before, for the add-people suggestions. */
    suspend fun recentPeople(): ApiResult<List<RecentPersonDto>> =
        apiCall { api.recentPeople() }.map { it.people }

    suspend fun notifications(unreadOnly: Boolean = false): ApiResult<NotificationListResponse> =
        apiCall { api.notifications(unreadOnly) }

    suspend fun markNotificationsRead(ids: List<String> = emptyList()): ApiResult<Unit> =
        apiCall { api.markNotificationsRead(MarkReadRequest(ids)) }.map { }
}
