package app.taskbaba.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.taskbaba.app.AppController
import app.taskbaba.app.Screen
import app.taskbaba.app.platform.MacBridge
import app.taskbaba.app.ui.health.HealthScreen
import app.taskbaba.app.ui.review.ReviewScreen
import app.taskbaba.app.ui.settings.SettingsScreen
import app.taskbaba.app.ui.shell.MainWindowContent
import app.taskbaba.app.ui.shell.SidebarExtras
import app.taskbaba.app.ui.tasks.TaskListScreen
import app.taskbaba.app.ui.tasks.TodayScreen
import app.taskbaba.app.ui.theme.BabaTheme
import app.taskbaba.core.BabaCore
import app.taskbaba.core.data.DatabaseFactory
import app.taskbaba.core.model.Category
import app.taskbaba.core.model.HealthOutcome
import app.taskbaba.core.model.HealthType
import app.taskbaba.core.model.Importance
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.RecurrenceRule
import app.taskbaba.core.model.TaskDraft
import kotlinx.coroutines.CoroutineScope
import app.taskbaba.app.ui.screens.IntegrationsSettings
import app.taskbaba.app.ui.screens.InboxScreen
import app.taskbaba.app.ui.screens.AiScreen
import app.taskbaba.core.integrations.FollowUpTaskCreator
import app.taskbaba.core.integrations.IntegrationsModule
import app.taskbaba.core.ai.AiModule
import app.taskbaba.core.platform.InMemorySecretStore
import app.taskbaba.core.data.KvStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the real screens offscreen with sample (non-personal) data. Doubles as a smoke test that
 * every screen composes without crashing in light and dark mode. Output: app/build/screenshots/.
 */
class ScreenshotRenderer {
    private fun sampleApp(): AppController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val core = BabaCore(DatabaseFactory.inMemory(), SilentNotifier)
        val today = core.today()
        val t = core.tasks
        t.create(TaskDraft("Fix login bug in PROJ-142", priority = Priority.CRITICAL, dueDate = today, dueTime = LocalTime.of(16, 0), estimatedMinutes = 45))
        val mit = t.create(TaskDraft("Prepare sprint demo", priority = Priority.HIGH, importance = Importance.VITAL, dueDate = today)).getOrThrow()
        t.setMostImportant(mit.id)
        t.create(TaskDraft("Reply to design review", priority = Priority.MEDIUM, dueDate = today, estimatedMinutes = 10))
        val late = t.create(TaskDraft("Submit expense report", priority = Priority.MEDIUM, category = Category.PERSONAL, dueDate = today.minusDays(2))).getOrThrow()
        repeat(4) { t.snooze(late.id, Duration.ofSeconds(1)) }
        t.create(TaskDraft("Read Kotlin coroutines chapter", priority = Priority.LOW, category = Category.LEARNING, dueDate = today.plusDays(2)))
        t.create(TaskDraft("Morning stand-up notes", priority = Priority.MEDIUM, dueDate = today, dueTime = LocalTime.of(10, 0), recurrence = RecurrenceRule.WEEKDAYS))
        val done = t.create(TaskDraft("Code review for teammate", priority = Priority.HIGH, dueDate = today)).getOrThrow()
        t.complete(done.id)
        core.logHealth(HealthType.WATER)
        core.logHealth(HealthType.STRETCH)
        runBlocking { core.tick() }
        core.speak(app.taskbaba.core.monk.MonkEvent.TASK_POSTPONED, core.todayContext(t.get(late.id)))
        return AppController(core, MacBridge(scope, helper = null), scope)
    }

    private fun render(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val out = Paths.get("build", "screenshots").also { Files.createDirectories(it) }
        ImageComposeScene(1440, 900, Density(1f)) {
            BabaTheme(dark) { content() }
        }.use { scene ->
            scene.render(0)
            val img = scene.render(500_000_000)
            val bytes = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            Files.write(out.resolve("$name-${if (dark) "dark" else "light"}.png"), bytes)
            assertTrue(bytes.size > 10_000)
        }
    }

    @Test fun renderFeatureScreens() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = DatabaseFactory.inMemory()
        val kv = KvStore(db)
        val secrets = InMemorySecretStore()
        val core = BabaCore(db, SilentNotifier)
        val app = AppController(core, MacBridge(scope, helper = null), scope)
        val ai = AiModule(kv, secrets, scope, openBrowser = {})
        val integrations = IntegrationsModule(
            db, kv, secrets, SilentNotifier, FollowUpTaskCreator { core.tasks.create(it) },
            settingsProvider = { core.settings.value }, scope = scope, openBrowser = {},
        )
        for (dark in listOf(false, true)) {
            app.screen = Screen.AI
            render("ai", dark) { MainWindowContent(app, SidebarExtras()) { AiScreen(ai) } }
            app.screen = Screen.INBOX
            render("inbox", dark) { MainWindowContent(app, SidebarExtras()) { InboxScreen(integrations, {}, emptyList()) } }
            app.screen = Screen.SETTINGS
            render("settings-integrations", dark) {
                MainWindowContent(app, SidebarExtras()) { SettingsScreen(app) { IntegrationsSettings(integrations) } }
            }
        }
    }

    @Test fun renderAllScreens() {
        val app = sampleApp()
        for (dark in listOf(false, true)) {
            for (screen in listOf(Screen.TODAY, Screen.ALL, Screen.HEALTH, Screen.REVIEW, Screen.SETTINGS)) {
                app.screen = screen
                render(screen.name.lowercase(), dark) {
                    MainWindowContent(app, SidebarExtras(2, "Built-in only", false)) { s ->
                        when (s) {
                            Screen.TODAY -> TodayScreen(app)
                            Screen.HEALTH -> HealthScreen(app)
                            Screen.REVIEW -> ReviewScreen(app)
                            Screen.SETTINGS -> SettingsScreen(app)
                            else -> TaskListScreen(app, s)
                        }
                    }
                }
            }
        }
    }
}

private object SilentNotifier : app.taskbaba.core.platform.Notifier {
    override suspend fun authorization() = app.taskbaba.core.platform.NotificationAuth.NOT_DETERMINED
    override suspend fun requestAuthorization() = authorization()
    override suspend fun post(notification: app.taskbaba.core.platform.BabaNotification) = Result.success(Unit)
    override suspend fun remove(id: String) {}
    override val responses = kotlinx.coroutines.flow.MutableSharedFlow<app.taskbaba.core.platform.NotificationResponse>()
}
