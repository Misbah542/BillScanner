package com.snaptab.app.ui.screen.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingUp
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
import com.snaptab.app.data.remote.dto.CategorySpendDto
import com.snaptab.app.ui.components.*
import com.snaptab.app.ui.theme.categoryColors
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * What you actually spent, and what that number means.
 *
 * The split between personal and "your share" is the point of the screen: two figures
 * that add up to your spending, next to a third — what left your account — which is
 * larger and is *not* spending, because the difference is a loan to whoever you covered.
 * Showing all three is the only way the big number is trustworthy.
 */
@Composable
fun MonthlyScreen(
    onBack: () -> Unit,
    onOpenInbox: () -> Unit,
    onAddExpense: () -> Unit,
    onOpenSettle: () -> Unit,
    viewModel: MonthlyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = state.summary

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = monthLabel(state.month),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = state.timezone,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::previousMonth) {
                        Icon(
                            Icons.Outlined.ChevronLeft,
                            contentDescription = stringResource(R.string.previous_month)
                        )
                    }
                    IconButton(onClick = viewModel::nextMonth, enabled = state.canGoForward) {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = stringResource(R.string.next_month)
                        )
                    }
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
                        text = stringResource(R.string.n_to_log, state.unmatchedAlerts),
                        onClick = onOpenInbox,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryButton(
                        text = stringResource(R.string.add_expense),
                        onClick = onAddExpense,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            item {
                ErrorBanner(
                    message = state.error,
                    onDismiss = viewModel::dismissError,
                    onRetry = viewModel::refresh,
                    offline = state.offline
                )
            }

            item {
                SnapCard {
                    Text(
                        text = stringResource(R.string.you_spent).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Amount(
                            minor = summary?.spentMinor ?: 0,
                            currency = summary?.currency ?: "INR",
                            size = 38.sp
                        )
                        Spacer(Modifier.width(9.dp))
                        ComparisonChip(
                            current = summary?.spentMinor ?: 0,
                            previous = summary?.previousSpentMinor,
                            currency = summary?.currency ?: "INR"
                        )
                    }
                    summary?.previousSpentMinor?.let { previous ->
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "last month: ${Money.formatCompact(previous, summary.currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    PersonalSharedBar(
                        personalMinor = summary?.personalMinor ?: 0,
                        sharedMinor = summary?.sharedShareMinor ?: 0
                    )
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SplitLegend(
                            color = MaterialTheme.colorScheme.primary,
                            label = stringResource(R.string.lens_personal),
                            amountMinor = summary?.personalMinor ?: 0,
                            currency = summary?.currency ?: "INR",
                            caption = stringResource(R.string.personal_count, summary?.personalCount ?: 0),
                            modifier = Modifier.weight(1f)
                        )
                        SplitLegend(
                            color = MaterialTheme.colorScheme.secondary,
                            label = stringResource(R.string.your_share_of_splits),
                            amountMinor = summary?.sharedShareMinor ?: 0,
                            currency = summary?.currency ?: "INR",
                            caption = stringResource(R.string.shared_count, summary?.sharedCount ?: 0),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // The loan, called what it is.
            if ((summary?.paidOutMinor ?: 0) > (summary?.spentMinor ?: 0)) {
                item {
                    val fronted = (summary!!.paidOutMinor - summary.spentMinor)
                    SnapCard(
                        background = MaterialTheme.colorScheme.primaryContainer,
                        borderColor = MaterialTheme.colorScheme.primaryContainer,
                        contentPadding = PaddingValues(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = stringResource(R.string.left_your_account),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            Amount(
                                minor = summary.paidOutMinor,
                                currency = summary.currency,
                                size = 16.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                inline = true
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.loan_explainer,
                                Money.formatCompact(fronted, summary.currency)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (summary.owedToYouMinor > 0) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = onOpenSettle, contentPadding = PaddingValues(0.dp)) {
                                Text(
                                    text = "${Money.formatCompact(summary.owedToYouMinor, summary.currency)} still owed to you",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionLabel(text = stringResource(R.string.where_it_went)) {
                    Text(
                        text = "personal + your share",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val buckets = summary?.byCategory.orEmpty()
            if (buckets.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.nothing_yet_title),
                        body = stringResource(R.string.nothing_yet_body)
                    )
                }
            } else {
                items(buckets, key = { it.slug }) { bucket ->
                    CategoryRow(bucket = bucket, currency = summary?.currency ?: "INR")
                }
            }

            if (state.trend.isNotEmpty()) {
                item {
                    SnapCard {
                        SectionLabel(text = stringResource(R.string.six_months))
                        Spacer(Modifier.height(12.dp))
                        TrendBars(
                            points = state.trend.map { it.month to it.spentMinor },
                            currentMonth = state.month
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ComparisonChip(current: Long, previous: Long?, currency: String) {
    if (previous == null || previous == 0L) return
    val difference = current - previous
    if (abs(difference) * 100 / previous < 3) {
        StatusChip(text = "about the same")
        return
    }
    val up = difference > 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (up) Icons.Outlined.TrendingUp else Icons.Outlined.TrendingDown,
            contentDescription = null,
            tint = if (up) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = Money.formatCompact(abs(difference), currency),
            style = MaterialTheme.typography.labelLarge,
            color = if (up) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PersonalSharedBar(personalMinor: Long, sharedMinor: Long) {
    val total = (personalMinor + sharedMinor).coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (personalMinor > 0) {
            Box(
                modifier = Modifier
                    .weight(personalMinor.toFloat())
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${personalMinor * 100 / total}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        if (sharedMinor > 0) {
            Box(
                modifier = Modifier
                    .weight(sharedMinor.toFloat())
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${sharedMinor * 100 / total}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
    }
}

@Composable
private fun SplitLegend(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    amountMinor: Long,
    currency: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(2.dp))
        Amount(minor = amountMinor, currency = currency, size = 16.sp, inline = true)
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CategoryRow(bucket: CategorySpendDto, currency: String) {
    val colors = categoryColors(bucket.slug)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(colors.tint),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.fg)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = bucket.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Amount(minor = bucket.spentMinor, currency = currency, size = 14.sp, inline = true)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(bucket.shareBp / 10_000f)
                            .fillMaxHeight()
                            .background(colors.fg)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${bucket.shareBp / 100}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TrendBars(points: List<Pair<String, Long>>, currentMonth: String) {
    val peak = points.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        points.forEach { (month, spent) ->
            val isCurrent = month == currentMonth
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(((spent.toFloat() / peak) * 58f).coerceAtLeast(6f).dp)
                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                        .background(
                            if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = shortMonth(month),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

private fun monthLabel(month: String): String = runCatching {
    val parsed = YearMonth.parse(month)
    "${parsed.month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())} ${parsed.year}"
}.getOrDefault(month)

private fun shortMonth(month: String): String = runCatching {
    YearMonth.parse(month).month.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
}.getOrDefault(month.takeLast(2))
