package com.snaptab.app.ui.screen.profile

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.local.TokenStore
import com.snaptab.app.data.remote.dto.BalanceDto
import com.snaptab.app.data.remote.dto.MonthlySummaryDto
import com.snaptab.app.data.remote.dto.UserDto
import com.snaptab.app.data.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

data class ProfileUiState(
    val user: UserDto? = null,
    val summary: MonthlySummaryDto? = null,
    val balance: BalanceDto? = null,
    val expenseCount: Int = 0,
    val smsEnabled: Boolean = false,
    val keepAlertBodies: Boolean = false,
    val editingName: Boolean = false,
    val nameDraft: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val signedOut: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false
)

/**
 * The profile screen's state: who you are, how you sign in, what SnapTab is allowed to
 * read, and the two destructive actions.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val users: UserRepository,
    private val auth: AuthRepository,
    private val insights: InsightsRepository,
    private val settlements: SettlementRepository,
    private val devices: DeviceRepository,
    private val expenses: ExpenseRepository,
    private val tokenStore: TokenStore
) : ViewModel() {

    val smsPermissions = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(tokenStore.smsEnabled, tokenStore.keepAlertBodies) { sms, bodies -> sms to bodies }
                .collect { (sms, bodies) ->
                    _state.update { it.copy(smsEnabled = sms, keepAlertBodies = bodies) }
                }
        }
        viewModelScope.launch {
            expenses.observe(ExpenseKindFilter.ALL).collect { rows ->
                _state.update { it.copy(expenseCount = rows.size) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            // Three independent calls; awaiting them in turn tripled the time the screen
            // spent showing nothing.
            val (me, summary, balance) = coroutineScope {
                val meJob = async { users.me() }
                val summaryJob = async { insights.refreshMonthly(YearMonth.now().toString()) }
                val balanceJob = async { settlements.refreshBalance() }
                Triple(meJob.await(), summaryJob.await(), balanceJob.await())
            }

            _state.update { current ->
                current.copy(
                    loading = false,
                    user = me.successOrNull ?: current.user,
                    nameDraft = me.successOrNull?.name ?: current.nameDraft,
                    // The server is authoritative on this setting; the local copy is a mirror
                    // so the SMS receiver can check it without a network call.
                    keepAlertBodies = me.successOrNull?.keepAlertBodies ?: current.keepAlertBodies,
                    summary = summary.successOrNull ?: current.summary,
                    balance = balance.successOrNull ?: current.balance,
                    error = (me as? ApiResult.Failure)?.message?.takeIf { _ ->
                        (me as ApiResult.Failure).isOffline.not()
                    },
                    offline = (me as? ApiResult.Failure)?.isOffline == true
                )
            }
            me.successOrNull?.let { tokenStore.setKeepAlertBodies(it.keepAlertBodies) }
        }
    }

    fun startEditingName() = _state.update {
        it.copy(editingName = true, nameDraft = it.user?.name.orEmpty())
    }

    fun setNameDraft(value: String) = _state.update { it.copy(nameDraft = value) }

    fun cancelEditingName() = _state.update { it.copy(editingName = false) }

    fun saveName() {
        val name = _state.value.nameDraft.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(error = "Your name cannot be empty.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            when (val result = users.updateProfile(name = name)) {
                is ApiResult.Success -> _state.update {
                    it.copy(saving = false, editingName = false, user = result.data)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(saving = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    /** Called after the runtime permission dialog resolves. */
    fun onSmsPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            tokenStore.markAskedForSms()
            devices.setSmsEnabled(granted)
        }
    }

    /** Switching reading off locally is immediate; the server is told so it stops expecting alerts. */
    fun setSmsEnabled(enabled: Boolean) {
        viewModelScope.launch { devices.setSmsEnabled(enabled) }
    }

    /**
     * Turning "keep the message text" off also tells the server to forget what it already
     * kept — the setting means what it says, retroactively.
     */
    fun setKeepAlertBodies(keep: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            when (val result = users.setKeepAlertBodies(keep)) {
                is ApiResult.Success -> _state.update {
                    it.copy(saving = false, keepAlertBodies = result.data.keepAlertBodies)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(saving = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            auth.signOut()
            _state.update { it.copy(saving = false, signedOut = true) }
        }
    }

    fun signOutEverywhere() {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            auth.signOutEverywhere()
            _state.update { it.copy(saving = false, signedOut = true) }
        }
    }

    fun askToDelete() = _state.update { it.copy(showDeleteConfirm = true) }

    fun cancelDelete() = _state.update { it.copy(showDeleteConfirm = false) }

    fun confirmDelete() {
        viewModelScope.launch {
            _state.update { it.copy(saving = true, showDeleteConfirm = false) }
            when (val result = auth.deleteAccount()) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, signedOut = true) }
                is ApiResult.Failure -> _state.update {
                    it.copy(saving = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
