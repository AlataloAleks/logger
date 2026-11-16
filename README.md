# Logger

A minimal Android app for tracking time spent on anything: planche holds, reading, programming,
YouTube. Pick an activity, start, stop. The app keeps an accurate record and turns it into
GitHub-style heatmaps, streaks and trends.

Single user, fully offline, no account, no ads.

## Features

**Tracking**
- Four modes: **Stopwatch**, **Timer** (counts down), **Pomodoro** (work and break phases),
  and **Manual entry** for time tracked without the app.
- Records the actual time spent: stopping a 2:00 timer after 1:20 saves 1:20; a timer that
  runs out saves exactly 2:00.
- Optional description, editable before, during and after a session. Duration, start time,
  date and activity can be corrected afterwards.
- One session at a time. Starting another activity offers to switch.

**Works in the background**
- Time is always derived from saved timestamps, so closing the app, locking the phone or
  rebooting never loses time.
- Ongoing notification with a live clock and a Stop button.
- Timer and Pomodoro alerts on time even when the phone sleeps (alarm-clock alarms), with sound
  and vibration.
- Alarms and the notification are restored after a reboot or app update; a timer whose alarm
  was missed is finished at its planned end when the app opens.
- If a stopwatch was left running and its notification disappeared, the app asks what to do:
  keep running, end now, end at a chosen time, or discard.

**Statistics**
- 12-month contribution heatmap, this week vs last week, 12-week and 12-month trends,
  4-week average, current and best streak, active days, and all-time totals per activity.
- Filter by all activities, a group, or one activity.
- Configurable day start (default 04:00, so a late session counts on the previous day) and
  week start (default Monday). Pomodoro breaks can count as tracked time or not.

**Organising**
- Activities with colours, optional groups, archiving (hidden from the list, kept in
  statistics) and deletion.
- History grouped by day with daily totals.

**Widgets**
- **Running session**: live time and Stop while tracking; otherwise quick-start buttons for
  the most used activities, repeating how each was last tracked (for example a 2:00 timer).
- **Streak heatmap**: the last 12 weeks and current streak for all activities, a group, or
  one activity.

**Backup**
- Export everything to a JSON file and import it back (replaces all data after confirmation;
  invalid files are rejected without changing anything).

## Install

There is no store release. Build and install it yourself:

```bash
git clone https://github.com/AlataloAleks/logger.git
cd logger
./gradlew installRelease
```

Requirements: JDK 17 or newer, the Android SDK (platform 37, build tools 36), and an Android
8.0+ phone with USB debugging enabled. Create `local.properties` with
`sdk.dir=/path/to/Android/Sdk` if `ANDROID_HOME` is not set.

The release build is signed with the local debug key, which is fine for personal sideloading.

Two variants can be installed side by side:

| Variant | App name | Package | Use |
|---|---|---|---|
| `release` | Logger | `com.aleksalatalo.logger` | everyday use |
| `debug` | Logger dev | `com.aleksalatalo.logger.debug` | development; on-device tests uninstall it and wipe its data |

## Permissions

| Permission | Why |
|---|---|
| Notifications | Running-session notification and timer / Pomodoro alerts |
| Exact alarms (`USE_EXACT_ALARM`) | Ending timers and Pomodoro phases on time |
| Run at startup | Restoring alarms and the notification after a reboot |

No internet permission: data never leaves the phone unless you export it.

## Tech stack

- Kotlin, Jetpack Compose, Material 3 (dynamic colour, light and dark)
- Room (SQLite) with exported schemas, Navigation Compose
- AlarmManager, notifications and broadcast receivers; no background service
- App widgets with RemoteViews
- Charts drawn with Compose Canvas; no chart library
- AGP 9.5, Kotlin 2.4, Gradle 9.6, minSdk 26, targetSdk 36

## Architecture

```
Compose screens -> ViewModels -> SessionEngine / ActivityRepository -> Room
                                      |
                       SessionEffects (keeps notification + alarm in sync with the database)
                       Widgets        (re-render on every data change)
                       Receivers      (alarm, Stop action, boot, widget quick start)
```

- The database is the single source of truth. A running session is a row without an end
  time; elapsed time is computed from timestamps, never counted in memory.
- `SessionEngine` performs every session write inside a transaction.
- Statistics are computed on demand from session rows by pure functions in `domain/Stats.kt`.

```
app/src/main/java/com/aleksalatalo/logger/
  data/      Room entities and DAOs, activity repository, JSON backup
  domain/    session engine, Pomodoro rules, time and statistics calculations
  system/    notifications, alarms, receivers, widgets
  ui/        Compose screens: activities, running, history, editor, stats, settings
```

The original requirements and design decisions are in [docs/PLAN.md](docs/PLAN.md).

## Tests

```bash
./gradlew testDebugUnitTest            # time rules and statistics (JVM)
./gradlew connectedDebugAndroidTest    # session engine, repository, backup (needs a device)
```

## License

[MIT](LICENSE)
