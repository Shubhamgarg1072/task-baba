package app.taskbaba.core.ai

import app.taskbaba.core.data.KvStore
import app.taskbaba.core.data.PersistedState
import app.taskbaba.core.drive.DriveClient
import app.taskbaba.core.drive.DriveModelService
import app.taskbaba.core.drive.GoogleOAuth
import app.taskbaba.core.model.AiMode
import app.taskbaba.core.model.Personality
import app.taskbaba.core.platform.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.net.http.HttpClient
import java.time.Duration

/**
 * Wires the hybrid-AI and Google Drive pieces together. Construct once at startup and call [start].
 * [router] is the [MonkAi] the monk engine should use.
 */
class AiModule(
    kv: KvStore,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
    openBrowser: (String) -> Unit,
    http: HttpClient = CloudInferenceClient.defaultHttpClient(),
    driveBaseUrl: String = "https://www.googleapis.com/drive/v3",
) {
    val settings: PersistedState<AiSettings> = kv.state(AiSettings.KEY, AiSettings.serializer()) { AiSettings() }
    val client = CloudInferenceClient(http)

    val health = EndpointHealthMonitor(
        scope = scope,
        config = { currentEndpoint(requireActiveMode = true) },
        probe = { cfg -> client.health(cfg, Duration.ofSeconds(5)) },
    )

    val router = AiRouter(settings.state, ::apiKey, client, health, scope)

    val oauth = GoogleOAuth(secrets)
    val driveClient = DriveClient(oauth, baseUrl = driveBaseUrl)
    val drive = DriveModelService(settings, oauth, driveClient, openBrowser)

    private var started = false

    fun start() {
        if (started) return
        started = true
        health.start()
        // Re-probe whenever the route-relevant settings change.
        settings.state
            .map { val n = it.normalized(); Triple(n.effectiveMode(), n.endpoint, n.cloudConsentGiven) }
            .distinctUntilChanged()
            .drop(1)
            .onEach { health.requestCheck() }
            .launchIn(scope)
    }

    fun apiKey(): String? = secrets.get(AiSettings.API_KEY_SECRET)
    val hasApiKey: Boolean get() = !apiKey().isNullOrBlank()

    fun setApiKey(key: String): Result<Unit> {
        val k = key.trim()
        if (k.length < 16 || k.any { it.isWhitespace() || it.isISOControl() }) {
            return Result.failure(IllegalArgumentException("That doesn't look like the key the notebook printed."))
        }
        return secrets.set(AiSettings.API_KEY_SECRET, k).also { health.requestCheck() }
    }

    fun clearApiKey() {
        secrets.delete(AiSettings.API_KEY_SECRET)
        health.requestCheck()
    }

    /** Explicit, user-initiated health check (works before consent; it sends no personal data). */
    suspend fun checkConnection(): EndpointHealth {
        val cfg = currentEndpoint(requireActiveMode = false) ?: return EndpointHealth.NotConfigured
        return health.checkNow(cfg)
    }

    /** Sends a fixed sample prompt containing no personal data and reports the real outcome. */
    suspend fun testInference(): InferenceResult = router.test(
        MonkAiRequest(
            event = "test",
            personality = Personality.ADAPTIVE,
            sarcasmAllowed = true,
            fallback = "One task at a time, my child. Even the universe wasn't built by multitasking.",
        ),
    )

    /** Turning a cloud mode on requires consent; the UI must show the consent dialog first. */
    fun setMode(mode: AiMode, consent: Boolean? = null) {
        settings.update { s ->
            s.copy(mode = mode, cloudConsentGiven = consent ?: s.cloudConsentGiven)
        }
    }

    fun revokeConsent() {
        settings.update { it.copy(cloudConsentGiven = false, mode = AiMode.BUILT_IN) }
    }

    private fun currentEndpoint(requireActiveMode: Boolean): EndpointConfig? {
        val s = settings.value.normalized()
        if (requireActiveMode && s.effectiveMode() == AiMode.BUILT_IN) return null
        if (AiSettings.validateEndpoint(s.endpoint) != null) return null
        return EndpointConfig(s.endpoint, apiKey())
    }
}
