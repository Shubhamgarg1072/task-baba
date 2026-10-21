package app.taskbaba.core.integrations

import app.taskbaba.core.model.ClockTime
import app.taskbaba.core.model.TimeWindow
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
enum class InboxPriority(val label: String, val rank: Int) {
    CRITICAL("Critical", 4),
    HIGH("High", 3),
    NORMAL("Normal", 2),
    LOW("Low", 1),
    NOISE("Noise", 0),
}

/** Task Baba's own triage state. It is never synced back to the source app. */
enum class InboxStatus(val label: String) { NEW("New"), REVIEWED("Reviewed in Task Baba"), SNOOZED("Snoozed"), DISMISSED("Dismissed") }

enum class ItemKind(val label: String) { MESSAGE("Message"), MENTION("Mention"), DIRECT("Direct message"), ISSUE("Issue"), EMAIL("Email"), EVENT("Event") }

/** How Task Baba delivered an item: alerted immediately, held for a digest, folded into a digest, or kept silent. */
enum class Delivery { NONE, ALERTED, HELD, DIGESTED, SILENT }

/** An item as returned by a source adapter, before classification and storage. */
data class IncomingItem(
    val externalId: String,
    val kind: ItemKind,
    val sender: String,
    val channel: String,
    val title: String,
    val snippet: String,
    val url: String?,
    val receivedAt: Instant,
    val mentionsMe: Boolean = false,
    val isDirect: Boolean = false,
    val isBot: Boolean = false,
    /** The source itself flagged this as important (e.g. Outlook importance=high, Jira priority Highest). */
    val sourceMarkedImportant: Boolean = false,
)

data class InboxItem(
    val id: String,
    val source: String,
    val externalId: String,
    val kind: ItemKind,
    val sender: String,
    val channel: String,
    val title: String,
    val snippet: String,
    val url: String?,
    val receivedAt: Instant,
    val autoPriority: InboxPriority,
    val userPriority: InboxPriority?,
    val status: InboxStatus,
    val snoozedUntil: Instant?,
    val linkedTaskId: String?,
    val fetchedAt: Instant,
    val matchedRule: String?,
    val mentionsMe: Boolean,
    val isDirect: Boolean,
    val delivery: Delivery,
) {
    /** The user's override always wins over automatic classification. */
    val priority: InboxPriority get() = userPriority ?: autoPriority

    val recommendedAction: String get() = when {
        linkedTaskId != null -> "Tracked as a task — work it from your task list"
        priority == InboxPriority.NOISE -> "Probably noise — dismiss it"
        kind == ItemKind.EVENT -> "Prepare for or join this event"
        priority == InboxPriority.CRITICAL -> "Open it now"
        kind == ItemKind.ISSUE -> "Create a follow-up task if it needs work"
        priority == InboxPriority.HIGH -> "Reply soon — open the original"
        priority == InboxPriority.LOW -> "Review later in a digest"
        else -> "Review when you have a moment"
    }
}

/**
 * A user rule. Every condition that is set must match; unset conditions are ignored. Rules are evaluated
 * in list order and the first enabled match wins.
 */
@Serializable
data class ClassificationRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val source: String? = null,
    val senderContains: String? = null,
    val channelContains: String? = null,
    val keywords: List<String> = emptyList(),
    val requireMention: Boolean = false,
    /** null = any time; true = only while a focus session is active; false = only outside focus. */
    val duringFocus: Boolean? = null,
    val timeWindow: TimeWindow? = null,
    val priority: InboxPriority,
    /** Only when the user explicitly ticks this does a matching item become a task automatically. */
    val autoCreateTask: Boolean = false,
) {
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("Give the rule a name.")
        val hasCondition = source != null || !senderContains.isNullOrBlank() || !channelContains.isNullOrBlank() ||
            keywords.any { it.isNotBlank() } || requireMention || duringFocus != null || timeWindow != null
        if (!hasCondition) add("A rule needs at least one condition, otherwise it would match everything.")
        if (keywords.size > 30) add("Up to 30 keywords per rule.")
    }
}

@Serializable
data class SourceSettings(
    val enabled: Boolean = true,
    /** Muted sources are still fetched into the inbox but never alert. */
    val muted: Boolean = false,
    val pollMinutes: Int = 5,
)

@Serializable
data class FocusSession(val startedAt: Long, val endsAt: Long)

@Serializable
data class IntegrationSettings(
    val sources: Map<String, SourceSettings> = emptyMap(),
    /** Non-secret connection details per source (site URL, email, client ID). Secrets are in the Keychain. */
    val sourceConfig: Map<String, Map<String, String>> = emptyMap(),
    val rules: List<ClassificationRule> = emptyList(),
    /** Minimum priority that may alert immediately; lower priorities wait for the digest. */
    val alertThreshold: InboxPriority = InboxPriority.HIGH,
    /** Critical items break through focus sessions and quiet hours. */
    val urgentExceptions: Boolean = true,
    val digestEnabled: Boolean = true,
    val digestTimes: List<ClockTime> = listOf(ClockTime.of(12, 30), ClockTime.of(17, 30)),
    val retentionDays: Int = 7,
    val maxAlertsPerHour: Int = 4,
    val allowSenders: List<String> = emptyList(),
    val allowChannels: List<String> = emptyList(),
    val paused: Boolean = false,
    /** When false, alerts say only "New high-priority message from Slack" without content. */
    val previewInAlerts: Boolean = true,
    val focus: FocusSession? = null,
    val lastDigestAt: Long? = null,
    val lastPollAt: Map<String, Long> = emptyMap(),
) {
    fun source(id: String) = sources[id] ?: SourceSettings()

    fun focusActive(now: Instant): Boolean = focus?.let { now.toEpochMilli() in it.startedAt until it.endsAt } == true

    fun validate(): List<String> = buildList {
        if (retentionDays !in 1..90) add("Retention must be 1–90 days.")
        if (maxAlertsPerHour !in 1..30) add("Max alerts per hour must be 1–30.")
        sources.forEach { (id, s) -> if (s.pollMinutes !in 1..120) add("$id: polling interval must be 1–120 minutes.") }
        rules.forEach { addAll(it.validate()) }
    }
}
