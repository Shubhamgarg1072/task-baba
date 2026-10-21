package app.taskbaba.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.taskbaba.core.model.Category
import app.taskbaba.core.model.Due
import app.taskbaba.core.model.HealthEvent
import app.taskbaba.core.model.HealthOutcome
import app.taskbaba.core.model.HealthType
import app.taskbaba.core.model.Importance
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.RecurrenceRule
import app.taskbaba.core.model.RecurringSeries
import app.taskbaba.core.model.Task
import app.taskbaba.db.Day_record
import app.taskbaba.db.Health_event
import app.taskbaba.db.Recurring_series
import app.taskbaba.db.TaskBabaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import app.taskbaba.db.Task as TaskRow

private fun Long?.instant(): Instant? = this?.let(Instant::ofEpochMilli)
private fun String?.date(): LocalDate? = this?.let(LocalDate::parse)
private inline fun <reified E : Enum<E>> parseEnum(raw: String, fallback: E): E =
    runCatching { enumValueOf<E>(raw) }.getOrDefault(fallback)

class TaskRepository(private val db: TaskBabaDatabase) {
    private val q get() = db.taskQueries

    fun observeAll(): Flow<List<Task>> = q.selectAll().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toTask() } }
    fun all(): List<Task> = q.selectAll().executeAsList().map { it.toTask() }
    fun byId(id: String): Task? = q.selectById(id).executeAsOneOrNull()?.toTask()
    fun upsert(task: Task) = q.upsert(task.toRow())
    /** Inserts unless a task with the same id or the same (series, date) already exists. */
    fun insertOccurrence(task: Task): Boolean {
        val before = byId(task.id)
        if (before != null) return false
        q.insertOccurrence(task.toRow())
        return byId(task.id) != null
    }
    fun delete(id: String) = q.deleteById(id)
    fun clearMit(date: LocalDate) = q.clearMitForDate(date.toString())
    fun deleteAll() = q.deleteAll()
    fun transaction(block: () -> Unit) = db.transaction { block() }

    private fun TaskRow.toTask() = Task(
        id = id,
        title = title,
        description = description,
        notes = notes,
        category = parseEnum(category, Category.OTHER),
        priority = parseEnum(priority, Priority.MEDIUM),
        importance = parseEnum(importance, Importance.NORMAL),
        createdAt = Instant.ofEpochMilli(created_at),
        due = due_date.date()?.let { Due.OnDate(it) } ?: due_at.instant()?.let { Due.At(it) },
        estimatedMinutes = estimated_minutes?.toInt(),
        completedAt = completed_at.instant(),
        seriesId = series_id,
        occurrenceDate = occurrence_date.date(),
        postponeCount = postpone_count.toInt(),
        snoozedUntil = snoozed_until.instant(),
        mitDate = mit_date.date(),
        manualOrder = manual_order,
        updatedAt = Instant.ofEpochMilli(updated_at),
    )

    private fun Task.toRow() = TaskRow(
        id = id,
        title = title,
        description = description,
        notes = notes,
        category = category.name,
        priority = priority.name,
        importance = importance.name,
        created_at = createdAt.toEpochMilli(),
        due_at = (due as? Due.At)?.instant?.toEpochMilli(),
        due_date = (due as? Due.OnDate)?.date?.toString(),
        estimated_minutes = estimatedMinutes?.toLong(),
        completed_at = completedAt?.toEpochMilli(),
        series_id = seriesId,
        occurrence_date = occurrenceDate?.toString(),
        postpone_count = postponeCount.toLong(),
        snoozed_until = snoozedUntil?.toEpochMilli(),
        mit_date = mitDate?.toString(),
        manual_order = manualOrder,
        updated_at = updatedAt.toEpochMilli(),
    )
}

class SeriesRepository(private val db: TaskBabaDatabase) {
    private val q get() = db.recurringQueries

    data class Stored(val series: RecurringSeries, val lastGenerated: LocalDate?)

    fun all(): List<Stored> = q.selectAllSeries().executeAsList().map { it.toStored() }
    fun upsert(s: RecurringSeries, lastGenerated: LocalDate? = null) = q.upsertSeries(
        Recurring_series(
            id = s.id, title = s.title, description = s.description, category = s.category.name,
            priority = s.priority.name, importance = s.importance.name, estimated_minutes = s.estimatedMinutes?.toLong(),
            rule = s.rule.name, time_of_day_minutes = s.timeOfDay?.let { (it.hour * 60 + it.minute).toLong() },
            start_date = s.startDate.toString(), active = if (s.active) 1 else 0, created_at = s.createdAt.toEpochMilli(),
            last_generated_date = lastGenerated?.toString(),
        ),
    )
    fun markGenerated(id: String, date: LocalDate) = q.markGenerated(date.toString(), id)
    fun deactivate(id: String) = q.deactivateSeries(id)
    fun deleteAll() = q.deleteAllSeries()

