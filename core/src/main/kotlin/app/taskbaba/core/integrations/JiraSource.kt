package app.taskbaba.core.integrations

import app.taskbaba.core.platform.SecretStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64

/**
 * Jira Cloud via the official REST API v3 with an Atlassian API token (Basic auth: email + token).
 * Polls issues assigned to, reported by, or watched by the current user that changed since the last poll.
 */
class JiraSource(
    private val secrets: SecretStore,
    private val config: SourceConfigAccess,
    private val http: HttpSupport = HttpSupport(),
    clock: () -> Instant = Instant::now,
) : BaseSource(clock) {
    override val id = ID
    override val displayName = "Jira Cloud"
    override val description = "Issues assigned to you, reported by you, or that you watch."
    override val capabilities = SourceCapabilities(canOpenOriginal = true)
    override val configFields = listOf(
        ConfigField(SITE, "Site URL", "https://your-team.atlassian.net"),
        ConfigField(EMAIL, "Atlassian account email", "you@company.com"),
        ConfigField(TOKEN, "API token", "Paste the token — stored in your Keychain", secret = true),
    )
    override val setupHelp = """
        1. Open https://id.atlassian.com/manage-profile/security/api-tokens and choose "Create API token".
        2. Name it "Task Baba" and copy the token.
        3. Enter your Jira site URL, the email you sign in with, and the token here.
        The token is stored in the macOS Keychain. Task Baba only reads issues; it never changes them.
        Note: Jira's search API cannot filter "@mentions of me", so mentions in comments on issues you don't
        watch are not included.
    """.trimIndent()

    init { initStatus() }

    override fun isConfigured(): Boolean {
        val c = config.read(id)
        return !c[SITE].isNullOrBlank() && !c[EMAIL].isNullOrBlank() && secrets.get(SECRET_KEY) != null
    }

    override fun currentConfig(): Map<String, String> = config.read(id)

    override suspend fun configure(values: Map<String, String>): Result<Unit> {
        val site = normalizeSite(values[SITE].orEmpty())
            ?: return Result.failure(SourceFailure.Protocol("Enter your Jira site as https://<name>.atlassian.net."))
        val email = values[EMAIL].orEmpty().trim()
        if (!email.contains('@') || email.length > 254) return Result.failure(SourceFailure.Protocol("Enter a valid email address."))
        val token = values[TOKEN].orEmpty().trim()
        if (token.length < 8) return Result.failure(SourceFailure.Protocol("Paste the full API token."))
        markConnecting()
        val verified = record(runCatching { myself(site, email, token) }) { "Connected as $it" }
        if (verified.isFailure) return verified.map { }
        secrets.set(SECRET_KEY, token).onFailure { return Result.failure(SourceFailure.Protocol("Couldn't save the token to the Keychain.")) }
        config.write(id, mapOf(SITE to site, EMAIL to email))
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        secrets.delete(SECRET_KEY)
        config.write(id, null)
        markNotConfigured()
    }

    override suspend fun poll(since: Instant): Result<List<IncomingItem>> {
        val c = config.read(id)
        val site = c[SITE]; val email = c[EMAIL]; val token = secrets.get(SECRET_KEY)
        if (site == null || email == null || token == null) return Result.failure(SourceFailure.NotConfigured())
        return record(runCatching { search(site, email, token, since) }) { "Checked — ${it.size} new update(s)" }
    }

    private suspend fun myself(site: String, email: String, token: String): String {
        val body = http.sendJson(http.request("$site/rest/api/3/myself").header("Authorization", basic(email, token)).GET().build())
        return body.jsonObject["displayName"]?.jsonPrimitive?.contentOrNull ?: email
    }

    private suspend fun search(site: String, email: String, token: String, since: Instant): List<IncomingItem> {
        // Relative JQL dates avoid depending on the Jira profile's time zone.
        val minutes = Duration.between(since, clock()).toMinutes().coerceIn(1, 7L * 24 * 60)
        val jql = "(assignee = currentUser() OR reporter = currentUser() OR watcher = currentUser()) " +
            "AND updated >= -${minutes}m ORDER BY updated DESC"
        val payload = buildJsonObject {
            put("jql", jql)
            put("maxResults", 50)
            putJsonArray("fields") { listOf("summary", "updated", "priority", "status", "assignee", "reporter", "project").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
        val req = http.request("$site/rest/api/3/search/jql")
            .header("Authorization", basic(email, token))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
        val body = http.sendJson(req).jsonObject
        val me = email.lowercase()
        return body["issues"]?.jsonArray.orEmpty().mapNotNull { el ->
            val issue = el.jsonObject
            val key = issue.str("key") ?: return@mapNotNull null
            val f = issue["fields"]?.jsonObject ?: JsonObject(emptyMap())
            val updated = f.str("updated")?.let(::parseJiraTime) ?: clock()
            val priority = f.obj("priority")?.str("name")
            val status = f.obj("status")?.str("name")
            val assigneeEmail = f.obj("assignee")?.str("emailAddress")?.lowercase()
            IncomingItem(
                externalId = "$key@${updated.toEpochMilli()}",
                kind = ItemKind.ISSUE,
                sender = f.obj("reporter")?.str("displayName") ?: "Jira",
                channel = f.obj("project")?.str("key") ?: key.substringBefore('-'),
                title = "$key: ${f.str("summary").orEmpty()}".truncate(200),
                snippet = listOfNotNull(status?.let { "Status: $it" }, priority?.let { "Priority: $it" }).joinToString(" · "),
                url = "$site/browse/$key",
                receivedAt = updated,
                isDirect = assigneeEmail != null && assigneeEmail == me,
                sourceMarkedImportant = priority in setOf("Highest", "Blocker", "Critical"),
            )
        }
    }

    companion object {
        const val ID = "jira"
        const val SITE = "site"
        const val EMAIL = "email"
        const val TOKEN = "token"
        const val SECRET_KEY = "integration.jira.token"

        private val JIRA_TIME: DateTimeFormatter = DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME).appendPattern("[XX][XXX]").toFormatter()

        fun parseJiraTime(s: String): Instant? = runCatching { OffsetDateTime.parse(s, JIRA_TIME).toInstant() }.getOrNull()

        fun normalizeSite(raw: String): String? {
            val s = raw.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            // HTTPS only, except loopback so tests can use a local mock server.
            val loopback = uri.host == "127.0.0.1" || uri.host == "localhost"
            if (uri.host.isNullOrBlank() || (uri.scheme != "https" && !(loopback && uri.scheme == "http"))) return null
            return s
        }

        private fun basic(email: String, token: String) =
            "Basic " + Base64.getEncoder().encodeToString("$email:$token".toByteArray())
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "true"
