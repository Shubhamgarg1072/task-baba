package app.taskbaba.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.taskbaba.app.AppController
import app.taskbaba.app.ui.components.BabaCard
import app.taskbaba.app.ui.components.ErrorText
import app.taskbaba.app.ui.components.PrimaryButton
import app.taskbaba.app.ui.components.QuietButton
import app.taskbaba.app.ui.components.ScreenHeader
import app.taskbaba.app.ui.components.SecondaryButton
import app.taskbaba.app.ui.components.SectionTitle
import app.taskbaba.app.ui.tasks.SmallChip
import app.taskbaba.app.ui.tasks.babaFieldColors
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.notes.Note
import app.taskbaba.core.notes.NoteDraft
import app.taskbaba.core.notes.NoteKind
import app.taskbaba.core.notes.NotesService
import app.taskbaba.core.notes.SecureFields
import app.taskbaba.core.platform.DeviceAuthResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which note the right-hand pane shows: an existing one, or a new one of a given kind. */
private sealed interface NoteSelection {
    data class Existing(val id: String) : NoteSelection
    data class New(val kind: NoteKind) : NoteSelection
}

@Composable
fun NotesScreen(app: AppController, notes: NotesService) {
    val all by notes.observe().collectAsState(initial = notes.all())
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<NoteKind?>(null) }
    var selection by remember { mutableStateOf<NoteSelection?>(null) }
    val shown = notes.search(all, query, filter)

    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f).widthIn(min = 300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenHeader("Notes", "Notes stay on this Mac. Secure notes keep their secrets in the macOS Keychain.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("New note", { selection = NoteSelection.New(NoteKind.PLAIN) })
                SecondaryButton("New secure note", { selection = NoteSelection.New(NoteKind.SECURE) })
            }
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Search titles, websites and notes", color = Baba.colors.textMuted) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Baba.colors.textMuted) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), colors = babaFieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip("All", filter == null) { filter = null }
                SmallChip("Notes", filter == NoteKind.PLAIN) { filter = NoteKind.PLAIN }
                SmallChip("Secure", filter == NoteKind.SECURE) { filter = NoteKind.SECURE }
            }
            BabaCard(Modifier.weight(1f), padding = 6.dp) {
                if (shown.isEmpty()) {
                    Text(
                        if (all.isEmpty()) "No notes yet. Jot something down, or save a login as a secure note."
                        else "No notes match.",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted,
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown, key = { it.id }) { n ->
                        NoteRow(n, selected = (selection as? NoteSelection.Existing)?.id == n.id) { selection = NoteSelection.Existing(n.id) }
                    }
                }
            }
        }
        Box(Modifier.weight(1.3f).widthIn(min = 360.dp)) {
            when (val sel = selection) {
                null -> BabaCard {
                    SectionTitle("How secure notes work")
                    Text(
                        "• Username, password and secret text are stored only in the macOS Keychain — never in Task Baba's database.\n" +
                            "• Seeing or copying them asks for Touch ID or your Mac password, then stays unlocked for 2 minutes.\n" +
                            "• Copied passwords are wiped from the clipboard after 30 seconds.\n" +
                            "• Notes are never sent to AI, shown in notifications, or written to logs.\n" +
                            "• For critical accounts, a dedicated password manager with sync and breach alerts is still the safer home.",
                        style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted,
                    )
                }
                is NoteSelection.New ->
                    if (sel.kind == NoteKind.PLAIN) PlainNoteEditor(app, notes, null) { selection = it?.let(NoteSelection::Existing) }
                    else SecureNoteEditor(app, notes, null) { selection = it?.let(NoteSelection::Existing) }
                is NoteSelection.Existing -> {
                    val note = all.firstOrNull { it.id == sel.id }
                    if (note == null) LaunchedEffect(sel) { selection = null }
                    else if (note.kind == NoteKind.PLAIN) PlainNoteEditor(app, notes, note) { selection = it?.let(NoteSelection::Existing) }
                    else SecureNoteEditor(app, notes, note) { selection = it?.let(NoteSelection::Existing) }
                }
            }
        }
    }
}

private val UPDATED = DateTimeFormatter.ofPattern("d MMM, HH:mm")

