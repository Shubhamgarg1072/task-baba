package app.taskbaba.core.integrations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionException

enum class SourceState { NOT_CONFIGURED, CONNECTING, CONNECTED, AUTH_FAILED, PERMISSION_DENIED, OFFLINE, ERROR, UNSUPPORTED }

data class SourceStatus(val state: SourceState, val message: String, val checkedAt: Instant? = null) {
    val isProblem: Boolean get() = state in setOf(SourceState.AUTH_FAILED, SourceState.PERMISSION_DENIED, SourceState.OFFLINE, SourceState.ERROR)
}

/** What a source can truly do. The UI only offers actions that are backed by a real capability. */
data class SourceCapabilities(
    val canOpenOriginal: Boolean = false,
    /** Marking the item read in the source app itself. No adapter implements this in 1.0. */
    val supportsMarkReadRemotely: Boolean = false,
    val usesDeviceCodeLogin: Boolean = false,
)

data class ConfigField(
    val key: String,
    val label: String,
    val placeholder: String = "",
    val secret: Boolean = false,
    val optional: Boolean = false,
)

/** Failures every adapter maps its transport and API errors onto. */
sealed class SourceFailure(message: String) : Exception(message) {
    class NotConfigured(message: String = "Not connected yet.") : SourceFailure(message)
    class Offline(message: String = "Can't reach the service. Check your internet connection.") : SourceFailure(message)
    class AuthFailed(message: String = "Sign-in was rejected. Reconnect with a fresh token.") : SourceFailure(message)
    class PermissionDenied(message: String = "The account is missing a required permission (scope).") : SourceFailure(message)
    class RateLimited(val retryAfter: Duration) : SourceFailure("Rate limited by the service; retrying in ${retryAfter.seconds}s.")
    class Server(val code: Int) : SourceFailure("The service had a problem (HTTP $code). Baba will retry.")
    class Protocol(message: String) : SourceFailure(message)
    class Unsupported(message: String) : SourceFailure(message)
}

fun SourceFailure.toStatus(at: Instant): SourceStatus = when (this) {
    is SourceFailure.NotConfigured -> SourceStatus(SourceState.NOT_CONFIGURED, message ?: "", at)
    is SourceFailure.Offline -> SourceStatus(SourceState.OFFLINE, message ?: "", at)
    is SourceFailure.AuthFailed -> SourceStatus(SourceState.AUTH_FAILED, message ?: "", at)
    is SourceFailure.PermissionDenied -> SourceStatus(SourceState.PERMISSION_DENIED, message ?: "", at)
    is SourceFailure.Unsupported -> SourceStatus(SourceState.UNSUPPORTED, message ?: "", at)
    is SourceFailure.RateLimited, is SourceFailure.Server, is SourceFailure.Protocol -> SourceStatus(SourceState.ERROR, message ?: "", at)
}

interface NotificationSource {
    val id: String
    val displayName: String
    val description: String
    /** Step-by-step instructions shown in settings for creating the token / app registration. */
    val setupHelp: String
    val capabilities: SourceCapabilities
    val configFields: List<ConfigField>
    val status: StateFlow<SourceStatus>

    fun isConfigured(): Boolean
    /** Current non-secret values, for pre-filling the settings form. */
    fun currentConfig(): Map<String, String>
    /** Validates, stores, and verifies credentials against the real API. */
    suspend fun configure(values: Map<String, String>): Result<Unit>
    /** Forgets credentials (Keychain) and non-secret config. */
    suspend fun disconnect()
    /** Fetches items newer than [since]. Never fabricates data; failures come back as [SourceFailure]. */
    suspend fun poll(since: Instant): Result<List<IncomingItem>>
}

/** Sign-in for sources that use the OAuth 2.0 device authorization grant. */
data class DeviceCodePrompt(
    val userCode: String,
    val verificationUri: String,
    val message: String,
    val deviceCode: String,
    val intervalSeconds: Long,
    val expiresAt: Instant,
)

interface DeviceCodeLogin {
    suspend fun startLogin(): Result<DeviceCodePrompt>
    /** Polls until the user approves, declines, or the code expires. Cancellable. */
    suspend fun awaitLogin(prompt: DeviceCodePrompt): Result<Unit>
}

/** Non-secret per-source config persisted in integration settings. */
interface SourceConfigAccess {
    fun read(sourceId: String): Map<String, String>
    fun write(sourceId: String, values: Map<String, String>?)
}

