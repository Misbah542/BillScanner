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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.snaptab.app.ui.theme.InkTertiary
import com.snaptab.app.ui.theme.InkTertiaryDark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A flat grid with a light moving under it, bulging and brightening whatever it passes.
 *
 * The grid itself is square on — no perspective, no rotation, no vanishing point. All the
 * depth comes from one travelling focus:
 *
 * **It scales.** Every grid line is drawn as a chain of short segments rather than one
 * straight line, and each sample point is pushed *away* from the focus by a Gaussian
 * falloff. Cells near the focus spread apart and the lines through them bow outward, so the
 * surface reads as being pressed up from underneath. A straight line cannot do this — the
 * curve is the effect, and it is why the extra segments are worth their cost.
 *
 * **It brightens.** The same falloff lifts each segment's colour toward white and its alpha
 * with it, so the grid runs from nearly invisible at the edges to a bright filament at the
 * centre of the light. Brightness varies *along* each line, not per line, which is what
 * stops it looking like a highlighted row.
 *
 * The focus travels a **closed** Lissajous path, two sines on a 2 : 3 ratio, and the whole
 * number matters. The first version used 1 : 0.73, chosen so the path would not repeat — but
 * Compose restarts an infiniteRepeatable from its initial value, and a path that has not
 * returned home by the end of its loop teleports when it does. That one jumped 711px
 * vertically every cycle. A closed curve costs some unpredictability and buys continuity in
 * both position and velocity.
 *
 * It is drawn once, in the navigation graph behind the NavHost, rather than per screen. That
 * is what lets it be the app's background instead of Home's: a navigation does not restart its
 * animation, and there is one Canvas for thirteen screens. The screens that own a Scaffold have
 * to pass `containerColor = Color.Transparent`, because M3 otherwise paints an opaque
 * `background` colour straight over it.
 *
 * The grid drifts underneath by a whole number of cells — one across, two down — and wraps.
 * Whole cells matter for the same reason: a line leaving one edge has to be replaced exactly
 * by its neighbour, so the offset has to come back to zero modulo the cell size. The first
 * version drifted 0.6 of a cell vertically, and jumped 54px each time it wrapped.
 */
@Composable
fun GridBackground(
    modifier: Modifier = Modifier,
    /**
     * Neutral ink, not the brand teal.
     *
     * It was teal, which was fine when it only sat behind Home. Behind every screen a
     * coloured grid is a third accent competing with the two that mean something — teal
     * for money coming to you, clay for money you owe — and it tinted every card and chip
     * drawn over it. A background should be texture, not colour.
     */
    color: Color = if (isSystemInDarkTheme()) InkTertiaryDark else InkTertiary,
    /** Set false to stop it — a permanently moving background is never free. */
    animated: Boolean = true,
    cellSize: Dp = 52.dp,
    /**
     * What the grid is worth away from the light, and this is the number that decides
     * whether it covers the screen.
     *
     * It used to be derived — 0.22 of the peak, which came out at 0.075 — and at that value
     * a 1px line on a dark background is not there. The grid was drawn edge to edge the whole
     * time; only the lit part could be seen, so it read as one bright square in the middle
     * with nothing around it. It is a value in its own right now, set where a line is
     * genuinely visible.
     */
    restAlpha: Float = if (isSystemInDarkTheme()) 0.13f else 0.08f,
    /** What the grid is worth under the light. */
    /**
     * Dark mode gets the smaller lift. A light-on-dark sweep carries much further than the
     * same number on paper, and at 0.42 the grid was one more thing glaring on an already
     * loud dark theme.
     */
    litAlpha: Float = if (isSystemInDarkTheme()) 0.30f else 0.22f
) {
    val transition = rememberInfiniteTransition(label = "grid")

    // One code path whether or not it animates: with the target equal to the start there is
    // nothing to interpolate, so the grid is drawn and simply holds still.
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animated) 1f else 0f,
        animationSpec = infiniteRepeatable(
            // Linear and wrapping on whole cells, so there is no seam and no stutter.
            animation = tween(durationMillis = 8_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "grid-drift"
    )
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animated) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "grid-travel"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        drawLitGrid(
            drift = drift,
            travel = travel,
            cell = cellSize.toPx(),
            color = color,
            restAlpha = restAlpha,
            litAlpha = litAlpha
        )
    }
}

/** Sample points along each line. Enough for the bow to look smooth, few enough to be cheap. */
private const val SAMPLES = 12

