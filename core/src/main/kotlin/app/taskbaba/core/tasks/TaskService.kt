package app.taskbaba.core.tasks

import app.taskbaba.core.data.SeriesRepository
import app.taskbaba.core.data.TaskRepository
import app.taskbaba.core.model.Category
import app.taskbaba.core.model.Due
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.RecurrenceRule
import app.taskbaba.core.model.RecurringSeries
import app.taskbaba.core.model.Task
import app.taskbaba.core.model.TaskDraft
import app.taskbaba.core.platform.ClockProvider
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ValidationException(val errors: List<String>) : IllegalArgumentException(errors.joinToString(" "))

/** Things that happened to tasks, consumed by the monk and statistics. */
sealed interface TaskEvent {
    val task: Task
    data class Created(override val task: Task) : TaskEvent
    data class Completed(override val task: Task) : TaskEvent
    data class Reopened(override val task: Task) : TaskEvent
    data class Postponed(override val task: Task) : TaskEvent
    data class Deleted(override val task: Task) : TaskEvent
    data class MitChosen(override val task: Task) : TaskEvent
}

enum class TaskView { TODAY, UPCOMING, OVERDUE, COMPLETED, ALL }

enum class SortMode(val label: String) { SMART("Smart rank"), DUE("Deadline"), PRIORITY("Priority"), CREATED("Newest"), MANUAL("My order") }

data class TaskFilter(
    val query: String = "",
    val categories: Set<Category> = emptySet(),
    val priorities: Set<Priority> = emptySet(),
)