/** A source Task Baba deliberately does not support, listed so the user knows why. */
class UnsupportedSource(
    override val id: String,
    override val displayName: String,
    override val description: String,
    reason: String,
) : NotificationSource {
    override val setupHelp: String = reason
    override val capabilities = SourceCapabilities()
    override val configFields: List<ConfigField> = emptyList()
    override val status: StateFlow<SourceStatus> = MutableStateFlow(SourceStatus(SourceState.UNSUPPORTED, reason)).asStateFlow()
    override fun isConfigured() = false
    override fun currentConfig(): Map<String, String> = emptyMap()
    override suspend fun configure(values: Map<String, String>): Result<Unit> = Result.failure(SourceFailure.Unsupported(setupHelp))
    override suspend fun disconnect() {}
    override suspend fun poll(since: Instant): Result<List<IncomingItem>> = Result.failure(SourceFailure.Unsupported(setupHelp))

    companion object {
        fun all(): List<UnsupportedSource> = listOf(
            UnsupportedSource(
                "macos-notification-center", "Other macOS apps' notifications",
                "Banners from Teams, Mail, WhatsApp and other apps on this Mac.",
                "macOS has no public API that lets one app read another app's notifications, and Task Baba will not " +
                    "read protected system databases. Connect the service's official API instead (Slack, Jira, Microsoft 365).",
            ),
            UnsupportedSource(
                "browser-pwa", "Browser apps and PWAs",
                "Web apps installed from Chrome, Edge, or Safari.",
                "PWA notifications are delivered by the browser and their content stays inside it. Reading them would " +
                    "need a site-specific browser extension, which is on the roadmap but not built. Many PWAs (Teams, " +
                    "Outlook, Slack) are covered by their official API connections above.",
            ),
            UnsupportedSource(
                "webhooks", "Generic webhooks",
                "Push events from arbitrary services.",
                "Webhooks need a publicly reachable server. Task Baba is local-first and does not open public endpoints " +
                    "on your Mac, so this is not supported.",
            ),
        )
    }
}

/** Shared state handling for adapters. */
abstract class BaseSource(protected val clock: () -> Instant) : NotificationSource {
    protected val mutableStatus = MutableStateFlow(SourceStatus(SourceState.NOT_CONFIGURED, "Not connected."))
    override val status: StateFlow<SourceStatus> = mutableStatus.asStateFlow()

    protected fun markConnected(message: String) { mutableStatus.value = SourceStatus(SourceState.CONNECTED, message, clock()) }
    protected fun markConnecting() { mutableStatus.value = SourceStatus(SourceState.CONNECTING, "Connecting…", clock()) }
    protected fun markNotConfigured() { mutableStatus.value = SourceStatus(SourceState.NOT_CONFIGURED, "Not connected.", clock()) }

    protected fun <T> record(result: Result<T>, okMessage: (T) -> String): Result<T> {
        result.onSuccess { markConnected(okMessage(it)) }
        result.onFailure { e ->
            mutableStatus.value = (e as? SourceFailure ?: SourceFailure.Protocol("Unexpected error: ${e.javaClass.simpleName}")).toStatus(clock())
        }
        return result
    }

    /** Call from init once configuration is known so a restart shows "connected, not yet checked". */
    protected fun initStatus() {
        if (isConfigured()) mutableStatus.value = SourceStatus(SourceState.CONNECTED, "Connected — waiting for first check.")
    }
}

/** HTTP helper: default TLS verification, bounded timeouts, and uniform error mapping. Never logs bodies. */
class HttpSupport(
    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(20),
) {
    val json = Json { ignoreUnknownKeys = true }

    fun request(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).header("Accept", "application/json")
            .header("User-Agent", "TaskBaba/1.0")

    suspend fun send(request: HttpRequest): HttpResponse<String> = try {
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    } catch (e: Throwable) {
        throw mapTransport(if (e is CompletionException) e.cause ?: e else e)
    }

    /** Sends and maps HTTP status codes; returns the parsed body on 2xx. */
    suspend fun sendJson(request: HttpRequest): JsonElement {
        val resp = send(request)
        checkStatus(resp)
        return parse(resp.body())
    }

    fun parse(body: String): JsonElement =
        runCatching { json.parseToJsonElement(body.ifBlank { "{}" }) }.getOrElse { throw SourceFailure.Protocol("The service returned an unreadable response.") }

    fun checkStatus(resp: HttpResponse<String>) {
        when (val code = resp.statusCode()) {
            in 200..299 -> Unit
            401 -> throw SourceFailure.AuthFailed()
            403 -> throw SourceFailure.PermissionDenied()
            429 -> throw SourceFailure.RateLimited(retryAfter(resp))
            in 500..599 -> throw SourceFailure.Server(code)
            else -> throw SourceFailure.Protocol("Unexpected HTTP $code from the service.")
        }
    }

    companion object {
        fun mapTransport(e: Throwable): Throwable = when (e) {
            is SourceFailure -> e
            is kotlinx.coroutines.CancellationException -> e
            is HttpConnectTimeoutException, is HttpTimeoutException -> SourceFailure.Offline("The service did not respond in time.")
            is ConnectException, is UnknownHostException, is UnresolvedAddressException -> SourceFailure.Offline()
            is IOException -> SourceFailure.Offline("Network error while contacting the service.")
            is IllegalArgumentException -> SourceFailure.Protocol("Invalid service address.")
            else -> SourceFailure.Protocol("Unexpected error: ${e.javaClass.simpleName}")
        }

        fun retryAfter(resp: HttpResponse<*>): Duration {
            val raw = resp.headers().firstValue("Retry-After").orElse(null)?.trim() ?: return Duration.ofSeconds(60)
            raw.toLongOrNull()?.let { return Duration.ofSeconds(it.coerceIn(1, 3600)) }
            return runCatching {
                val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                Duration.between(Instant.now(), at).coerceAtLeast(Duration.ofSeconds(1))
            }.getOrDefault(Duration.ofSeconds(60))
        }
    }
}

internal fun String.truncate(max: Int): String = if (length <= max) this else take(max - 1).trimEnd() + "…"

/** Strips basic HTML (Graph chat bodies, Outlook previews) to plain text. */
internal fun String.stripHtml(): String =
    replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()
