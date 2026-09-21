package com.snaptab.app.ui.screen.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.local.GroupEntity
import com.snaptab.app.data.remote.dto.*
import com.snaptab.app.data.repository.ExpenseRepository
import com.snaptab.app.data.repository.GroupRepository
import com.snaptab.app.data.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Where the scan has got to. Mirrors the server's own scan states. */
enum class ScanStage { IDLE, UPLOADING, PROCESSING, READY, FAILED }

data class ScanUiState(
    val stage: ScanStage = ScanStage.IDLE,
    val scanId: String? = null,
    val result: ParsedReceiptDto? = null,
    val groups: List<GroupEntity> = emptyList(),
    /** Editable copies, so the user can fix what the reader got wrong. */
    val merchant: String = "",
    val categorySlug: String? = null,
    val items: List<ParsedReceiptItemDto> = emptyList(),
    val savedExpenseId: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
    val offline: Boolean = false,
    /** Set when the scan was started from a card alert, so it can be linked on save. */
    val alertId: String? = null
) {
    val totals: ExpenseTotalsDto? get() = result?.totals
    val suggestions: List<CategorySuggestionDto> get() = result?.suggestions.orEmpty()
    val warnings: List<String> get() = result?.warnings.orEmpty()
    val itemsSum: Long get() = items.sumOf { it.amountMinor }
}

/**
 * Driving a scan.
 *
 * The client's whole job here is upload and poll. Recognition, parsing and the category
 * guess all happen server-side, which is what makes the scan finish even if the app is
 * closed and lets the parsing rules be fixed without an app release.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scans: ScanRepository,
    private val expenses: ExpenseRepository,
    private val groups: GroupRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            groups.refresh()
            groups.observe().collect { rows -> _state.update { it.copy(groups = rows) } }
        }
    }

    fun setAlertContext(alertId: String?) = _state.update { it.copy(alertId = alertId) }

    fun upload(image: File) {
        viewModelScope.launch {
            _state.update { it.copy(stage = ScanStage.UPLOADING, error = null, errorCode = null) }

            when (val upload = scans.upload(image)) {
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        stage = ScanStage.FAILED,
                        error = upload.message,
                        errorCode = upload.code,
                        offline = upload.isOffline
                    )
                }
                is ApiResult.Success -> {
                    _state.update { it.copy(stage = ScanStage.PROCESSING, scanId = upload.data.id) }
                    // Already done — the server had read this photo before.
                    if (upload.data.status == "SUCCEEDED") {
                        adopt(upload.data)
                        return@launch
                    }
                    awaitResult(upload.data.id)
                }
            }
            // The cached copy of the image is no use once it is uploaded.
            runCatching { image.delete() }
        }
    }

    fun retry() {
        val id = _state.value.scanId ?: return
        viewModelScope.launch {
            _state.update { it.copy(stage = ScanStage.PROCESSING, error = null, errorCode = null) }
            when (val result = scans.retry(id)) {
                is ApiResult.Success -> awaitResult(id)
                is ApiResult.Failure -> _state.update {
                    it.copy(stage = ScanStage.FAILED, error = result.message, errorCode = result.code)
                }
            }
        }
    }

    private suspend fun awaitResult(scanId: String) {
        when (val result = scans.awaitResult(scanId)) {
            is ApiResult.Success -> adopt(result.data)
            is ApiResult.Failure -> _state.update {
                it.copy(
                    stage = ScanStage.FAILED,
                    error = result.message,
                    errorCode = result.code,
                    offline = result.isOffline
                )
            }
        }
    }

    private fun adopt(scan: ScanDto) {
        if (scan.status == "FAILED" || scan.result == null) {
            _state.update {
                it.copy(
                    stage = ScanStage.FAILED,
                    error = scan.error?.message ?: "That bill could not be read.",
                    errorCode = scan.error?.code
                )
            }
            return
        }
        _state.update {
            it.copy(
                stage = ScanStage.READY,
                scanId = scan.id,
                result = scan.result,
                merchant = scan.result.merchantName.orEmpty(),
                items = scan.result.items,
                // Take the top suggestion only when it is confident; the user confirms it.
                categorySlug = scan.result.suggestions
                    .firstOrNull()
                    ?.takeIf { suggestion -> suggestion.confidence > 0.45 }
                    ?.slug
            )
        }
    }

    fun setMerchant(value: String) = _state.update { it.copy(merchant = value) }

    fun setCategory(slug: String?) = _state.update { it.copy(categorySlug = slug) }

    fun updateItem(index: Int, item: ParsedReceiptItemDto) = _state.update { current ->
        current.copy(
            items = current.items.toMutableList().also { list ->
                if (index in list.indices) list[index] = item
            }
        )
    }

    fun removeItem(index: Int) = _state.update { current ->
        current.copy(items = current.items.filterIndexed { i, _ -> i != index })
    }

    /**
     * Saves what was read. `shared` decides whether it becomes a tab to split or a
     * personal expense — a scanned receipt is not automatically a shared one.
     */
    fun save(shared: Boolean, groupId: String? = null) {
        val current = _state.value
        val totals = current.totals ?: return

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }

            val request = CreateExpenseRequest(
                kind = if (shared) "SHARED" else "PERSONAL",
                source = "SCAN",
                groupId = groupId,
                merchantName = current.merchant.ifBlank { null },
                occurredAt = current.result?.occurredAt,
                currency = current.result?.currency ?: "INR",
                categorySlug = current.categorySlug,
                // Recomputed from the edited rows, not the original read.
                itemTotalMinor = current.itemsSum.takeIf { it > 0 } ?: totals.itemTotalMinor,
                serviceChargeMinor = totals.serviceChargeMinor,
                taxMinor = totals.taxMinor,
                discountMinor = totals.discountMinor,
                tipMinor = totals.tipMinor,
                roundOffMinor = totals.roundOffMinor,
                totalMinor = totals.totalMinor,
                items = current.items.map {
                    ExpenseItemRequest(it.name, it.quantityMilli, it.unitPriceMinor, it.amountMinor)
                },
                taxLines = current.result?.taxLines.orEmpty(),
                splitMethod = if (shared) "EQUAL" else null,
                participants = if (shared && groupId != null) {
                    // The group's members, so a scanned bill is one tap to a split.
                    groups.detail(groupId).successOrNull?.group?.members
                        ?.map { ParticipantRequest(userId = it.user.id) }
                } else {
                    null
                },
                scanId = current.scanId,
                alertId = current.alertId
            )

            val result = if (shared) {
                expenses.createShared(request)
            } else {
                expenses.createShared(request.copy(kind = "PERSONAL", splitMethod = null, participants = null))
            }

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

    fun reset() = _state.update { ScanUiState(groups = it.groups) }

    fun dismissError() = _state.update { it.copy(error = null) }
}
