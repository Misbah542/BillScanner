package com.snaptab.app.ui.screen.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snaptab.app.R
import com.snaptab.app.ui.components.*
import com.snaptab.app.ui.theme.AmountStyle
import com.snaptab.app.ui.theme.categoryColors
import java.time.Instant

/**
 * Add an expense by hand. No group required, no split, no receipt — an amount and,
 * ideally, where you spent it.
 */
@Composable
fun AddExpenseScreen(
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
    onScanInstead: () -> Unit,
    onSplitInstead: () -> Unit,
    prefillAmountMinor: Long? = null,
    prefillMerchant: String? = null,
    prefillCategorySlug: String? = null,
    prefillOccurredAt: Instant? = null,
    viewModel: AddExpenseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(prefillAmountMinor, prefillMerchant) {
        if (prefillAmountMinor != null || prefillMerchant != null) {
            viewModel.prefill(prefillAmountMinor, prefillMerchant, prefillCategorySlug, prefillOccurredAt)
        }
    }

    LaunchedEffect(state.savedExpenseId) {
        state.savedExpenseId?.let(onSaved)
    }

    Scaffold(
        // Transparent so the app-wide grid behind the NavHost shows through; a Scaffold
        // otherwise paints an opaque `background` over it.
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_expense)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    TextButton(onClick = onScanInstead) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.scan_bill))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    PrimaryButton(
                        text = state.amountMinor
                            ?.let { "${stringResource(R.string.save)} ${com.snaptab.app.core.Money.format(it)}" }
                            ?: stringResource(R.string.save),
                        onClick = viewModel::save,
                        enabled = state.canSave,
                        loading = state.saving
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ErrorBanner(
                message = state.error,
                onDismiss = viewModel::dismissError,
                offline = state.offline
            )

            // Amount, given the whole card: it is the only field that is actually required.
            SnapCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
                Text(
                    text = stringResource(R.string.amount).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "₹",
                        style = AmountStyle.copy(fontSize = 30.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    BasicAmountField(
                        value = state.amountText,
                        onValueChange = viewModel::setAmount,
                        modifier = Modifier.width(190.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    StatusChip(
                        text = stringResource(R.string.all_yours),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    TextButton(onClick = onSplitInstead, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.split_it), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            SnapCard {
                Text(
                    text = stringResource(R.string.merchant).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = state.merchant,
                    onValueChange = viewModel::setMerchant,
                    placeholder = { Text("Shop, restaurant, anything") },
                    singleLine = true,
                    shape = RoundedCornerShape(13.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SnapCard {
                SectionLabel(text = stringResource(R.string.category)) {
                    if (state.categoryIsSuggested) {
                        StatusChip(
                            text = stringResource(R.string.suggested),
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                CategoryPicker(
                    categories = state.categories,
                    suggestions = state.suggestions,
                    selected = state.categorySlug,
                    onSelect = viewModel::setCategory
                )
                state.suggestions.firstOrNull()?.reasons?.firstOrNull()?.let { reason ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // A group is optional, and "None" is both the default and the usual answer.
            SnapCard {
                SectionLabel(text = stringResource(R.string.groups))
                Spacer(Modifier.height(8.dp))
                GroupPicker(
                    groups = state.groups,
                    selectedId = state.groupId,
                    onSelect = viewModel::setGroup
                )
            }

            SnapCard(
                background = MaterialTheme.colorScheme.primaryContainer,
                borderColor = MaterialTheme.colorScheme.primaryContainer,
                contentPadding = PaddingValues(14.dp)
            ) {
                Row {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "No group, no split, no receipt needed. It just counts toward this month's spending.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            SnapCard {
                Text(
                    text = "NOTE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    placeholder = { Text("Optional") },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BasicAmountField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = AmountStyle.copy(
            fontSize = 42.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Column {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    if (value.isEmpty()) {
                        Text(
                            text = "0.00",
                            style = AmountStyle.copy(fontSize = 42.sp, textAlign = TextAlign.Center),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    inner()
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        },
        modifier = modifier
    )
}

/** Suggestions first, in confidence order, then the rest of the taxonomy. */
@Composable
private fun CategoryPicker(
    categories: List<com.snaptab.app.data.local.CategoryEntity>,
    suggestions: List<com.snaptab.app.data.remote.dto.CategorySuggestionDto>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    val suggestedSlugs = suggestions.map { it.slug }
    val ordered = buildList {
        suggestedSlugs.forEach { slug -> categories.firstOrNull { it.slug == slug }?.let(::add) }
        addAll(categories.filter { it.slug !in suggestedSlugs && it.kind == "SPEND" })
    }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        ordered.chunked(2).take(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { category ->
                    val colors = categoryColors(category.slug)
                    val isSelected = category.slug == selected
                    Surface(
                        onClick = { onSelect(if (isSelected) null else category.slug) },
                        shape = RoundedCornerShape(19.dp),
                        color = if (isSelected) colors.tint else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) colors.fg else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
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
                                text = category.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) colors.fg else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Lists the groups that already exist, plus "None". It never creates one — starting a
 * group is a deliberate act, not something that happens while logging a coffee.
 */
@Composable
private fun GroupPicker(
    groups: List<com.snaptab.app.data.local.GroupEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        GroupOption(
            label = "None — just me",
            selected = selectedId == null,
            onClick = { onSelect(null) }
        )
        groups.forEach { group ->
            GroupOption(
                label = group.name,
                selected = group.id == selectedId,
                onClick = { onSelect(group.id) }
            )
        }
    }
}

@Composable
private fun GroupOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(13.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
