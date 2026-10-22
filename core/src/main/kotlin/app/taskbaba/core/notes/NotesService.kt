package app.taskbaba.core.notes

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.taskbaba.core.data.BabaJson
import app.taskbaba.core.platform.ClockProvider
import app.taskbaba.core.platform.DeviceAuthResult
import app.taskbaba.core.platform.DeviceAuthenticator
import app.taskbaba.core.platform.SecretStore
import app.taskbaba.db.TaskBabaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import app.taskbaba.db.Note as NoteRow

enum class NoteKind { PLAIN, SECURE }

data class Note(
    val id: String,
    val kind: NoteKind,
    val title: String,
    /** Plain notes only. Always empty for secure notes. */
    val body: String,
    val url: String,
    val pinned: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** The secret part of a secure note. Lives only in the Keychain. */
@Serializable
data class SecureFields(val username: String = "", val password: String = "", val secretNotes: String = "") {
    override fun toString() = "SecureFields(***)" // never leak into logs or crash reports
}

data class NoteDraft(
    val kind: NoteKind,
    val title: String,
    val body: String = "",
    val url: String = "",
    val secure: SecureFields = SecureFields(),
    val pinned: Boolean = false,
) {
    fun validate(): List<String> = buildList {
        if (title.isBlank()) add("Give the note a title.")
        if (title.length > 200) add("Title is too long (max 200 characters).")
        if (body.length > 50_000) add("Note is too long (max 50,000 characters).")
        if (url.isNotBlank()) {
            val scheme = runCatching { URI(url.trim()).scheme?.lowercase() }.getOrNull()
            if (scheme !in setOf("http", "https")) add("Website must start with https:// (or http://).")
        }
        if (kind == NoteKind.SECURE) {
            if (secure.username.length > 500 || secure.password.length > 2_000 || secure.secretNotes.length > 20_000) add("A secret field is too long.")
            if (body.isNotEmpty()) add("Secure notes keep their text in the secret notes field.")
        }
    }
}

class NotesLockedException : IllegalStateException("Unlock secure notes with Touch ID or your Mac password first.")

/**
 * Notes and secure notes. Secure fields go only to [secrets] (the macOS Keychain); the database holds
 * the title and website so notes can be listed and searched without unlocking. Reading secrets
 * requires a recent successful [DeviceAuthenticator] check, which unlocks for [unlockDuration].
 */
class NotesService(
    private val db: TaskBabaDatabase,
    private val secrets: SecretStore,
    private val auth: DeviceAuthenticator,
    /** False when secrets would only be kept in memory (no Keychain); secure notes are then refused. */
    private val secretsPersistent: () -> Boolean,
    private val clock: ClockProvider = ClockProvider.SYSTEM,
    val unlockDuration: Duration = Duration.ofMinutes(2),
) {
    private val q get() = db.notesQueries
    @Volatile private var unlockedUntil: Instant = Instant.MIN

    val canStoreSecrets: Boolean get() = secretsPersistent()

    fun observe(): Flow<List<Note>> = q.allNotes().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toNote() } }
    fun all(): List<Note> = q.allNotes().executeAsList().map { it.toNote() }
    fun get(id: String): Note? = q.noteById(id).executeAsOneOrNull()?.toNote()

    fun search(notes: List<Note>, query: String, kind: NoteKind?): List<Note> = notes.filter { n ->
        (kind == null || n.kind == kind) && (query.isBlank() ||
            n.title.contains(query, true) || n.url.contains(query, true) || (n.kind == NoteKind.PLAIN && n.body.contains(query, true)))
    }

    fun create(draft: NoteDraft): Result<Note> = save(UUID.randomUUID().toString(), draft, null)

    /** Updating a secure note's secrets requires being unlocked (otherwise you could overwrite blindly). */
    fun update(id: String, draft: NoteDraft): Result<Note> {
        val old = get(id) ?: return Result.failure(IllegalStateException("That note no longer exists."))
        if (old.kind == NoteKind.SECURE && !isUnlocked()) return Result.failure(NotesLockedException())
        return save(id, draft.copy(kind = old.kind), old)
    }

    private fun save(id: String, draft: NoteDraft, old: Note?): Result<Note> = runCatching {
        val errors = draft.validate()
        require(errors.isEmpty()) { errors.joinToString(" ") }
        if (draft.kind == NoteKind.SECURE) {
            check(secretsPersistent()) { "Secure notes need the macOS Keychain, which isn't available in this build. Open the installed Task Baba app." }
            secrets.set(secretKey(id), BabaJson.encodeToString(SecureFields.serializer(), draft.secure)).getOrThrow()
        }
        val now = clock.clock().instant()
        val note = Note(id, draft.kind, draft.title.trim(), if (draft.kind == NoteKind.PLAIN) draft.body else "", draft.url.trim(),
            draft.pinned, old?.createdAt ?: now, now)
        q.upsertNote(NoteRow(note.id, note.kind.name, note.title, note.body, note.url, if (note.pinned) 1 else 0, note.createdAt.toEpochMilli(), note.updatedAt.toEpochMilli()))
        note
    }

    fun setPinned(id: String, pinned: Boolean): Result<Unit> = runCatching {
        val n = get(id) ?: error("That note no longer exists.")
        q.upsertNote(NoteRow(n.id, n.kind.name, n.title, n.body, n.url, if (pinned) 1 else 0, n.createdAt.toEpochMilli(), clock.clock().instant().toEpochMilli()))
    }

    fun delete(id: String): Result<Unit> = runCatching {
        val n = get(id) ?: return@runCatching
        if (n.kind == NoteKind.SECURE) secrets.delete(secretKey(id))
        q.deleteNote(id)
    }

    /** Deletes every note and every secure-note Keychain item. */
    fun deleteAll() {
        q.allNoteIds().executeAsList().forEach { secrets.delete(secretKey(it)) }
        q.deleteAllNotes()
        lock()
    }

    fun isUnlocked(): Boolean = clock.clock().instant().isBefore(unlockedUntil)
    fun unlockedUntil(): Instant? = unlockedUntil.takeIf { isUnlocked() }
    fun lock() { unlockedUntil = Instant.MIN }

    suspend fun unlock(): DeviceAuthResult {
        if (isUnlocked()) return DeviceAuthResult.SUCCESS
        val r = auth.authenticate("show your secure notes")
        if (r == DeviceAuthResult.SUCCESS) unlockedUntil = clock.clock().instant().plus(unlockDuration)
        return r
    }

    /** Secret fields of a secure note; fails while locked. */
    fun readSecrets(id: String): Result<SecureFields> = runCatching {
        if (!isUnlocked()) throw NotesLockedException()
        val raw = secrets.get(secretKey(id)) ?: error("This note's secrets weren't found in the Keychain.")
        BabaJson.decodeFromString(SecureFields.serializer(), raw)
    }

    private fun NoteRow.toNote() = Note(
        id, runCatching { NoteKind.valueOf(kind) }.getOrDefault(NoteKind.PLAIN), title, body, url, pinned == 1L,
        Instant.ofEpochMilli(created_at), Instant.ofEpochMilli(updated_at),
    )

    companion object {
        fun secretKey(id: String) = "note.$id"

        private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
        private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val DIGITS = "23456789"
        private const val SYMBOLS = "!@#\$%^&*-_=+?"

        /** Strong random password with at least one of each character class (no look-alike characters). */
        fun generatePassword(length: Int = 20, random: SecureRandom = SecureRandom()): String {
            require(length in 12..128)
            val all = LOWER + UPPER + DIGITS + SYMBOLS
            val chars = mutableListOf(
                LOWER[random.nextInt(LOWER.length)], UPPER[random.nextInt(UPPER.length)],
                DIGITS[random.nextInt(DIGITS.length)], SYMBOLS[random.nextInt(SYMBOLS.length)],
            )
            while (chars.size < length) chars += all[random.nextInt(all.length)]
            for (i in chars.indices.reversed()) { val j = random.nextInt(i + 1); val t = chars[i]; chars[i] = chars[j]; chars[j] = t }
            return chars.joinToString("")
        }
    }
}
