package app.taskbaba.app.platform

import app.taskbaba.core.platform.InMemorySecretStore
import app.taskbaba.core.platform.LoginItemController
import app.taskbaba.core.platform.LoginItemStatus
import app.taskbaba.core.platform.SecretStore
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Entry point that wires every native integration from one located helper. */
class MacBridge(scope: CoroutineScope, val helper: Path? = BridgeLocator.locate()) {
    val notifier = MacNotifier(helper, scope)
    val secrets = KeychainSecretStore(helper)
    val loginItem = MacLoginItemController(helper)
    val deviceAuth = MacDeviceAuthenticator(helper)
    val isAvailable: Boolean get() = helper != null

    fun close() = notifier.close()
}

internal data class HelperResult(val exitCode: Int, val stdout: String)

/** Runs a short-lived helper command. Secrets go through stdin only, never argv. */
internal fun runHelper(helper: Path, args: List<String>, stdin: String? = null, timeoutSec: Long = 10): HelperResult? = runCatching {
    val p = ProcessBuilder(listOf(helper.toString()) + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    p.outputStream.use { out -> if (stdin != null) out.write(stdin.toByteArray(Charsets.UTF_8)) }
    val stdout = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroy(); return null }
    HelperResult(p.exitValue(), stdout)
}.getOrNull()

/**
 * Keychain-backed secrets (generic passwords, service `app.taskbaba.secrets`). Without the helper it uses
 * an in-memory store and reports [isPersistent] = false so the UI can warn that secrets won't survive a restart.
 */
class KeychainSecretStore(private val helper: Path?) : SecretStore {
    private val memory = InMemorySecretStore()
    val isPersistent: Boolean get() = helper != null

    override fun get(key: String): String? {
        val h = helper ?: return memory.get(key)
        val r = runHelper(h, listOf("keychain", "get", SERVICE, key)) ?: return null
        return if (r.exitCode == 0) r.stdout else null
    }

    override fun set(key: String, value: String): Result<Unit> {
        require(value.isNotEmpty()) { "Secret must not be empty" }
        val h = helper ?: return memory.set(key, value)
        val r = runHelper(h, listOf("keychain", "set", SERVICE, key), stdin = value)
        return if (r?.exitCode == 0) Result.success(Unit) else Result.failure(IllegalStateException("Could not save to the Keychain."))
    }

    override fun delete(key: String) {
        val h = helper ?: return memory.delete(key)
        runHelper(h, listOf("keychain", "delete", SERVICE, key))
    }

    companion object { const val SERVICE = "app.taskbaba.secrets" }
}

/** Launch at login via SMAppService.mainApp. macOS may require approval in System Settings › Login Items. */
class MacLoginItemController(private val helper: Path?) : LoginItemController {
    override fun status(): LoginItemStatus {
        val h = helper ?: return LoginItemStatus.UNAVAILABLE
        val r = runHelper(h, listOf("login", "status")) ?: return LoginItemStatus.UNAVAILABLE
        return parse(r.stdout, r.exitCode)
    }

    override fun setEnabled(enabled: Boolean): Result<LoginItemStatus> {
        val h = helper ?: return Result.failure(IllegalStateException("Launch at login is only available in the packaged app."))
        val r = runHelper(h, listOf("login", if (enabled) "enable" else "disable"))
            ?: return Result.failure(IllegalStateException("The login item helper did not respond."))
        val status = parse(r.stdout, r.exitCode)
        return if (r.exitCode == 0) Result.success(status)
        else Result.failure(IllegalStateException("macOS refused to ${if (enabled) "enable" else "disable"} launch at login (status: $status)."))
    }

    companion object {
        /** Exit code 2 means the helper runs outside an app bundle. */
        private const val NOT_BUNDLED = 2

        /** `notFound` from a bundled helper only means "never registered", which is simply off. */
        fun parse(out: String?, exitCode: Int): LoginItemStatus = when {
            exitCode == NOT_BUNDLED -> LoginItemStatus.UNAVAILABLE
            else -> when (out?.trim()) {
                "enabled" -> LoginItemStatus.ENABLED
                "disabled", "notFound" -> LoginItemStatus.DISABLED
                "requiresApproval" -> LoginItemStatus.REQUIRES_APPROVAL
                else -> LoginItemStatus.UNAVAILABLE
            }
        }
    }
}

/** Touch ID / Mac password via `babakit auth verify`. Blocks only an IO thread while the system prompt is up. */
class MacDeviceAuthenticator(private val helper: Path?) : app.taskbaba.core.platform.DeviceAuthenticator {
    override suspend fun authenticate(reason: String): app.taskbaba.core.platform.DeviceAuthResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val h = helper ?: return@withContext app.taskbaba.core.platform.DeviceAuthResult.UNAVAILABLE
            val r = runHelper(h, listOf("auth", "verify", reason), timeoutSec = 180)
                ?: return@withContext app.taskbaba.core.platform.DeviceAuthResult.FAILED
            when (r.stdout.trim()) {
                "ok" -> app.taskbaba.core.platform.DeviceAuthResult.SUCCESS
                "cancelled" -> app.taskbaba.core.platform.DeviceAuthResult.CANCELLED
                "unavailable" -> app.taskbaba.core.platform.DeviceAuthResult.UNAVAILABLE
                else -> app.taskbaba.core.platform.DeviceAuthResult.FAILED
            }
        }
}
