package app.taskbaba.core.integrations

import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.ClockProvider
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.platform.NotificationResponse
import app.taskbaba.core.platform.Notifier
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

class FakeNotifier(var fail: Boolean = false) : Notifier {
    val posted = CopyOnWriteArrayList<BabaNotification>()
    val removed = CopyOnWriteArrayList<String>()
    override suspend fun authorization() = NotificationAuth.AUTHORIZED
    override suspend fun requestAuthorization() = NotificationAuth.AUTHORIZED
    override suspend fun post(notification: BabaNotification): Result<Unit> =
        if (fail) Result.failure(IllegalStateException("denied")) else Result.success(Unit).also { posted += notification }
    override suspend fun remove(id: String) { removed += id }
    override val responses: Flow<NotificationResponse> = MutableSharedFlow()
}

/** Mutable clock for deterministic time in tests. */
class TestClock(var now: Instant, val zone: ZoneId = ZoneId.of("Asia/Kolkata")) : ClockProvider {
    override fun clock(): Clock = Clock.fixed(now, zone)
}

data class Recorded(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)

/** Minimal local HTTP server; each handler returns (status, body, extra headers). */
class MockServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<Recorded>()
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    fun route(path: String, handler: (Recorded) -> Triple<Int, String, Map<String, String>>) {
        server.createContext(path) { ex: HttpExchange ->
            val rec = Recorded(
                ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery,
                ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                ex.requestBody.readBytes().decodeToString(),
            )
            requests += rec
            val (code, body, headers) = handler(rec)
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
    }

    fun start() = apply { server.start() }
    override fun close() = server.stop(0)
}

fun ok(body: String) = Triple(200, body, emptyMap<String, String>())

/** In-memory config access for adapter tests. */
class MapConfig : SourceConfigAccess {
    val map = mutableMapOf<String, Map<String, String>>()
    override fun read(sourceId: String) = map[sourceId].orEmpty()
    override fun write(sourceId: String, values: Map<String, String>?) {
        if (values == null) map.remove(sourceId) else map[sourceId] = values
    }
}

/** A controllable adapter for InboxService tests. */
class FakeSource(override val id: String = "fake", var items: List<IncomingItem> = emptyList(), var failure: SourceFailure? = null) : NotificationSource {
    override val displayName = "Fake"
    override val description = ""
    override val setupHelp = ""
    override val capabilities = SourceCapabilities(canOpenOriginal = true)
    override val configFields = emptyList<ConfigField>()
    override val status = kotlinx.coroutines.flow.MutableStateFlow(SourceStatus(SourceState.CONNECTED, "ok"))
    var polls = 0
    override fun isConfigured() = true
    override fun currentConfig() = emptyMap<String, String>()
    override suspend fun configure(values: Map<String, String>) = Result.success(Unit)
    override suspend fun disconnect() {}
    override suspend fun poll(since: Instant): Result<List<IncomingItem>> {
        polls++
        return failure?.let { Result.failure(it) } ?: Result.success(items)
    }
}
