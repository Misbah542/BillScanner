package com.snaptab.app.ui.screen.settle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.remote.dto.BalanceDto
import com.snaptab.app.data.repository.SettlementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettleUiState(
    val balance: BalanceDto? = null,
    val loading: Boolean = true,
    val working: Boolean = false,
    /** Set when a payment covered more than was owed, which is worth saying out loud. */
    val unappliedMinor: Long = 0,
    val error: String? = null,
    val offline: Boolean = false
)

@HiltViewModel
class SettleViewModel @Inject constructor(
    private val settlements: SettlementRepository
) : ViewModel() {

    private val transient = MutableStateFlow(SettleUiState())

    /**
     * The balance comes from the cache, so this screen has its figures on the first frame
     * and the refresh below only corrects them. It used to await the network before showing
     * anything, which meant a visible spell of zeroes on every visit.
     */
    val state: StateFlow<SettleUiState> = combine(
        settlements.observeBalance(),
        transient
    ) { cached, extra ->
        extra.copy(
            balance = cached,
            // Only loading while there is genuinely nothing to show.
            loading = cached == null && extra.loading
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettleUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            transient.update { it.copy(error = null) }
            when (val result = settlements.refreshBalance()) {
                is ApiResult.Success -> transient.update { it.copy(loading = false) }
                is ApiResult.Failure -> transient.update {
                    it.copy(loading = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun markReceived(userId: String, amountMinor: Long) {
        viewModelScope.launch {
            transient.update { it.copy(working = true, error = null) }
            when (val result = settlements.recordReceived(userId, amountMinor)) {
                is ApiResult.Success -> {
                    // No refresh() here: recording a settlement re-reads the balance in the
                    // repository, so the cache — and therefore this screen — is already current.
                    transient.update {
                        it.copy(working = false, unappliedMinor = result.data.unappliedMinor)
                    }
                }
                is ApiResult.Failure -> transient.update {
                    it.copy(working = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun markPaid(userId: String, amountMinor: Long) {
        viewModelScope.launch {
            transient.update { it.copy(working = true, error = null) }
            when (val result = settlements.recordPaid(userId, amountMinor)) {
                is ApiResult.Success -> transient.update { it.copy(working = false) }
                is ApiResult.Failure -> transient.update {
                    it.copy(working = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun remind(userId: String) {
        viewModelScope.launch {
            transient.update { it.copy(working = true, error = null) }
            when (val result = settlements.remind(userId)) {
                is ApiResult.Success -> transient.update { it.copy(working = false) }
                is ApiResult.Failure -> transient.update {
                    it.copy(working = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun dismissError() = transient.update { it.copy(error = null) }
}
