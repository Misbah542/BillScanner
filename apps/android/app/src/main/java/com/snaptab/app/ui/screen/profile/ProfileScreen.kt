package com.snaptab.app.ui.screen.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snaptab.app.R
import com.snaptab.app.ui.components.*
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * Your profile: who you are, how you sign in, what SnapTab is allowed to read on this
 * phone, and the two ways out.
 *
 * The permission toggles are here rather than buried in a settings list because reading
 * someone's bank messages is the most invasive thing this app does, and it should be one
 * tap to see and one tap to stop.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onOpenMonthly: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.delete_account)) },
            text = { Text(stringResource(R.string.delete_account_warning)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(
                        text = stringResource(R.string.delete_account_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ProfileHeader(
            state = state,
            onBack = onBack,
            onEditName = viewModel::startEditingName,
            onNameChange = viewModel::setNameDraft,
            onSaveName = viewModel::saveName,
            onCancelName = viewModel::cancelEditingName
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(14.dp))

            ErrorBanner(
                message = state.error,
                onDismiss = viewModel::dismissError,
                onRetry = viewModel::refresh,
                offline = state.offline
            )

            SectionLabel(text = stringResource(R.string.how_you_sign_in))
            SnapCard(contentPadding = PaddingValues(0.dp)) {
                IdentityRow(
                    label = stringResource(R.string.label_email),
                    value = state.user?.email ?: "—",
                    icon = Icons.Outlined.Email,
                    verified = state.user?.emailVerified == true,
                    showDivider = true
                )
                IdentityRow(
                    label = stringResource(R.string.label_phone),
                    value = state.user?.phone ?: "—",
                    icon = Icons.Outlined.PhoneAndroid,
                    verified = state.user?.phoneVerified == true,
                    showDivider = true
                )
                IdentityRow(
                    label = stringResource(R.string.google),
                    value = stringResource(
                        if (state.user?.googleLinked == true) R.string.linked else R.string.not_linked
                    ),
                    icon = Icons.Outlined.AccountCircle,
                    verified = state.user?.googleLinked == true,
                    showDivider = false
                )
            }

            SectionLabel(text = stringResource(R.string.reading_card_alerts))
            SnapCard(contentPadding = PaddingValues(0.dp)) {
                ToggleRow(
                    title = stringResource(R.string.toggle_read_sms_title),
                    detail = stringResource(R.string.toggle_read_sms_body),
                    checked = state.smsEnabled,
                    onCheckedChange = { wanted ->
                        // Turning it ON needs the runtime permission; turning it off never does.
                        if (wanted) onRequestSmsPermission() else viewModel.setSmsEnabled(false)
                    },
                    showDivider = true
                )
                ToggleRow(
                    title = stringResource(R.string.toggle_keep_bodies_title),
                    detail = stringResource(R.string.toggle_keep_bodies_body),
                    checked = state.keepAlertBodies,
                    enabled = state.smsEnabled,
                    onCheckedChange = viewModel::setKeepAlertBodies,
                    showDivider = false
                )
            }

            SectionLabel(text = stringResource(R.string.preferences))
            SnapCard(contentPadding = PaddingValues(0.dp)) {
                PreferenceRow(
                    label = stringResource(R.string.monthly_spending),
                    value = currentMonthName(),
                    icon = Icons.Outlined.BarChart,
                    onClick = onOpenMonthly,
                    showDivider = true
                )
                PreferenceRow(
                    label = stringResource(R.string.currency),
                    value = state.user?.currency ?: "INR",
                    icon = Icons.Outlined.Payments,
                    onClick = null,
                    showDivider = true
                )
                PreferenceRow(
                    label = stringResource(R.string.time_zone),
                    value = state.user?.timezone ?: ZoneId.systemDefault().id,
                    icon = Icons.Outlined.Schedule,
                    onClick = null,
                    showDivider = false
                )
            }

            Spacer(Modifier.height(6.dp))

            SecondaryButton(
                text = stringResource(R.string.sign_out),
                onClick = viewModel::signOut,
                enabled = !state.saving,
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            TextButton(
                onClick = viewModel::signOutEverywhere,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.sign_out_everywhere))
            }

            OutlinedButton(
                onClick = viewModel::askToDelete,
                enabled = !state.saving,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.delete_account))
            }

            Text(
                text = stringResource(R.string.delete_account_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ProfileHeader(
    state: ProfileUiState,
    onBack: () -> Unit,
    onEditName: () -> Unit,
    onNameChange: (String) -> Unit,
    onSaveName: () -> Unit,
    onCancelName: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.inverseSurface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!state.editingName) {
                    OutlinedButton(
                        onClick = onEditName,
                        shape = RoundedCornerShape(13.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface
                        )
                    ) {
                        Text(stringResource(R.string.edit))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    name = state.user?.name,
                    size = 64.dp,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (state.editingName) {
                        OutlinedTextField(
                            value = state.nameDraft,
                            onValueChange = onNameChange,
                            label = { Text(stringResource(R.string.your_name)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.inverseOnSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.inverseOnSurface,
                                focusedLabelColor = MaterialTheme.colorScheme.inverseOnSurface,
                                unfocusedLabelColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                                focusedBorderColor = MaterialTheme.colorScheme.inverseOnSurface,
                                unfocusedBorderColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.4f),
                                cursorColor = MaterialTheme.colorScheme.inverseOnSurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onSaveName,
                                enabled = !state.saving,
                                shape = RoundedCornerShape(11.dp)
                            ) {
                                Text(stringResource(R.string.save))
                            }
                            TextButton(onClick = onCancelName) {
                                Text(
                                    text = stringResource(R.string.cancel),
                                    color = MaterialTheme.colorScheme.inverseOnSurface
                                )
                            }
                        }
                    } else {
                        Text(
                            text = state.user?.name ?: stringResource(R.string.loading),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        state.user?.createdAt?.let { created ->
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = stringResource(R.string.member_since, formatMonthYear(created)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderStat(
                    label = stringResource(R.string.spent_in, currentMonthName()),
                    value = state.summary?.spentMinor ?: 0,
                    currency = state.summary?.currency ?: "INR",
                    accent = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.weight(1f)
                )
                HeaderStat(
                    label = stringResource(R.string.net_position),
                    value = state.balance?.netMinor ?: 0,
                    currency = state.balance?.currency ?: "INR",
                    accent = if ((state.balance?.netMinor ?: 0) >= 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    signed = true,
                    modifier = Modifier.weight(1f)
                )
                HeaderStatText(
                    label = stringResource(R.string.tabs_count),
                    value = state.expenseCount.toString(),
                    modifier = Modifier.weight(0.7f)
                )
            }
        }
    }
}

@Composable
private fun HeaderStat(
    label: String,
    value: Long,
    currency: String,
    accent: Color,
    modifier: Modifier = Modifier,
    signed: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.12f))
            .padding(horizontal = 13.dp, vertical = 11.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Amount(
            minor = value,
            currency = currency,
            size = 18.sp,
            color = accent,
            signed = signed,
            inline = true
        )
    }
}

@Composable
private fun HeaderStatText(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.12f))
            .padding(horizontal = 13.dp, vertical = 11.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.inverseOnSurface
        )
    }
}

@Composable
private fun IdentityRow(
    label: String,
    value: String,
    icon: ImageVector,
    verified: Boolean,
    showDivider: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (verified) {
            StatusChip(
                text = stringResource(R.string.verified),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(11.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PreferenceRow(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    showDivider: Boolean
) {
    val row = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(11.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onClick != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    if (onClick != null) {
        Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
            row()
        }
    } else {
        row()
    }
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun currentMonthName(): String =
    YearMonth.now().month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())

private fun formatMonthYear(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMMM yyyy")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(iso))
}.getOrDefault("")
