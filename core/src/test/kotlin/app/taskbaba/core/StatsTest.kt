package app.taskbaba.core

import app.taskbaba.core.data.DayRecord
import app.taskbaba.core.model.Due
import app.taskbaba.core.model.HealthEvent
import app.taskbaba.core.model.HealthOutcome
import app.taskbaba.core.model.HealthType
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.Task
import app.taskbaba.core.stats.StatsCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val date = LocalDate.parse("2026-10-21")
    private val now = date.atTime(18, 0).atZone(zone).toInstant()

    private fun t(id: String, due: LocalDate?, done: Instant? = null, p: Priority = Priority.MEDIUM) =
        Task(id, id, priority = p, createdAt = now.minusSeconds(86400 * 3), due = due?.let { Due.OnDate(it) }, completedAt = done)

    @Test fun plannedVersusCompletedAndPriorityCounts() {
        val tasks = listOf(
            t("a", date, now.minusSeconds(60), Priority.CRITICAL),
            t("b", date, null, Priority.HIGH),
            t("c", date, now.minusSeconds(30)),
            t("undated-done-today", null, now.minusSeconds(10)),
            t("tomorrow", date.plusDays(1)),
            t("yesterday-done", date.minusDays(1), now.minusSeconds(86400)),
        )
        val r = StatsCalculator.day(tasks, emptyList(), date, zone, now, goalPercent = 60)
        assertEquals(4, r.planned)
        assertEquals(3, r.completed)
        assertEquals(2, r.priorityPlanned)
        assertEquals(1, r.priorityCompleted)
        assertEquals(75, r.completionPercent)
        assertTrue(r.goalMet)
    }

    @Test fun healthDistinguishesDeliveredFromConfirmed() {
        val ev = listOf(
            HealthEvent("1", HealthType.WATER, now, now, HealthOutcome.DONE, now, date),
            HealthEvent("2", HealthType.WATER, now, now, HealthOutcome.DELIVERED, null, date),
            HealthEvent("3", HealthType.EYES, now, now, HealthOutcome.SKIPPED, now, date),
            HealthEvent("4", HealthType.WATER, now, null, HealthOutcome.DONE, now, date), // user-recorded, no reminder
        )
        val h = StatsCalculator.health(ev)
        assertEquals(3, h.delivered)
        assertEquals(2, h.confirmed)
        assertEquals(1, h.skipped)
        assertEquals(2, h.confirmedByType[HealthType.WATER])
    }

    private fun rec(d: String, planned: Int, met: Boolean) =
        DayRecord(LocalDate.parse(d), planned, if (met) planned else 0, 0, 0, 0, false, 0, 0, 0, met)

    @Test fun streaksIgnoreEmptyDaysAndTodayInProgress() {
        val history = listOf(
            rec("2026-10-14", 3, true), rec("2026-10-15", 3, true), rec("2026-10-16", 3, true),
            rec("2026-10-17", 2, false),
            rec("2026-10-18", 0, false), // weekend, nothing planned: neutral
            rec("2026-10-19", 4, true), rec("2026-10-20", 2, true),
        )
        val todayInProgress = rec("2026-10-21", 3, false)
        assertEquals(2, StatsCalculator.streaks(history, todayInProgress).current)
        assertEquals(3, StatsCalculator.streaks(history, todayInProgress).best)
        assertEquals(3, StatsCalculator.streaks(history, rec("2026-10-21", 3, true)).current)
    }

    @Test fun missedDayBreaksStreak() {
        val s = StatsCalculator.streaks(listOf(rec("2026-10-19", 2, true), rec("2026-10-20", 2, false)), null)
        assertEquals(0, s.current)
        assertEquals(1, s.best)
    }

    @Test fun emptyDayDoesNotMeetGoal() {
        assertFalse(StatsCalculator.day(emptyList(), emptyList(), date, zone, now, 60).goalMet)
    }
}
