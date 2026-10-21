package app.taskbaba.core

import app.taskbaba.core.model.ClockTime
import app.taskbaba.core.model.HealthReminderConfig
import app.taskbaba.core.model.HealthType
import app.taskbaba.core.model.TimeWindow
import app.taskbaba.core.model.Weekday
import app.taskbaba.core.reminders.HealthSchedule
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthScheduleTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(s: String, z: ZoneId = ist) = LocalDateTime.parse(s).atZone(z).toInstant()
    private fun local(i: java.time.Instant?, z: ZoneId = ist) = i?.atZone(z)?.toLocalDateTime()?.toString()

    private val water = HealthReminderConfig(HealthType.WATER, true, 60, activeHours = TimeWindow(ClockTime.of(9), ClockTime.of(19)), weekdays = Weekday.WORKDAYS)
    private val quiet = TimeWindow(ClockTime.of(22), ClockTime.of(7))

    @Test fun intervalInsideWindow() = assertEquals("2026-10-21T11:30", local(HealthSchedule.nextDue(water, at("2026-10-21T10:30:00"), ist, quiet)))

    @Test fun afterWindowMovesToNextWorkdayStart() {
        assertEquals("2026-10-22T09:00", local(HealthSchedule.nextDue(water, at("2026-10-21T18:30:00"), ist, quiet)))
        // Friday evening → Monday morning
        assertEquals("2026-10-26T09:00", local(HealthSchedule.nextDue(water, at("2026-10-23T18:30:00"), ist, quiet)))
    }

    @Test fun quietHoursPushToTheirEnd() {
        val nightOwl = water.copy(activeHours = TimeWindow(ClockTime.of(6), ClockTime.of(23, 59)), weekdays = Weekday.ALL)
        assertEquals("2026-10-22T07:00", local(HealthSchedule.nextDue(nightOwl, at("2026-10-21T21:30:00"), ist, quiet)))
    }

    @Test fun mealsUseFixedTimes() {
        val meal = HealthReminderConfig.defaults().first { it.type == HealthType.MEAL }
        assertEquals("2026-10-21T13:00", local(HealthSchedule.nextDue(meal, at("2026-10-21T10:00:00"), ist, quiet)))
        assertEquals("2026-10-21T20:00", local(HealthSchedule.nextDue(meal, at("2026-10-21T13:00:00"), ist, quiet)))
        assertEquals("2026-10-22T13:00", local(HealthSchedule.nextDue(meal, at("2026-10-21T20:00:00"), ist, quiet)))
    }

    @Test fun disabledOrNoDaysMeansNoReminder() {
        assertNull(HealthSchedule.nextDue(water.copy(enabled = false), at("2026-10-21T10:00:00"), ist, quiet))
        assertNull(HealthSchedule.nextDue(water.copy(weekdays = emptySet()), at("2026-10-21T10:00:00"), ist, quiet))
    }

    @Test fun wrappingActiveWindowBelongsToStartDay() {
        // Active 20:00–02:00 on Fridays only: Saturday 01:00 is still inside Friday's window.
        val late = water.copy(activeHours = TimeWindow(ClockTime.of(20), ClockTime.of(2)), weekdays = setOf(Weekday.FRI))
        assertEquals("2026-10-24T01:00", local(HealthSchedule.nextDue(late, at("2026-10-24T00:00:00"), ist, null)))
    }

    @Test fun intervalIsRealElapsedTimeAcrossDst() {
        val ny = ZoneId.of("America/New_York")
        val allDay = water.copy(activeHours = TimeWindow(ClockTime.of(0), ClockTime.of(23, 59)), weekdays = Weekday.ALL)
        // Fall back 2026-11-01 01:59 EDT → 01:00 EST: 60 real minutes after 01:30 EDT is 01:30 EST.
        val next = HealthSchedule.nextDue(allDay, at("2026-11-01T01:30:00", ny), ny, null)!!
        assertEquals(3600, next.epochSecond - at("2026-11-01T01:30:00", ny).epochSecond)
    }
}
