package app.taskbaba.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.taskbaba.app.platform.MacBridge
import app.taskbaba.core.BabaCore
import app.taskbaba.core.model.AppSettings
import app.taskbaba.core.model.MotionPreference
import app.taskbaba.core.platform.LoginItemStatus
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.tasks.SortMode
import app.taskbaba.core.tasks.TaskFilter
import app.taskbaba.core.tasks.TaskView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.TimeUnit

enum class Screen(val title: String, val view: TaskView? = null) {
    TODAY("Today", TaskView.TODAY),
    UPCOMING("Upcoming", TaskView.UPCOMING),
    OVERDUE("Overdue", TaskView.OVERDUE),
    COMPLETED("Completed", TaskView.COMPLETED),
    ALL("All Tasks", TaskView.ALL),
    INBOX("Smart Inbox"),
    HEALTH("Health"),
    NOTES("Notes"),
    SECURE_NOTES("Secure Notes"),
    REVIEW("Daily Review"),
    AI("AI & Model"),
    SETTINGS("Settings"),
}

sealed interface Editor {
    data object New : Editor
    data class Edit(val id: String) : Editor
}

/** UI-level state for the windows. Business rules stay in [BabaCore]. */
private const val BUNDLE_ID = "app.taskbaba.TaskBaba"

enum class LinkRoute { BROWSER, SYSTEM_OPEN, REFUSED }

/**
 * Web links go to the browser. System Settings and mail links go through `open`, which hands them to
 * the app that owns the scheme (Java's Desktop.browse would send them to the browser). Anything else is refused.
 */
fun linkRoute(url: String): LinkRoute = when (runCatching { URI(url).scheme?.lowercase() }.getOrNull()) {
    "https", "http" -> LinkRoute.BROWSER
    "x-apple.systempreferences", "mailto" -> LinkRoute.SYSTEM_OPEN
    else -> LinkRoute.REFUSED
}

class AppController(
    val core: BabaCore,
    val bridge: MacBridge,
    val scope: CoroutineScope,
) {
    var screen by mutableStateOf(Screen.TODAY)
    var editor by mutableStateOf<Editor?>(null)
    var filter by mutableStateOf(TaskFilter())
    var sort by mutableStateOf(SortMode.SMART)
    var mainWindowVisible by mutableStateOf(true)
    var quickAddVisible by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
        private set
    var notificationAuth by mutableStateOf(NotificationAuth.NOT_DETERMINED)
        private set
    var loginItemStatus by mutableStateOf(LoginItemStatus.UNAVAILABLE)
        private set
    var systemReduceMotion by mutableStateOf(false)
        private set

    val settings: AppSettings get() = core.settings.value

    /** Extra cleanup run by Settings → Reset all data (e.g. notes and their Keychain items). */
    val resetHooks = mutableListOf<() -> Unit>()

    private var clipboardJob: kotlinx.coroutines.Job? = null

    /**
     * Copies text. Sensitive values (passwords) are wiped from the clipboard after 30 seconds unless
     * something else was copied in the meantime.
     */
    fun copy(text: String, sensitive: Boolean, what: String) {
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        runCatching { clipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
            .onFailure { say("Couldn't copy: ${it.message}"); return }
        clipboardJob?.cancel()
        if (!sensitive) { say("$what copied."); return }
        say("$what copied — it will be cleared from the clipboard in 30 seconds.")
        clipboardJob = scope.launch {
            delay(30_000)
            runCatching {
                val current = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                if (current == text) clipboard.setContents(java.awt.datatransfer.StringSelection(""), null)
            }
        }
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            refreshNotificationAuth()
            loginItemStatus = bridge.loginItem.status()
            systemReduceMotion = readSystemReduceMotion()
        }
    }

    fun reduceMotion(s: AppSettings): Boolean = when (s.motion) {
        MotionPreference.FULL -> false
        MotionPreference.REDUCED -> true
        MotionPreference.FOLLOW_SYSTEM -> systemReduceMotion
    }

    fun open(screen: Screen) {
        this.screen = screen
        mainWindowVisible = true
    }

    fun newTask() {
        open(Screen.TODAY.takeIf { screen.view == null } ?: screen)
        editor = Editor.New
    }

    /** Shows a short status line in the window; also used for errors so nothing fails silently. */
    fun say(text: String) {
        message = text
        scope.launch {
            delay(5000)
            if (message == text) message = null
        }
    }

    fun <T> report(result: Result<T>, success: String? = null): Result<T> {
        result.onFailure { say(it.message ?: "Something went wrong.") }
        result.onSuccess { if (success != null) say(success) }
        return result
    }

    suspend fun refreshNotificationAuth() {
        notificationAuth = withContext(Dispatchers.IO) { core.let { bridge.notifier.authorization() } }
    }

    fun requestNotifications() = scope.launch(Dispatchers.IO) {
        notificationAuth = bridge.notifier.requestAuthorization()
        say(
            when (notificationAuth) {
                NotificationAuth.AUTHORIZED -> "Notifications allowed. Baba can now reach you."
                NotificationAuth.DENIED -> "Notifications are off. Reminders will appear inside Task Baba only — you can allow them in System Settings."
                NotificationAuth.REFUSED -> "macOS refused notification permission for this copy of Task Baba (it was rebuilt or not opened from Finder). " +
                    "Quit Task Baba, open it from Finder or the Dock, then press Allow notifications again. Until then reminders show as simple banners and in the app."
                else -> "Native notifications aren't available in this build; Baba will use simple banners and in-app reminders."
            },
        )
    }

    /** System Settings › Notifications, scrolled to Task Baba where macOS supports it. */
    fun openNotificationSettings() = openUrl("x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=$BUNDLE_ID")

    fun setLaunchAtLogin(enabled: Boolean) = scope.launch(Dispatchers.IO) {
        val r = bridge.loginItem.setEnabled(enabled)
        r.onSuccess { status ->
            loginItemStatus = status
            core.updateSettings { it.copy(launchAtLogin = status == LoginItemStatus.ENABLED || status == LoginItemStatus.REQUIRES_APPROVAL) }
            if (status == LoginItemStatus.REQUIRES_APPROVAL) say("Approve Task Baba in System Settings › General › Login Items.")
        }.onFailure { say("Couldn't change launch at login: ${it.message}") }
    }

    fun sendTestNotification() = scope.launch(Dispatchers.IO) {
        if (notificationAuth == NotificationAuth.NOT_DETERMINED) notificationAuth = bridge.notifier.requestAuthorization()
        core.sendTestNotification()
            .onSuccess { say("Test notification sent. Try its buttons!") }
            .onFailure { say("Test notification failed: ${it.message ?: "notifications are not allowed"}") }
    }

    fun openUrl(url: String) {
        runCatching {
            val uri = URI(url)
            when (linkRoute(url)) {
                LinkRoute.BROWSER ->
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(uri)
                    else ProcessBuilder("/usr/bin/open", url).start()
                LinkRoute.SYSTEM_OPEN -> ProcessBuilder("/usr/bin/open", url).start()
                LinkRoute.REFUSED -> error("Refusing to open ${uri.scheme} links")
            }
        }.onFailure { say("Couldn't open the link: ${it.message}") }
    }

    private fun readSystemReduceMotion(): Boolean = runCatching {
        val p = ProcessBuilder("/usr/bin/defaults", "read", "com.apple.universalaccess", "reduceMotion").redirectErrorStream(true).start()
        p.waitFor(2, TimeUnit.SECONDS)
        p.inputStream.bufferedReader().readText().trim() == "1"
    }.getOrDefault(false)
}
