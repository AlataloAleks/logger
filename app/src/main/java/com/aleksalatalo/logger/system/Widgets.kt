package com.aleksalatalo.logger.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.aleksalatalo.logger.LoggerApp
import com.aleksalatalo.logger.MainActivity
import com.aleksalatalo.logger.R
import com.aleksalatalo.logger.data.ActivityRepository
import com.aleksalatalo.logger.data.db.ActivityEntity
import com.aleksalatalo.logger.data.db.ActivityGroupEntity
import com.aleksalatalo.logger.data.db.SessionEntity
import com.aleksalatalo.logger.data.db.SessionMode
import com.aleksalatalo.logger.data.db.SettingsEntity
import com.aleksalatalo.logger.domain.SessionEngine
import com.aleksalatalo.logger.domain.computeStats
import com.aleksalatalo.logger.domain.dayEntries
import com.aleksalatalo.logger.domain.formatClock
import com.aleksalatalo.logger.domain.heatLevels
import com.aleksalatalo.logger.domain.logicalDate
import com.aleksalatalo.logger.domain.phaseLabel
import com.aleksalatalo.logger.domain.weekStartOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.ZoneId

/** Default color of "All activities" (matches the app's fallback primary). */
private const val ALL_COLOR = 0xFF1E88E5.toInt()
private const val HEATMAP_WEEKS = 12

