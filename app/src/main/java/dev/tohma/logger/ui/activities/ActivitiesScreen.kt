package dev.tohma.logger.ui.activities

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.tohma.logger.LoggerApp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tohma.logger.data.NameError
import dev.tohma.logger.data.db.ActivityEntity
import dev.tohma.logger.data.db.ActivityGroupEntity
import dev.tohma.logger.domain.StoppedSession
import dev.tohma.logger.ui.common.ColorDot
import dev.tohma.logger.ui.common.ConfirmDialog
import dev.tohma.logger.ui.common.RunningBanner
import dev.tohma.logger.ui.common.SectionHeader
import dev.tohma.logger.ui.theme.ActivityPalette

/** Which dialog is open. Only one at a time. */
private sealed interface DialogState {
    data class EditActivity(val activity: ActivityEntity?) : DialogState
    data class DeleteActivity(val activity: ActivityEntity, val sessionCount: Int) : DialogState
    data object Groups : DialogState
    data class Start(val activity: ActivityEntity) : DialogState
    data class Switch(
        val activity: ActivityEntity,
        val description: String?,
        val timerSeconds: Long?,
        val pomodoro: Pomodoro?,
        val runningName: String,
    ) : DialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesScreen(
    snackbarHostState: SnackbarHostState,
    onOpenRunning: () -> Unit,
    onOpenHistory: () -> Unit,
    onManualEntry: (activityId: Long, description: String?) -> Unit,
    onStopped: (StoppedSession) -> Unit,
    viewModel: ActivitiesViewModel = viewModel(factory = ActivitiesViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pomodoroDefaults by viewModel.pomodoroDefaults.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    // Ask for notification permission (Android 13+) the first time a session starts; start either way.
    val notifications = (LocalContext.current.applicationContext as LoggerApp).container.notifications
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPermission?.invoke()
        afterPermission = null
    }
    val withNotificationPermission: (() -> Unit) -> Unit = { action ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifications.canPost()) {
            afterPermission = action
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
    var notificationsOff by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsOff = !notifications.canPost() }

    val onDelete: (ActivityEntity) -> Unit = { activity ->
        viewModel.loadSessionCount(activity) { count -> dialog = DialogState.DeleteActivity(activity, count) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activities") },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "History")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Manage groups") },
                                onClick = { menuOpen = false; dialog = DialogState.Groups },
                            )
                            DropdownMenuItem(
                                text = { Text(if (state.showArchived) "Hide archived" else "Show archived") },
                                onClick = { menuOpen = false; viewModel.toggleShowArchived() },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialog = DialogState.EditActivity(null) }) {
                Icon(Icons.Default.Add, contentDescription = "New activity")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        state.running?.let { row ->
            RunningBanner(row, onClick = onOpenRunning, onStop = { viewModel.stop(onStopped) })
        }
        if (notificationsOff && state.activityCount > 0) NotificationsOffCard()
        if (!state.loading && state.activityCount == 0) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No activities yet.\nTap + to create one.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp),
            ) {
                state.sections.forEach { section ->
                    section.group?.let { group ->
                        item(key = "group-${group.id}") { SectionHeader(group.name) }
                    }
                    items(section.activities, key = { it.id }) { activity ->
                        ActivityRow(
                            activity = activity,
                            onClick = { dialog = DialogState.Start(activity) },
                            onEdit = { dialog = DialogState.EditActivity(activity) },
                            onArchiveToggle = { viewModel.setArchived(activity, true) },
                            onDelete = { onDelete(activity) },
                        )
                    }
                }
                if (state.showArchived && state.archived.isNotEmpty()) {
                    item(key = "archived-header") { SectionHeader("Archived") }
                    items(state.archived, key = { it.id }) { activity ->
                        ActivityRow(
                            activity = activity,
                            onClick = { dialog = DialogState.EditActivity(activity) },
                            onEdit = { dialog = DialogState.EditActivity(activity) },
                            onArchiveToggle = { viewModel.setArchived(activity, false) },
                            onDelete = { onDelete(activity) },
                        )
                    }
                }
            }
        }
        }
    }

    when (val d = dialog) {
        is DialogState.EditActivity -> ActivityEditorDialog(
            activity = d.activity,
            groups = state.groups,
            defaultColor = ActivityPalette[state.activityCount % ActivityPalette.size],
            onSave = { name, groupId, color, onError ->
                viewModel.saveActivity(d.activity?.id, name, groupId, color) { error ->
                    if (error == null) dialog = null else onError(error)
                }
            },
            onDismiss = { dialog = null },
        )
        is DialogState.DeleteActivity -> ConfirmDialog(
            title = "Delete \"${d.activity.name}\"?",
            text = when (d.sessionCount) {
                0 -> "This cannot be undone."
                1 -> "Its 1 recorded session will also be deleted. This cannot be undone."
                else -> "Its ${d.sessionCount} recorded sessions will also be deleted. This cannot be undone."
            } + if (!d.activity.archived) "\n\nTo keep the history, archive it instead." else "",
            confirmLabel = "Delete",
            onConfirm = { viewModel.deleteActivity(d.activity); dialog = null },
            onDismiss = { dialog = null },
        )
        DialogState.Groups -> GroupsDialog(
            groups = state.groups,
            onCreate = viewModel::createGroup,
            onRename = viewModel::renameGroup,
            onDelete = viewModel::deleteGroup,
            onDismiss = { dialog = null },
        )
        is DialogState.Start -> StartSheet(
            activity = d.activity,
            initialTimerSeconds = viewModel.lastTimerSeconds,
            pomodoroDefaults = pomodoroDefaults,
            onStart = { description, timerSeconds, pomodoro ->
                withNotificationPermission {
                    viewModel.start(
                        d.activity,
                        description,
                        timerSeconds,
                        pomodoro,
                        onStarted = { dialog = null; onOpenRunning() },
                        onConflict = {
                            dialog = DialogState.Switch(
                                d.activity,
                                description,
                                timerSeconds,
                                pomodoro,
                                state.running?.row?.activityName ?: "another activity",
                            )
                        },
                    )
                }
            },
            onManualEntry = { description -> dialog = null; onManualEntry(d.activity.id, description) },
            onDismiss = { dialog = null },
        )
        is DialogState.Switch -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Switch activity?") },
            text = { Text("\"${d.runningName}\" is running. Stop it and start \"${d.activity.name}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.switchTo(d.activity, d.description, d.timerSeconds, d.pomodoro) { dialog = null; onOpenRunning() }
                    },
                ) { Text("Switch") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

private enum class StartMode(val label: String) {
    STOPWATCH("Stopwatch"), TIMER("Timer"), POMODORO("Pomodoro"), MANUAL("Manual"),
}

private val TIMER_PRESETS_MIN = listOf(1, 2, 5, 10, 25)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartSheet(
    activity: ActivityEntity,
    initialTimerSeconds: Long,
    pomodoroDefaults: Pomodoro,
    onStart: (description: String?, timerSeconds: Long?, pomodoro: Pomodoro?) -> Unit,
    onManualEntry: (description: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(StartMode.STOPWATCH) }
    var description by remember { mutableStateOf("") }
    var timerMin by remember { mutableStateOf((initialTimerSeconds / 60).toString()) }
    var timerSec by remember { mutableStateOf((initialTimerSeconds % 60).toString()) }
    val timerSeconds = (timerMin.toLongOrNull() ?: 0) * 60 + (timerSec.toLongOrNull() ?: 0)
    var workMin by remember { mutableStateOf(pomodoroDefaults.workMin.toString()) }
    var breakMin by remember { mutableStateOf(pomodoroDefaults.breakMin.toString()) }
    val pomodoro = Pomodoro(workMin.toIntOrNull() ?: 0, breakMin.toIntOrNull() ?: 0)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(activity.color, size = 18)
                Spacer(Modifier.size(12.dp))
                Text(activity.name, style = MaterialTheme.typography.titleLarge)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StartMode.entries) { m ->
                    FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                }
            }
            if (mode == StartMode.POMODORO) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DigitsField("Work min", workMin, Modifier.weight(1f)) { workMin = it }
                    DigitsField("Break min", breakMin, Modifier.weight(1f)) { breakMin = it }
                }
                Text(
                    "Runs until you stop it. Every 4th break is a long break.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (mode == StartMode.TIMER) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TIMER_PRESETS_MIN) { min ->
                        FilterChip(
                            selected = timerSeconds == min * 60L,
                            onClick = { timerMin = min.toString(); timerSec = "0" },
                            label = { Text("$min min") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DigitsField("Min", timerMin, Modifier.weight(1f)) { timerMin = it }
                    DigitsField("Sec", timerSec, Modifier.weight(1f)) { timerSec = it }
                }
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = when (mode) {
                    StartMode.TIMER -> timerSeconds > 0
                    StartMode.POMODORO -> pomodoro.workMin > 0 && pomodoro.breakMin > 0
                    else -> true
                },
                onClick = {
                    val d = description.takeIf { it.isNotBlank() }
                    when (mode) {
                        StartMode.STOPWATCH -> onStart(d, null, null)
                        StartMode.TIMER -> onStart(d, timerSeconds, null)
                        StartMode.POMODORO -> onStart(d, null, pomodoro)
                        StartMode.MANUAL -> onManualEntry(d)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (mode == StartMode.MANUAL) "Continue" else "Start")
            }
        }
    }
}

