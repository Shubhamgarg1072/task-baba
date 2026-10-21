package app.taskbaba.core.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed interface EndpointHealth {
    /** Built-in mode, no consent, or no endpoint: nothing is probed. */
    data object NotConfigured : EndpointHealth
    data object Unknown : EndpointHealth
    data class Healthy(val latencyMs: Long, val checkedAt: Long) : EndpointHealth
    data class Down(val kind: FailureKind, val message: String, val checkedAt: Long, val retryInMs: Long) : EndpointHealth
}

/**
 * Tracks whether the configured endpoint actually answers its health check (not merely whether the
 * Mac has a network). While down it retries with exponential backoff; [requestCheck] forces an
 * immediate probe, e.g. after a settings change or a failed request.
 */
class EndpointHealthMonitor(
    private val scope: CoroutineScope,
    private val config: () -> EndpointConfig?,
    private val probe: suspend (EndpointConfig) -> HealthResult,
    private val minBackoffMs: Long = 15_000,
    private val maxBackoffMs: Long = 300_000,
    private val healthyIntervalMs: Long = 120_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<EndpointHealth>(EndpointHealth.Unknown)
    val state: StateFlow<EndpointHealth> = _state.asStateFlow()
    val isHealthy: Boolean get() = _state.value is EndpointHealth.Healthy

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val probeLock = Mutex()
    private var consecutiveFailures = 0
    private var job: Job? = null

    fun start(): Job = job ?: scope.launch {
        while (isActive) {
            val cfg = config()
            val waitMs = if (cfg == null) {
                _state.value = EndpointHealth.NotConfigured
                consecutiveFailures = 0
                Long.MAX_VALUE
            } else {
                when (val s = runProbe(cfg)) {
                    is EndpointHealth.Down -> s.retryInMs
                    else -> healthyIntervalMs
                }
            }
            if (waitMs == Long.MAX_VALUE) wake.receive() else withTimeoutOrNull(waitMs) { wake.receive() }
        }
    }.also { job = it }

    /** Ask the background loop to probe now (non-blocking). */
    fun requestCheck() {
        wake.trySend(Unit)
    }

    /** Probe immediately and return the result. [override] lets the UI test an endpoint before it is saved. */
    suspend fun checkNow(override: EndpointConfig? = null): EndpointHealth {
        val cfg = override ?: config() ?: return EndpointHealth.NotConfigured.also { _state.value = it }
        return runProbe(cfg)
    }

    /** Called by the router when a real request failed, so callers stop waiting on a dead endpoint. */
    fun reportFailure(kind: FailureKind, message: String) {
        if (kind == FailureKind.BAD_RESPONSE) return // the server answered; the reply was just unusable
        consecutiveFailures++
        _state.value = EndpointHealth.Down(kind, message, now(), backoff())
        requestCheck()
    }

    fun reportSuccess(latencyMs: Long) {
        consecutiveFailures = 0
        _state.value = EndpointHealth.Healthy(latencyMs, now())
    }

    private suspend fun runProbe(cfg: EndpointConfig): EndpointHealth = probeLock.withLock {
        val next = when (val r = probe(cfg)) {
            is HealthResult.Healthy -> {
                consecutiveFailures = 0
                EndpointHealth.Healthy(r.latencyMs, now())
            }
            is HealthResult.Unhealthy -> {
                consecutiveFailures++
                EndpointHealth.Down(r.kind, r.message, now(), backoff())
            }
        }
        _state.value = next
        next
    }

    private fun backoff(): Long {
        val shift = (consecutiveFailures - 1).coerceIn(0, 10)
        return (minBackoffMs shl shift).coerceAtMost(maxBackoffMs)
    }
}
