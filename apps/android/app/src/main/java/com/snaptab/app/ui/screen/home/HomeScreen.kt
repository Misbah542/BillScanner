package com.snaptab.app.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snaptab.app.R
import com.snaptab.app.core.Money
import com.snaptab.app.data.local.ExpenseEntity
import com.snaptab.app.ui.components.*
import com.snaptab.app.ui.theme.categoryColors
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * Home. Three things, in order of how often they matter: what you are owed and owe,
 * what you have spent this month, and the tabs you touched most recently.
 *
 * The spend card switches between All, Personal and Your share, because those are three
 * genuinely different numbers and conflating them is what makes a spending figure
 * useless — "All" is your own spending, "Your share" is only the split part of it, and
 * money you fronted for other people is in neither.
 */
@Composable
fun HomeScreen(
    onOpenExpense: (String) -> Unit,
    onOpenInbox: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenMonthly: () -> Unit,
    onAddExpense: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                BadgedIconButton(
                    count = state.unmatchedAlerts,
                    onClick = onOpenInbox,
                    contentDescription = stringResource(R.string.nav_inbox)
                )
                Spacer(Modifier.width(8.dp))
                Avatar(
                    name = null,
                    size = 44.dp,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.clickable(onClick = onOpenProfile)
                )
            }
        }

        item {
            ErrorBanner(
                message = state.error,
                onDismiss = viewModel::dismissError,
                onRetry = viewModel::refresh,
                offline = state.offline
            )
        }

        if (state.offline) {
            item {
                StatusChip(
                    text = stringResource(R.string.error_offline),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        item {
            BalanceTiles(
                owedToYou = state.balance?.owedToYouMinor ?: 0,
                owedByYou = state.balance?.owedByYouMinor ?: 0,
                peopleOwingYou = state.balance?.people?.count { it.netMinor > 0 } ?: 0,
                currency = state.balance?.currency ?: "INR"
            )
        }

        item {
            SpendCard(
                state = state,
                onLensChange = viewModel::setLens,
                onOpenDetails = onOpenMonthly
            )
        }

        item {
            SectionLabel(text = stringResource(R.string.recent_tabs)) {
                TextButton(onClick = onOpenMonthly) {
                    Text(stringResource(R.string.see_all))
                }
            }
        }

        if (state.expenses.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.nothing_yet_title),
                    body = stringResource(R.string.nothing_yet_body),
                    action = {
                        SecondaryButton(
                            text = stringResource(R.string.add_expense),
                            onClick = onAddExpense,
                            modifier = Modifier.widthIn(max = 220.dp)
                        )
                    }
                )
            }
        } else {
            items(state.expenses, key = { it.id }) { expense ->
                ExpenseRow(expense = expense, onClick = { onOpenExpense(expense.id) })
            }
        }
    }
}

@Composable
private fun BadgedIconButton(count: Int, onClick: () -> Unit, contentDescription: String) {
    Box {
        FilledTonalIconButton(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp)
            )
        }
        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-6).dp, y = 6.dp)
                    .size(9.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.secondary)
            )
        }
    }
}