@Composable
private fun DigitsField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 3 && v.all(Char::isDigit)) onChange(v) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ActivityRow(
    activity: ActivityEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).alpha(if (activity.archived) 0.6f else 1f),
        leadingContent = { ColorDot(activity.color) },
        headlineContent = { Text(activity.name) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options for ${activity.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(
                        text = { Text(if (activity.archived) "Unarchive" else "Archive") },
                        onClick = { menuOpen = false; onArchiveToggle() },
                    )
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        },
    )
}

private fun NameError.message(): String = when (this) {
    NameError.BLANK -> "Name cannot be empty"
    NameError.DUPLICATE -> "An activity with this name already exists"
}

@Composable
private fun ActivityEditorDialog(
    activity: ActivityEntity?,
    groups: List<ActivityGroupEntity>,
    defaultColor: Int,
    onSave: (name: String, groupId: Long?, color: Int, onError: (NameError) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(activity?.name ?: "") }
    var groupId by remember { mutableStateOf(activity?.groupId?.takeIf { id -> groups.any { it.id == id } }) }
    var color by remember { mutableStateOf(activity?.color ?: defaultColor) }
    var error by remember { mutableStateOf<NameError?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (activity == null) "New activity" else "Edit activity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it.message()) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (groups.isNotEmpty()) {
                    Text("Group", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = groupId == null, onClick = { groupId = null }, label = { Text("None") })
                        }
                        items(groups, key = { it.id }) { group ->
                            FilterChip(
                                selected = groupId == group.id,
                                onClick = { groupId = group.id },
                                label = { Text(group.name) },
                            )
                        }
                    }
                }
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityPalette.chunked(5).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { c ->
                                val selected = c == color
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .background(Color(c), CircleShape)
                                        .then(
                                            if (selected) {
                                                Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .clickable { color = c },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, groupId, color) { error = it } }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


@Composable
private fun GroupsDialog(
    groups: List<ActivityGroupEntity>,
    onCreate: (String, (NameError?) -> Unit) -> Unit,
    onRename: (ActivityGroupEntity, String, (NameError?) -> Unit) -> Unit,
    onDelete: (ActivityGroupEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ActivityGroupEntity?>(null) }
    var deleting by remember { mutableStateOf<ActivityGroupEntity?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Groups") },
        text = {
            Column {
                if (groups.isEmpty()) {
                    Text("No groups yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    groups.forEach { group ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(group.name, modifier = Modifier.weight(1f))
                            IconButton(onClick = { renaming = group }) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename ${group.name}")
                            }
                            IconButton(onClick = { deleting = group }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete ${group.name}")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New group") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = newName.isNotBlank(),
                        onClick = { onCreate(newName) { if (it == null) newName = "" } },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add group")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    renaming?.let { group ->
        var name by remember(group.id) { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename group") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { onRename(group, name) { if (it == null) renaming = null } },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { group ->
        ConfirmDialog(
            title = "Delete group \"${group.name}\"?",
            text = "Its activities are kept and become ungrouped.",
            confirmLabel = "Delete",
            onConfirm = { onDelete(group); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun NotificationsOffCard() {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Notifications are off, so timer alerts won't appear.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }) { Text("Turn on") }
        }
    }
}
