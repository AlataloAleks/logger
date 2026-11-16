package com.aleksalatalo.logger.ui

import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aleksalatalo.logger.domain.StoppedSession
import com.aleksalatalo.logger.domain.formatDuration
import com.aleksalatalo.logger.ui.activities.ActivitiesScreen
import com.aleksalatalo.logger.ui.history.HistoryScreen
import com.aleksalatalo.logger.ui.running.RunningScreen
import com.aleksalatalo.logger.ui.sessions.SessionEditorScreen
import com.aleksalatalo.logger.ui.settings.SettingsScreen
import com.aleksalatalo.logger.ui.stats.StatsScreen
import kotlinx.coroutines.launch

/** Request to open the start panel for [activityId]; [nonce] distinguishes repeated taps. */
data class StartRequest(val activityId: Long, val nonce: Int)

object Routes {
    const val HOME = "home"
    const val RUNNING = "running"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val EDITOR = "editor?sessionId={sessionId}&activityId={activityId}&description={description}"

    fun editSession(sessionId: Long) = "editor?sessionId=$sessionId"

    fun newManual(activityId: Long?, description: String?): String {
        val params = buildList {
            if (activityId != null) add("activityId=$activityId")
            if (!description.isNullOrBlank()) add("description=${Uri.encode(description)}")
        }
        return if (params.isEmpty()) "editor" else "editor?" + params.joinToString("&")
    }
}

@Composable
fun LoggerNavHost(
    openRunningRequests: Int,
    startRequest: StartRequest?,
    onStartRequestHandled: () -> Unit,
) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val onStopped: (StoppedSession) -> Unit = { stopped ->
        nav.popBackStack(Routes.HOME, inclusive = false)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = "Saved ${formatDuration(stopped.seconds)}",
                actionLabel = "Edit",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) nav.navigate(Routes.editSession(stopped.sessionId))
        }
    }

    LaunchedEffect(openRunningRequests) {
        if (openRunningRequests > 0) nav.navigate(Routes.RUNNING) { launchSingleTop = true }
    }

    // A widget's quick-start button: show the start panel on the home screen.
    LaunchedEffect(startRequest) {
        if (startRequest != null) nav.popBackStack(Routes.HOME, inclusive = false)
    }

    RecoveryDialog(onStopped = onStopped)

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            ActivitiesScreen(
                snackbarHostState = snackbar,
                onOpenRunning = { nav.navigate(Routes.RUNNING) },
                onOpenHistory = { nav.navigate(Routes.HISTORY) },
                onOpenStats = { nav.navigate(Routes.STATS) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onManualEntry = { activityId, description -> nav.navigate(Routes.newManual(activityId, description)) },
                onStopped = onStopped,
                startRequest = startRequest,
                onStartRequestHandled = onStartRequestHandled,
            )
        }
        composable(Routes.RUNNING) {
            RunningScreen(onStopped = onStopped, onClose = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.STATS) {
            StatsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { nav.popBackStack() },
                onOpenSession = { row ->
                    nav.navigate(if (row.session.endedAt == null) Routes.RUNNING else Routes.editSession(row.session.id))
                },
                onAddManual = { nav.navigate(Routes.newManual(null, null)) },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("activityId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("description") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            SessionEditorScreen(onDone = { nav.popBackStack() })
        }
    }
}
