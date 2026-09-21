package com.snaptab.app.ui.screen.groups

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snaptab.app.R
import com.snaptab.app.core.Money
import com.snaptab.app.ui.components.*

/**
 * A group: who is in it, who owes whom, and what has happened.
 */
@Composable
fun GroupDetailScreen(
    groupId: String,
    onBack: () -> Unit,
    onOpenExpense: (String) -> Unit,
    onAddExpense: (String) -> Unit,
    onSettleUp: () -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var contactsDraft by remember { mutableStateOf("") }

    LaunchedEffect(groupId) { viewModel.load(groupId) }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.add_people)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.invite_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = contactsDraft,
                        onValueChange = { contactsDraft = it },
                        label = { Text(stringResource(R.string.email_or_phone)) },
                        placeholder = { Text("comma separated") },
                        minLines = 2,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addMembers(
                            contactsDraft.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                        )
                        contactsDraft = ""
                        showAdd = false
                    },
                    enabled = contactsDraft.trim().length >= 3 && !state.adding
                ) {
                    Text(stringResource(R.string.invite))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.group?.name ?: stringResource(R.string.loading)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(
                            Icons.Outlined.PersonAdd,
                            contentDescription = stringResource(R.string.add_people)
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
                        text = stringResource(R.string.settle_up),
                        onClick = onSettleUp,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryButton(
                        text = stringResource(R.string.add_expense),
                        onClick = { onAddExpense(groupId) },
                        modifier = Modifier.weight(1f)
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
                    onDismiss = viewModel::dismissError,
                    onRetry = { viewModel.load(groupId) },
                    offline = state.offline
                )
            }

            item {
                SnapCard {
                    Text(
                        text = stringResource(R.string.net_position).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Amount(
                        minor = state.balance?.netMinor ?: 0,
                        currency = state.balance?.currency ?: "INR",
                        size = 27.sp,
                        signed = true,
                        color = if ((state.balance?.netMinor ?: 0) >= 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    AvatarRow(names = state.group?.members.orEmpty().map { it.user.name })
                }
            }

            val people = state.balance?.people.orEmpty()
            if (people.isNotEmpty()) {
                item { SectionLabel(text = stringResource(R.string.who_owes_whom)) }
                items(people, key = { it.userId }) { person ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Avatar(name = person.name, size = 32.dp)
                        Spacer(Modifier.width(11.dp))
                        Text(
                            text = if (person.netMinor > 0) {
                                "${person.name ?: "Someone"} ${stringResource(R.string.owes_you).lowercase()}"
                            } else {
                                "you owe ${person.name ?: "someone"}"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Amount(
                            minor = kotlin.math.abs(person.netMinor),
                            currency = state.balance?.currency ?: "INR",
                            size = 15.sp,
                            inline = true,
                            color = if (person.netMinor > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                    }
                }
            }

            if (state.activity.isNotEmpty()) {
                item { SectionLabel(text = stringResource(R.string.recent_activity)) }
                items(state.activity, key = { it.expense?.id ?: it.settlement?.id ?: it.at }) { entry ->
                    entry.expense?.let { expense ->
                        Surface(
                            onClick = { onOpenExpense(expense.id) },
                            shape = RoundedCornerShape(15.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = expense.merchantName ?: "Expense",
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "${expense.paidBy.name ?: "Someone"} paid",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Amount(
                                        minor = expense.totalMinor,
                                        currency = expense.currency,
                                        size = 14.sp,
                                        inline = true
                                    )
                                    Text(
                                        text = "your share ${Money.formatCompact(expense.yourShareMinor, expense.currency)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    entry.settlement?.let { settlement ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            StatusChip(
                                text = stringResource(R.string.settled),
                                container = MaterialTheme.colorScheme.primaryContainer,
                                content = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "${settlement.fromUser?.name ?: "Someone"} → ${settlement.toUser?.name ?: "someone"}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Amount(
                                minor = settlement.amountMinor,
                                currency = settlement.currency,
                                size = 14.sp,
                                inline = true
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
