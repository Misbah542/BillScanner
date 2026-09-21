package com.snaptab.app.ui.screen.scan

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snaptab.app.R
import com.snaptab.app.ui.components.ErrorBanner
import com.snaptab.app.ui.components.PrimaryButton
import com.snaptab.app.ui.components.SecondaryButton
import com.snaptab.app.ui.components.SnapCard

/**
 * While the server reads the bill.
 *
 * The screen says plainly that the work is happening server-side and offers to leave —
 * because it is true, and because a progress bar that pretends the phone is busy is a lie
 * that makes people sit and wait for no reason.
 */
@Composable
fun ProcessingScreen(
    state: ScanUiState,
    onBackground: () -> Unit,
    onRetry: () -> Unit,
    onEnterByHand: () -> Unit,
    onCancel: () -> Unit
) {
    val failed = state.stage == ScanStage.FAILED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(if (failed) R.string.scan_failed_title else R.string.reading_your_bill),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        if (failed) {
            ErrorBanner(message = state.error, onDismiss = {}, offline = state.offline)
            Spacer(Modifier.height(20.dp))
            PrimaryButton(text = stringResource(R.string.try_again), onClick = onRetry)
            Spacer(Modifier.height(10.dp))
            SecondaryButton(text = stringResource(R.string.enter_by_hand), onClick = onEnterByHand)
        } else {
            ScanningReceipt()

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.reading_explainer),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))
            Steps(stage = state.stage)

            Spacer(Modifier.weight(1f))

            SnapCard(
                background = MaterialTheme.colorScheme.primaryContainer,
                borderColor = MaterialTheme.colorScheme.primaryContainer,
                contentPadding = PaddingValues(13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.NotificationsNone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Leave if you like — we'll ping you when the items are ready.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            SecondaryButton(text = stringResource(R.string.do_in_background), onClick = onBackground)
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** A receipt with a scan line travelling down it. Decorative, and honest about it. */
@Composable
private fun ScanningReceipt() {
    val transition = rememberInfiniteTransition(label = "scan")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanline"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(216.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(168.dp)
                .height(216.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                listOf(0.78f, 0.52f, 0.92f, 0.84f, 0.88f, 0.70f, 0.6f).forEachIndexed { index, width ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(width)
                            .height(if (index == 0) 9.dp else 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (index == 0) {
                                    MaterialTheme.colorScheme.outlineVariant
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (offset * 190).dp)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun Steps(stage: ScanStage) {
    val steps = listOf(
        stringResource(R.string.step_uploaded) to (stage != ScanStage.UPLOADING),
        stringResource(R.string.step_recognised) to (stage == ScanStage.PROCESSING || stage == ScanStage.READY),
        stringResource(R.string.step_parsed) to (stage == ScanStage.READY),
        stringResource(R.string.step_categorised) to (stage == ScanStage.READY)
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEach { (label, done) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            if (done) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
