package com.snaptab.app.ui.screen.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The three states of a scan, behind one destination: camera, then the server reading it,
 * then the review. Keeping them in one route means the back button leaves the flow rather
 * than walking back into a camera for a bill that has already been read.
 */
@Composable
fun ScanFlow(
    alertId: String?,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
    onSplit: (String) -> Unit,
    onEnterByHand: () -> Unit,
    viewModel: ScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(alertId) { viewModel.setAlertContext(alertId) }

    LaunchedEffect(state.savedExpenseId) {
        state.savedExpenseId?.let(onSaved)
    }

    when (state.stage) {
        ScanStage.IDLE -> CameraScreen(
            onCaptured = viewModel::upload,
            onClose = onClose,
            onEnterByHand = onEnterByHand
        )

        ScanStage.UPLOADING, ScanStage.PROCESSING, ScanStage.FAILED -> ProcessingScreen(
            state = state,
            onBackground = onClose,
            onRetry = viewModel::retry,
            onEnterByHand = onEnterByHand,
            onCancel = {
                viewModel.reset()
                onClose()
            }
        )

        ScanStage.READY -> ReviewScreen(
            state = state,
            onMerchantChange = viewModel::setMerchant,
            onCategoryChange = viewModel::setCategory,
            onItemChange = viewModel::updateItem,
            onItemRemove = viewModel::removeItem,
            onKeepPersonal = { viewModel.save(shared = false) },
            onSplit = { groupId ->
                if (groupId != null) {
                    viewModel.save(shared = true, groupId = groupId)
                } else {
                    // No group: save it, then send the user to the split editor to pick people.
                    viewModel.save(shared = false)
                }
            },
            onRescan = viewModel::reset,
            onDismissError = viewModel::dismissError
        )
    }
}
