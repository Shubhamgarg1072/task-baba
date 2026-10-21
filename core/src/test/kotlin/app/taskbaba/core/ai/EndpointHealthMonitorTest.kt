package app.taskbaba.core.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class EndpointHealthMonitorTest {
    private val cfg = EndpointConfig("https://example.test", "k")

    @Test fun `backs off exponentially while down and recovers`() = runTest {
        val probeTimes = mutableListOf<Long>()
        var healthy = false
        val monitor = EndpointHealthMonitor(
            scope = backgroundScope,
            config = { cfg },
            probe = {
                probeTimes += testScheduler.currentTime
                if (healthy) HealthResult.Healthy(5) else HealthResult.Unhealthy(FailureKind.UNREACHABLE, "down")
            },
            minBackoffMs = 1_000, maxBackoffMs = 4_000, healthyIntervalMs = 60_000,
            now = { testScheduler.currentTime },
        )
        monitor.start()
        runCurrent()
        advanceTimeBy(1_000 + 2_000 + 4_000 + 4_000 + 1)
        // probes at 0, then after 1s, 2s, 4s, 4s (capped)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 11_000L), probeTimes)
        assertIs<EndpointHealth.Down>(monitor.state.value)

        healthy = true
        monitor.requestCheck()
        runCurrent()
        assertIs<EndpointHealth.Healthy>(monitor.state.value)
        val countAfterRecovery = probeTimes.size
        advanceTimeBy(30_000)
        assertEquals(countAfterRecovery, probeTimes.size, "healthy endpoints are polled slowly")
    }

    @Test fun `not configured never probes`() = runTest {
        var probes = 0
        val monitor = EndpointHealthMonitor(backgroundScope, config = { null }, probe = { probes++; HealthResult.Healthy(1) })
        monitor.start()
        runCurrent()
        advanceTimeBy(600_000)
        assertEquals(0, probes)
        assertEquals(EndpointHealth.NotConfigured, monitor.state.value)
    }

    @Test fun `reported request failure marks endpoint down and triggers recheck`() = runTest {
        var probes = 0
        val monitor = EndpointHealthMonitor(
            backgroundScope, config = { cfg },
            probe = { probes++; HealthResult.Healthy(3) },
            now = { testScheduler.currentTime },
        )
        monitor.start()
        runCurrent()
        assertEquals(1, probes)
        monitor.reportFailure(FailureKind.TIMEOUT, "slow")
        assertIs<EndpointHealth.Down>(monitor.state.value)
        runCurrent()
        assertEquals(2, probes)
        assertIs<EndpointHealth.Healthy>(monitor.state.value)
    }
}
