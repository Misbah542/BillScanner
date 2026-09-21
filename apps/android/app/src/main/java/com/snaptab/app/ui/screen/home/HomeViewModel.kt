package com.snaptab.app.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.local.ExpenseEntity
import com.snaptab.app.data.local.TokenStore
import com.snaptab.app.data.repository.*
import com.snaptab.app.data.remote.dto.BalanceDto
import com.snaptab.app.data.remote.dto.MonthlySummaryDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    /** True until the first summary has arrived, whatever it turns out to say. */
    val loadingSummary: Boolean = true,
    /** Likewise for the balance. False from the first frame once anything is cached. */
    val loadingBalance: Boolean = true,
    /** For the header face. Cached, so it is right on the first frame. */
    val userName: String? = null,
    val userAvatarUrl: String? = null,
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
    private val categories: CategoryRepository,
    private val users: UserRepository,
    private val tokenStore: TokenStore
) : ViewModel() {

    private val lens = MutableStateFlow(SpendLens.ALL)
    private val transient = MutableStateFlow(TransientState())

    /**
     * combine takes at most five typed flows, and this screen needs eight. Nesting them is
     * the usual answer; a named holder beats a Triple of Pairs for saying what is in it.
     */
    private data class Nested(
        val pending: Int,
        val balance: BalanceDto?,
        val profile: Pair<String?, String?>,
        val extra: TransientState
    )

    private data class TransientState(
        val refreshing: Boolean = false,
        val loadedOnce: Boolean = false,
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
        // Nested to stay within combine's five typed parameters. The balance is observed
        // from Room like everything else here, so it paints on the first frame.
        combine(
            alerts.observePendingCount(),
            settlements.observeBalance(),
            combine(tokenStore.userName, tokenStore.avatarUrl) { name, avatar -> name to avatar },
            transient
        ) { pending, balance, profile, extra -> Nested(pending, balance, profile, extra) }
    ) { currentLens, expenseRows, summary, unmatched, nested ->
        val (pending, cachedBalance, profile, extra) = nested
        HomeUiState(
            lens = currentLens,
            summary = summary,
            balance = cachedBalance,
            expenses = expenseRows,
            unmatchedAlerts = unmatched,
            pendingAlerts = pending,
            userName = profile.first,
            userAvatarUrl = profile.second,
            refreshing = extra.refreshing,
            // A null value before the first load means "not known yet"; after it, the month
            // genuinely has nothing in it. Neither card may show zero for the first case.
            loadingSummary = summary == null && !extra.loadedOnce,
            loadingBalance = cachedBalance == null && !extra.loadedOnce,
            error = extra.error,
            offline = extra.offline
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refresh()
    }

    fun setLens(next: SpendLens) {
        lens.value = next
        // The cached figure for this lens is already on screen by the time this runs — the
        // first refresh prefetched all three — so this only corrects a stale one.
        viewModelScope.launch {
            coroutineScope {
                launch { insights.refreshMonthly(kind = next.wire) }
                launch { expenses.refresh(next.filter) }
            }
        }
    }

    /**
     * One round trip's worth of latency, not six.
     *
     * These calls were awaited one after another, which put the headline spend figure
     * second in the queue and the balance third. Against a real API that is a second and
     * a half of a screen showing zero, and zero is indistinguishable from an answer — the
     * card looked like it had loaded and decided the month was empty. Nothing here depends
     * on anything else finishing, with one exception noted below, so it all goes at once.
     */
    fun refresh() {
        viewModelScope.launch {
            transient.update { it.copy(refreshing = true, error = null) }
            val current = lens.value

            // coroutineScope returns the two results that are reported, rather than
            // assigning outer vals — it is not an inline function, so a lambda cannot
            // write to them.
            val (expenseResult, balanceResult) = coroutineScope {
                val expenseJob = async { expenses.refresh(current.filter) }
                val balanceJob = async { settlements.refreshBalance() }
                // The lens on screen first, then the other two, so switching a lens later
                // reads from cache instead of waiting on the network again.
                val summaryJobs = SpendLens.entries
                    .sortedByDescending { it == current }
                    .map { lensToFetch -> async { insights.refreshMonthly(kind = lensToFetch.wire) } }
                val alertJob = async {
                    // The one ordering that matters: anything the SMS receiver queued while
                    // offline has to go up before the list is re-read, or the alerts it
                    // creates are missing from what comes back.
                    alerts.syncPending()
                    alerts.refresh()
                }
                val categoryJob = async { categories.refresh() }
                // Writes the name and avatar through to the token store, which is what the
                // header observes.
                val profileJob = async { users.me() }

                summaryJobs.forEach { it.await() }
                alertJob.await()
                categoryJob.await()
                profileJob.await()
                expenseJob.await() to balanceJob.await()
            }

            val failure = (expenseResult as? ApiResult.Failure) ?: (balanceResult as? ApiResult.Failure)
            transient.update {
                it.copy(
                    refreshing = false,
                    loadedOnce = true,
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
