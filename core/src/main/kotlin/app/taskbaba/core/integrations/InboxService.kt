package app.taskbaba.core.integrations

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.taskbaba.core.data.PersistedState
import app.taskbaba.core.model.AppSettings
import app.taskbaba.core.model.Category
import app.taskbaba.core.model.ClockTime
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.Task
import app.taskbaba.core.model.TaskDraft
import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.ClockProvider
import app.taskbaba.core.platform.NotificationAction
import app.taskbaba.core.platform.NotificationKind
import app.taskbaba.core.platform.NotificationResponse
import app.taskbaba.core.platform.Notifier
import app.taskbaba.db.Inbox_item
import app.taskbaba.db.TaskBabaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class InboxResponseOutcome { HANDLED, OPEN_INBOX, NOT_INBOX }

data class PollOutcome(val sourceId: String, val fetched: Int, val stored: Int, val failure: SourceFailure?)

/**
 * Polls connected sources, stores and classifies items, applies the notification gate, and performs the
 * user's triage actions. All state changes come from explicit user actions or explicit user rules.
 */
class InboxService(
    private val db: TaskBabaDatabase,
    private val settings: PersistedState<IntegrationSettings>,
    val sources: List<NotificationSource>,
    private val notifier: Notifier,
    private val taskCreator: FollowUpTaskCreator,
    private val appSettings: () -> AppSettings,
    private val scope: CoroutineScope,
    private val openBrowser: (String) -> Unit,
    private val clockProvider: ClockProvider = ClockProvider.SYSTEM,
) {
    private val q = db.inboxQueries
    private val ingestLock = Mutex()
    private val recentAlerts = ArrayDeque<AlertRecord>()
    private val refreshRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val jobs = mutableListOf<Job>()

    val items: StateFlow<List<InboxItem>> = q.selectAllItems().asFlow().mapToList(Dispatchers.IO)
        .map { rows -> rows.map { it.toModel() } }
        .stateIn(scope, SharingStarted.Eagerly, q.selectAllItems().executeAsList().map { it.toModel() })

    /** Untriaged, non-noise items — for sidebar and menu bar badges. */
    val newCount: StateFlow<Int> = items.map { list -> list.count { it.status == InboxStatus.NEW && it.priority != InboxPriority.NOISE } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private fun now(): Instant = clockProvider.clock().instant()
    private fun localTime(): ClockTime = ClockTime.of(java.time.LocalTime.now(clockProvider.clock()))
    private fun source(id: String) = sources.firstOrNull { it.id == id }

    fun start() {
        if (jobs.isNotEmpty()) return
        // Don't fire a digest for slots that passed before the app was ever opened.
        if (settings.value.lastDigestAt == null) settings.update { it.copy(lastDigestAt = now().toEpochMilli()) }
        purge()
        sources.forEach { src -> jobs += scope.launch { pollLoop(src) } }
        jobs += scope.launch {
            var ticks = 0L
            while (isActive) {
                delay(60_000)
                housekeeping()
                if (++ticks % 360 == 0L) purge()
            }
        }
    }

    fun stop() { jobs.forEach { it.cancel() }; jobs.clear() }

    fun refresh(sourceId: String) { refreshRequests.tryEmit(sourceId) }

    private suspend fun pollLoop(src: NotificationSource) {
        var failures = 0
        while (scope.isActive) {
            val cfg = settings.value.source(src.id)
            val wait = if (!cfg.enabled || !src.isConfigured()) {
                Duration.ofSeconds(30)
            } else {
                val outcome = pollOnce(src)
                failures = if (outcome.failure == null) 0 else failures + 1
                nextDelay(Duration.ofMinutes(cfg.pollMinutes.toLong()), failures, outcome.failure)
            }
            withTimeoutOrNull(wait.toMillis()) { refreshRequests.first { it == src.id } }
        }
    }

    /** Polls one source now. Safe to call from the UI ("Check now"). */
    suspend fun pollOnce(src: NotificationSource): PollOutcome {
        val startedAt = now()
        val since = settings.value.lastPollAt[src.id]?.let(Instant::ofEpochMilli) ?: startedAt.minus(Duration.ofHours(24))
        val result = src.poll(since)
        val failure = result.exceptionOrNull()?.let { it as? SourceFailure ?: SourceFailure.Protocol(it.message ?: "Error") }
        val fetched = result.getOrNull().orEmpty()
        val stored = if (failure == null) ingest(src.id, fetched) else 0
        if (failure == null) settings.update { it.copy(lastPollAt = it.lastPollAt + (src.id to startedAt.toEpochMilli())) }
        return PollOutcome(src.id, fetched.size, stored, failure)
    }

    /** Stores new items (duplicates ignored), classifies them, and alerts per the gate. Returns how many were new. */
    suspend fun ingest(sourceId: String, incoming: List<IncomingItem>): Int = ingestLock.withLock {
        val s = settings.value
        val app = appSettings()
        val now = now()
        val local = localTime()
        val retentionCutoff = now.minus(Duration.ofDays(s.retentionDays.toLong()))
        var stored = 0
        for (inc in incoming) {
            if (inc.receivedAt.isBefore(retentionCutoff)) continue
            if (q.itemBySourceExternal(sourceId, inc.externalId).executeAsOneOrNull() != null) continue
            val c = NotificationClassifier.classify(sourceId, inc, s.rules, s.focusActive(now), local)
            val id = UUID.randomUUID().toString()
            q.insertItem(Inbox_item(
                id = id, source = sourceId, external_id = inc.externalId, kind = inc.kind.name,
                sender = inc.sender.truncate(200), channel = inc.channel.truncate(200), title = inc.title.truncate(300),
                snippet = inc.snippet.truncate(600), url = inc.url, received_at = inc.receivedAt.toEpochMilli(),
                auto_priority = c.priority.name, user_priority = null, status = InboxStatus.NEW.name, snoozed_until = null,
                linked_task_id = null, fetched_at = now.toEpochMilli(), matched_rule = c.reason,
                mentions_me = if (inc.mentionsMe) 1 else 0, is_direct = if (inc.isDirect) 1 else 0, delivery = Delivery.NONE.name,
            ))
            stored++
            var item = q.itemById(id).executeAsOne().toModel()
            if (c.autoCreateTask) {
                // Only reached when the user ticked "create a task" on a rule they wrote.
                createFollowUpTask(id, c.priority.toTaskPriority(), LocalDate.now(clockProvider.clock()), null)
                item = q.itemById(id).executeAsOne().toModel()
            }
            deliver(item, s, app, now, local)
        }
        stored
    }

    private suspend fun deliver(item: InboxItem, s: IntegrationSettings, app: AppSettings, now: Instant, local: ClockTime) {
        while (recentAlerts.isNotEmpty() && Duration.between(recentAlerts.first().at, now) > Duration.ofHours(1)) recentAlerts.removeFirst()
        val gate = NotificationGate.decide(item, s, app, now, local, recentAlerts.toList())
        when (gate.decision) {
            GateDecision.NOTIFY_NOW -> {
                val posted = notifier.post(alertFor(item, s.previewInAlerts))
                if (posted.isSuccess) {
                    recentAlerts.addLast(AlertRecord(now, NotificationGate.alertKey(item)))
                    q.setDelivery(Delivery.ALERTED.name, item.id)
                } else {
                    q.setDelivery(Delivery.HELD.name, item.id)
                }
            }
            GateDecision.HOLD_FOR_DIGEST -> q.setDelivery(Delivery.HELD.name, item.id)
            GateDecision.SILENT -> q.setDelivery(Delivery.SILENT.name, item.id)
        }
    }

    private fun alertFor(item: InboxItem, preview: Boolean): BabaNotification {
        val src = source(item.source)
        val name = src?.displayName ?: item.source
        val actions = buildList {
            add(NotificationAction(ACTION_REVIEW, "Mark reviewed"))
            add(NotificationAction(ACTION_SNOOZE, "Snooze 1 hour"))
            if (item.url != null && src?.capabilities?.canOpenOriginal == true) add(NotificationAction(ACTION_OPEN, "Open original"))
        }
        return BabaNotification(
            id = "inbox-${item.id}",
            kind = NotificationKind.INBOX,
            title = if (preview) "$name · ${item.sender}" else "${item.priority.label} item from $name",
            body = if (preview) item.title else "Open Task Baba to see it.",
            actions = actions,
            sound = appSettings().soundEnabled && item.priority.rank >= InboxPriority.HIGH.rank,
            userInfo = mapOf(KIND_KEY to KIND_VALUE, ITEM_KEY to item.id),
        )
    }

    /** Wakes snoozed items, ends expired focus sessions, and sends due digests. Called every minute. */
    suspend fun housekeeping() {
        val now = now()
        q.wakeSnoozed(now.toEpochMilli())
        val focus = settings.value.focus
        if (focus != null && now.toEpochMilli() >= focus.endsAt) {
            settings.update { it.copy(focus = null) }
            sendDigest(reason = "Focus session over")
        } else if (NotificationGate.digestDue(settings.value, now, clockProvider.clock().zone)) {
            val app = appSettings()
            if (!(app.quietHoursEnabled && app.quietHours.contains(localTime())) && !settings.value.focusActive(now)) sendDigest("Scheduled digest")
        }
    }

    /** Posts one summary of held items and marks them digested. Returns the number summarized. */
    suspend fun sendDigest(reason: String = "Digest"): Int {
        val s = settings.value
        settings.update { it.copy(lastDigestAt = now().toEpochMilli()) }
        val held = q.heldItems().executeAsList().map { it.toModel() }
        if (held.isEmpty() || s.paused || !appSettings().notificationsEnabled) return 0
        val counts = held.groupingBy { it.priority }.eachCount().entries.sortedByDescending { it.key.rank }
            .joinToString(", ") { "${it.value} ${it.key.label.lowercase()}" }
        val top = held.maxByOrNull { it.priority.rank }
        val body = if (s.previewInAlerts && top != null) "$counts. Top: ${top.title.truncate(90)}" else "$counts waiting in your inbox."
        val posted = notifier.post(BabaNotification(
            id = "inbox-digest", kind = NotificationKind.INBOX,
            title = "Baba's inbox digest — ${held.size} item${if (held.size == 1) "" else "s"} ($reason)",
            body = body, sound = false, userInfo = mapOf(KIND_KEY to KIND_VALUE),
        ))
        if (posted.isFailure) return 0
        held.forEach { q.setDelivery(Delivery.DIGESTED.name, it.id) }
        return held.size
    }

    // --- Triage actions (local state only) ---------------------------------------------------------

    fun markReviewed(id: String) { q.updateStatus(InboxStatus.REVIEWED.name, null, id); scope.launch { notifier.remove("inbox-$id") } }
    fun reopen(id: String) = q.updateStatus(InboxStatus.NEW.name, null, id)
    fun dismiss(id: String) { q.updateStatus(InboxStatus.DISMISSED.name, null, id); scope.launch { notifier.remove("inbox-$id") } }
    fun snooze(id: String, duration: Duration) {
        q.updateStatus(InboxStatus.SNOOZED.name, now().plus(duration).toEpochMilli(), id)
        scope.launch { notifier.remove("inbox-$id") }
    }
    fun setUserPriority(id: String, priority: InboxPriority?) = q.setUserPriority(priority?.name, id)
    fun linkTask(id: String, taskId: String?) = q.linkTask(taskId, id)

    fun openOriginal(id: String): Boolean {
        val item = q.itemById(id).executeAsOneOrNull()?.toModel() ?: return false
        val url = item.url ?: return false
        if (source(item.source)?.capabilities?.canOpenOriginal != true) return false
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        openBrowser(url)
        return true
    }

    /** Creates a follow-up task the user asked for, links it, and marks the item reviewed. */
    fun createFollowUpTask(id: String, priority: Priority, dueDate: LocalDate?, dueTime: LocalTime?, title: String? = null): Result<Task> {
        val item = q.itemById(id).executeAsOneOrNull()?.toModel() ?: return Result.failure(IllegalArgumentException("That item no longer exists."))
        val srcName = source(item.source)?.displayName ?: item.source
        val draft = TaskDraft(
            title = (title?.takeIf { it.isNotBlank() } ?: "Follow up: ${item.title}").truncate(200),
            description = buildString {
                append("From $srcName")
                if (item.sender.isNotBlank()) append(" · ${item.sender}")
                if (item.channel.isNotBlank()) append(" · ${item.channel}")
                item.url?.let { append("\n$it") }
            },
            category = Category.WORK,
            priority = priority,
            dueDate = dueDate,
            dueTime = dueTime,
        )
        val errors = draft.validate()
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString(" ")))
        return taskCreator.create(draft).onSuccess { task ->
            q.linkTask(task.id, id)
            q.updateStatus(InboxStatus.REVIEWED.name, null, id)
        }
    }

    fun deleteAll() = q.deleteAllItems()
    fun deleteSource(sourceId: String) {
        q.deleteBySource(sourceId)
        settings.update { it.copy(lastPollAt = it.lastPollAt - sourceId) }
    }

    fun purge() {
        val cutoff = now().minus(Duration.ofDays(settings.value.retentionDays.toLong()))
        q.purgeOlderThan(cutoff.toEpochMilli())
    }

    // --- Focus -------------------------------------------------------------------------------------

    fun startFocus(minutes: Int) {
        val n = now().toEpochMilli()
        settings.update { it.copy(focus = FocusSession(n, n + minutes.coerceIn(5, 480) * 60_000L)) }
    }

    suspend fun stopFocus() {
        if (settings.value.focus == null) return
        settings.update { it.copy(focus = null) }
        sendDigest("Focus session ended")
    }

    /** Routes notification button presses for inbox alerts. */
    fun handleResponse(response: NotificationResponse): InboxResponseOutcome {
        if (response.userInfo[KIND_KEY] != KIND_VALUE) return InboxResponseOutcome.NOT_INBOX
        val id = response.userInfo[ITEM_KEY] ?: return InboxResponseOutcome.OPEN_INBOX
        return when (response.actionId) {
            ACTION_REVIEW -> { markReviewed(id); InboxResponseOutcome.HANDLED }
            ACTION_SNOOZE -> { snooze(id, Duration.ofHours(1)); InboxResponseOutcome.HANDLED }
            ACTION_OPEN -> if (openOriginal(id)) InboxResponseOutcome.HANDLED else InboxResponseOutcome.OPEN_INBOX
            else -> InboxResponseOutcome.OPEN_INBOX
        }
    }

    companion object {
        const val ACTION_REVIEW = "inbox.review"
        const val ACTION_SNOOZE = "inbox.snooze"
        const val ACTION_OPEN = "inbox.open"
        const val KIND_KEY = "kind"
        const val KIND_VALUE = "inbox"
        const val ITEM_KEY = "inboxItemId"

        /** Exponential backoff on failures (capped at 1h); honours Retry-After when rate limited. */
        fun nextDelay(base: Duration, consecutiveFailures: Int, failure: SourceFailure?): Duration = when {
            failure == null -> base
            failure is SourceFailure.RateLimited -> maxOf(failure.retryAfter, Duration.ofSeconds(30))
            failure is SourceFailure.AuthFailed || failure is SourceFailure.PermissionDenied || failure is SourceFailure.NotConfigured ->
                Duration.ofMinutes(30)
            else -> {
                val factor = 1L shl (consecutiveFailures - 1).coerceIn(0, 6)
                minOf(base.multipliedBy(factor), Duration.ofHours(1))
            }
        }
    }
}

