package com.snaptab.app.ui.screen.expense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.Money
import com.snaptab.app.data.remote.dto.*
import com.snaptab.app.data.repository.ExpenseRepository
import com.snaptab.app.data.repository.GroupRepository
import com.snaptab.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SplitMethod(val wire: String) {
    EQUAL("EQUAL"),
    EXACT("EXACT"),
    PERCENT("PERCENT"),
    SHARES("SHARES")
}

/** One row of the editor. `input` is what the user typed, in the method's own units. */
data class SplitRow(
    val userId: String,
    val name: String,
    val subtitle: String,
    val included: Boolean = true,
    /** Minor units for EXACT, whole percent for PERCENT, a count for SHARES. */
    val input: String = "",
    val pending: Boolean = false
)

data class SplitUiState(
    val expense: ExpenseDto? = null,
    val method: SplitMethod = SplitMethod.EQUAL,
    val rows: List<SplitRow> = emptyList(),
    val computed: Map<String, Long> = emptyMap(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false
) {
    val totalMinor: Long get() = expense?.totals?.totalMinor ?: 0
    val assignedMinor: Long get() = computed.values.sum()
    val remainingMinor: Long get() = totalMinor - assignedMinor

    /**
     * Whether the split adds up. EQUAL and SHARES always do, because the engine
     * apportions; EXACT and PERCENT are the user's arithmetic and can be wrong.
     */
    val balances: Boolean
        get() = when (method) {
            SplitMethod.EXACT -> remainingMinor == 0L
            SplitMethod.PERCENT -> percentTotal == 100
            else -> rows.any { it.included }
        }

    val percentTotal: Int
        get() = rows.filter { it.included }.sumOf { it.input.toIntOrNull() ?: 0 }

    val canSave: Boolean get() = balances && !saving && rows.count { it.included } > 0
}

/**
 * The split editor: equal, unequal (exact amounts), percentage, or shares.
 *
 * Previews are computed locally with the same largest-remainder arithmetic the server
 * uses, so what the user sees before saving is what gets saved, to the paisa. The old app
 * had no splitting at all.
 */
@HiltViewModel
class SplitViewModel @Inject constructor(
    private val expenses: ExpenseRepository,
    private val groups: GroupRepository,
    private val users: UserRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SplitUiState())
    val state: StateFlow<SplitUiState> = _state.asStateFlow()

    private var expenseId: String? = null

    fun load(id: String) {
        if (expenseId == id && _state.value.expense != null) return
        expenseId = id
        viewModelScope.launch {
            val result = expenses.load(id)
            val expense = result.successOrNull ?: return@launch _state.update {
                it.copy(error = result.failureOrNull?.message)
            }

            // Start from whoever is already on it; failing that, the group; failing that,
            // just the payer plus whoever the user has split with recently.
            val existing = expense.shares.map { share ->
                SplitRow(
                    userId = share.userId,
                    name = share.user?.name ?: "Someone",
                    subtitle = share.user?.email ?: share.user?.phone.orEmpty(),
                    pending = share.user?.pending == true
                )
            }
            val rows = existing.ifEmpty {
                val fromGroup = expense.group?.id
                    ?.let { groupId -> groups.detail(groupId).successOrNull?.group?.members }
                    ?.map { member ->
                        SplitRow(
                            userId = member.user.id,
                            name = member.user.name ?: "Someone",
                            subtitle = member.user.email ?: member.user.phone.orEmpty(),
                            pending = member.user.pending
                        )
                    }
                fromGroup ?: listOf(
                    SplitRow(
                        userId = expense.paidBy.id,
                        name = expense.paidBy.name ?: "You",
                        subtitle = "paid the bill"
                    )
                )
            }

            val method = expense.splitMethod
                ?.let { wire -> SplitMethod.entries.firstOrNull { it.wire == wire } }
                ?: SplitMethod.EQUAL

            _state.update { it.copy(expense = expense, method = method, rows = rows) }
            seedInputs()
            recompute()
        }
    }

    fun setMethod(method: SplitMethod) {
        _state.update { it.copy(method = method, error = null) }
        seedInputs()
        recompute()
    }

    fun toggleIncluded(userId: String) {
        _state.update { current ->
            current.copy(
                rows = current.rows.map { row ->
                    if (row.userId == userId) row.copy(included = !row.included) else row
                }
            )
        }
        seedInputs()
        recompute()
    }

    fun setInput(userId: String, value: String) {
        val cleaned = when (_state.value.method) {
            SplitMethod.EXACT -> value.filter { it.isDigit() || it == '.' }
            else -> value.filter(Char::isDigit).take(4)
        }
        _state.update { current ->
            current.copy(
                rows = current.rows.map { row ->
                    if (row.userId == userId) row.copy(input = cleaned) else row
                },
                error = null
            )
        }
        recompute()
    }

    /** Adds somebody by email or phone; the server creates a placeholder if they are new. */
    fun addPerson(contact: String) {
        viewModelScope.launch {
            when (val result = users.lookup(contact)) {
                is ApiResult.Success -> {
                    val found = result.data.user
                    val row = SplitRow(
                        userId = found?.id ?: PENDING_PREFIX + contact.trim(),
                        name = found?.name ?: contact.trim(),
                        subtitle = contact.trim(),
                        pending = found == null
                    )
                    if (_state.value.rows.none { it.userId == row.userId }) {
                        _state.update { it.copy(rows = it.rows + row) }
                        seedInputs()
                        recompute()
                    }
                }
                is ApiResult.Failure -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun removePerson(userId: String) {
        _state.update { current -> current.copy(rows = current.rows.filterNot { it.userId == userId }) }
        seedInputs()
        recompute()
    }

    fun save() {
        val id = expenseId ?: return
        val current = _state.value
        if (!current.canSave) return

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }

            val participants = current.rows.filter { it.included }.map { row ->
                val value = when (current.method) {
                    SplitMethod.EQUAL -> null
                    SplitMethod.EXACT -> Money.parseOrNull(row.input) ?: 0L
                    // The API takes basis points, so 25% is 2500.
                    SplitMethod.PERCENT -> (row.input.toLongOrNull() ?: 0L) * 100
                    SplitMethod.SHARES -> row.input.toLongOrNull() ?: 0L
                }
                if (row.userId.startsWith(PENDING_PREFIX)) {
                    val contact = row.userId.removePrefix(PENDING_PREFIX)
                    if (contact.contains('@')) {
                        ParticipantRequest(email = contact, displayName = row.name, value = value)
                    } else {
                        ParticipantRequest(phone = contact, displayName = row.name, value = value)
                    }
                } else {
                    ParticipantRequest(userId = row.userId, value = value)
                }
            }

            val result = expenses.setSplit(
                id,
                SetSplitRequest(method = current.method.wire, participants = participants)
            )
            when (result) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, saved = true) }
                is ApiResult.Failure -> _state.update {
                    // The server's message carries the arithmetic: "the percentages add up
                    // to 90% instead of 100%". Show it verbatim.
                    it.copy(saving = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Fills the inputs with a sensible starting point whenever the method changes. */
    private fun seedInputs() {
        val current = _state.value
        val included = current.rows.filter { it.included }
        if (included.isEmpty()) return

        val seeded = when (current.method) {
            SplitMethod.EQUAL -> current.rows.map { it.copy(input = "") }
            SplitMethod.EXACT -> {
                val amounts = Money.apportion(
                    current.totalMinor,
                    included.map { 1L },
                    preferIndex = payerIndex(included)
                )
                var cursor = 0
                current.rows.map { row ->
                    if (row.included) row.copy(input = Money.toEditable(amounts[cursor++])) else row.copy(input = "")
                }
            }
            SplitMethod.PERCENT -> {
                val each = 100 / included.size
                val remainder = 100 - each * included.size
                var cursor = 0
                current.rows.map { row ->
                    if (row.included) {
                        val extra = if (cursor == payerIndex(included)) remainder else 0
                        row.copy(input = (each + extra).toString()).also { cursor++ }
                    } else {
                        row.copy(input = "")
                    }
                }
            }
            SplitMethod.SHARES -> current.rows.map { row ->
                if (row.included) row.copy(input = "1") else row.copy(input = "")
            }
        }
        _state.update { it.copy(rows = seeded) }
    }

    /** The live preview, using the same arithmetic the server will apply. */
    private fun recompute() {
        val current = _state.value
        val included = current.rows.filter { it.included }
        if (included.isEmpty()) {
            _state.update { it.copy(computed = emptyMap()) }
            return
        }

        val computed = when (current.method) {
            SplitMethod.EQUAL -> {
                val amounts = Money.apportion(
                    current.totalMinor,
                    included.map { 1L },
                    preferIndex = payerIndex(included)
                )
                included.mapIndexed { index, row -> row.userId to amounts[index] }.toMap()
            }
            SplitMethod.EXACT -> included.associate { row ->
                row.userId to (Money.parseOrNull(row.input) ?: 0L)
            }
            SplitMethod.PERCENT -> {
                val weights = included.map { (it.input.toLongOrNull() ?: 0L) }
                val amounts = Money.apportion(current.totalMinor, weights, payerIndex(included))
                included.mapIndexed { index, row -> row.userId to amounts[index] }.toMap()
            }
            SplitMethod.SHARES -> {
                val weights = included.map { (it.input.toLongOrNull() ?: 0L) }
                val amounts = Money.apportion(current.totalMinor, weights, payerIndex(included))
                included.mapIndexed { index, row -> row.userId to amounts[index] }.toMap()
            }
        }
        _state.update { it.copy(computed = computed) }
    }

    /** The payer absorbs the odd paise, which is what the server does too. */
    private fun payerIndex(included: List<SplitRow>): Int {
        val payerId = _state.value.expense?.paidBy?.id ?: return 0
        return included.indexOfFirst { it.userId == payerId }.coerceAtLeast(0)
    }

    private companion object {
        const val PENDING_PREFIX = "contact:"
    }
}
