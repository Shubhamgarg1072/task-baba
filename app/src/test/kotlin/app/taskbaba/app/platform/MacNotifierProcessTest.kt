package app.taskbaba.app.platform

import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.platform.NotificationKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Drives MacNotifier against a scripted fake of `babakit serve` to exercise the real process plumbing. */
class MacNotifierProcessTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: Path = Files.createTempDirectory("babakit-fake")

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private fun fakeHelper(body: String): Path {
        val script = dir.resolve("babakit")
        Files.writeString(
            script,
            """
            #!/bin/sh
            echo '{"event":"ready","bundled":true,"bundleId":"app.taskbaba.TaskBaba"}'
            while IFS= read -r line; do
              rid=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"rid":\([0-9]*\).*/\1/p')
              $body
            done
            """.trimIndent() + "\n",
        )
        script.toFile().setExecutable(true)
        return script
    }

    @Test
    fun `correlates replies, maps notAllowed and forwards responses`() = runBlocking {
        val helper = fakeHelper(
            """
            case "${'$'}line" in
              *authStatus*) echo "{\"ok\":true,\"rid\":${'$'}rid,\"status\":\"denied\"}" ;;
              *'"op":"post"'*)
                echo "{\"error\":\"notAllowed\",\"ok\":false,\"rid\":${'$'}rid}"
                echo '{"action":"done","event":"response","id":"n1","userInfo":{"taskId":"42"}}' ;;
              *) echo "{\"ok\":true,\"rid\":${'$'}rid}" ;;
            esac
            """.trimIndent(),
        )
        val notifier = MacNotifier(helper, scope, replyTimeoutMs = 3_000)
        try {
            assertEquals(NotificationAuth.DENIED, notifier.authorization())
            val response = scope.async { notifier.responses.first() }
            val result = notifier.post(BabaNotification("n1", NotificationKind.TASK, "Title", "Body"))
            assertIs<NotificationsNotAllowedException>(result.exceptionOrNull())
            val r = withTimeout(3_000) { response.await() }
            assertEquals("done", r.actionId)
            assertEquals("42", r.userInfo["taskId"])
        } finally {
            notifier.close()
        }
    }

    @Test
    fun `a silent helper times out instead of hanging`() = runBlocking {
        val helper = fakeHelper(": # never replies")
        val notifier = MacNotifier(helper, scope, replyTimeoutMs = 300)
        try {
            assertEquals(NotificationAuth.UNAVAILABLE, notifier.authorization())
            assertTrue(notifier.post(BabaNotification("x", NotificationKind.TEST, "T", "B")).isFailure)
        } finally {
            notifier.close()
        }
    }

    @Test
    fun `restarts the helper after it exits`() = runBlocking {
        // Replies once, then exits; the next call must spawn a fresh process.
        val helper = fakeHelper(
            """echo "{\"ok\":true,\"rid\":${'$'}rid,\"status\":\"authorized\"}"; exit 0""",
        )
        val notifier = MacNotifier(helper, scope, replyTimeoutMs = 2_000)
        try {
            assertEquals(NotificationAuth.AUTHORIZED, notifier.authorization())
            Thread.sleep(3_200) // restart back-off is 3 s
            assertEquals(NotificationAuth.AUTHORIZED, notifier.authorization())
        } finally {
            notifier.close()
        }
    }
}
