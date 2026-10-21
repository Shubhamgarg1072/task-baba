package app.taskbaba.core.reminders

import app.taskbaba.core.model.AppSettings
import app.taskbaba.core.model.ClockTime
import app.taskbaba.core.platform.NotificationKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class Gate { ALLOW, QUIET_HOURS, PAUSED, DISABLED, RATE_LIMITED }

/** Central rules for whether Baba may post a system notification right now. */
class NotificationPolicy {
    private val posted = ArrayDeque<Instant>()

    @Synchronized
    fun check(kind: NotificationKind, settings: AppSettings, now: Instant, zone: ZoneId): Gate {
        if (kind == NotificationKind.TEST) return Gate.ALLOW
        if (!settings.notificationsEnabled) return Gate.DISABLED
        if (kind == NotificationKind.HEALTH && !settings.healthNotifications) return Gate.DISABLED
        if (kind == NotificationKind.TASK && !settings.taskNotifications) return Gate.DISABLED
        settings.pausedUntil?.let { if (now.toEpochMilli() < it) return Gate.PAUSED }
        if (settings.quietHoursEnabled && settings.quietHours.contains(ClockTime.of(now.atZone(zone).toLocalTime()))) return Gate.QUIET_HOURS
        prune(now)
        if (posted.size >= settings.maxNotificationsPerHour) return Gate.RATE_LIMITED
        return Gate.ALLOW
    }

    @Synchronized
    fun recordPosted(now: Instant) {
        prune(now)
        posted.addLast(now)
    }

    private fun prune(now: Instant) {
        while (posted.isNotEmpty() && Duration.between(posted.first(), now) >= Duration.ofHours(1)) posted.removeFirst()
    }
}
