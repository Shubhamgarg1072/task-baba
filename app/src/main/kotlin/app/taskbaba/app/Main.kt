package app.taskbaba.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.taskbaba.app.platform.MacBridge
import app.taskbaba.app.ui.screens.AiScreen
import app.taskbaba.app.ui.screens.InboxScreen
import app.taskbaba.app.ui.screens.IntegrationsSettings
import app.taskbaba.app.ui.health.HealthScreen
import app.taskbaba.app.ui.review.ReviewScreen
import app.taskbaba.app.ui.settings.SettingsScreen
import app.taskbaba.app.ui.shell.BabaTray
import app.taskbaba.app.ui.shell.FloatingMonk
import app.taskbaba.app.ui.shell.MainWindowContent
import app.taskbaba.app.ui.shell.QuickAddWindow
import app.taskbaba.app.ui.shell.SidebarExtras
import app.taskbaba.app.ui.tasks.TaskListScreen
import app.taskbaba.app.ui.tasks.TodayScreen
import app.taskbaba.app.ui.tasks.rememberTasks
import app.taskbaba.app.ui.theme.BabaTheme
import app.taskbaba.core.BabaCore
import app.taskbaba.core.ai.AiModule
import app.taskbaba.core.data.AppPaths
import app.taskbaba.core.data.DatabaseFactory
import app.taskbaba.core.data.KvStore
import app.taskbaba.core.integrations.FollowUpTaskCreator
import app.taskbaba.core.integrations.InboxResponseOutcome
import app.taskbaba.core.integrations.IntegrationsModule
import app.taskbaba.core.platform.NotificationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/** Everything the windows need, created once at startup. */
class AppContainer(val scope: CoroutineScope) {
    val bridge: MacBridge = MacBridge(scope)
    val db: app.taskbaba.db.TaskBabaDatabase = DatabaseFactory.file()
    private val kv: KvStore = KvStore(db)
    val ai: AiModule = AiModule(kv, bridge.secrets, scope, openBrowser = { url -> openExternal(url) })
    val core: BabaCore = BabaCore(db, bridge.notifier, monkAi = ai.router)
    val app: AppController = AppController(core, bridge, scope)
    val integrations: IntegrationsModule = IntegrationsModule(
        db, kv, bridge.secrets, bridge.notifier,
        FollowUpTaskCreator { draft -> core.tasks.create(draft) },
        settingsProvider = { core.settings.value }, scope = scope, openBrowser = { url -> openExternal(url) },
    )

    fun start() {
        ai.start()
        core.start(scope)
        integrations.start()
        app.start()
        core.greet()
        scope.launch { bridge.notifier.responses.collect(::onNotificationResponse) }
    }

    private fun onNotificationResponse(r: NotificationResponse) {
        if (r.userInfo["kind"] == "inbox") {
            if (integrations.inbox.handleResponse(r) == InboxResponseOutcome.OPEN_INBOX) app.open(Screen.INBOX)
            return
        }
        if (r.actionId == NotificationResponse.DEFAULT_ACTION) {
            app.open(if (r.userInfo["healthEvent"] != null) Screen.HEALTH else Screen.TODAY)
            r.userInfo["task"]?.let { app.editor = Editor.Edit(it) }
        }
    }

    private fun openExternal(url: String) { app.openUrl(url) }

    fun close() = runCatching { bridge.close() }
}

