package com.snaptab.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A perspective grid receding to a horizon, drifting toward the viewer.
 *
 * The geometry is a floor plane under a camera, which is what makes it read as depth rather
 * than as a pattern. For a row of lines at increasing distance `z`, the screen position is
 * `horizon + k / z` — the reciprocal is the whole trick. Evenly spaced lines in the world
 * bunch up toward the horizon on screen exactly the way a real floor does, and animating a
 * fractional offset into `z` slides the whole field forward: each line accelerates as it
 * approaches, because 1/z changes faster as z gets small.
 *
 * Verticals are straight lines from the vanishing point to the bottom edge. They need no
 * projection — in a one-point perspective every line parallel to the view direction meets
 * at that single point.
 *
 * Three things keep it from becoming noise behind real content:
 *
 * It is slow. A 9 second loop reads as drift, not motion; anything quicker competes with
 * the content for attention and makes text harder to read.
 *
 * It fades twice. Once by depth, so lines emerge from the horizon rather than popping in at
 * full strength, and once by height, so the grid is gone before it reaches the content at
 * the top of the screen. Without the second fade the horizon is a hard bright line.
 *
 * It is faint. Alpha tops out around 0.2 in the dark theme and lower in light, which is
 * enough to feel like depth and not enough to read as a chart.
 */
@Composable
fun GridBackground(
    modifier: Modifier = Modifier,
    color: Color = if (isSystemInDarkTheme()) Color(0xFF7FC9B8) else Color(0xFF0F6B5C),
    /** Set false to stop the animation — an always-moving background is never free. */
    animated: Boolean = true,
    /** Where the horizon sits, as a fraction of height. Lower means more floor. */
    horizonFraction: Float = 0.34f,
    maxAlpha: Float = if (isSystemInDarkTheme()) 0.20f else 0.10f
) {
    val transition = rememberInfiniteTransition(label = "grid")
    // One code path whether or not it animates: with the target equal to the start there is
    // nothing to interpolate, so the grid is drawn and simply stays put. Branching here
    // instead would mean two different state types behind one `by`.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animated) 1f else 0f,
        animationSpec = infiniteRepeatable(
            // Linear on purpose: the perspective already supplies the acceleration, and an
            // eased phase on top of it reads as a stutter.
            animation = tween(durationMillis = 9_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "grid-phase"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        drawPerspectiveGrid(
            phase = phase,
            color = color,
            horizonY = size.height * horizonFraction,
            maxAlpha = maxAlpha
        )
    }
}

/** Rows of depth to draw. Beyond about 14 the lines are closer than a pixel apart. */
private const val DEPTH_LINES = 14

/** Verticals either side of centre. */
private const val COLUMNS = 7

private fun DrawScope.drawPerspectiveGrid(
    phase: Float,
    color: Color,
    horizonY: Float,
    maxAlpha: Float
) {
    val width = size.width
    val height = size.height
    val centerX = width / 2f
    val floorHeight = height - horizonY
    if (floorHeight <= 0f) return

    val strokeThin = 1.dp.toPx()

    // ---- verticals: straight lines out of the vanishing point ----
    // Spread well past the screen edge so the outermost ones leave at the bottom corners
    // rather than stopping short inside the frame.
    val spread = width * 1.9f
    for (i in -COLUMNS..COLUMNS) {
        val t = i.toFloat() / COLUMNS
        val bottomX = centerX + t * spread
        // Faint at the centre, stronger toward the edges: the middle of the fan is where the
        // lines crowd together, and full strength there turns into a solid wedge.
        val alpha = maxAlpha * (0.35f + 0.65f * abs(t))
        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(centerX, horizonY),
            end = Offset(bottomX, height),
            strokeWidth = strokeThin,
            cap = StrokeCap.Round
        )
    }

    // ---- horizontals: the floor rows, projected by 1/z ----
    for (i in 0 until DEPTH_LINES) {
        // The fractional phase is what moves the field. Adding it to z means row 0 walks
        // from the horizon to the viewer and the next row takes its place.
        val z = i + 1f - phase
        if (z <= 0f) continue
        val y = horizonY + floorHeight / z

        // Past the bottom of the screen: it has gone by.
        if (y > height) continue

        // Depth fade — 1/z again, so a row emerges rather than appearing.
        val depthAlpha = (1f / z).coerceIn(0f, 1f)
        // Height fade — gone before it reaches the content above.
        val riseAlpha = ((y - horizonY) / floorHeight).coerceIn(0f, 1f)
        drawLine(
            color = color.copy(alpha = maxAlpha * depthAlpha * riseAlpha),
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = strokeThin,
            cap = StrokeCap.Round
        )
    }

    // A soft glow sitting on the horizon, which is what stops it looking like a cut edge.
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, color.copy(alpha = maxAlpha * 0.5f), Color.Transparent),
            startY = horizonY - floorHeight * 0.06f,
            endY = horizonY + floorHeight * 0.06f
        ),
        topLeft = Offset(0f, horizonY - floorHeight * 0.06f),
        size = Size(width, floorHeight * 0.12f)
    )
}
