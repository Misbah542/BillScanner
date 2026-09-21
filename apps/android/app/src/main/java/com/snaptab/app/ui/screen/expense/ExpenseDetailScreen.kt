package com.snaptab.app.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snaptab.app.R
import com.snaptab.app.core.Money
import com.snaptab.app.data.remote.dto.ExpenseDto
import com.snaptab.app.data.remote.dto.ExpenseShareDto
import com.snaptab.app.ui.components.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One expense, in either of its two shapes.
 *
 * A personal expense is the short version: amount, merchant, category, and an offer to
 * split it if it turns out other people were involved. A shared one adds the people, what
 * each owes, and the ways to chase it.
 */
@Composable
fun ExpenseDetailScreen(
    expenseId: String,
    onBack: () -> Unit,
    onEditSplit: (String) -> Unit,
    onSplitByItem: (String) -> Unit,
    onDeleted: () -> Unit,
    viewModel: ExpenseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showGroupPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(expenseId) { viewModel.load(expenseId) }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }

    state.shareLink?.let { link ->
        AlertDialog(
            onDismissRequest = viewModel::dismissShareLink,
            title = { Text(stringResource(R.string.share_this_tab)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.share_explainer),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(text = link.url, style = MaterialTheme.typography.titleSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(link.url))
                    viewModel.dismissShareLink()
                }) {
                    Text(stringResource(R.string.copy))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissShareLink) {
                    Text(stringResource(R.string.done))
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this expense?") },
            text = { Text("It comes out of your monthly total and any balance it created.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showGroupPicker) {
        ModalBottomSheet(onDismissRequest = { showGroupPicker = false }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
                Text("Put this on a group", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Only groups you already have.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    state.groups.forEach { group ->
                        SecondaryButton(
                            text = group.name,
                            onClick = {
                                showGroupPicker = false
                                viewModel.moveToGroup(group.id)
                                onEditSplit(expenseId)
                            }
                        )
                    }
                    if (state.groups.isEmpty()) {
                        Text(
                            text = "You have no groups yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    val expense = state.expense
    Scaffold(
        bottomBar = {
            if (expense != null) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SecondaryButton(
                            text = stringResource(R.string.share),
                            onClick = { viewModel.createShareLink() },
                            enabled = !state.working,
                            modifier = Modifier.weight(1f),
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                        PrimaryButton(
                            text = if (state.isPersonal) {
                                stringResource(R.string.split_it)
                            } else {
                                stringResource(R.string.change)
                            },
                            onClick = { onEditSplit(expenseId) },
                            enabled = !state.working,
                            modifier = Modifier.weight(1.1f)
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (expense == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                if (state.loading) {
                    CircularProgressIndicator()
                } else {
                    EmptyState(
                        title = stringResource(R.string.error_unknown),
                        body = state.error ?: "",
                        action = { SecondaryButton(stringResource(R.string.back), onBack) }
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 12.dp)
        ) {
            item {
                ExpenseHeader(
                    expense = expense,
                    onBack = onBack,
                    onDelete = { confirmDelete = true }
                )
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Spacer(Modifier.height(14.dp))

                    ErrorBanner(
                        message = state.error,
                        onDismiss = viewModel::dismissError,
                        offline = state.offline
                    )

                    expense.matchedAlerts.firstOrNull()?.let { alert ->
                        SnapCard(
                            contentPadding = PaddingValues(12.dp),
                            background = MaterialTheme.colorScheme.primaryContainer,
                            borderColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.CreditCard,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(
                                        R.string.matched_to_alert,
                                        "${alert.bankName ?: ""} ··${alert.accountMask ?: ""}".trim(),
                                        Money.format(alert.amountMinor, expense.currency)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            if (expense.kind == "PERSONAL") {
                item { PersonalExtras(expense, onSplit = { showGroupPicker = true }, onEditSplit = { onEditSplit(expenseId) }) }
            } else {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        SnapCard {
                            SectionLabel(
                                text = stringResource(
                                    R.string.split_n_ways,
                                    expense.shares.size,
                                    (expense.splitMethod ?: "EQUAL").lowercase()
                                )
                            ) {
                                TextButton(onClick = { onSplitByItem(expenseId) }) {
                                    Text(stringResource(R.string.split_by_item))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            expense.shares.forEach { share ->
                                ShareRow(
                                    share = share,
                                    currency = expense.currency,
                                    isPayer = share.userId == expense.paidBy.id,
                                    canChase = expense.viewer.isPayer && share.userId != expense.paidBy.id,
                                    onRemind = { viewModel.remind(share.userId) },
                                    onMarkPaid = {
                                        viewModel.markShareSettled(
                                            share.userId,
                                            share.amountMinor - share.paidMinor
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (expense.items.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        SectionLabel(text = stringResource(R.string.n_items, expense.items.size))
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(expense.items, key = { it.id ?: it.name }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.quantityMilli != 1000) {
                            Text(
                                text = "×${item.quantityMilli / 1000}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Amount(
                            minor = item.amountMinor,
                            currency = expense.currency,
                            size = 14.sp,
                            inline = true,
                            compact = false
                        )
                    }
                }
            }

            if (expense.taxLines.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        SnapCard(background = MaterialTheme.colorScheme.surfaceVariant) {
                            TotalRow(stringResource(R.string.item_total), expense.totals.itemTotalMinor, expense.currency)
                            if (expense.totals.serviceChargeMinor != 0L) {
                                TotalRow(stringResource(R.string.service_charge), expense.totals.serviceChargeMinor, expense.currency)
                            }
                            if (expense.totals.taxMinor != 0L) {
                                TotalRow(stringResource(R.string.taxes), expense.totals.taxMinor, expense.currency)
                            }
                            if (expense.totals.discountMinor != 0L) {
                                TotalRow(stringResource(R.string.discount), -expense.totals.discountMinor, expense.currency)
                            }
                            if (expense.totals.roundOffMinor != 0L) {
                                TotalRow(stringResource(R.string.round_off), expense.totals.roundOffMinor, expense.currency)
                            }
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.net_amount),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Amount(
                                    minor = expense.totals.totalMinor,
                                    currency = expense.currency,
                                    size = 20.sp,
                                    compact = false
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun ExpenseHeader(expense: ExpenseDto, onBack: () -> Unit, onDelete: () -> Unit) {
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
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                CategoryChip(
                    slug = expense.category?.slug,
                    name = expense.category?.name,
                    dark = false
                )
                if (expense.kind == "PERSONAL") {
                    StatusChip(
                        text = stringResource(R.string.personal_expense),
                        container = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.18f),
                        content = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
                Text(
                    text = formatWhen(expense.occurredAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.66f)
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = expense.merchantName ?: stringResource(R.string.add_expense),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.net_amount).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f)
                    )
                    Amount(
                        minor = expense.totals.totalMinor,
                        currency = expense.currency,
                        size = 34.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        compact = false
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(
                            if (expense.kind == "PERSONAL") R.string.all_yours else R.string.your_share
                        ).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f)
                    )
                    Amount(
                        minor = expense.viewer.yourShareMinor,
                        currency = expense.currency,
                        size = 20.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        compact = false
                    )
                }
            }
        }
    }
}

/** What a personal expense offers instead of a split: the chance to become one. */
@Composable
private fun PersonalExtras(expense: ExpenseDto, onSplit: () -> Unit, onEditSplit: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        SnapCard(borderColor = MaterialTheme.colorScheme.outline) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CallSplit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "Turns out it was shared?",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = "Add people and this becomes a split tab. Only your share stays in your monthly total.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = "Split with people",
                    onClick = onEditSplit,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = "On a group",
                    onClick = onSplit,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ShareRow(
    share: ExpenseShareDto,
    currency: String,
    isPayer: Boolean,
    canChase: Boolean,
    onRemind: () -> Unit,
    onMarkPaid: () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = share.user?.name, imageUrl = share.user?.avatarUrl, size = 34.dp)
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = share.user?.name ?: "Someone",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                val (label, color) = when {
                    isPayer -> stringResource(R.string.you_paid) to MaterialTheme.colorScheme.primary
                    share.settledAt != null -> stringResource(R.string.settled) to MaterialTheme.colorScheme.onSurfaceVariant
                    share.user?.pending == true -> stringResource(R.string.invite_pending) to MaterialTheme.colorScheme.tertiary
                    else -> stringResource(R.string.owes_you) to MaterialTheme.colorScheme.secondary
                }
                Text(text = label, style = MaterialTheme.typography.bodySmall, color = color)
            }
            Amount(minor = share.amountMinor, currency = currency, size = 15.sp, inline = true, compact = false)
        }

        if (canChase && share.settledAt == null) {
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRemind,
                    shape = RoundedCornerShape(11.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                ) {
                    Text("Remind", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = onMarkPaid,
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                ) {
                    Text(stringResource(R.string.mark_received), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, minor: Long, currency: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Amount(minor = minor, currency = currency, size = 13.sp, inline = true, compact = false)
    }
}

private fun formatWhen(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("EEE d MMM · h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(iso))
}.getOrDefault("")
