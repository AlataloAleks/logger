package com.aleksalatalo.logger.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aleksalatalo.logger.AppContainer
import com.aleksalatalo.logger.LoggerApp
import kotlinx.coroutines.launch

/** Runs [block] on the app scope while keeping the receiver alive until it finishes. */
internal fun BroadcastReceiver.runAsync(context: Context, block: suspend AppContainer.() -> Unit) {
    val pending = goAsync()
    val container = (context.applicationContext as LoggerApp).container
    container.appScope.launch {
        try {
            container.block()
        } finally {
            pending.finish()
        }
    }
}

/** Fired by the alarm when a timer's time is up. */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context) { sessionEffects.expireDue() }
}

/** "Stop" button on the running-session notification. */
class StopActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runAsync(context) { sessionEngine.stop() }
}

/** Alarms and notifications are cleared on reboot and on app update; restore them from the database. */
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTORE_ACTIONS) return
        runAsync(context) { sessionEffects.refresh() }
    }

    private companion object {
        val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
