package com.snaptab.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.snaptab.app.navigation.SnapTabNavigation
import com.snaptab.app.ui.theme.SnapTabTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Where a notification wants to land. Held as state so a tap that arrives while the
     * activity is already running still navigates — `singleTask` means the second tap
     * comes through onNewIntent, not onCreate.
     */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingRoute = routeFrom(intent)

        setContent {
            SnapTabTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SnapTabNavigation(
                        deepLinkRoute = pendingRoute,
                        onDeepLinkHandled = { pendingRoute = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFrom(intent)?.let { pendingRoute = it }
    }

    /**
     * Two ways in: a notification carrying EXTRA_ROUTE, or an https://snaptab.app/t/<token>
     * link to a shared tab.
     */
    private fun routeFrom(intent: Intent?): String? {
        intent?.getStringExtra(EXTRA_ROUTE)?.takeIf { it.isNotBlank() }?.let { return it }

        val path = intent?.data?.path ?: return null
        val token = path.removePrefix("/t/").trim('/')
        return if (token.isNotBlank()) "$ROUTE_SHARED_TAB/$token" else null
    }

    companion object {
        const val EXTRA_ROUTE = "snaptab_route"

        const val ROUTE_HOME = "home"
        const val ROUTE_SCAN = "scan"
        const val ROUTE_INBOX = "inbox"
        /** The sheet that offers "personal" or one of the user's existing groups. */
        const val ROUTE_LOG_ALERT = "log_alert"
        const val ROUTE_SHARED_TAB = "shared_tab"
    }
}
