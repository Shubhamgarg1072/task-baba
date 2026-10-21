package app.taskbaba.core.drive

import app.taskbaba.core.ai.AiSettings
import app.taskbaba.core.ai.MockHttpServer
import app.taskbaba.core.data.DatabaseFactory
import app.taskbaba.core.data.KvStore
import app.taskbaba.core.platform.InMemorySecretStore
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveTest {
    private val server = MockHttpServer()
    private val secrets = InMemorySecretStore()
    private var nowMs = 1_000_000L
    private val oauth = GoogleOAuth(
        secrets,
        endpoints = OAuthEndpoints(
            authorize = "${server.baseUrl}/auth",
            token = "${server.baseUrl}/token",
            revoke = "${server.baseUrl}/revoke",
        ),
        now = { nowMs },
    )
    private val http = HttpClient.newHttpClient()

    @AfterTest fun tearDown() = server.close()

    /** Plays the role of the user's browser: follow the consent URL straight to the loopback redirect. */
    private fun browser(extra: (Map<String, String>) -> String): (String) -> Unit = { url ->
        val q = GoogleOAuth.parseQuery(URI(url).rawQuery)
        assertEquals(GoogleOAuth.SCOPE, q["scope"])
        assertEquals("S256", q["code_challenge_method"])
        assertNotNull(q["code_challenge"])
        val redirect = q.getValue("redirect_uri")
        assertTrue(redirect.startsWith("http://127.0.0.1:"))
        Thread {
            http.send(HttpRequest.newBuilder(URI("$redirect/?${extra(q)}")).GET().build(), HttpResponse.BodyHandlers.discarding())
        }.start()
    }

    private val tokenOk = """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"token_type":"Bearer"}"""

    @Test fun `authorization success stores tokens in the secret store only`() = runBlocking<Unit> {
        server.reply("/token", MockHttpServer.Reply(200, tokenOk))
        oauth.authorize("client-123.apps.googleusercontent.com", "desktop-secret", browser { q -> "code=abc&state=${q["state"]}" })
        assertTrue(oauth.isConnected)
        assertEquals("at-1", oauth.accessToken())
        val body = server.lastBodies.getValue("/token")
        assertTrue(body.contains("code_verifier="))
        assertTrue(body.contains("grant_type=authorization_code"))
        assertEquals("desktop-secret", secrets.get(GoogleOAuth.SECRET_CLIENT_SECRET))
    }

    @Test fun `user denying access is reported as DENIED`() = runBlocking<Unit> {
        val e = assertFailsWith<DriveAuthException> {
            oauth.authorize("client-123", null, browser { q -> "error=access_denied&state=${q["state"]}" })
        }
        assertEquals(DriveAuthException.Reason.DENIED, e.reason)
        assertFalse(oauth.isConnected)
        assertEquals(0, server.count("/token"))
    }

    @Test fun `state mismatch is rejected`() = runBlocking<Unit> {
        server.reply("/token", MockHttpServer.Reply(200, tokenOk))
        val e = assertFailsWith<DriveAuthException> {
            oauth.authorize("client-123", null, browser { _ -> "code=abc&state=forged" })
        }
        assertEquals(DriveAuthException.Reason.MISCONFIGURED, e.reason)
        assertFalse(oauth.isConnected)
    }

    @Test fun `sign-in timeout is reported as cancelled`() = runBlocking<Unit> {
        val e = assertFailsWith<DriveAuthException> { oauth.authorize("client-123", null, { }, Duration.ofMillis(200)) }
        assertEquals(DriveAuthException.Reason.CANCELLED, e.reason)
    }

    @Test fun `expired token refreshes`() = runBlocking<Unit> {
        server.reply("/token", MockHttpServer.Reply(200, tokenOk))
        oauth.authorize("client-123", null, browser { q -> "code=abc&state=${q["state"]}" })
        nowMs += 3_600_000
        server.reply("/token", MockHttpServer.Reply(200, """{"access_token":"at-2","expires_in":3600}"""))
        assertEquals("at-2", oauth.accessToken())
        assertTrue(server.lastBodies.getValue("/token").contains("refresh_token=rt-1"))
        nowMs += 3_600_000
        server.reply("/token", MockHttpServer.Reply(200, """{"access_token":"at-3","expires_in":3600}"""))
        assertEquals("at-3", oauth.accessToken(), "refresh token is kept when Google omits a new one")
    }

    @Test fun `refresh failure clears tokens and reports EXPIRED`() = runBlocking<Unit> {
        server.reply("/token", MockHttpServer.Reply(200, tokenOk))
        oauth.authorize("client-123", null, browser { q -> "code=abc&state=${q["state"]}" })
        nowMs += 3_600_000
        server.reply("/token", MockHttpServer.Reply(400, """{"error":"invalid_grant"}"""))
        val e = assertFailsWith<DriveAuthException> { oauth.accessToken() }
        assertEquals(DriveAuthException.Reason.EXPIRED, e.reason)
        assertFalse(oauth.isConnected)
        assertNull(secrets.get(GoogleOAuth.SECRET_TOKENS))
    }

    @Test fun `disconnect revokes and forgets tokens`() = runBlocking<Unit> {
        server.reply("/token", MockHttpServer.Reply(200, tokenOk))
        server.reply("/revoke", MockHttpServer.Reply(200))
        oauth.authorize("client-123", null, browser { q -> "code=abc&state=${q["state"]}" })
        oauth.disconnect()
        assertFalse(oauth.isConnected)
        assertEquals(1, server.count("/revoke"))
    }

    // ---- verifier ----

    private val spec = ModelCatalog.QWEN3_4B_Q4KM
    private fun file(name: String = spec.fileName, size: Long? = spec.sizeBytes, sha: String? = spec.sha256, trashed: Boolean = false) =
        DriveFile(id = name, name = name, size = size?.toString(), sha256Checksum = sha, trashed = trashed)

    @Test fun `verifier states`() {
        assertEquals(ModelFileState.MODEL_MISSING, DriveModelVerifier.check(spec, null).state)
        assertEquals(ModelFileState.MODEL_MISSING, DriveModelVerifier.check(spec, file(trashed = true)).state)
        assertEquals(ModelFileState.INCOMPLETE, DriveModelVerifier.check(spec, file(size = 1_000)).state)
        assertEquals(ModelFileState.CHECKSUM_PENDING, DriveModelVerifier.check(spec, file(sha = null)).state)
        assertEquals(ModelFileState.CHECKSUM_MISMATCH, DriveModelVerifier.check(spec, file(sha = "0".repeat(64))).state)
        assertEquals(ModelFileState.VERIFIED, DriveModelVerifier.check(spec, file(sha = spec.sha256.uppercase())).state)
    }

    @Test fun `report notices partial downloads and verified backups`() {
        val partial = DriveModelVerifier.report(spec, listOf(file(name = spec.fileName + ".part", size = 10)), emptyList())
        assertEquals(ModelFileState.MODEL_MISSING, partial.state)
        assertNotNull(partial.partialFile)
        assertTrue(partial.message!!.contains("in progress"))

        val corruptWithBackup = DriveModelVerifier.report(spec, listOf(file(sha = "f".repeat(64))), listOf(file()))
        assertEquals(ModelFileState.CHECKSUM_MISMATCH, corruptWithBackup.state)
        assertEquals(ModelFileState.VERIFIED, corruptWithBackup.backups.single().state)
        assertTrue(corruptWithBackup.message!!.contains("Restore"))
    }

    // ---- service against a mock Drive API ----

    private fun service(): DriveModelService {
        val kv = KvStore(DatabaseFactory.inMemory())
        val settings = kv.state(AiSettings.KEY, AiSettings.serializer()) { AiSettings() }
        val client = DriveClient(oauth, baseUrl = "${server.baseUrl}/drive/v3")
        return DriveModelService(settings, oauth, client, browser { q -> "code=abc&state=${q["state"]}" })
    }

    private fun filesJson(vararg f: DriveFile) =
        """{"files":[${f.joinToString(",") { """{"id":"${it.id}","name":"${it.name}","mimeType":"${it.mimeType}"${it.size?.let { s -> ",\"size\":\"$s\"" } ?: ""}${it.sha256Checksum?.let { s -> ",\"sha256Checksum\":\"$s\"" } ?: ""}}""" }}]}"""

    @Test fun `service verifies the model in the chosen folder`() = runBlocking<Unit> {
        server.reply("/token", MockHttpServer.Reply(200, tokenOk))
        server.reply("/drive/v3/files/folder-1", MockHttpServer.Reply(200, """{"id":"folder-1","name":"models","mimeType":"${DriveFile.FOLDER_MIME}"}"""))
        server.route("/drive/v3/files") { ex, _ ->
            val q = GoogleOAuth.parseQuery(ex.requestURI.rawQuery).getValue("q")
            when {
                q.contains("'folder-1' in parents") -> MockHttpServer.Reply(200, filesJson(
                    file(), DriveFile("backup-id", "backup", DriveFile.FOLDER_MIME),
                ))
                q.contains("'backup-id' in parents") -> MockHttpServer.Reply(200, filesJson(file(sha = null)))
                else -> MockHttpServer.Reply(200, filesJson(DriveFile("folder-1", "models", DriveFile.FOLDER_MIME)))
            }
        }
        val svc = service()
        assertEquals(ModelFileState.NOT_CONNECTED, svc.report.value.state)
        svc.connect("client-123", null).getOrThrow()
        assertEquals(ModelFileState.FOLDER_NOT_SELECTED, svc.report.value.state)
        val folders = svc.listFolders("models").getOrThrow()
        svc.selectFolder(folders.single()).getOrThrow()
        val r = svc.report.value
        assertEquals(ModelFileState.VERIFIED, r.state)
        assertEquals(ModelFileState.CHECKSUM_PENDING, r.backups.single().state)
        assertEquals("Bearer at-1", server.lastHeaders["/drive/v3/files"]?.get("Authorization")?.single())
    }

    @Test fun `deleted folder and missing metadata are reported`() = runBlocking<Unit> {
        server.reply("/token", MockHttpServer.Reply(200, tokenOk))
        server.reply("/drive/v3/files/folder-1", MockHttpServer.Reply(200, """{"id":"folder-1","name":"models","mimeType":"${DriveFile.FOLDER_MIME}"}"""))
        server.reply("/drive/v3/files", MockHttpServer.Reply(200, """{"files":[]}"""))
        val svc = service()
        svc.connect("client-123", null).getOrThrow()
        svc.selectFolder(DriveFile("folder-1", "models", DriveFile.FOLDER_MIME)).getOrThrow()
        assertEquals(ModelFileState.MODEL_MISSING, svc.report.value.state)

        server.reply("/drive/v3/files/folder-1", MockHttpServer.Reply(404, """{"error":{"code":404}}"""))
        svc.refresh().getOrThrow()
        assertEquals(ModelFileState.FOLDER_MISSING, svc.report.value.state)
    }

    @Test fun `revoked access surfaces as auth expired`() = runBlocking<Unit> {
        server.reply("/token", MockHttpServer.Reply(200, tokenOk))
        server.reply("/drive/v3/files/folder-1", MockHttpServer.Reply(401))
        val svc = service()
        svc.connect("client-123", null) // folder not selected yet: succeeds
        svc.selectFolder(DriveFile("folder-1", "models", DriveFile.FOLDER_MIME))
        server.reply("/token", MockHttpServer.Reply(400, """{"error":"invalid_grant"}"""))
        svc.refresh()
        val state = svc.report.value.state
        assertTrue(state == ModelFileState.AUTH_EXPIRED || state == ModelFileState.NOT_CONNECTED, "got $state")
        assertFalse(oauth.isConnected)
    }
}
