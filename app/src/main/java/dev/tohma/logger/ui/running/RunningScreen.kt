package dev.tohma.logger.ui.running

import androidx.compose.foundation.layout.Box
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
import dev.tohma.logger.LoggerApp
import dev.tohma.logger.data.db.SessionRow
import dev.tohma.logger.domain.SessionEngine
import dev.tohma.logger.domain.StoppedSession
import dev.tohma.logger.domain.formatClock
import dev.tohma.logger.ui.common.ColorDot
import dev.tohma.logger.ui.common.ConfirmDialog
import dev.tohma.logger.ui.common.elapsedSeconds
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
    data class Active(val row: SessionRow) : RunningState
}

@OptIn(FlowPreview::class)
class RunningViewModel(
    private val engine: SessionEngine,
    /** Outlives this ViewModel, so a pending edit can still be saved after the screen closes. */
    private val appScope: CoroutineScope,
) : ViewModel() {
    val state: StateFlow<RunningState?> = engine.running
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
                RunningViewModel(container.sessionEngine, container.appScope)
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

    // Leave when the session was stopped elsewhere (for example from the notification later on).
    LaunchedEffect(state) { if (state == RunningState.None) onClose() }

    val row = (state as? RunningState.Active)?.row ?: return

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
            Text(
                formatClock(elapsedSeconds(row.session.startedAt)),
                fontSize = 64.sp,
                fontFamily = FontFamily.Monospace,
            )
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
                "Time keeps counting if you leave the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = TextAlign.Center,
            )
        }
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
