package app.taskbaba.app.ui.shell

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import app.taskbaba.app.AppController
import app.taskbaba.app.platform.MacBridge
import app.taskbaba.app.platform.ObservedNotifier
import app.taskbaba.app.ui.theme.BabaTheme
import app.taskbaba.core.BabaCore
import app.taskbaba.core.data.DatabaseFactory
import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.platform.NotificationKind
import app.taskbaba.core.platform.NotificationResponse
import app.taskbaba.core.platform.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeNotifier(var fail: Boolean = false) : Notifier {
    override suspend fun authorization() = NotificationAuth.AUTHORIZED
    override suspend fun requestAuthorization() = authorization()
    override suspend fun post(notification: BabaNotification) = if (fail) Result.failure(IllegalStateException("x")) else Result.success(Unit)
    override suspend fun remove(id: String) {}
    override val responses = MutableSharedFlow<NotificationResponse>()
}

@OptIn(ExperimentalTestApi::class)
class FloatingBabaTest {
    private val stretch = BabaNotification("h1", NotificationKind.HEALTH, "🧘 Stand & stretch", "Your spine has been patient. Give it a gentle stretch.")

    private fun app(): AppController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = DatabaseFactory.inMemory()
        return AppController(BabaCore(db, FakeNotifier()), MacBridge(scope, helper = null), scope).also {
            it.core.updateSettings { s -> s.copy(floatingMonk = true) }
        }
    }

    @Test fun observedNotifierAnnouncesOnlyDeliveredNotifications() = runBlocking {
        val inner = FakeNotifier()
        val observed = ObservedNotifier(inner)
        val first = async { withTimeoutOrNull(2_000) { observed.posted.first() } }
        kotlinx.coroutines.yield()
        inner.fail = true
        observed.post(stretch.copy(id = "failed"))
        inner.fail = false
        observed.post(stretch)
        assertEquals("h1", first.await()?.id)
    }

    @Test fun closeHidesUntilTheNextNotification() {
        val app = app()
        assertTrue(app.isFloatingVisible())
        app.dismissFloating()
        assertFalse(app.isFloatingVisible())
        assertTrue(app.core.settings.value.floatingMonk, "closing is not the same as turning the feature off")
        app.onNotificationPosted(stretch)
        assertTrue(app.isFloatingVisible())
        assertEquals("🧘 Stand & stretch", app.floatingNotice?.title)
    }

    @Test fun menuCheckboxShowsAndTurnsOff() {
        val app = app()
        app.dismissFloating()
        app.setFloatingVisible(true)
        assertTrue(app.isFloatingVisible())
        app.setFloatingVisible(false)
        assertFalse(app.core.settings.value.floatingMonk)
        app.onNotificationPosted(stretch)
        assertFalse(app.isFloatingVisible(), "a notification never re-enables a feature the user turned off")
    }

    @Test fun noticesExpire() {
        val app = app()
        val t = Instant.parse("2026-10-22T10:00:00Z")
        app.onNotificationPosted(stretch, t)
        assertTrue(app.floatingNotice!!.isFresh(t.plus(Duration.ofMinutes(4))))
        assertFalse(app.floatingNotice!!.isFresh(t.plus(Duration.ofMinutes(6))))
    }

    @Test fun closeButtonInTheCard() = runComposeUiTest {
        val app = app()
        app.onNotificationPosted(stretch)
        setContent {
            BabaTheme(dark = true) {
                val line by app.core.monkLine.collectAsState()
                androidx.compose.foundation.layout.Box(Modifier.size(310.dp, 130.dp)) { FloatingMonkContent(app, line, 40) }
            }
        }
        onNodeWithText("🧘 Stand & stretch").assertExists()
        onNodeWithContentDescription("Hide Floating Baba until the next notification").performClick()
        waitForIdle()
        assertTrue(app.floatingDismissed)
        assertNull(app.floatingNotice)
    }

    @Test fun renderFloatingCard() {
        val app = app()
        app.onNotificationPosted(stretch)
        val out = Paths.get("build", "screenshots").also { Files.createDirectories(it) }
        for (dark in listOf(false, true)) {
            ImageComposeScene(310, 130, Density(1f)) {
                BabaTheme(dark) { val line by app.core.monkLine.collectAsState(); FloatingMonkContent(app, line, 40) }
            }.use { scene ->
                scene.render(0)
                Files.write(out.resolve("floating-${if (dark) "dark" else "light"}.png"), scene.render(400_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }
}