    private fun Recurring_series.toStored() = Stored(
        RecurringSeries(
            id = id, title = title, description = description,
            category = parseEnum(category, Category.OTHER), priority = parseEnum(priority, Priority.MEDIUM),
            importance = parseEnum(importance, Importance.NORMAL), estimatedMinutes = estimated_minutes?.toInt(),
            rule = parseEnum(rule, RecurrenceRule.DAILY),
            timeOfDay = time_of_day_minutes?.let { LocalTime.of((it / 60).toInt(), (it % 60).toInt()) },
            startDate = LocalDate.parse(start_date), active = active == 1L, createdAt = Instant.ofEpochMilli(created_at),
        ),
        last_generated_date.date(),
    )
}

data class ReminderState(val type: HealthType, val nextDueAt: Instant?, val lastFiredAt: Instant?)

class HealthRepository(private val db: TaskBabaDatabase) {
    private val q get() = db.healthQueries

    fun observeForDate(date: LocalDate): Flow<List<HealthEvent>> =
        q.eventsForDate(date.toString()).asFlow().mapToList(Dispatchers.IO).map { it.map { r -> r.toEvent() } }
    fun forDate(date: LocalDate): List<HealthEvent> = q.eventsForDate(date.toString()).executeAsList().map { it.toEvent() }
    fun between(from: LocalDate, to: LocalDate): List<HealthEvent> =
        q.eventsBetweenDates(from.toString(), to.toString()).executeAsList().map { it.toEvent() }
    fun byId(id: String): HealthEvent? = q.eventById(id).executeAsOneOrNull()?.toEvent()
    fun insert(e: HealthEvent) = q.insertEvent(
        Health_event(e.id, e.type.name, e.scheduledAt.toEpochMilli(), e.deliveredAt?.toEpochMilli(), e.outcome.name, e.respondedAt?.toEpochMilli(), e.localDate.toString()),
    )
    fun respond(id: String, outcome: HealthOutcome, at: Instant) = q.updateOutcome(outcome.name, at.toEpochMilli(), id)
    fun deleteForDate(date: LocalDate) = q.deleteEventsForDate(date.toString())
    fun deleteAll() { q.deleteAllEvents(); q.deleteAllStates() }

    fun states(): Map<HealthType, ReminderState> = q.allStates().executeAsList().mapNotNull { r ->
        val t = runCatching { HealthType.valueOf(r.type) }.getOrNull() ?: return@mapNotNull null
        t to ReminderState(t, r.next_due_at.instant(), r.last_fired_at.instant())
    }.toMap()
    fun saveState(s: ReminderState) = q.upsertState(
        app.taskbaba.db.Reminder_state(s.type.name, s.nextDueAt?.toEpochMilli(), s.lastFiredAt?.toEpochMilli()),
    )

    private fun Health_event.toEvent() = HealthEvent(
        id = id, type = parseEnum(type, HealthType.REST), scheduledAt = Instant.ofEpochMilli(scheduled_at),
        deliveredAt = delivered_at.instant(), outcome = parseEnum(outcome, HealthOutcome.DELIVERED),
        respondedAt = responded_at.instant(), localDate = LocalDate.parse(local_date),
    )
}

data class DayRecord(
    val date: LocalDate,
    val planned: Int,
    val completed: Int,
    val priorityPlanned: Int,
    val priorityCompleted: Int,
    val overdue: Int,
    val mitCompleted: Boolean,
    val healthDelivered: Int,
    val healthDone: Int,
    val healthSkipped: Int,
    val goalMet: Boolean,
    val summary: String = "",
) {
    val completionPercent: Int get() = if (planned == 0) 0 else (completed * 100 / planned)
}

class DayRepository(private val db: TaskBabaDatabase) {
    private val q get() = db.dayQueries
    fun observeAll(): Flow<List<DayRecord>> = q.allDays().asFlow().mapToList(Dispatchers.IO).map { it.map { r -> r.toRecord() } }
    fun all(): List<DayRecord> = q.allDays().executeAsList().map { it.toRecord() }
    fun byDate(date: LocalDate): DayRecord? = q.dayByDate(date.toString()).executeAsOneOrNull()?.toRecord()
    fun upsert(r: DayRecord) = q.upsertDay(
        Day_record(
            r.date.toString(), r.planned.toLong(), r.completed.toLong(), r.priorityPlanned.toLong(), r.priorityCompleted.toLong(),
            r.overdue.toLong(), if (r.mitCompleted) 1 else 0, r.healthDelivered.toLong(), r.healthDone.toLong(),
            r.healthSkipped.toLong(), if (r.goalMet) 1 else 0, r.summary,
        ),
    )
    fun delete(date: LocalDate) = q.deleteDay(date.toString())
    fun deleteAll() = q.deleteAllDays()

    private fun Day_record.toRecord() = DayRecord(
        LocalDate.parse(date), planned.toInt(), completed.toInt(), priority_planned.toInt(), priority_completed.toInt(),
        overdue.toInt(), mit_completed == 1L, health_delivered.toInt(), health_done.toInt(), health_skipped.toInt(),
        goal_met == 1L, summary,
    )
}

class MonkHistoryRepository(private val db: TaskBabaDatabase) {
    fun recent(limit: Int): List<String> = db.kvQueries.recentMonk(limit.toLong()).executeAsList()
    fun record(messageId: String, event: String, at: Instant) {
        db.kvQueries.insertMonk(messageId, event, at.toEpochMilli())
        db.kvQueries.trimMonk()
    }
    fun deleteAll() = db.kvQueries.deleteAllMonk()
}
