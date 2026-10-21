package app.taskbaba.core.monk

import app.taskbaba.core.data.MonkHistoryRepository
import app.taskbaba.core.model.HealthType
import app.taskbaba.core.model.Personality
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

data class MonkContext(
    val task: String? = null,
    val postponeCount: Int = 0,
    val done: Int = 0,
    val planned: Int = 0,
    val overdue: Int = 0,
) {
    val pct: Int get() = if (planned == 0) 0 else done * 100 / planned
}

data class MonkVoice(val personality: Personality, val sarcasmEnabled: Boolean, val muted: Boolean)

data class MonkLine(val id: String, val event: MonkEvent, val text: String, val tone: Tone?, val mood: MonkMood)

/**
 * Picks a line for an event. Deterministic given its [random] seed, avoids repeating recently shown
 * lines (also across restarts when a history repository is given), and falls back to plain wording when
 * the monk is muted.
 */
class MonkEngine(
    private val history: MonkHistoryRepository? = null,
    private val random: Random = Random.Default,
    private val recentWindow: Int = 24,
) {
    private val recent = ArrayDeque<String>().apply { history?.recent(recentWindow)?.asReversed()?.forEach { addLast(it) } }
    private val lastSpoken = mutableMapOf<MonkEvent, Instant>()

    fun toneFor(event: MonkEvent, voice: MonkVoice, ctx: MonkContext): Tone {
        if (!voice.sarcasmEnabled) return Tone.FRIENDLY
        if (event == MonkEvent.HEALTH_SKIPPED) return Tone.FRIENDLY // never tease someone for skipping a break
        return when (voice.personality) {
            Personality.FRIENDLY -> Tone.FRIENDLY
            Personality.SAVAGE -> Tone.SAVAGE
            Personality.ADAPTIVE -> when {
                event in HEALTH_EVENTS -> Tone.FRIENDLY
                ctx.postponeCount >= ADAPTIVE_POSTPONE_THRESHOLD -> Tone.SAVAGE
                ctx.overdue >= ADAPTIVE_OVERDUE_THRESHOLD -> Tone.SAVAGE
                else -> Tone.FRIENDLY
            }
        }
    }

    @Synchronized
    fun line(event: MonkEvent, voice: MonkVoice, ctx: MonkContext = MonkContext(), now: Instant = Instant.now()): MonkLine {
        val resolved = if (event == MonkEvent.TASK_POSTPONED && ctx.postponeCount >= ADAPTIVE_POSTPONE_THRESHOLD) MonkEvent.TASK_POSTPONED_REPEAT else event
        if (voice.muted) return MonkLine("plain.${resolved.name.lowercase()}", resolved, plain(resolved, ctx), null, MonkMood.CALM)

        val tone = toneFor(resolved, voice, ctx)
        val candidates = MessageLibrary.forEvent(resolved, tone).ifEmpty { MessageLibrary.forEvent(resolved, Tone.FRIENDLY) }
            .filter { ctx.task != null || "{task}" !in it.text }
            .ifEmpty { return MonkLine("plain.${resolved.name.lowercase()}", resolved, plain(resolved, ctx), tone, MonkMood.CALM) }

        val fresh = candidates.filter { it.id !in recent }
        val chosen = if (fresh.isNotEmpty()) fresh[random.nextInt(fresh.size)]
        else candidates.minBy { c -> recent.indexOf(c.id).let { if (it < 0) Int.MIN_VALUE else it } } // oldest use first

        recent.remove(chosen.id)
        recent.addLast(chosen.id)
        while (recent.size > recentWindow) recent.removeFirst()
        history?.record(chosen.id, resolved.name, now)
        lastSpoken[resolved] = now
        return MonkLine(chosen.id, resolved, fill(chosen.text, ctx), tone, chosen.mood)
    }

    /** Cooldown guard so chatty events (tips, greetings, welcome-backs) don't repeat too often. */
    @Synchronized
    fun mayRepeat(event: MonkEvent, now: Instant): Boolean {
        val cooldown = COOLDOWNS[event] ?: return true
        val last = lastSpoken[event] ?: return true
        return Duration.between(last, now) >= cooldown
    }

    companion object {
        const val ADAPTIVE_POSTPONE_THRESHOLD = 3
        const val ADAPTIVE_OVERDUE_THRESHOLD = 3

        val HEALTH_EVENTS = setOf(
            MonkEvent.HEALTH_WATER, MonkEvent.HEALTH_EYES, MonkEvent.HEALTH_STRETCH, MonkEvent.HEALTH_WALK,
            MonkEvent.HEALTH_MEAL, MonkEvent.HEALTH_REST, MonkEvent.HEALTH_DONE, MonkEvent.HEALTH_SKIPPED,
        )

        private val COOLDOWNS = mapOf(
            MonkEvent.FOCUS_TIP to Duration.ofMinutes(20),
            MonkEvent.RETURN_FROM_BREAK to Duration.ofMinutes(45),
            MonkEvent.GREETING_MORNING to Duration.ofHours(3),
            MonkEvent.GREETING_AFTERNOON to Duration.ofHours(3),
            MonkEvent.GREETING_EVENING to Duration.ofHours(3),
            MonkEvent.EMPTY_DAY to Duration.ofHours(2),
        )

        fun healthEvent(type: HealthType): MonkEvent = when (type) {
            HealthType.WATER -> MonkEvent.HEALTH_WATER
            HealthType.EYES -> MonkEvent.HEALTH_EYES
            HealthType.STRETCH -> MonkEvent.HEALTH_STRETCH
            HealthType.WALK -> MonkEvent.HEALTH_WALK
            HealthType.MEAL -> MonkEvent.HEALTH_MEAL
            HealthType.REST -> MonkEvent.HEALTH_REST
        }

        fun fill(template: String, ctx: MonkContext): String = template
            .replace("{task}", ctx.task?.take(80) ?: "your next task")
            .replace("{count}", ctx.postponeCount.toString())
            .replace("{done}", ctx.done.toString())
            .replace("{planned}", ctx.planned.toString())
            .replace("{pct}", ctx.pct.toString())
            .replace("{overdue}", ctx.overdue.toString())

        /** Neutral copy used when the monk is muted. */
        fun plain(event: MonkEvent, ctx: MonkContext): String {
            val task = ctx.task?.let { "\"${it.take(80)}\"" } ?: "A task"
            return when (event) {
                MonkEvent.GREETING_MORNING, MonkEvent.GREETING_AFTERNOON, MonkEvent.GREETING_EVENING ->
                    "${ctx.done} of ${ctx.planned} tasks done today."
                MonkEvent.EMPTY_DAY -> "No tasks planned for today."
                MonkEvent.TASK_CREATED -> "$task added."
                MonkEvent.MIT_CHOSEN -> "$task is today's most important task."
                MonkEvent.TASK_DONE, MonkEvent.IMPORTANT_DONE -> "$task completed."
                MonkEvent.ALL_PRIORITIES_DONE -> "All of today's priorities are complete."
                MonkEvent.TASK_DUE_SOON -> "$task is due soon."
                MonkEvent.TASK_OVERDUE -> "$task is overdue."
                MonkEvent.TASK_POSTPONED -> "$task postponed."
                MonkEvent.TASK_POSTPONED_REPEAT -> "$task postponed ${ctx.postponeCount} times."
                MonkEvent.HEALTH_WATER -> "Time to drink some water."
                MonkEvent.HEALTH_EYES -> "Eye break: look 20 feet away for 20 seconds."
                MonkEvent.HEALTH_STRETCH -> "Time to stand up and stretch."
                MonkEvent.HEALTH_WALK -> "Time for a short walk."
                MonkEvent.HEALTH_MEAL -> "Meal time."
                MonkEvent.HEALTH_REST -> "Time for a short rest break."
                MonkEvent.HEALTH_DONE -> "Recorded."
                MonkEvent.HEALTH_SKIPPED -> "Skipped."
                MonkEvent.RETURN_FROM_BREAK -> "Welcome back."
                MonkEvent.PRODUCTIVE_DAY, MonkEvent.UNPRODUCTIVE_DAY ->
                    "Today: ${ctx.done} of ${ctx.planned} tasks done (${ctx.pct}%)."
                MonkEvent.FOCUS_TIP -> "Focus on one task at a time."
            }
        }
    }
}
