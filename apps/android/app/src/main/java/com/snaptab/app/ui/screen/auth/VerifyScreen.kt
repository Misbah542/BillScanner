package com.snaptab.app.ui.screen.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snaptab.app.R
import com.snaptab.app.ui.components.ErrorBanner
import com.snaptab.app.ui.components.PrimaryButton
import com.snaptab.app.ui.theme.AmountStyle

/**
 * The code step. One field rather than six boxes: six separately-focused boxes look neat
 * and fight every SMS autofill implementation on the platform.
 */
@Composable
fun VerifyScreen(
    state: AuthUiState,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
    onDismissError: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.back)
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.enter_your_code),
            style = MaterialTheme.typography.displaySmall
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = stringResource(R.string.sent_to, state.codeSentTo.orEmpty()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.wrong_address))
        }

        Spacer(Modifier.height(18.dp))

        ErrorBanner(message = state.error, onDismiss = onDismissError, offline = state.offline)

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            textStyle = AmountStyle.copy(
                fontSize = MaterialTheme.typography.displayMedium.fontSize,
                textAlign = TextAlign.Center
            ),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            ),
            placeholder = {
                Text(
                    text = "······",
                    style = AmountStyle.copy(
                        fontSize = MaterialTheme.typography.displayMedium.fontSize,
                        textAlign = TextAlign.Center
                    ),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (state.devCode != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                // Development only: the server withholds devCode outside non-production,
                // so this cannot appear in a release build.
                text = "Development code: ${state.devCode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = if (state.resendSeconds > 0) {
                    stringResource(R.string.resend_in, "0:${state.resendSeconds.toString().padStart(2, '0')}")
                } else {
                    ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onResend,
                enabled = state.resendSeconds == 0 && !state.sending,
                shape = RoundedCornerShape(11.dp)
            ) {
                Text(stringResource(R.string.resend))
            }
        }

        Spacer(Modifier.weight(1f))

        PrimaryButton(
            text = stringResource(R.string.verify_and_continue),
            onClick = onVerify,
            enabled = state.canVerify,
            loading = state.verifying
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.use_a_different_method))
        }
        Spacer(Modifier.height(20.dp))
    }
}