/**
 * Past this many falloff radii from the light, a line is drawn straight in one stroke.
 *
 * At this distance the light's influence is 0.03, so the difference it would have made to a
 * segment's alpha is under 0.01 — below what the eye resolves on a 1px line, and below the
 * rounding of an 8-bit channel.
 *
 * This saved more when the light was narrower. With the radius at half the long edge almost
 * every line on a phone screen is now within range, so the honest figure is around 380 short
 * strokes a frame rather than the 150 this once bought. That is the price of a grid that
 * covers the screen, and it is roughly what a detailed vector drawable costs.
 *
 * It is worth saying plainly that this now runs on every screen rather than on Home only, and
 * it never stops. `animated = false` is the switch if it ever needs to come off a slow device.
 */
private const val FAR = 1.5f

/** How far the light pushes the grid apart, as a fraction of a cell. */
private const val BULGE = 0.5f

private fun DrawScope.drawLitGrid(
    drift: Float,
    travel: Float,
    cell: Float,
    color: Color,
    restAlpha: Float,
    litAlpha: Float
) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f || cell <= 1f) return

    // Whole-number frequencies, so the path closes: at the end of the loop the light is
    // exactly where it began, moving in exactly the same direction. The vertical amplitude is
    // the smaller of the two because the vertical frequency is the higher one, and equal
    // amplitudes at 3x send it thrashing up and down the screen.
    val angle = travel * 2f * PI.toFloat()
    val focus = Offset(
        x = width * (0.5f + 0.34f * sin(2f * angle + 0.9f)),
        y = height * (0.45f + 0.16f * sin(3f * angle))
    )
    // Half the long edge. At 0.36 the falloff was spent well before the top and bottom of a
    // phone screen, which is the other half of why it looked like one square.
    val radius = maxOf(width, height) * 0.50f
    val bulge = cell * BULGE

    // Drifts diagonally, wrapping on a WHOLE number of cells in each axis — one across, two
    // down. A fractional number leaves the grid somewhere it has no line to hand over to,
    // which is exactly what a jump is.
    val offsetX = drift * cell
    val offsetY = drift * cell * 2f

    val stroke = 1.dp.toPx()
    val baseAlpha = restAlpha

    /** How strongly the light affects a point: 1 at the centre, falling off smoothly. */
    fun influence(p: Offset): Float {
        val d = hypot(p.x - focus.x, p.y - focus.y) / radius
        return exp(-d * d * 1.6f)
    }

    /** Pushes a point away from the light, which is what spreads the cells apart. */
    fun displace(p: Offset, strength: Float): Offset {
        if (strength <= 0.004f) return p
        val dx = p.x - focus.x
        val dy = p.y - focus.y
        val len = hypot(dx, dy)
        if (len < 0.001f) return p
        val push = bulge * strength
        return Offset(p.x + (dx / len) * push, p.y + (dy / len) * push)
    }

    /**
     * Draws one line as a chain of segments, each displaced and lit on its own.
     *
     * `at` maps 0..1 along the line to a point, so the same code draws both families.
     */
    fun drawLit(distanceToLight: Float, at: (Float) -> Offset) {
        if (distanceToLight > radius * FAR) {
            // Too far to be bent or brightened: one stroke at the resting alpha.
            drawLine(
                color = color.copy(alpha = baseAlpha),
                start = at(0f),
                end = at(1f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            return
        }
        var previous = displace(at(0f), influence(at(0f)))
        for (s in 1..SAMPLES) {
            val t = s.toFloat() / SAMPLES
            val raw = at(t)
            val strength = influence(raw)
            val point = displace(raw, strength)

            // Toward white and more opaque as the light gets closer: "darker" everywhere
            // else, so the lit part reads as a filament rather than a wash.
            val alpha = baseAlpha + (litAlpha - baseAlpha) * strength
            // Toward white, but not all the way there: at 0.75 the peak of the sweep was
            // effectively a white filament, which is exactly the kind of glare a dark theme
            // does not need.
            val segmentColor = lerp(color, Color.White, strength * 0.45f).copy(alpha = alpha)
            drawLine(
                color = segmentColor,
                start = previous,
                end = point,
                // The lit stretch is drawn a little heavier, which is most of what sells it.
                strokeWidth = stroke * (1f + strength * 0.7f),
                cap = StrokeCap.Round
            )
            previous = point
        }
    }

    // One cell of bleed on every side, so a displaced line never ends inside the frame.
    val columns = (width / cell).toInt() + 3
    val rows = (height / cell).toInt() + 3
    val left = -cell
    val top = -cell

    // For an axis-aligned line the distance to the light is just the perpendicular gap, so
    // the early-out costs one subtraction per line.
    for (c in 0..columns) {
        val x = left + c * cell + offsetX
        drawLit(abs(x - focus.x)) { t -> Offset(x, top + t * (height + 2f * cell)) }
    }
    for (r in 0..rows) {
        val y = top + r * cell + offsetY
        drawLit(abs(y - focus.y)) { t -> Offset(left + t * (width + 2f * cell), y) }
    }
}
