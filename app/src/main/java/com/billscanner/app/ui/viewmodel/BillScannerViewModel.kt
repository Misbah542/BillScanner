package com.billscanner.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billscanner.app.data.model.BillResponse
import com.billscanner.app.data.repository.BillScannerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class BillScannerViewModel @Inject constructor(
    private val repository: BillScannerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BillScannerUiState())
    val uiState: StateFlow<BillScannerUiState> = _uiState.asStateFlow()

    fun scanBill(imageFile: File) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            repository.scanBill(imageFile)
                .onSuccess { billResponse ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        billResponse = billResponse,
                        error = null
                    )
                }
                .onFailure { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = exception.message ?: "Unknown error occurred"
                    )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearBillResponse() {
        _uiState.value = _uiState.value.copy(billResponse = null)
    }
}

data class BillScannerUiState(
    val isLoading: Boolean = false,
    val billResponse: BillResponse? = null,
    val error: String? = null
)
