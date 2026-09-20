package com.snaptab.app.ui.screen.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptab.app.R
import com.snaptab.app.core.Money
import com.snaptab.app.data.remote.dto.ParsedReceiptItemDto
import com.snaptab.app.ui.components.*
import com.snaptab.app.ui.theme.categoryColors

/**
 * What the server read, before it becomes an expense.
 *
 * Everything is editable, and the confidence and warnings the parser reported are shown
 * rather than hidden, because a bill read at 60% confidence should be checked and a bill
 * read at 96% should not need checking at all.
 *
 * The two buttons are the real decision: a scanned receipt is not automatically shared.
 */
@Composable
fun ReviewScreen(
    state: ScanUiState,
    onMerchantChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onItemChange: (Int, ParsedReceiptItemDto) -> Unit,
    onItemRemove: (Int) -> Unit,
    onKeepPersonal: () -> Unit,
    onSplit: (String?) -> Unit,
    onRescan: () -> Unit,
    onDismissError: () -> Unit
) {
    var showGroupPicker by remember { mutableStateOf(false) }

    if (showGroupPicker) {
        ModalBottomSheet(onDismissRequest = { showGroupPicker = false }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
                Text(stringResource(R.string.split_the_tab), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Pick a group you already have, or split with people from the next screen.",
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
                                onSplit(group.id)
                            }
                        )
                    }
                    SecondaryButton(
                        text = "No group — pick people",
                        onClick = {
                            showGroupPicker = false
                            onSplit(null)
                        }
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.check_the_scan)) },
                actions = {
                    TextButton(onClick = onRescan) { Text(stringResource(R.string.rescan)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SecondaryButton(
                        text = stringResource(R.string.keep_as_personal),
                        onClick = onKeepPersonal,
                        enabled = !state.saving,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryButton(
                        text = stringResource(R.string.split_it),
                        onClick = { showGroupPicker = true },
                        enabled = !state.saving,
                        loading = state.saving,
                        modifier = Modifier.weight(1.1f)
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            item {
                ErrorBanner(
                    message = state.error,
                    onDismiss = onDismissError,
                    offline = state.offline
                )
            }

            if (state.warnings.isNotEmpty()) {
                item {
                    SnapCard(
                        background = MaterialTheme.colorScheme.tertiaryContainer,
                        borderColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentPadding = PaddingValues(13.dp)
                    ) {
                        Row {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                state.warnings.forEach { warning ->
                                    Text(
                                        text = warning,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SnapCard {
                    Text(
                        text = stringResource(R.string.merchant).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = state.merchant,
                        onValueChange = onMerchantChange,
                        singleLine = true,
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.result?.confidence?.let { confidence ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Read from the receipt, ${(confidence * 100).toInt()}% confident",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                SnapCard {
                    SectionLabel(text = stringResource(R.string.category)) {
                        StatusChip(
                            text = stringResource(R.string.suggested),
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        state.suggestions.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                row.forEach { suggestion ->
                                    val colors = categoryColors(suggestion.slug)
                                    val selected = suggestion.slug == state.categorySlug
                                    Surface(
                                        onClick = { onCategoryChange(suggestion.slug) },
                                        shape = RoundedCornerShape(19.dp),
                                        color = if (selected) colors.tint else MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(
                                            if (selected) 2.dp else 1.dp,
                                            if (selected) colors.fg else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(colors.fg)
                                            )
                                            Spacer(Modifier.width(7.dp))
                                            Text(
                                                text = suggestion.name,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (selected) colors.fg else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (suggestion.confidence > 0) {
                                                Text(
                                                    text = "${(suggestion.confidence * 100).toInt()}%",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    // Why, not just what — the classifier explains itself.
                    state.suggestions.firstOrNull()?.reasons?.firstOrNull()?.let { reason ->
                        Spacer(Modifier.height(9.dp))
                        Text(
                            text = "Matched on $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SectionLabel(text = stringResource(R.string.items_read, state.items.size))
            }

            itemsIndexed(state.items, key = { index, item -> "$index-${item.name}" }) { index, item ->
                SnapCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(
                                    R.string.qty_times,
                                    (item.quantityMilli / 1000.0).toString().removeSuffix(".0"),
                                    Money.format(item.unitPriceMinor)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Amount(
                            minor = item.amountMinor,
                            currency = state.result?.currency ?: "INR",
                            size = 14.sp,
                            inline = true,
                            compact = false
                        )
                        IconButton(onClick = { onItemRemove(index) }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove ${item.name}",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            state.totals?.let { totals ->
                item {
                    SnapCard(background = MaterialTheme.colorScheme.surfaceVariant) {
                        TotalsLine(stringResource(R.string.item_total), state.itemsSum)
                        if (totals.serviceChargeMinor != 0L) {
                            TotalsLine(stringResource(R.string.service_charge), totals.serviceChargeMinor)
                        }
                        if (totals.taxMinor != 0L) {
                            TotalsLine(stringResource(R.string.taxes), totals.taxMinor)
                        }
                        if (totals.roundOffMinor != 0L) {
                            TotalsLine(stringResource(R.string.round_off), totals.roundOffMinor)
                        }
                        Spacer(Modifier.height(9.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(9.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.net_amount),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Amount(
                                minor = totals.totalMinor,
                                currency = state.result?.currency ?: "INR",
                                size = 22.sp,
                                compact = false
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun TotalsLine(label: String, minor: Long) {
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
        Amount(minor = minor, size = 13.sp, inline = true, compact = false)
    }
}
