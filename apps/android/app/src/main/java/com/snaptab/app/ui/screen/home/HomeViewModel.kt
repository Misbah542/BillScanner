package com.snaptab.app.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.local.ExpenseEntity
import com.snaptab.app.data.repository.*
import com.snaptab.app.data.remote.dto.BalanceDto
import com.snaptab.app.data.remote.dto.MonthlySummaryDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

/** Which slice of spending the home card is showing. */
enum class SpendLens(val wire: String, val filter: ExpenseKindFilter) {
    ALL("ALL", ExpenseKindFilter.ALL),
    PERSONAL("PERSONAL", ExpenseKindFilter.PERSONAL),
    SHARED("SHARED", ExpenseKindFilter.SHARED)
}

data class HomeUiState(
    val lens: SpendLens = SpendLens.ALL,
    val summary: MonthlySummaryDto? = null,
    val balance: BalanceDto? = null,
    val expenses: List<ExpenseEntity> = emptyList(),
    val unmatchedAlerts: Int = 0,
    val pendingAlerts: Int = 0,
    val refreshing: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false
) {
    val month: String get() = summary?.month ?: YearMonth.now().toString()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val expenses: ExpenseRepository,
    private val insights: InsightsRepository,
    private val settlements: SettlementRepository,
    private val alerts: AlertRepository,
    private val categories: CategoryRepository
) : ViewModel() {

    private val lens = MutableStateFlow(SpendLens.ALL)
    private val transient = MutableStateFlow(TransientState())

    private data class TransientState(
        val balance: BalanceDto? = null,
        val refreshing: Boolean = false,
        val error: String? = null,
        val offline: Boolean = false
    )

    /**
     * Built from the cache, so the screen paints immediately on launch and keeps working
     * offline; the network refresh then flows in through the same Room queries.
     */
    val state: StateFlow<HomeUiState> = combine(
        lens,
        lens.flatMapLatest { expenses.observe(it.filter) },
        lens.flatMapLatest { insights.observeMonthly(YearMonth.now().toString(), it.wire) },
        alerts.observeUnmatchedCount(),
        combine(alerts.observePendingCount(), transient) { pending, extra -> pending to extra }
    ) { currentLens, expenseRows, summary, unmatched, (pending, extra) ->
        HomeUiState(
            lens = currentLens,
            summary = summary,
            balance = extra.balance,
            expenses = expenseRows,
            unmatchedAlerts = unmatched,
            pendingAlerts = pending,
            refreshing = extra.refreshing,
            error = extra.error,
            offline = extra.offline
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refresh()
    }

    fun setLens(next: SpendLens) {
        lens.value = next
        // Each lens is a different server-side figure, so fetch the one being shown.
        viewModelScope.launch {
            insights.refreshMonthly(kind = next.wire)
            expenses.refresh(next.filter)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            transient.update { it.copy(refreshing = true, error = null) }

            val current = lens.value
            val expenseResult = expenses.refresh(current.filter)
            insights.refreshMonthly(kind = current.wire)
            val balanceResult = settlements.balance()
            alerts.refresh()
            categories.refresh()
            // Anything the SMS receiver queued while offline goes up now.
            alerts.syncPending()

            val failure = (expenseResult as? ApiResult.Failure) ?: (balanceResult as? ApiResult.Failure)
            transient.update {
                it.copy(
                    refreshing = false,
                    balance = balanceResult.successOrNull ?: it.balance,
                    // Offline is not an error worth a red banner when the cache has content;
                    // it is reported quietly instead.
                    error = failure?.message?.takeIf { _ -> failure.isOffline.not() },
                    offline = failure?.isOffline == true
                )
            }
        }
    }

    fun dismissError() = transient.update { it.copy(error = null) }
}
