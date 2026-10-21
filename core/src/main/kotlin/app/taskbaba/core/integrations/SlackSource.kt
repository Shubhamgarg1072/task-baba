package app.taskbaba.core.integrations

import app.taskbaba.core.platform.SecretStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Instant

/**
 * Slack via the official Web API with a user token (xoxp-…) from the user's own Slack app.
 * Uses `search.messages` for @mentions and direct messages. Requires the `search:read` user scope.
 */
class SlackSource(
    private val secrets: SecretStore,
    private val config: SourceConfigAccess,
    private val http: HttpSupport = HttpSupport(),
    private val baseUrl: String = "https://slack.com/api",
    clock: () -> Instant = Instant::now,
) : BaseSource(clock) {
    override val id = ID
    override val displayName = "Slack"
    override val description = "Messages that @mention you and direct messages."
    override val capabilities = SourceCapabilities(canOpenOriginal = true)
    override val configFields = listOf(ConfigField(TOKEN, "User OAuth token", "xoxp-… — stored in your Keychain", secret = true))
    override val setupHelp = """
        Slack doesn't let third-party apps read messages without a Slack app, so you create a private one:
        1. Go to https://api.slack.com/apps → "Create New App" → "From scratch", pick your workspace.
        2. Under "OAuth & Permissions" → "User Token Scopes", add: search:read
        3. Click "Install to Workspace" (your admin may need to approve).
        4. Copy the "User OAuth Token" (starts with xoxp-) and paste it here.
        Task Baba only searches for your mentions and DMs; it never posts, reacts, or marks messages read.
    """.trimIndent()

    init { initStatus() }

    override fun isConfigured() = secrets.get(SECRET_KEY) != null
    override fun currentConfig(): Map<String, String> = config.read(id)

    override suspend fun configure(values: Map<String, String>): Result<Unit> {
        val token = values[TOKEN].orEmpty().trim()
        if (!token.startsWith("xox")) return Result.failure(SourceFailure.Protocol("Paste a Slack user token (it starts with xoxp-)."))
        markConnecting()
        val verified = record(runCatching { authTest(token) }) { "Connected as ${it.second} in ${it.third}" }
        val (userId, user, team) = verified.getOrElse { return Result.failure(it) }
        secrets.set(SECRET_KEY, token).onFailure { return Result.failure(SourceFailure.Protocol("Couldn't save the token to the Keychain.")) }
        config.write(id, mapOf(USER_ID to userId, USER to user, TEAM to team))
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        secrets.delete(SECRET_KEY)
        config.write(id, null)
        markNotConfigured()
    }

    override suspend fun poll(since: Instant): Result<List<IncomingItem>> {
        val token = secrets.get(SECRET_KEY) ?: return Result.failure(SourceFailure.NotConfigured())
        return record(runCatching {
            val userId = config.read(id)[USER_ID] ?: authTest(token).first.also { config.write(id, config.read(id) + (USER_ID to it)) }
            val mentions = search(token, "<@$userId>", since, mention = true)
            val dms = search(token, "to:me", since, mention = false)
            (mentions + dms).distinctBy { it.externalId }.filter { it.isDirect || it.mentionsMe }
        }) { "Checked — ${it.size} new message(s)" }
    }

    private suspend fun authTest(token: String): Triple<String, String, String> {
        val body = call(http.request("$baseUrl/auth.test").header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.noBody()).build())
        return Triple(body.str("user_id") ?: throw SourceFailure.Protocol("Slack did not return a user id."), body.str("user").orEmpty(), body.str("team").orEmpty())
    }

    private suspend fun search(token: String, query: String, since: Instant, mention: Boolean): List<IncomingItem> {
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        val body = call(http.request("$baseUrl/search.messages?query=$q&sort=timestamp&sort_dir=desc&count=30")
            .header("Authorization", "Bearer $token").GET().build())
        val matches = body.obj("messages")?.get("matches")?.jsonArray.orEmpty()
        return matches.mapNotNull { m -> parse(m, since, mention) }
    }

    private fun parse(el: JsonElement, since: Instant, mention: Boolean): IncomingItem? {
        val m = el.jsonObject
        val ts = m.str("ts") ?: return null
        val at = ts.toBigDecimalOrNull()?.let { Instant.ofEpochMilli(it.movePointRight(3).toLong()) } ?: return null
        if (!at.isAfter(since)) return null
        val channel = m.obj("channel") ?: JsonObject(emptyMap())
        val isIm = channel.bool("is_im") || channel.bool("is_mpim")
        val text = m.str("text").orEmpty()
        return IncomingItem(
            externalId = "${channel.str("id").orEmpty()}:$ts",
            kind = if (isIm) ItemKind.DIRECT else ItemKind.MENTION,
            sender = m.str("username") ?: m.str("user") ?: "Slack",
            channel = if (isIm) "Direct message" else "#" + (channel.str("name") ?: "channel"),
            title = text.lineSequence().firstOrNull().orEmpty().truncate(120).ifBlank { "New Slack message" },
            snippet = text.truncate(280),
            url = m.str("permalink"),
            receivedAt = at,
            mentionsMe = mention || text.contains("<@"),
            isDirect = isIm,
            isBot = m.str("bot_id") != null || m.str("subtype") == "bot_message",
        )
    }

    /** Slack returns HTTP 200 with ok=false for most API errors; map those onto our failures. */
    private suspend fun call(request: HttpRequest): JsonObject {
        val resp = http.send(request)
        http.checkStatus(resp)
        val body = http.parse(resp.body()).jsonObject
        if (body.bool("ok")) return body
        throw when (val err = body.str("error")) {
            "invalid_auth", "not_authed", "token_revoked", "token_expired", "account_inactive" -> SourceFailure.AuthFailed()
            "missing_scope", "no_permission", "not_allowed_token_type" -> SourceFailure.PermissionDenied("The Slack token needs the search:read user scope.")
            "ratelimited" -> SourceFailure.RateLimited(java.time.Duration.ofSeconds(60))
            else -> SourceFailure.Protocol("Slack error: ${err ?: "unknown"}")
        }
    }

    companion object {
        const val ID = "slack"
        const val TOKEN = "token"
        const val USER_ID = "userId"
        const val USER = "user"
        const val TEAM = "team"
        const val SECRET_KEY = "integration.slack.token"
    }
}
