package com.snaptab.app.data.repository

import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.local.BalanceDao
import com.snaptab.app.data.local.BalanceEntity
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettlementRepository @Inject constructor(
    private val api: SnapTabApi,
    private val dao: BalanceDao,
    private val json: Json
) {

    /**
     * The last known balance, straight from the cache, so a screen showing it has something
     * to draw on its first frame. Emits null only before the very first successful fetch.
     */
    fun observeBalance(groupId: String? = null): Flow<BalanceDto?> =
        dao.observe(scope(groupId)).map { entity -> entity?.toDto(json) }

    /** Fetches and caches. Callers observe the cache; this only corrects it. */
    suspend fun refreshBalance(groupId: String? = null): ApiResult<BalanceDto> =
        apiCall { api.balance(groupId) }.map { response ->
            dao.upsert(response.balance.toEntity(scope(groupId), json))
            response.balance
        }

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
        }.also { if (it is ApiResult.Success) invalidate(groupId) }

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
        }.also { if (it is ApiResult.Success) invalidate(groupId) }

    suspend fun reverse(settlementId: String): ApiResult<Unit> =
        apiCall { api.reverseSettlement(settlementId) }.map { }
            .also { if (it is ApiResult.Success) invalidate(null) }

    suspend fun remind(userId: String, message: String? = null): ApiResult<Long> =
        apiCall { api.remind(RemindRequest(userId, message)) }.map { it.remindedMinor }

    suspend fun history(groupId: String? = null): ApiResult<List<SettlementDto>> =
        apiCall { api.settlements(groupId) }.map { it.settlements }

    /**
     * Re-reads the balance after something changed it.
     *
     * Recording a payment and then leaving the cache alone is how you get a screen that
     * says you are still owed money you have just been paid.
     */
    private suspend fun invalidate(groupId: String?) {
        refreshBalance(groupId)
        // The overall balance moves whenever a group's does.
        if (groupId != null) refreshBalance(null)
    }

    private fun scope(groupId: String?) = groupId ?: "global"

    private fun BalanceDto.toEntity(scope: String, json: Json) = BalanceEntity(
        scope = scope,
        currency = currency,
        owedToYouMinor = owedToYouMinor,
        owedByYouMinor = owedByYouMinor,
        netMinor = netMinor,
        peopleJson = json.encodeToString(ListSerializer(PersonBalanceDto.serializer()), people),
        transfersJson = json.encodeToString(
            ListSerializer(SuggestedTransferDto.serializer()),
            suggestedTransfers
        ),
        cachedAtEpoch = System.currentTimeMillis()
    )
}

private fun BalanceEntity.toDto(json: Json) = BalanceDto(
    currency = currency,
    owedToYouMinor = owedToYouMinor,
    owedByYouMinor = owedByYouMinor,
    netMinor = netMinor,
    people = runCatching {
        json.decodeFromString(ListSerializer(PersonBalanceDto.serializer()), peopleJson)
    }.getOrDefault(emptyList()),
    suggestedTransfers = runCatching {
        json.decodeFromString(ListSerializer(SuggestedTransferDto.serializer()), transfersJson)
    }.getOrDefault(emptyList())
)
