package com.snaptab.app.data.repository

import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.local.ExpenseDao
import com.snaptab.app.data.local.ExpenseEntity
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** `all`, `personal` or `shared` — the three columns the home screen switches between. */
enum class ExpenseKindFilter(val wire: String?, val entity: String?) {
    ALL("all", null),
    PERSONAL("personal", "PERSONAL"),
    SHARED("shared", "SHARED")
}

@Singleton
class ExpenseRepository @Inject constructor(
    private val api: SnapTabApi,
    private val dao: ExpenseDao
) {

    /** Reads from the cache, so a screen paints immediately and offline. */
    fun observe(filter: ExpenseKindFilter = ExpenseKindFilter.ALL): Flow<List<ExpenseEntity>> =
        dao.observe(filter.entity)

    fun observeOne(id: String): Flow<ExpenseEntity?> = dao.observeOne(id)

    fun observeForGroup(groupId: String): Flow<List<ExpenseEntity>> = dao.observeForGroup(groupId)

    suspend fun refresh(
        kind: ExpenseKindFilter? = ExpenseKindFilter.ALL,
        groupId: String? = null
    ): ApiResult<List<ExpenseDto>> =
        apiCall { api.expenses(kind = kind?.wire ?: "all", groupId = groupId, limit = 50) }
            .map { response ->
                dao.upsert(response.expenses.map { it.toEntity() })
                response.expenses
            }

    suspend fun load(id: String): ApiResult<ExpenseDto> =
        apiCall { api.expense(id) }.map { response ->
            dao.upsert(listOf(response.expense.toEntity()))
            response.expense
        }

    /**
     * Creates a PERSONAL expense: an amount, a merchant, a category, and nothing else
     * required. No group, no split, no receipt — the case most spending actually is.
     */
    suspend fun createPersonal(
        totalMinor: Long,
        merchantName: String?,
        categorySlug: String?,
        occurredAtIso: String? = null,
        note: String? = null,
        groupId: String? = null,
        currency: String = "INR",
        source: String = "MANUAL"
    ): ApiResult<ExpenseDto> =
        apiCall {
            api.createExpense(
                CreateExpenseRequest(
                    kind = "PERSONAL",
                    source = source,
                    merchantName = merchantName?.trim()?.ifBlank { null },
                    note = note?.trim()?.ifBlank { null },
                    occurredAt = occurredAtIso,
                    currency = currency,
                    categorySlug = categorySlug,
                    groupId = groupId,
                    itemTotalMinor = totalMinor,
                    totalMinor = totalMinor
                )
            )
        }.map { response ->
            dao.upsert(listOf(response.expense.toEntity()))
            response.expense
        }

    /** Creates a SHARED expense with a split, usually straight off a scan. */
    suspend fun createShared(request: CreateExpenseRequest): ApiResult<ExpenseDto> =
        apiCall { api.createExpense(request) }.map { response ->
            dao.upsert(listOf(response.expense.toEntity()))
            response.expense
        }

    suspend fun update(
        id: String,
        merchantName: String? = null,
        note: String? = null,
        categorySlug: String? = null,
        occurredAtIso: String? = null,
        groupId: String? = null
    ): ApiResult<ExpenseDto> =
        apiCall {
            api.updateExpense(
                id,
                UpdateExpenseRequest(
                    merchantName = merchantName,
                    note = note,
                    categorySlug = categorySlug,
                    occurredAt = occurredAtIso,
                    groupId = groupId
                )
            )
        }.map { response ->
            dao.upsert(listOf(response.expense.toEntity()))
            response.expense
        }

    suspend fun delete(id: String): ApiResult<Unit> =
        apiCall { api.deleteExpense(id) }.map { dao.delete(id) }

    /** Sets or replaces the split. Turning a personal expense shared is just this. */
    suspend fun setSplit(id: String, request: SetSplitRequest): ApiResult<ExpenseDto> =
        apiCall { api.setSplit(id, request) }.map { response ->
            dao.upsert(listOf(response.expense.toEntity()))
            response.expense
        }

    /** Drops the split and makes it personal again. */
    suspend fun removeSplit(id: String): ApiResult<ExpenseDto> =
        apiCall { api.removeSplit(id) }.map { response ->
            dao.upsert(listOf(response.expense.toEntity()))
            response.expense
        }

    suspend fun suggestCategory(
        merchant: String?,
        itemNames: List<String> = emptyList()
    ): ApiResult<List<CategorySuggestionDto>> =
        apiCall {
            api.suggestCategory(
                merchant = merchant?.trim()?.ifBlank { null },
                items = itemNames.takeIf { it.isNotEmpty() }?.joinToString("|")
            )
        }.map { it.suggestions }
}

internal fun ExpenseDto.toEntity() = ExpenseEntity(
    id = id,
    kind = kind,
    source = source,
    status = status,
    merchantName = merchantName,
    note = note,
    occurredAtEpoch = occurredAt.toEpochMillisOrNow(),
    currency = currency,
    categorySlug = category?.slug,
    categoryName = category?.name,
    categoryColorHex = category?.colorHex,
    groupId = group?.id,
    groupName = group?.name,
    paidByUserId = paidBy.id,
    paidByName = paidBy.name,
    totalMinor = totals.totalMinor,
    itemTotalMinor = totals.itemTotalMinor,
    splitMethod = splitMethod,
    yourShareMinor = viewer.yourShareMinor,
    youAreOwedMinor = viewer.youAreOwedMinor,
    youOweMinor = viewer.youOweMinor,
    isPayer = viewer.isPayer,
    shareCount = shares.size,
    hasReceipt = hasReceipt,
    cachedAtEpoch = System.currentTimeMillis()
)
