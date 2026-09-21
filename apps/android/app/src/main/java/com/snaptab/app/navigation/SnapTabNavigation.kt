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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.snaptab.app.R
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
        runCatching { navController.navigate(route) }
        onDeepLinkHandled()
    }

    if (!root.ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (!root.signedIn) {
        SignInScreen(
            onSignedIn = { /* the root state flips and this composable is replaced */ },
            onNeedsGoogleSignIn = viewModel::onGoogleSignInRequested
        )
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
                    onSelect = { route ->
                        navController.navigate(route) {
                            // Single instance per tab, and the back button leaves the app from
                            // the start destination rather than walking the tab history.
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onScan = { navController.navigate(Routes.scan()) }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(bottom = if (showBottomBar) padding.calculateBottomPadding() else 0.dp)
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenExpense = { navController.navigate(Routes.expense(it)) },
                    onOpenInbox = { navController.navigate(Routes.INBOX) },
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenMonthly = { navController.navigate(Routes.MONTHLY) },
                    onAddExpense = { navController.navigate(Routes.ADD_EXPENSE) }
                )
            }

            composable(Routes.GROUPS) {
                GroupsScreen(onOpenGroup = { navController.navigate(Routes.group(it)) })
            }

            composable(Routes.INBOX) {
                InboxScreen(
                    onOpenExpense = { navController.navigate(Routes.expense(it)) },
                    onScanForAlert = { alert -> navController.navigate(Routes.scan(alert.id)) },
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
                    onClose = { navController.popBackStack() },
                    onSaved = { expenseId ->
                        navController.popBackStack()
                        navController.navigate(Routes.expense(expenseId))
                    },
                    onSplit = { expenseId ->
                        navController.popBackStack()
                        navController.navigate(Routes.split(expenseId))
                    },
                    onEnterByHand = {
                        navController.popBackStack()
                        navController.navigate(Routes.ADD_EXPENSE)
                    }
                )
            }

            composable(Routes.ADD_EXPENSE) {
                AddExpenseScreen(
                    onClose = { navController.popBackStack() },
                    onSaved = { expenseId ->
                        navController.popBackStack()
                        navController.navigate(Routes.expense(expenseId))
                    },
                    onScanInstead = {
                        navController.popBackStack()
                        navController.navigate(Routes.scan())
                    },
                    onSplitInstead = { navController.navigate(Routes.GROUPS) }
                )
            }

            composable(Routes.MONTHLY) {
                MonthlyScreen(
                    onBack = { navController.popBackStack() },
                    onOpenInbox = { navController.navigate(Routes.INBOX) },
                    onAddExpense = { navController.navigate(Routes.ADD_EXPENSE) },
                    onOpenSettle = { navController.navigate(Routes.SETTLE) }
                )
            }

            composable(Routes.PROFILE) {
                ProfileScreen(
                    onBack = { navController.popBackStack() },
                    onSignedOut = { /* the root state flips and sign-in takes over */ },
                    onOpenMonthly = { navController.navigate(Routes.MONTHLY) },
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
                    onBack = { navController.popBackStack() },
                    onEditSplit = { navController.navigate(Routes.split(it)) },
                    onSplitByItem = { navController.navigate(Routes.split(it)) },
                    onDeleted = { navController.popBackStack() }
                )
            }

            composable(
                route = "${Routes.SPLIT}/{expenseId}",
                arguments = listOf(navArgument("expenseId") { type = NavType.StringType })
            ) { entry ->
                val id = entry.arguments?.getString("expenseId").orEmpty()
                SplitScreen(
                    expenseId = id,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
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
                    onBack = { navController.popBackStack() },
                    onOpenExpense = { navController.navigate(Routes.expense(it)) },
                    onAddExpense = { navController.navigate(Routes.ADD_EXPENSE) },
                    onSettleUp = { navController.navigate(Routes.SETTLE) }
                )
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
