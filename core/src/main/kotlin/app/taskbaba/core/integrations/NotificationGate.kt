package app.taskbaba.core.integrations

import app.taskbaba.core.model.AppSettings
import app.taskbaba.core.model.ClockTime
import java.time.Duration
import java.time.Instant

enum class GateDecision { NOTIFY_NOW, HOLD_FOR_DIGEST, SILENT }

data class GateResult(val decision: GateDecision, val reason: String)

/** A previously posted alert, used for rate limiting and duplicate suppression. */
data class AlertRecord(val at: Instant, val key: String)

/**
 * Decides whether a stored inbox item alerts now, waits for the digest, or stays silent.
 * Pure function of its inputs so every rule is unit-testable.
 */
object NotificationGate {
    val DUPLICATE_WINDOW: Duration = Duration.ofMinutes(30)

    fun alertKey(item: InboxItem) = "${item.source}|${item.sender.lowercase()}|${item.title.lowercase().take(80)}"

    fun decide(
        item: InboxItem,
        settings: IntegrationSettings,
        app: AppSettings,
        now: Instant,
        localTime: ClockTime,
        recentAlerts: List<AlertRecord>,
    ): GateResult {
        val source = settings.source(item.source)
        val hold = if (settings.digestEnabled) GateDecision.HOLD_FOR_DIGEST else GateDecision.SILENT

        if (item.status != InboxStatus.NEW) return GateResult(GateDecision.SILENT, "Already triaged")
        if (!source.enabled) return GateResult(GateDecision.SILENT, "Source disabled")
        if (source.muted) return GateResult(GateDecision.SILENT, "Source muted")
        if (!app.notificationsEnabled) return GateResult(GateDecision.SILENT, "Notifications are off")
        if (settings.paused) return GateResult(GateDecision.SILENT, "Inbox alerts paused")
        if (app.pausedUntil?.let { now.toEpochMilli() < it } == true) return GateResult(GateDecision.SILENT, "All Baba notifications paused")

        val priority = item.priority
        if (priority == InboxPriority.NOISE) return GateResult(GateDecision.SILENT, "Classified as noise")

        val key = alertKey(item)
        if (recentAlerts.any { it.key == key && Duration.between(it.at, now) < DUPLICATE_WINDOW }) {
            return GateResult(GateDecision.SILENT, "Duplicate of a recent alert")
        }

        val allowlisted = settings.allowSenders.any { it.isNotBlank() && item.sender.contains(it.trim(), true) } ||
            settings.allowChannels.any { it.isNotBlank() && item.channel.contains(it.trim(), true) }
        val effectiveRank = if (allowlisted) maxOf(priority.rank, InboxPriority.HIGH.rank) else priority.rank

        if (priority == InboxPriority.CRITICAL && settings.urgentExceptions) {
            return GateResult(GateDecision.NOTIFY_NOW, "Critical — urgent exception")
        }
        if (app.quietHoursEnabled && app.quietHours.contains(localTime)) return GateResult(hold, "Quiet hours")
        if (settings.focusActive(now)) return GateResult(hold, "Focus session")
        if (effectiveRank < settings.alertThreshold.rank) return GateResult(hold, "Below alert threshold")
        val lastHour = recentAlerts.count { Duration.between(it.at, now) < Duration.ofHours(1) }
        if (lastHour >= settings.maxAlertsPerHour) return GateResult(hold, "Hourly alert limit reached")
        return GateResult(GateDecision.NOTIFY_NOW, if (allowlisted) "Allowlisted" else "High priority")
    }

    /** True when a scheduled digest time has passed since the last digest (checked once a minute). */
    fun digestDue(settings: IntegrationSettings, now: Instant, zone: java.time.ZoneId): Boolean {
        if (!settings.digestEnabled || settings.digestTimes.isEmpty()) return false
        val today = now.atZone(zone).toLocalDate()
        val last = settings.lastDigestAt?.let(Instant::ofEpochMilli) ?: Instant.EPOCH
        return settings.digestTimes.any { t ->
            val slot = today.atTime(t.toLocalTime()).atZone(zone).toInstant()
            !slot.isAfter(now) && slot.isAfter(last)
        }
    }
}
