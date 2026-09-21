package com.snaptab.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptab.app.ui.theme.AmountStyle
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One ring segment: what it is, how much of it there is, and what colour it draws in. */
data class DonutSlice(
    val id: String,
    val label: String,
    val value: Long,
    val color: Color
)

/**
 * A donut chart that draws itself in, and reacts when a segment is tapped.
 *
 * Three animations, each with a job:
 *
 * The sweep. On first composition the ring draws clockwise from twelve o'clock over 900ms
 * while the whole chart scales up from 92%. A chart that simply appears has to be read; one
 * that draws itself shows you the order of its segments as it goes, which is most of what
 * the chart is for.
 *
 * The selection. A tapped segment thickens and pushes outward along its own mid-angle on a
 * low-bounce spring while the others recede to 40%. Both halves matter — the lift says
 * which one you picked, the fade says the rest are still there and still to scale. Every
 * segment has its own animation, so switching straight from one to another animates both
 * ends instead of snapping.
 *
 * The centre. The total cross-fades to the selected segment's own figure, sliding as it
 * goes, so the number being read is always the number that was asked for.
 *
 * Angles accumulate from a running total rather than being computed per slice, so rounding
 * cannot leave a hairline gap in the ring or overshoot past 360.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    centerLabel: String,
    centerValue: String,
    modifier: Modifier = Modifier,
    thickness: Dp = 22.dp,
    selectedId: String? = null,
    onSelect: (String?) -> Unit = {},
    formatSliceValue: (DonutSlice) -> String = { it.value.toString() }
) {
    val total = remember(slices) { slices.sumOf { it.value }.coerceAtLeast(1L) }
    val ids = remember(slices) { slices.map { it.id } }

    // Restarted whenever the set of segments changes, so switching lens or month redraws
    // rather than silently swapping the numbers under a ring that never moved.
    val sweep = remember { Animatable(0f) }
    val entrance = remember { Animatable(0.92f) }
    LaunchedEffect(ids) {
        sweep.snapTo(0f)
        entrance.snapTo(0.92f)
        launch { entrance.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
        sweep.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }

    /**
     * One animation per segment, held outside composition rather than created with
     * animateFloatAsState in a loop: the number of composable calls in a loop has to stay
     * stable across recompositions, and this list does not.
     */
    val lifts = remember { mutableMapOf<String, Animatable<Float, AnimationVector1D>>() }
    ids.forEach { id -> lifts.getOrPut(id) { Animatable(0f) } }
    LaunchedEffect(selectedId, ids) {
        ids.forEach { id ->
            val anim = lifts.getValue(id)
            val target = if (id == selectedId) 1f else 0f
            launch {
                anim.animateTo(target, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow))
            }
        }
    }

    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    /**
     * Measured once per layout, in composition — not in the draw pass.
     *
     * Hit testing has to agree with what was drawn, and only the draw scope knows the pixel
     * geometry. Writing it to state from inside the draw lambda is a draw-phase side effect
     * that invalidates the layout that produced it, so it is derived from the laid-out size
     * instead. The entrance scale is deliberately left out: it settles in under half a
     * second, and hit testing the final ring is right for every tap that follows.
     */
    val geometry = remember(canvasSize, slices, total, thickness, density) {
        if (canvasSize == IntSize.Zero || slices.isEmpty()) {
            DonutGeometry.Empty
        } else {
            val strokePx = with(density) { thickness.toPx() }
            val liftRoom = strokePx * 0.5f
            val minDimension = minOf(canvasSize.width, canvasSize.height).toFloat()
            val radius = (minDimension / 2f) - strokePx / 2f - liftRoom

            var accumulated = 0L
            var startOffset = 0f
            val angles = slices.map { slice ->
                accumulated += slice.value
                // Where this slice ENDS as a fraction of the whole, so the last one lands
                // exactly on 360 however the divisions round.
                val endOffset = (accumulated.toFloat() / total.toFloat()) * 360f
                SliceAngle(slice.id, startOffset, endOffset - startOffset)
                    .also { startOffset = endOffset }
            }
            DonutGeometry(
                center = Offset(canvasSize.width / 2f, canvasSize.height / 2f),
                radius = radius,
                strokePx = strokePx,
                liftRoom = liftRoom,
                angles = angles
            )
        }
    }

    Box(modifier = modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(geometry) {
                    detectTapGestures { tap ->
                        val hit = geometry.sliceAt(tap)
                        // Tapping the selected segment again, or the hole, clears the
                        // selection, so there is always a way back to the total.
                        onSelect(if (hit == null || hit == selectedId) null else hit)
                    }
                }
        ) {
            if (geometry.angles.isEmpty()) return@Canvas
            val scale = entrance.value
            val radius = geometry.radius * scale

            slices.forEachIndexed { index, slice ->
                val angle = geometry.angles.getOrNull(index) ?: return@forEachIndexed
                val startAngle = START_ANGLE + angle.start
                val drawnSweep = angle.sweep * sweep.value
                if (drawnSweep <= 0.05f) return@forEachIndexed

                val lift = lifts[slice.id]?.value ?: 0f
                val midAngleRad = Math.toRadians((startAngle + angle.sweep / 2f).toDouble())
                val push = geometry.liftRoom * lift
                val dx = (cos(midAngleRad) * push).toFloat()
                val dy = (sin(midAngleRad) * push).toFloat()

                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = (drawnSweep - SLICE_GAP).coerceAtLeast(0.4f),
                    useCenter = false,
                    topLeft = Offset(
                        geometry.center.x - radius + dx,
                        geometry.center.y - radius + dy
                    ),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(
                        width = geometry.strokePx * (1f + 0.22f * lift),
                        cap = StrokeCap.Butt
                    ),
                    // Everything that is not the selection recedes, but never disappears.
                    alpha = if (selectedId == null) 1f else 0.4f + 0.6f * lift
                )
            }
        }

        val selected = slices.firstOrNull { it.id == selectedId }
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(220)) { -it / 3 })
            },
            label = "donut-center"
        ) { slice ->
            DonutCenter(
                label = slice?.label ?: centerLabel,
                value = if (slice != null) formatSliceValue(slice) else centerValue,
                accent = slice?.color
            )
        }
    }
}

