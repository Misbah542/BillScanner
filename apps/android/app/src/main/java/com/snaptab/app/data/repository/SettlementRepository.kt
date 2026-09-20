package com.snaptab.app.data.repository

import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettlementRepository @Inject constructor(private val api: SnapTabApi) {

    suspend fun balance(groupId: String? = null): ApiResult<BalanceDto> =
        apiCall { api.balance(groupId) }.map { it.balance }

    /** They paid you. Only one side is ever named; the other is always the signed-in user. */
    suspend fun recordReceived(
        fromUserId: String,
        amountMinor: Long,
        method: String = "UPI",
        groupId: String? = null,
        note: String? = null
    ): ApiResult<CreateSettlementResponse> =
        apiCall {
            api.createSettlement(
                CreateSettlementRequest(
                    fromUserId = fromUserId,
                    amountMinor = amountMinor,
                    method = method,
                    groupId = groupId,
                    note = note
                )
            )
        }

    /** You paid them. */
    suspend fun recordPaid(
        toUserId: String,
        amountMinor: Long,
        method: String = "UPI",
        groupId: String? = null,
        note: String? = null
    ): ApiResult<CreateSettlementResponse> =
        apiCall {
            api.createSettlement(
                CreateSettlementRequest(
                    toUserId = toUserId,
                    amountMinor = amountMinor,
                    method = method,
                    groupId = groupId,
                    note = note
                )
            )
        }

    suspend fun reverse(settlementId: String): ApiResult<Unit> =
        apiCall { api.reverseSettlement(settlementId) }.map { }

    suspend fun remind(userId: String, message: String? = null): ApiResult<Long> =
        apiCall { api.remind(RemindRequest(userId, message)) }.map { it.remindedMinor }

    suspend fun history(groupId: String? = null): ApiResult<List<SettlementDto>> =
        apiCall { api.settlements(groupId) }.map { it.settlements }
}
