package com.aleksalatalo.logger.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aleksalatalo.logger.MainActivity

/**
 * Schedules the alarm that ends a running timer. Uses an alarm-clock alarm, which the system
 * delivers on time even in Doze; falls back to an inexact alarm if exact alarms are not allowed.
 */
class SessionAlarms(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun scheduleTimerEnd(triggerAt: Long) {
        val operation = alarmIntent()
        if (canScheduleExact()) {
            val show = PendingIntent.getActivity(
                context,
                3,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_RUNNING, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    fun cancel() = alarmManager.cancel(alarmIntent())

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TimerAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
