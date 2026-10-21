package app.taskbaba.core.tasks

import app.taskbaba.core.model.Task
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class RankedTask(val task: Task, val score: Int, val reasons: List<String>)

/**
 * Transparent, deterministic ranking. Higher score = do it sooner. Rules (also shown in the app):
 *
 *  • Priority: Critical +100, High +70, Medium +40, Low +15
 *  • Your importance flag: Important +12, Vital +25
 *  • Most Important Task of the day: +50
 *  • Deadline proximity: overdue +60 (+2 per hour late, max +40 extra); due within 2 h +45;
 *    within 6 h +35; later today +25; within 3 days +12; within a week +5
 *  • Postponed before: +3 per postponement (max +15) — avoided tasks float up
 *  • Quick win: estimated ≤ 15 min and due within 24 h +5
 *  • Snoozed right now: listed after every active task until the snooze ends
 *
 * Urgency uses coarse buckets so tasks don't reshuffle minute by minute. Ties keep your manual order,
 * then earliest deadline, then oldest first. Completed tasks are never ranked. This is not AI.
 */
object TaskRanker {
    fun rank(tasks: List<Task>, now: Instant, zone: ZoneId): List<RankedTask> {
        val today = now.atZone(zone).toLocalDate()
        return tasks.asSequence()
            .filter { !it.isCompleted }
            .map { score(it, now, zone, today) }
            .sortedWith(
                compareBy<RankedTask> { it.task.isSnoozed(now) }
                    .thenByDescending { it.score }
                    .thenBy { it.task.manualOrder }
                    .thenBy { it.task.deadline(zone) ?: Instant.MAX }
                    .thenBy { it.task.createdAt }
                    .thenBy { it.task.id },
            )
            .toList()
    }

    fun score(task: Task, now: Instant, zone: ZoneId, today: LocalDate = now.atZone(zone).toLocalDate()): RankedTask {
        var score = task.priority.weight
        val reasons = mutableListOf("${task.priority.label} priority")

        if (task.importance.score > 0) {
            score += task.importance.score
            reasons += "Marked ${task.importance.label.lowercase()}"
        }
        if (task.isMit(today)) {
            score += 50
            reasons += "Most important task today"
        }

        val deadline = task.deadline(zone)
        if (deadline != null) {
            val untilDue = Duration.between(now, deadline)
            when {
                untilDue.isNegative -> {
                    val hoursLate = (-untilDue.toHours()).coerceAtLeast(0)
                    score += 60 + (hoursLate * 2).coerceAtMost(40).toInt()
                    reasons += if (hoursLate >= 24) "Overdue by ${hoursLate / 24} day(s)" else "Overdue"
                }
                untilDue <= Duration.ofHours(2) -> { score += 45; reasons += "Due within 2 hours" }
                untilDue <= Duration.ofHours(6) -> { score += 35; reasons += "Due within 6 hours" }
                task.dueDate(zone) == today -> { score += 25; reasons += "Due today" }
                untilDue <= Duration.ofDays(3) -> { score += 12; reasons += "Due in the next 3 days" }
                untilDue <= Duration.ofDays(7) -> { score += 5; reasons += "Due this week" }
            }
            val est = task.estimatedMinutes
            if (est != null && est <= 15 && !untilDue.isNegative && untilDue <= Duration.ofHours(24)) {
                score += 5
                reasons += "Quick win (${est} min)"
            }
        }

        if (task.postponeCount > 0) {
            score += (task.postponeCount * 3).coerceAtMost(15)
            reasons += "Postponed ${task.postponeCount}×"
        }
        if (task.isSnoozed(now)) reasons += "Snoozed"
        return RankedTask(task, score, reasons)
    }
}
