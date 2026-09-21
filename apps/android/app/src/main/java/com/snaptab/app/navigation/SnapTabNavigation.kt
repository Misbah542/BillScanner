package com.snaptab.app.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.snaptab.app.R
import com.snaptab.app.ui.components.GridBackground
import com.snaptab.app.ui.screen.auth.SignInScreen
import com.snaptab.app.ui.screen.expense.ExpenseDetailScreen
import com.snaptab.app.ui.screen.expense.SplitScreen
import com.snaptab.app.ui.screen.groups.GroupDetailScreen
import com.snaptab.app.ui.screen.groups.GroupsScreen
import com.snaptab.app.ui.screen.home.HomeScreen
import com.snaptab.app.ui.screen.inbox.InboxScreen
import com.snaptab.app.ui.screen.personal.AddExpenseScreen
import com.snaptab.app.ui.screen.personal.MonthlyScreen
import com.snaptab.app.ui.screen.profile.ProfileScreen
import com.snaptab.app.ui.screen.scan.ScanFlow
import com.snaptab.app.ui.screen.settle.SettleScreen
import com.snaptab.app.ui.theme.Motion

object Routes {
    const val SIGN_IN = "sign_in"
    const val HOME = "home"
    const val GROUPS = "groups"
    const val INBOX = "inbox"
    const val SETTLE = "settle"

    const val SCAN = "scan"
    const val ADD_EXPENSE = "add_expense"
    const val MONTHLY = "monthly"
    const val PROFILE = "profile"
    const val EXPENSE = "expense"
    const val SPLIT = "split"
    const val GROUP = "group"

    fun expense(id: String) = "$EXPENSE/$id"
    fun split(id: String) = "$SPLIT/$id"
    fun group(id: String) = "$GROUP/$id"
    fun scan(alertId: String? = null) = if (alertId == null) SCAN else "$SCAN?alertId=$alertId"
}

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

private val bottomTabs = listOf(
    BottomTab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    BottomTab(Routes.GROUPS, R.string.nav_groups, Icons.Outlined.Groups),
    BottomTab(Routes.INBOX, R.string.nav_inbox, Icons.Outlined.Inbox),
    BottomTab(Routes.SETTLE, R.string.nav_settle, Icons.Outlined.SwapHoriz)
)

/**
 * The whole graph.
 *
 * Four tabs plus a centre scan button, and everything else pushed on top. The bottom bar
 * only shows on the four tabs, so a detail screen gets the full height.
 */
