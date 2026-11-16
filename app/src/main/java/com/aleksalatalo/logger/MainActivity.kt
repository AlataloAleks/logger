package com.aleksalatalo.logger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.aleksalatalo.logger.ui.StartRequest
import androidx.compose.runtime.setValue
import com.aleksalatalo.logger.ui.LoggerNavHost
import com.aleksalatalo.logger.ui.theme.LoggerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Incremented each time a notification asks to show the running session. */
    private var openRunningRequests by mutableIntStateOf(0)

    /** Activity whose start panel a widget asked to open, with a counter so repeats are noticed. */
    private var startRequest by mutableStateOf<StartRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            LoggerTheme {
                LoggerNavHost(
                    openRunningRequests = openRunningRequests,
                    startRequest = startRequest,
                    onStartRequestHandled = { startRequest = null },
                )
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
        intent.getLongExtra(EXTRA_START_ACTIVITY, -1).takeIf { it > 0 }?.let { id ->
            startRequest = StartRequest(id, (startRequest?.nonce ?: 0) + 1)
            intent.removeExtra(EXTRA_START_ACTIVITY)
        }
    }

    companion object {
        const val EXTRA_OPEN_RUNNING = "open_running"
        const val EXTRA_START_ACTIVITY = "start_activity"
    }
}
