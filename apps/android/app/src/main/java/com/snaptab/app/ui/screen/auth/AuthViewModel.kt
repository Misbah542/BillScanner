package com.snaptab.app.ui.screen.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snaptab.app.BuildConfig
import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.repository.AuthRepository
import com.snaptab.app.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ContactMethod { EMAIL, PHONE }

data class AuthUiState(
    val method: ContactMethod = ContactMethod.EMAIL,
    val contact: String = "",
    val name: String = "",
    val code: String = "",
    val codeSentTo: String? = null,
    val resendSeconds: Int = 0,
    val sending: Boolean = false,
    val verifying: Boolean = false,
    val googleAvailable: Boolean = BuildConfig.GOOGLE_CLIENT_ID.isNotBlank(),
    val signedIn: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false,
    /** Only ever populated outside production, so a local build can sign in with no mailer. */
    val devCode: String? = null
) {
    val canSend: Boolean get() = contact.trim().length >= 3 && !sending
    val canVerify: Boolean get() = code.length in 4..8 && !verifying
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val categories: CategoryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        // Ask the server which methods it actually offers, so a Google button is never
        // shown for a server that has no client id configured.
        viewModelScope.launch {
            auth.availableMethods().onSuccess { methods ->
                _state.update { it.copy(googleAvailable = methods.google && it.googleAvailable) }
            }
        }
    }

    fun setMethod(method: ContactMethod) = _state.update {
        it.copy(method = method, contact = "", error = null)
    }

    fun setContact(value: String) = _state.update { it.copy(contact = value, error = null) }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setCode(value: String) = _state.update {
        it.copy(code = value.filter(Char::isDigit).take(6), error = null)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun sendCode() {
        val contact = _state.value.contact.trim()
        if (contact.length < 3) return

        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            when (val result = auth.startOtp(contact)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            sending = false,
                            codeSentTo = result.data.sentTo,
                            devCode = result.data.devCode,
                            // Pre-fill in development so a local build is one tap, and never
                            // in a release build, where devCode is absent anyway.
                            code = result.data.devCode.takeIf { _ -> BuildConfig.DEBUG }.orEmpty(),
                            resendSeconds = RESEND_SECONDS
                        )
                    }
                    countDown()
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(sending = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun verify() {
        val current = _state.value
        if (!current.canVerify) return

        viewModelScope.launch {
            _state.update { it.copy(verifying = true, error = null) }
            when (val result = auth.verifyOtp(current.contact, current.code, current.name)) {
                is ApiResult.Success -> {
                    // Pull the taxonomy straight away: the first screen after sign-in shows
                    // category chips and should not have to wait for them.
                    runCatching { categories.refresh() }
                    _state.update { it.copy(verifying = false, signedIn = true) }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(verifying = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    /** Called with the id token the Google client handed back. */
    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _state.update { it.copy(verifying = true, error = null) }
            when (val result = auth.signInWithGoogle(idToken)) {
                is ApiResult.Success -> {
                    runCatching { categories.refresh() }
                    _state.update { it.copy(verifying = false, signedIn = true) }
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(verifying = false, error = result.message, offline = result.isOffline)
                }
            }
        }
    }

    fun backToContact() = _state.update {
        it.copy(codeSentTo = null, code = "", devCode = null, error = null)
    }

    private fun countDown() {
        viewModelScope.launch {
            while (_state.value.resendSeconds > 0) {
                kotlinx.coroutines.delay(1000)
                _state.update { it.copy(resendSeconds = (it.resendSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    private companion object {
        const val RESEND_SECONDS = 30
    }
}
