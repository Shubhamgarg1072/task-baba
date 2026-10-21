package app.taskbaba.core.integrations

import app.taskbaba.core.data.DatabaseFactory
import app.taskbaba.core.data.KvStore
import app.taskbaba.core.model.AppSettings
import app.taskbaba.core.model.ClockTime
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.Task
import app.taskbaba.core.model.TaskDraft
import app.taskbaba.core.platform.NotificationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InboxServiceTest {
    private val db = DatabaseFactory.inMemory()
    private val kv = KvStore(db)
    private val settings = kv.state("integrations", IntegrationSettings.serializer()) { IntegrationSettings() }
    private val clock = TestClock(Instant.parse("2026-10-21T05:00:00Z")) // 10:30 IST, outside quiet hours
    private val notifier = FakeNotifier()
    private val created = mutableListOf<TaskDraft>()
    private val scope = CoroutineScope(SupervisorJob())
    private var app = AppSettings()
    private val opened = mutableListOf<String>()
    private val source = FakeSource()

    private val creator = FollowUpTaskCreator { draft ->
        created += draft
        Result.success(Task(id = "task-${created.size}", title = draft.title, createdAt = clock.now, priority = draft.priority))
    }

    private val service = InboxService(db, settings, listOf(source), notifier, creator, { app }, scope, { opened += it }, clock)

    @AfterTest fun tearDown() = scope.cancel()

    private fun item(id: String, title: String = "Message $id", direct: Boolean = false, at: Instant = clock.now.minusSeconds(60), url: String? = "https://x/$id") =
        IncomingItem(id, ItemKind.MESSAGE, "Ravi", "#eng", title, "", url, at, isDirect = direct)

    private fun rows() = db.inboxQueries.selectAllItems().executeAsList().map { it.toModel() }

    @Test fun `duplicates are stored once across polls`() = runTest {
        source.items = listOf(item("a"), item("b"))
        assertEquals(2, service.pollOnce(source).stored)
        source.items = listOf(item("a"), item("b"), item("c"))
        assertEquals(1, service.pollOnce(source).stored)
        assertEquals(3, rows().size)
        // Same batch containing the same item twice.
        assertEquals(1, service.ingest("fake", listOf(item("d"), item("d"))))
    }

    @Test fun `high priority alerts with only supported actions, normal is held then digested`() = runTest {
        service.ingest("fake", listOf(item("dm", direct = true), item("n1"), item("n2", url = null)))
        assertEquals(1, notifier.posted.size)
        val alert = notifier.posted.single()
        assertEquals(listOf(InboxService.ACTION_REVIEW, InboxService.ACTION_SNOOZE, InboxService.ACTION_OPEN), alert.actions.map { it.id })
        assertEquals(2, rows().count { it.delivery == Delivery.HELD })

        assertEquals(2, service.sendDigest())
        assertTrue(notifier.posted.last().title.contains("digest"))
        assertEquals(0, rows().count { it.delivery == Delivery.HELD })
        assertEquals(0, service.sendDigest(), "nothing left to digest")
    }

    @Test fun `alerts respect preview privacy setting`() = runTest {
        settings.update { it.copy(previewInAlerts = false) }
        service.ingest("fake", listOf(item("dm", title = "Salary numbers for Q4", direct = true)))
        val alert = notifier.posted.single()
        assertTrue("Salary" !in alert.title + alert.body)
    }

    @Test fun `notification permission denied keeps the item held instead of losing it`() = runTest {
        notifier.fail = true
        service.ingest("fake", listOf(item("dm", direct = true)))
        assertEquals(Delivery.HELD, rows().single().delivery)
    }

    @Test fun `failed poll stores nothing and keeps the cursor`() = runTest {
        source.failure = SourceFailure.Offline()
        val out = service.pollOnce(source)
        assertIs<SourceFailure.Offline>(out.failure)
        assertEquals(0, rows().size)
        assertTrue("fake" !in settings.value.lastPollAt)
        source.failure = null
        source.items = listOf(item("a"))
        assertEquals(1, service.pollOnce(source).stored)
        assertEquals(clock.now.toEpochMilli(), settings.value.lastPollAt["fake"])
    }

    @Test fun `follow-up task is only created on explicit action`() = runTest {
        service.ingest("fake", listOf(item("a", title = "Review the PR", direct = true)))
        assertTrue(created.isEmpty(), "no automatic task creation")
        val id = rows().single().id
        val task = service.createFollowUpTask(id, Priority.HIGH, LocalDate.of(2026, 10, 22), null).getOrThrow()
        assertEquals("Follow up: Review the PR", created.single().title)
        assertEquals(Priority.HIGH, created.single().priority)
        val row = rows().single()
        assertEquals(task.id, row.linkedTaskId)
        assertEquals(InboxStatus.REVIEWED, row.status)
    }

    @Test fun `explicit user rule may auto-create tasks`() = runTest {
        settings.update {
            it.copy(rules = listOf(ClassificationRule("r", "Incidents to tasks", keywords = listOf("incident"), priority = InboxPriority.CRITICAL, autoCreateTask = true)))
        }
        service.ingest("fake", listOf(item("a", title = "New incident opened")))
        assertEquals(Priority.CRITICAL, created.single().priority)
        assertNotNull(rows().single().linkedTaskId)
    }

    @Test fun `retention purge deletes old items`() = runTest {
        service.ingest("fake", listOf(item("new"), item("old", at = clock.now.minus(Duration.ofDays(3)))))
        assertEquals(2, rows().size)
        settings.update { it.copy(retentionDays = 2) }
        service.purge()
        assertEquals(listOf("new"), rows().map { it.externalId })
        // Items already past retention are never stored.
        assertEquals(0, service.ingest("fake", listOf(item("ancient", at = clock.now.minus(Duration.ofDays(10))))))
    }

    @Test fun `triage actions, snooze wake-up, and notification responses`() = runTest {
        service.ingest("fake", listOf(item("a", direct = true), item("b"), item("c")))
        val (a, b, c) = rows().sortedBy { it.externalId }
        assertEquals(InboxResponseOutcome.HANDLED,
            service.handleResponse(NotificationResponse("inbox-${a.id}", InboxService.ACTION_REVIEW, mapOf("kind" to "inbox", "inboxItemId" to a.id))))
        assertEquals(InboxStatus.REVIEWED, rows().first { it.id == a.id }.status)

        service.handleResponse(NotificationResponse("x", InboxService.ACTION_OPEN, mapOf("kind" to "inbox", "inboxItemId" to b.id)))
        assertEquals(listOf("https://x/b"), opened)
        assertEquals(InboxResponseOutcome.NOT_INBOX, service.handleResponse(NotificationResponse("t", "done", mapOf("kind" to "task"))))

        service.snooze(c.id, Duration.ofMinutes(30))
        assertEquals(InboxStatus.SNOOZED, rows().first { it.id == c.id }.status)
        clock.now = clock.now.plus(Duration.ofMinutes(31))
        service.housekeeping()
        assertEquals(InboxStatus.NEW, rows().first { it.id == c.id }.status)

        service.setUserPriority(b.id, InboxPriority.CRITICAL)
        assertEquals(InboxPriority.CRITICAL, rows().first { it.id == b.id }.priority)
        service.dismiss(b.id)
        assertEquals(InboxStatus.DISMISSED, rows().first { it.id == b.id }.status)
    }

    @Test fun `focus holds alerts and ending focus sends a digest`() = runTest {
        service.startFocus(25)
        service.ingest("fake", listOf(item("dm", direct = true)))
        assertTrue(notifier.posted.isEmpty())
        service.stopFocus()
        assertEquals(1, notifier.posted.size)
        assertTrue(notifier.posted.single().title.contains("digest"))
    }

    @Test fun `expired focus session ends in housekeeping`() = runTest {
        service.startFocus(5)
        service.ingest("fake", listOf(item("dm", direct = true)))
        clock.now = clock.now.plus(Duration.ofMinutes(6))
        service.housekeeping()
        assertEquals(null, settings.value.focus)
        assertEquals(1, notifier.posted.size)
    }

    @Test fun `quiet hours hold alerts`() = runTest {
        clock.now = Instant.parse("2026-10-21T17:30:00Z") // 23:00 IST
        service.ingest("fake", listOf(item("dm", direct = true)))
        assertTrue(notifier.posted.isEmpty())
        assertEquals(Delivery.HELD, rows().single().delivery)
        app = app.copy(quietHoursEnabled = false, quietHours = app.quietHours)
        assertEquals(ClockTime.of(22), app.quietHours.start)
    }

    @Test fun `delete per source and delete all`() = runTest {
        service.ingest("fake", listOf(item("a")))
        service.ingest("other", listOf(item("b")))
        service.deleteSource("fake")
        assertEquals(listOf("other"), rows().map { it.source })
        service.deleteAll()
        assertTrue(rows().isEmpty())
    }
}
