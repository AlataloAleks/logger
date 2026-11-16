package com.aleksalatalo.logger.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aleksalatalo.logger.LoggerApp
import com.aleksalatalo.logger.system.HeatmapSource
import com.aleksalatalo.logger.ui.common.ColorDot
import com.aleksalatalo.logger.ui.common.SectionHeader
import com.aleksalatalo.logger.ui.theme.LoggerTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Chooses what a heatmap widget shows: all activities, one group, or one activity. Also opened to reconfigure an existing widget. */
class WidgetConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out without choosing must not leave an unconfigured widget behind.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val container = (application as LoggerApp).container
        val activities = container.activityRepository.activities.map { list -> list.filter { !it.archived } }
        val groups = container.activityRepository.groups

        enableEdgeToEdge()
        setContent {
            LoggerTheme {
                val list by activities.collectAsStateWithLifecycle(emptyList())
                val groupList by groups.collectAsStateWithLifecycle(emptyList())
                Scaffold(topBar = { TopAppBar(title = { Text("Show in widget") }) }) { padding ->
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding())) {
                        item {
                            ListItem(
                                modifier = Modifier.clickable { choose(widgetId, HeatmapSource.All) },
                                headlineContent = { Text("All activities") },
                            )
                        }
                        if (groupList.isNotEmpty()) {
                            item { SectionHeader("Groups") }
                            items(groupList, key = { "g${it.id}" }) { g ->
                                val members = list.filter { it.groupId == g.id }
                                ListItem(
                                    modifier = Modifier.clickable { choose(widgetId, HeatmapSource.Group(g.id)) },
                                    headlineContent = { Text(g.name) },
                                    supportingContent = {
                                        Text(members.joinToString { it.name }.ifEmpty { "No activities yet" })
                                    },
                                )
                            }
                            item { SectionHeader("Activities") }
                        }
                        items(list, key = { "a${it.id}" }) { a ->
                            ListItem(
                                modifier = Modifier.clickable { choose(widgetId, HeatmapSource.Activity(a.id)) },
                                leadingContent = { ColorDot(a.color) },
                                headlineContent = { Text(a.name) },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun choose(widgetId: Int, source: HeatmapSource) {
        val widgets = (application as LoggerApp).container.widgets
        widgets.setHeatmapSource(widgetId, source)
        lifecycleScope.launch {
            widgets.updateAll()
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}
