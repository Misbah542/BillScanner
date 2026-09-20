package com.snaptab.app.ui.screen.inbox

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.local.AlertEntity
import com.snaptab.app.data.local.GroupEntity
import com.snaptab.app.data.local.TokenStore
import com.snaptab.app.data.repository.AlertRepository
import com.snaptab.app.data.repository.DeviceRepository
import com.snaptab.app.data.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class InboxFilter(val serverStatus: String?) {
    NEEDS_LOGGING("UNMATCHED"),
    MATCHED("LINKED"),
    IGNORED("IGNORED")
}

/** Which alert the "personal or group" sheet is open for. */
data class LogTarget(val alert: AlertEntity)

data class InboxUiState(
    val filter: InboxFilter = InboxFilter.NEEDS_LOGGING,
    val alerts: List<AlertEntity> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val groups: List<GroupEntity> = emptyList(),
    val pendingUploads: Int = 0,
    val smsEnabled: Boolean = false,
    val askedForSmsBefore: Boolean = false,
    val logTarget: LogTarget? = null,
    val working: Boolean = false,
    val refreshing: Boolean = false,
    val justLoggedExpenseId: String? = null,
    val message: String? = null,
    val error: String? = null,
    val offline: Boolean = false
) {
    val needsPermission: Boolean get() = !smsEnabled
}

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val alerts: AlertRepository,
    private val groups: GroupRepository,
    private val devices: DeviceRepository,
    private val tokenStore: TokenStore
) : ViewModel() {

    val smsPermissions = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)

    private val filter = MutableStateFlow(InboxFilter.NEEDS_LOGGING)
    private val transient = MutableStateFlow(Transient())

    private data class Transient(
        val counts: Map<String, Int> = emptyMap(),
        val logTarget: LogTarget? = null,
        val working: Boolean = false,
        val refreshing: Boolean = false,
        val justLoggedExpenseId: String? = null,
        val message: String? = null,
        val error: String? = null,
        val offline: Boolean = false
    )

    val state: StateFlow<InboxUiState> = combine(
        filter,
        filter.flatMapLatest { alerts.observe(it.serverStatus) },
        groups.observe(),
        alerts.observePendingCount(),
        combine(tokenStore.smsEnabled, tokenStore.askedForSms, transient) { enabled, asked, extra ->
            Triple(enabled, asked, extra)
        }
    ) { currentFilter, rows, groupRows, pending, (enabled, asked, extra) ->
        InboxUiState(
            filter = currentFilter,
            alerts = rows,
            counts = extra.counts,
            groups = groupRows,
            pendingUploads = pending,
            smsEnabled = enabled,
            askedForSmsBefore = asked,
            logTarget = extra.logTarget,
            working = extra.working,
            refreshing = extra.refreshing,
            justLoggedExpenseId = extra.justLoggedExpenseId,
            message = extra.message,
            error = extra.error,
            offline = extra.offline
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    init {
        refresh()
        viewModelScope.launch { groups.refresh() }
    }

    fun setFilter(next: InboxFilter) {
        filter.value = next
        viewModelScope.launch { alerts.refresh(next.serverStatus) }
    }

    fun refresh() {
        viewModelScope.launch {
            transient.update { it.copy(refreshing = true, error = null) }
            alerts.syncPending()
            val result = alerts.refresh(filter.value.serverStatus)
            transient.update { current ->
                current.copy(
                    refreshing = false,
                    counts = result.successOrNull?.let { _ -> current.counts }.orEmpty(),
                    error = result.failureOrNull?.message?.takeIf { _ ->
                        result.failureOrNull?.isOffline != true
                    },
                    offline = result.failureOrNull?.isOffline == true
                )
            }
        }
    }

    /** Records that the user granted (or refused) SMS reading on this device. */
    fun onSmsPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            tokenStore.markAskedForSms()
            devices.setSmsEnabled(granted)
            if (granted) refresh()
        }
    }

    fun setSmsEnabled(enabled: Boolean) {
        viewModelScope.launch { devices.setSmsEnabled(enabled) }
    }

    // ------------------------------------------------- the personal-or-group choice ---

    fun openLogSheet(alert: AlertEntity) =
        transient.update { it.copy(logTarget = LogTarget(alert), error = null) }

    fun dismissLogSheet() = transient.update { it.copy(logTarget = null) }

    /** All yours: no group, no split, straight into this month's spend. */
    fun logAsPersonal(alert: AlertEntity) {
        viewModelScope.launch {
            transient.update { it.copy(working = true, error = null) }
            handle(alerts.logPersonal(alert.id, alert.suggestedCategorySlug))
        }
    }

    /**
     * On a group the user already has. The server splits it equally across that group's
     * current members, so this stays one action.
     */
    fun logToGroup(alert: AlertEntity, groupId: String) {
        viewModelScope.launch {
            transient.update { it.copy(working = true, error = null) }
            handle(alerts.logToGroup(alert.id, groupId, categorySlug = alert.suggestedCategorySlug))
        }
    }

    fun ignore(alert: AlertEntity) {
        viewModelScope.launch {
            transient.update { it.copy(working = true, error = null) }
            when (val result = alerts.ignore(alert.id)) {
                is ApiResult.Success -> transient.update {
                    it.copy(working = false, logTarget = null)
                }
                is ApiResult.Failure -> transient.update {
                    it.copy(working = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun consumeLoggedExpense() = transient.update { it.copy(justLoggedExpenseId = null) }

    fun dismissError() = transient.update { it.copy(error = null, message = null) }

    private fun handle(result: ApiResult<com.snaptab.app.data.remote.dto.ExpenseDto>) {
        when (result) {
            is ApiResult.Success -> transient.update {
                it.copy(
                    working = false,
                    logTarget = null,
                    justLoggedExpenseId = result.data.id
                )
            }
            is ApiResult.Failure -> transient.update {
                it.copy(working = false, error = result.message, offline = result.isOffline)
            }
        }
    }
}
