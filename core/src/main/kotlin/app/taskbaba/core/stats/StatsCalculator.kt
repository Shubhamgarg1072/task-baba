package app.taskbaba.core.stats

import app.taskbaba.core.data.DayRecord
import app.taskbaba.core.model.HealthEvent
import app.taskbaba.core.model.HealthOutcome
import app.taskbaba.core.model.HealthType
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Streaks(val current: Int, val best: Int)

data class HealthSummary(
    /** Reminders that were shown. Showing a reminder proves nothing about drinking water. */
    val delivered: Int,
    /** Reminders the user explicitly confirmed with "Done". */
    val confirmed: Int,
    val skipped: Int,
    val snoozed: Int,
    val confirmedByType: Map<HealthType, Int>,
)

data class PeriodSummary(val days: Int, val planned: Int, val completed: Int, val goalDays: Int, val healthConfirmed: Int, val healthDelivered: Int) {
    val completionPercent: Int get() = if (planned == 0) 0 else completed * 100 / planned
}

/**
 * All statistics are computed from stored tasks and health events. A day is "planned" with every task
 * due that day, every task completed that day, and the day's Most Important Task.
 */
object StatsCalculator {
    val PRIORITY_LEVELS = setOf(Priority.CRITICAL, Priority.HIGH)

    fun plannedFor(tasks: List<Task>, date: LocalDate, zone: ZoneId): List<Task> = tasks.filter { t ->
        t.dueDate(zone) == date || t.completedAt?.atZone(zone)?.toLocalDate() == date || t.mitDate == date
    }

    private fun completedBy(t: Task, date: LocalDate, zone: ZoneId) =
        t.completedAt?.atZone(zone)?.toLocalDate()?.let { it <= date } == true

    fun health(events: List<HealthEvent>): HealthSummary = HealthSummary(
        delivered = events.count { it.deliveredAt != null },
        confirmed = events.count { it.outcome == HealthOutcome.DONE },
        skipped = events.count { it.outcome == HealthOutcome.SKIPPED },
        snoozed = events.count { it.outcome == HealthOutcome.SNOOZED },
        confirmedByType = events.filter { it.outcome == HealthOutcome.DONE }.groupingBy { it.type }.eachCount(),
    )

    fun day(
        tasks: List<Task>, health: List<HealthEvent>, date: LocalDate, zone: ZoneId, now: Instant, goalPercent: Int, summary: String = "",
    ): DayRecord {
        val planned = plannedFor(tasks, date, zone)
        val completed = planned.filter { completedBy(it, date, zone) }
        val endOfDay = date.plusDays(1).atStartOfDay(zone).toInstant()
        val cutoff = if (now.isBefore(endOfDay)) now else endOfDay
        val overdue = tasks.count { t ->
            val d = t.deadline(zone) ?: return@count false
            d.isBefore(cutoff) && (t.completedAt == null || t.completedAt.isAfter(cutoff))
        }
        val h = health(health.filter { it.localDate == date })
        val pct = if (planned.isEmpty()) 0 else completed.size * 100 / planned.size
        return DayRecord(
            date = date,
            planned = planned.size,
            completed = completed.size,
            priorityPlanned = planned.count { it.priority in PRIORITY_LEVELS },
            priorityCompleted = completed.count { it.priority in PRIORITY_LEVELS },
            overdue = overdue,
            mitCompleted = planned.any { it.mitDate == date && completedBy(it, date, zone) },
            healthDelivered = h.delivered,
            healthDone = h.confirmed,
            healthSkipped = h.skipped,
            goalMet = planned.isNotEmpty() && completed.isNotEmpty() && pct >= goalPercent,
            summary = summary,
        )
    }

    /**
     * Streak of days meeting the goal. Days with nothing planned (weekends, holidays) neither count nor
     * break the streak. Today extends the streak once met but never breaks it while still in progress.
     */
    fun streaks(history: List<DayRecord>, today: DayRecord?): Streaks {
        val days = (history.filter { today == null || it.date != today.date } + listOfNotNull(today))
            .filter { it.planned > 0 }
            .sortedBy { it.date }
        var best = 0
        var run = 0
        for (d in days) {
            if (d.goalMet) { run++; best = maxOf(best, run) }
            else if (today == null || d.date != today.date) run = 0
        }
        // Walk back from the most recent counted day to get the current streak.
        var current = 0
        for (d in days.asReversed()) {
            if (d.goalMet) current++
            else if (today != null && d.date == today.date) continue
            else break
        }
        return Streaks(current, maxOf(best, current))
    }

    fun period(records: List<DayRecord>): PeriodSummary = PeriodSummary(
        days = records.size,
        planned = records.sumOf { it.planned },
        completed = records.sumOf { it.completed },
        goalDays = records.count { it.goalMet },
        healthConfirmed = records.sumOf { it.healthDone },
        healthDelivered = records.sumOf { it.healthDelivered },
    )
}
