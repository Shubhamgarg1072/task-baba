package app.taskbaba.app.ui.notes

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.taskbaba.app.AppController
import app.taskbaba.app.Screen
import app.taskbaba.app.platform.MacBridge
import app.taskbaba.app.ui.shell.MainWindowContent
import app.taskbaba.app.ui.shell.SidebarExtras
import app.taskbaba.app.ui.theme.BabaTheme
import app.taskbaba.core.BabaCore
import app.taskbaba.core.data.DatabaseFactory
import app.taskbaba.core.notes.NoteDraft
import app.taskbaba.core.notes.NoteKind
import app.taskbaba.core.notes.NotesService
import app.taskbaba.core.notes.SecureFields
import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.DeviceAuthResult
import app.taskbaba.core.platform.InMemorySecretStore
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.platform.NotificationResponse
import app.taskbaba.core.platform.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private object Silent : Notifier {
    override suspend fun authorization() = NotificationAuth.AUTHORIZED
    override suspend fun requestAuthorization() = authorization()
    override suspend fun post(notification: BabaNotification) = Result.success(Unit)
    override suspend fun remove(id: String) {}
    override val responses = MutableSharedFlow<NotificationResponse>()
}

@OptIn(ExperimentalTestApi::class)
class NotesUiTest {
    private class Setup(authResult: DeviceAuthResult = DeviceAuthResult.SUCCESS) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = DatabaseFactory.inMemory()
        val secrets = InMemorySecretStore()
        var authCalls = 0
        val notes = NotesService(db, secrets, { authCalls++; authResult }, secretsPersistent = { true })
        val app = AppController(BabaCore(db, Silent), MacBridge(scope, helper = null), scope)
    }

    @Test fun createPlainNote() = runComposeUiTest {
        val s = Setup()
        setContent { BabaTheme { NotesScreen(s.app, s.notes) } }
        onNodeWithText("New note").performClick()
        waitForIdle()
        onAllNodes(hasSetTextAction() and hasText("Title")).onFirst().performTextInput("Office Wi-Fi")
        onAllNodes(hasSetTextAction() and hasText("Note")).onFirst().performTextInput("Guest network: ask IT")
        onNodeWithText("Save note").performClick()
        waitForIdle()
        assertEquals(listOf("Office Wi-Fi" to "Guest network: ask IT"), s.notes.all().map { it.title to it.body })
    }

    @Test fun createSecureNoteThenItIsLockedAndUnlocksWithAuth() = runComposeUiTest {
        val s = Setup()
        setContent { BabaTheme { NotesScreen(s.app, s.notes) } }
        onNodeWithText("New secure note").performClick()
        waitForIdle()
        onAllNodes(hasSetTextAction() and hasText("Title")).onFirst().performTextInput("Bank")
        onAllNodes(hasSetTextAction() and hasText("Username / email")).onFirst().performTextInput("akd")
        onAllNodes(hasSetTextAction() and hasText("Password")).onFirst().performTextInput("Hunter2-Strong!")
        onNodeWithText("Save to Keychain").performClick()
        waitForIdle()
        val saved = s.notes.all().single()
        assertEquals(NoteKind.SECURE, saved.kind)
        assertEquals("", saved.body)
        assertTrue(s.secrets.get(NotesService.secretKey(saved.id))!!.contains("Hunter2-Strong!"))

        // After saving, it shows locked: no password on screen.
        onNodeWithText("Locked").assertExists()
        assertTrue(onAllNodes(hasText("Hunter2-Strong!")).fetchSemanticsNodes().isEmpty())

        onNodeWithText("Unlock with Touch ID").performClick()
        waitUntil(timeoutMillis = 3_000) { onAllNodes(hasText("Unlocked", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, s.authCalls)
        onAllNodes(hasSetTextAction() and hasText("akd")).onFirst().assertExists()
    }

    @Test fun cancelledAuthKeepsSecretsHidden() = runComposeUiTest {
        val s = Setup(DeviceAuthResult.CANCELLED)
        val n = s.notes.create(NoteDraft(NoteKind.SECURE, "Server", secure = SecureFields("root", "topsecret"))).getOrThrow()
        setContent { BabaTheme { NotesScreen(s.app, s.notes) } }
        onNodeWithText("Server").performClick()
        waitForIdle()
        onNodeWithText("Unlock with Touch ID").performClick()
        waitForIdle()
        runBlocking { kotlinx.coroutines.delay(300) }
        waitForIdle()
        onNodeWithText("Locked").assertExists()
        assertTrue(onAllNodes(hasText("root")).fetchSemanticsNodes().isEmpty())
        assertTrue(s.notes.readSecrets(n.id).isFailure)
    }

    @Test fun renderNotesScreens() {
        val s = Setup()
        s.notes.create(NoteDraft(NoteKind.PLAIN, "Sprint retro ideas", "1. Fewer meetings\n2. Chai breaks are sacred", pinned = true))
        s.notes.create(NoteDraft(NoteKind.PLAIN, "Office Wi-Fi", "Guest network — ask IT for today's code"))
        s.notes.create(NoteDraft(NoteKind.SECURE, "Jira", url = "https://example.atlassian.net", secure = SecureFields("me@example.com", "Example-Passw0rd!")))
        s.app.screen = Screen.NOTES
        val out = Paths.get("build", "screenshots").also { Files.createDirectories(it) }
        for (dark in listOf(false, true)) {
            ImageComposeScene(1440, 900, Density(1f)) {
                BabaTheme(dark) { MainWindowContent(s.app, SidebarExtras()) { NotesScreen(s.app, s.notes) } }
            }.use { scene ->
                scene.render(0)
                val bytes = scene.render(500_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
                Files.write(out.resolve("notes-${if (dark) "dark" else "light"}.png"), bytes)
            }
        }
    }
}
