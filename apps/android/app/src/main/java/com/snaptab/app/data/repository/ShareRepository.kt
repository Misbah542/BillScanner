package com.snaptab.app.data.repository

import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.CreateShareLinkRequest
import com.snaptab.app.data.remote.dto.ShareLinkDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareRepository @Inject constructor(private val api: SnapTabApi) {

    /**
     * A read-only link to a tab. SUMMARY leaves the receipt photo out; FULL includes it.
     * Either way the bank alert behind the expense is never exposed.
     */
    suspend fun linkForExpense(
        expenseId: String,
        includeReceipt: Boolean = false,
        expiresInDays: Int? = null
    ): ApiResult<ShareLinkDto> =
        apiCall {
            api.createShareLink(
                CreateShareLinkRequest(
                    expenseId = expenseId,
                    scope = if (includeReceipt) "FULL" else "SUMMARY",
                    expiresInDays = expiresInDays
                )
            )
        }.map { it.link }

    suspend fun linkForGroup(groupId: String, expiresInDays: Int? = null): ApiResult<ShareLinkDto> =
        apiCall {
            api.createShareLink(
                CreateShareLinkRequest(groupId = groupId, expiresInDays = expiresInDays)
            )
        }.map { it.link }

    suspend fun revoke(linkId: String): ApiResult<Unit> =
        apiCall { api.revokeShareLink(linkId) }.map { }
}
