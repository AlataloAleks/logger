package dev.tohma.logger.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tohma.logger.AppContainer
import dev.tohma.logger.LoggerApp
import dev.tohma.logger.data.db.SessionMode
import dev.tohma.logger.data.db.SessionRow
import dev.tohma.logger.domain.StoppedSession
import dev.tohma.logger.domain.formatDuration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Asks what to do with a stopwatch that was left running while the app was closed in a way
 * that removed its notification (force-stop, reboot, notification swiped away).
 */
class RecoveryViewModel(private val container: AppContainer) : ViewModel() {
    var candidate by mutableStateOf<SessionRow?>(null)
        private set

    init {
        if (!container.recoveryHandled) {
            container.recoveryHandled = true
            viewModelScope.launch {
                val row = container.sessionEngine.running.first()
                if (row != null && row.session.mode == SessionMode.STOPWATCH && !container.runningNotificationWasShowing) {
                    candidate = row
                }
            }
        }
    }

    fun keepRunning() {
        candidate = null
    }

    fun endNow(onStopped: (StoppedSession) -> Unit) = finish { container.sessionEngine.stop()?.let(onStopped) }

    fun endAt(endedAt: Long, onStopped: (StoppedSession) -> Unit) =
        finish { container.sessionEngine.stopAt(endedAt)?.let(onStopped) }

    fun discard() = finish { container.sessionEngine.discardRunning() }

    private fun finish(block: suspend () -> Unit) {
        candidate = null
        viewModelScope.launch { block() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { RecoveryViewModel((this[APPLICATION_KEY] as LoggerApp).container) }
        }
    }
}

private val startFormat = DateTimeFormatter.ofPattern("EEE d MMM 'at' HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryDialog(
    onStopped: (StoppedSession) -> Unit,
    viewModel: RecoveryViewModel = viewModel(factory = RecoveryViewModel.Factory),
) {
    val row = viewModel.candidate ?: return
    val s = row.session
    val zone = ZoneId.of(s.zoneId)
    var pickEnd by remember { mutableStateOf(false) }
    var endError by remember { mutableStateOf<String?>(null) }

    if (!pickEnd) {
        val now = System.currentTimeMillis()
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Still tracking ${row.activityName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Started ${Instant.ofEpochMilli(s.startedAt).atZone(zone).format(startFormat)}, " +
                            "${formatDuration((now - s.startedAt) / 1000)} ago. The app was closed while it was running.",
                    )
                    Column(Modifier.fillMaxWidth()) {
                        TextButton(onClick = viewModel::keepRunning) { Text("Keep running") }
                        TextButton(onClick = { viewModel.endNow(onStopped) }) { Text("End now") }
                        TextButton(onClick = { pickEnd = true }) { Text("End at…") }
                        TextButton(onClick = viewModel::discard) {
                            Text("Discard session", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    } else {
        val state = rememberTimePickerState(is24Hour = DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(
            onDismissRequest = { pickEnd = false },
            title = { Text("When did you stop?") },
            text = {
                Column {
                    Box(Modifier.fillMaxWidth()) { TimePicker(state = state) }
                    endError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val now = System.currentTimeMillis()
                    // The latest moment at the chosen time that is not in the future.
                    var end = ZonedDateTime.now(zone).with(LocalTime.of(state.hour, state.minute))
                    if (end.toInstant().toEpochMilli() > now) end = end.minusDays(1)
                    val endMillis = end.toInstant().toEpochMilli()
                    if (endMillis < s.startedAt) {
                        endError = "That is before the session started"
                    } else {
                        viewModel.endAt(endMillis, onStopped)
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pickEnd = false; endError = null }) { Text("Back") } },
        )
    }
}
