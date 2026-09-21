package com.snaptab.app.ui.screen.expense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.local.GroupEntity
import com.snaptab.app.data.remote.dto.ExpenseDto
import com.snaptab.app.data.remote.dto.ShareLinkDto
import com.snaptab.app.data.repository.ExpenseRepository
import com.snaptab.app.data.repository.GroupRepository
import com.snaptab.app.data.repository.SettlementRepository
import com.snaptab.app.data.repository.ShareRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExpenseDetailUiState(
    val expense: ExpenseDto? = null,
    val groups: List<GroupEntity> = emptyList(),
    val shareLink: ShareLinkDto? = null,
    val loading: Boolean = true,
    val working: Boolean = false,
    val deleted: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false
) {
    val isPersonal: Boolean get() = expense?.kind == "PERSONAL"
    val canEdit: Boolean get() = expense?.viewer?.isPayer == true
}

@HiltViewModel
class ExpenseViewModel @Inject constructor(
    private val expenses: ExpenseRepository,
    private val groups: GroupRepository,
    private val share: ShareRepository,
    private val settlements: SettlementRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ExpenseDetailUiState())
    val state: StateFlow<ExpenseDetailUiState> = _state.asStateFlow()

    private var expenseId: String? = null

    fun load(id: String) {
        if (expenseId == id && _state.value.expense != null) return
        expenseId = id
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = expenses.load(id)
            groups.refresh()
            _state.update { current ->
                current.copy(
                    loading = false,
                    expense = result.successOrNull ?: current.expense,
                    error = result.failureOrNull?.message,
                    offline = result.failureOrNull?.isOffline == true
                )
            }
        }
        viewModelScope.launch {
            groups.observe().collect { rows -> _state.update { it.copy(groups = rows) } }
        }
    }

    fun setCategory(slug: String) {
        val id = expenseId ?: return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            handle(expenses.update(id, categorySlug = slug))
        }
    }

    /** Turns a personal expense into a shared one, on a group that already exists. */
    fun moveToGroup(groupId: String?) {
        val id = expenseId ?: return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            handle(expenses.update(id, groupId = groupId))
        }
    }

    /** Drops the split and makes it a personal expense again. */
    fun makePersonal() {
        val id = expenseId ?: return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            handle(expenses.removeSplit(id))
        }
    }

    fun createShareLink(includeReceipt: Boolean = false) {
        val id = expenseId ?: return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            when (val result = share.linkForExpense(id, includeReceipt)) {
                is ApiResult.Success -> _state.update {
                    it.copy(working = false, shareLink = result.data)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(working = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun remind(userId: String) {
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            when (val result = settlements.remind(userId)) {
                is ApiResult.Success -> _state.update { it.copy(working = false) }
                is ApiResult.Failure -> _state.update {
                    it.copy(working = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    /** Marks one person's share as paid. */
    fun markShareSettled(userId: String, amountMinor: Long) {
        val expense = _state.value.expense ?: return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            val result = settlements.recordReceived(
                fromUserId = userId,
                amountMinor = amountMinor,
                groupId = expense.group?.id
            )
            when (result) {
                is ApiResult.Success -> {
                    _state.update { it.copy(working = false) }
                    expenseId?.let { load(it) }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(working = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun delete() {
        val id = expenseId ?: return
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            when (val result = expenses.delete(id)) {
                is ApiResult.Success -> _state.update { it.copy(working = false, deleted = true) }
                is ApiResult.Failure -> _state.update {
                    it.copy(working = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun dismissShareLink() = _state.update { it.copy(shareLink = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun handle(result: ApiResult<ExpenseDto>) {
        when (result) {
            is ApiResult.Success -> _state.update { it.copy(working = false, expense = result.data) }
            is ApiResult.Failure -> _state.update {
                it.copy(working = false, error = result.message, offline = result.isOffline)
            }
        }
    }
}
