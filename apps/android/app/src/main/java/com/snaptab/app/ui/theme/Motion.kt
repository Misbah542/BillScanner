package com.snaptab.app.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/**
 * One place for how things move, so the app reads as a single thing rather than as a
 * collection of screens that each animate slightly differently.
 *
 * Two rules behind the numbers. Anything the user is waiting on — a screen arriving, a
 * sheet opening — is fast and eased, in the 200–320ms band, because motion in the way of
 * a task is a cost. Anything that is feedback on something they just did — a selection
 * lifting, a value changing — is a spring, because a spring settles the way a physical
 * thing does and is what makes a tap feel like it connected.
 *
 * Nothing here exceeds 400ms. Past roughly that, an animation stops being perceived as the
 * thing moving and starts being perceived as the app being slow.
 */
object Motion {

    /** The house easing: quick to leave, gentle to arrive. */
    val Emphasised = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val Fast = 180
    const val Normal = 260
    const val Slow = 320

    /** Feedback on a direct action: a chip selecting, a row lifting. */
    fun <T> springy() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    /** A value settling into place with no overshoot — amounts, widths, progress. */
    fun <T> settle() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    /**
     * Pushing a screen on top of another.
     *
     * The outgoing screen slides a short way rather than the full width, and fades. Moving
     * it the whole width is the iOS idiom and looks wrong under a bottom bar that stays
     * put; a fifth of the width reads as depth without the furniture appearing to slide.
     */
    fun enterPush(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition =
        with(scope) {
            slideInHorizontally(tween(Normal, easing = Emphasised)) { full -> full / 3 } +
                fadeIn(tween(Normal, easing = Emphasised))
        }

    fun exitPush(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition =
        with(scope) {
            slideOutHorizontally(tween(Normal, easing = Emphasised)) { full -> -full / 5 } +
                fadeOut(tween(Fast))
        }

    fun enterPop(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition =
        with(scope) {
            slideInHorizontally(tween(Normal, easing = Emphasised)) { full -> -full / 5 } +
                fadeIn(tween(Normal, easing = Emphasised))
        }

    fun exitPop(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition =
        with(scope) {
            slideOutHorizontally(tween(Normal, easing = Emphasised)) { full -> full / 3 } +
                fadeOut(tween(Fast))
        }

    /**
     * Switching between the four tabs.
     *
     * A cross-fade with a hair of scale, and deliberately no slide: tabs are siblings, not
     * a stack, and sliding between them implies an order that the bottom bar does not have.
     */
    val enterTab: EnterTransition =
        fadeIn(tween(Fast)) + scaleIn(tween(Normal, easing = Emphasised), initialScale = 0.98f)

    val exitTab: ExitTransition =
        fadeOut(tween(Fast)) + scaleOut(tween(Normal, easing = Emphasised), targetScale = 0.98f)

    /**
     * A list item's entrance, staggered by position.
     *
     * Capped at eight: past that the last item waits long enough to feel like a load rather
     * than a flourish, and on a long list nobody sees the tail of it anyway.
     */
    fun staggerDelay(index: Int, step: Int = 28): Int = (index.coerceAtMost(8)) * step
}
