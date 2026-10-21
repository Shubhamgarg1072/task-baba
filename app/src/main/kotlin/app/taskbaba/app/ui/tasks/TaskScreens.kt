package app.taskbaba.app.ui.tasks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.taskbaba.app.AppController
import app.taskbaba.app.Screen
import app.taskbaba.app.ui.components.BabaCard
import app.taskbaba.app.ui.components.PrimaryButton
import app.taskbaba.app.ui.components.QuietButton
import app.taskbaba.app.ui.components.ScreenHeader
import app.taskbaba.app.ui.components.SecondaryButton
import app.taskbaba.app.ui.components.SectionTitle
import app.taskbaba.app.ui.health.HealthCompactCard
import app.taskbaba.app.ui.monk.MonkCharacter
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.model.Task
import app.taskbaba.core.monk.MonkEvent
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.stats.StatsCalculator
import app.taskbaba.core.tasks.SortMode
import app.taskbaba.core.tasks.TaskService
import app.taskbaba.core.tasks.TaskView
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun rememberTasks(app: AppController): List<Task> =
    app.core.tasks.observe().collectAsState(initial = app.core.tasks.all()).value

@Composable
fun TodayScreen(app: AppController) {
    val tasks = rememberTasks(app)
    val now by app.core.nowFlow.collectAsState()
    val zone = app.core.zone()
    val today = now.atZone(zone).toLocalDate()
    val settings by app.core.settings.state.collectAsState()
    val listed = TaskService.select(tasks, TaskView.TODAY, app.filter, app.sort, now, zone)
    val top = TaskService.topPriorities(tasks, now, zone)
    val record = StatsCalculator.day(tasks, emptyList(), today, zone, now, settings.dailyGoalPercent)
    val overdue = tasks.count { it.isOverdue(now, zone) }
    val history by app.core.dayRepo.observeAll().collectAsState(initial = emptyList())
    val streaks = StatsCalculator.streaks(history, record)

    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1.6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenHeader(
                "Today",
                today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())) + " · Get your tasks done, my child. Karma is watching.",
            ) { PrimaryButton("New task", { app.newTask() }) }
            MonkPanel(app)
            NotificationPermissionCard(app)
            BabaCard {
                SectionTitle("Top 3 priorities") { if (top.isNotEmpty()) QuietButton("How ranking works", { app.open(Screen.REVIEW) }) }
                if (top.isEmpty()) {
                    Text("Nothing urgent. Add a task, or enjoy the calm.", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted)
                } else top.forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleLarge, color = Baba.colors.saffron, modifier = Modifier.width(22.dp))
                        Box(Modifier.weight(1f)) { TaskRow(r, app, now, zone, showReasons = true, compact = true) }
                    }
                }
            }
            BabaCard {
                SectionTitle("Today's list") { Text("${record.completed}/${record.planned} done", style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted) }
                QuickAddField(app, defaultDue = today)
                if (listed.isEmpty()) {
                    Text(
                        if (overdue > 0) "Nothing due today — but $overdue task(s) are overdue." else "No tasks for today yet.",
                        style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted,
                    )
                    if (overdue > 0) QuietButton("Review overdue tasks", { app.open(Screen.OVERDUE) })
                }
                listed.forEach { r -> TaskRow(r, app, now, zone, showReasons = false) }
            }
        }
        Column(Modifier.weight(1f).widthIn(min = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val editor = app.editor
            if (editor != null) {
                TaskEditorPane(app, editor, defaultDate = today)
            } else {
                ProgressCard(record.completed, record.planned, record.completionPercent, streaks.current, streaks.best, overdue, settings.dailyGoalPercent, app)
                HealthCompactCard(app)
            }
        }
    }
}

