package app.taskbaba.core.ai

import app.taskbaba.core.model.Personality
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional AI rewriting of monk messages. The deterministic line is always computed first and passed
 * as [fallback]; implementations return null to keep it. AI output is display text only and can never
 * trigger app actions.
 */
data class MonkAiRequest(
    val event: String,
    val personality: Personality,
    val sarcasmAllowed: Boolean,
    val fallback: String,
    /** Minimal, consent-filtered context (e.g. a task title only when the user allowed sharing titles). */
    val context: Map<String, String> = emptyMap(),
)

enum class AiRoute { CLOUD, BUILT_IN }

data class AiStatus(val route: AiRoute, val label: String, val healthy: Boolean)

interface MonkAi {
    val status: StateFlow<AiStatus>
    suspend fun rewrite(request: MonkAiRequest): String?
}
