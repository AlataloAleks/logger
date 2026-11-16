package com.aleksalatalo.logger.ui.sessions

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aleksalatalo.logger.LoggerApp
import com.aleksalatalo.logger.data.ActivityRepository
import com.aleksalatalo.logger.data.db.ActivityEntity
import com.aleksalatalo.logger.domain.SessionEngine
import com.aleksalatalo.logger.ui.common.ColorDot
import com.aleksalatalo.logger.ui.common.ConfirmDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class EditorForm(
    val activityId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    /** Optional for new sessions; null means "work it out from the date and duration". */
    val startTime: LocalTime? = null,
    val hours: String = "",
    val minutes: String = "",
    val seconds: String = "",
    val description: String = "",
)

class SessionEditorViewModel(
    handle: SavedStateHandle,
    private val engine: SessionEngine,
    private val activityRepository: ActivityRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val sessionId: Long = handle.get<Long>("sessionId")?.takeIf { it > 0 } ?: -1L
    val isNew = sessionId < 0

    var loaded by mutableStateOf(false); private set
    var form by mutableStateOf(EditorForm()); private set
    var activities by mutableStateOf(emptyList<ActivityEntity>()); private set
    var error by mutableStateOf<String?>(null); private set

    /** Zone the session is shown and edited in: its own for existing sessions, the device's for new ones. */
    private var zone: ZoneId = ZoneId.systemDefault()

    init {
        viewModelScope.launch {
            val all = activityRepository.activities.first()
            if (isNew) {
                val preselected = handle.get<Long>("activityId")?.takeIf { it > 0 }
                form = EditorForm(
                    activityId = preselected ?: all.firstOrNull { !it.archived }?.id,
                    date = LocalDate.now(zone),
                    description = handle.get<String>("description") ?: "",
                )
                activities = all.filter { !it.archived }
            } else {
                val s = engine.get(sessionId)
                if (s != null) {
                    zone = ZoneId.of(s.zoneId)
                    breakSeconds = s.breakSeconds
                    val start = Instant.ofEpochMilli(s.startedAt).atZone(zone)
                    form = EditorForm(
                        activityId = s.activityId,
                        date = start.toLocalDate(),
                        startTime = start.toLocalTime().withNano(0),
                        hours = (s.workSeconds / 3600).toString(),
                        minutes = (s.workSeconds % 3600 / 60).toString(),
                        seconds = (s.workSeconds % 60).toString(),
                        description = s.description ?: "",
                    )
                    // Keep the session's own activity selectable even if it has been archived.
                    activities = all.filter { !it.archived || it.id == s.activityId }
                }
            }
            loaded = true
        }
    }

    fun update(transform: (EditorForm) -> EditorForm) {
        form = transform(form)
        error = null
    }

    /** Pomodoro break time; part of the session's span but not of the edited duration. */
    private var breakSeconds = 0L

    private fun EditorForm.durationSeconds(): Long =
        (hours.toLongOrNull() ?: 0) * 3600 + (minutes.toLongOrNull() ?: 0) * 60 + (seconds.toLongOrNull() ?: 0)

    /**
     * For an existing session, changing the duration keeps the end time and moves the start: the
     * usual mistake is starting late, not stopping late. New manual entries keep their start.
     */
    fun updateDuration(transform: (EditorForm) -> EditorForm) {
        val before = form
        val after = transform(before)
        val startTime = before.startTime
        form = if (!isNew && startTime != null) {
            val end = ZonedDateTime.of(before.date, startTime, zone).plusSeconds(before.durationSeconds() + breakSeconds)
            val start = end.minusSeconds(after.durationSeconds() + breakSeconds)
            after.copy(date = start.toLocalDate(), startTime = start.toLocalTime())
        } else {
            after
        }
        error = null
    }

    /** End time shown under the duration of an existing session. */
    fun endTime(): ZonedDateTime? {
        val f = form
        val startTime = f.startTime ?: return null
        return ZonedDateTime.of(f.date, startTime, zone).plusSeconds(f.durationSeconds() + breakSeconds)
    }

    fun today(): LocalDate = LocalDate.now(zone)

    fun save(onDone: () -> Unit) {
        val f = form
        val activityId = f.activityId ?: run { error = "Choose an activity"; return }
        val h = f.hours.toLongOrNull() ?: 0
        val m = f.minutes.toLongOrNull() ?: 0
        val s = f.seconds.toLongOrNull() ?: 0
        val duration = h * 3600 + m * 60 + s
        if (duration <= 0) { error = "Enter a duration"; return }

        val now = clock()
        val startedAt = when {
            f.startTime != null -> ZonedDateTime.of(f.date, f.startTime, zone).toInstant().toEpochMilli()
            f.date == today() -> now - duration * 1000
            else -> ZonedDateTime.of(f.date, LocalTime.NOON, zone).toInstant().toEpochMilli()
        }
        // One minute of tolerance for the seconds that pass while filling in the form.
        if (startedAt + duration * 1000 > now + 60_000) { error = "The session can't end in the future"; return }

        viewModelScope.launch {
            if (isNew) {
                engine.addManual(activityId, startedAt, duration, f.description)
            } else {
                engine.editFinished(sessionId, activityId, startedAt, duration, f.description)
            }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            engine.delete(sessionId)
            onDone()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as LoggerApp).container
                SessionEditorViewModel(createSavedStateHandle(), c.sessionEngine, c.activityRepository)
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private const val DAY_MS = 86_400_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionEditorScreen(
    onDone: () -> Unit,
    viewModel: SessionEditorViewModel = viewModel(factory = SessionEditorViewModel.Factory),
) {
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val form = viewModel.form

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isNew) "New session" else "Edit session") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                },
                actions = {
                    if (!viewModel.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete session")
                        }
                    }
                    TextButton(enabled = viewModel.loaded, onClick = { viewModel.save(onDone) }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (!viewModel.loaded) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Label("Activity")
            if (viewModel.activities.isEmpty()) {
                Text("Create an activity first.", color = MaterialTheme.colorScheme.error)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(viewModel.activities, key = { it.id }) { a ->
                        FilterChip(
                            selected = form.activityId == a.id,
                            onClick = { viewModel.update { it.copy(activityId = a.id) } },
                            label = { Text(a.name) },
                            leadingIcon = { ColorDot(a.color, size = 10) },
                        )
                    }
                }
            }

            Label("Date")
            OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                Text(form.date.format(dateFormat))
            }

            Label("Start time")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) {
                    Text(form.startTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "Not set (optional)")
                }
                if (form.startTime != null && viewModel.isNew) {
                    TextButton(onClick = { viewModel.update { it.copy(startTime = null) } }) { Text("Clear") }
                }
            }

            Label("Duration")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Hours", form.hours, Modifier.weight(1f)) { v -> viewModel.updateDuration { it.copy(hours = v) } }
                NumberField("Min", form.minutes, Modifier.weight(1f)) { v -> viewModel.updateDuration { it.copy(minutes = v) } }
                NumberField("Sec", form.seconds, Modifier.weight(1f)) { v -> viewModel.updateDuration { it.copy(seconds = v) } }
            }
            if (!viewModel.isNew) {
                viewModel.endTime()?.let { end ->
                    Text(
                        "Ends at ${end.format(DateTimeFormatter.ofPattern("HH:mm"))}. Changing the duration moves the start time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = form.description,
                onValueChange = { v -> viewModel.update { it.copy(description = v) } },
                label = { Text("Description (optional)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (pickDate) {
        val todayUtcMs = viewModel.today().toEpochDay() * DAY_MS
        val state = rememberDatePickerState(
            initialSelectedDateMillis = form.date.toEpochDay() * DAY_MS,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtcMs
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        viewModel.update { it.copy(date = LocalDate.ofEpochDay(ms / DAY_MS)) }
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }

    if (pickTime) {
        val initial = form.startTime ?: LocalTime.now()
        val state = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { Box(Modifier.fillMaxWidth()) { TimePicker(state = state) } },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.update { it.copy(startTime = LocalTime.of(state.hour, state.minute)) }
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete session?",
            text = "This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; viewModel.delete(onDone) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 4 && v.all(Char::isDigit)) onChange(v) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
