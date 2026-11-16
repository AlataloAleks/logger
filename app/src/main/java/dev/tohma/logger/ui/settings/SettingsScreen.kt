package dev.tohma.logger.ui.settings

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tohma.logger.AppContainer
import dev.tohma.logger.LoggerApp
import dev.tohma.logger.data.InvalidBackupException
import dev.tohma.logger.data.db.SettingsEntity
import dev.tohma.logger.ui.common.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class SettingsViewModel(private val c: AppContainer, private val resolver: ContentResolver) : ViewModel() {
    val settings: StateFlow<SettingsEntity?> = c.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** One-off result messages for the snackbar. */
    val messages = MutableStateFlow<String?>(null)

    fun update(transform: (SettingsEntity) -> SettingsEntity) {
        viewModelScope.launch {
            val current = c.settings.first()
            val next = transform(current)
            if (next != current) c.database.settingsDao().upsert(next)
        }
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            messages.value = try {
                val json = c.backup.exportJson()
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                }
                val d = c.backup.read()
                "Exported ${d.activities.size} activities and ${d.sessions.size} sessions"
            } catch (e: Exception) {
                "Export failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            messages.value = try {
                val json = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                }
                val d = c.backup.importJson(json)
                c.sessionEffects.refresh()
                "Imported ${d.activities.size} activities and ${d.sessions.size} sessions"
            } catch (e: InvalidBackupException) {
                "Import failed: ${e.message}. Nothing was changed."
            } catch (e: Exception) {
                "Import failed: ${e.message ?: e.javaClass.simpleName}. Nothing was changed."
            }
        }
    }

    suspend fun counts(): Pair<Int, Int> = c.backup.read().let { it.activities.size to it.sessions.size }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LoggerApp
                SettingsViewModel(app.container, app.contentResolver)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val container = (context.applicationContext as LoggerApp).container
    var confirmImport by remember { mutableStateOf<Pair<Uri, Pair<Int, Int>>?>(null) }
    var notificationsOn by remember { mutableStateOf(true) }
    var exactAlarms by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsOn = container.notifications.canPost()
        exactAlarms = container.alarms.canScheduleExact()
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messages.value = null
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::export)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { confirmImport = it to viewModel.counts() } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Header("Days and weeks")
            ChoiceRow(
                title = "New day starts at",
                supporting = "Sessions before this hour count on the previous day",
                value = "%02d:00".format(s.dayStartHour),
                options = (0..23).map { "%02d:00".format(it) },
                onSelect = { i -> viewModel.update { it.copy(dayStartHour = i) } },
            )
            val days = DayOfWeek.entries
            ChoiceRow(
                title = "Week starts on",
                supporting = null,
                value = DayOfWeek.of(s.weekStart).getDisplayName(TextStyle.FULL, Locale.getDefault()),
                options = days.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) },
                onSelect = { i -> viewModel.update { it.copy(weekStart = days[i].value) } },
            )

            Header("Pomodoro")
            ListItem(
                modifier = Modifier.clickable { viewModel.update { it.copy(countBreaks = !it.countBreaks) } },
                headlineContent = { Text("Count breaks as tracked time") },
                supportingContent = { Text("Applies to all history and statistics") },
                trailingContent = {
                    Switch(checked = s.countBreaks, onCheckedChange = { v -> viewModel.update { it.copy(countBreaks = v) } })
                },
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MinutesField("Work", s.pomoWorkMin, Modifier.weight(1f)) { v -> viewModel.update { it.copy(pomoWorkMin = v) } }
                MinutesField("Break", s.pomoBreakMin, Modifier.weight(1f)) { v -> viewModel.update { it.copy(pomoBreakMin = v) } }
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MinutesField("Long break", s.pomoLongBreakMin, Modifier.weight(1f)) { v ->
                    viewModel.update { it.copy(pomoLongBreakMin = v) }
                }
                MinutesField("Long break every", s.pomoLongEvery, Modifier.weight(1f), suffix = "breaks") { v ->
                    viewModel.update { it.copy(pomoLongEvery = v) }
                }
            }

            Header("Alerts")
            ListItem(
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
                headlineContent = { Text("Notifications") },
                supportingContent = {
                    Text(
                        when {
                            !notificationsOn -> "Off: timer and Pomodoro alerts won't appear"
                            !exactAlarms -> "On, but exact alarms are not allowed: alerts may be late"
                            else -> "On"
                        },
                    )
                },
            )

            Header("Backup")
            ListItem(
                modifier = Modifier.clickable { exportLauncher.launch("logger-backup-${LocalDate.now()}.json") },
                headlineContent = { Text("Export data") },
                supportingContent = { Text("Save everything to a JSON file") },
            )
            ListItem(
                modifier = Modifier.clickable { importLauncher.launch(arrayOf("*/*")) },
                headlineContent = { Text("Import data") },
                // Any type: file managers report .json inconsistently; the content is validated instead.
                supportingContent = { Text("Replace all data with a backup file") },
            )
            HorizontalDivider()
        }
    }

    confirmImport?.let { (uri, counts) ->
        ConfirmDialog(
            title = "Replace all data?",
            text = "Your current ${counts.first} activities and ${counts.second} sessions will be replaced by the " +
                "backup. This cannot be undone. Export first if you want to keep them.",
            confirmLabel = "Replace",
            onConfirm = { confirmImport = null; viewModel.import(uri) },
            onDismiss = { confirmImport = null },
        )
    }
}

@Composable
private fun Header(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChoiceRow(title: String, supporting: String?, value: String, options: List<String>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            headlineContent = { Text(title) },
            supportingContent = supporting?.let { { Text(it) } },
            trailingContent = { Text(value, color = MaterialTheme.colorScheme.primary) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}

/** Saves only valid values (1-999); the field keeps whatever is typed meanwhile. */
@Composable
private fun MinutesField(label: String, value: Int, modifier: Modifier, suffix: String = "min", onValid: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            if (v.length <= 3 && v.all(Char::isDigit)) {
                text = v
                v.toIntOrNull()?.takeIf { it > 0 }?.let(onValid)
            }
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        isError = (text.toIntOrNull() ?: 0) <= 0,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
