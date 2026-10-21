package app.taskbaba.core.reminders

import app.taskbaba.core.model.ClockTime
import app.taskbaba.core.model.HealthReminderConfig
import app.taskbaba.core.model.HealthType
import app.taskbaba.core.model.TimeWindow
import app.taskbaba.core.model.Weekday
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure scheduling math for health reminders. Elapsed intervals are added on the instant timeline (so a
 * 60-minute water reminder is 60 real minutes even across a DST change); windows and fixed meal times
 * are resolved in the current local zone via ZonedDateTime, which shifts times that fall into a DST gap.
 */
object HealthSchedule {
    private const val LOOKAHEAD_DAYS = 8L

    /**
     * The next time [config] should fire strictly after [after]. For interval reminders [after] is the last
     * firing (or "now" when starting fresh). Returns null when disabled or nothing fits in the next week.
     */
    fun nextDue(config: HealthReminderConfig, after: Instant, zone: ZoneId, quiet: TimeWindow?): Instant? {
        if (!config.enabled || config.weekdays.isEmpty()) return null
        return if (config.type == HealthType.MEAL) nextMeal(config, after, zone, quiet)
        else nextInterval(config, after, zone, quiet)
    }

    private fun nextInterval(config: HealthReminderConfig, after: Instant, zone: ZoneId, quiet: TimeWindow?): Instant? {
        val candidate = after.plus(Duration.ofMinutes(config.intervalMinutes.toLong()))
        if (allowed(candidate, config, zone, quiet)) return candidate
        // Allowed regions only begin at an active-window start, at the end of quiet hours, or at midnight.
        val startDate = candidate.atZone(zone).toLocalDate()
        val boundaries = (0..LOOKAHEAD_DAYS).flatMap { d ->
            val date = startDate.plusDays(d)
            listOfNotNull(config.activeHours.start, quiet?.end, ClockTime(0)).map { at(date, it, zone) }
        }.filter { it.isAfter(candidate) }.sorted()
        return boundaries.firstOrNull { allowed(it, config, zone, quiet) }
    }

    private fun nextMeal(config: HealthReminderConfig, after: Instant, zone: ZoneId, quiet: TimeWindow?): Instant? {
        val startDate = after.atZone(zone).toLocalDate()
        return (0..LOOKAHEAD_DAYS).asSequence().flatMap { d ->
            val date = startDate.plusDays(d)
            if (Weekday.of(date.dayOfWeek) !in config.weekdays) emptySequence()
            else config.fixedTimes.sorted().asSequence().map { at(date, it, zone) }
        }.firstOrNull { it.isAfter(after) && !inQuiet(it, zone, quiet) }
    }

    fun allowed(t: Instant, config: HealthReminderConfig, zone: ZoneId, quiet: TimeWindow?): Boolean {
        val local = t.atZone(zone).toLocalDateTime()
        return inActiveWindow(local, config.activeHours, config.weekdays) && !inQuiet(t, zone, quiet)
    }

    fun inQuiet(t: Instant, zone: ZoneId, quiet: TimeWindow?): Boolean =
        quiet != null && quiet.contains(ClockTime.of(t.atZone(zone).toLocalTime()))

    /** For windows that wrap past midnight, the early-morning part belongs to the previous day's window. */
    fun inActiveWindow(local: LocalDateTime, window: TimeWindow, days: Set<Weekday>): Boolean {
        val time = ClockTime.of(local.toLocalTime())
        if (!window.contains(time)) return false
        val owningDate = if (window.start > window.end && time < window.end) local.toLocalDate().minusDays(1) else local.toLocalDate()
        return Weekday.of(owningDate.dayOfWeek) in days
    }

    private fun at(date: LocalDate, t: ClockTime, zone: ZoneId): Instant = date.atTime(t.toLocalTime()).atZone(zone).toInstant()
}
