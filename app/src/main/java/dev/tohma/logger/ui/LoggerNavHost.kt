package dev.tohma.logger.ui

import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.tohma.logger.domain.StoppedSession
import dev.tohma.logger.domain.formatDuration
import dev.tohma.logger.ui.activities.ActivitiesScreen
import dev.tohma.logger.ui.history.HistoryScreen
import dev.tohma.logger.ui.running.RunningScreen
import dev.tohma.logger.ui.sessions.SessionEditorScreen
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val RUNNING = "running"
    const val HISTORY = "history"
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
fun LoggerNavHost() {
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

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            ActivitiesScreen(
                snackbarHostState = snackbar,
                onOpenRunning = { nav.navigate(Routes.RUNNING) },
                onOpenHistory = { nav.navigate(Routes.HISTORY) },
                onManualEntry = { activityId, description -> nav.navigate(Routes.newManual(activityId, description)) },
                onStopped = onStopped,
            )
        }
        composable(Routes.RUNNING) {
            RunningScreen(onStopped = onStopped, onClose = { nav.popBackStack() })
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
