package app.taskbaba.core.ai

import app.taskbaba.core.model.AiMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

/**
 * Chooses where a monk line comes from. There is no on-device model: the cloud route is the user's
 * Colab session, and every failure path returns null so the caller shows its built-in line.
 *
 * - AUTOMATIC: Colab when its health check passes, otherwise built-in. Returns to Colab automatically
 *   once the health monitor sees it recover.
 * - CLOUD_ONLY: same route, but the UI reports Colab outages as errors rather than a quiet fallback.
 * - BUILT_IN (or no consent / no endpoint): never touches the network.
 *
 * [rewrite] never blocks longer than the overall budget, so reminders and notifications are never
 * delayed by slow inference.
 */
class AiRouter(
    private val settings: StateFlow<AiSettings>,
    private val apiKey: () -> String?,
    private val backend: InferenceBackend,
    private val health: EndpointHealthMonitor,
    private val scope: CoroutineScope,
    private val prompts: PromptBuilder = PromptBuilder(),
    private val validator: OutputValidator = OutputValidator(),
    private val budgetMs: (AiSettings) -> Long = { (it.timeoutSeconds + 2) * 1000L },
) : MonkAi {

    private val inFlight = HashMap<String, Deferred<String?>>()

    override val status: StateFlow<AiStatus> =
        combine(settings, health.state) { s, h -> describe(s, h) }
            .stateIn(scope, SharingStarted.Eagerly, describe(settings.value, health.state.value))

    override suspend fun rewrite(request: MonkAiRequest): String? {
        val s = settings.value.normalized()
        if (s.effectiveMode() == AiMode.BUILT_IN) return null
        val h = health.state.value
        if (h !is EndpointHealth.Healthy) {
            if (h is EndpointHealth.Unknown || h is EndpointHealth.NotConfigured) health.requestCheck()
            return null
        }
        val key = dedupKey(request, s)
        val deferred = synchronized(inFlight) {
            inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) { call(request, s) }.also { d ->
                inFlight[key] = d
                d.invokeOnCompletion { synchronized(inFlight) { if (inFlight[key] === d) inFlight.remove(key) } }
                d.start()
            }
        }
        // The shared request keeps running for other waiters; this caller just stops waiting.
        return withTimeoutOrNull(budgetMs(s)) { deferred.await() }
    }

    /** Sends one request regardless of health state and reports exactly what happened (for "Test inference"). */
    suspend fun test(request: MonkAiRequest): InferenceResult {
        val s = settings.value.normalized()
        AiSettings.validateEndpoint(s.endpoint)?.let { return InferenceResult.Failure(FailureKind.NOT_CONFIGURED, it) }
        val result = backend.chat(endpoint(s), prompts.build(request, s), params(s))
        return when (result) {
            is InferenceResult.Success -> {
                health.reportSuccess(result.latencyMs)
                when (val v = validator.check(result.text)) {
                    is OutputValidator.Verdict.Accepted -> result.copy(text = v.text)
                    is OutputValidator.Verdict.Rejected -> InferenceResult.Failure(
                        FailureKind.BAD_RESPONSE, "The model answered, but the reply was rejected (${v.reason}); Baba would use his built-in line.",
                    )
                }
            }
            is InferenceResult.Failure -> result.also { health.reportFailure(it.kind, it.message) }
        }
    }

    private suspend fun call(request: MonkAiRequest, s: AiSettings): String? =
        when (val r = backend.chat(endpoint(s), prompts.build(request, s), params(s))) {
            is InferenceResult.Success -> {
                health.reportSuccess(r.latencyMs)
                validator.validate(r.text)
            }
            is InferenceResult.Failure -> {
                health.reportFailure(r.kind, r.message)
                null
            }
        }

    private fun endpoint(s: AiSettings) = EndpointConfig(s.endpoint, apiKey())

    private fun params(s: AiSettings) = GenerationParams(s.maxTokens, s.temperature, Duration.ofSeconds(s.timeoutSeconds.toLong()))

    private fun dedupKey(r: MonkAiRequest, s: AiSettings) =
        listOf(r.event, r.personality.name, r.sarcasmAllowed.toString(), r.fallback,
            prompts.filterContext(r.context, s).toSortedMap().toString()).joinToString("\u0000")

    companion object {
        fun describe(s: AiSettings, h: EndpointHealth): AiStatus {
            val n = s.normalized()
            if (n.mode == AiMode.BUILT_IN) return AiStatus(AiRoute.BUILT_IN, "Built-in only", healthy = true)
            val prefix = if (n.mode == AiMode.CLOUD_ONLY) "Cloud only: " else ""
            if (!n.cloudConsentGiven) return AiStatus(AiRoute.BUILT_IN, "${prefix}waiting for your consent; using built-in lines", false)
            if (AiSettings.validateEndpoint(n.endpoint) != null) return AiStatus(AiRoute.BUILT_IN, "${prefix}no Colab endpoint set; using built-in lines", false)
            return when (h) {
                is EndpointHealth.Healthy -> AiStatus(AiRoute.CLOUD, "${prefix}Colab model online (${h.latencyMs} ms)", true)
                is EndpointHealth.Down -> AiStatus(AiRoute.BUILT_IN, prefix + when (h.kind) {
                    FailureKind.UNAUTHORIZED -> "API key rejected; using built-in lines"
                    FailureKind.LOADING -> "Colab model still loading; using built-in lines"
                    FailureKind.TIMEOUT -> "Colab too slow to answer; using built-in lines"
                    FailureKind.RATE_LIMITED -> "Colab rate limit hit; using built-in lines"
                    else -> "Colab session offline; using built-in lines"
                }, false)
                EndpointHealth.Unknown, EndpointHealth.NotConfigured -> AiStatus(AiRoute.BUILT_IN, "${prefix}checking Colab connection…", false)
            }
        }
    }
}
