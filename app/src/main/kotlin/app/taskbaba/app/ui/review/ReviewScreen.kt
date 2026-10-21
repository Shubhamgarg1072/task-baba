package app.taskbaba.app.ui.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.taskbaba.app.AppController
import app.taskbaba.app.ui.components.BabaCard
import app.taskbaba.app.ui.components.Pill
import app.taskbaba.app.ui.components.QuietButton
import app.taskbaba.app.ui.components.ScreenHeader
import app.taskbaba.app.ui.components.SecondaryButton
import app.taskbaba.app.ui.components.SectionTitle
import app.taskbaba.app.ui.tasks.rememberTasks
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.data.DayRecord
import app.taskbaba.core.stats.StatsCalculator
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun ReviewScreen(app: AppController) {
    val tasks = rememberTasks(app)
    val now by app.core.nowFlow.collectAsState()
    val settings by app.core.settings.state.collectAsState()
    val history by app.core.dayRepo.observeAll().collectAsState(initial = emptyList())
    val events by app.core.healthRepo.observeForDate(app.core.today()).collectAsState(initial = emptyList())
    val zone = app.core.zone()
    val today = now.atZone(zone).toLocalDate()
    val todayRec = StatsCalculator.day(tasks, events, today, zone, now, settings.dailyGoalPercent)
    val streaks = StatsCalculator.streaks(history, todayRec)
    val health = StatsCalculator.health(events)
    val last7 = (6 downTo 1).map { today.minusDays(it.toLong()) }.map { d -> history.firstOrNull { it.date == d } ?: DayRecord(d, 0, 0, 0, 0, 0, false, 0, 0, 0, false) } + todayRec
    val week = StatsCalculator.period(last7)
    var commentary by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1.4f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ScreenHeader("Daily Review", "Everything here is calculated from your stored tasks and health records.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Planned today", todayRec.planned.toString(), Modifier.weight(1f))
                Stat("Completed", "${todayRec.completed} (${todayRec.completionPercent}%)", Modifier.weight(1f))
                Stat("Overdue now", todayRec.overdue.toString(), Modifier.weight(1f))
                Stat("Streak", "${streaks.current} · best ${streaks.best}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Critical + High done", "${todayRec.priorityCompleted}/${todayRec.priorityPlanned}", Modifier.weight(1f))
                Stat("Most important task", if (todayRec.mitCompleted) "Done ✓" else if (tasks.any { it.mitDate == today }) "Not yet" else "Not chosen", Modifier.weight(1f))
                Stat("Health confirmed by you", health.confirmed.toString(), Modifier.weight(1f))
                Stat("Reminders shown", "${health.delivered} · ${health.skipped} skipped", Modifier.weight(1f))
            }
            BabaCard {
                SectionTitle("Baba's end-of-day words")
                Text(commentary ?: "Ask Baba for today's verdict whenever you like; he also sends it at the end of your workday.",
                    style = MaterialTheme.typography.bodyLarge, color = if (commentary == null) Baba.colors.textMuted else Baba.colors.text)
                SecondaryButton("Ask Baba for today's summary", { commentary = app.core.summaryLine(app.core.todayRecord()).text })
            }
            BabaCard {
                SectionTitle("Last 7 days — tasks completed vs planned")
                last7.forEach { r -> DayBar(r, r.date == today) }
                Text(
                    "Week: ${week.completed}/${week.planned} tasks (${week.completionPercent}%) · goal met on ${week.goalDays} day(s) · " +
                        "${week.healthConfirmed} health action(s) confirmed of ${week.healthDelivered} reminder(s) shown",
                    style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
                )
            }
            BabaCard {
                SectionTitle("History")
                if (history.isEmpty()) Text("Finished days appear here after midnight. Today is still being written.", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted)
                history.asReversed().take(60).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")), style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text, modifier = Modifier.width(140.dp))
                        Text("${r.completed}/${r.planned} · ${r.healthDone} health", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted, modifier = Modifier.weight(1f))
                        if (r.goalMet) Pill("Goal met", Baba.colors.success)
                        QuietButton("Delete", { app.core.dayRepo.delete(r.date); app.say("Removed the record for ${r.date}. Tasks were not touched.") })
                    }
                    if (r.summary.isNotBlank()) Text("“${r.summary}”", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted, modifier = Modifier.padding(start = 140.dp, bottom = 4.dp))
                }
            }
        }
        Column(Modifier.weight(1f).widthIn(min = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BabaCard {
                SectionTitle("How Baba ranks tasks")
                Text("Ranking is a fixed, transparent formula — not AI. Higher score goes first:", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.text)
                listOf(
                    "Priority: Critical +100 · High +70 · Medium +40 · Low +15",
                    "Your importance: Important +12 · Vital +25",
                    "Most Important Task of the day: +50",
                    "Overdue: +60, plus +2 per hour late (max +40)",
                    "Due within 2 h +45 · within 6 h +35 · later today +25",
                    "Due within 3 days +12 · within a week +5",
                    "Each postponement +3 (max +15)",
                    "Quick win (≤ 15 min, due within 24 h) +5",
                    "Snoozed tasks wait below active ones until the snooze ends",
                    "Ties: your manual order, then earliest deadline, then oldest",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted) }
            }
            BabaCard {
                SectionTitle("What counts")
                Text(
                    "Planned = tasks due that day, tasks completed that day, and the day's Most Important Task. " +
                        "A day meets the goal when at least ${settings.dailyGoalPercent}% of planned tasks are done. " +
                        "Days with nothing planned don't break your streak. A health reminder being shown is never counted as done — only your “Done” or “I just did” is.",
                    style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    BabaCard(modifier, padding = 14.dp) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted)
        Text(value, style = MaterialTheme.typography.titleLarge, color = Baba.colors.text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayBar(r: DayRecord, isToday: Boolean) {
    val fraction = if (r.planned == 0) 0f else (r.completed.toFloat() / r.planned).coerceIn(0f, 1f)
    TooltipArea(tooltip = {
        Surface(shape = RoundedCornerShape(6.dp), color = Baba.colors.text) {
            Text("${r.date}: ${r.completed} of ${r.planned} done · ${r.healthDone} health action(s) confirmed",
                Modifier.padding(6.dp), color = Baba.colors.background, style = MaterialTheme.typography.bodySmall)
        }
    }) {
        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isToday) "Today" else r.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted, modifier = Modifier.width(48.dp),
            )
            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Baba.colors.saffronSoft)) {
                if (fraction > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(4.dp)).background(Baba.colors.saffron))
            }
            Spacer(Modifier.width(10.dp))
            Text(if (r.planned == 0) "—" else "${r.completed}/${r.planned}", style = MaterialTheme.typography.labelMedium, color = Baba.colors.text, modifier = Modifier.width(44.dp))
        }
    }
}
