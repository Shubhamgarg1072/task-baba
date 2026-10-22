package app.taskbaba.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import app.taskbaba.app.AppController
import app.taskbaba.app.ui.components.BabaCard
import app.taskbaba.app.ui.components.PrimaryButton
import app.taskbaba.app.ui.components.ScreenHeader
import app.taskbaba.app.ui.components.SecondaryButton
import app.taskbaba.app.ui.components.SectionTitle
import app.taskbaba.app.ui.components.StatusDot
import app.taskbaba.app.ui.components.StatusTone
import app.taskbaba.app.ui.components.ToggleRow
import app.taskbaba.app.ui.health.ClockField
import app.taskbaba.app.ui.monk.AnimatedBaba
import app.taskbaba.app.ui.monk.PlayRequest
import app.taskbaba.app.ui.monk.rig.BabaAnimations
import app.taskbaba.app.ui.tasks.SmallChip
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.data.AppPaths
import app.taskbaba.core.model.AppSettings
import app.taskbaba.core.model.MotionPreference
import app.taskbaba.core.model.Personality
import app.taskbaba.core.model.TimeWindow
import app.taskbaba.core.model.Weekday
import app.taskbaba.core.platform.LoginItemStatus
import app.taskbaba.core.platform.NotificationAuth

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(app: AppController, extraSections: @Composable () -> Unit = {}) {
    val s by app.core.settings.state.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmHealthWipe by remember { mutableStateOf(false) }
    fun update(f: (app.taskbaba.core.model.AppSettings) -> app.taskbaba.core.model.AppSettings) {
        val next = f(s)
        val problems = next.validate()
        if (problems.isEmpty()) app.core.updateSettings(f) else app.say(problems.first())
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("Settings", "Saved instantly on this Mac.")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).widthIn(min = 320.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BabaCard {
                    SectionTitle("Baba's personality")
                    Personality.entries.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallChip(p.label, s.personality == p) { update { it.copy(personality = p) } }
                            Spacer(Modifier.width(8.dp))
                            Text(p.blurb, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                        }
                    }
                    ToggleRow("Allow sarcasm", s.sarcasmEnabled, { v -> update { it.copy(sarcasmEnabled = v) } }, "Off = Baba is always gentle, whatever the personality.")
                    ToggleRow("Mute Baba", s.monkMuted, { v -> update { it.copy(monkMuted = v) } }, "Plain, joke-free wording everywhere.")
                }
                BabaCard {
                    SectionTitle("Notifications")
                    val (tone, label) = when (app.notificationAuth) {
                        NotificationAuth.AUTHORIZED -> StatusTone.OK to "macOS notifications allowed"
                        NotificationAuth.DENIED -> StatusTone.ERROR to "Blocked in System Settings — reminders show in-app only"
                        NotificationAuth.NOT_DETERMINED -> StatusTone.WARN to "Not asked yet"
                        NotificationAuth.REFUSED -> StatusTone.WARN to "macOS refused permission for this copy — open Task Baba from Finder and try again (simple banners meanwhile)"
                        NotificationAuth.UNAVAILABLE -> StatusTone.WARN to "Native bridge unavailable — simple banners without buttons"
                    }
                    StatusDot(tone, label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (app.notificationAuth == NotificationAuth.NOT_DETERMINED || app.notificationAuth == NotificationAuth.REFUSED) PrimaryButton("Allow notifications", { app.requestNotifications() })
                        SecondaryButton("Send test notification", { app.sendTestNotification() })
                        SecondaryButton("System Settings…", { app.openNotificationSettings() })
                    }
                    ToggleRow("Send notifications", s.notificationsEnabled, { v -> update { it.copy(notificationsEnabled = v) } })
                    ToggleRow("Task deadlines", s.taskNotifications, { v -> update { it.copy(taskNotifications = v) } }, "Due-soon (15 min) and overdue alerts, once per task.", enabled = s.notificationsEnabled)
                    ToggleRow("Health reminders", s.healthNotifications, { v -> update { it.copy(healthNotifications = v) } }, enabled = s.notificationsEnabled)
                    ToggleRow("End-of-day summary", s.endOfDaySummary, { v -> update { it.copy(endOfDaySummary = v) } }, "At the end of your workday.")
                    ToggleRow("Sounds", s.soundEnabled, { v -> update { it.copy(soundEnabled = v) } })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("At most", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text)
                        IconButton({ update { it.copy(maxNotificationsPerHour = (it.maxNotificationsPerHour - 1).coerceAtLeast(1)) } }) { Icon(Icons.Filled.Remove, "Fewer") }
                        Text("${s.maxNotificationsPerHour}", style = MaterialTheme.typography.titleMedium, color = Baba.colors.text)
                        IconButton({ update { it.copy(maxNotificationsPerHour = (it.maxNotificationsPerHour + 1).coerceAtMost(30)) } }) { Icon(Icons.Filled.Add, "More") }
                        Text("notifications per hour", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text)
                    }
                }
                BabaCard {
                    SectionTitle("Quiet hours & workday")
                    ToggleRow("Quiet hours", s.quietHoursEnabled, { v -> update { it.copy(quietHoursEnabled = v) } }, "No notifications at all during this window.")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClockField(s.quietHours.start, "From") { t -> update { it.copy(quietHours = TimeWindow(t, it.quietHours.end)) } }
                        ClockField(s.quietHours.end, "To") { t -> update { it.copy(quietHours = TimeWindow(it.quietHours.start, t)) } }
                    }
                    Text("Workday (used for the end-of-day summary and “pause until tomorrow”)", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClockField(s.workday.start, "Starts") { t -> update { it.copy(workday = TimeWindow(t, it.workday.end)) } }
                        ClockField(s.workday.end, "Ends") { t -> update { it.copy(workday = TimeWindow(it.workday.start, t)) } }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Weekday.entries.forEach { d ->
                            SmallChip(d.name.lowercase().replaceFirstChar(Char::uppercase), d in s.workdays) {
                                update { it.copy(workdays = if (d in it.workdays) it.workdays - d else it.workdays + d) }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Daily goal", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text)
                        IconButton({ update { it.copy(dailyGoalPercent = (it.dailyGoalPercent - 10).coerceAtLeast(10)) } }) { Icon(Icons.Filled.Remove, "Lower goal") }
                        Text("${s.dailyGoalPercent}%", style = MaterialTheme.typography.titleMedium, color = Baba.colors.text)
                        IconButton({ update { it.copy(dailyGoalPercent = (it.dailyGoalPercent + 10).coerceAtMost(100)) } }) { Icon(Icons.Filled.Add, "Raise goal") }
                        Text("of planned tasks", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text)
                    }
                }
            }
            Column(Modifier.weight(1f).widthIn(min = 320.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BabaCard {
                    SectionTitle("App")
                    val loginLabel = when (app.loginItemStatus) {
                        LoginItemStatus.ENABLED -> "Task Baba opens when you log in."
                        LoginItemStatus.REQUIRES_APPROVAL -> "Waiting for approval in System Settings › General › Login Items."
                        LoginItemStatus.DISABLED -> "Reminders only run while Task Baba is open."
                        LoginItemStatus.UNAVAILABLE -> "Only available in the installed app (not in dev runs)."
                    }
                    ToggleRow("Launch at login", app.loginItemStatus == LoginItemStatus.ENABLED || app.loginItemStatus == LoginItemStatus.REQUIRES_APPROVAL,
                        { app.setLaunchAtLogin(it) }, loginLabel, enabled = app.loginItemStatus != LoginItemStatus.UNAVAILABLE)
                    ToggleRow("Floating Baba", s.floatingMonk, { v -> update { it.copy(floatingMonk = v) } }, "A small always-on-top Baba you can drag anywhere. Never takes keyboard focus.")
                    Text("Animations", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MotionPreference.entries.forEach { m -> SmallChip(m.label, s.motion == m) { update { it.copy(motion = m) } } }
                    }
                    Text("Baba's smoothness", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppSettings.ANIMATION_FPS_OPTIONS.forEach { f -> SmallChip("$f fps", s.animationFps == f) { update { it.copy(animationFps = f) } } }
                        Text("Higher is smoother but uses a little more battery.", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                    }
                }
                AnimationGallery(s.animationFps, app.reduceMotion(s))
                extraSections()
                BabaCard {
                    SectionTitle("Your data")
                    Text("Everything is stored locally in ${AppPaths.home}. No analytics, no accounts. Secrets live in the macOS Keychain.",
                        style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Show in Finder", { runCatching { ProcessBuilder("/usr/bin/open", AppPaths.home.toString()).start() } })
                        SecondaryButton("Delete today's health log", { confirmHealthWipe = true })
                        SecondaryButton("Reset all data…", { confirmReset = true }, danger = true)
                    }
                }
            }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset Task Baba?") },
        text = { Text("This permanently deletes all tasks, repeating tasks, notes (including secure notes and their Keychain entries), health history, daily records and settings on this Mac. Integrations and Keychain secrets are disconnected separately.") },
        confirmButton = { SecondaryButton("Delete everything", { app.core.resetAllData(); app.resetHooks.forEach { it() }; confirmReset = false; app.editor = null; app.say("All local data was deleted. A fresh start, my child.") }, danger = true) },
        dismissButton = { SecondaryButton("Cancel", { confirmReset = false }) },
    )
    if (confirmHealthWipe) AlertDialog(
        onDismissRequest = { confirmHealthWipe = false },
        title = { Text("Delete today's health log?") },
        text = { Text("Removes today's reminder and confirmation records. Tasks are not affected.") },
        confirmButton = { SecondaryButton("Delete", { app.core.healthRepo.deleteForDate(app.core.today()); confirmHealthWipe = false; app.say("Today's health log deleted.") }, danger = true) },
        dismissButton = { SecondaryButton("Cancel", { confirmHealthWipe = false }) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimationGallery(fps: Int, reduceMotion: Boolean) {
    var request by remember { mutableStateOf<PlayRequest?>(null) }
    BabaCard {
        SectionTitle("Baba's moves (${BabaAnimations.all.size})")
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedBaba(null, 140.dp, fps, request = request, paused = !LocalWindowInfo.current.isWindowFocused)
            Spacer(Modifier.width(12.dp))
            Text(
                if (reduceMotion) "Reduced motion is on, so Baba holds each move's key pose instead of animating."
                else "Baba performs these on his own when things happen — drinking water at water breaks, a facepalm after the fourth postponement, a dance when a big task is done. Tap one to preview it.",
                style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BabaAnimations.all.forEach { a -> SmallChip(a.label, request?.animationId == a.id) { request = PlayRequest(a.id) } }
        }
    }
}
