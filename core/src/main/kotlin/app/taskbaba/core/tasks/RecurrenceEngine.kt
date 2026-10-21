package app.taskbaba.core.tasks

import app.taskbaba.core.data.SeriesRepository
import app.taskbaba.core.data.TaskRepository
import app.taskbaba.core.model.Due
import app.taskbaba.core.model.RecurringSeries
import app.taskbaba.core.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Creates today's instance of each active recurring series. Safe to run any number of times:
 *  - instance ids are deterministic ("<series>@<date>") and (series_id, occurrence_date) is unique;
 *  - a series remembers the last date it generated, so an instance the user deleted is not recreated.
 * Past missed days are not back-filled (no surprise pile of stale chores after a holiday).
 */
class RecurrenceEngine(private val tasks: TaskRepository, private val series: SeriesRepository) {

    fun generate(today: LocalDate, zone: ZoneId, now: Instant): List<Task> {
        val created = mutableListOf<Task>()
        tasks.transaction {
            for (stored in series.all()) {
                val s = stored.series
                if (!s.active) continue
                if (stored.lastGenerated != null && !stored.lastGenerated.isBefore(today)) continue
                if (!s.rule.occursOn(today, s.startDate)) continue
                val task = instanceFor(s, today, zone, now)
                if (tasks.insertOccurrence(task)) created += task
                series.markGenerated(s.id, today)
            }
        }
        return created
    }

    companion object {
        fun instanceId(seriesId: String, date: LocalDate) = "$seriesId@$date"

        fun instanceFor(s: RecurringSeries, date: LocalDate, zone: ZoneId, now: Instant) = Task(
            id = instanceId(s.id, date),
            title = s.title,
            description = s.description,
            category = s.category,
            priority = s.priority,
            importance = s.importance,
            createdAt = now,
            // ZonedDateTime resolves DST gaps/overlaps (e.g. 02:30 on spring-forward day becomes 03:30).
            due = s.timeOfDay?.let { Due.At(date.atTime(it).atZone(zone).toInstant()) } ?: Due.OnDate(date),
            estimatedMinutes = s.estimatedMinutes,
            seriesId = s.id,
            occurrenceDate = date,
            updatedAt = now,
        )
    }
}
