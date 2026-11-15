package dev.tohma.logger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.tohma.logger.ui.LoggerNavHost
import dev.tohma.logger.ui.theme.LoggerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Incremented each time a notification asks to show the running session. */
    private var openRunningRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            LoggerTheme {
                LoggerNavHost(openRunningRequests = openRunningRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Finish timers whose alarm was missed and restore a notification that was swiped away.
        val container = (application as LoggerApp).container
        container.appScope.launch { container.sessionEffects.refresh() }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_RUNNING, false)) {
            openRunningRequests++
            // Handle it once, not again after a configuration change.
            intent.removeExtra(EXTRA_OPEN_RUNNING)
        }
    }

    companion object {
        const val EXTRA_OPEN_RUNNING = "open_running"
    }
}
