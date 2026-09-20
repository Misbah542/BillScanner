package com.snaptab.app.ui.screen.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.data.repository.AlertRepository
import com.snaptab.app.data.repository.InsightsRepository
import com.snaptab.app.data.remote.dto.MonthlySummaryDto
import com.snaptab.app.data.remote.dto.TrendPointDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class MonthlyUiState(
    val month: String = YearMonth.now().toString(),
    val timezone: String = ZoneId.systemDefault().id,
    val summary: MonthlySummaryDto? = null,
    val trend: List<TrendPointDto> = emptyList(),
    val unmatchedAlerts: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false
) {
    /** No point offering next month before it has happened. */
    val canGoForward: Boolean get() = YearMonth.parse(month) < YearMonth.now()
}

@HiltViewModel
class MonthlyViewModel @Inject constructor(
    private val insights: InsightsRepository,
    private val alerts: AlertRepository
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now().toString())
    private val extra = MutableStateFlow(Extra())

    private data class Extra(
        val trend: List<TrendPointDto> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val offline: Boolean = false
    )

    val state: StateFlow<MonthlyUiState> = combine(
        month,
        month.flatMapLatest { insights.observeMonthly(it, "ALL") },
        alerts.observeUnmatchedCount(),
        extra
    ) { currentMonth, summary, unmatched, other ->
        MonthlyUiState(
            month = currentMonth,
            summary = summary,
            trend = other.trend,
            unmatchedAlerts = unmatched,
            loading = other.loading,
            error = other.error,
            offline = other.offline
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonthlyUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            extra.update { it.copy(loading = true, error = null) }
            val summary = insights.refreshMonthly(month.value)
            val trend = insights.trend(months = 6)
            extra.update {
                it.copy(
                    loading = false,
                    trend = trend.successOrNull ?: it.trend,
                    error = summary.failureOrNull?.message?.takeIf { _ ->
                        summary.failureOrNull?.isOffline != true
                    },
                    offline = summary.failureOrNull?.isOffline == true
                )
            }
        }
    }

    fun previousMonth() {
        month.value = YearMonth.parse(month.value).minusMonths(1).toString()
        refresh()
    }

    fun nextMonth() {
        val next = YearMonth.parse(month.value).plusMonths(1)
        if (next <= YearMonth.now()) {
            month.value = next.toString()
            refresh()
        }
    }

    fun dismissError() = extra.update { it.copy(error = null) }
}
