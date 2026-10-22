package app.taskbaba.core.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.taskbaba.core.MutableClock
import app.taskbaba.core.data.DatabaseFactory
import app.taskbaba.core.platform.DeviceAuthResult
import app.taskbaba.core.platform.DeviceAuthenticator
import app.taskbaba.core.platform.InMemorySecretStore
import app.taskbaba.db.TaskBabaDatabase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesServiceTest {
    private class FakeAuth(var result: DeviceAuthResult = DeviceAuthResult.SUCCESS) : DeviceAuthenticator {
        var calls = 0
        override suspend fun authenticate(reason: String): DeviceAuthResult { calls++; return result }
    }

    private val clock = MutableClock.at("2026-10-22T10:00:00")

    private fun service(
        db: TaskBabaDatabase = DatabaseFactory.inMemory(), secrets: InMemorySecretStore = InMemorySecretStore(),
        auth: FakeAuth = FakeAuth(), persistent: Boolean = true,
    ) = NotesService(db, secrets, auth, { persistent }, clock)

    @Test fun plainNotesCrudAndSearch() {
        val s = service()
        val n = s.create(NoteDraft(NoteKind.PLAIN, "Wi-Fi at office", "Ask IT for the guest network", "https://intranet.example.com")).getOrThrow()
        s.create(NoteDraft(NoteKind.PLAIN, "Shopping", "milk, dal, chai"))
        assertEquals(listOf("Wi-Fi at office"), s.search(s.all(), "guest", null).map { it.title })
        s.update(n.id, NoteDraft(NoteKind.PLAIN, "Wi-Fi (office)", "updated")).getOrThrow()
        assertEquals("updated", s.get(n.id)!!.body)
        s.setPinned(n.id, true)
        assertEquals(n.id, s.all().first().id)
        s.delete(n.id)
        assertNull(s.get(n.id))
    }

    @Test fun secureSecretsNeverTouchTheDatabaseFile() {
        val file = Files.createTempDirectory("notes").resolve("t.db")
        val secrets = InMemorySecretStore()
        val s = service(DatabaseFactory.file(file), secrets)
        val n = s.create(NoteDraft(NoteKind.SECURE, "Bank login", url = "https://bank.example.com",
            secure = SecureFields("akd-user-42", "S3cret!Pa55-word", "security answer: chai"))).getOrThrow()
        val raw = Files.readAllBytes(file).toString(Charsets.ISO_8859_1) +
            (file.resolveSibling("t.db-wal").takeIf { Files.exists(it) }?.let { Files.readAllBytes(it).toString(Charsets.ISO_8859_1) } ?: "")
        assertFalse("S3cret!Pa55-word" in raw, "password leaked into SQLite")
        assertFalse("akd-user-42" in raw, "username leaked into SQLite")
        assertFalse("security answer" in raw)
        assertTrue("Bank login" in raw)
        assertTrue(secrets.get(NotesService.secretKey(n.id))!!.contains("S3cret!Pa55-word"))
        assertEquals("", s.get(n.id)!!.body)
    }

    @Test fun readingSecretsRequiresUnlockAndRelocksAfterTimeout() = runBlocking {
        val auth = FakeAuth()
        val s = service(auth = auth)
        val n = s.create(NoteDraft(NoteKind.SECURE, "GitHub", secure = SecureFields("me", "pw-123456789"))).getOrThrow()
        assertIs<NotesLockedException>(s.readSecrets(n.id).exceptionOrNull())
        assertEquals(DeviceAuthResult.SUCCESS, s.unlock())
        assertEquals("pw-123456789", s.readSecrets(n.id).getOrThrow().password)
        assertEquals(DeviceAuthResult.SUCCESS, s.unlock())
        assertEquals(1, auth.calls, "no second prompt while unlocked")
        clock.advance(Duration.ofMinutes(3))
        assertIs<NotesLockedException>(s.readSecrets(n.id).exceptionOrNull())
        s.unlock(); s.lock()
        assertFalse(s.isUnlocked())
    }

    @Test fun cancelledOrUnavailableAuthKeepsItLocked() = runBlocking {
        for (r in listOf(DeviceAuthResult.CANCELLED, DeviceAuthResult.FAILED, DeviceAuthResult.UNAVAILABLE)) {
            val s = service(auth = FakeAuth(r))
            val n = s.create(NoteDraft(NoteKind.SECURE, "X", secure = SecureFields(password = "p"))).getOrThrow()
            assertEquals(r, s.unlock())
            assertTrue(s.readSecrets(n.id).isFailure)
        }
    }

    @Test fun editingASecureNoteRequiresUnlock() = runBlocking {
        val s = service()
        val n = s.create(NoteDraft(NoteKind.SECURE, "Server", secure = SecureFields("root", "old"))).getOrThrow()
        assertIs<NotesLockedException>(s.update(n.id, NoteDraft(NoteKind.SECURE, "Server", secure = SecureFields("root", "new"))).exceptionOrNull())
        s.unlock()
        s.update(n.id, NoteDraft(NoteKind.SECURE, "Server", secure = SecureFields("root", "new"))).getOrThrow()
        assertEquals("new", s.readSecrets(n.id).getOrThrow().password)
    }

    @Test fun deletingRemovesKeychainItems() {
        val secrets = InMemorySecretStore()
        val s = service(secrets = secrets)
        val a = s.create(NoteDraft(NoteKind.SECURE, "A", secure = SecureFields(password = "1"))).getOrThrow()
        val b = s.create(NoteDraft(NoteKind.SECURE, "B", secure = SecureFields(password = "2"))).getOrThrow()
        s.delete(a.id)
        assertNull(secrets.get(NotesService.secretKey(a.id)))
        s.deleteAll()
        assertNull(secrets.get(NotesService.secretKey(b.id)))
        assertTrue(s.all().isEmpty())
    }

    @Test fun secureNotesRefusedWithoutAKeychain() {
        val s = service(persistent = false)
        assertTrue(s.create(NoteDraft(NoteKind.SECURE, "X", secure = SecureFields(password = "p"))).isFailure)
        assertTrue(s.create(NoteDraft(NoteKind.PLAIN, "Plain is fine")).isSuccess)
    }

    @Test fun validationAndPasswordGenerator() {
        val s = service()
        assertTrue(s.create(NoteDraft(NoteKind.PLAIN, " ")).isFailure)
        assertTrue(s.create(NoteDraft(NoteKind.PLAIN, "x", url = "javascript:alert(1)")).isFailure)
        repeat(50) {
            val p = NotesService.generatePassword(20)
            assertEquals(20, p.length)
            assertTrue(p.any(Char::isLowerCase) && p.any(Char::isUpperCase) && p.any(Char::isDigit) && p.any { !it.isLetterOrDigit() })
        }
        assertEquals("SecureFields(***)", SecureFields("u", "p").toString())
    }

    @Test fun existingVersion1DatabaseIsMigrated() {
        val file = Files.createTempDirectory("mig").resolve("old.db")
        // Simulate a 1.0 database: current schema minus the note table, marked as version 1.
        val driver = JdbcSqliteDriver("jdbc:sqlite:$file")
        TaskBabaDatabase.Schema.create(driver)
        driver.execute(null, "DROP TABLE note", 0)
        driver.execute(null, "PRAGMA user_version = 1", 0)
        driver.close()
        val s = service(DatabaseFactory.file(file))
        assertTrue(s.create(NoteDraft(NoteKind.PLAIN, "After upgrade")).isSuccess)
    }
}
