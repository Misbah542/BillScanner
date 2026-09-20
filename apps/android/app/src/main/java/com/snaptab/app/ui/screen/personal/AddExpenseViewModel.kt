package com.snaptab.app.ui.screen.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.Money
import com.snaptab.app.data.local.CategoryEntity
import com.snaptab.app.data.local.GroupEntity
import com.snaptab.app.data.remote.dto.CategorySuggestionDto
import com.snaptab.app.data.repository.CategoryRepository
import com.snaptab.app.data.repository.ExpenseRepository
import com.snaptab.app.data.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class AddExpenseUiState(
    val amountText: String = "",
    val merchant: String = "",
    val note: String = "",
    val categorySlug: String? = null,
    /** Ranked by the server from the merchant name, so the chips reorder as you type. */
    val suggestions: List<CategorySuggestionDto> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val groups: List<GroupEntity> = emptyList(),
    /** Null means no group at all, which is the default and the common case. */
    val groupId: String? = null,
    val occurredAt: Instant = Instant.now(),
    val recentMerchants: List<String> = emptyList(),
    val saving: Boolean = false,
    val savedExpenseId: String? = null,
    val error: String? = null,
    val offline: Boolean = false
) {
    val amountMinor: Long? get() = Money.parseOrNull(amountText)
    val canSave: Boolean get() = (amountMinor ?: 0) > 0 && !saving

    /** True when the category was taken from a suggestion rather than chosen outright. */
    val categoryIsSuggested: Boolean
        get() = categorySlug != null && categorySlug == suggestions.firstOrNull()?.slug
}

/**
 * Adding an expense by hand, with no group and no split.
 *
 * This is the path for spending that simply has no bill to scan and nobody to divide it
 * with, which is most spending. An amount is the only required field; a merchant gets
 * you a category suggestion for free.
 */
@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val expenses: ExpenseRepository,
    private val categories: CategoryRepository,
    private val groups: GroupRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AddExpenseUiState())
    val state: StateFlow<AddExpenseUiState> = _state.asStateFlow()

    private var suggestJob: Job? = null

    init {
        viewModelScope.launch {
            categories.observe().collect { rows -> _state.update { it.copy(categories = rows) } }
        }
        viewModelScope.launch {
            groups.observe().collect { rows -> _state.update { it.copy(groups = rows) } }
        }
        viewModelScope.launch {
            categories.refresh()
            groups.refresh()
        }
    }

    /** Pre-fills from a bank alert, so "log this" lands on a form that is already filled. */
    fun prefill(amountMinor: Long?, merchant: String?, categorySlug: String?, occurredAt: Instant?) {
        _state.update { current ->
            current.copy(
                amountText = amountMinor?.let { Money.toEditable(it) } ?: current.amountText,
                merchant = merchant ?: current.merchant,
                categorySlug = categorySlug ?: current.categorySlug,
                occurredAt = occurredAt ?: current.occurredAt
            )
        }
        if (merchant != null && categorySlug == null) requestSuggestions(merchant)
    }

    fun setAmount(text: String) {
        // Accept only what could become a number, so the field cannot hold "12.3.4".
        val cleaned = text.filter { it.isDigit() || it == '.' }
            .let { raw ->
                val firstDot = raw.indexOf('.')
                if (firstDot < 0) raw else raw.substring(0, firstDot + 1) + raw.substring(firstDot + 1).replace(".", "")
            }
            .let { if (it.contains('.')) it.substringBefore('.') + "." + it.substringAfter('.').take(2) else it }
        _state.update { it.copy(amountText = cleaned, error = null) }
    }

    fun setMerchant(text: String) {
        _state.update { it.copy(merchant = text, error = null) }
        requestSuggestions(text)
    }

    fun setNote(text: String) = _state.update { it.copy(note = text) }

    fun setCategory(slug: String?) = _state.update { it.copy(categorySlug = slug) }

    fun setGroup(groupId: String?) = _state.update { it.copy(groupId = groupId) }

    fun setOccurredAt(instant: Instant) = _state.update { it.copy(occurredAt = instant) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun save() {
        val current = _state.value
        val amount = current.amountMinor
        if (amount == null || amount <= 0) {
            _state.update { it.copy(error = "Enter an amount first.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            val result = expenses.createPersonal(
                totalMinor = amount,
                merchantName = current.merchant,
                categorySlug = current.categorySlug,
                occurredAtIso = DateTimeFormatter.ISO_INSTANT.format(current.occurredAt),
                note = current.note,
                groupId = current.groupId
            )
            when (result) {
                is ApiResult.Success -> _state.update {
                    it.copy(saving = false, savedExpenseId = result.data.id)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(saving = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    /** Debounced: a suggestion per keystroke would be a request per keystroke. */
    private fun requestSuggestions(merchant: String) {
        suggestJob?.cancel()
        if (merchant.trim().length < 3) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(350)
            expenses.suggestCategory(merchant).onSuccess { ranked ->
                _state.update { current ->
                    current.copy(
                        suggestions = ranked,
                        // Adopt the top suggestion only while the user has not chosen for
                        // themselves — never overwrite a deliberate choice.
                        categorySlug = current.categorySlug
                            ?: ranked.firstOrNull()?.takeIf { it.confidence > 0.45 }?.slug
                    )
                }
            }
        }
    }

    fun formattedDate(zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(zone).format(_state.value.occurredAt)
}
