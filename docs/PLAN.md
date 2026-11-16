# Logger: development plan

Minimal Android app for tracking time spent on activities. Agreed during planning (2026-11-15).
`(default)` marks proposed values that can change without affecting the design.

## Status (2026-11-16)

MVP steps 1-6 are implemented and verified on a device. Added after the plan:
home-screen widgets (running session / quick start, streak heatmap), group filters for
statistics and the heatmap widget, and seconds shown in durations under an hour.
Remaining from step 7: force-stop, day-boundary, notifications-off and archived-activity checks.

Deviations from the plan: compileSdk 37 (required by Compose 1.12); JSON backup uses Android's
built-in `org.json` instead of kotlinx.serialization; widgets use RemoteViews (needed for a live
chronometer) instead of Glance.

## Requirements

- Native Android, single user, offline, no sync. Sideloaded APK for now.
- Activities: create, rename, archive, delete, optional one-level groups. Archived activities are
  hidden from the picker but kept in stats. Deleting an activity deletes its sessions after confirmation.
- One running session at a time. Modes: Stopwatch, Timer, Pomodoro, Manual entry.
- Any mode can be stopped at any time. Actual elapsed time is recorded, never the planned time.
  Timers finish automatically when they expire.
- Description is optional and editable before, during and after a session. Duration editable afterwards.
- Pomodoro: phases advance automatically; 25/5 min, 15 min long break every 4 work phases (default).
  Setting: whether breaks count as tracked time.
- Background: alert when a timer or Pomodoro phase ends; ongoing notification with live time and Stop;
  alarms rescheduled after reboot.
- Recovery after the app was killed: expired Timer/Pomodoro finishes at its planned end; a running
  Stopwatch asks Continue / End now / End at... / Discard.
- Day starts at a configurable hour (04:00 default). A session counts on the day it started.
  Week start configurable (Monday default).
- Stats: heatmaps (per activity and combined), totals, daily/weekly/monthly, this week vs last week,
  trends, distribution, consistency.
- Manual JSON export and import (import replaces all data).

## Stack

Kotlin, Jetpack Compose + Material 3, Room 2.8 (KSP), Navigation Compose, kotlinx.serialization,
custom Canvas charts, AlarmManager `setAlarmClock`, NotificationCompat chronometer. No DI framework,
no foreground service. AGP 9.5 (built-in Kotlin), Gradle 9.6, minSdk 26, targetSdk 36, compileSdk 37
(required by Compose 1.12).

## Architecture

```
Compose screens -> ViewModels -> Repository -> Room
                                   ^
                     SessionEngine (single source of session logic)
                     <- AlarmReceiver, NotificationActionReceiver, BootReceiver
```

- Elapsed time is always derived from persisted timestamps, never counted in memory.
- On app start, reconcile DB with alarms and notification (repost, reschedule, finish expired, prompt).
- Stats are computed on demand from session rows.

## Database

```
activity_group  id, name, sortOrder
activity        id, name (unique, case-insensitive), groupId? (SET NULL), color, archived, sortOrder, createdAt
session         id, activityId (CASCADE), mode, startedAt (UTC ms), endedAt? (null = running), zoneId,
                plannedSeconds?, workSeconds, breakSeconds, description?, createdAt, updatedAt
pomodoro_phase  id, sessionId (CASCADE), type (WORK|BREAK), phaseIndex, startedAt, endedAt?
settings        single row: dayStartHour, weekStart, countBreaks, pomodoro defaults
```

- Tracked time = workSeconds + (countBreaks ? breakSeconds : 0).
- Logical day derived at query time from startedAt, zoneId and dayStartHour.
- Editing duration changes workSeconds and keeps startedAt.
- Schema JSON exported to `app/schemas/` from v1; every change needs a migration and test.

## MVP build order

1. Foundation: project, database, activities and groups screens.
2. Stopwatch and Manual entry: SessionEngine, running screen, history and editing.
3. Timer and background: alarms, notification, permissions, boot, recovery prompt.
4. Pomodoro.
5. Statistics.
6. Settings, export and import.
7. Device testing: sleep, kill, force-stop, reboot, denied permissions, day boundaries.

## Future

Midnight split setting, Android 16 Live Updates, widget / quick-settings tile, group stats and goals,
merge import / CSV / auto backup, sync and iOS (new architecture), Play publishing.

## Risks

- OEM battery savers or force-stop cancel alarms: only the alert is lost; repaired on app start.
- Notification permission denied: in-app warning.
- Ongoing notification swiped away (Android 14+): session continues; reposted on app open.
- USE_EXACT_ALARM rejected on Play: fall back to SCHEDULE_EXACT_ALARM.
- Clock changed during a session: accepted; duration editable.
