package app.taskbaba.core

import app.taskbaba.core.model.Due
import app.taskbaba.core.model.Importance
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.Task
import app.taskbaba.core.tasks.TaskRanker
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskRankerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-21T06:30:00Z") // 12:00 IST

    private fun task(id: String, p: Priority = Priority.MEDIUM, due: Due? = null, order: Long = 0, imp: Importance = Importance.NORMAL, postponed: Int = 0) =
        Task(id = id, title = id, priority = p, due = due, createdAt = now.minusSeconds(3600), manualOrder = order, importance = imp, postponeCount = postponed)

    @Test fun criticalBeatsLowWithSameDeadline() {
        val r = TaskRanker.rank(listOf(task("low", Priority.LOW), task("crit", Priority.CRITICAL)), now, zone)
        assertEquals(listOf("crit", "low"), r.map { it.task.id })
    }

    @Test fun overdueMediumOutranksUndatedHigh() {
        val overdue = task("late", Priority.MEDIUM, Due.At(now.minus(Duration.ofHours(3))))
        val r = TaskRanker.rank(listOf(task("high", Priority.HIGH), overdue), now, zone)
        assertEquals("late", r.first().task.id)
        assertTrue(r.first().reasons.any { it.startsWith("Overdue") })
    }

    @Test fun closerDeadlineWinsAtEqualPriority() {
        val soon = task("soon", due = Due.At(now.plus(Duration.ofHours(1))))
        val later = task("later", due = Due.At(now.plus(Duration.ofDays(2))))
        assertEquals(listOf("soon", "later"), TaskRanker.rank(listOf(later, soon), now, zone).map { it.task.id })
    }

    @Test fun mitAndImportanceAddExplainedPoints() {
        val mit = task("mit").copy(mitDate = now.atZone(zone).toLocalDate())
        val scored = TaskRanker.score(mit, now, zone)
        assertEquals(Priority.MEDIUM.weight + 50, scored.score)
        assertTrue("Most important task today" in scored.reasons)
        assertEquals(Priority.MEDIUM.weight + 25, TaskRanker.score(task("v", imp = Importance.VITAL), now, zone).score)
    }

    @Test fun postponementBonusIsCapped() {
        assertEquals(Priority.LOW.weight + 15, TaskRanker.score(task("p", Priority.LOW, postponed = 20), now, zone).score)
    }

    @Test fun snoozedTasksSink() {
        val snoozed = task("snoozed", Priority.CRITICAL).copy(snoozedUntil = now.plusSeconds(600))
        assertEquals("other", TaskRanker.rank(listOf(snoozed, task("other", Priority.LOW)), now, zone).first().task.id)
    }

    @Test fun tiesKeepManualOrderAndAreStable() {
        val tasks = (0 until 5).map { task("t$it", order = (4 - it).toLong()) }
        val first = TaskRanker.rank(tasks, now, zone).map { it.task.id }
        val second = TaskRanker.rank(tasks.shuffled(), now.plusSeconds(60), zone).map { it.task.id }
        assertEquals(listOf("t4", "t3", "t2", "t1", "t0"), first)
        assertEquals(first, second)
    }

    @Test fun completedTasksAreNotRanked() {
        assertTrue(TaskRanker.rank(listOf(task("done").copy(completedAt = now)), now, zone).isEmpty())
    }

    @Test fun dateOnlyDeadlineIsEndOfLocalDay() {
        val today = now.atZone(zone).toLocalDate()
        val t = task("d", due = Due.OnDate(today))
        assertTrue(!t.isOverdue(now, zone))
        assertTrue(TaskRanker.score(t, now, zone).reasons.contains("Due today"))
        assertTrue(t.isOverdue(today.plusDays(1).atStartOfDay(zone).toInstant().plusSeconds(1), zone))
    }
}
