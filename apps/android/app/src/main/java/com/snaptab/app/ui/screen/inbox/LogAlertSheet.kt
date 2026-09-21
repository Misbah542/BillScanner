package com.snaptab.app.ui.screen.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.snaptab.app.R
import com.snaptab.app.core.Money
import com.snaptab.app.data.local.AlertEntity
import com.snaptab.app.data.local.GroupEntity
import com.snaptab.app.ui.components.SecondaryButton
import com.snaptab.app.ui.components.SectionLabel

/**
 * "Where does this ₹486 go?"
 *
 * The one decision only the user can make, asked once and answered in a tap: keep it to
 * yourself, or put it on a group. The group list is the groups they ALREADY have — this
 * sheet never creates one, because a group is a thing with people in it and a running
 * balance, not something to conjure while filing a coffee.
 *
 * Picking a group splits the amount equally across that group's current members in the
 * same action; the per-head figure is shown on each row so the choice is not a guess.
 */
@Composable
fun LogAlertSheet(
    alert: AlertEntity,
    groups: List<GroupEntity>,
    working: Boolean,
    onLogPersonal: () -> Unit,
    onLogToGroup: (String) -> Unit,
    onScanBill: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
        ) {
            Text(
                text = "Where does ${Money.formatCompact(alert.amountMinor, alert.currency)} go?",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "Keep it to yourself, or put it on one of your groups and split it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))
            SectionLabel(text = "Just me")
            Spacer(Modifier.height(8.dp))

            ChoiceRow(
                title = stringResource(R.string.personal_expense),
                subtitle = "All ${Money.formatCompact(alert.amountMinor, alert.currency)} counts as your own spending",
                icon = { tint ->
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                },
                highlighted = true,
                enabled = !working,
                onClick = onLogPersonal
            )

            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionLabel(text = "Or split it on a group you already have")
                Spacer(Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    groups.forEach { group ->
                        val heads = (group.memberCount + group.pendingCount).coerceAtLeast(1)
                        val perHead = Money.apportion(
                            alert.amountMinor,
                            List(heads) { 1L }
                        ).firstOrNull() ?: alert.amountMinor

                        ChoiceRow(
                            title = group.name,
                            subtitle = "$heads people · ${Money.format(perHead, group.currency)} each",
                            icon = { tint ->
                                Icon(
                                    Icons.Outlined.Groups,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            highlighted = false,
                            enabled = !working,
                            onClick = { onLogToGroup(group.id) }
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "You have no groups yet. Start one from the Groups tab and it will show up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(14.dp))
            SecondaryButton(
                text = stringResource(R.string.scan_bill),
                onClick = onScanBill,
                enabled = !working,
                leadingIcon = {
                    Icon(
                        Icons.Outlined.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            if (working) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val border = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(if (highlighted) 2.dp else 1.dp, border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                icon(MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (highlighted) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/** Used when the user wants to split with people who are in no group. */
@Composable
fun SplitWithoutGroupRow(onClick: () -> Unit, enabled: Boolean = true) {
    SecondaryButton(
        text = "Split with people, no group",
        onClick = onClick,
        enabled = enabled,
        leadingIcon = {
            Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    )
}
