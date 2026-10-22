package app.taskbaba.app.ui.shell

import androidx.compose.foundation.background
import app.taskbaba.core.monk.MonkLine
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import app.taskbaba.app.AppController
import app.taskbaba.app.Screen
import app.taskbaba.app.ui.monk.AnimatedBaba
import app.taskbaba.app.ui.tasks.QuickAddField
import app.taskbaba.app.ui.tasks.rememberTasks
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.app.ui.theme.BabaTheme
import app.taskbaba.core.model.HealthOutcome
import app.taskbaba.core.stats.StatsCalculator
import app.taskbaba.core.tasks.TaskService
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import java.awt.GraphicsEnvironment
import java.time.Duration
import java.time.format.DateTimeFormatter

/** Menu bar glyph: a tiny saffron Baba head with tilak, readable on light and dark menu bars. */
private class TrayGlyph : Painter() {
    override val intrinsicSize = Size(22f, 22f)
    override fun DrawScope.onDraw() {
        val w = size.width
        drawCircle(Color(0xFFE07A1F), radius = w * 0.42f, center = Offset(w / 2, w / 2))
        drawRect(Color.White, Offset(w * 0.32f, w * 0.30f), Size(w * 0.36f, w * 0.07f))
        drawRect(Color.White, Offset(w * 0.32f, w * 0.42f), Size(w * 0.36f, w * 0.07f))
        drawCircle(Color(0xFF8A2A05), radius = w * 0.07f, center = Offset(w / 2, w * 0.40f))
        drawRect(Color.White, Offset(w * 0.36f, w * 0.66f), Size(w * 0.28f, w * 0.06f))
    }
}

/**
 * Menu bar companion. AWT renders this as a native NSMenu, rebuilt whenever its state changes, so
 * the progress line, top priorities and next reminder stay current while the window is closed.
 */
@Composable
fun ApplicationScope.BabaTray(app: AppController, aiStatus: String?, inboxCount: Int) {
    val tasks = rememberTasks(app)
    val now by app.core.nowFlow.collectAsState()
    val upcoming by app.core.upcomingReminders.collectAsState()
    val settings by app.core.settings.state.collectAsState()
    val zone = app.core.zone()
    val today = now.atZone(zone).toLocalDate()
    val rec = StatsCalculator.day(tasks, emptyList(), today, zone, now, settings.dailyGoalPercent)
    val top = TaskService.topPriorities(tasks, now, zone)
    val glyph = remember { TrayGlyph() }
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    val paused = settings.pausedUntil?.takeIf { it > now.toEpochMilli() }

    Tray(
        icon = glyph,
        tooltip = "Task Baba — ${rec.completed}/${rec.planned} done today",
        onAction = { app.open(app.screen) },
        menu = {
            Item("Today: ${rec.completed} of ${rec.planned} done (${rec.completionPercent}%)", enabled = false, onClick = {})
            Separator()
            if (top.isEmpty()) Item("No priorities right now", enabled = false, onClick = {})
            top.forEachIndexed { i, r ->
                Item("✓ ${i + 1}. ${r.task.title.take(48)}", onClick = { app.report(app.core.tasks.complete(r.task.id), "Completed \"${r.task.title.take(40)}\".") })
            }
            Item("Quick add task…", onClick = { app.quickAddVisible = true })
            Separator()
            val next = upcoming.firstOrNull()
            Item(
                when {
                    paused != null -> "Reminders paused until ${java.time.Instant.ofEpochMilli(paused).atZone(zone).format(hm)}"
                    next != null -> "Next: ${next.type.emoji} ${next.type.label} at ${next.at.atZone(zone).format(hm)}"
                    else -> "No health reminders scheduled"
                },
                enabled = false, onClick = {},
            )
            app.core.pendingHealth().lastOrNull()?.let { e ->
                Item("Done: ${e.type.label}", onClick = { app.core.respondHealth(e.id, HealthOutcome.DONE) })
            }
            Menu("Snooze reminders") {
                Item("For 30 minutes", onClick = { app.core.pauseAll(Duration.ofMinutes(30)) })
                Item("For 1 hour", onClick = { app.core.pauseAll(Duration.ofHours(1)) })
                Item("For 2 hours", onClick = { app.core.pauseAll(Duration.ofHours(2)) })
                Item("Until tomorrow", onClick = { app.core.pauseUntilTomorrow() })
                if (paused != null) Item("Resume now", onClick = { app.core.pauseAll(null) })
            }
            if (inboxCount > 0) Item("Smart Inbox: $inboxCount new", onClick = { app.open(Screen.INBOX) })
            aiStatus?.let { Item("AI: $it", enabled = false, onClick = {}) }
            Separator()
            CheckboxItem("Floating Baba", checked = app.isFloatingVisible(settings), onCheckedChange = { v -> app.setFloatingVisible(v) })
            CheckboxItem("Mute Baba", checked = settings.monkMuted, onCheckedChange = { v -> app.core.updateSettings { it.copy(monkMuted = v) } })
            Item("Open Task Baba", onClick = { app.open(Screen.TODAY) })
            Item("Settings…", onClick = { app.open(Screen.SETTINGS) })
            Separator()
            Item("Quit Task Baba", onClick = ::exitApplication)
        },
    )
}

