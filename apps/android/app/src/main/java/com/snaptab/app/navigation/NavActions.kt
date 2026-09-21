package com.snaptab.app.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

/**
 * Every navigation in the app goes through one of these four.
 *
 * Three bugs came out of not having them:
 *
 * A tab could be reached two different ways. The bottom bar used
 * popUpTo(start){saveState} + launchSingleTop + restoreState, while "see your inbox" on
 * the home screen used a bare navigate(). Bare navigate pushes another copy, so going
 * home -> inbox by the card and then reaching for back walked through a stack the user
 * never knowingly built. `toTab` is now the only way to reach a tab, from anywhere.
 *
 * Nothing was single-top. Two taps on a row — easy on a slow frame, and the usual
 * reaction when the first tap seems not to have worked — pushed the same screen twice,
 * so back appeared to do nothing the first time.
 *
 * `popBackStack()` followed by `navigate()` was two operations where one was meant. Fired
 * twice it pops twice, and a pop of the screen you are navigating from can swallow the
 * navigate. `replace` does it as one atomic navigate instead.
 *
 * The lifecycle guard is the thread running through all of it: a click is only acted on
 * while the screen it came from is RESUMED. A screen that is already animating away has
 * no business navigating, and this is what makes a double tap idempotent rather than
 * merely unlikely to hurt.
 */

/**
 * True while this entry owns the screen the user is looking at.
 *
 * A composable that has started its exit transition is STARTED, not RESUMED, but it is
 * still composed and its click handlers still fire — which is exactly the window a
 * double tap lands in.
 */
private fun NavBackStackEntry.isResumed(): Boolean =
    lifecycle.currentState == Lifecycle.State.RESUMED

/**
 * Switch to one of the four bottom tabs.
 *
 * Each tab keeps its own scroll position and back stack across switches (saveState /
 * restoreState), and there is only ever one copy of a tab on the stack, so back from any
 * tab leaves the app rather than replaying where you have been.
 */
fun NavHostController.toTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Push a detail screen on top of the current one. */
fun NavHostController.push(from: NavBackStackEntry, route: String) {
    if (!from.isResumed()) return
    navigate(route) { launchSingleTop = true }
}

/** Go back one screen, if there is one and if this screen is still the live one. */
fun NavHostController.up(from: NavBackStackEntry) {
    if (!from.isResumed()) return
    popBackStack()
}

/**
 * Swap this screen for another, leaving nothing of it behind.
 *
 * For the steps that are a means to an end: the camera once a bill is scanned, the
 * add-expense form once it is saved. Backing out of the expense you just created should
 * return you to where you started, not to the empty form you were just done with.
 */
fun NavHostController.replace(from: NavBackStackEntry, route: String) {
    if (!from.isResumed()) return
    val current = from.destination.route ?: return
    navigate(route) {
        popUpTo(current) { inclusive = true }
        launchSingleTop = true
    }
}
