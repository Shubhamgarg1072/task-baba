package app.taskbaba.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class Priority(val label: String, val weight: Int) {
    CRITICAL("Critical", 100),
    HIGH("High", 70),
    MEDIUM("Medium", 40),
    LOW("Low", 15),
}

enum class Category(val label: String) {
    WORK("Work"),
    PERSONAL("Personal"),
    HEALTH("Health"),
    LEARNING("Learning"),
    OTHER("Other"),
}

enum class RecurrenceRule(val label: String) {
    NONE("Does not repeat"),
    DAILY("Every day"),
    WEEKDAYS("Every weekday"),
    WEEKLY("Every week"),
    ;

    fun occursOn(date: LocalDate, anchor: LocalDate): Boolean = when (this) {
        NONE -> date == anchor
        DAILY -> !date.isBefore(anchor)
        WEEKDAYS -> !date.isBefore(anchor) && date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        WEEKLY -> !date.isBefore(anchor) && date.dayOfWeek == anchor.dayOfWeek
    }
}

/** User-selected importance, independent of priority ("this matters to me"). */
enum class Importance(val label: String, val score: Int) {
    NORMAL("Normal", 0),
    IMPORTANT("Important", 12),
    VITAL("Vital", 25),
}

data class Task(
    val id: String,
    val title: String,
    val description: String = "",
    val notes: String = "",
    val category: Category = Category.WORK,
    val priority: Priority = Priority.MEDIUM,
    val importance: Importance = Importance.NORMAL,
    val createdAt: Instant,
    val dueAt: Instant? = null,
    /** False when the user picked only a date; the task is then due by the end of that day. */
    val dueHasTime: Boolean = false,
    val estimatedMinutes: Int? = null,
    val completedAt: Instant? = null,
    val seriesId: String? = null,
    val occurrenceDate: LocalDate? = null,
    val postponeCount: Int = 0,
    val snoozedUntil: Instant? = null,
    /** The local date for which this task is the Most Important Task, if any. */
    val mitDate: LocalDate? = null,
    val manualOrder: Long = 0,
    val updatedAt: Instant = createdAt,
) {
    val isCompleted: Boolean get() = completedAt != null
    fun isMit(today: LocalDate): Boolean = mitDate == today
    fun isSnoozed(now: Instant): Boolean = snoozedUntil?.isAfter(now) == true
}

/** Template for a repeating task. Instances are generated per local date with deterministic ids. */
data class RecurringSeries(
    val id: String,
    val title: String,
    val description: String = "",
    val category: Category = Category.WORK,
    val priority: Priority = Priority.MEDIUM,
    val importance: Importance = Importance.NORMAL,
    val estimatedMinutes: Int? = null,
    val rule: RecurrenceRule,
    /** Due time of day for each occurrence; null means "by end of day". */
    val timeOfDay: LocalTime? = null,
    val startDate: LocalDate,
    val active: Boolean = true,
    val createdAt: Instant,
)

/** Validated input from the task editor. */
data class TaskDraft(
    val title: String,
    val description: String = "",
    val notes: String = "",
    val category: Category = Category.WORK,
    val priority: Priority = Priority.MEDIUM,
    val importance: Importance = Importance.NORMAL,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val estimatedMinutes: Int? = null,
    val recurrence: RecurrenceRule = RecurrenceRule.NONE,
) {
    fun validate(): List<String> = buildList {
        val t = title.trim()
        if (t.isEmpty()) add("Give the task a title, my child. Even karma needs a name.")
        if (t.length > 200) add("Title is too long (max 200 characters).")
        if (description.length > 4000) add("Description is too long (max 4000 characters).")
        if (notes.length > 4000) add("Notes are too long (max 4000 characters).")
        if (estimatedMinutes != null && estimatedMinutes !in 1..(24 * 60)) add("Estimated duration must be between 1 minute and 24 hours.")
        if (dueTime != null && dueDate == null) add("Pick a due date to go with that time.")
        if (recurrence != RecurrenceRule.NONE && dueDate == null) add("Repeating tasks need a start date.")
    }
}
