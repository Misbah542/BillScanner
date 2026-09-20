package com.snaptab.app.ui.screen.settle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snaptab.app.R
import com.snaptab.app.data.remote.dto.PersonBalanceDto
import com.snaptab.app.ui.components.*
import kotlin.math.abs

/**
 * Settling up. The list is people, not tabs, because nobody wants to pay back nine
 * separate dinners — the server has already collapsed the web of debts into the fewest
 * transfers that clear it.
 */
@Composable
fun SettleScreen(
    viewModel: SettleViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val collect = state.balance?.people.orEmpty().filter { it.netMinor > 0 }
    val pay = state.balance?.people.orEmpty().filter { it.netMinor < 0 }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.settle_up),
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = stringResource(R.string.settle_transfers),
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

        if (collect.isEmpty() && pay.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.all_square_title),
                    body = stringResource(R.string.all_square_body)
                )
            }
        }

        if (collect.isNotEmpty()) {
            item { SectionLabel(text = stringResource(R.string.you_collect)) }
            items(collect, key = { it.userId }) { person ->
                PersonCard(
                    person = person,
                    currency = state.balance?.currency ?: "INR",
                    working = state.working,
                    primaryLabel = stringResource(R.string.mark_received),
                    secondaryLabel = stringResource(R.string.remind_by_email),
                    onPrimary = { viewModel.markReceived(person.userId, person.netMinor) },
                    onSecondary = { viewModel.remind(person.userId) },
                    accent = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (pay.isNotEmpty()) {
            item { SectionLabel(text = stringResource(R.string.you_pay)) }
            items(pay, key = { it.userId }) { person ->
                PersonCard(
                    person = person,
                    currency = state.balance?.currency ?: "INR",
                    working = state.working,
                    primaryLabel = stringResource(R.string.mark_paid),
                    secondaryLabel = stringResource(R.string.pay_by_upi),
                    onPrimary = { viewModel.markPaid(person.userId, abs(person.netMinor)) },
                    onSecondary = { viewModel.markPaid(person.userId, abs(person.netMinor)) },
                    accent = MaterialTheme.colorScheme.secondary
                )
            }
        }

        item {
            SnapCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CreditCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = stringResource(R.string.auto_settle_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    text = stringResource(R.string.auto_settle_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PersonCard(
    person: PersonBalanceDto,
    currency: String,
    working: Boolean,
    primaryLabel: String,
    secondaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    accent: androidx.compose.ui.graphics.Color
) {
    SnapCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = person.name, size = 38.dp)
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = person.name ?: "Someone",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (person.status == "INVITED") {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.invite_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Amount(
                minor = abs(person.netMinor),
                currency = currency,
                size = 22.sp,
                color = accent,
                compact = false
            )
        }

        Spacer(Modifier.height(11.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onSecondary,
                enabled = !working,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).heightIn(min = 42.dp)
            ) {
                Text(secondaryLabel, style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = onPrimary,
                enabled = !working,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.weight(1f).heightIn(min = 42.dp)
            ) {
                Text(primaryLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
