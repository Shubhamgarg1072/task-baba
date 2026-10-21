package app.taskbaba.core

import app.taskbaba.core.data.DatabaseFactory
import app.taskbaba.core.data.SeriesRepository
import app.taskbaba.core.data.TaskRepository
import app.taskbaba.core.model.Due
import app.taskbaba.core.model.RecurrenceRule
import app.taskbaba.core.model.TaskDraft
import app.taskbaba.core.tasks.TaskService
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecurrenceTest {
    private fun service(clock: MutableClock): Pair<TaskService, TaskRepository> {
        val db = DatabaseFactory.inMemory()
        val repo = TaskRepository(db)
        return TaskService(repo, SeriesRepository(db), clock) to repo
    }

    @Test fun dailySeriesGeneratesOncePerDayEvenWhenRunRepeatedly() {
        val clock = MutableClock.at("2026-10-21T08:00:00")
        val (svc, repo) = service(clock)
        svc.create(TaskDraft("Stand-up notes", dueDate = LocalDate.parse("2026-10-21"), dueTime = LocalTime.of(10, 0), recurrence = RecurrenceRule.DAILY)).getOrThrow()
        repeat(5) { svc.generateRecurring() }
        assertEquals(1, repo.all().size)

        clock.advance(Duration.ofDays(1))
        repeat(3) { svc.generateRecurring() }
        val all = repo.all().sortedBy { it.occurrenceDate }
        assertEquals(listOf(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-22")), all.map { it.occurrenceDate })
        assertEquals(1, all.map { it.seriesId }.distinct().size)
    }

    @Test fun deletedOccurrenceIsNotRecreatedSameDay() {
        val clock = MutableClock.at("2026-10-21T08:00:00")
        val (svc, repo) = service(clock)
        val first = svc.create(TaskDraft("Water plants", dueDate = LocalDate.parse("2026-10-21"), recurrence = RecurrenceRule.DAILY)).getOrThrow()
        svc.delete(first.id)
        svc.generateRecurring()
        assertTrue(repo.all().isEmpty())
    }

    @Test fun duplicateInsertForSameSeriesAndDateIsIgnored() {
        val clock = MutableClock.at("2026-10-21T08:00:00")
        val (svc, repo) = service(clock)
        val t = svc.create(TaskDraft("Journal", dueDate = LocalDate.parse("2026-10-21"), recurrence = RecurrenceRule.DAILY)).getOrThrow()
        assertTrue(!repo.insertOccurrence(t.copy(id = "different-id")))
        assertEquals(1, repo.all().size)
    }

    @Test fun weekdaySeriesSkipsWeekendsAndMissedDaysAreNotBackfilled() {
        val clock = MutableClock.at("2026-10-23T08:00:00") // Friday
        val (svc, repo) = service(clock)
        svc.create(TaskDraft("Timesheet", dueDate = LocalDate.parse("2026-10-23"), recurrence = RecurrenceRule.WEEKDAYS)).getOrThrow()
        clock.advance(Duration.ofDays(1)); svc.generateRecurring() // Saturday
        clock.advance(Duration.ofDays(1)); svc.generateRecurring() // Sunday
        assertEquals(1, repo.all().size)
        clock.advance(Duration.ofDays(3)); svc.generateRecurring() // Wednesday; Mon/Tue were missed
        assertEquals(listOf("2026-10-23", "2026-10-28"), repo.all().map { it.occurrenceDate.toString() }.sorted())
    }

    @Test fun timedOccurrenceSurvivesDstGap() {
        // US spring-forward 2027-03-14: 02:30 doesn't exist in New York and resolves to 03:30.
        val clock = MutableClock.at("2027-03-13T08:00:00", "America/New_York")
        val (svc, repo) = service(clock)
        svc.create(TaskDraft("Night job", dueDate = LocalDate.parse("2027-03-13"), dueTime = LocalTime.of(2, 30), recurrence = RecurrenceRule.DAILY)).getOrThrow()
        clock.advance(Duration.ofDays(1)); svc.generateRecurring()
        val dst = repo.all().first { it.occurrenceDate == LocalDate.parse("2027-03-14") }
        val local = (dst.due as Due.At).instant.atZone(ZoneId.of("America/New_York")).toLocalTime()
        assertEquals(LocalTime.of(3, 30), local)
    }

    @Test fun validationRejectsBadInput() {
        val (svc, _) = service(MutableClock.at("2026-10-21T08:00:00"))
        assertTrue(svc.create(TaskDraft("   ")).isFailure)
        assertTrue(svc.create(TaskDraft("x", estimatedMinutes = 0)).isFailure)
        assertTrue(svc.create(TaskDraft("x", recurrence = RecurrenceRule.DAILY)).isFailure)
        assertTrue(svc.create(TaskDraft("x", dueTime = LocalTime.NOON)).isFailure)
    }

    @Test fun reschedulingLaterCountsAsPostponement() {
        val (svc, _) = service(MutableClock.at("2026-10-21T08:00:00"))
        val t = svc.create(TaskDraft("Report", dueDate = LocalDate.parse("2026-10-21"))).getOrThrow()
        val moved = svc.reschedule(t.id, LocalDate.parse("2026-10-22")).getOrThrow()
        assertEquals(1, moved.postponeCount)
        assertEquals(1, svc.reschedule(t.id, LocalDate.parse("2026-10-21")).getOrThrow().postponeCount)
    }

    @Test fun onlyOneMostImportantTaskPerDay() {
        val (svc, repo) = service(MutableClock.at("2026-10-21T08:00:00"))
        val a = svc.create(TaskDraft("A")).getOrThrow()
        val b = svc.create(TaskDraft("B")).getOrThrow()
        svc.setMostImportant(a.id); svc.setMostImportant(b.id)
        assertEquals(listOf(b.id), repo.all().filter { it.mitDate != null }.map { it.id })
    }
}
