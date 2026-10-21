package app.taskbaba.core.integrations

import app.taskbaba.core.model.AppSettings
import app.taskbaba.core.model.ClockTime
import app.taskbaba.core.model.TimeWindow
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassifierAndGateTest {
    private val t0 = Instant.parse("2026-10-21T05:00:00Z") // 10:30 in Kolkata
    private val tenAm = ClockTime.of(10, 30)

    private fun incoming(
        title: String = "Can you look at the build?",
        sender: String = "Asha",
        channel: String = "#team",
        mention: Boolean = false,
        direct: Boolean = false,
        bot: Boolean = false,
        kind: ItemKind = ItemKind.MESSAGE,
    ) = IncomingItem("x", kind, sender, channel, title, "", "https://example.com", t0, mention, direct, bot)

    private fun stored(priority: InboxPriority, user: InboxPriority? = null, source: String = "slack", sender: String = "Asha", channel: String = "#team", title: String = "Hello") =
        InboxItem("id", source, "e", ItemKind.MESSAGE, sender, channel, title, "", null, t0, priority, user,
            InboxStatus.NEW, null, null, t0, null, false, false, Delivery.NONE)

    @Test fun `built-in defaults`() {
        fun c(i: IncomingItem) = NotificationClassifier.classify("slack", i, emptyList(), false, tenAm).priority
        assertEquals(InboxPriority.CRITICAL, c(incoming(title = "Prod is down, sev1 incident")))
        assertEquals(InboxPriority.HIGH, c(incoming(direct = true)))
        assertEquals(InboxPriority.HIGH, c(incoming(mention = true)))
        assertEquals(InboxPriority.NOISE, c(incoming(bot = true, title = "Build passed")))
        assertEquals(InboxPriority.NOISE, c(incoming(sender = "newsletter@vendor.com", title = "October news")))
        assertEquals(InboxPriority.NORMAL, c(incoming()))
        // A bot shouting "urgent" is still noise, not critical.
        assertEquals(InboxPriority.NOISE, c(incoming(bot = true, title = "URGENT: renew now")))
    }

    @Test fun `user rules win over defaults, first match in order`() {
        val rules = listOf(
            ClassificationRule("1", "Boss", senderContains = "priya", priority = InboxPriority.CRITICAL),
            ClassificationRule("2", "Mute random", channelContains = "random", priority = InboxPriority.NOISE),
            ClassificationRule("3", "Also Priya", senderContains = "priya", priority = InboxPriority.LOW),
        )
        val boss = NotificationClassifier.classify("slack", incoming(sender = "Priya Rao"), rules, false, tenAm)
        assertEquals(InboxPriority.CRITICAL, boss.priority)
        assertEquals("Rule: Boss", boss.reason)
        // Rule beats the "mentioned" default.
        assertEquals(InboxPriority.NOISE, NotificationClassifier.classify("slack", incoming(channel = "#random", mention = true), rules, false, tenAm).priority)
    }

    @Test fun `rule conditions - source, keywords, mention, focus, time window, disabled`() {
        val r = ClassificationRule("1", "Deploys", source = "slack", keywords = listOf("deploy", "release"),
            requireMention = true, duringFocus = false, timeWindow = TimeWindow(ClockTime.of(9), ClockTime.of(18)), priority = InboxPriority.CRITICAL)
        val i = incoming(title = "Release is blocked", mention = true)
        assertTrue(NotificationClassifier.matches(r, "slack", i, false, tenAm))
        assertFalse(NotificationClassifier.matches(r, "jira", i, false, tenAm))
        assertFalse(NotificationClassifier.matches(r, "slack", incoming(title = "Lunch?", mention = true), false, tenAm))
        assertFalse(NotificationClassifier.matches(r, "slack", incoming(title = "Release", mention = false), false, tenAm))
        assertFalse(NotificationClassifier.matches(r, "slack", i, true, tenAm))
        assertFalse(NotificationClassifier.matches(r, "slack", i, false, ClockTime.of(20)))
        val disabled = r.copy(enabled = false)
        assertEquals(InboxPriority.HIGH, NotificationClassifier.classify("slack", i, listOf(disabled), false, tenAm).priority)
    }

    @Test fun `rule validation rejects match-everything rules`() {
        assertTrue(ClassificationRule("1", "Everything", priority = InboxPriority.HIGH).validate().isNotEmpty())
        assertTrue(ClassificationRule("1", "Ok", keywords = listOf("x"), priority = InboxPriority.HIGH).validate().isEmpty())
    }

    @Test fun `user override always wins`() {
        assertEquals(InboxPriority.LOW, stored(InboxPriority.CRITICAL, user = InboxPriority.LOW).priority)
        val gate = NotificationGate.decide(stored(InboxPriority.CRITICAL, user = InboxPriority.NOISE), IntegrationSettings(), AppSettings(), t0, tenAm, emptyList())
        assertEquals(GateDecision.SILENT, gate.decision)
    }

    @Test fun `gate - high alerts, normal waits for digest, noise silent`() {
        val s = IntegrationSettings()
        val app = AppSettings()
        assertEquals(GateDecision.NOTIFY_NOW, NotificationGate.decide(stored(InboxPriority.HIGH), s, app, t0, tenAm, emptyList()).decision)
        assertEquals(GateDecision.HOLD_FOR_DIGEST, NotificationGate.decide(stored(InboxPriority.NORMAL), s, app, t0, tenAm, emptyList()).decision)
        assertEquals(GateDecision.SILENT, NotificationGate.decide(stored(InboxPriority.NOISE), s, app, t0, tenAm, emptyList()).decision)
        assertEquals(GateDecision.SILENT, NotificationGate.decide(stored(InboxPriority.NORMAL), s.copy(digestEnabled = false), app, t0, tenAm, emptyList()).decision)
    }

    @Test fun `gate - focus and quiet hours hold, critical breaks through only with urgent exceptions`() {
        val focus = IntegrationSettings(focus = FocusSession(t0.toEpochMilli() - 1000, t0.toEpochMilli() + 60_000))
        val app = AppSettings()
        assertEquals(GateDecision.HOLD_FOR_DIGEST, NotificationGate.decide(stored(InboxPriority.HIGH), focus, app, t0, tenAm, emptyList()).decision)
        assertEquals(GateDecision.NOTIFY_NOW, NotificationGate.decide(stored(InboxPriority.CRITICAL), focus, app, t0, tenAm, emptyList()).decision)
        assertEquals(GateDecision.HOLD_FOR_DIGEST,
            NotificationGate.decide(stored(InboxPriority.CRITICAL), focus.copy(urgentExceptions = false), app, t0, tenAm, emptyList()).decision)
        val night = ClockTime.of(23)
        assertEquals(GateDecision.HOLD_FOR_DIGEST, NotificationGate.decide(stored(InboxPriority.HIGH), IntegrationSettings(), app, t0, night, emptyList()).decision)
        assertEquals(GateDecision.NOTIFY_NOW, NotificationGate.decide(stored(InboxPriority.HIGH), IntegrationSettings(), app.copy(quietHoursEnabled = false), t0, night, emptyList()).decision)
    }

    @Test fun `gate - max frequency, duplicates, mute, pause, allowlist`() {
        val s = IntegrationSettings(maxAlertsPerHour = 2)
        val app = AppSettings()
        val two = listOf(AlertRecord(t0.minusSeconds(600), "a"), AlertRecord(t0.minusSeconds(300), "b"))
        assertEquals(GateDecision.HOLD_FOR_DIGEST, NotificationGate.decide(stored(InboxPriority.HIGH), s, app, t0, tenAm, two).decision)
        // Alerts older than an hour don't count.
        val old = two.map { it.copy(at = t0.minusSeconds(4000)) }
        assertEquals(GateDecision.NOTIFY_NOW, NotificationGate.decide(stored(InboxPriority.HIGH), s, app, t0, tenAm, old).decision)

        val item = stored(InboxPriority.HIGH)
        val dup = listOf(AlertRecord(t0.minusSeconds(60), NotificationGate.alertKey(item)))
        assertEquals(GateDecision.SILENT, NotificationGate.decide(item, IntegrationSettings(), app, t0, tenAm, dup).decision)

        val muted = IntegrationSettings(sources = mapOf("slack" to SourceSettings(muted = true)))
        assertEquals(GateDecision.SILENT, NotificationGate.decide(item, muted, app, t0, tenAm, emptyList()).decision)
        assertEquals(GateDecision.SILENT, NotificationGate.decide(item, IntegrationSettings(paused = true), app, t0, tenAm, emptyList()).decision)
        assertEquals(GateDecision.SILENT, NotificationGate.decide(item, IntegrationSettings(), app.copy(notificationsEnabled = false), t0, tenAm, emptyList()).decision)

        val allow = IntegrationSettings(allowSenders = listOf("asha"))
        assertEquals(GateDecision.NOTIFY_NOW, NotificationGate.decide(stored(InboxPriority.NORMAL), allow, app, t0, tenAm, emptyList()).decision)
    }

    @Test fun `digest due only for slots passed since the last digest`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val s = IntegrationSettings(digestTimes = listOf(ClockTime.of(12, 30)))
        val before = Instant.parse("2026-10-21T06:00:00Z") // 11:30 IST
        val after = Instant.parse("2026-10-21T07:05:00Z")  // 12:35 IST
        assertFalse(NotificationGate.digestDue(s.copy(lastDigestAt = before.minusSeconds(3600).toEpochMilli()), before, zone))
        assertTrue(NotificationGate.digestDue(s.copy(lastDigestAt = before.toEpochMilli()), after, zone))
        assertFalse(NotificationGate.digestDue(s.copy(lastDigestAt = after.toEpochMilli()), after.plusSeconds(60), zone))
        assertFalse(NotificationGate.digestDue(s.copy(digestEnabled = false, lastDigestAt = 0), after, zone))
    }

    @Test fun `backoff grows, caps, and honours retry-after`() {
        val base = java.time.Duration.ofMinutes(5)
        assertEquals(base, InboxService.nextDelay(base, 0, null))
        assertEquals(java.time.Duration.ofMinutes(5), InboxService.nextDelay(base, 1, SourceFailure.Offline()))
        assertEquals(java.time.Duration.ofMinutes(10), InboxService.nextDelay(base, 2, SourceFailure.Offline()))
        assertEquals(java.time.Duration.ofHours(1), InboxService.nextDelay(base, 10, SourceFailure.Server(500)))
        assertEquals(java.time.Duration.ofSeconds(90), InboxService.nextDelay(base, 1, SourceFailure.RateLimited(java.time.Duration.ofSeconds(90))))
        assertEquals(java.time.Duration.ofMinutes(30), InboxService.nextDelay(base, 1, SourceFailure.AuthFailed()))
    }
}
