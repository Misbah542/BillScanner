package com.snaptab.app.ui.screen.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snaptab.app.R
import com.snaptab.app.core.Money
import com.snaptab.app.data.local.AlertEntity
import com.snaptab.app.ui.components.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The card-alert inbox.
 *
 * Every row offers the same two routes out, because a debit is one of two things and only
 * the person who spent the money knows which: log it as yours, or put it on a group.
 * Scanning the bill is the third route, for when the item list is worth having.
 */
@Composable
fun InboxScreen(
    onOpenExpense: (String) -> Unit,
    onScanForAlert: (AlertEntity) -> Unit,
    onRequestSmsPermission: () -> Unit,
    viewModel: InboxViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    state.justLoggedExpenseId?.let { id ->
        androidx.compose.runtime.LaunchedEffect(id) {
            viewModel.consumeLoggedExpense()
            onOpenExpense(id)
        }
    }

    state.logTarget?.let { target ->
        LogAlertSheet(
            alert = target.alert,
            groups = state.groups,
            working = state.working,
            onLogPersonal = { viewModel.logAsPersonal(target.alert) },
            onLogToGroup = { groupId -> viewModel.logToGroup(target.alert, groupId) },
            onScanBill = {
                viewModel.dismissLogSheet()
                onScanForAlert(target.alert)
            },
            onDismiss = viewModel::dismissLogSheet
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.inbox),
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.inbox_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            ErrorBanner(
                message = state.error,
                onDismiss = viewModel::dismissError,
                onRetry = viewModel::refresh,
                offline = state.offline
            )
        }

        if (state.needsPermission) {
            item { SmsPermissionCard(onAllow = onRequestSmsPermission) }
        }

        if (state.pendingUploads > 0) {
            item {
                SnapCard(
                    contentPadding = PaddingValues(13.dp),
                    background = MaterialTheme.colorScheme.tertiaryContainer,
                    borderColor = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "${state.pendingUploads} waiting to upload — they will go up when you are back online.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        item {
            SegmentedTabs(
                options = listOf(
                    stringResource(R.string.inbox_needs_logging),
                    stringResource(R.string.inbox_matched),
                    stringResource(R.string.inbox_ignored)
                ),
                selectedIndex = InboxFilter.entries.indexOf(state.filter),
                onSelect = { viewModel.setFilter(InboxFilter.entries[it]) }
            )
        }

        if (state.alerts.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.inbox_empty_title),
                    body = stringResource(R.string.inbox_empty_body)
                )
            }
        } else {
            items(state.alerts, key = { it.id }) { alert ->
                AlertCard(
                    alert = alert,
                    onLog = { viewModel.openLogSheet(alert) },
                    onScan = { onScanForAlert(alert) },
                    onIgnore = { viewModel.ignore(alert) },
                    onOpenExpense = { alert.expenseId?.let(onOpenExpense) }
                )
            }
        }

        item {
            SnapCard(
                contentPadding = PaddingValues(13.dp),
                background = MaterialTheme.colorScheme.primaryContainer,
                borderColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.on_device_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun SmsPermissionCard(onAllow: () -> Unit) {
    SnapCard {
        Text(
            text = stringResource(R.string.sms_permission_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.sms_permission_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(text = stringResource(R.string.allow_sms), onClick = onAllow)
    }
}

@Composable
private fun AlertCard(
    alert: AlertEntity,
    onLog: () -> Unit,
    onScan: () -> Unit,
    onIgnore: () -> Unit,
    onOpenExpense: () -> Unit
) {
    val isCredit = alert.direction == "CREDIT"
    val accent = if (isCredit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

    SnapCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isCredit) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCredit) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp)
                )
            }

            Spacer(Modifier.width(11.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = alert.merchantRaw ?: stringResource(R.string.error_unknown),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Amount(
                        minor = if (isCredit) alert.amountMinor else -alert.amountMinor,
                        currency = alert.currency,
                        size = 19.sp,
                        color = accent,
                        signed = true
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = listOfNotNull(
                        alert.bankName,
                        alert.accountMask?.let { "··$it" },
                        relativeTime(alert.occurredAtEpoch)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(11.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (alert.suggestedCategoryName != null) {
                CategoryChip(
                    slug = alert.suggestedCategorySlug,
                    name = stringResource(R.string.looks_like, alert.suggestedCategoryName)
                )
            }
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(11.dp))

        when {
            alert.status == "UNMATCHED" && !isCredit -> Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                TextButton(onClick = onIgnore, modifier = Modifier.heightIn(min = 40.dp)) {
                    Text(stringResource(R.string.ignore))
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onScan,
                    shape = RoundedCornerShape(11.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Text(stringResource(R.string.scan_bill), style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = onLog,
                    shape = RoundedCornerShape(11.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.log_amount,
                            Money.formatCompact(alert.amountMinor, alert.currency)
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            alert.expenseId != null -> OutlinedButton(
                onClick = onOpenExpense,
                shape = RoundedCornerShape(11.dp),
                modifier = Modifier.heightIn(min = 40.dp)
            ) {
                Text(stringResource(R.string.open_tab))
            }

            else -> StatusChip(text = alert.status.lowercase().replaceFirstChar { it.uppercase() })
        }
    }
}

private fun relativeTime(epochMillis: Long): String {
    val instant = Instant.ofEpochMilli(epochMillis)
    val zone = ZoneId.systemDefault()
    val today = Instant.now().atZone(zone).toLocalDate()
    val date = instant.atZone(zone).toLocalDate()
    val time = DateTimeFormatter.ofPattern("h:mm a").withZone(zone).format(instant)
    return when {
        date == today -> "today, $time"
        date == today.minusDays(1) -> "yesterday, $time"
        else -> DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(zone).format(instant)
    }
}
