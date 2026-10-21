package app.taskbaba.core.integrations

import app.taskbaba.core.platform.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import java.net.ServerSocket
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdapterTest {
    private val server = MockServer()
    private val secrets = InMemorySecretStore()
    private val config = MapConfig()
    private val now = Instant.parse("2026-10-21T05:00:00Z")

    @AfterTest fun stop() = server.close()

    // --- Jira ---------------------------------------------------------------------------------------

    private fun jira() = JiraSource(secrets, config, clock = { now })

    @Test fun `jira connects, stores token in secret store only, and parses issues`() = runTest {
        server.route("/rest/api/3/myself") { ok("""{"displayName":"Asha"}""") }
        server.route("/rest/api/3/search/jql") {
            ok("""{"issues":[{"key":"PAY-12","fields":{"summary":"Checkout fails","updated":"2026-10-21T10:15:00.000+0530",
               "priority":{"name":"Highest"},"status":{"name":"In Progress"},"assignee":{"emailAddress":"a@x.com"},
               "reporter":{"displayName":"Ravi"},"project":{"key":"PAY"}}}]}""")
        }
        server.start()
        val src = jira()
        val r = src.configure(mapOf("site" to server.base, "email" to "a@x.com", "token" to "secret-token-123"))
        assertTrue(r.isSuccess, r.exceptionOrNull()?.message)
        assertEquals(SourceState.CONNECTED, src.status.value.state)
        assertEquals("secret-token-123", secrets.get(JiraSource.SECRET_KEY))
        assertFalse(config.read("jira").values.any { it.contains("secret-token") }, "token must not be in plain settings")

        val items = src.poll(now.minusSeconds(3600)).getOrThrow()
        assertEquals(1, items.size)
        val i = items.single()
        assertEquals("PAY-12: Checkout fails", i.title)
        assertEquals("${server.base}/browse/PAY-12", i.url)
        assertTrue(i.isDirect)
        assertTrue(i.sourceMarkedImportant)
        val req = server.requests.last { it.path.endsWith("/search/jql") }
        assertTrue(req.headers["authorization"]!!.startsWith("Basic "))
        assertTrue(req.body.contains("updated >= -60m"))
    }

    @Test fun `jira 401 maps to AUTH_FAILED and nothing is saved`() = runTest {
        server.route("/rest/api/3/myself") { Triple(401, "{}", emptyMap()) }
        server.start()
        val src = jira()
        val r = src.configure(mapOf("site" to server.base, "email" to "a@x.com", "token" to "bad-token-123"))
        assertIs<SourceFailure.AuthFailed>(r.exceptionOrNull())
        assertEquals(SourceState.AUTH_FAILED, src.status.value.state)
        assertNull(secrets.get(JiraSource.SECRET_KEY))
        assertFalse(src.isConfigured())
    }

    @Test fun `jira 403 on search maps to PERMISSION_DENIED, 500 to ERROR`() = runTest {
        var code = 403
        server.route("/rest/api/3/myself") { ok("""{"displayName":"Asha"}""") }
        server.route("/rest/api/3/search/jql") { Triple(code, "{}", emptyMap()) }
        server.start()
        val src = jira()
        src.configure(mapOf("site" to server.base, "email" to "a@x.com", "token" to "token-12345")).getOrThrow()
        assertIs<SourceFailure.PermissionDenied>(src.poll(now).exceptionOrNull())
        assertEquals(SourceState.PERMISSION_DENIED, src.status.value.state)
        code = 500
        assertIs<SourceFailure.Server>(src.poll(now).exceptionOrNull())
        assertEquals(SourceState.ERROR, src.status.value.state)
    }

    @Test fun `jira rejects non-https sites`() = runTest {
        assertNull(JiraSource.normalizeSite("http://evil.example.com"))
        assertEquals("https://team.atlassian.net", JiraSource.normalizeSite("team.atlassian.net/"))
        val r = jira().configure(mapOf("site" to "ftp://x", "email" to "a@x.com", "token" to "token-12345"))
        assertTrue(r.isFailure)
    }

    @Test fun `unconfigured poll fails without network`() = runTest {
        assertIs<SourceFailure.NotConfigured>(jira().poll(now).exceptionOrNull())
    }

    // --- Slack --------------------------------------------------------------------------------------

    private fun slack(base: String = server.base) = SlackSource(secrets, config, baseUrl = base, clock = { now })

    @Test fun `slack connects and returns only mentions and DMs after since`() = runTest {
        server.route("/auth.test") { ok("""{"ok":true,"user_id":"U1","user":"asha","team":"Acme"}""") }
        server.route("/search.messages") { req ->
            if (req.query!!.contains("to%3Ame")) ok("""{"ok":true,"messages":{"matches":[
                {"ts":"1792558000.000100","text":"hey, quick q","username":"ravi","channel":{"id":"D1","is_im":true},"permalink":"https://acme.slack.com/p1"}]}}""")
            else ok("""{"ok":true,"messages":{"matches":[
                {"ts":"1792558100.000200","text":"<@U1> can you review?","username":"meera","channel":{"id":"C1","name":"eng"},"permalink":"https://acme.slack.com/p2"},
                {"ts":"1000.0","text":"<@U1> ancient","username":"old","channel":{"id":"C1","name":"eng"}}]}}""")
        }
        server.start()
        val src = slack()
        src.configure(mapOf("token" to "xoxp-123")).getOrThrow()
        val items = src.poll(Instant.ofEpochSecond(1792550000)).getOrThrow()
        assertEquals(2, items.size)
        val dm = items.first { it.isDirect }
        assertEquals(ItemKind.DIRECT, dm.kind)
        val mention = items.first { it.mentionsMe && !it.isDirect }
        assertEquals("#eng", mention.channel)
        assertEquals("C1:1792558100.000200", mention.externalId)
        assertTrue(server.requests.all { it.headers["authorization"] == "Bearer xoxp-123" })
    }

    @Test fun `slack ok=false errors map to auth and permission failures`() = runTest {
        var err = "invalid_auth"
        server.route("/auth.test") { ok("""{"ok":false,"error":"$err"}""") }
        server.start()
        val src = slack()
        assertIs<SourceFailure.AuthFailed>(src.configure(mapOf("token" to "xoxp-1")).exceptionOrNull())
        assertEquals(SourceState.AUTH_FAILED, src.status.value.state)
        err = "missing_scope"
        assertIs<SourceFailure.PermissionDenied>(src.configure(mapOf("token" to "xoxp-1")).exceptionOrNull())
        assertEquals(SourceState.PERMISSION_DENIED, src.status.value.state)
    }

    @Test fun `slack 429 carries Retry-After`() = runTest {
        server.route("/auth.test") { Triple(429, "", mapOf("Retry-After" to "7")) }
        server.start()
        val f = slack().configure(mapOf("token" to "xoxp-1")).exceptionOrNull()
        assertIs<SourceFailure.RateLimited>(f)
        assertEquals(7, f.retryAfter.seconds)
    }

    @Test fun `connection refused maps to OFFLINE without crashing`() = runTest {
        val port = ServerSocket(0).use { it.localPort } // closed again: nothing listens there
        secrets.set(SlackSource.SECRET_KEY, "xoxp-1")
        config.write("slack", mapOf("userId" to "U1"))
        val src = slack("http://127.0.0.1:$port")
        assertIs<SourceFailure.Offline>(src.poll(now).exceptionOrNull())
        assertEquals(SourceState.OFFLINE, src.status.value.state)
    }

    // --- Microsoft Graph ----------------------------------------------------------------------------

    private fun graph() = GraphSource(secrets, config, loginBase = server.base, graphBase = server.base + "/v1.0", clock = { now }, sleep = {})
    private val clientId = "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b"

    private fun deviceCodeRoute() = server.route("/organizations/oauth2/v2.0/devicecode") {
        ok("""{"user_code":"ABCD-EFGH","device_code":"dev123","verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":5,"message":"Go sign in"}""")
    }

    @Test fun `graph device code - pending, slow_down, then success stores refresh token`() = runTest {
        deviceCodeRoute()
        var calls = 0
        server.route("/organizations/oauth2/v2.0/token") {
            calls++
            when (calls) {
                1 -> Triple(400, """{"error":"authorization_pending"}""", emptyMap())
                2 -> Triple(400, """{"error":"slow_down"}""", emptyMap())
                else -> ok("""{"access_token":"at1","refresh_token":"rt1","expires_in":3600}""")
            }
        }
        server.route("/v1.0/me") { ok("""{"id":"me-1","displayName":"Asha"}""") }
        server.start()
        val src = graph()
        src.configure(mapOf("clientId" to clientId)).getOrThrow()
        assertFalse(src.isConfigured())
        val prompt = src.startLogin().getOrThrow()
        assertEquals("ABCD-EFGH", prompt.userCode)
        assertTrue(src.awaitLogin(prompt).isSuccess)
        assertEquals("rt1", secrets.get(GraphSource.SECRET_REFRESH))
        assertTrue(src.isConfigured())
        assertEquals(SourceState.CONNECTED, src.status.value.state)
    }

    @Test fun `graph device code declined and expired`() = runTest {
        deviceCodeRoute()
        var error = "authorization_declined"
        server.route("/organizations/oauth2/v2.0/token") { Triple(400, """{"error":"$error"}""", emptyMap()) }
        server.start()
        val src = graph()
        src.configure(mapOf("clientId" to clientId)).getOrThrow()
        val declined = src.awaitLogin(src.startLogin().getOrThrow()).exceptionOrNull()
        assertIs<SourceFailure.AuthFailed>(declined)
        assertTrue(declined.message!!.contains("declined"))
        error = "expired_token"
        val expired = src.awaitLogin(src.startLogin().getOrThrow()).exceptionOrNull()
        assertTrue(expired!!.message!!.contains("expired"))
        assertNull(secrets.get(GraphSource.SECRET_REFRESH))
    }

    @Test fun `graph polls mail, chats and events, refreshing the token`() = runTest {
        secrets.set(GraphSource.SECRET_REFRESH, "rt0")
        config.write("microsoft365", mapOf("clientId" to clientId, "tenant" to "organizations", "userId" to "me-1"))
        server.route("/organizations/oauth2/v2.0/token") { ok("""{"access_token":"at2","refresh_token":"rt2","expires_in":3600}""") }
        server.route("/v1.0/me/mailFolders/inbox/messages") {
            ok("""{"value":[{"id":"m1","subject":"Budget","from":{"emailAddress":{"name":"CFO","address":"cfo@x.com"}},
               "receivedDateTime":"2026-10-21T04:50:00Z","importance":"high","bodyPreview":"Need numbers","webLink":"https://outlook/m1"}]}""")
        }
        server.route("/v1.0/me/chats") {
            ok("""{"value":[
              {"id":"c1","chatType":"oneOnOne","webUrl":"https://teams/c1","lastMessagePreview":{"id":"p1","createdDateTime":"2026-10-21T04:55:00Z","body":{"content":"<p>ping</p>"},"from":{"user":{"id":"u2","displayName":"Ravi"}}}},
              {"id":"c2","chatType":"group","lastMessagePreview":{"id":"p2","createdDateTime":"2026-10-21T04:56:00Z","body":{"content":"mine"},"from":{"user":{"id":"me-1","displayName":"Me"}}}}]}""")
        }
        server.route("/v1.0/me/calendarView") {
            ok("""{"value":[{"id":"e1","subject":"Standup","start":{"dateTime":"2026-10-21T05:30:00.0000000"},"organizer":{"emailAddress":{"name":"Lead"}},"webLink":"https://outlook/e1"}]}""")
        }
        server.start()
        val items = graph().poll(now.minusSeconds(3600)).getOrThrow()
        assertEquals(3, items.size, "own chat message excluded")
        assertTrue(items.first { it.kind == ItemKind.EMAIL }.sourceMarkedImportant)
        assertEquals("ping", items.first { it.kind == ItemKind.DIRECT }.title)
        assertEquals(Instant.parse("2026-10-21T05:30:00Z"), items.first { it.kind == ItemKind.EVENT }.receivedAt)
        assertEquals("rt2", secrets.get(GraphSource.SECRET_REFRESH))
        assertTrue(server.requests.filter { it.path.startsWith("/v1.0") }.all { it.headers["authorization"] == "Bearer at2" })
    }

    @Test fun `graph 403 maps to PERMISSION_DENIED`() = runTest {
        secrets.set(GraphSource.SECRET_REFRESH, "rt0")
        config.write("microsoft365", mapOf("clientId" to clientId, "userId" to "me-1"))
        server.route("/organizations/oauth2/v2.0/token") { ok("""{"access_token":"at","expires_in":3600}""") }
        server.route("/v1.0/me/mailFolders/inbox/messages") { Triple(403, "{}", emptyMap()) }
        server.start()
        val src = graph()
        assertIs<SourceFailure.PermissionDenied>(src.poll(now).exceptionOrNull())
        assertEquals(SourceState.PERMISSION_DENIED, src.status.value.state)
    }

    @Test fun `graph rejects invalid client id`() = runTest {
        assertTrue(graph().configure(mapOf("clientId" to "not-a-guid")).isFailure)
    }

    // --- Unsupported --------------------------------------------------------------------------------

    @Test fun `unsupported sources never return data`() = runTest {
        UnsupportedSource.all().forEach { s ->
            assertEquals(SourceState.UNSUPPORTED, s.status.value.state)
            assertIs<SourceFailure.Unsupported>(s.poll(now).exceptionOrNull())
            assertIs<SourceFailure.Unsupported>(s.configure(emptyMap()).exceptionOrNull())
            assertFalse(s.isConfigured())
        }
    }
}
