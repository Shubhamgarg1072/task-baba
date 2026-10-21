package app.taskbaba.core.integrations

import app.taskbaba.core.model.ClockTime

data class Classification(
    val priority: InboxPriority,
    /** Rule name or built-in reason, shown to the user as "why". */
    val reason: String,
    val autoCreateTask: Boolean = false,
)

/**
 * Deterministic classification: the user's rules first (in order, first match wins), then built-in
 * defaults. A per-item user override is applied on top by [InboxItem.priority]. No AI is involved.
 */
object NotificationClassifier {
    private val CRITICAL_WORDS = Regex(
        "(?i)\\b(urgent|asap|outage|incident|sev[ -]?[01]|p0|p1|prod(uction)? (is )?down|site (is )?down|blocker|emergency)\\b",
    )
    private val NOISE_SENDERS = Regex("(?i)(no-?reply|newsletter|notifications?@|mailer-daemon|digest|marketing)")
    private val NOISE_TEXT = Regex("(?i)\\b(unsubscribe|weekly digest|newsletter|promo(tion)?s?)\\b")

    fun classify(
        sourceId: String,
        item: IncomingItem,
        rules: List<ClassificationRule>,
        focusActive: Boolean,
        localTime: ClockTime,
    ): Classification {
        rules.firstOrNull { it.enabled && matches(it, sourceId, item, focusActive, localTime) }?.let {
            return Classification(it.priority, "Rule: ${it.name}", it.autoCreateTask)
        }
        val text = "${item.title} ${item.snippet}"
        return when {
            CRITICAL_WORDS.containsMatchIn(text) && !item.isBot -> Classification(InboxPriority.CRITICAL, "Urgent wording")
            item.isBot || NOISE_SENDERS.containsMatchIn(item.sender) || NOISE_TEXT.containsMatchIn(text) ->
                Classification(InboxPriority.NOISE, "Looks automated or promotional")
            item.isDirect -> Classification(InboxPriority.HIGH, if (item.kind == ItemKind.ISSUE) "Assigned to you" else "Direct message")
            item.mentionsMe -> Classification(InboxPriority.HIGH, "You were mentioned")
            item.sourceMarkedImportant -> Classification(InboxPriority.HIGH, "Marked important by the source")
            item.kind == ItemKind.EVENT -> Classification(InboxPriority.NORMAL, "Upcoming event")
            item.kind == ItemKind.EMAIL -> Classification(InboxPriority.NORMAL, "Unread email")
            else -> Classification(InboxPriority.NORMAL, "Default")
        }
    }

    fun matches(rule: ClassificationRule, sourceId: String, item: IncomingItem, focusActive: Boolean, localTime: ClockTime): Boolean {
        if (rule.source != null && rule.source != sourceId) return false
        if (!rule.senderContains.isNullOrBlank() && !item.sender.contains(rule.senderContains.trim(), ignoreCase = true)) return false
        if (!rule.channelContains.isNullOrBlank() && !item.channel.contains(rule.channelContains.trim(), ignoreCase = true)) return false
        val keywords = rule.keywords.map { it.trim() }.filter { it.isNotEmpty() }
        if (keywords.isNotEmpty()) {
            val text = "${item.title} ${item.snippet}"
            if (keywords.none { text.contains(it, ignoreCase = true) }) return false
        }
        if (rule.requireMention && !(item.mentionsMe || item.isDirect)) return false
        if (rule.duringFocus != null && rule.duringFocus != focusActive) return false
        if (rule.timeWindow != null && !rule.timeWindow.contains(localTime)) return false
        return true
    }
}
