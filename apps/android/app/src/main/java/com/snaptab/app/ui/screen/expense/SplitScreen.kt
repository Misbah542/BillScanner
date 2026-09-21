package com.snaptab.app.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snaptab.app.R
import com.snaptab.app.core.Money
import com.snaptab.app.ui.components.*
import com.snaptab.app.ui.theme.InlineAmountStyle
import kotlin.math.absoluteValue

/**
 * Splitting a tab four ways, and the three other ways people actually split things.
 *
 * The tally at the bottom is the important part: EXACT and PERCENT are the user's own
 * arithmetic and can be wrong, so the screen says how far off it is and refuses to save
 * until it adds up, rather than letting the server reject it after the fact.
 */
@Composable
fun SplitScreen(
    expenseId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onSplitByItem: (String) -> Unit,
    viewModel: SplitViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAddPerson by remember { mutableStateOf(false) }
    var contactDraft by remember { mutableStateOf("") }

    LaunchedEffect(expenseId) { viewModel.load(expenseId) }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    if (showAddPerson) {
        AlertDialog(
            onDismissRequest = { showAddPerson = false },
            title = { Text(stringResource(R.string.add_people)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.invite_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = contactDraft,
                        onValueChange = { contactDraft = it },
                        label = { Text(stringResource(R.string.email_or_phone)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addPerson(contactDraft)
                        contactDraft = ""
                        showAddPerson = false
                    },
                    enabled = contactDraft.trim().length >= 3
                ) {
                    Text(stringResource(R.string.invite))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPerson = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.split_the_tab),
                            style = MaterialTheme.typography.titleLarge
                        )
                        state.expense?.let { expense ->
                            Text(
                                text = "${expense.merchantName.orEmpty()} · " +
                                    Money.format(expense.totals.totalMinor, expense.currency),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
                    IconButton(onClick = { showAddPerson = true }) {
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
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    PrimaryButton(
                        text = stringResource(R.string.save_this_split),
                        onClick = viewModel::save,
                        enabled = state.canSave,
                        loading = state.saving
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SegmentedTabs(
                    options = listOf(
                        stringResource(R.string.method_equal),
                        stringResource(R.string.method_unequal),
                        stringResource(R.string.method_percent),
                        stringResource(R.string.method_shares)
                    ),
                    selectedIndex = SplitMethod.entries.indexOf(state.method),
                    onSelect = { viewModel.setMethod(SplitMethod.entries[it]) }
                )
            }

            item {
                Text(
                    text = stringResource(
                        when (state.method) {
                            SplitMethod.EQUAL -> R.string.hint_equal
                            SplitMethod.EXACT -> R.string.hint_unequal
                            SplitMethod.PERCENT -> R.string.hint_percent
                            SplitMethod.SHARES -> R.string.hint_shares
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                ErrorBanner(
                    message = state.error,
                    onDismiss = viewModel::dismissError,
                    offline = state.offline
                )
            }

            items(state.rows, key = { it.userId }) { row ->
                SplitRowItem(
                    row = row,
                    method = state.method,
                    computedMinor = state.computed[row.userId] ?: 0,
                    currency = state.expense?.currency ?: "INR",
                    onToggle = { viewModel.toggleIncluded(row.userId) },
                    onInput = { viewModel.setInput(row.userId, it) },
                    onRemove = { viewModel.removePerson(row.userId) }
                )
            }

            item {
                TextButton(
                    onClick = { onSplitByItem(expenseId) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.assign_item_by_item))
                }
            }

            item { TallyCard(state = state) }

            item {
                Text(
                    text = stringResource(R.string.remainder_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SplitRowItem(
    row: SplitRow,
    method: SplitMethod,
    computedMinor: Long,
    currency: String,
    onToggle: () -> Unit,
    onInput: (String) -> Unit,
    onRemove: () -> Unit
) {
    SnapCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = row.name, size = 36.dp)
            Spacer(Modifier.width(11.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = if (row.pending) stringResource(R.string.invite_pending) else row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            when {
                !row.included -> IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "Include ${row.name}",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }

                method == SplitMethod.EQUAL -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Amount(minor = computedMinor, currency = currency, size = 15.sp, inline = true, compact = false)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = "Leave ${row.name} out",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                method == SplitMethod.EXACT -> InputPill(
                    prefix = "₹",
                    value = row.input,
                    onValueChange = onInput,
                    label = stringResource(R.string.amount_for, row.name),
                    width = 86.dp,
                    keyboard = KeyboardType.Decimal
                )

                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Amount(minor = computedMinor, currency = currency, size = 12.sp, inline = true)
                    Spacer(Modifier.width(8.dp))
                    InputPill(
                        suffix = if (method == SplitMethod.PERCENT) "%" else "×",
                        value = row.input,
                        onValueChange = onInput,
                        label = if (method == SplitMethod.PERCENT) {
                            stringResource(R.string.percent_for, row.name)
                        } else {
                            stringResource(R.string.shares_for, row.name)
                        },
                        width = 58.dp,
                        keyboard = KeyboardType.Number
                    )
                }
            }

            if (row.pending) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.remove_person, row.name),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InputPill(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    width: androidx.compose.ui.unit.Dp,
    keyboard: KeyboardType,
    prefix: String? = null,
    suffix: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp)
    ) {
        if (prefix != null) {
            Text(
                text = prefix,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(3.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = InlineAmountStyle.copy(
                fontSize = 15.sp,
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurface
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .width(width)
                .semanticsLabel(label)
        )
        if (suffix != null) {
            Spacer(Modifier.width(2.dp))
            Text(
                text = suffix,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Does it add up? For EQUAL and SHARES it always does. For EXACT and PERCENT this is the
 * only thing standing between the user and a rejected save.
 */
@Composable
private fun TallyCard(state: SplitUiState) {
    val currency = state.expense?.currency ?: "INR"
    val (container, content, text) = when {
        state.method == SplitMethod.PERCENT && state.percentTotal != 100 -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "${state.percentTotal}% assigned — needs 100%"
        )
        state.method == SplitMethod.EXACT && state.remainingMinor != 0L -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            if (state.remainingMinor > 0) {
                stringResource(R.string.left_to_assign, Money.format(state.remainingMinor, currency))
            } else {
                stringResource(
                    R.string.over_by,
                    Money.format(state.remainingMinor.absoluteValue, currency)
                )
            }
        )
        else -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            stringResource(R.string.fully_split)
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(container)
            .padding(horizontal = 15.dp, vertical = 13.dp)
    ) {
        Icon(
            imageVector = if (state.balances) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = content,
            modifier = Modifier.weight(1f)
        )
        Amount(
            minor = state.assignedMinor,
            currency = currency,
            size = 15.sp,
            color = content,
            inline = true,
            compact = false
        )
    }
}

/** A content description for a bare BasicTextField, which has no label of its own. */
private fun Modifier.semanticsLabel(label: String): Modifier =
    this.semantics { contentDescription = label }
