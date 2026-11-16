package com.aleksalatalo.logger.ui.running

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aleksalatalo.logger.LoggerApp
import com.aleksalatalo.logger.data.db.SessionRow
import com.aleksalatalo.logger.domain.SessionEngine
import com.aleksalatalo.logger.domain.StoppedSession
import com.aleksalatalo.logger.domain.formatClock
import com.aleksalatalo.logger.ui.common.ColorDot
import com.aleksalatalo.logger.ui.common.ConfirmDialog
import com.aleksalatalo.logger.domain.RunningSession
import com.aleksalatalo.logger.ui.common.modeLabel
import com.aleksalatalo.logger.system.SessionEffects
import com.aleksalatalo.logger.ui.common.displaySeconds
import com.aleksalatalo.logger.ui.common.rememberNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** null = still loading; [RunningState.None] = nothing is running. */
sealed interface RunningState {
    data object None : RunningState
    data class Active(val running: RunningSession) : RunningState
}

@OptIn(FlowPreview::class)
class RunningViewModel(
    private val engine: SessionEngine,
    /** Outlives this ViewModel, so a pending edit can still be saved after the screen closes. */
    private val appScope: CoroutineScope,
    private val effects: SessionEffects,
) : ViewModel() {
    val state: StateFlow<RunningState?> = engine.runningSession
        .map { if (it == null) RunningState.None else RunningState.Active(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Pending description edits, saved shortly after typing stops. */
    private val descriptionEdits = MutableStateFlow<Pair<Long, String>?>(null)

    init {
        viewModelScope.launch {
            descriptionEdits.debounce(500).collect { edit ->
                edit?.let { (id, text) -> engine.setDescription(id, text) }
            }
        }
    }

    fun onDescriptionChange(sessionId: Long, text: String) {
        descriptionEdits.value = sessionId to text
    }

    /** Saves any pending description first so it is never lost when stopping. */
    private suspend fun flushDescription() {
        descriptionEdits.value?.let { (id, text) -> engine.setDescription(id, text) }
        descriptionEdits.value = null
    }

    fun stop(onStopped: (StoppedSession) -> Unit) {
        viewModelScope.launch {
            flushDescription()
            engine.stop()?.let(onStopped)
        }
    }

    fun expireDue() {
        appScope.launch { effects.expireDue() }
    }

    fun startEarlier(minutes: Long) {
        viewModelScope.launch { engine.startEarlier(minutes * 60) }
    }

    fun discard(onDone: () -> Unit) {
        viewModelScope.launch {
            descriptionEdits.value = null
            engine.discardRunning()
            onDone()
        }
    }

    override fun onCleared() {
        descriptionEdits.value?.let { (id, text) -> appScope.launch { engine.setDescription(id, text) } }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as LoggerApp).container
                RunningViewModel(container.sessionEngine, container.appScope, container.sessionEffects)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunningScreen(
    onStopped: (StoppedSession) -> Unit,
    onClose: () -> Unit,
    viewModel: RunningViewModel = viewModel(factory = RunningViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var startEarlier by remember { mutableStateOf(false) }

    // Leave when the session was stopped elsewhere (for example from the notification later on).
    LaunchedEffect(state) { if (state == RunningState.None) onClose() }

    val running = (state as? RunningState.Active)?.running ?: return
    val row = running.row

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Started earlier…") },
                                onClick = { menuOpen = false; startEarlier = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Discard session") },
                                onClick = { menuOpen = false; confirmDiscard = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(row.activityColor, size = 16)
                Spacer(Modifier.width(10.dp))
                Text(row.activityName, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            Text(formatClock(displaySeconds(running)), fontSize = 64.sp, fontFamily = FontFamily.Monospace)
            modeLabel(running)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val planned = running.endsAt
            if (planned != null) {
                // Backup for a missed alarm: handle the deadline shortly after it passed.
                val overdue = rememberNow().value - planned > 3_000
                LaunchedEffect(overdue) { if (overdue) viewModel.expireDue() }
            }
            Spacer(Modifier.height(32.dp))
            // Local text state: the DB copy lags behind by the debounce and must not overwrite typing.
            var description by remember(row.session.id) { mutableStateOf(row.session.description ?: "") }
            OutlinedTextField(
                value = description,
                onValueChange = {
                    description = it
                    viewModel.onDescriptionChange(row.session.id, it)
                },
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { viewModel.stop(onStopped) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Text("Stop", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    running.phase != null -> "You'll get an alert at every work and break change."
                    planned != null -> "You'll get an alert when the timer ends."
                    else -> "Time keeps counting if you leave the app."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (startEarlier) {
        StartEarlierDialog(
            onConfirm = { minutes -> startEarlier = false; viewModel.startEarlier(minutes) },
            onDismiss = { startEarlier = false },
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard session?",
            text = "The time recorded for \"${row.activityName}\" will not be saved.",
            confirmLabel = "Discard",
            onConfirm = { confirmDiscard = false; viewModel.discard(onClose) },
            onDismiss = { confirmDiscard = false },
        )
    }
}

private val EARLIER_PRESETS_MIN = listOf(5L, 10L, 15L, 30L)

/** Moves the start of the running session back by a number of minutes. */
@Composable
private fun StartEarlierDialog(onConfirm: (minutes: Long) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf("10") }
    val value = minutes.toLongOrNull() ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Started earlier") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add time you spent before starting the session.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EARLIER_PRESETS_MIN.forEach { m ->
                        FilterChip(selected = value == m, onClick = { minutes = m.toString() }, label = { Text("$m") })
                    }
                }
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { v -> if (v.length <= 4 && v.all(Char::isDigit)) minutes = v },
                    label = { Text("Minutes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = value > 0, onClick = { onConfirm(value) }) { Text("Add $value min") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