internal fun InboxPriority.toTaskPriority(): Priority = when (this) {
    InboxPriority.CRITICAL -> Priority.CRITICAL
    InboxPriority.HIGH -> Priority.HIGH
    InboxPriority.NORMAL -> Priority.MEDIUM
    InboxPriority.LOW, InboxPriority.NOISE -> Priority.LOW
}

internal fun Inbox_item.toModel() = InboxItem(
    id = id, source = source, externalId = external_id,
    kind = runCatching { ItemKind.valueOf(kind) }.getOrDefault(ItemKind.MESSAGE),
    sender = sender, channel = channel, title = title, snippet = snippet, url = url,
    receivedAt = Instant.ofEpochMilli(received_at),
    autoPriority = runCatching { InboxPriority.valueOf(auto_priority) }.getOrDefault(InboxPriority.NORMAL),
    userPriority = user_priority?.let { runCatching { InboxPriority.valueOf(it) }.getOrNull() },
    status = runCatching { InboxStatus.valueOf(status) }.getOrDefault(InboxStatus.NEW),
    snoozedUntil = snoozed_until?.let(Instant::ofEpochMilli),
    linkedTaskId = linked_task_id,
    fetchedAt = Instant.ofEpochMilli(fetched_at),
    matchedRule = matched_rule,
    mentionsMe = mentions_me != 0L,
    isDirect = is_direct != 0L,
    delivery = runCatching { Delivery.valueOf(delivery) }.getOrDefault(Delivery.NONE),
)
