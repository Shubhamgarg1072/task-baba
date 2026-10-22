package app.taskbaba.app.ui.tasks

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import app.taskbaba.app.AppController
import app.taskbaba.app.Screen
import app.taskbaba.app.platform.MacBridge
import app.taskbaba.app.ui.theme.BabaTheme
import app.taskbaba.core.BabaCore
import app.taskbaba.core.data.DatabaseFactory
import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.platform.NotificationResponse
import app.taskbaba.core.platform.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

private object QuietNotifier : Notifier {
    override suspend fun authorization() = NotificationAuth.AUTHORIZED
    override suspend fun requestAuthorization() = authorization()
    override suspend fun post(notification: BabaNotification) = Result.success(Unit)
    override suspend fun remove(id: String) {}
    override val responses = MutableSharedFlow<NotificationResponse>()
}

/** Drives the real Today screen the way a user does. */
@OptIn(ExperimentalTestApi::class)
class AddTaskUiTest {
    private fun newApp(): AppController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = DatabaseFactory.file(Files.createTempDirectory("baba-ui").resolve("t.db"))
        return AppController(BabaCore(db, QuietNotifier), MacBridge(scope, helper = null), scope)
    }

    @Test fun newTaskButtonThenAddTask() = runComposeUiTest {
        val app = newApp()
        setContent { BabaTheme { TodayScreen(app) } }
        onNodeWithText("New task").performClick()
        waitForIdle()
        onAllNodes(hasSetTextAction() and hasText("Title")).onFirst().performTextInput("Pay electricity bill")
        onNodeWithText("Add task").performClick()
        waitForIdle()
        assertEquals(listOf("Pay electricity bill"), app.core.tasks.all().map { it.title })
    }

    @Test fun enterInEditorTitleSaves() = runComposeUiTest {
        val app = newApp()
        setContent { BabaTheme { TodayScreen(app) } }
        onNodeWithText("New task").performClick()
        waitForIdle()
        val title = onAllNodes(hasSetTextAction() and hasText("Title")).onFirst()
        title.performTextInput("Book dentist")
        title.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("Book dentist"), app.core.tasks.all().map { it.title })
        assertEquals(null, app.editor, "editor closes after saving")
    }

    @Test fun addFromAllTasksScreen() = runComposeUiTest {
        val app = newApp()
        app.screen = Screen.ALL
        setContent { BabaTheme { TaskListScreen(app, app.screen) } }
        onNodeWithText("New task").performClick()
        waitForIdle()
        onAllNodes(hasSetTextAction() and hasText("Title")).onFirst().performTextInput("Renew passport")
        onNodeWithText("Add task").performClick()
        waitForIdle()
        assertEquals(listOf("Renew passport"), app.core.tasks.all().map { it.title })
    }

    @Test fun emptyTitleShowsAnErrorInsteadOfFailingSilently() = runComposeUiTest {
        val app = newApp()
        setContent { BabaTheme { TodayScreen(app) } }
        onNodeWithText("New task").performClick()
        waitForIdle()
        onNodeWithText("Add task").performClick()
        waitForIdle()
        onNodeWithText("Give the task a title", substring = true).assertExists()
        assertEquals(0, app.core.tasks.all().size)
    }

    @Test fun quickAddWithEnter() = runComposeUiTest {
        val app = newApp()
        setContent { BabaTheme { TodayScreen(app) } }
        val field = onAllNodes(hasSetTextAction()).onFirst()
        field.performTextInput("Call the bank !h")
        field.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("Call the bank"), app.core.tasks.all().map { it.title })
    }
}
