package com.snaptab.app.ui.screen.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.local.GroupEntity
import com.snaptab.app.data.remote.dto.*
import com.snaptab.app.data.repository.GroupRepository
import com.snaptab.app.data.repository.SettlementRepository
import com.snaptab.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupsUiState(
    val groups: List<GroupEntity> = emptyList(),
    val balance: BalanceDto? = null,
    val creating: Boolean = false,
    val createdGroupId: String? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false
)

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groups: GroupRepository,
    private val settlements: SettlementRepository
) : ViewModel() {

    private val extra = MutableStateFlow(GroupsUiState())

    val state: StateFlow<GroupsUiState> = combine(groups.observe(), extra) { rows, other ->
        other.copy(groups = rows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            extra.update { it.copy(refreshing = true, error = null) }
            val result = groups.refresh()
            val balance = settlements.balance()
            extra.update {
                it.copy(
                    refreshing = false,
                    balance = balance.successOrNull ?: it.balance,
                    error = result.failureOrNull?.message?.takeIf { _ ->
                        result.failureOrNull?.isOffline != true
                    },
                    offline = result.failureOrNull?.isOffline == true
                )
            }
        }
    }

    fun create(name: String, contacts: List<String>) {
        viewModelScope.launch {
            extra.update { it.copy(creating = true, error = null) }
            when (val result = groups.create(name = name, invite = contacts)) {
                is ApiResult.Success -> extra.update {
                    it.copy(creating = false, createdGroupId = result.data.group.id)
                }
                is ApiResult.Failure -> extra.update {
                    it.copy(creating = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun consumeCreated() = extra.update { it.copy(createdGroupId = null) }

    fun dismissError() = extra.update { it.copy(error = null) }
}

data class GroupDetailUiState(
    val group: GroupDto? = null,
    val balance: BalanceDto? = null,
    val activity: List<GroupActivityEntryDto> = emptyList(),
    val pendingInvites: List<PendingInviteDto> = emptyList(),
    val adding: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val offline: Boolean = false
)

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    private val groups: GroupRepository,
    private val users: UserRepository
) : ViewModel() {

    private val _state = MutableStateFlow(GroupDetailUiState())
    val state: StateFlow<GroupDetailUiState> = _state.asStateFlow()

    private var groupId: String? = null

    fun load(id: String) {
        groupId = id
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val detail = groups.detail(id)
            val activity = groups.activity(id)
            _state.update { current ->
                current.copy(
                    loading = false,
                    group = detail.successOrNull?.group ?: current.group,
                    balance = detail.successOrNull?.balance ?: current.balance,
                    pendingInvites = detail.successOrNull?.pendingInvites ?: current.pendingInvites,
                    activity = activity.successOrNull ?: current.activity,
                    error = detail.failureOrNull?.message,
                    offline = detail.failureOrNull?.isOffline == true
                )
            }
        }
    }

    /** Adds by email or phone; someone with no account becomes a placeholder who owes real money. */
    fun addMembers(contacts: List<String>) {
        val id = groupId ?: return
        viewModelScope.launch {
            _state.update { it.copy(adding = true, error = null) }
            when (val result = groups.addMembers(id, contacts)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(adding = false, group = result.data.group) }
                    load(id)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(adding = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun removeMember(userId: String) {
        val id = groupId ?: return
        viewModelScope.launch {
            when (val result = groups.removeMember(id, userId)) {
                is ApiResult.Success -> load(id)
                // The server refuses while they still owe, and says so; show it verbatim.
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun lookup(contact: String, onResult: (UserDto?) -> Unit) {
        viewModelScope.launch {
            onResult(users.lookup(contact).successOrNull?.user)
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