@Composable
fun TaskListScreen(app: AppController, screen: Screen) {
    val tasks = rememberTasks(app)
    val now by app.core.nowFlow.collectAsState()
    val zone = app.core.zone()
    val view = screen.view ?: TaskView.ALL
    val listed = TaskService.select(tasks, view, app.filter, app.sort, now, zone)
    val subtitle = when (view) {
        TaskView.UPCOMING -> "Everything with a deadline after today."
        TaskView.OVERDUE -> "Past their deadline. Finish, reschedule, or let go — all are fine choices."
        TaskView.COMPLETED -> "Finished work. Reopen anything that wasn't really done."
        TaskView.ALL -> "Every task, including ones without a date."
        TaskView.TODAY -> ""
    }
    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ScreenHeader(screen.title, subtitle) { PrimaryButton("New task", { app.newTask() }) }
            SearchAndFilters(app)
            if (view != TaskView.COMPLETED) QuickAddField(app, defaultDue = if (view == TaskView.UPCOMING) app.core.today().plusDays(1) else null)
            BabaCard(Modifier.weight(1f), padding = 8.dp) {
                if (listed.isEmpty()) {
                    Text(
                        when {
                            app.filter.query.isNotBlank() || app.filter.priorities.isNotEmpty() || app.filter.categories.isNotEmpty() -> "No tasks match these filters."
                            view == TaskView.OVERDUE -> "Nothing overdue. Baba is pleased."
                            view == TaskView.COMPLETED -> "Nothing completed yet. The first one is the hardest."
                            else -> "No tasks here yet."
                        },
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted,
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(listed, key = { it.task.id }) { r ->
                        val openOrdered = listed.filter { !it.task.isCompleted }.map { it.task }
                        TaskRow(
                            r, app, now, zone, showReasons = app.sort == SortMode.SMART && view != TaskView.COMPLETED,
                            manualMove = if (app.sort == SortMode.MANUAL) { d -> app.report(app.core.tasks.move(r.task.id, openOrdered, d)) } else null,
                        )
                    }
                }
            }
        }
        val editor = app.editor
        Box(Modifier.weight(1f).widthIn(min = 300.dp)) {
            if (editor != null) TaskEditorPane(app, editor, defaultDate = if (view == TaskView.UPCOMING) app.core.today().plusDays(1) else null)
            else BabaCard {
                SectionTitle("Tip")
                Text(
                    "Click a task to edit it. Hover a task to see why it's ranked where it is. Use “My order” sorting to arrange tasks by hand.",
                    style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted,
                )
            }
        }
    }
}

@Composable
fun MonkPanel(app: AppController, characterSize: Dp = 96.dp) {
    val line by app.core.monkLine.collectAsState()
    val settings by app.core.settings.state.collectAsState()
    BabaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonkCharacter(line.mood, characterSize, animate = true)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (settings.monkMuted) "Baba (muted)" else settings.personality.label,
                    style = MaterialTheme.typography.titleSmall, color = Baba.colors.saffron,
                )
                AnimatedContent(line.text, transitionSpec = {
                    if (app.reduceMotion(settings)) fadeIn(tween(0)) togetherWith fadeOut(tween(0)) else fadeIn(tween(350)) togetherWith fadeOut(tween(200))
                }, label = "monk") { text ->
                    Text(text, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal), color = Baba.colors.text)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    QuietButton("Another wisdom", { app.core.speak(MonkEvent.FOCUS_TIP) })
                    QuietButton("How am I doing?", { app.core.speak(app.core.summaryLine(app.core.todayRecord()).event) })
                    QuietButton(if (settings.monkMuted) "Unmute Baba" else "Mute Baba", {
                        app.core.updateSettings { it.copy(monkMuted = !it.monkMuted) }
                    })
                }
            }
        }
    }
}

@Composable
fun NotificationPermissionCard(app: AppController) {
    when (app.notificationAuth) {
        NotificationAuth.NOT_DETERMINED -> BabaCard {
            SectionTitle("Let Baba reach you")
            Text(
                "Task Baba uses macOS notifications for health breaks and deadlines, with buttons like Done and Remind me later. " +
                    "Nothing leaves your Mac. You can change this any time in System Settings.",
                style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Allow notifications", { app.requestNotifications() })
                SecondaryButton("Not now", { app.core.updateSettings { it.copy(onboarded = true) }; app.say("Reminders will appear inside Task Baba only.") })
            }
        }
        NotificationAuth.DENIED -> BabaCard {
            SectionTitle("Notifications are off")
            Text("Reminders still appear here and in the menu bar, but macOS won't show banners.", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Open System Settings", { app.openNotificationSettings() })
                QuietButton("Check again", { app.scope.launch { app.refreshNotificationAuth() } })
            }
        }
        else -> Unit
    }
}

@Composable
fun ProgressCard(done: Int, planned: Int, pct: Int, streak: Int, best: Int, overdue: Int, goal: Int, app: AppController) {
    BabaCard {
        SectionTitle("Today's progress")
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(if (planned == 0) 0f else done / planned.toFloat(), 86.dp, app)
            Spacer(Modifier.width(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$done of $planned done", style = MaterialTheme.typography.titleLarge, color = Baba.colors.text)
                Text(if (planned == 0) "Nothing planned yet" else "$pct% · goal $goal%", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted)
                Text("Streak: $streak day(s) · best $best", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted)
                if (overdue > 0) QuietButton("$overdue overdue →", { app.open(Screen.OVERDUE) })
            }
        }
    }
}

@Composable
fun ProgressRing(fraction: Float, size: Dp, app: AppController) {
    val settings by app.core.settings.state.collectAsState()
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), if (app.reduceMotion(settings)) tween(0) else tween(700), label = "ring")
    val track = Baba.colors.saffronSoft
    val fill = Baba.colors.saffron
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.12f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(fill, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = Baba.colors.text)
    }
}

@Composable
fun Divider() = HorizontalDivider(color = Baba.colors.cardBorder, modifier = Modifier.padding(vertical = 4.dp).height(1.dp))
