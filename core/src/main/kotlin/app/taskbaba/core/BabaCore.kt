package app.taskbaba.core

import app.taskbaba.core.ai.MonkAi
import app.taskbaba.core.ai.MonkAiRequest
import app.taskbaba.core.data.BabaJson
import app.taskbaba.core.data.DayRecord
import app.taskbaba.core.data.DayRepository
import app.taskbaba.core.data.HealthRepository
import app.taskbaba.core.data.KvStore
import app.taskbaba.core.data.MonkHistoryRepository
import app.taskbaba.core.data.ReminderState
import app.taskbaba.core.data.SeriesRepository
import app.taskbaba.core.data.TaskRepository
import app.taskbaba.core.model.AppSettings
import app.taskbaba.core.model.ClockTime
import app.taskbaba.core.model.HealthEvent
import app.taskbaba.core.model.HealthOutcome
import app.taskbaba.core.model.HealthReminderConfig
import app.taskbaba.core.model.HealthType
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.Task
import app.taskbaba.core.model.Weekday
import app.taskbaba.core.monk.MonkContext
import app.taskbaba.core.monk.MonkEngine
import app.taskbaba.core.monk.MonkEvent
import app.taskbaba.core.monk.MonkLine
import app.taskbaba.core.monk.MonkVoice
import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.ClockProvider
import app.taskbaba.core.platform.NotificationAction
import app.taskbaba.core.platform.NotificationKind
import app.taskbaba.core.platform.NotificationResponse
import app.taskbaba.core.platform.Notifier
import app.taskbaba.core.reminders.Gate
import app.taskbaba.core.reminders.HealthSchedule
import app.taskbaba.core.reminders.NotificationPolicy
import app.taskbaba.core.stats.StatsCalculator
import app.taskbaba.core.tasks.TaskEvent
import app.taskbaba.core.tasks.TaskService
import app.taskbaba.db.TaskBabaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class UpcomingReminder(val type: HealthType, val at: Instant)

/**
 * Application core: owns persistence, schedules reminders, reacts to task events, and keeps the monk's
 * current line. Reminders run on their own loop and never wait on AI.
 */
