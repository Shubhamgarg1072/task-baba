package app.taskbaba.core.platform

import kotlinx.coroutines.flow.Flow
import java.time.Clock

/**
 * [REFUSED]: macOS rejected the permission request itself (UNErrorDomain 1). For apps built and
 * ad-hoc signed on this Mac this happens after a rebuild (the stored permission no longer matches the
 * new signature) or when the app wasn't launched normally from Finder/Dock.
 */
enum class NotificationAuth { NOT_DETERMINED, AUTHORIZED, DENIED, REFUSED, UNAVAILABLE }

enum class NotificationKind { TASK, HEALTH, INBOX, SUMMARY, TEST }

data class NotificationAction(val id: String, val title: String, val destructive: Boolean = false)

data class BabaNotification(
    /** Stable id; posting the same id replaces the previous banner instead of stacking duplicates. */
    val id: String,
    val kind: NotificationKind,
    val title: String,
    val body: String,
    val actions: List<NotificationAction> = emptyList(),
    val sound: Boolean = true,
    val userInfo: Map<String, String> = emptyMap(),
)

data class NotificationResponse(
    val notificationId: String,
    /** Action id, or [DEFAULT_ACTION] when the banner itself was clicked. */
    val actionId: String,
    val userInfo: Map<String, String>,
) {
    companion object { const val DEFAULT_ACTION = "default" }
}

/** Native notification delivery (UserNotifications via the macOS bridge, or a fake in tests). */
interface Notifier {
    suspend fun authorization(): NotificationAuth
    /** Shows the system permission prompt if undetermined; returns the resulting status. */
    suspend fun requestAuthorization(): NotificationAuth
    suspend fun post(notification: BabaNotification): Result<Unit>
    suspend fun remove(id: String)
    val responses: Flow<NotificationResponse>
}

/** Secrets (API keys, OAuth tokens) live in the macOS Keychain, never in the database or settings. */
interface SecretStore {
    fun get(key: String): String?
    fun set(key: String, value: String): Result<Unit>
    fun delete(key: String)
}

enum class DeviceAuthResult { SUCCESS, CANCELLED, FAILED, UNAVAILABLE }

/** Asks the Mac's owner to confirm with Touch ID or their login password. */
fun interface DeviceAuthenticator {
    suspend fun authenticate(reason: String): DeviceAuthResult
}

enum class LoginItemStatus { ENABLED, DISABLED, REQUIRES_APPROVAL, UNAVAILABLE }

interface LoginItemController {
    fun status(): LoginItemStatus
    fun setEnabled(enabled: Boolean): Result<LoginItemStatus>
}

/** In-memory secret store for tests and for running outside the app bundle. */
class InMemorySecretStore : SecretStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String) = Result.success(Unit).also { map[key] = value }
    override fun delete(key: String) { map.remove(key) }
}

/** Injectable time source; the system default zone is read on every call so time-zone changes apply live. */
fun interface ClockProvider {
    fun clock(): Clock
    companion object { val SYSTEM = ClockProvider { Clock.systemDefaultZone() } }
}
