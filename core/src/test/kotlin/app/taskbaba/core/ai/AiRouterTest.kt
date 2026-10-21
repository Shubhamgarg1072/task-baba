package app.taskbaba.core.ai

import app.taskbaba.core.model.AiMode
import app.taskbaba.core.model.Personality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiRouterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockHttpServer()
    private val client = CloudInferenceClient(retryDelayMs = 10)

    @AfterTest fun tearDown() {
        scope.cancel()
        server.close()
    }

    private val request = MonkAiRequest(
        event = "task_overdue",
        personality = Personality.SAVAGE,
        sarcasmAllowed = true,
        fallback = "Your deadline has left the chat.",
        context = mapOf("priority" to "High"),
    )

    private fun settings(endpoint: String = server.baseUrl, mode: AiMode = AiMode.AUTOMATIC, consent: Boolean = true, timeout: Int = 2) =
        MutableStateFlow(AiSettings(mode = mode, endpoint = endpoint, cloudConsentGiven = consent, timeoutSeconds = timeout))

    private fun router(
        s: MutableStateFlow<AiSettings>,
        budgetMs: ((AiSettings) -> Long)? = null,
    ): Pair<AiRouter, EndpointHealthMonitor> {
        val monitor = EndpointHealthMonitor(
            scope = scope,
            config = { s.value.normalized().let { n -> if (n.effectiveMode() == AiMode.BUILT_IN) null else EndpointConfig(n.endpoint, "test-key-123") } },
            probe = { client.health(it, Duration.ofSeconds(2)) },
        )
        val r = if (budgetMs != null) AiRouter(s, { "test-key-123" }, client, monitor, scope, budgetMs = budgetMs)
        else AiRouter(s, { "test-key-123" }, client, monitor, scope)
        return r to monitor
    }

    private fun healthyServer(text: String = "Karma noted your deadline, my child. Now go finish it.") {
        server.reply("/health", MockHttpServer.Reply(200, """{"status":"ok"}"""))
        server.reply("/v1/chat/completions", MockHttpServer.Reply(200, MockHttpServer.chatReply(text)))
    }

    @Test fun `cloud healthy returns validated model text and sends bearer key`() = runBlocking<Unit> {
        healthyServer()
        val (r, monitor) = router(settings())
        assertIs<EndpointHealth.Healthy>(monitor.checkNow())
        assertEquals("Karma noted your deadline, my child. Now go finish it.", r.rewrite(request))
        assertEquals(listOf("Bearer test-key-123"), server.lastHeaders["/v1/chat/completions"]?.get("Authorization"))
        delay(50)
        assertTrue(r.status.value.healthy)
        assertEquals(AiRoute.CLOUD, r.status.value.route)
    }

    @Test fun `connection refused falls back to built-in`() = runBlocking<Unit> {
        val (r, monitor) = router(settings(endpoint = "http://127.0.0.1:${MockHttpServer.closedPort()}"))
        val h = monitor.checkNow()
        assertIs<EndpointHealth.Down>(h)
        assertEquals(FailureKind.UNREACHABLE, h.kind)
        assertNull(r.rewrite(request))
    }

    @Test fun `unresolvable host is reported as unreachable`() = runBlocking<Unit> {
        val h = client.health(EndpointConfig("https://task-baba-test.invalid", null), Duration.ofSeconds(3))
        assertIs<HealthResult.Unhealthy>(h)
        assertEquals(FailureKind.UNREACHABLE, h.kind)
    }

    @Test fun `internet ok but cloud down returns null`() = runBlocking<Unit> {
        server.reply("/health", MockHttpServer.Reply(502))
        server.reply("/v1/chat/completions", MockHttpServer.Reply(502))
        val (r, monitor) = router(settings())
        assertIs<EndpointHealth.Down>(monitor.checkNow())
        assertNull(r.rewrite(request))
        assertEquals(0, server.count("/v1/chat/completions"), "router must not call a known-down endpoint")
    }

    @Test fun `server error mid-session is retried once then falls back`() = runBlocking<Unit> {
        server.reply("/health", MockHttpServer.Reply(200))
        server.reply("/v1/chat/completions", MockHttpServer.Reply(500))
        val (r, monitor) = router(settings())
        monitor.checkNow()
        assertNull(r.rewrite(request))
        assertEquals(2, server.count("/v1/chat/completions"))
        assertIs<EndpointHealth.Down>(monitor.state.value)
    }

    @Test fun `cloud timeout returns null within budget`() = runBlocking<Unit> {
        server.reply("/health", MockHttpServer.Reply(200))
        server.reply("/v1/chat/completions", MockHttpServer.Reply(200, MockHttpServer.chatReply("late"), delayMs = 5_000))
        val (r, monitor) = router(settings(timeout = 2))
        monitor.checkNow()
        val started = System.currentTimeMillis()
        assertNull(r.rewrite(request))
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 4_500, "took $elapsed ms")
        val down = monitor.state.value
        assertIs<EndpointHealth.Down>(down)
        assertEquals(FailureKind.TIMEOUT, down.kind)
        assertEquals(1, server.count("/v1/chat/completions"), "timeouts are not retried")
    }

    @Test fun `health reminder due during slow inference is not delayed`() = runBlocking<Unit> {
        server.reply("/health", MockHttpServer.Reply(200))
        server.reply("/v1/chat/completions", MockHttpServer.Reply(200, MockHttpServer.chatReply("slow"), delayMs = 4_000))
        val (r, monitor) = router(settings(timeout = 10), budgetMs = { 500 })
        monitor.checkNow()
        val started = System.currentTimeMillis()
        val reminderFiredAt = async {
            delay(100) // a reminder scheduled 100 ms from now
            System.currentTimeMillis() - started
        }
        val line = async { r.rewrite(request) }
        assertTrue(reminderFiredAt.await() < 400, "reminder was delayed")
        assertNull(line.await())
        assertTrue(System.currentTimeMillis() - started < 1_500, "router exceeded its budget")
    }

    @Test fun `all routes failing yields null so the caller keeps its built-in line`() = runBlocking<Unit> {
        val (r, _) = router(settings(endpoint = "http://127.0.0.1:${MockHttpServer.closedPort()}"))
        // Health never confirmed, so nothing is attempted and the built-in line wins immediately.
        assertNull(r.rewrite(request))
    }

    @Test fun `connectivity returning switches back to cloud`() = runBlocking<Unit> {
        server.reply("/health", MockHttpServer.Reply(503))
        server.reply("/v1/chat/completions", MockHttpServer.Reply(200, MockHttpServer.chatReply("Welcome back, wanderer.")))
        val (r, monitor) = router(settings())
        assertIs<EndpointHealth.Down>(monitor.checkNow())
        assertNull(r.rewrite(request))
        server.reply("/health", MockHttpServer.Reply(200))
        assertIs<EndpointHealth.Healthy>(monitor.checkNow())
        assertEquals("Welcome back, wanderer.", r.rewrite(request))
    }

    @Test fun `built-in mode never touches the network`() = runBlocking<Unit> {
        healthyServer()
        val s = settings(mode = AiMode.BUILT_IN)
        val (r, monitor) = router(s)
        monitor.start()
        assertNull(r.rewrite(request))
        delay(200)
        assertEquals(EndpointHealth.NotConfigured, monitor.state.value)
        assertEquals(0, server.hits.values.sumOf { it.get() })
        assertEquals("Built-in only", r.status.value.label)
    }

    @Test fun `cloud mode without consent never touches the network`() = runBlocking<Unit> {
        healthyServer()
        val (r, monitor) = router(settings(consent = false))
        monitor.start()
        assertNull(r.rewrite(request))
        delay(200)
        assertEquals(0, server.hits.values.sumOf { it.get() })
    }

    @Test fun `invalid model output is rejected`() = runBlocking<Unit> {
        healthyServer("<think>let me plan the joke")
        val (r, monitor) = router(settings())
        monitor.checkNow()
        assertNull(r.rewrite(request))
        assertIs<EndpointHealth.Healthy>(monitor.state.value, "a bad reply is not an outage")
    }

    @Test fun `identical concurrent requests are deduplicated`() = runBlocking<Unit> {
        server.reply("/health", MockHttpServer.Reply(200))
        server.reply("/v1/chat/completions", MockHttpServer.Reply(200, MockHttpServer.chatReply("Patience, my child."), delayMs = 300))
        val (r, monitor) = router(settings())
        monitor.checkNow()
        val results = (1..5).map { async { r.rewrite(request) } }.awaitAll()
        assertTrue(results.all { it == "Patience, my child." })
        assertEquals(1, server.count("/v1/chat/completions"))
    }

    @Test fun `unauthorized key is surfaced in status`() = runBlocking<Unit> {
        server.reply("/health", MockHttpServer.Reply(401))
        val (r, monitor) = router(settings())
        monitor.checkNow()
        delay(50)
        assertTrue(r.status.value.label.contains("API key rejected"), r.status.value.label)
    }

    @Test fun `test inference reports real outcome`() = runBlocking<Unit> {
        healthyServer("Visit https://example.com for enlightenment")
        val (r, _) = router(settings(mode = AiMode.BUILT_IN))
        val result = r.test(request)
        assertIs<InferenceResult.Failure>(result)
        assertTrue(result.message.contains("rejected"))
    }
}