class BabaCore(
    val db: TaskBabaDatabase,
    private val notifier: Notifier,
    private val clock: ClockProvider = ClockProvider.SYSTEM,
    private val monkAi: MonkAi? = null,
) {
    val kv = KvStore(db)
    val settings = kv.state("app", AppSettings.serializer()) { AppSettings() }
    val tasksRepo = TaskRepository(db)
    val seriesRepo = SeriesRepository(db)
    val healthRepo = HealthRepository(db)
    val dayRepo = DayRepository(db)
    private val monkHistory = MonkHistoryRepository(db)
    val monk = MonkEngine(monkHistory)
    private val policy = NotificationPolicy()
    private val tickMutex = Mutex()

    val tasks = TaskService(tasksRepo, seriesRepo, clock, ::onTaskEvent)

    private val _monkLine = MutableStateFlow(monk.line(MonkEvent.FOCUS_TIP, voice(), MonkContext(), now()))
    val monkLine: StateFlow<MonkLine> = _monkLine.asStateFlow()

    private val _upcoming = MutableStateFlow<List<UpcomingReminder>>(emptyList())
    val upcomingReminders: StateFlow<List<UpcomingReminder>> = _upcoming.asStateFlow()

    /** Bumped every tick so time-dependent UI (overdue, "today") refreshes even without data changes. */
    private val _now = MutableStateFlow(now())
    val nowFlow: StateFlow<Instant> = _now.asStateFlow()

    private var scope: CoroutineScope? = null
    private var aiJob: Job? = null
    private var lastHealthConfigs: List<HealthReminderConfig> = emptyList()

    fun now(): Instant = clock.clock().instant()
    fun zone(): ZoneId = clock.clock().zone
    fun today(): LocalDate = now().atZone(zone()).toLocalDate()

    fun voice(s: AppSettings = settings.value) = MonkVoice(s.personality, s.sarcasmEnabled, s.monkMuted)

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch(Dispatchers.IO) { notifier.responses.collect { handleResponse(it) } }
        scope.launch(Dispatchers.IO) {
            tick(startup = true)
            while (isActive) {
                delay(TICK.toMillis())
                runCatching { tick(startup = false) }
            }
        }
    }

    /** Runs one scheduling pass immediately (after settings changes, wake from sleep, tests). */
    suspend fun tick(startup: Boolean = false) = tickMutex.withLock {
        val now = now()
        _now.value = now
        rollover(now, startup)
        tasks.generateRecurring()
        healthTick(now)
        taskTick(now)
        summaryTick(now)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = settings.update(transform)
        scope?.launch(Dispatchers.IO) { runCatching { tick() } }
        return next
    }

    // ── Monk ────────────────────────────────────────────────────────────────

    fun speak(event: MonkEvent, ctx: MonkContext = todayContext()): MonkLine {
        val line = monk.line(event, voice(), ctx, now())
        _monkLine.value = line
        enhanceWithAi(line, ctx)
        return line
    }

    /** Only the in-app line is ever AI-rewritten; notifications always use the library text. */
    private fun enhanceWithAi(line: MonkLine, ctx: MonkContext) {
        val ai = monkAi ?: return
        val s = settings.value
        if (s.monkMuted) return
        val scope = scope ?: return
        aiJob?.cancel()
        aiJob = scope.launch(Dispatchers.IO) {
            val rewritten = withTimeoutOrNull(AI_BUDGET.toMillis()) {
                ai.rewrite(
                    MonkAiRequest(
                        event = line.event.name, personality = s.personality, sarcasmAllowed = s.sarcasmEnabled && line.tone != null,
                        fallback = line.text,
                        context = buildMap {
                            ctx.task?.let { put("task", it) }
                            put("done", ctx.done.toString()); put("planned", ctx.planned.toString())
                            if (ctx.postponeCount > 0) put("postponed", ctx.postponeCount.toString())
                        },
                    ),
                )
            }
            if (rewritten != null && _monkLine.value.id == line.id) _monkLine.value = line.copy(text = rewritten, id = line.id + ".ai")
        }
    }

    fun todayContext(task: Task? = null): MonkContext {
        val all = tasks.all()
        val day = StatsCalculator.day(all, emptyList(), today(), zone(), now(), settings.value.dailyGoalPercent)
        val top = task ?: TaskService.topPriorities(all, now(), zone(), 1).firstOrNull()?.task
        return MonkContext(task = top?.title, postponeCount = task?.postponeCount ?: 0, done = day.completed, planned = day.planned,
            overdue = all.count { it.isOverdue(now(), zone()) })
    }

    fun greet() {
        val hour = now().atZone(zone()).hour
        val all = tasks.all()
        val event = when {
            StatsCalculator.plannedFor(all, today(), zone()).isEmpty() && all.none { !it.isCompleted } -> MonkEvent.EMPTY_DAY
            hour < 12 -> MonkEvent.GREETING_MORNING
            hour < 17 -> MonkEvent.GREETING_AFTERNOON
            else -> MonkEvent.GREETING_EVENING
        }
        speak(event)
    }

    /** Called when the user comes back to the app after being away. */
    fun onReturn(away: Duration) {
        if (away >= Duration.ofMinutes(30) && monk.mayRepeat(MonkEvent.RETURN_FROM_BREAK, now())) speak(MonkEvent.RETURN_FROM_BREAK)
    }

    private fun onTaskEvent(e: TaskEvent) {
        val ctx = todayContext(e.task)
        when (e) {
            is TaskEvent.Created -> speak(MonkEvent.TASK_CREATED, ctx)
            is TaskEvent.MitChosen -> speak(MonkEvent.MIT_CHOSEN, ctx)
            is TaskEvent.Postponed -> speak(MonkEvent.TASK_POSTPONED, ctx)
            is TaskEvent.Completed -> {
                scope?.launch { notifier.remove(taskNotificationId(e.task.id, "overdue")); notifier.remove(taskNotificationId(e.task.id, "soon")) }
                val all = tasks.all()
                val today = today()
                val priorities = StatsCalculator.plannedFor(all, today, zone())
                    .filter { it.priority in StatsCalculator.PRIORITY_LEVELS || it.mitDate == today }
                val important = e.task.priority in StatsCalculator.PRIORITY_LEVELS || e.task.mitDate == today
                when {
                    important && priorities.isNotEmpty() && priorities.all { it.isCompleted } -> speak(MonkEvent.ALL_PRIORITIES_DONE, ctx)
                    important -> speak(MonkEvent.IMPORTANT_DONE, ctx)
                    else -> speak(MonkEvent.TASK_DONE, ctx)
                }
            }
            is TaskEvent.Reopened, is TaskEvent.Deleted -> Unit
        }
    }

    // ── Notifications ───────────────────────────────────────────────────────

    /** Posts through the policy. Returns the gate decision so callers can reschedule. */
    suspend fun postGated(n: BabaNotification): Gate {
        val gate = policy.check(n.kind, settings.value, now(), zone())
        if (gate != Gate.ALLOW) return gate
        val result = notifier.post(n.copy(sound = n.sound && settings.value.soundEnabled))
        if (result.isSuccess) policy.recordPosted(now())
        return if (result.isSuccess) Gate.ALLOW else Gate.DISABLED
    }

    suspend fun sendTestNotification(): Result<Unit> {
        val line = monk.line(MonkEvent.HEALTH_WATER, voice(), MonkContext(), now())
        return notifier.post(
            BabaNotification("test", NotificationKind.TEST, "Task Baba test", line.text, HEALTH_ACTIONS, sound = settings.value.soundEnabled,
                userInfo = mapOf("test" to "true")),
        )
    }

    private suspend fun handleResponse(r: NotificationResponse) {
        val info = r.userInfo
        when {
            info["test"] == "true" -> speak(if (r.actionId == ACTION_DONE) MonkEvent.HEALTH_DONE else MonkEvent.FOCUS_TIP)
            info["healthEvent"] != null -> {
                val id = info.getValue("healthEvent")
                when (r.actionId) {
                    ACTION_DONE -> respondHealth(id, HealthOutcome.DONE)
                    ACTION_LATER -> respondHealth(id, HealthOutcome.SNOOZED)
                    ACTION_SKIP -> respondHealth(id, HealthOutcome.SKIPPED)
                    ACTION_DISABLE -> healthRepo.byId(id)?.let { e -> setHealthEnabled(e.type, false) }
                }
            }
            info["task"] != null -> {
                val id = info.getValue("task")
                when (r.actionId) {
                    ACTION_DONE -> tasks.complete(id)
                    ACTION_SNOOZE -> tasks.snooze(id, Duration.ofHours(1))
                    ACTION_TOMORROW -> tasks.reschedule(id, today().plusDays(1))
                }
            }
        }
    }

    // ── Health ──────────────────────────────────────────────────────────────

    /** Records the user's answer to a delivered reminder. Only DONE counts as a confirmed action. */
    fun respondHealth(eventId: String, outcome: HealthOutcome) {
        val e = healthRepo.byId(eventId) ?: return
        healthRepo.respond(eventId, outcome, now())
        scope?.launch { notifier.remove(eventId) }
        when (outcome) {
            HealthOutcome.DONE -> speak(MonkEvent.HEALTH_DONE)
            HealthOutcome.SKIPPED -> speak(MonkEvent.HEALTH_SKIPPED)
            HealthOutcome.SNOOZED -> {
                val cfg = settings.value.healthConfig(e.type)
                val state = healthRepo.states()[e.type]
                healthRepo.saveState(ReminderState(e.type, now().plusSeconds(cfg.snoozeMinutes * 60L), state?.lastFiredAt))
                refreshUpcoming()
            }
            HealthOutcome.DELIVERED -> Unit
        }
    }

    /** "I drank water" without a reminder. Stored as user-recorded (never delivered). */
    fun logHealth(type: HealthType) {
        val now = now()
        healthRepo.insert(HealthEvent(UUID.randomUUID().toString(), type, now, null, HealthOutcome.DONE, now, today()))
        speak(MonkEvent.HEALTH_DONE)
    }

    fun setHealthEnabled(type: HealthType, enabled: Boolean) = updateSettings { s ->
        s.copy(health = s.health.map { if (it.type == type) it.copy(enabled = enabled) else it })
    }

    fun pauseAll(duration: Duration?) = updateSettings { it.copy(pausedUntil = duration?.let { d -> now().plus(d).toEpochMilli() }) }

    fun pauseUntilTomorrow() = updateSettings {
        it.copy(pausedUntil = today().plusDays(1).atTime(it.workday.start.toLocalTime()).atZone(zone()).toInstant().toEpochMilli())
    }

    fun pendingHealth(): List<HealthEvent> = healthRepo.forDate(today()).filter { it.outcome == HealthOutcome.DELIVERED && it.deliveredAt != null }

    private suspend fun healthTick(now: Instant) {
        val s = settings.value
        val zone = zone()
        val quiet = s.quietHours.takeIf { s.quietHoursEnabled }
        val states = healthRepo.states()
        val changed = s.health != lastHealthConfigs
        for (cfg in s.health) {
            val state = states[cfg.type]
            if (!cfg.enabled) {
                if (state?.nextDueAt != null) healthRepo.saveState(state.copy(nextDueAt = null))
                continue
            }
            val prevCfg = lastHealthConfigs.firstOrNull { it.type == cfg.type }
            var due = state?.nextDueAt
            if (due == null || (changed && prevCfg != null && prevCfg != cfg)) {
                val base = if (cfg.type == HealthType.MEAL) now else (state?.lastFiredAt?.takeIf { it.isAfter(now.minus(Duration.ofMinutes(cfg.intervalMinutes.toLong()))) } ?: now)
                due = HealthSchedule.nextDue(cfg, base, zone, quiet)
                healthRepo.saveState(ReminderState(cfg.type, due, state?.lastFiredAt))
            }
            if (due == null || now.isBefore(due)) continue

            // Missed by a lot (Mac asleep / app closed)? Don't fire stale reminders; just reschedule.
            val stale = Duration.between(due, now) > STALE_AFTER
            if (!stale) {
                val eventId = "${cfg.type.name}@${due.epochSecond / 60}"
                if (healthRepo.byId(eventId) == null) {
                    val line = monk.line(MonkEngine.healthEvent(cfg.type), voice(s), MonkContext(), now)
                    val gate = postGated(
                        BabaNotification(eventId, NotificationKind.HEALTH, "${cfg.type.emoji} ${cfg.type.label}", line.text, HEALTH_ACTIONS,
                            sound = cfg.sound, userInfo = mapOf("healthEvent" to eventId, "type" to cfg.type.name)),
                    )
                    when (gate) {
                        Gate.RATE_LIMITED -> { healthRepo.saveState(ReminderState(cfg.type, now.plus(Duration.ofMinutes(5)), state?.lastFiredAt)); continue }
                        Gate.PAUSED -> {
                            val resume = Instant.ofEpochMilli(s.pausedUntil!!)
                            val resumeAt = if (HealthSchedule.allowed(resume, cfg, zone, quiet)) resume
                                else HealthSchedule.nextDue(cfg, resume, zone, quiet)
                            healthRepo.saveState(ReminderState(cfg.type, resumeAt, state?.lastFiredAt))
                            continue
                        }
                        Gate.QUIET_HOURS -> Unit // quiet hours changed after scheduling: skip this one silently
                        Gate.ALLOW, Gate.DISABLED -> {
                            // Shown in-app even when notifications are off, so the record reflects a real delivery.
                            healthRepo.insert(HealthEvent(eventId, cfg.type, due, now, HealthOutcome.DELIVERED, null, today()))
                            if (_monkLine.value.event !in MonkEngine.HEALTH_EVENTS) _monkLine.value = line
                        }
                    }
                }
            }
            val next = HealthSchedule.nextDue(cfg, now, zone, quiet)
            healthRepo.saveState(ReminderState(cfg.type, next, now))
        }
        lastHealthConfigs = s.health
        refreshUpcoming()
    }

    private fun refreshUpcoming() {
        val paused = settings.value.pausedUntil?.let(Instant::ofEpochMilli)
        _upcoming.value = healthRepo.states().values
            .filter { st -> settings.value.healthConfig(st.type).enabled && st.nextDueAt != null }
            .map { UpcomingReminder(it.type, if (paused != null && paused.isAfter(it.nextDueAt)) paused else it.nextDueAt!!) }
            .sortedBy { it.at }
    }

    // ── Tasks ───────────────────────────────────────────────────────────────

    private val notifiedSerializer = MapSerializer(String.serializer(), String.serializer())

    private suspend fun taskTick(now: Instant) {
        val zone = zone()
        val notified = kv.load("notified.tasks", notifiedSerializer) { emptyMap() }.toMutableMap()
        val all = tasks.all()
        val open = all.filter { !it.isCompleted }.associateBy { it.id }
        notified.keys.retainAll(open.keys)
        for (t in open.values) {
            if (t.isSnoozed(now)) continue
            val deadline = t.deadline(zone) ?: continue
            val kind = when {
                deadline.isBefore(now) -> "overdue"
                t.due is app.taskbaba.core.model.Due.At && Duration.between(now, deadline) <= DUE_SOON -> "soon"
                else -> continue
            }
            if (notified[t.id] == kind || (kind == "soon" && notified[t.id] == "overdue")) continue
            // Quiet hours hold the alert (it is retried on later ticks); very old overdue tasks are not announced.
            if (kind == "overdue" && Duration.between(deadline, now) > Duration.ofHours(36)) { notified[t.id] = kind; continue }
            val overdueCount = open.values.count { it.isOverdue(now, zone) }
            val ctx = MonkContext(t.title, t.postponeCount, overdue = overdueCount)
            val event = if (kind == "overdue") MonkEvent.TASK_OVERDUE else MonkEvent.TASK_DUE_SOON
            val line = monk.line(event, voice(), ctx, now)
            val gate = postGated(
                BabaNotification(taskNotificationId(t.id, kind), NotificationKind.TASK,
                    if (kind == "overdue") "Overdue: ${t.title.take(60)}" else "Due soon: ${t.title.take(60)}",
                    line.text, TASK_ACTIONS, userInfo = mapOf("task" to t.id)),
            )
            if (gate == Gate.ALLOW || gate == Gate.DISABLED) {
                notified[t.id] = kind
                if (kind == "overdue") _monkLine.value = line
            }
        }
        kv.save("notified.tasks", notifiedSerializer, notified)
    }

    private suspend fun summaryTick(now: Instant) {
        val s = settings.value
        if (!s.endOfDaySummary) return
        val local = now.atZone(zone())
        if (Weekday.of(local.dayOfWeek) !in s.workdays) return
        if (ClockTime.of(local.toLocalTime()) < s.workday.end) return
        val today = today()
        if (kv.load("summary.lastDate", String.serializer()) { "" } == today.toString()) return
        val record = todayRecord()
        if (record.planned == 0) { kv.save("summary.lastDate", String.serializer(), today.toString()); return }
        val line = summaryLine(record)
        postGated(BabaNotification("summary@$today", NotificationKind.SUMMARY, "Baba's end-of-day review", line.text))
        _monkLine.value = line
        kv.save("summary.lastDate", String.serializer(), today.toString())
    }

    fun summaryLine(r: DayRecord): MonkLine {
        val ctx = MonkContext(done = r.completed, planned = r.planned)
        return monk.line(if (r.goalMet) MonkEvent.PRODUCTIVE_DAY else MonkEvent.UNPRODUCTIVE_DAY, voice(), ctx, now())
    }

    // ── Days ────────────────────────────────────────────────────────────────

    fun todayRecord(): DayRecord = StatsCalculator.day(
        tasks.all(), healthRepo.forDate(today()), today(), zone(), now(), settings.value.dailyGoalPercent,
    )

    /** Freezes summaries of finished days into history. Never deletes tasks or events. */
    private fun rollover(now: Instant, startup: Boolean) {
        val today = now.atZone(zone()).toLocalDate()
        val lastRaw = kv.load("lastActiveDate", String.serializer()) { "" }
        val last = runCatching { LocalDate.parse(lastRaw) }.getOrNull()
        if (last == today) return
        if (last != null && last.isBefore(today)) {
            val all = tasks.all()
            var d = maxOf(last, today.minusDays(31))
            while (d.isBefore(today)) {
                if (dayRepo.byDate(d) == null || d == last) {
                    val rec = StatsCalculator.day(all, healthRepo.forDate(d), d, zone(), now, settings.value.dailyGoalPercent)
                    if (rec.planned > 0 || rec.healthDelivered > 0 || rec.healthDone > 0) dayRepo.upsert(rec.copy(summary = summaryLine(rec).text))
                }
                d = d.plusDays(1)
            }
        }
        kv.save("lastActiveDate", String.serializer(), today.toString())
        if (!startup) { tasks.generateRecurring(); greet() }
    }

    /** Deletes everything stored locally (tasks, history, settings). Used by Settings → Reset. */
    fun resetAllData() {
        db.transaction {
            tasks.deleteAll(); healthRepo.deleteAll(); dayRepo.deleteAll(); monkHistory.deleteAll()
            listOf("notified.tasks", "summary.lastDate", "lastActiveDate").forEach(kv::delete)
        }
        settings.update { AppSettings(onboarded = true) }
        lastHealthConfigs = emptyList()
    }

    companion object {
        val TICK: Duration = Duration.ofSeconds(20)
        val STALE_AFTER: Duration = Duration.ofMinutes(30)
        val DUE_SOON: Duration = Duration.ofMinutes(15)
        val AI_BUDGET: Duration = Duration.ofSeconds(12)

        const val ACTION_DONE = "done"
        const val ACTION_LATER = "later"
        const val ACTION_SKIP = "skip"
        const val ACTION_DISABLE = "disable"
        const val ACTION_SNOOZE = "snooze"
        const val ACTION_TOMORROW = "tomorrow"

        val HEALTH_ACTIONS = listOf(
            NotificationAction(ACTION_DONE, "Done ✓"),
            NotificationAction(ACTION_LATER, "Remind me later"),
            NotificationAction(ACTION_SKIP, "Skip"),
            NotificationAction(ACTION_DISABLE, "Turn off this reminder", destructive = true),
        )
        val TASK_ACTIONS = listOf(
            NotificationAction(ACTION_DONE, "Mark done"),
            NotificationAction(ACTION_SNOOZE, "Snooze 1 hour"),
            NotificationAction(ACTION_TOMORROW, "Move to tomorrow"),
        )

        fun taskNotificationId(taskId: String, kind: String) = "task-$kind-$taskId"
    }
}
