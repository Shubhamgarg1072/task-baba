package app.taskbaba.core.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

enum class HealthType(val label: String, val emoji: String, val defaultIntervalMinutes: Int) {
    WATER("Drink water", "💧", 60),
    EYES("Eye break (20-20-20)", "👀", 20),
    STRETCH("Stand & stretch", "🧘", 50),
    WALK("Walk around", "🚶", 90),
    MEAL("Meal time", "🍛", 0),
    REST("Rest break", "☕", 120),
}

enum class HealthOutcome { DELIVERED, DONE, SNOOZED, SKIPPED }

/**
 * A single reminder delivery and the user's response. DELIVERED means the reminder was shown and
 * nothing was confirmed; only DONE counts as a user-confirmed health action.
 */
data class HealthEvent(
    val id: String,
    val type: HealthType,
    val scheduledAt: Instant,
    val deliveredAt: Instant?,
    val outcome: HealthOutcome,
    val respondedAt: Instant?,
    val localDate: LocalDate,
)

@Serializable
enum class Weekday { MON, TUE, WED, THU, FRI, SAT, SUN;
    fun toDayOfWeek(): DayOfWeek = DayOfWeek.of(ordinal + 1)
    companion object {
        fun of(d: DayOfWeek): Weekday = entries[d.value - 1]
        val ALL = entries.toSet()
        val WORKDAYS = setOf(MON, TUE, WED, THU, FRI)
    }
}

/** Minutes since local midnight, serializable and DST-agnostic. */
@Serializable
data class ClockTime(val minutes: Int) : Comparable<ClockTime> {
    init { require(minutes in 0 until 24 * 60) { "minutes out of range: $minutes" } }
    val hour get() = minutes / 60
    val minute get() = minutes % 60
    fun toLocalTime(): java.time.LocalTime = java.time.LocalTime.of(hour, minute)
    override fun compareTo(other: ClockTime) = minutes.compareTo(other.minutes)
    override fun toString() = "%02d:%02d".format(hour, minute)
    companion object {
        fun of(h: Int, m: Int = 0) = ClockTime(h * 60 + m)
        fun of(t: java.time.LocalTime) = ClockTime(t.hour * 60 + t.minute)
    }
}

/** A daily window; if end < start the window wraps past midnight (e.g. quiet hours 22:00–07:00). */
@Serializable
data class TimeWindow(val start: ClockTime, val end: ClockTime) {
    fun contains(t: ClockTime): Boolean =
        if (start <= end) t >= start && t < end else t >= start || t < end
}

@Serializable
data class HealthReminderConfig(
    val type: HealthType,
    val enabled: Boolean,
    val intervalMinutes: Int,
    /** Used by MEAL instead of an interval. */
    val fixedTimes: List<ClockTime> = emptyList(),
    val activeHours: TimeWindow = TimeWindow(ClockTime.of(9), ClockTime.of(19)),
    val weekdays: Set<Weekday> = Weekday.WORKDAYS,
    val snoozeMinutes: Int = 10,
    val sound: Boolean = true,
) {
    fun validate(): List<String> = buildList {
        if (type != HealthType.MEAL && intervalMinutes !in 10..480) add("${type.label}: interval must be 10–480 minutes.")
        if (type == HealthType.MEAL && fixedTimes.isEmpty() && enabled) add("Meal reminders need at least one time.")
        if (snoozeMinutes !in 1..120) add("${type.label}: snooze must be 1–120 minutes.")
        if (weekdays.isEmpty() && enabled) add("${type.label}: pick at least one day.")
    }

    companion object {
        fun defaults(): List<HealthReminderConfig> = HealthType.entries.map { t ->
            when (t) {
                HealthType.MEAL -> HealthReminderConfig(
                    t, enabled = true, intervalMinutes = 0,
                    fixedTimes = listOf(ClockTime.of(13), ClockTime.of(20)),
                    activeHours = TimeWindow(ClockTime.of(7), ClockTime.of(23)),
                    weekdays = Weekday.ALL,
                )
                HealthType.EYES -> HealthReminderConfig(t, enabled = false, intervalMinutes = t.defaultIntervalMinutes, sound = false)
                HealthType.WALK -> HealthReminderConfig(t, enabled = false, intervalMinutes = t.defaultIntervalMinutes)
                else -> HealthReminderConfig(t, enabled = true, intervalMinutes = t.defaultIntervalMinutes)
            }
        }
    }
}