/** One running copy only: a second copy would double every reminder. */
private fun acquireSingleInstanceLock(): Boolean = runCatching {
    val channel = FileChannel.open(AppPaths.home.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = channel.tryLock()
    if (lock != null) Runtime.getRuntime().addShutdownHook(Thread { runCatching { lock.release(); channel.close() } })
    lock != null
}.getOrDefault(true)

fun main() {
    System.setProperty("apple.awt.application.name", "Task Baba")
    System.setProperty("apple.awt.application.appearance", "system")

    if (!acquireSingleInstanceLock()) {
        JOptionPane.showMessageDialog(null, "Task Baba is already running — look for Baba in the menu bar.", "Task Baba", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val container = runCatching { AppContainer(scope) }.getOrElse { e ->
        JOptionPane.showMessageDialog(
            null,
            "Task Baba couldn't open its data in ${AppPaths.home}.\n\n${e.message}\n\nYour data has not been changed.",
            "Task Baba", JOptionPane.ERROR_MESSAGE,
        )
        exitProcess(1)
    }
    container.start()
    val app = container.app
    if (Desktop.isDesktopSupported()) runCatching {
        Desktop.getDesktop().addAppEventListener(AppReopenedListener { scope.launch { app.mainWindowVisible = true } })
    }

    application(exitProcessOnExit = true) {
        val dark = isSystemInDarkTheme()
        val settings by app.core.settings.state.collectAsState()
        val aiStatus by container.ai.router.status.collectAsState()
        val inboxCount by container.integrations.inbox.newCount.collectAsState()
        val reduceMotion = app.reduceMotion(settings)

        BabaTray(app, aiStatus.label, inboxCount)
        QuickAddWindow(app, dark)
        FloatingMonk(app, dark)

        val windowState = rememberWindowState(size = DpSize(1220.dp, 820.dp))
        Window(
            onCloseRequest = { app.mainWindowVisible = false }, // keep running in the menu bar
            visible = app.mainWindowVisible, state = windowState, title = "Task Baba",
        ) {
            window.minimumSize = java.awt.Dimension(980, 640)
            ReturnDetector(window, app)
            MenuBar {
                Menu("File") {
                    Item("New Task", shortcut = KeyShortcut(Key.N, meta = true), onClick = { app.newTask() })
                    Item("Quick Add…", shortcut = KeyShortcut(Key.N, meta = true, shift = true), onClick = { app.quickAddVisible = true })
                    Separator()
                    Item("Close Window", shortcut = KeyShortcut(Key.W, meta = true), onClick = { app.mainWindowVisible = false })
                }
                Menu("View") {
                    listOf(Screen.TODAY, Screen.UPCOMING, Screen.OVERDUE, Screen.COMPLETED, Screen.ALL, Screen.INBOX, Screen.HEALTH, Screen.REVIEW)
                        .forEachIndexed { i, s -> Item(s.title, shortcut = KeyShortcut(Key.Companion.number(i + 1), meta = true), onClick = { app.open(s) }) }
                    Separator()
                    Item("AI & Model", onClick = { app.open(Screen.AI) })
                    Item("Settings…", shortcut = KeyShortcut(Key.Comma, meta = true), onClick = { app.open(Screen.SETTINGS) })
                }
                Menu("Baba") {
                    CheckboxItem("Floating Baba", settings.floatingMonk, onCheckedChange = { v -> app.core.updateSettings { it.copy(floatingMonk = v) } })
                    CheckboxItem("Mute Baba", settings.monkMuted, onCheckedChange = { v -> app.core.updateSettings { it.copy(monkMuted = v) } })
                    Item("Pause reminders for 1 hour", onClick = { app.core.pauseAll(Duration.ofHours(1)) })
                    Item("Send test notification", onClick = { app.sendTestNotification() })
                }
            }
            BabaTheme(dark, reduceMotion) {
                MainWindowContent(app, SidebarExtras(inboxCount, aiStatus.label, aiStatus.healthy)) { screen ->
                    when (screen) {
                        Screen.TODAY -> TodayScreen(app)
                        Screen.UPCOMING, Screen.OVERDUE, Screen.COMPLETED, Screen.ALL -> TaskListScreen(app, screen)
                        Screen.INBOX -> InboxScreen(container.integrations, app::openUrl, rememberTasks(app), onOpenSettings = { app.open(Screen.SETTINGS) })
                        Screen.HEALTH -> HealthScreen(app)
                        Screen.REVIEW -> ReviewScreen(app)
                        Screen.AI -> AiScreen(container.ai)
                        Screen.SETTINGS -> SettingsScreen(app) { IntegrationsSettings(container.integrations) }
                    }
                }
            }
        }
    }
}

private fun Key.Companion.number(n: Int): Key = when (n) {
    1 -> Key.One; 2 -> Key.Two; 3 -> Key.Three; 4 -> Key.Four; 5 -> Key.Five
    6 -> Key.Six; 7 -> Key.Seven; 8 -> Key.Eight; else -> Key.Nine
}

/** Greets the user when they come back to the window after a long break. */
@Composable
private fun ReturnDetector(window: java.awt.Window, app: AppController) {
    DisposableEffect(window) {
        var lostAt: Instant? = null
        val listener = object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent?) { lostAt = Instant.now() }
            override fun windowGainedFocus(e: WindowEvent?) {
                lostAt?.let { app.core.onReturn(Duration.between(it, Instant.now())) }
                lostAt = null
            }
        }
        window.addWindowFocusListener(listener)
        onDispose { window.removeWindowFocusListener(listener) }
    }
}