/** Builds and pushes the content of every home-screen widget from the database. */
class Widgets(
    private val context: Context,
    private val engine: SessionEngine,
    private val activities: ActivityRepository,
    private val settings: Flow<SettingsEntity>,
) {
    private val manager = AppWidgetManager.getInstance(context)
    private val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    private fun ids(provider: Class<*>): IntArray = manager.getAppWidgetIds(ComponentName(context, provider))

    fun hasAny(): Boolean = ids(RunningWidget::class.java).isNotEmpty() || ids(HeatmapWidget::class.java).isNotEmpty()

    /** What a heatmap widget shows. Stored per widget; no saved choice means all activities. */
    fun heatmapSource(widgetId: Int): HeatmapSource {
        prefs.getLong("heatmap_group_$widgetId", -1).takeIf { it > 0 }?.let { return HeatmapSource.Group(it) }
        prefs.getLong("heatmap_$widgetId", -1).takeIf { it > 0 }?.let { return HeatmapSource.Activity(it) }
        return HeatmapSource.All
    }

    fun setHeatmapSource(widgetId: Int, source: HeatmapSource) {
        prefs.edit()
            .putLong("heatmap_$widgetId", (source as? HeatmapSource.Activity)?.id ?: -1)
            .putLong("heatmap_group_$widgetId", (source as? HeatmapSource.Group)?.id ?: -1)
            .apply()
    }

    fun forget(widgetIds: IntArray) = prefs.edit().apply {
        widgetIds.forEach { remove("heatmap_$it"); remove("heatmap_group_$it") }
    }.apply()

    suspend fun updateAll() {
        val running = ids(RunningWidget::class.java)
        val heatmaps = ids(HeatmapWidget::class.java)
        if (running.isEmpty() && heatmaps.isEmpty()) return
        val all = activities.activities.first()
        val sessions = engine.history.first().map { it.session }
        if (running.isNotEmpty()) manager.updateAppWidget(running, runningViews(all, sessions))
        if (heatmaps.isNotEmpty()) {
            val s = settings.first()
            val groups = activities.groups.first()
            heatmaps.forEach { id -> manager.updateAppWidget(id, heatmapViews(id, all, groups, sessions, s)) }
        }
    }

    private suspend fun runningViews(all: List<ActivityEntity>, sessions: List<SessionEntity>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_running)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(running = true))
        val running = engine.runningSession.first()
        views.setViewVisibility(R.id.running, if (running != null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.idle, View.GONE)
        views.setViewVisibility(R.id.empty, View.GONE)

        if (running != null) {
            val s = running.row.session
            views.setInt(R.id.dot, "setColorFilter", running.row.activityColor)
            views.setTextViewText(R.id.activity_name, running.row.activityName)
            views.setTextViewText(
                R.id.mode_label,
                listOfNotNull(
                    running.phase?.let { phaseLabel(it.phaseIndex) }
                        ?: s.plannedSeconds?.let { "Timer · ${formatClock(it)}" },
                    s.description,
                ).joinToString(" · ").ifEmpty { "Stopwatch" },
            )
            // Chronometer bases use the elapsed-realtime clock; convert from wall-clock times.
            val offset = SystemClock.elapsedRealtime() - System.currentTimeMillis()
            val end = running.endsAt
            views.setChronometer(R.id.chronometer, (end ?: s.startedAt) + offset, null, true)
            views.setChronometerCountDown(R.id.chronometer, end != null)
            views.setOnClickPendingIntent(
                R.id.stop,
                PendingIntent.getBroadcast(
                    context, 0, Intent(context, StopActionReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            return views
        }

        val quick = quickStart(all, sessions)
        if (quick.isEmpty()) {
            views.setViewVisibility(R.id.empty, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.empty, openApp(running = false))
            return views
        }
        views.setViewVisibility(R.id.idle, View.VISIBLE)
        val buttons = listOf(R.id.start1, R.id.start2, R.id.start3, R.id.start4)
        buttons.forEachIndexed { i, button ->
            val item = quick.getOrNull(i)
            views.setViewVisibility(button, if (item != null) View.VISIBLE else View.GONE)
            if (item != null) {
                val (activity, last) = item
                views.setTextViewText(button, "${activity.name}\n${modeHint(last)}")
                views.setOnClickPendingIntent(button, startIntent(activity.id))
            }
        }
        return views
    }

    /**
     * Up to four activities, most used in the last 30 days first, each paired with its last
     * session so a tap repeats how it was last tracked (e.g. a 2:00 timer).
     */
    private fun quickStart(all: List<ActivityEntity>, sessions: List<SessionEntity>): List<Pair<ActivityEntity, SessionEntity?>> {
        val since = System.currentTimeMillis() - 30L * 86_400_000
        val recentCounts = sessions.filter { it.startedAt >= since }.groupingBy { it.activityId }.eachCount()
        val lastByActivity = sessions.groupBy { it.activityId }.mapValues { (_, list) -> list.maxBy { it.startedAt } }
        return all.filter { !it.archived }
            .sortedWith(compareByDescending<ActivityEntity> { recentCounts[it.id] ?: 0 }.thenBy { it.sortOrder })
            .take(4)
            .map { it to lastByActivity[it.id] }
    }

    private fun modeHint(last: SessionEntity?): String = when (last?.mode) {
        SessionMode.TIMER -> "Timer ${formatClock(last.plannedSeconds ?: 0)}"
        SessionMode.POMODORO -> "Pomodoro"
        else -> "Stopwatch"
    }

    private fun heatmapViews(
        widgetId: Int,
        all: List<ActivityEntity>,
        groups: List<ActivityGroupEntity>,
        sessions: List<SessionEntity>,
        s: SettingsEntity,
    ): RemoteViews {
        // A deleted activity or group falls back to all activities.
        val source = heatmapSource(widgetId)
        val activity = (source as? HeatmapSource.Activity)?.let { src -> all.firstOrNull { it.id == src.id } }
        val group = (source as? HeatmapSource.Group)?.let { src -> groups.firstOrNull { it.id == src.id } }
        val included: Set<Long>? = when {
            activity != null -> setOf(activity.id)
            group != null -> all.filter { it.groupId == group.id }.map { it.id }.toSet()
            else -> null
        }
        val entries = dayEntries(sessions, s).filter { included == null || it.activityId in included }
        val weekStart = DayOfWeek.of(s.weekStart)
        val today = logicalDate(System.currentTimeMillis(), ZoneId.systemDefault().id, s.dayStartHour)
        val stats = computeStats(entries, today, weekStart)

        val views = RemoteViews(context.packageName, R.layout.widget_heatmap)
        views.setTextViewText(R.id.title, activity?.name ?: group?.name ?: "All activities")
        views.setTextViewText(
            R.id.streak,
            "Streak ${stats.currentStreak} ${if (stats.currentStreak == 1) "day" else "days"}",
        )
        views.setImageViewBitmap(
            R.id.heatmap,
            renderHeatmap(stats.daily, today, weekStart, activity?.color ?: ALL_COLOR),
        )
        views.setOnClickPendingIntent(R.id.widget_root, openApp(running = false))
        return views
    }

    /** Last [HEATMAP_WEEKS] weeks as a grid: columns are weeks, rows are weekdays. */
    private fun renderHeatmap(
        daily: Map<java.time.LocalDate, Long>,
        today: java.time.LocalDate,
        weekStart: DayOfWeek,
        color: Int,
    ): Bitmap {
        val cell = 26f
        val gap = 6f
        val step = cell + gap
        val start = weekStartOf(today, weekStart).minusWeeks((HEATMAP_WEEKS - 1).toLong())
        val bitmap = Bitmap.createBitmap((HEATMAP_WEEKS * step - gap).toInt(), (7 * step - gap).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val window = (0 until HEATMAP_WEEKS * 7).map { start.plusDays(it.toLong()) }.filter { !it.isAfter(today) }
        val level = heatLevels(window.map { daily[it] ?: 0 })
        val alphas = listOf(0, 77, 128, 191, 255)
        window.forEachIndexed { i, date ->
            val l = level(daily[date] ?: 0)
            // Empty days: translucent gray that reads on both light and dark backgrounds.
            paint.color = if (l == 0) 0x33888888 else (color and 0x00FFFFFF) or (alphas[l] shl 24)
            val x = (i / 7) * step
            val y = (i % 7) * step
            canvas.drawRoundRect(RectF(x, y, x + cell, y + cell), 5f, 5f, paint)
        }
        return bitmap
    }

    private fun openApp(running: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        if (running) 10 else 11,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_RUNNING, running),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun startIntent(activityId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        // One distinct PendingIntent per activity.
        activityId.toInt(),
        Intent(context, WidgetStartReceiver::class.java).putExtra(WidgetStartReceiver.EXTRA_ACTIVITY_ID, activityId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

sealed interface HeatmapSource {
    data object All : HeatmapSource
    data class Activity(val id: Long) : HeatmapSource
    data class Group(val id: Long) : HeatmapSource
}

/** Running-session / quick-start widget. Content is pushed by [Widgets.updateAll]. */
class RunningWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        runAsync(context) { widgets.updateAll() }
}

/** Streak heatmap widget; each instance shows the activity, group or all, as chosen in its configuration. */
class HeatmapWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        runAsync(context) { widgets.updateAll() }

    override fun onDeleted(context: Context, ids: IntArray) {
        (context.applicationContext as LoggerApp).container.widgets.forget(ids)
    }
}

/**
 * Quick-start button: starts the activity the way it was last tracked (stopwatch, same timer
 * length, or Pomodoro). Does nothing if another session started in the meantime.
 */
class WidgetStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val activityId = intent.getLongExtra(EXTRA_ACTIVITY_ID, -1).takeIf { it > 0 } ?: return
        runAsync(context) {
            val last = sessionEngine.history.first().map { it.session }
                .filter { it.activityId == activityId }
                .maxByOrNull { it.startedAt }
            sessionEngine.start(
                activityId = activityId,
                description = null,
                timerSeconds = last?.plannedSeconds?.takeIf { last.mode == SessionMode.TIMER },
                pomodoro = last?.mode == SessionMode.POMODORO,
            )
        }
    }

    companion object {
        const val EXTRA_ACTIVITY_ID = "activity_id"
    }
}