class TaskService(
    private val repo: TaskRepository,
    private val series: SeriesRepository,
    private val clock: ClockProvider = ClockProvider.SYSTEM,
    private val onEvent: (TaskEvent) -> Unit = {},
) {
    val recurrence = RecurrenceEngine(repo, series)

    private fun now(): Instant = clock.clock().instant()
    private fun zone(): ZoneId = clock.clock().zone
    private fun today(): LocalDate = now().atZone(zone()).toLocalDate()

    fun observe(): Flow<List<Task>> = repo.observeAll()
    fun all(): List<Task> = repo.all()
    fun get(id: String): Task? = repo.byId(id)

    fun create(draft: TaskDraft): Result<Task> = runCatching {
        val errors = draft.validate()
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val now = now()
        val nextOrder = (repo.all().minOfOrNull { it.manualOrder } ?: 0) - 1
        val task = if (draft.recurrence != RecurrenceRule.NONE) {
            val s = RecurringSeries(
                id = UUID.randomUUID().toString(), title = draft.title.trim(), description = draft.description.trim(),
                category = draft.category, priority = draft.priority, importance = draft.importance,
                estimatedMinutes = draft.estimatedMinutes, rule = draft.recurrence, timeOfDay = draft.dueTime,
                startDate = draft.dueDate!!, createdAt = now,
            )
            // The first occurrence is created right away so it shows up in Today/Upcoming.
            val first = RecurrenceEngine.instanceFor(s, s.startDate, zone(), now).copy(notes = draft.notes.trim(), manualOrder = nextOrder)
            repo.transaction {
                series.upsert(s, lastGenerated = s.startDate)
                repo.insertOccurrence(first)
            }
            first
        } else {
            Task(
                id = UUID.randomUUID().toString(), title = draft.title.trim(), description = draft.description.trim(),
                notes = draft.notes.trim(), category = draft.category, priority = draft.priority, importance = draft.importance,
                createdAt = now, due = dueOf(draft), estimatedMinutes = draft.estimatedMinutes, manualOrder = nextOrder, updatedAt = now,
            ).also { repo.upsert(it) }
        }
        onEvent(TaskEvent.Created(task))
        task
    }

    /** Edits a task. Moving its deadline later counts as a postponement. */
    fun update(id: String, draft: TaskDraft): Result<Task> = runCatching {
        val errors = draft.validate().filterNot { draft.recurrence == RecurrenceRule.NONE && it.startsWith("Repeating") }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val old = repo.byId(id) ?: error("That task no longer exists.")
        val newDue = dueOf(draft)
        val postponed = isLater(old.deadline(zone()), newDue?.deadline(zone()))
        val updated = old.copy(
            title = draft.title.trim(), description = draft.description.trim(), notes = draft.notes.trim(),
            category = draft.category, priority = draft.priority, importance = draft.importance,
            due = newDue, estimatedMinutes = draft.estimatedMinutes,
            postponeCount = old.postponeCount + if (postponed) 1 else 0,
            updatedAt = now(),
        )
        repo.upsert(updated)
        if (postponed) onEvent(TaskEvent.Postponed(updated))
        updated
    }

    fun complete(id: String): Result<Task> = mutate(id) { t ->
        if (t.isCompleted) t else t.copy(completedAt = now(), snoozedUntil = null, updatedAt = now())
    }.onSuccess { onEvent(TaskEvent.Completed(it)) }

    fun reopen(id: String): Result<Task> = mutate(id) { it.copy(completedAt = null, updatedAt = now()) }
        .onSuccess { onEvent(TaskEvent.Reopened(it)) }

    fun delete(id: String): Result<Unit> = runCatching {
        val t = repo.byId(id) ?: return@runCatching
        repo.delete(id)
        onEvent(TaskEvent.Deleted(t))
    }

    /** Hides the task from the top of the list for a while and counts a postponement. */
    fun snooze(id: String, duration: Duration): Result<Task> = mutate(id) {
        it.copy(snoozedUntil = now().plus(duration), postponeCount = it.postponeCount + 1, updatedAt = now())
    }.onSuccess { onEvent(TaskEvent.Postponed(it)) }

    /** Moves the deadline to a new date (keeping the time of day for timed tasks). */
    fun reschedule(id: String, date: LocalDate): Result<Task> = runCatching {
        val old = repo.byId(id) ?: error("That task no longer exists.")
        val z = zone()
        val newDue: Due = when (val d = old.due) {
            is Due.At -> Due.At(date.atTime(d.instant.atZone(z).toLocalTime()).atZone(z).toInstant())
            else -> Due.OnDate(date)
        }
        val postponed = isLater(old.deadline(z), newDue.deadline(z))
        val updated = old.copy(due = newDue, snoozedUntil = null, postponeCount = old.postponeCount + if (postponed) 1 else 0, updatedAt = now())
        repo.upsert(updated)
        if (postponed) onEvent(TaskEvent.Postponed(updated))
        updated
    }

    /** Sets (or with [on] = false clears) today's single Most Important Task. */
    fun setMostImportant(id: String, on: Boolean = true): Result<Task> = runCatching {
        val today = today()
        var result: Task? = null
        repo.transaction {
            repo.clearMit(today)
            val t = repo.byId(id) ?: error("That task no longer exists.")
            result = t.copy(mitDate = if (on) today else null, updatedAt = now()).also { repo.upsert(it) }
        }
        result!!.also { if (on) onEvent(TaskEvent.MitChosen(it)) }
    }

    /** Swaps manual order with the neighbour in [ordered] (the list as currently shown). */
    fun move(id: String, ordered: List<Task>, delta: Int): Result<Unit> = runCatching {
        val index = ordered.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in ordered.indices) return@runCatching
        val reordered = ordered.toMutableList().apply { add(target, removeAt(index)) }
        repo.transaction {
            reordered.forEachIndexed { i, t -> if (t.manualOrder != i.toLong()) repo.upsert(t.copy(manualOrder = i.toLong())) }
        }
    }

    fun stopRepeating(task: Task): Result<Unit> = runCatching { task.seriesId?.let(series::deactivate) }

    fun generateRecurring(): List<Task> = recurrence.generate(today(), zone(), now())

    fun deleteAll() = repo.transaction { repo.deleteAll(); series.deleteAll() }

    private fun mutate(id: String, f: (Task) -> Task): Result<Task> = runCatching {
        val t = repo.byId(id) ?: error("That task no longer exists.")
        f(t).also { repo.upsert(it) }
    }

    private fun dueOf(d: TaskDraft): Due? {
        val date = d.dueDate ?: return null
        val time = d.dueTime ?: return Due.OnDate(date)
        return Due.At(date.atTime(time).atZone(zone()).toInstant())
    }

    private fun isLater(old: Instant?, new: Instant?) = old != null && new != null && new.isAfter(old)

    companion object {
        /** Pure view selection + filtering + sorting, shared by the UI and tests. */
        fun select(
            tasks: List<Task>, view: TaskView, filter: TaskFilter, sort: SortMode, now: Instant, zone: ZoneId,
        ): List<RankedTask> {
            val today = now.atZone(zone).toLocalDate()
            val inView = tasks.filter { t ->
                when (view) {
                    TaskView.TODAY -> {
                        val due = t.dueDate(zone)
                        if (t.isCompleted) t.completedAt!!.atZone(zone).toLocalDate() == today && (due == null || due <= today)
                        else (due != null && due <= today) || t.isMit(today)
                    }
                    TaskView.UPCOMING -> !t.isCompleted && t.dueDate(zone)?.isAfter(today) == true
                    TaskView.OVERDUE -> t.isOverdue(now, zone)
                    TaskView.COMPLETED -> t.isCompleted
                    TaskView.ALL -> true
                }
            }.filter { matches(it, filter) }

            val open = inView.filter { !it.isCompleted }
            val done = inView.filter { it.isCompleted }.sortedByDescending { it.completedAt }
                .map { RankedTask(it, 0, listOf("Completed")) }
            val rankedOpen = TaskRanker.rank(open, now, zone)
            val sortedOpen = when (sort) {
                SortMode.SMART -> rankedOpen
                SortMode.DUE -> rankedOpen.sortedWith(compareBy<RankedTask> { it.task.deadline(zone) ?: Instant.MAX }.thenByDescending { it.score })
                SortMode.PRIORITY -> rankedOpen.sortedWith(compareBy<RankedTask> { it.task.priority.ordinal }.thenByDescending { it.score })
                SortMode.CREATED -> rankedOpen.sortedByDescending { it.task.createdAt }
                SortMode.MANUAL -> rankedOpen.sortedWith(compareBy<RankedTask> { it.task.manualOrder }.thenBy { it.task.createdAt })
            }
            return if (view == TaskView.COMPLETED) done else sortedOpen + done
        }

        fun matches(t: Task, f: TaskFilter): Boolean {
            if (f.categories.isNotEmpty() && t.category !in f.categories) return false
            if (f.priorities.isNotEmpty() && t.priority !in f.priorities) return false
            val q = f.query.trim()
            if (q.isEmpty()) return true
            return listOf(t.title, t.description, t.notes).any { it.contains(q, ignoreCase = true) }
        }

        fun topPriorities(tasks: List<Task>, now: Instant, zone: ZoneId, n: Int = 3): List<RankedTask> {
            val today = now.atZone(zone).toLocalDate()
            val candidates = tasks.filter { t ->
                !t.isCompleted && !t.isSnoozed(now) && ((t.dueDate(zone)?.let { it <= today.plusDays(1) } ?: false) || t.isMit(today) || t.due == null)
            }
            return TaskRanker.rank(candidates, now, zone).take(n)
        }
    }
}
