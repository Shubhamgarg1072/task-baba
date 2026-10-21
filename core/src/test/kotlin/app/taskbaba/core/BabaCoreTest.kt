package app.taskbaba.core

import app.taskbaba.core.data.DatabaseFactory
import app.taskbaba.core.model.ClockTime
import app.taskbaba.core.model.HealthOutcome
import app.taskbaba.core.model.HealthType
import app.taskbaba.core.model.Personality
import app.taskbaba.core.model.TaskDraft
import app.taskbaba.core.model.TimeWindow
import app.taskbaba.core.model.Weekday
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.platform.NotificationKind
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BabaCoreTest {
    private fun core(clock: MutableClock, notifier: FakeNotifier = FakeNotifier(), db: app.taskbaba.db.TaskBabaDatabase = DatabaseFactory.inMemory()) =
        BabaCore(db, notifier, clock).apply {
            // Only water, every 60 min, all day, no quiet hours: keeps tests readable.
            updateSettings { s ->
                s.copy(quietHoursEnabled = false, health = s.health.map {
                    if (it.type == HealthType.WATER) it.copy(enabled = true, intervalMinutes = 60, activeHours = TimeWindow(ClockTime.of(8), ClockTime.of(20)), weekdays = Weekday.ALL)
                    else it.copy(enabled = false)
                })
            }
        }

    @Test fun healthReminderFiresOnceAndRecordsDeliveryNotCompletion() = runTest {
        val clock = MutableClock.at("2026-10-21T10:00:00")
        val n = FakeNotifier()
        val c = core(clock, n)
        c.tick()
        assertTrue(n.posted.isEmpty())
        clock.advance(Duration.ofMinutes(61)); c.tick(); c.tick()
        assertEquals(1, n.posted.count { it.kind == NotificationKind.HEALTH })
        val ev = c.healthRepo.forDate(c.today()).single()
        assertEquals(HealthOutcome.DELIVERED, ev.outcome)
        c.respondHealth(ev.id, HealthOutcome.DONE)
        assertEquals(HealthOutcome.DONE, c.healthRepo.byId(ev.id)!!.outcome)
        assertEquals(1, c.todayRecord().healthDone)
    }

    @Test fun pausedRemindersAreDeferredNotDelivered() = runTest {
        val clock = MutableClock.at("2026-10-21T10:00:00")
        val n = FakeNotifier()
        val c = core(clock, n)
        c.tick()
        c.pauseAll(Duration.ofHours(3))
        clock.advance(Duration.ofMinutes(61)); c.tick()
        assertTrue(n.posted.isEmpty())
        assertTrue(c.healthRepo.forDate(c.today()).isEmpty())
        clock.advance(Duration.ofMinutes(110)); c.tick() // 12:51, still paused
        assertTrue(n.posted.isEmpty())
        clock.advance(Duration.ofMinutes(10)); c.tick() // 13:01, pause over
        assertEquals(1, n.posted.size)
    }

    @Test fun staleRemindersAfterSleepDoNotBurst() = runTest {
        val clock = MutableClock.at("2026-10-21T10:00:00")
        val n = FakeNotifier()
        val c = core(clock, n)
        c.tick()
        clock.advance(Duration.ofHours(5)); c.tick()
        assertTrue(n.posted.isEmpty(), "a reminder missed by hours should be rescheduled, not fired")
        assertTrue(c.upcomingReminders.value.single().at.isAfter(clock.instant))
    }

    @Test fun permissionDeniedStillWorksInAppWithoutCrashing() = runTest {
        val clock = MutableClock.at("2026-10-21T10:00:00")
        val n = FakeNotifier(auth = NotificationAuth.DENIED)
        val c = core(clock, n)
        c.tick(); clock.advance(Duration.ofMinutes(61)); c.tick()
        assertTrue(n.posted.isEmpty())
        assertEquals(1, c.pendingHealth().size, "reminder is still visible in the app")
    }

    @Test fun quietHoursAndRateLimitRespected() = runTest {
        val clock = MutableClock.at("2026-10-21T21:00:00")
        val n = FakeNotifier()
        val c = core(clock, n)
        c.updateSettings { it.copy(quietHoursEnabled = true, quietHours = TimeWindow(ClockTime.of(21), ClockTime.of(7))) }
        c.tasks.create(TaskDraft("Late thing", dueDate = LocalDate.parse("2026-10-21"), dueTime = java.time.LocalTime.of(21, 5))).getOrThrow()
        clock.advance(Duration.ofMinutes(10)); c.tick()
        assertTrue(n.posted.isEmpty())
        // Quiet hours end: the overdue alert is delivered once.
        clock.instant = java.time.LocalDateTime.parse("2026-10-22T07:30:00").atZone(clock.zone).toInstant(); c.tick(); c.tick()
        assertEquals(1, n.posted.count { it.kind == NotificationKind.TASK })
    }

    @Test fun overdueTaskNotifiedOnceAcrossRestart() = runTest {
        val dir = Files.createTempDirectory("baba")
        val file = dir.resolve("t.db")
        val clock = MutableClock.at("2026-10-21T10:00:00")
        val n1 = FakeNotifier()
        val c1 = core(clock, n1, DatabaseFactory.file(file))
        c1.tasks.create(TaskDraft("Invoice", dueDate = LocalDate.parse("2026-10-21"), dueTime = java.time.LocalTime.of(10, 30))).getOrThrow()
        clock.advance(Duration.ofMinutes(45)); c1.tick()
        assertEquals(1, n1.posted.count { it.title.startsWith("Overdue") })

        val n2 = FakeNotifier()
        val c2 = BabaCore(DatabaseFactory.file(file), n2, clock)
        c2.tick()
        assertTrue(n2.posted.none { it.title.startsWith("Overdue") })
        assertEquals("Invoice", c2.tasks.all().single().title, "tasks persist across restart")
        assertEquals(Personality.ADAPTIVE, c2.settings.value.personality)
    }

    @Test fun rolloverFreezesYesterdayWithoutDeletingTasks() = runTest {
        val clock = MutableClock.at("2026-10-21T10:00:00")
        val c = core(clock)
        val t = c.tasks.create(TaskDraft("Ship it", dueDate = LocalDate.parse("2026-10-21"))).getOrThrow()
        c.tasks.complete(t.id)
        c.tick()
        clock.advance(Duration.ofDays(1)); c.tick()
        val rec = c.dayRepo.byDate(LocalDate.parse("2026-10-21"))!!
        assertEquals(1, rec.planned); assertEquals(1, rec.completed); assertTrue(rec.goalMet)
        assertTrue(rec.summary.isNotBlank())
        assertEquals(1, c.tasks.all().size)
    }

    @Test fun settingsPersistAndValidate() {
        val db = DatabaseFactory.inMemory()
        val c = BabaCore(db, FakeNotifier(), MutableClock.at("2026-10-21T10:00:00"))
        c.updateSettings { it.copy(personality = Personality.SAVAGE, sarcasmEnabled = false, maxNotificationsPerHour = 3) }
        val reloaded = BabaCore(db, FakeNotifier(), MutableClock.at("2026-10-21T10:00:00")).settings.value
        assertEquals(Personality.SAVAGE, reloaded.personality)
        assertEquals(false, reloaded.sarcasmEnabled)
        assertTrue(reloaded.copy(maxNotificationsPerHour = 0).validate().isNotEmpty())
    }
}
