package app.taskbaba.core.drive

import app.taskbaba.core.platform.SecretStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

class DriveAuthException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NOT_CONNECTED, DENIED, EXPIRED, CANCELLED, NETWORK, MISCONFIGURED }
}

data class OAuthEndpoints(
    val authorize: String = "https://accounts.google.com/o/oauth2/v2/auth",
    val token: String = "https://oauth2.googleapis.com/token",
    val revoke: String = "https://oauth2.googleapis.com/revoke",
)

@Serializable
internal data class StoredTokens(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long)

/**
 * Google OAuth for installed apps: system browser + loopback redirect on 127.0.0.1 with PKCE (S256)
 * and a random state. Tokens and the client secret live only in the [SecretStore] (Keychain).
 *
 * Scope is `drive.metadata.readonly`: Task Baba only needs to confirm that the model files the Colab
 * notebook wrote are present and intact (name, size, SHA-256). It cannot read or change file contents.
 */
class GoogleOAuth(
    private val secrets: SecretStore,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val endpoints: OAuthEndpoints = OAuthEndpoints(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()
    private val random = SecureRandom()

    val isConnected: Boolean get() = loadTokens() != null

    /**
     * Opens the consent page via [openBrowser] and waits for the loopback redirect.
     * Throws [DriveAuthException] with DENIED when the user declines.
     */
    suspend fun authorize(
        clientId: String,
        clientSecret: String?,
        openBrowser: (String) -> Unit,
        timeout: Duration = Duration.ofMinutes(5),
    ) {
        if (clientId.isBlank()) throw DriveAuthException(DriveAuthException.Reason.MISCONFIGURED, "Add your Google OAuth client ID first.")
        val verifier = randomToken(64)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomToken(24)
        val result = CompletableDeferred<Map<String, String>>()

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val params = parseQuery(ex.requestURI.rawQuery)
            val ok = params["error"] == null && params["code"] != null
            val page = if (ok) "Task Baba is connected to Google Drive. You can close this tab."
            else "Task Baba did not get access to Google Drive. You can close this tab."
            val bytes = "<!doctype html><meta charset=utf-8><title>Task Baba</title><p style=\"font:16px -apple-system\">$page</p>".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            if (params.containsKey("code") || params.containsKey("error")) result.complete(params)
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}"
            val url = endpoints.authorize + "?" + form(
                "client_id" to clientId,
                "redirect_uri" to redirect,
                "response_type" to "code",
                "scope" to SCOPE,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "state" to state,
                "access_type" to "offline",
                "prompt" to "consent",
            )
            openBrowser(url)
            val params = withTimeoutOrNull(timeout.toMillis()) { result.await() }
                ?: throw DriveAuthException(DriveAuthException.Reason.CANCELLED, "Timed out waiting for Google sign-in.")
            params["error"]?.let { err ->
                throw if (err == "access_denied") DriveAuthException(DriveAuthException.Reason.DENIED, "Google Drive access was denied.")
                else DriveAuthException(DriveAuthException.Reason.MISCONFIGURED, "Google returned an error: $err")
            }
            if (params["state"] != state) throw DriveAuthException(DriveAuthException.Reason.MISCONFIGURED, "Sign-in response did not match (state mismatch).")
            val code = params["code"]!!
            val tokens = tokenRequest(
                "code" to code,
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "redirect_uri" to redirect,
                "grant_type" to "authorization_code",
                "code_verifier" to verifier,
            )
            secrets.set(SECRET_CLIENT_ID, clientId)
            if (!clientSecret.isNullOrBlank()) secrets.set(SECRET_CLIENT_SECRET, clientSecret) else secrets.delete(SECRET_CLIENT_SECRET)
            saveTokens(tokens)
        } finally {
            server.stop(0)
        }
    }

    /** A valid access token, refreshing it if needed. Throws EXPIRED if the refresh token no longer works. */
    suspend fun accessToken(): String = refreshLock.withLock {
        val t = loadTokens() ?: throw DriveAuthException(DriveAuthException.Reason.NOT_CONNECTED, "Google Drive is not connected.")
        if (t.expiresAtMs - 60_000 > now()) return t.accessToken
        val refresh = t.refreshToken ?: run {
            clearTokens()
            throw DriveAuthException(DriveAuthException.Reason.EXPIRED, "Google sign-in expired. Connect Google Drive again.")
        }
        val clientId = secrets.get(SECRET_CLIENT_ID)
            ?: throw DriveAuthException(DriveAuthException.Reason.MISCONFIGURED, "Missing OAuth client ID. Connect Google Drive again.")
        val fresh = try {
            tokenRequest(
                "client_id" to clientId,
                "client_secret" to secrets.get(SECRET_CLIENT_SECRET),
                "refresh_token" to refresh,
                "grant_type" to "refresh_token",
            )
        } catch (e: DriveAuthException) {
            if (e.reason == DriveAuthException.Reason.EXPIRED) clearTokens()
            throw e
        }
        val merged = fresh.copy(refreshToken = fresh.refreshToken ?: refresh)
        saveTokens(merged)
        merged.accessToken
    }

    /** Forces the next [accessToken] call to refresh (after a 401). */
    fun invalidateAccessToken() {
        loadTokens()?.let { saveTokens(it.copy(expiresAtMs = 0)) }
    }

    /** Revokes the grant at Google (best effort) and deletes all stored tokens. */
    suspend fun disconnect() {
        val t = loadTokens()
        clearTokens()
        val token = t?.refreshToken ?: t?.accessToken ?: return
        withContext(io) {
            runCatching {
                val req = HttpRequest.newBuilder(URI(endpoints.revoke))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form("token" to token)))
                    .build()
                http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).await()
            }
        }
    }

    private suspend fun tokenRequest(vararg params: Pair<String, String?>): StoredTokens = withContext(io) {
        val req = HttpRequest.newBuilder(URI(endpoints.token))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(*params)))
            .build()
        val res = try {
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DriveAuthException(DriveAuthException.Reason.NETWORK, "Couldn't reach Google to sign in. Check your connection.")
        }
        val body = runCatching { json.parseToJsonElement(res.body()).jsonObject }.getOrNull()
        if (res.statusCode() != 200 || body == null) {
            val err = body?.get("error")?.jsonPrimitive?.content
            throw when (err) {
                "invalid_grant" -> DriveAuthException(DriveAuthException.Reason.EXPIRED, "Google sign-in expired or was revoked. Connect Google Drive again.")
                "invalid_client", "unauthorized_client" -> DriveAuthException(DriveAuthException.Reason.MISCONFIGURED, "Google rejected the OAuth client ID/secret.")
                else -> DriveAuthException(DriveAuthException.Reason.NETWORK, "Google token request failed (HTTP ${res.statusCode()}).")
            }
        }
        val access = body["access_token"]?.jsonPrimitive?.content
            ?: throw DriveAuthException(DriveAuthException.Reason.NETWORK, "Google returned no access token.")
        val expiresIn = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        StoredTokens(access, body["refresh_token"]?.jsonPrimitive?.content, now() + expiresIn * 1000)
    }

    private fun loadTokens(): StoredTokens? =
        secrets.get(SECRET_TOKENS)?.let { runCatching { json.decodeFromString(StoredTokens.serializer(), it) }.getOrNull() }

    private fun saveTokens(t: StoredTokens) {
        secrets.set(SECRET_TOKENS, json.encodeToString(StoredTokens.serializer(), t)).getOrThrow()
    }

    private fun clearTokens() = secrets.delete(SECRET_TOKENS)

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.metadata.readonly"
        const val SECRET_TOKENS = "drive.tokens"
        const val SECRET_CLIENT_ID = "drive.clientId"
        const val SECRET_CLIENT_SECRET = "drive.clientSecret"

        private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        internal fun form(vararg params: Pair<String, String?>): String =
            params.filter { it.second != null }.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8)
            }

        internal fun parseQuery(raw: String?): Map<String, String> =
            raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
                val i = part.indexOf('=')
                val k = if (i < 0) part else part.substring(0, i)
                val v = if (i < 0) "" else part.substring(i + 1)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }
    }
}
