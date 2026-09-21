package com.snaptab.app.data.repository

import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.local.AlertDao
import com.snaptab.app.data.local.AlertEntity
import com.snaptab.app.data.local.PendingAlertDao
import com.snaptab.app.data.local.PendingAlertEntity
import com.snaptab.app.data.local.TokenStore
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bank-alert inbox and the queue behind it.
 *
 * The device parses; this uploads only the extracted fields. The message body goes up
 * solely when the user has opted in — the queue column is null otherwise, so there is
 * nothing to leak even if the upload is retried weeks later.
 */
@Singleton
class AlertRepository @Inject constructor(
    private val api: SnapTabApi,
    private val alertDao: AlertDao,
    private val pendingDao: PendingAlertDao,
    private val tokenStore: TokenStore
) {

    fun observe(status: String? = null): Flow<List<AlertEntity>> = alertDao.observe(status)

    fun observeUnmatchedCount(): Flow<Int> = alertDao.observeUnmatchedCount()

    fun observePendingCount(): Flow<Int> = pendingDao.observeCount()

    suspend fun refresh(status: String? = null): ApiResult<List<BankAlertDto>> =
        apiCall { api.alerts(status = status) }
            .map { response ->
                alertDao.upsert(response.alerts.map { it.toEntity() })
                response.alerts
            }

    /**
     * Uploads everything the receiver queued. Idempotent by fingerprint on both sides,
     * so a partially-failed batch can be retried without duplicating anything.
     */
    suspend fun syncPending(): ApiResult<Int> {
        pendingDao.dropExhausted()
        val queued = pendingDao.take()
        if (queued.isEmpty()) return ApiResult.Success(0)

        val keepBodies = tokenStore.shouldSendAlertBodies()
        val payload = queued.map { it.toDto(includeBody = keepBodies) }

        return when (val result = apiCall { api.ingestAlerts(IngestAlertsRequest(payload)) }) {
            is ApiResult.Success -> {
                pendingDao.remove(queued.map { it.fingerprint })
                refresh()
                ApiResult.Success(result.data.created)
            }
            is ApiResult.Failure -> {
                // Only count an attempt against a rejection the server will keep rejecting;
                // being offline is not the payload's fault.
                if (!result.isOffline) pendingDao.bumpAttempts(queued.map { it.fingerprint })
                result
            }
        }
    }

    /** Puts the queue in front of the server without waiting for the next worker run. */
    suspend fun enqueue(alert: PendingAlertEntity): Boolean = pendingDao.enqueue(alert) != -1L

    /**
     * The one-tap path: a lone debit becomes a personal expense. No bill, no items, no
     * split, straight into this month's spend.
     */
    suspend fun logPersonal(
        alertId: String,
        categorySlug: String? = null,
        note: String? = null
    ): ApiResult<ExpenseDto> =
        apiCall {
            api.alertToExpense(
                alertId,
                AlertToExpenseRequest(kind = "PERSONAL", categorySlug = categorySlug, note = note)
            )
        }.map { response ->
            alertDao.setStatus(alertId, "LINKED")
            response.expense
        }

    /**
     * The other tap: put it on a group the user already has. The server splits it
     * equally across that group's current members unless told otherwise, so it is still
     * one action.
     */
    suspend fun logToGroup(
        alertId: String,
        groupId: String,
        splitMethod: String = "EQUAL",
        participants: List<ParticipantRequest>? = null,
        categorySlug: String? = null
    ): ApiResult<ExpenseDto> =
        apiCall {
            api.alertToExpense(
                alertId,
                AlertToExpenseRequest(
                    kind = "SHARED",
                    groupId = groupId,
                    splitMethod = splitMethod,
                    participants = participants,
                    categorySlug = categorySlug
                )
            )
        }.map { response ->
            alertDao.setStatus(alertId, "LINKED")
            response.expense
        }

    /** Splitting with people who are in no group at all. */
    suspend fun logWithPeople(
        alertId: String,
        participants: List<ParticipantRequest>,
        splitMethod: String = "EQUAL"
    ): ApiResult<ExpenseDto> =
        apiCall {
            api.alertToExpense(
                alertId,
                AlertToExpenseRequest(
                    kind = "SHARED",
                    splitMethod = splitMethod,
                    participants = participants
                )
            )
        }.map { response ->
            alertDao.setStatus(alertId, "LINKED")
            response.expense
        }

    suspend fun ignore(alertId: String): ApiResult<Unit> =
        apiCall { api.ignoreAlert(alertId) }.map { alertDao.setStatus(alertId, "IGNORED") }

    suspend fun link(alertId: String, expenseId: String): ApiResult<LinkAlertResponse> =
        apiCall { api.linkAlert(alertId, LinkAlertRequest(expenseId)) }
            .map { response ->
                alertDao.setStatus(alertId, "LINKED")
                response
            }

    suspend fun suggestions(alertId: String): ApiResult<AlertSuggestionsResponse> =
        apiCall { api.alertSuggestions(alertId) }

    /**
     * Used by the notification's buttons, which only know the fingerprint the receiver
     * computed — the server-side id does not exist until the alert has been uploaded.
     * So: flush the queue, then find the alert the server now holds.
     */
    suspend fun logPersonalByFingerprint(fingerprint: String): ApiResult<ExpenseDto> {
        syncPending()
        val alertId = findServerId(fingerprint)
            ?: return ApiResult.Failure(
                code = "ALERT_NOT_SYNCED",
                message = "That alert has not reached the server yet."
            )
        return logPersonal(alertId)
    }

    suspend fun ignoreByFingerprint(fingerprint: String): ApiResult<Unit> {
        syncPending()
        val alertId = findServerId(fingerprint)
            ?: return ApiResult.Failure(
                code = "ALERT_NOT_SYNCED",
                message = "That alert has not reached the server yet."
            )
        return ignore(alertId)
    }

    /**
     * Matches a locally-parsed alert to the server's row. The reference number is exact
     * when the bank gave one; otherwise amount, mask and day, the same fields the
     * fingerprint is built from.
     */
    private suspend fun findServerId(fingerprint: String): String? {
        val response = apiCall { api.alerts(limit = 60) }.successOrNull ?: return null
        // The server stores the fingerprint but does not return it, so match on the parts.
        val pending = pendingDao.take(limit = 100).firstOrNull { it.fingerprint == fingerprint }
        return if (pending != null) {
            response.alerts.firstOrNull { candidate ->
                candidate.amountMinor == pending.amountMinor &&
                    candidate.direction == pending.direction &&
                    (pending.referenceNumber == null || candidate.referenceNumber == pending.referenceNumber) &&
                    (pending.accountMask == null || candidate.accountMask == pending.accountMask)
            }?.id
        } else {
            // Already uploaded and cleared from the queue: fall back to the newest
            // unmatched alert, which is what the notification was about.
            response.alerts.firstOrNull { it.status == "UNMATCHED" }?.id
        }
    }

    private fun BankAlertDto.toEntity() = AlertEntity(
        id = id,
        direction = direction,
        status = status,
        amountMinor = amountMinor,
        currency = currency,
        merchantRaw = merchantRaw,
        accountMask = accountMask,
        accountKind = accountKind,
        bankName = bankName,
        referenceNumber = referenceNumber,
        confidence = confidence,
        occurredAtEpoch = occurredAt.toEpochMillisOrNow(),
        expenseId = expenseId,
        suggestedCategorySlug = suggestedCategory?.slug,
        suggestedCategoryName = suggestedCategory?.name,
        cachedAtEpoch = System.currentTimeMillis()
    )

    private fun PendingAlertEntity.toDto(includeBody: Boolean) = IngestAlertDto(
        direction = direction,
        amountMinor = amountMinor,
        currency = currency,
        merchantRaw = merchantRaw,
        accountMask = accountMask,
        accountKind = accountKind,
        bankId = bankId,
        bankName = bankName,
        referenceNumber = referenceNumber,
        channel = "SMS",
        occurredAt = DateTimeFormatter.ISO_INSTANT.format(
            Instant.ofEpochMilli(occurredAtEpoch).atOffset(ZoneOffset.UTC)
        ),
        fingerprint = fingerprint,
        confidence = confidence,
        rawBody = if (includeBody) rawBody else null
    )
}

/** Tolerates a missing or malformed timestamp rather than throwing in a mapper. */
internal fun String?.toEpochMillisOrNow(): Long =
    this?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?: System.currentTimeMillis()
