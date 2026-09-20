package com.snaptab.app.ui.screen.settle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.remote.dto.BalanceDto
import com.snaptab.app.data.repository.SettlementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _state = MutableStateFlow(SettleUiState())
    val state: StateFlow<SettleUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = settlements.balance()) {
                is ApiResult.Success -> _state.update {
                    it.copy(loading = false, balance = result.data)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(loading = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun markReceived(userId: String, amountMinor: Long) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            when (val result = settlements.recordReceived(userId, amountMinor)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(working = false, unappliedMinor = result.data.unappliedMinor)
                    }
                    refresh()
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(working = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun markPaid(userId: String, amountMinor: Long) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            when (val result = settlements.recordPaid(userId, amountMinor)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(working = false) }
                    refresh()
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(working = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun remind(userId: String) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            when (val result = settlements.remind(userId)) {
                is ApiResult.Success -> _state.update { it.copy(working = false) }
                is ApiResult.Failure -> _state.update {
                    it.copy(working = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
