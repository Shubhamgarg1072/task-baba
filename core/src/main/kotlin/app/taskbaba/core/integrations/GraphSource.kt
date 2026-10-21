package app.taskbaba.core.integrations

import app.taskbaba.core.platform.SecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Microsoft 365 (Outlook mail, Teams chats, calendar) via Microsoft Graph, signed in with the OAuth 2.0
 * device authorization grant against the user's own Azure app registration (public client, no secret).
 */
class GraphSource(
    private val secrets: SecretStore,
    private val config: SourceConfigAccess,
    private val http: HttpSupport = HttpSupport(),
    private val loginBase: String = "https://login.microsoftonline.com",
    private val graphBase: String = "https://graph.microsoft.com/v1.0",
    clock: () -> Instant = Instant::now,
    /** Injected so tests don't wait real seconds between token polls. */
    private val sleep: suspend (Duration) -> Unit = { delay(it.toMillis()) },
) : BaseSource(clock), DeviceCodeLogin {
    override val id = ID
    override val displayName = "Microsoft 365 (Teams, Outlook, Calendar)"
    override val description = "Unread important mail, recent Teams chat messages, and meetings in the next 2 hours."
    override val capabilities = SourceCapabilities(canOpenOriginal = true, usesDeviceCodeLogin = true)
    override val configFields = listOf(
        ConfigField(CLIENT_ID, "Application (client) ID", "00000000-0000-0000-0000-000000000000"),
        ConfigField(TENANT, "Tenant", "organizations (or your tenant ID)", optional = true),
    )
    override val setupHelp = """
        Microsoft requires an app registration you control:
        1. In https://entra.microsoft.com → App registrations → New registration. Name it "Task Baba",
           supported accounts: your organization (or any org + personal accounts).
        2. Authentication → Advanced settings → enable "Allow public client flows" (device code). No secret needed.
        3. API permissions → Microsoft Graph → Delegated: offline_access, User.Read, Mail.Read, Chat.Read,
           Calendars.Read. Some organizations require an admin to grant consent for Chat.Read.
        4. Paste the Application (client) ID here, save, then choose "Sign in" and enter the code shown.
        Task Baba only reads; it never sends mail, replies in Teams, or marks anything read.
        Teams: only the latest message of each recent chat is checked (Graph's chat list preview).
    """.trimIndent()

    private val tokenLock = Mutex()
    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAt: Instant = Instant.EPOCH

    init {
        if (isConfigured()) initStatus()
        else if (config.read(id)[CLIENT_ID] != null) mutableStatus.value = SourceStatus(SourceState.NOT_CONFIGURED, "Saved — sign in to finish connecting.")
    }

    private val scopes = "offline_access User.Read Mail.Read Chat.Read Calendars.Read"
    private fun tenant() = config.read(id)[TENANT]?.takeIf { it.isNotBlank() } ?: "organizations"
    private fun clientId() = config.read(id)[CLIENT_ID]

    override fun isConfigured() = clientId() != null && secrets.get(SECRET_REFRESH) != null
    override fun currentConfig(): Map<String, String> = config.read(id)

    override suspend fun configure(values: Map<String, String>): Result<Unit> {
        val clientId = values[CLIENT_ID].orEmpty().trim()
        if (!GUID.matches(clientId)) return Result.failure(SourceFailure.Protocol("The client ID should look like 0f1e2d3c-…-… (a GUID)."))
        val tenant = values[TENANT].orEmpty().trim().ifBlank { "organizations" }
        if (!TENANT_RE.matches(tenant)) return Result.failure(SourceFailure.Protocol("Tenant must be organizations, common, consumers, a domain, or a GUID."))
        val previous = config.read(id)
        if (previous[CLIENT_ID] != clientId || previous[TENANT] != tenant) {
            secrets.delete(SECRET_REFRESH); accessToken = null
        }
        config.write(id, previous + mapOf(CLIENT_ID to clientId, TENANT to tenant))
        mutableStatus.value = SourceStatus(SourceState.NOT_CONFIGURED, "Saved — sign in to finish connecting.", clock())
        return Result.success(Unit)
    }

    override suspend fun startLogin(): Result<DeviceCodePrompt> {
        val clientId = clientId() ?: return Result.failure(SourceFailure.NotConfigured("Save the client ID first."))
        markConnecting()
        return runCatching {
            val body = form("$loginBase/${tenant()}/oauth2/v2.0/devicecode", mapOf("client_id" to clientId, "scope" to scopes))
            body.str("error")?.let { throw oauthError(it) }
            DeviceCodePrompt(
                userCode = body.str("user_code") ?: throw SourceFailure.Protocol("Microsoft did not return a code."),
                verificationUri = body.str("verification_uri") ?: "https://microsoft.com/devicelogin",
                message = body.str("message").orEmpty(),
                deviceCode = body.str("device_code") ?: throw SourceFailure.Protocol("Microsoft did not return a device code."),
                intervalSeconds = body.str("interval")?.toLongOrNull() ?: 5,
                expiresAt = clock().plusSeconds(body.str("expires_in")?.toLongOrNull() ?: 900),
            )
        }.onFailure { e -> record(Result.failure<Unit>(e)) { "" } }
    }

    override suspend fun awaitLogin(prompt: DeviceCodePrompt): Result<Unit> {
        val clientId = clientId() ?: return Result.failure(SourceFailure.NotConfigured())
        var interval = Duration.ofSeconds(prompt.intervalSeconds.coerceAtLeast(1))
        val result: Result<Unit> = runCatching {
            while (true) {
                if (clock().isAfter(prompt.expiresAt)) throw SourceFailure.AuthFailed("The sign-in code expired. Start again.")
                sleep(interval)
                val body = form("$loginBase/${tenant()}/oauth2/v2.0/token", mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to clientId,
                    "device_code" to prompt.deviceCode,
                ))
                when (val err = body.str("error")) {
                    null -> { storeTokens(body); break }
                    "authorization_pending" -> continue
                    "slow_down" -> interval = interval.plusSeconds(5)
                    else -> throw oauthError(err)
                }
            }
            me()
            Unit
        }
        return record(result) { "Signed in to Microsoft 365" }
    }

    override suspend fun disconnect() {
        secrets.delete(SECRET_REFRESH)
        accessToken = null
        config.write(id, null)
        markNotConfigured()
    }

    override suspend fun poll(since: Instant): Result<List<IncomingItem>> {
        if (!isConfigured()) return Result.failure(SourceFailure.NotConfigured())
        return record(runCatching {
            val myId = config.read(id)[MY_ID] ?: me()
            mail(since) + chats(since, myId) + events()
        }) { "Checked — ${it.size} new item(s)" }
    }

    // --- Graph calls -------------------------------------------------------------------------------

    private suspend fun me(): String {
        val body = get("$graphBase/me?\$select=id,displayName")
        val myId = body.str("id") ?: throw SourceFailure.Protocol("Graph did not return your user id.")
        config.write(id, config.read(id) + (MY_ID to myId))
        return myId
    }

    private suspend fun mail(since: Instant): List<IncomingItem> {
        val filter = enc("isRead eq false and receivedDateTime ge ${ISO.format(since.atOffset(ZoneOffset.UTC))}")
        val body = get("$graphBase/me/mailFolders/inbox/messages?\$filter=$filter&\$orderby=receivedDateTime%20desc&\$top=25" +
            "&\$select=id,subject,from,receivedDateTime,importance,bodyPreview,webLink")
        return body["value"]?.jsonArray.orEmpty().mapNotNull { el ->
            val m = el.jsonObject
            val from = m.obj("from")?.obj("emailAddress")
            val address = from?.str("address").orEmpty()
            IncomingItem(
                externalId = "mail:" + (m.str("id") ?: return@mapNotNull null),
                kind = ItemKind.EMAIL,
                sender = from?.str("name") ?: address.ifBlank { "Outlook" },
                channel = "Outlook",
                title = (m.str("subject") ?: "(no subject)").truncate(200),
                snippet = m.str("bodyPreview").orEmpty().stripHtml().truncate(280),
                url = m.str("webLink"),
                receivedAt = m.str("receivedDateTime")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: clock(),
                isBot = BOT_SENDER.containsMatchIn(address),
                sourceMarkedImportant = m.str("importance") == "high",
            )
        }
    }

    private suspend fun chats(since: Instant, myId: String): List<IncomingItem> {
        val body = get("$graphBase/me/chats?\$expand=lastMessagePreview&\$top=25")
        return body["value"]?.jsonArray.orEmpty().mapNotNull { el ->
            val chat = el.jsonObject
            val msg = chat.obj("lastMessagePreview") ?: return@mapNotNull null
            val at = msg.str("createdDateTime")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
            if (!at.isAfter(since)) return@mapNotNull null
            val fromUser = msg.obj("from")?.obj("user")
            if (fromUser?.str("id") == myId) return@mapNotNull null
            val isDirect = chat.str("chatType") == "oneOnOne"
            val text = msg.obj("body")?.str("content").orEmpty().stripHtml()
            IncomingItem(
                externalId = "chat:${chat.str("id")}:${msg.str("id")}",
                kind = if (isDirect) ItemKind.DIRECT else ItemKind.MESSAGE,
                sender = fromUser?.str("displayName") ?: "Teams",
                channel = if (isDirect) "Teams chat" else chat.str("topic") ?: "Teams group chat",
                title = text.truncate(120).ifBlank { "New Teams message" },
                snippet = text.truncate(280),
                url = chat.str("webUrl"),
                receivedAt = at,
                isDirect = isDirect,
                mentionsMe = false,
                isBot = msg.obj("from")?.obj("application") != null,
            )
        }
    }

    private suspend fun events(): List<IncomingItem> {
        val now = clock()
        val start = enc(ISO.format(now.atOffset(ZoneOffset.UTC)))
        val end = enc(ISO.format(now.plus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC)))
        val body = get("$graphBase/me/calendarView?startDateTime=$start&endDateTime=$end&\$top=10" +
            "&\$select=id,subject,start,organizer,webLink,isCancelled", prefer = "outlook.timezone=\"UTC\"")
        return body["value"]?.jsonArray.orEmpty().mapNotNull { el ->
            val e = el.jsonObject
            if (e.bool("isCancelled")) return@mapNotNull null
            val startAt = e.obj("start")?.str("dateTime")?.let { runCatching { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC) }.getOrNull() } ?: return@mapNotNull null
            val eventId = e.str("id") ?: return@mapNotNull null
            IncomingItem(
                externalId = "event:$eventId:${startAt.toEpochMilli()}",
                kind = ItemKind.EVENT,
                sender = e.obj("organizer")?.obj("emailAddress")?.str("name") ?: "Calendar",
                channel = "Calendar",
                title = (e.str("subject") ?: "Meeting").truncate(200),
                snippet = "Starts ${DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(startAt)}",
                url = e.str("webLink"),
                receivedAt = startAt,
            )
        }
    }

    private suspend fun get(url: String, prefer: String? = null): JsonObject {
        suspend fun attempt(token: String) = http.send(http.request(url).header("Authorization", "Bearer $token")
            .apply { if (prefer != null) header("Prefer", prefer) }.GET().build())
        var resp = attempt(token(forceRefresh = false))
        if (resp.statusCode() == 401) resp = attempt(token(forceRefresh = true))
        if (resp.statusCode() == 403) throw SourceFailure.PermissionDenied("Microsoft 365 refused access. An admin may need to grant consent for Mail.Read, Chat.Read, or Calendars.Read.")
        http.checkStatus(resp)
        return http.parse(resp.body()).jsonObject
    }

    private suspend fun token(forceRefresh: Boolean): String = tokenLock.withLock {
        val cached = accessToken
        if (!forceRefresh && cached != null && clock().isBefore(accessExpiresAt.minusSeconds(60))) return cached
        val refresh = secrets.get(SECRET_REFRESH) ?: throw SourceFailure.NotConfigured("Sign in to Microsoft 365.")
        val body = form("$loginBase/${tenant()}/oauth2/v2.0/token", mapOf(
            "grant_type" to "refresh_token", "client_id" to (clientId() ?: throw SourceFailure.NotConfigured()),
            "refresh_token" to refresh, "scope" to scopes,
        ))
        body.str("error")?.let { err ->
            if (err == "invalid_grant") secrets.delete(SECRET_REFRESH)
            throw oauthError(err)
        }
        storeTokens(body)
        accessToken ?: throw SourceFailure.Protocol("Microsoft did not return an access token.")
    }

    private fun storeTokens(body: JsonObject) {
        accessToken = body.str("access_token") ?: throw SourceFailure.Protocol("Microsoft did not return an access token.")
        accessExpiresAt = clock().plusSeconds(body.str("expires_in")?.toLongOrNull() ?: 3600)
        body.str("refresh_token")?.let { rt ->
            secrets.set(SECRET_REFRESH, rt).onFailure { throw SourceFailure.Protocol("Couldn't save the sign-in to the Keychain.") }
        }
    }

    /** OAuth endpoints return 400 with a JSON error for pending/declined states, so don't treat 400 as fatal. */
    private suspend fun form(url: String, fields: Map<String, String>): JsonObject {
        val payload = fields.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val resp = http.send(http.request(url).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build())
        if (resp.statusCode() == 400 || resp.statusCode() == 401) {
            val body = http.parse(resp.body()).jsonObject
            if (body.str("error") != null) return body
        }
        http.checkStatus(resp)
        return http.parse(resp.body()).jsonObject
    }

    private fun oauthError(code: String): SourceFailure = when (code) {
        "authorization_declined", "access_denied" -> SourceFailure.AuthFailed("Sign-in was declined.")
        "expired_token", "bad_verification_code" -> SourceFailure.AuthFailed("The sign-in code expired. Start again.")
        "invalid_grant", "interaction_required" -> SourceFailure.AuthFailed("Your Microsoft sign-in expired. Sign in again.")
        "invalid_client", "unauthorized_client" -> SourceFailure.Protocol("Microsoft rejected the client ID. Check the app registration and enable public client flows.")
        "consent_required" -> SourceFailure.PermissionDenied("Consent is required for the requested permissions. Ask your admin.")
        else -> SourceFailure.Protocol("Microsoft sign-in error: $code")
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    companion object {
        const val ID = "microsoft365"
        const val CLIENT_ID = "clientId"
        const val TENANT = "tenant"
        const val MY_ID = "userId"
        const val SECRET_REFRESH = "integration.microsoft365.refresh_token"
        private val GUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val TENANT_RE = Regex("^(organizations|common|consumers|[A-Za-z0-9.-]{3,100})$")
        private val BOT_SENDER = Regex("(?i)(no-?reply|newsletter|notifications?@|mailer-daemon|digest@)")
        private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    }
}