@Composable
private fun NoteRow(n: Note, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Baba.colors.saffronSoft.copy(alpha = 0.6f) else Baba.colors.card)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (n.kind == NoteKind.SECURE) Icons.Outlined.Lock else Icons.Outlined.StickyNote2,
            if (n.kind == NoteKind.SECURE) "Secure note" else "Note",
            tint = if (n.kind == NoteKind.SECURE) Baba.colors.saffron else Baba.colors.textMuted, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(n.title, style = MaterialTheme.typography.bodyLarge, color = Baba.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    n.kind == NoteKind.SECURE -> (n.url.ifBlank { "Secure note" }) + " · ••••••"
                    else -> n.body.lineSequence().firstOrNull { it.isNotBlank() } ?: "Empty note"
                },
                style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (n.pinned) Icon(Icons.Filled.PushPin, "Pinned", tint = Baba.colors.saffron, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(n.updatedAt.atZone(ZoneId.systemDefault()).format(UPDATED), style = MaterialTheme.typography.labelSmall, color = Baba.colors.textMuted)
    }
}

@Composable
private fun PlainNoteEditor(app: AppController, notes: NotesService, note: Note?, onSaved: (String?) -> Unit) {
    var title by remember(note?.id) { mutableStateOf(note?.title ?: "") }
    var url by remember(note?.id) { mutableStateOf(note?.url ?: "") }
    var body by remember(note?.id) { mutableStateOf(note?.body ?: "") }
    var pinned by remember(note?.id) { mutableStateOf(note?.pinned ?: false) }
    var errors by remember(note?.id) { mutableStateOf(emptyList<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        val draft = NoteDraft(NoteKind.PLAIN, title, body, url, pinned = pinned)
        errors = draft.validate()
        if (errors.isNotEmpty()) return
        val r = if (note == null) notes.create(draft) else notes.update(note.id, draft)
        r.onSuccess { app.say("Note saved."); onSaved(it.id) }.onFailure { errors = listOf(it.message ?: "Couldn't save.") }
    }

    BabaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EditorHeader(if (note == null) "New note" else "Edit note", pinned) { pinned = it }
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))
            OutlinedTextField(url, { url = it }, label = { Text("Link (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://…") }, colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))
            OutlinedTextField(body, { body = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))
            Text(
                "Plain notes are stored unencrypted in Task Baba's local database. For passwords, use a secure note.",
                style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
            )
        }
        ErrorText(errors)
        EditorButtons(
            primary = if (note == null) "Save note" else "Save", onPrimary = ::save, onCancel = { onSaved(note?.id) },
            onDelete = note?.let { { confirmDelete = true } }, onOpenLink = url.takeIf { it.startsWith("http") }?.let { { app.openUrl(url.trim()) } },
        )
    }
    if (confirmDelete && note != null) DeleteDialog(note.title, secure = false, onDismiss = { confirmDelete = false }) {
        app.report(notes.delete(note.id), "Note deleted."); confirmDelete = false; onSaved(null)
    }
}

