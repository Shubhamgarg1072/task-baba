package app.taskbaba.app.platform

import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.platform.NotificationResponse
import app.taskbaba.core.platform.Notifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Native notifications through the `babakit serve` helper (UserNotifications, with action buttons).
 *
 * The helper is spawned on first use and restarted if it dies. Without a helper (dev runs) notifications
 * fall back to `osascript` banners without actions and [authorization] reports UNAVAILABLE.
 * Titles and bodies are never logged.
 */
class MacNotifier(
    private val helper: Path? = BridgeLocator.locate(),
    private val scope: CoroutineScope,
    private val fallback: OsascriptNotifier = OsascriptNotifier(),
    private val replyTimeoutMs: Long = 5_000,
    /** Permission prompts wait on the user. */
    private val authTimeoutMs: Long = 120_000,
) : Notifier {
    private val log = Logger.getLogger("TaskBaba.MacNotifier")
    private val lifecycle = Mutex()
    private val nextRid = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<BridgeMessage.Reply>>()
    private val _responses = MutableSharedFlow<NotificationResponse>(extraBufferCapacity = 64)
    override val responses: SharedFlow<NotificationResponse> = _responses.asSharedFlow()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var lastStart = 0L
    @Volatile private var bundled = true

    /** True when the native helper is present; false means banners use the osascript fallback. */
    val isNative: Boolean get() = helper != null && bundled

    override suspend fun authorization(): NotificationAuth {
        if (helper == null) return NotificationAuth.UNAVAILABLE
        val reply = call(replyTimeoutMs) { BridgeProtocol.authStatus(it) } ?: return NotificationAuth.UNAVAILABLE
        return BridgeProtocol.authFrom(reply.status)
    }

    override suspend fun requestAuthorization(): NotificationAuth {
        if (helper == null) return NotificationAuth.UNAVAILABLE
        val reply = call(authTimeoutMs) { BridgeProtocol.requestAuth(it) } ?: return authorization()
        val status = BridgeProtocol.authFrom(reply.status)
        // macOS answered "not allowed" without ever showing the prompt.
        if (!reply.ok && status == NotificationAuth.NOT_DETERMINED && BridgeProtocol.isNotAllowed(reply.error)) return NotificationAuth.REFUSED
        return status
    }

    override suspend fun post(notification: BabaNotification): Result<Unit> {
        if (helper == null || !bundled) return fallback.post(notification)
        val reply = call(replyTimeoutMs) { BridgeProtocol.post(it, notification) }
            ?: return Result.failure(IllegalStateException("The notification helper did not respond."))
        return when {
            reply.ok -> Result.success(Unit)
            reply.status == "unavailable" -> { bundled = false; fallback.post(notification) }
            // Native banners refused (turned off, or permission not granted to this build): still show a
            // simple banner so the reminder isn't lost. Buttons are only available natively.
            BridgeProtocol.isNotAllowed(reply.error) -> fallback.post(notification)
            else -> Result.failure(IllegalStateException("Notification failed: ${reply.error ?: "unknown error"}"))
        }
    }

    override suspend fun remove(id: String) {
        if (helper == null || !bundled) return
        call(replyTimeoutMs) { BridgeProtocol.remove(it, id) }
    }

    /** Stops the helper (closing stdin makes it exit). */
    fun close() {
        runCatching { writer?.close() }
        process?.let { p -> if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroy() }
        process = null
        writer = null
    }

    private suspend fun call(timeoutMs: Long, encode: (Long) -> String): BridgeMessage.Reply? {
        val w = ensureRunning() ?: return null
        val rid = nextRid.getAndIncrement()
        val deferred = CompletableDeferred<BridgeMessage.Reply>()
        pending[rid] = deferred
        try {
            val sent = withContext(Dispatchers.IO) {
                runCatching { synchronized(w) { w.write(encode(rid)); w.newLine(); w.flush() } }.isSuccess
            }
            if (!sent) return null
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(rid)
        }
    }

    private suspend fun ensureRunning(): BufferedWriter? = lifecycle.withLock {
        val current = process
        if (current != null && current.isAlive) return writer
        val path = helper ?: return null
        // Avoid a tight restart loop if the helper keeps crashing.
        val now = System.currentTimeMillis()
        if (current != null && now - lastStart < 3_000) return null
        lastStart = now
        withContext(Dispatchers.IO) {
            runCatching {
                val p = ProcessBuilder(path.toString(), "serve").redirectError(ProcessBuilder.Redirect.DISCARD).start()
                process = p
                writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
                scope.launch(Dispatchers.IO) { readLoop(p) }
                writer
            }.onFailure { log.warning("Could not start notification helper: ${it.javaClass.simpleName}") }.getOrNull()
        }
    }

    private suspend fun readLoop(p: Process) {
        runCatching {
            p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    when (val msg = BridgeProtocol.decode(line)) {
                        is BridgeMessage.Reply -> pending[msg.rid]?.complete(msg)
                        is BridgeMessage.Response -> _responses.emit(msg.response)
                        is BridgeMessage.Ready -> bundled = msg.bundled
                        is BridgeMessage.Unknown -> log.fine("Ignoring unrecognised helper output")
                    }
                }
            }
        }
        // Helper exited: fail outstanding calls fast; the next call restarts it.
        pending.values.forEach { it.cancel() }
    }
}

class NotificationsNotAllowedException(message: String) : IllegalStateException(message)