/** The total, or the selected segment's figure. Sized to fit inside the hole. */
@Composable
private fun DonutCenter(label: String, value: String, accent: Color?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            style = AmountStyle.copy(fontSize = 23.sp),
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

private const val START_ANGLE = -90f

/** A hair of background between segments, so neighbouring colours stay legible. */
private const val SLICE_GAP = 1.6f

/** `start` is an offset in degrees from the top of the ring, not an absolute angle. */
private data class SliceAngle(val id: String, val start: Float, val sweep: Float)

private data class DonutGeometry(
    val center: Offset,
    val radius: Float,
    val strokePx: Float,
    val liftRoom: Float,
    val angles: List<SliceAngle>
) {
    private val innerRadius: Float get() = radius - strokePx / 2f - liftRoom
    private val outerRadius: Float get() = radius + strokePx / 2f + liftRoom

    /** Which segment, if any, a tap landed on. Null for the hole and for outside the ring. */
    fun sliceAt(tap: Offset): String? {
        if (angles.isEmpty()) return null
        val dx = tap.x - center.x
        val dy = tap.y - center.y
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance < innerRadius || distance > outerRadius) return null

        // atan2 measures from three o'clock; the ring starts at twelve.
        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val fromTop = ((degrees - START_ANGLE) % 360f + 360f) % 360f
        // The last segment absorbs any rounding at the seam rather than reporting a miss.
        return angles.firstOrNull { fromTop >= it.start && fromTop < it.start + it.sweep }?.id
            ?: angles.last().id
    }

    companion object {
        val Empty = DonutGeometry(Offset.Zero, 0f, 0f, 0f, emptyList())
    }
}