@Composable
fun QuickAddWindow(app: AppController, dark: Boolean) {
    if (!app.quickAddVisible) return
    val state = rememberWindowState(size = DpSize(520.dp, 150.dp), position = WindowPosition(Alignment.Center))
    Window(
        onCloseRequest = { app.quickAddVisible = false }, state = state, title = "Quick add — Task Baba",
        resizable = false, alwaysOnTop = true,
    ) {
        BabaTheme(dark) {
            Column(Modifier.fillMaxSize().background(Baba.colors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickAddField(app, defaultDue = app.core.today(), autoFocusHint = "What needs doing? (Enter to add, “!h” for High)")
                Text("Added tasks go to Today. Close this window when done.", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
            }
        }
    }
}

/**
 * Small always-on-top Baba. Not focusable, so typing elsewhere is never interrupted; drag to move;
 * position is remembered and clamped to the visible screens (handles unplugged displays).
 */
@Composable
fun FloatingMonk(app: AppController, dark: Boolean) {
    val settings by app.core.settings.state.collectAsState()
    if (!app.isFloatingVisible(settings)) return
    val size = DpSize(310.dp, 130.dp)
    val start = remember { clampedPosition(settings.floatingMonkX, settings.floatingMonkY, 310, 130) }
    val state = rememberWindowState(size = size, position = start)
    val line by app.core.monkLine.collectAsState()

    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.position }
            .drop(1).map { it as? WindowPosition.Absolute }.filterNotNull().debounce(500)
            .collect { p -> app.core.settings.update { it.copy(floatingMonkX = p.x.value.toInt(), floatingMonkY = p.y.value.toInt()) } }
    }

    Window(
        onCloseRequest = { app.core.updateSettings { it.copy(floatingMonk = false) } },
        state = state, title = "Floating Baba", undecorated = true, transparent = true, resizable = false,
        alwaysOnTop = true, focusable = false,
    ) {
        BabaTheme(dark, reduceMotion = app.reduceMotion(settings)) {
            WindowDraggableArea { FloatingMonkContent(app, line, settings.animationFps) }
        }
    }
}

/** The card inside Floating Baba: the latest notification (if fresh) or Baba's line, plus a × to hide him. */
@Composable
fun FloatingMonkContent(app: AppController, line: MonkLine, fps: Int) {
    val now by app.core.nowFlow.collectAsState()
    val notice = app.floatingNotice?.takeIf { it.isFresh(now) }
    Box(
        Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(16.dp))
            .background(Baba.colors.card.copy(alpha = 0.97f))
            .border(1.dp, Baba.colors.cardBorder, RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier.fillMaxSize().clickable { app.open(if (notice != null) Screen.TODAY else app.screen) }.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedBaba(line, 84.dp, fps)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (notice != null) {
                    Text(notice.title, style = MaterialTheme.typography.titleSmall, color = Baba.colors.saffron, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(notice.body, style = MaterialTheme.typography.bodySmall, color = Baba.colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(line.text, style = MaterialTheme.typography.bodySmall, color = Baba.colors.text, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        IconButton(onClick = { app.dismissFloating() }, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(26.dp)) {
            Icon(Icons.Filled.Close, "Hide Floating Baba until the next notification", tint = Baba.colors.textMuted, modifier = Modifier.size(16.dp))
        }
    }
}

private fun clampedPosition(x: Int?, y: Int?, w: Int, h: Int): WindowPosition {
    val screens = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds } }.getOrDefault(emptyList())
    if (x != null && y != null && screens.any { it.contains(x + w / 2, y + h / 2) }) return WindowPosition(x.dp, y.dp)
    val main = screens.firstOrNull() ?: return WindowPosition(Alignment.BottomEnd)
    return WindowPosition((main.x + main.width - w - 24).dp, (main.y + main.height - h - 90).dp)
}
