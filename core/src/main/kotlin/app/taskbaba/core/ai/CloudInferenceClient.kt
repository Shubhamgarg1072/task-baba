package app.taskbaba.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

data class EndpointConfig(val baseUrl: String, val apiKey: String?)

@Serializable
data class ChatMessage(val role: String, val content: String)

data class GenerationParams(val maxTokens: Int, val temperature: Double, val timeout: Duration)

enum class FailureKind { UNREACHABLE, UNAUTHORIZED, SERVER_ERROR, LOADING, TIMEOUT, RATE_LIMITED, BAD_RESPONSE, NOT_CONFIGURED }

sealed interface HealthResult {
    data class Healthy(val latencyMs: Long) : HealthResult
    data class Unhealthy(val kind: FailureKind, val message: String) : HealthResult
}

sealed interface InferenceResult {
    data class Success(val text: String, val latencyMs: Long) : InferenceResult
    data class Failure(val kind: FailureKind, val message: String) : InferenceResult
}

/** The network seam used by the router; [CloudInferenceClient] is the real implementation. */
interface InferenceBackend {
    suspend fun health(config: EndpointConfig, timeout: Duration): HealthResult
    suspend fun chat(config: EndpointConfig, messages: List<ChatMessage>, params: GenerationParams): InferenceResult
}

/**
 * OpenAI-compatible client for a llama.cpp server (directly, or behind the Task Baba auth proxy).
 * TLS verification always uses JDK defaults. Prompts, responses and keys are never logged.
 */
class CloudInferenceClient(
    private val http: HttpClient = defaultHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelayMs: Long = 400,
) : InferenceBackend {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun health(config: EndpointConfig, timeout: Duration): HealthResult = withContext(io) {
        val started = System.nanoTime()
        val req = HttpRequest.newBuilder(URI("${config.baseUrl}/health"))
            .timeout(timeout)
            .GET()
            .withBearer(config.apiKey)
            .build()
        try {
            val res = http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).await()
            val ms = (System.nanoTime() - started) / 1_000_000
            when (res.statusCode()) {
                200 -> HealthResult.Healthy(ms)
                401, 403 -> HealthResult.Unhealthy(FailureKind.UNAUTHORIZED, "The server rejected the API key.")
                503 -> HealthResult.Unhealthy(FailureKind.LOADING, "The server is up but the model is still loading.")
                429 -> HealthResult.Unhealthy(FailureKind.RATE_LIMITED, "The server is rate-limiting requests.")
                else -> HealthResult.Unhealthy(FailureKind.SERVER_ERROR, "Health check returned HTTP ${res.statusCode()}.")
            }
        } catch (e: Exception) {
            val f = classify(e)
            HealthResult.Unhealthy(f.kind, f.message)
        }
    }

    override suspend fun chat(config: EndpointConfig, messages: List<ChatMessage>, params: GenerationParams): InferenceResult =
        withContext(io) {
            val body = buildJsonObject {
                put("model", "task-baba")
                put("messages", buildJsonArray {
                    messages.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) }
                })
                put("max_tokens", params.maxTokens.coerceIn(1, AiSettings.MAX_TOKENS_CAP))
                put("temperature", params.temperature)
                put("stream", false)
                // Qwen3: answer directly without a hidden reasoning block.
                putJsonObject("chat_template_kwargs") { put("enable_thinking", false) }
            }.toString()
            val req = HttpRequest.newBuilder(URI("${config.baseUrl}/v1/chat/completions"))
                .timeout(params.timeout)
                .header("Content-Type", "application/json")
                .withBearer(config.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            var attempt = 0
            var result: InferenceResult
            while (true) {
                result = send(req)
                // One retry for transient server/connect errors only; timeouts are not retried so the
                // caller's time budget is respected.
                val retryable = result is InferenceResult.Failure &&
                    (result.kind == FailureKind.SERVER_ERROR || result.kind == FailureKind.UNREACHABLE)
                if (!retryable || attempt >= 1) break
                attempt++
                delay(retryDelayMs)
            }
            result
        }

    private suspend fun send(req: HttpRequest): InferenceResult {
        val started = System.nanoTime()
        return try {
            val res = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
            val ms = (System.nanoTime() - started) / 1_000_000
            when (val code = res.statusCode()) {
                200 -> parse(res.body(), ms)
                401, 403 -> InferenceResult.Failure(FailureKind.UNAUTHORIZED, "The server rejected the API key.")
                429 -> InferenceResult.Failure(FailureKind.RATE_LIMITED, "The server is rate-limiting requests.")
                503 -> InferenceResult.Failure(FailureKind.LOADING, "The model is still loading.")
                in 500..599 -> InferenceResult.Failure(FailureKind.SERVER_ERROR, "Server error (HTTP $code).")
                else -> InferenceResult.Failure(FailureKind.BAD_RESPONSE, "Unexpected HTTP $code.")
            }
        } catch (e: Exception) {
            classify(e)
        }
    }

    private fun parse(body: String, ms: Long): InferenceResult = try {
        val root = json.parseToJsonElement(body).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
        if (content == null) InferenceResult.Failure(FailureKind.BAD_RESPONSE, "Response had no message content.")
        else InferenceResult.Success(content, ms)
    } catch (e: Exception) {
        InferenceResult.Failure(FailureKind.BAD_RESPONSE, "Response was not valid JSON.")
    }

    private fun classify(e: Throwable): InferenceResult.Failure {
        if (e is CancellationException) throw e
        val chain = generateSequence(e) { it.cause }.toList()
        return when {
            chain.any { it is HttpConnectTimeoutException } ->
                InferenceResult.Failure(FailureKind.UNREACHABLE, "Could not connect to the server in time.")
            chain.any { it is HttpTimeoutException } ->
                InferenceResult.Failure(FailureKind.TIMEOUT, "The server took too long to answer.")
            chain.any { it is UnknownHostException } ->
                InferenceResult.Failure(FailureKind.UNREACHABLE, "Host not found: offline, or the Colab tunnel URL expired.")
            chain.any { it is ConnectException } ->
                InferenceResult.Failure(FailureKind.UNREACHABLE, "Connection refused. Is the Colab session running?")
            chain.any { it is javax.net.ssl.SSLException } ->
                InferenceResult.Failure(FailureKind.UNREACHABLE, "Secure connection failed (TLS).")
            chain.any { it is IOException } ->
                InferenceResult.Failure(FailureKind.UNREACHABLE, "Network error talking to the server.")
            chain.any { it is IllegalArgumentException } ->
                InferenceResult.Failure(FailureKind.NOT_CONFIGURED, "The endpoint URL is invalid.")
            else -> InferenceResult.Failure(FailureKind.BAD_RESPONSE, "Unexpected error: ${e::class.simpleName}")
        }
    }

    private fun HttpRequest.Builder.withBearer(key: String?): HttpRequest.Builder =
        apply { key?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build()
    }
}
