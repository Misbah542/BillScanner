package com.snaptab.app.ui.screen.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.snaptab.app.data.local.GroupEntity
import com.snaptab.app.ui.components.*

/**
 * Groups, each with the one number that matters: whether you are up or down with them.
 */
@Composable
fun GroupsScreen(
    onOpenGroup: (String) -> Unit,
    viewModel: GroupsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var contactsDraft by remember { mutableStateOf("") }

    LaunchedEffect(state.createdGroupId) {
        state.createdGroupId?.let { id ->
            viewModel.consumeCreated()
            onOpenGroup(id)
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.create_group)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text(stringResource(R.string.group_name)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = contactsDraft,
                        onValueChange = { contactsDraft = it },
                        label = { Text(stringResource(R.string.email_or_phone)) },
                        placeholder = { Text("comma separated") },
                        singleLine = false,
                        minLines = 2,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.invite_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.create(
                            nameDraft,
                            contactsDraft.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                        )
                        nameDraft = ""
                        contactsDraft = ""
                        showCreate = false
                    },
                    enabled = nameDraft.trim().isNotEmpty() && !state.creating
                ) {
                    Text(stringResource(R.string.create_group))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.groups),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { showCreate = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface
                    ),
                    modifier = Modifier.heightIn(min = 44.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.new_group))
                }
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

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.across_every_group).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Amount(
                            minor = state.balance?.owedToYouMinor ?: 0,
                            currency = state.balance?.currency ?: "INR",
                            size = 29.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.owed_to_you),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.66f)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Amount(
                            minor = state.balance?.owedByYouMinor ?: 0,
                            currency = state.balance?.currency ?: "INR",
                            size = 29.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = stringResource(R.string.you_owe),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.66f)
                        )
                    }
                }
            }
        }

        if (state.groups.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.start_a_group),
                    body = stringResource(R.string.groups_explainer),
                    action = {
                        SecondaryButton(
                            text = stringResource(R.string.start_a_group),
                            onClick = { showCreate = true },
                            modifier = Modifier.widthIn(max = 220.dp)
                        )
                    }
                )
            }
        } else {
            items(state.groups, key = { it.id }) { group ->
                GroupRow(group = group, onClick = { onOpenGroup(group.id) })
            }
        }
    }
}

@Composable
private fun GroupRow(group: GroupEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initialsOf(group.name),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(stringResource(R.string.n_members, group.memberCount))
                        if (group.pendingCount > 0) {
                            append(" · ")
                            append(stringResource(R.string.n_invites_pending, group.pendingCount))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Amount(
                    minor = group.netMinor,
                    currency = group.currency,
                    size = 15.sp,
                    signed = group.netMinor != 0L,
                    inline = true,
                    color = when {
                        group.netMinor > 0 -> MaterialTheme.colorScheme.primary
                        group.netMinor < 0 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        group.netMinor > 0 -> stringResource(R.string.you_get)
                        group.netMinor < 0 -> stringResource(R.string.you_owe)
                        else -> stringResource(R.string.all_square)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
