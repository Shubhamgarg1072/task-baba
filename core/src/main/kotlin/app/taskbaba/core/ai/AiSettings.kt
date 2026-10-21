package app.taskbaba.core.ai

import app.taskbaba.core.model.AiMode
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * AI preferences, stored in the key-value store under [KEY]. The API key is never stored here; it
 * lives in the Keychain under [API_KEY_SECRET].
 */
@Serializable
data class AiSettings(
    val mode: AiMode = AiMode.BUILT_IN,
    /** Base URL of the Colab tunnel (or another llama.cpp-compatible server), e.g. https://x.trycloudflare.com */
    val endpoint: String = "",
    val cloudConsentGiven: Boolean = false,
    val shareTaskTitles: Boolean = false,
    val shareCategories: Boolean = true,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val temperature: Double = 0.8,
    /** Google OAuth desktop client id supplied by the user (a public identifier, not a secret). */
    val driveClientId: String = "",
    val driveFolderId: String? = null,
    val driveFolderName: String? = null,
    val modelId: String = "qwen3-4b-q4km",
) {
    /** Values clamped to safe ranges; use this everywhere instead of the raw fields. */
    fun normalized(): AiSettings = copy(
        endpoint = endpoint.trim().trimEnd('/'),
        maxTokens = maxTokens.coerceIn(16, MAX_TOKENS_CAP),
        timeoutSeconds = timeoutSeconds.coerceIn(2, TIMEOUT_CAP_SECONDS),
        temperature = temperature.coerceIn(0.0, 1.5),
    )

    /** The mode actually in force: cloud modes need consent and a valid endpoint, otherwise built-in. */
    fun effectiveMode(): AiMode =
        if (mode == AiMode.BUILT_IN || !cloudConsentGiven || validateEndpoint(endpoint) != null) AiMode.BUILT_IN else mode

    companion object {
        const val KEY = "ai"
        const val API_KEY_SECRET = "ai.apiKey"
        const val DEFAULT_MAX_TOKENS = 80
        const val MAX_TOKENS_CAP = 200
        const val DEFAULT_TIMEOUT_SECONDS = 8
        const val TIMEOUT_CAP_SECONDS = 30

        /** Returns an error message, or null when the endpoint is acceptable. */
        fun validateEndpoint(raw: String): String? {
            val s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return "Enter the endpoint URL printed by the Colab notebook."
            val uri = runCatching { URI(s) }.getOrNull() ?: return "That URL doesn't parse."
            val host = uri.host ?: return "The URL needs a host, e.g. https://example.trycloudflare.com"
            if (uri.userInfo != null) return "Don't put credentials in the URL; use the API key field."
            if (uri.query != null || uri.fragment != null) return "Remove the query string from the URL."
            return when (uri.scheme?.lowercase()) {
                "https" -> null
                "http" -> if (host == "localhost" || host == "127.0.0.1" || host == "[::1]") null
                else "Use https. Plain http is only allowed for localhost testing."
                else -> "The URL must start with https://"
            }
        }
    }
}
