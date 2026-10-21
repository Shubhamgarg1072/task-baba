package app.taskbaba.app.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.taskbaba.app.AppController
import app.taskbaba.app.Screen
import app.taskbaba.app.ui.components.BabaCard
import app.taskbaba.app.ui.components.ErrorText
import app.taskbaba.app.ui.components.Pill
import app.taskbaba.app.ui.components.QuietButton
import app.taskbaba.app.ui.components.ScreenHeader
import app.taskbaba.app.ui.components.SecondaryButton
import app.taskbaba.app.ui.components.SectionTitle
import app.taskbaba.app.ui.components.ToggleRow
import app.taskbaba.app.ui.tasks.SmallChip
import app.taskbaba.app.ui.tasks.babaFieldColors
import app.taskbaba.app.ui.tasks.relative
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.model.ClockTime
import app.taskbaba.core.model.HealthOutcome
import app.taskbaba.core.model.HealthReminderConfig
import app.taskbaba.core.model.HealthType
import app.taskbaba.core.model.TimeWindow
import app.taskbaba.core.model.Weekday
import app.taskbaba.core.stats.StatsCalculator
import java.time.Duration
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HealthCompactCard(app: AppController) {
    val now by app.core.nowFlow.collectAsState()
    val zone = app.core.zone()
    val upcoming by app.core.upcomingReminders.collectAsState()
    val events by app.core.healthRepo.observeForDate(app.core.today()).collectAsState(initial = emptyList())
    val settings by app.core.settings.state.collectAsState()
    val pending = events.filter { it.outcome == HealthOutcome.DELIVERED && it.deliveredAt != null }.takeLast(3)
    val summary = StatsCalculator.health(events)

    BabaCard {
        SectionTitle("Health breaks") { QuietButton("Configure", { app.open(Screen.HEALTH) }) }
        settings.pausedUntil?.takeIf { it > now.toEpochMilli() }?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reminders paused until ${java.time.Instant.ofEpochMilli(it).atZone(zone).format(HM)}", style = MaterialTheme.typography.bodyMedium,
                    color = Baba.colors.warning, modifier = Modifier.weight(1f))
                QuietButton("Resume", { app.core.pauseAll(null) })
            }
        }
        pending.forEach { e ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${e.type.emoji} ${e.type.label} · ${e.deliveredAt!!.atZone(zone).format(HM)}", style = MaterialTheme.typography.bodyLarge, color = Baba.colors.text)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecondaryButton("Done", { app.core.respondHealth(e.id, HealthOutcome.DONE) })
                    SecondaryButton("Later", { app.core.respondHealth(e.id, HealthOutcome.SNOOZED) })
                    QuietButton("Skip", { app.core.respondHealth(e.id, HealthOutcome.SKIPPED) })
                }
            }
        }
        if (upcoming.isEmpty()) {
            Text("No reminders scheduled. Turn some on in Health.", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted)
        } else upcoming.take(3).forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${r.type.emoji} ${r.type.label}", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text, modifier = Modifier.weight(1f))
                Text("${r.at.atZone(zone).format(HM)} (${relative(r.at, now)})", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
            }
        }
        Text(
            "Today: ${summary.confirmed} confirmed by you · ${summary.delivered} reminder(s) shown · ${summary.skipped} skipped",
            style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("I just did:", style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted, modifier = Modifier.align(Alignment.CenterVertically))
            listOf(HealthType.WATER, HealthType.STRETCH, HealthType.WALK, HealthType.EYES).forEach { t ->
                SmallChip("${t.emoji} ${t.label.substringBefore(" (")}", false) { app.core.logHealth(t) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            QuietButton("Pause 1 h", { app.core.pauseAll(Duration.ofHours(1)); app.say("Reminders paused for an hour.") })
            QuietButton("Pause until tomorrow", { app.core.pauseUntilTomorrow(); app.say("Reminders paused until tomorrow's workday.") })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HealthScreen(app: AppController) {
    val settings by app.core.settings.state.collectAsState()
    val events by app.core.healthRepo.observeForDate(app.core.today()).collectAsState(initial = emptyList())
    val upcoming by app.core.upcomingReminders.collectAsState()
    val zone = app.core.zone()
    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1.5f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ScreenHeader("Health", "General wellness nudges — not medical advice. Baba only counts what you confirm.")
            BabaCard {
                ToggleRow("Health notifications", settings.healthNotifications, { v -> app.core.updateSettings { it.copy(healthNotifications = v) } },
                    "When off, reminders still appear inside Task Baba and the menu bar.")
            }
            settings.health.forEach { cfg ->
                ReminderConfigCard(cfg, upcoming.firstOrNull { it.type == cfg.type }?.at?.atZone(zone)?.format(DateTimeFormatter.ofPattern("EEE HH:mm"))) { updated ->
                    val problems = updated.validate()
                    if (problems.isEmpty()) app.core.updateSettings { s -> s.copy(health = s.health.map { if (it.type == updated.type) updated else it }) }
                    problems
                }
            }
        }
        Column(Modifier.weight(1f).widthIn(min = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HealthCompactCard(app)
            BabaCard {
                SectionTitle("Today's log")
                if (events.isEmpty()) Text("Nothing yet today.", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted)
                events.asReversed().forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.type.emoji} ${e.type.label}", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text, modifier = Modifier.weight(1f))
                        val (label, color) = when {
                            e.deliveredAt == null -> "Recorded by you" to Baba.colors.success
                            e.outcome == HealthOutcome.DONE -> "Confirmed" to Baba.colors.success
                            e.outcome == HealthOutcome.SKIPPED -> "Skipped" to Baba.colors.textMuted
                            e.outcome == HealthOutcome.SNOOZED -> "Snoozed" to Baba.colors.info
                            else -> "Shown, not confirmed" to Baba.colors.warning
                        }
                        Pill(label, color)
                        Spacer(Modifier.width(8.dp))
                        Text((e.respondedAt ?: e.deliveredAt ?: e.scheduledAt).atZone(zone).format(HM), style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderConfigCard(cfg: HealthReminderConfig, next: String?, onChange: (HealthReminderConfig) -> List<String>) {
    var errors by remember(cfg) { mutableStateOf(emptyList<String>()) }
    fun change(c: HealthReminderConfig) { errors = onChange(c) }
    BabaCard {
        ToggleRow("${cfg.type.emoji} ${cfg.type.label}", cfg.enabled, { change(cfg.copy(enabled = it)) },
            if (cfg.enabled) next?.let { "Next: $it" } ?: "Nothing fits the schedule in the next week" else "Off")
        if (cfg.enabled) {
            if (cfg.type == HealthType.MEAL) {
                Text("Meal times", style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    cfg.fixedTimes.sorted().forEach { t ->
                        SmallChip("$t ✕", true) { change(cfg.copy(fixedTimes = cfg.fixedTimes - t)) }
                    }
                    ClockField(null, "Add time") { t -> if (t !in cfg.fixedTimes) change(cfg.copy(fixedTimes = cfg.fixedTimes + t)) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text)
                    IconButton({ change(cfg.copy(intervalMinutes = (cfg.intervalMinutes - 5).coerceAtLeast(10))) }) { Icon(Icons.Filled.Remove, "Shorter interval") }
                    Text("${cfg.intervalMinutes} min", style = MaterialTheme.typography.titleMedium, color = Baba.colors.text)
                    IconButton({ change(cfg.copy(intervalMinutes = (cfg.intervalMinutes + 5).coerceAtMost(480))) }) { Icon(Icons.Filled.Add, "Longer interval") }
                    Spacer(Modifier.width(12.dp))
                    Text("between", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text)
                    Spacer(Modifier.width(8.dp))
                    ClockField(cfg.activeHours.start, "From") { change(cfg.copy(activeHours = TimeWindow(it, cfg.activeHours.end))) }
                    Spacer(Modifier.width(6.dp))
                    ClockField(cfg.activeHours.end, "To") { change(cfg.copy(activeHours = TimeWindow(cfg.activeHours.start, it))) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Weekday.entries.forEach { d ->
                    SmallChip(d.name.lowercase().replaceFirstChar(Char::uppercase), d in cfg.weekdays) {
                        change(cfg.copy(weekdays = if (d in cfg.weekdays) cfg.weekdays - d else cfg.weekdays + d))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("“Remind me later” waits", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                listOf(5, 10, 15, 30).forEach { m -> SmallChip("$m min", cfg.snoozeMinutes == m) { change(cfg.copy(snoozeMinutes = m)) } }
                Spacer(Modifier.weight(1f))
                SmallChip(if (cfg.sound) "Sound on" else "Silent", cfg.sound) { change(cfg.copy(sound = !cfg.sound)) }
            }
        }
        ErrorText(errors)
    }
}

/** HH:mm entry that commits on Enter or when the text becomes a valid time. */
@Composable
fun ClockField(value: ClockTime?, label: String, onCommit: (ClockTime) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    var bad by remember { mutableStateOf(false) }
    OutlinedTextField(
        text,
        { raw ->
            text = raw.filter { it.isDigit() || it == ':' }.take(5)
            val parsed = parseClock(text)
            bad = text.length >= 4 && parsed == null
            if (parsed != null && text.length == 5 && parsed != value) { onCommit(parsed); if (value == null) text = "" }
        },
        label = { Text(label) }, singleLine = true, isError = bad,
        modifier = Modifier.width(96.dp), shape = RoundedCornerShape(8.dp), colors = babaFieldColors(),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

fun parseClock(s: String): ClockTime? {
    val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(s.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) ClockTime.of(h, min) else null
}
