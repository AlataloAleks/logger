package com.aleksalatalo.logger.domain

import com.aleksalatalo.logger.data.db.PhaseType
import com.aleksalatalo.logger.data.db.SettingsEntity

/** Phases alternate work, break, work, break... starting with work at index 0. */
fun pomodoroPhaseType(index: Int): PhaseType = if (index % 2 == 0) PhaseType.WORK else PhaseType.BREAK

/** 1-based number of the work round a phase belongs to (a break belongs to the round before it). */
fun pomodoroRound(index: Int): Int = index / 2 + 1

/** Planned length of phase [index]. Every [SettingsEntity.pomoLongEvery]-th break is a long break. */
fun SettingsEntity.pomodoroPhaseSeconds(index: Int): Long = when {
    pomodoroPhaseType(index) == PhaseType.WORK -> pomoWorkMin * 60L
    pomoLongEvery > 0 && pomodoroRound(index) % pomoLongEvery == 0 -> pomoLongBreakMin * 60L
    else -> pomoBreakMin * 60L
}

/** "Work · round 2", "Break · round 2". */
fun phaseLabel(index: Int): String =
    "${if (pomodoroPhaseType(index) == PhaseType.WORK) "Work" else "Break"} · round ${pomodoroRound(index)}"
