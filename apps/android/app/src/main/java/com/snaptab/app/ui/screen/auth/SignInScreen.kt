package com.snaptab.app.ui.screen.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snaptab.app.BuildConfig
import com.snaptab.app.R
import com.snaptab.app.ui.components.ErrorBanner
import com.snaptab.app.ui.components.PrimaryButton
import com.snaptab.app.ui.components.SecondaryButton
import com.snaptab.app.ui.components.SegmentedTabs
import com.snaptab.app.ui.components.SnapCard
import kotlinx.coroutines.launch

/**
 * Sign in: Google, or a code by email or phone. No password anywhere, so there is
 * nothing to remember and nothing to leak.
 */
@Composable
fun SignInScreen(
    onSignedIn: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Both declared before the VerifyScreen branch below returns, so they are reached on
    // every composition of this function rather than only on one of its two paths.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) onSignedIn()
    }

    if (state.codeSentTo != null) {
        VerifyScreen(
            state = state,
            onCodeChange = viewModel::setCode,
            onVerify = viewModel::verify,
            onResend = viewModel::sendCode,
            onBack = viewModel::backToContact,
            onDismissError = viewModel::dismissError
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(40.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "S",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium
            )
        }

        Spacer(Modifier.height(26.dp))
        Text(
            text = stringResource(R.string.sign_in_headline),
            style = MaterialTheme.typography.displayMedium
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.sign_in_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(26.dp))

        ErrorBanner(
            message = state.error,
            onDismiss = viewModel::dismissError,
            offline = state.offline
        )

        if (state.googleAvailable) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                text = if (state.googleSigningIn) {
                    stringResource(R.string.google_signing_in)
                } else {
                    stringResource(R.string.continue_with_google)
                },
                // Disabled while either sign-in path is in flight, so the sheet cannot be
                // opened twice or raced against a code being verified.
                enabled = !state.googleSigningIn && !state.verifying,
                onClick = {
                    viewModel.startGoogleSignIn()
                    scope.launch {
                        // The client id is a build config value, never a literal: it comes
                        // from an untracked local.properties or the ANDROID_GOOGLE_CLIENT_ID
                        // secret, and is empty in builds that have neither.
                        when (val outcome = requestGoogleIdToken(context, BuildConfig.GOOGLE_CLIENT_ID)) {
                            is GoogleSignInOutcome.Token -> viewModel.signInWithGoogle(outcome.idToken)
                            GoogleSignInOutcome.Cancelled -> viewModel.onGoogleCancelled()
                            is GoogleSignInOutcome.Failed ->
                                viewModel.onGoogleFailed(context.getString(outcome.messageRes))
                        }
                    }
                }
            )

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.or).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(4.dp))
        }

        SegmentedTabs(
            options = listOf(stringResource(R.string.tab_email), stringResource(R.string.tab_phone)),
            selectedIndex = if (state.method == ContactMethod.EMAIL) 0 else 1,
            onSelect = { index ->
                viewModel.setMethod(if (index == 0) ContactMethod.EMAIL else ContactMethod.PHONE)
            }
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.contact,
            onValueChange = viewModel::setContact,
            label = {
                Text(
                    stringResource(
                        if (state.method == ContactMethod.EMAIL) R.string.label_email else R.string.label_phone
                    )
                )
            },
            placeholder = {
                Text(
                    stringResource(
                        if (state.method == ContactMethod.EMAIL) R.string.hint_email else R.string.hint_phone
                    )
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (state.method == ContactMethod.EMAIL) {
                        Icons.Outlined.Email
                    } else {
                        Icons.Outlined.PhoneAndroid
                    },
                    contentDescription = null
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (state.method == ContactMethod.EMAIL) {
                    KeyboardType.Email
                } else {
                    KeyboardType.Phone
                },
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))

        PrimaryButton(
            text = stringResource(
                if (state.method == ContactMethod.EMAIL) {
                    R.string.email_me_a_code
                } else {
                    R.string.text_me_a_code
                }
            ),
            onClick = viewModel::sendCode,
            enabled = state.canSend,
            loading = state.sending
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(
                if (state.method == ContactMethod.EMAIL) {
                    R.string.code_hint_email
                } else {
                    R.string.code_hint_phone
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(22.dp))

        SnapCard(contentPadding = PaddingValues(14.dp)) {
            Row {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.privacy_reassurance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.terms_and_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
    }
}
