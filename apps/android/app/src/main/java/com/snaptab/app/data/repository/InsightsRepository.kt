package com.snaptab.app.data.repository

import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.map
import com.snaptab.app.data.local.MonthlySummaryDao
import com.snaptab.app.data.local.MonthlySummaryEntity
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.apiCall
import com.snaptab.app.data.remote.dto.CategorySpendDto
import com.snaptab.app.data.remote.dto.MonthlySummaryDto
import com.snaptab.app.data.remote.dto.TrendPointDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "What did I spend this month."
 *
 * The number the app leads with is personal expenses in full plus the user's own share
 * of shared ones. What they fronted for other people is reported separately, as a loan
 * — folding it into spending would make every round of drinks look like a spree.
 */
@Singleton
class InsightsRepository @Inject constructor(
    private val api: SnapTabApi,
    private val dao: MonthlySummaryDao,
    private val json: Json
) {

    fun observeMonthly(month: String, kind: String = "ALL"): Flow<MonthlySummaryDto?> =
        dao.observe(key(month, kind)).map { entity -> entity?.toDto(json) }

    suspend fun refreshMonthly(month: String? = null, kind: String = "ALL"): ApiResult<MonthlySummaryDto> =
        apiCall { api.monthlySummary(month, kind) }.map { response ->
            dao.upsert(response.summary.toEntity(kind, json))
            response.summary
        }

    suspend fun trend(months: Int = 6, kind: String = "ALL"): ApiResult<List<TrendPointDto>> =
        apiCall { api.trend(months, kind) }.map { it.points }

    suspend fun topMerchants(month: String? = null) =
        apiCall { api.topMerchants(month) }.map { it.merchants }

    private fun key(month: String, kind: String) = "$month:$kind"

    private fun MonthlySummaryDto.toEntity(kind: String, json: Json) = MonthlySummaryEntity(
        monthAndKind = key(month, kind),
        month = month,
        kind = kind,
        currency = currency,
        personalMinor = personalMinor,
        sharedShareMinor = sharedShareMinor,
        spentMinor = spentMinor,
        paidOutMinor = paidOutMinor,
        owedToYouMinor = owedToYouMinor,
        owedByYouMinor = owedByYouMinor,
        personalCount = personalCount,
        sharedCount = sharedCount,
        previousSpentMinor = previousSpentMinor,
        byCategoryJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(CategorySpendDto.serializer()),
            byCategory
        ),
        cachedAtEpoch = System.currentTimeMillis()
    )
}

private fun MonthlySummaryEntity.toDto(json: Json) = MonthlySummaryDto(
    month = month,
    currency = currency,
    personalMinor = personalMinor,
    sharedShareMinor = sharedShareMinor,
    spentMinor = spentMinor,
    paidOutMinor = paidOutMinor,
    owedToYouMinor = owedToYouMinor,
    owedByYouMinor = owedByYouMinor,
    expenseCount = personalCount + sharedCount,
    personalCount = personalCount,
    sharedCount = sharedCount,
    byCategory = runCatching {
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(CategorySpendDto.serializer()),
            byCategoryJson
        )
    }.getOrDefault(emptyList()),
    previousSpentMinor = previousSpentMinor
)
