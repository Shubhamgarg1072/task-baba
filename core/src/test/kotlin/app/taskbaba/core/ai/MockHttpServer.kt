package app.taskbaba.core.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Tiny scriptable HTTP server on 127.0.0.1 for exercising real HTTP clients in tests. */
class MockHttpServer : AutoCloseable {
    data class Reply(val status: Int, val body: String = "", val delayMs: Long = 0, val contentType: String = "application/json")

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val routes = java.util.concurrent.ConcurrentHashMap<String, (HttpExchange, String) -> Reply>()
    val hits = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    val lastHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, List<String>>>()
    val lastBodies = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            lastHeaders[path] = ex.requestHeaders.toMap()
            lastBodies[path] = body
            val handler = routes[path]
            val reply = handler?.invoke(ex, body) ?: Reply(404, """{"error":"not found"}""")
            try {
                if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
                val bytes = reply.body.toByteArray()
                ex.responseHeaders.add("Content-Type", reply.contentType)
                ex.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            } catch (_: Exception) {
                // client gave up (timeout); nothing to do
            } finally {
                ex.close()
            }
        }
        server.start()
    }

    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun route(path: String, handler: (HttpExchange, String) -> Reply) { routes[path] = handler }
    fun reply(path: String, reply: Reply) = route(path) { _, _ -> reply }
    fun count(path: String): Int = hits[path]?.get() ?: 0

    override fun close() = server.stop(0)

    companion object {
        fun chatReply(text: String): String =
            """{"choices":[{"index":0,"message":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(text)}}}]}"""

        /** A port with nothing listening (bind, read the port, close). */
        fun closedPort(): Int = java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
    }
}
