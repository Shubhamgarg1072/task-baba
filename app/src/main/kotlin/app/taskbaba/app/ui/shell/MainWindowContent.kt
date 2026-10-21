package app.taskbaba.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.taskbaba.app.AppController
import app.taskbaba.app.Screen
import app.taskbaba.app.ui.components.StatusDot
import app.taskbaba.app.ui.components.StatusTone
import app.taskbaba.app.ui.monk.MonkCharacter
import app.taskbaba.app.ui.tasks.rememberTasks
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.monk.MonkMood
import app.taskbaba.core.tasks.TaskService
import app.taskbaba.core.tasks.TaskFilter
import app.taskbaba.core.tasks.SortMode
import app.taskbaba.core.tasks.TaskView

data class SidebarExtras(val inboxCount: Int = 0, val aiLabel: String? = null, val aiHealthy: Boolean = false)

@Composable
fun MainWindowContent(app: AppController, extras: SidebarExtras, content: @Composable (Screen) -> Unit) {
    Row(Modifier.fillMaxSize().background(Baba.colors.background)) {
        Sidebar(app, extras)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            content(app.screen)
            app.message?.let { msg ->
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(18.dp),
                    shape = RoundedCornerShape(10.dp), color = Baba.colors.text, shadowElevation = 6.dp,
                ) { Text(msg, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = Baba.colors.background, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun Sidebar(app: AppController, extras: SidebarExtras) {
    val tasks = rememberTasks(app)
    val now by app.core.nowFlow.collectAsState()
    val zone = app.core.zone()
    fun count(view: TaskView) = TaskService.select(tasks, view, TaskFilter(), SortMode.SMART, now, zone).count { !it.task.isCompleted }
    val line by app.core.monkLine.collectAsState()

    Column(
        Modifier.width(220.dp).fillMaxHeight().background(Baba.colors.sidebar).padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.padding(start = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MonkCharacter(MonkMood.CALM, 34.dp, animate = false)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Task Baba", style = MaterialTheme.typography.titleLarge, color = Baba.colors.text)
                Text("Karma is watching", style = MaterialTheme.typography.labelSmall, color = Baba.colors.textMuted)
            }
        }
        Item(app, Screen.TODAY, Icons.Outlined.WbSunny, count(TaskView.TODAY))
        Item(app, Screen.UPCOMING, Icons.Outlined.CalendarMonth, count(TaskView.UPCOMING))
        Item(app, Screen.OVERDUE, Icons.Outlined.WarningAmber, count(TaskView.OVERDUE), alert = true)
        Item(app, Screen.COMPLETED, Icons.Outlined.CheckCircle, null)
        Item(app, Screen.ALL, Icons.Outlined.List, null)
        Spacer(Modifier.height(12.dp))
        Item(app, Screen.INBOX, Icons.Outlined.Inbox, extras.inboxCount.takeIf { it > 0 })
        Item(app, Screen.HEALTH, Icons.Outlined.FavoriteBorder, null)
        Item(app, Screen.REVIEW, Icons.Outlined.Insights, null)
        Spacer(Modifier.height(12.dp))
        Item(app, Screen.AI, Icons.Outlined.AutoAwesome, null)
        Item(app, Screen.SETTINGS, Icons.Outlined.Settings, null)
        Spacer(Modifier.weight(1f))
        extras.aiLabel?.let { StatusDot(if (extras.aiHealthy) StatusTone.OK else StatusTone.NEUTRAL, it, Modifier.padding(6.dp)) }
        Text(
            line.text, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted, maxLines = 3,
            modifier = Modifier.padding(6.dp),
        )
    }
}

@Composable
private fun Item(app: AppController, screen: Screen, icon: ImageVector, count: Int?, alert: Boolean = false) {
    val selected = app.screen == screen
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) Baba.colors.saffronSoft else Baba.colors.sidebar)
            .clickable { app.screen = screen; app.editor = null }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Baba.colors.saffron else Baba.colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(screen.title, style = MaterialTheme.typography.bodyLarge, color = Baba.colors.text, modifier = Modifier.weight(1f))
        if (count != null && count > 0) {
            Text(
                "$count", style = MaterialTheme.typography.labelMedium,
                color = if (alert) Baba.colors.danger else Baba.colors.textMuted,
            )
        }
    }
}