@Composable
private fun SecureNoteEditor(app: AppController, notes: NotesService, note: Note?, onSaved: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val isNew = note == null
    var title by remember(note?.id) { mutableStateOf(note?.title ?: "") }
    var url by remember(note?.id) { mutableStateOf(note?.url ?: "") }
    var pinned by remember(note?.id) { mutableStateOf(note?.pinned ?: false) }
    // Secrets are only held in memory while unlocked (or while typing a brand-new note).
    var secret by remember(note?.id) { mutableStateOf<SecureFields?>(if (isNew) SecureFields() else null) }
    var showPassword by remember(note?.id) { mutableStateOf(false) }
    var errors by remember(note?.id) { mutableStateOf(emptyList<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    var unlocking by remember { mutableStateOf(false) }

    // Re-lock: drop secrets from memory as soon as the unlock window ends.
    LaunchedEffect(note?.id) {
        while (true) {
            now = Instant.now()
            if (!isNew && secret != null && !notes.isUnlocked()) { secret = null; showPassword = false }
            delay(1000)
        }
    }

    fun unlockAndLoad() {
        if (note == null || unlocking) return
        unlocking = true
        scope.launch {
            when (notes.unlock()) {
                DeviceAuthResult.SUCCESS -> notes.readSecrets(note.id).onSuccess { secret = it }.onFailure { errors = listOf(it.message ?: "Couldn't read the Keychain.") }
                DeviceAuthResult.CANCELLED -> app.say("Unlock cancelled.")
                DeviceAuthResult.FAILED -> app.say("Couldn't verify it's you. Secure notes stay locked.")
                DeviceAuthResult.UNAVAILABLE -> errors = listOf("Touch ID / password check isn't available in this build. Open the installed Task Baba app.")
            }
            unlocking = false
        }
    }

    fun save() {
        val s = secret ?: return
        val draft = NoteDraft(NoteKind.SECURE, title, url = url, secure = s, pinned = pinned)
        errors = draft.validate()
        if (errors.isNotEmpty()) return
        val r = if (note == null) notes.create(draft) else notes.update(note.id, draft)
        r.onSuccess { app.say("Secure note saved to the Keychain."); onSaved(it.id) }.onFailure { errors = listOf(it.message ?: "Couldn't save.") }
    }

    BabaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EditorHeader(if (isNew) "New secure note" else "Secure note", pinned) { pinned = it }
            if (!notes.canStoreSecrets) {
                ErrorText(listOf("Secure notes need the macOS Keychain, which isn't available in this development build. Open the installed Task Baba app."))
            }
            val unlocked = secret != null
            if (!isNew) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (unlocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null, tint = Baba.colors.saffron, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    val left = notes.unlockedUntil()?.let { Duration.between(now, it).seconds.coerceAtLeast(0) }
                    Text(
                        if (unlocked && left != null) "Unlocked · locks in ${left / 60}:${"%02d".format(left % 60)}" else "Locked",
                        style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text, modifier = Modifier.weight(1f),
                    )
                    if (unlocked) QuietButton("Lock now", { notes.lock(); secret = null; showPassword = false })
                    else PrimaryButton(if (unlocking) "Waiting for Touch ID…" else "Unlock with Touch ID", ::unlockAndLoad, enabled = !unlocking && notes.canStoreSecrets)
                }
            }
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, readOnly = !unlocked,
                modifier = Modifier.fillMaxWidth(), colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))
            OutlinedTextField(url, { url = it }, label = { Text("Website (optional)") }, singleLine = true, readOnly = !unlocked,
                placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth(), colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))

            val s = secret
            if (s == null) {
                LockedField("Username"); LockedField("Password"); LockedField("Secret notes")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(s.username, { secret = s.copy(username = it) }, label = { Text("Username / email") }, singleLine = true,
                        modifier = Modifier.weight(1f), colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))
                    QuietButton("Copy", { app.copy(s.username, sensitive = false, what = "Username") }, enabled = s.username.isNotEmpty())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        s.password, { secret = s.copy(password = it) }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton({ showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPassword) "Hide password" else "Show password")
                            }
                        },
                        modifier = Modifier.weight(1f), colors = babaFieldColors(), shape = RoundedCornerShape(10.dp),
                    )
                    QuietButton("Copy", { app.copy(s.password, sensitive = true, what = "Password") }, enabled = s.password.isNotEmpty())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuietButton("Generate strong password", { secret = s.copy(password = NotesService.generatePassword(20)); showPassword = true })
                }
                OutlinedTextField(s.secretNotes, { secret = s.copy(secretNotes = it) }, label = { Text("Secret notes (recovery codes, PINs…)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))
            }
        }
        ErrorText(errors)
        EditorButtons(
            primary = if (isNew) "Save to Keychain" else "Save", onPrimary = ::save, primaryEnabled = secret != null && notes.canStoreSecrets,
            onCancel = { onSaved(note?.id) }, onDelete = note?.let { { confirmDelete = true } },
            onOpenLink = url.takeIf { it.startsWith("http") }?.let { { app.openUrl(url.trim()) } },
        )
    }
    if (confirmDelete && note != null) DeleteDialog(note.title, secure = true, onDismiss = { confirmDelete = false }) {
        app.report(notes.delete(note.id), "Secure note and its Keychain entry deleted."); confirmDelete = false; onSaved(null)
    }
}

@Composable
private fun LockedField(label: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Baba.colors.sidebar).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted, modifier = Modifier.weight(1f))
        Text("••••••••", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted)
    }
}

@Composable
private fun EditorHeader(title: String, pinned: Boolean, onPin: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Baba.colors.text, modifier = Modifier.weight(1f))
        IconButton({ onPin(!pinned) }) {
            Icon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (pinned) "Unpin" else "Pin to top",
                tint = if (pinned) Baba.colors.saffron else Baba.colors.textMuted)
        }
    }
}

@Composable
private fun EditorButtons(
    primary: String, onPrimary: () -> Unit, onCancel: () -> Unit, onDelete: (() -> Unit)?, onOpenLink: (() -> Unit)?, primaryEnabled: Boolean = true,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(primary, onPrimary, enabled = primaryEnabled)
        SecondaryButton("Close", onCancel)
        if (onOpenLink != null) QuietButton("Open link", onOpenLink)
        Spacer(Modifier.weight(1f))
        if (onDelete != null) SecondaryButton("Delete", onDelete, danger = true)
    }
}

@Composable
private fun DeleteDialog(title: String, secure: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “${title.take(60)}”?") },
        text = { Text(if (secure) "This deletes the note and removes its username, password and secret notes from the Keychain. This can't be undone." else "This can't be undone.") },
        confirmButton = { SecondaryButton("Delete", onConfirm, danger = true) },
        dismissButton = { SecondaryButton("Cancel", onDismiss) },
    )
}
