package com.aleksalatalo.logger.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aleksalatalo.logger.MainActivity
import com.aleksalatalo.logger.R
import com.aleksalatalo.logger.data.db.SessionMode
import com.aleksalatalo.logger.data.db.PhaseType
import com.aleksalatalo.logger.domain.PhaseChange
import com.aleksalatalo.logger.domain.RunningSession
import com.aleksalatalo.logger.domain.phaseLabel
import com.aleksalatalo.logger.domain.formatClock

/** Posts and removes the running-session notification and the timer-finished alert. */
class SessionNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Running session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the session that is being tracked"
                setShowBadge(false)
            },
        )
        // A channel's sound cannot change after creation, so the first version is replaced.
        system.deleteNotificationChannel("alerts")
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Timer and Pomodoro alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when a timer or Pomodoro phase ends"
                // Play like an alarm (alarm volume) rather than a regular notification.
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            },
        )
    }

    fun canPost(): Boolean =
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) &&
            manager.areNotificationsEnabled()

    fun isRunningNotificationShowing(): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == ID_RUNNING }

    fun showRunning(running: RunningSession) {
        if (!canPost()) return
        val s = running.row.session
        val text = when (s.mode) {
            SessionMode.TIMER -> "Timer · ${formatClock(s.plannedSeconds ?: 0)}"
            SessionMode.POMODORO -> running.phase?.let { phaseLabel(it.phaseIndex) } ?: "Pomodoro"
            else -> "Stopwatch"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(running.row.activityName)
            .setContentText(s.description?.let { "$text · $it" } ?: text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            // Counts down to the timer or phase end; counts up from the start for a stopwatch.
            .setWhen(running.endsAt ?: s.startedAt)
            .setChronometerCountDown(running.endsAt != null)
            .setContentIntent(openAppIntent(openRunning = true))
            .addAction(0, "Stop", stopIntent())
        @Suppress("MissingPermission") // checked by canPost()
        manager.notify(ID_RUNNING, builder.build())
    }

    fun cancelRunning() = manager.cancel(ID_RUNNING)

    fun showTimerFinished(activityName: String, seconds: Long) =
        showAlert("Timer finished", "$activityName · ${formatClock(seconds)} recorded", openRunning = false)

    fun showPhaseChange(activityName: String, change: PhaseChange) = showAlert(
        title = if (change.type == PhaseType.WORK) "Back to work" else "Break time",
        text = "$activityName · ${formatClock(change.seconds)}",
        openRunning = true,
    )

    private fun showAlert(title: String, text: String, openRunning: Boolean) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(openRunning))
            .build()
        @Suppress("MissingPermission") // checked by canPost()
        manager.notify(ID_ALERT, notification)
    }

    private fun openAppIntent(openRunning: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_RUNNING, openRunning)
        return PendingIntent.getActivity(
            context,
            if (openRunning) 1 else 2,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, StopActionReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_RUNNING = "running"
        const val CHANNEL_ALERTS = "timer_done"
        const val ID_RUNNING = 1
        const val ID_ALERT = 2
    }
}
