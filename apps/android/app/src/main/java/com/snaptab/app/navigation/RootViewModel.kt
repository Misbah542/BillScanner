package com.snaptab.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.data.local.TokenStore
import com.snaptab.app.data.repository.AlertRepository
import com.snaptab.app.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RootState(
    /** False until the session has been read from disk, so sign-in does not flash. */
    val ready: Boolean = false,
    val signedIn: Boolean = false,
    val unreadAlerts: Int = 0,
    val googleSignInRequested: Boolean = false
)

@HiltViewModel
class RootViewModel @Inject constructor(
    tokenStore: TokenStore,
    private val devices: DeviceRepository,
    private val alerts: AlertRepository
) : ViewModel() {

    private val googleRequested = MutableStateFlow(false)

    val state: StateFlow<RootState> = combine(
        tokenStore.isSignedIn,
        alerts.observeUnmatchedCount(),
        googleRequested
    ) { signedIn, unread, google ->
        RootState(ready = true, signedIn = signedIn, unreadAlerts = unread, googleSignInRequested = google)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState())

    /**
     * Google sign-in needs the Credential Manager flow, which belongs to the Activity
     * rather than to a ViewModel. Recorded here so the UI can trigger it; the demo build
     * has it switched off server-side and the button is hidden.
     */
    fun onGoogleSignInRequested() {
        googleRequested.value = true
    }

    fun onSmsPermissionResult(granted: Boolean) {
        viewModelScope.launch { devices.setSmsEnabled(granted) }
    }
}