@Composable
private fun BalanceTiles(
    owedToYou: Long,
    owedByYou: Long,
    peopleOwingYou: Int,
    currency: String
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(
            label = stringResource(R.string.owed_to_you),
            amountMinor = owedToYou,
            currency = currency,
            caption = if (peopleOwingYou > 0) {
                stringResource(R.string.across_people, peopleOwingYou)
            } else {
                stringResource(R.string.all_square)
            },
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            accent = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Tile(
            label = stringResource(R.string.you_owe),
            amountMinor = owedByYou,
            currency = currency,
            caption = if (owedByYou == 0L) stringResource(R.string.all_square) else "",
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            accent = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Tile(
    label: String,
    amountMinor: Long,
    currency: String,
    caption: String,
    container: Color,
    content: Color,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = content
        )
        Spacer(Modifier.height(4.dp))
        Amount(minor = amountMinor, currency = currency, size = 27.sp, color = accent)
        if (caption.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SpendCard(
    state: HomeUiState,
    onLensChange: (SpendLens) -> Unit,
    onOpenDetails: () -> Unit
) {
    val summary = state.summary
    val monthName = runCatching {
        YearMonth.parse(state.month).month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())
    }.getOrDefault("")

    SnapCard {
        SectionLabel(text = stringResource(R.string.spend_in_month, monthName)) {
            TextButton(onClick = onOpenDetails) { Text(stringResource(R.string.details)) }
        }

        Spacer(Modifier.height(9.dp))
        SegmentedTabs(
            options = listOf(
                stringResource(R.string.lens_all),
                stringResource(R.string.lens_personal),
                stringResource(R.string.lens_your_share)
            ),
            selectedIndex = SpendLens.entries.indexOf(state.lens),
            onSelect = { onLensChange(SpendLens.entries[it]) }
        )

        Spacer(Modifier.height(12.dp))
        Amount(
            minor = summary?.spentMinor ?: 0,
            currency = summary?.currency ?: "INR",
            size = 32.sp
        )

        val caption = when (state.lens) {
            SpendLens.ALL -> summary?.let {
                "${Money.formatCompact(it.personalMinor, it.currency)} personal + " +
                    "${Money.formatCompact(it.sharedShareMinor, it.currency)} your share"
            }
            SpendLens.PERSONAL -> summary?.let {
                stringResource(R.string.personal_count, it.personalCount)
            }
            SpendLens.SHARED -> summary?.let {
                stringResource(R.string.shared_count, it.sharedCount)
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val buckets = summary?.byCategory.orEmpty()
        if (buckets.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ProportionBar(
                segments = buckets.take(5).map { bucket ->
                    categoryColors(bucket.slug).fg to bucket.spentMinor.toFloat().coerceAtLeast(1f)
                }
            )
            Spacer(Modifier.height(10.dp))
            FlowRowLegend(
                entries = buckets.take(3).map { bucket ->
                    Triple(
                        categoryColors(bucket.slug).fg,
                        bucket.name,
                        "${bucket.shareBp / 100}%"
                    )
                }
            )
        }
    }
}

@Composable
private fun FlowRowLegend(entries: List<Triple<Color, String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { (color, label, share) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(color)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "$label $share",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One row of the recent list. A personal expense is marked as such, because the
 * difference between "₹1,198 all yours" and "₹2,575 of which you owe ₹644" is the whole
 * point of the distinction.
 */
@Composable
fun ExpenseRow(expense: ExpenseEntity, onClick: () -> Unit) {
    val colors = categoryColors(expense.categorySlug)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.tint),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = com.snaptab.app.ui.components.initialsOf(expense.merchantName),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.fg
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.merchantName ?: stringResource(R.string.add_expense),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CategoryChip(slug = expense.categorySlug, name = expense.categoryName)
                    if (expense.kind == "PERSONAL") {
                        StatusChip(text = stringResource(R.string.personal_expense))
                    }
                    expense.groupName?.let { group ->
                        Text(
                            text = group,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Amount(
                    minor = expense.totalMinor,
                    currency = expense.currency,
                    size = 15.sp,
                    inline = true
                )
                Spacer(Modifier.height(3.dp))
                val (statusText, statusColor) = when {
                    expense.kind == "PERSONAL" ->
                        stringResource(R.string.all_yours) to MaterialTheme.colorScheme.onSurfaceVariant
                    expense.youAreOwedMinor > 0 ->
                        "${stringResource(R.string.you_get)} ${Money.formatCompact(expense.youAreOwedMinor, expense.currency)}" to
                            MaterialTheme.colorScheme.primary
                    expense.youOweMinor > 0 ->
                        "${stringResource(R.string.you_owe)} ${Money.formatCompact(expense.youOweMinor, expense.currency)}" to
                            MaterialTheme.colorScheme.secondary
                    else -> stringResource(R.string.settled) to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1
                )
            }
        }
    }
}