@Composable
fun SnapTabNavigation(
    deepLinkRoute: String? = null,
    onDeepLinkHandled: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
    viewModel: RootViewModel = hiltViewModel()
) {
    val root by viewModel.state.collectAsStateWithLifecycle()

    // A notification tap can arrive before or after the graph exists, so it is applied as
    // an effect rather than as a start destination.
    LaunchedEffect(deepLinkRoute, root.signedIn) {
        val target = deepLinkRoute ?: return@LaunchedEffect
        if (!root.signedIn) return@LaunchedEffect
        val route = when {
            target.startsWith("log_alert") -> Routes.INBOX
            target.startsWith(Routes.SCAN) -> target
            target.startsWith("shared_tab") -> Routes.HOME
            else -> target
        }
        // A notification that lands on a tab should select it, not stack a second copy on
        // top of wherever the user already was.
        runCatching {
            if (route in bottomTabs.map { it.route }) {
                navController.toTab(route)
            } else {
                navController.navigate(route) { launchSingleTop = true }
            }
        }
        onDeepLinkHandled()
    }

    if (!root.ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (!root.signedIn) {
        // Sign-in returns early, before the Scaffold below, so it needs its own copy of the
        // grid — it is the first screen anyone sees and the one that most wants the scenery.
        Box(modifier = Modifier.fillMaxSize()) {
            GridBackground()
            SignInScreen(
                onSignedIn = { /* the root state flips and this composable is replaced */ }
            )
        }
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomTabs.map { it.route }

    // POST_NOTIFICATIONS on Android 13+: without it the SMS receiver's notification is a
    // silent no-op, so it is asked for once the user is in.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* either way the alert still reaches the in-app inbox */ }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        viewModel.onSmsPermissionResult(granted.values.any { it })
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(visible = showBottomBar) {
                SnapTabBottomBar(
                    currentRoute = currentRoute,
                    unreadAlerts = root.unreadAlerts,
                    onSelect = navController::toTab,
                    onScan = { backStackEntry?.let { navController.push(it, Routes.scan()) } }
                )
            }
        }
    ) { padding ->
        val tabRoutes = remember { bottomTabs.map { it.route }.toSet() }

        Box(modifier = Modifier.fillMaxSize()) {
            // One grid for the whole app, drawn once here rather than per screen. It used to
            // live inside HomeScreen, which meant it vanished the moment you opened a tab.
            // Behind the NavHost it survives every navigation, so it never restarts its
            // animation mid-journey and costs one Canvas rather than thirteen. The screens
            // that own a Scaffold pass `containerColor = Color.Transparent` so their own
            // background does not paint over it.
            GridBackground()

            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(bottom = if (showBottomBar) padding.calculateBottomPadding() else 0.dp),
                // Tabs cross-fade because they are siblings; anything pushed on top slides,
                // because it is a layer above. Deciding per-transition rather than per-screen is
                // what keeps "back" feeling like the reverse of how you arrived.
                enterTransition = {
                    if (targetState.destination.route in tabRoutes) Motion.enterTab
                    else Motion.enterPush(this)
                },
                exitTransition = {
                    if (targetState.destination.route in tabRoutes) Motion.exitTab
                    else Motion.exitPush(this)
                },
                popEnterTransition = {
                    if (targetState.destination.route in tabRoutes) Motion.enterTab
                    else Motion.enterPop(this)
                },
                popExitTransition = {
                    if (targetState.destination.route in tabRoutes) Motion.exitTab
                    else Motion.exitPop(this)
                }
            ) {
                composable(Routes.HOME) { entry ->
                    HomeScreen(
                        onOpenExpense = { navController.push(entry, Routes.expense(it)) },
                        // A tab, so it switches tabs rather than stacking a second inbox.
                        onOpenInbox = { navController.toTab(Routes.INBOX) },
                        onOpenProfile = { navController.push(entry, Routes.PROFILE) },
                        onOpenMonthly = { navController.push(entry, Routes.MONTHLY) },
                        onAddExpense = { navController.push(entry, Routes.ADD_EXPENSE) }
                    )
                }

                composable(Routes.GROUPS) { entry ->
                    GroupsScreen(onOpenGroup = { navController.push(entry, Routes.group(it)) })
                }

                composable(Routes.INBOX) { entry ->
                    InboxScreen(
                        onOpenExpense = { navController.push(entry, Routes.expense(it)) },
                        onScanForAlert = { alert -> navController.push(entry, Routes.scan(alert.id)) },
                        onRequestSmsPermission = {
                            smsPermission.launch(
                                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
                            )
                        }
                    )
                }

                composable(Routes.SETTLE) { SettleScreen() }

                composable(
                    route = "${Routes.SCAN}?alertId={alertId}",
                    arguments = listOf(
                        navArgument("alertId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { entry ->
                    ScanFlow(
                        alertId = entry.arguments?.getString("alertId"),
                        onClose = { navController.up(entry) },
                        // Replace, not push: backing out of the expense should reach whatever
                        // opened the camera, not the camera again.
                        onSaved = { expenseId -> navController.replace(entry, Routes.expense(expenseId)) },
                        onSplit = { expenseId -> navController.replace(entry, Routes.split(expenseId)) },
                        onEnterByHand = { navController.replace(entry, Routes.ADD_EXPENSE) }
                    )
                }

                composable(Routes.ADD_EXPENSE) { entry ->
                    AddExpenseScreen(
                        onClose = { navController.up(entry) },
                        onSaved = { expenseId -> navController.replace(entry, Routes.expense(expenseId)) },
                        onScanInstead = { navController.replace(entry, Routes.scan()) },
                        onSplitInstead = { navController.toTab(Routes.GROUPS) }
                    )
                }

                composable(Routes.MONTHLY) { entry ->
                    MonthlyScreen(
                        onBack = { navController.up(entry) },
                        onOpenInbox = { navController.toTab(Routes.INBOX) },
                        onAddExpense = { navController.push(entry, Routes.ADD_EXPENSE) },
                        onOpenSettle = { navController.toTab(Routes.SETTLE) }
                    )
                }

                composable(Routes.PROFILE) { entry ->
                    ProfileScreen(
                        onBack = { navController.up(entry) },
                        onSignedOut = { /* the root state flips and sign-in takes over */ },
                        onOpenMonthly = { navController.push(entry, Routes.MONTHLY) },
                        onRequestSmsPermission = {
                            smsPermission.launch(
                                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
                            )
                        }
                    )
                }

                composable(
                    route = "${Routes.EXPENSE}/{expenseId}",
                    arguments = listOf(navArgument("expenseId") { type = NavType.StringType })
                ) { entry ->
                    val id = entry.arguments?.getString("expenseId").orEmpty()
                    ExpenseDetailScreen(
                        expenseId = id,
                        onBack = { navController.up(entry) },
                        onEditSplit = { navController.push(entry, Routes.split(it)) },
                        onSplitByItem = { navController.push(entry, Routes.split(it)) },
                        onDeleted = { navController.up(entry) }
                    )
                }

                composable(
                    route = "${Routes.SPLIT}/{expenseId}",
                    arguments = listOf(navArgument("expenseId") { type = NavType.StringType })
                ) { entry ->
                    val id = entry.arguments?.getString("expenseId").orEmpty()
                    SplitScreen(
                        expenseId = id,
                        onBack = { navController.up(entry) },
                        onSaved = { navController.up(entry) },
                        onSplitByItem = { /* the split screen handles item mode inline */ }
                    )
                }

                composable(
                    route = "${Routes.GROUP}/{groupId}",
                    arguments = listOf(navArgument("groupId") { type = NavType.StringType })
                ) { entry ->
                    val id = entry.arguments?.getString("groupId").orEmpty()
                    GroupDetailScreen(
                        groupId = id,
                        onBack = { navController.up(entry) },
                        onOpenExpense = { navController.push(entry, Routes.expense(it)) },
                        onAddExpense = { navController.push(entry, Routes.ADD_EXPENSE) },
                        onSettleUp = { navController.toTab(Routes.SETTLE) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapTabBottomBar(
    currentRoute: String?,
    unreadAlerts: Int,
    onSelect: (String) -> Unit,
    onScan: () -> Unit
) {
    Box {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            bottomTabs.forEachIndexed { index, tab ->
                // A gap in the middle for the scan button to sit over.
                if (index == 2) {
                    Spacer(Modifier.width(64.dp))
                }
                NavigationBarItem(
                    selected = currentRoute == tab.route,
                    onClick = { onSelect(tab.route) },
                    icon = {
                        if (tab.route == Routes.INBOX && unreadAlerts > 0) {
                            BadgedBox(badge = { Badge { Text(unreadAlerts.coerceAtMost(99).toString()) } }) {
                                Icon(tab.icon, contentDescription = null)
                            }
                        } else {
                            Icon(tab.icon, contentDescription = null)
                        }
                    },
                    label = { Text(stringResource(tab.labelRes)) }
                )
            }
        }

        FloatingActionButton(
            onClick = onScan,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-26).dp)
                .size(60.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.DocumentScanner,
                contentDescription = stringResource(R.string.nav_scan),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
