package app.taskbaba.app.ui.tasks

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.taskbaba.app.AppController
import app.taskbaba.app.Editor
import app.taskbaba.app.ui.components.Pill
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.model.Category
import app.taskbaba.core.model.Due
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.Task
import app.taskbaba.core.model.TaskDraft
import app.taskbaba.core.tasks.RankedTask
import app.taskbaba.core.tasks.SortMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun priorityColor(p: Priority): Color = when (p) {
    Priority.CRITICAL -> Baba.colors.critical
    Priority.HIGH -> Baba.colors.high
    Priority.MEDIUM -> Baba.colors.medium
    Priority.LOW -> Baba.colors.low
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

fun dueLabel(task: Task, now: Instant, zone: ZoneId): String? {
    val due = task.due ?: return null
    val today = now.atZone(zone).toLocalDate()
    val date = due.localDate(zone)
    val dayPart = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> if (date.isAfter(today) && date.isBefore(today.plusDays(7))) date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) else date.format(DAY)
    }
    val time = (due as? Due.At)?.instant?.atZone(zone)?.toLocalTime()?.format(TIME)
    return if (time != null) "$dayPart $time" else dayPart
}

fun relative(at: Instant, now: Instant): String {
    val d = Duration.between(now, at)
    val mins = d.toMinutes()
    return when {
        mins < 1 -> "now"
        mins < 60 -> "in $mins min"
        mins < 24 * 60 -> "in ${mins / 60} h ${mins % 60} min"
        else -> "in ${mins / (24 * 60)} days"
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    ranked: RankedTask,
    app: AppController,
    now: Instant,
    zone: ZoneId,
    showReasons: Boolean,
    manualMove: ((Int) -> Unit)? = null,
    compact: Boolean = false,
) {
    val t = ranked.task
    val today = now.atZone(zone).toLocalDate()
    val tasks = app.core.tasks
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) Baba.colors.saffronSoft.copy(alpha = 0.35f) else Color.Transparent, label = "rowbg")
    val selected = (app.editor as? Editor.Edit)?.id == t.id
    var snoozeMenu by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Baba.colors.saffronSoft.copy(alpha = 0.6f) else bg)
            .hoverable(interaction)
            .clickable { app.editor = Editor.Edit(t.id) }
            .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompleteCircle(t.isCompleted, priorityColor(t.priority)) {
            app.report(if (t.isCompleted) tasks.reopen(t.id) else tasks.complete(t.id))
        }
        Spacer(Modifier.width(12.dp))
        TooltipArea(
            tooltip = {
                Surface(shape = RoundedCornerShape(8.dp), color = Baba.colors.text, shadowElevation = 4.dp) {
                    Text(
                        "Why here: " + ranked.reasons.joinToString(" · ") + if (!t.isCompleted) "  (score ${ranked.score})" else "",
                        Modifier.padding(8.dp), color = Baba.colors.background, style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            delayMillis = 500,
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
            modifier = Modifier.weight(1f),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.isMit(today)) {
                        Icon(Icons.Filled.Star, "Most important task", tint = Baba.colors.saffron, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (t.isCompleted) Baba.colors.textMuted else Baba.colors.text,
                        textDecoration = if (t.isCompleted) TextDecoration.LineThrough else null,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Pill(t.priority.label, priorityColor(t.priority))
                    if (!compact) Pill(t.category.label, Baba.colors.textMuted)
                    dueLabel(t, now, zone)?.let { label ->
                        val overdue = t.isOverdue(now, zone)
                        Pill(if (overdue) "Overdue · $label" else label, if (overdue) Baba.colors.danger else Baba.colors.info)
                    }
                    t.estimatedMinutes?.let { Pill("~$it min", Baba.colors.textMuted) }
                    if (t.seriesId != null) Pill("Repeats", Baba.colors.textMuted)
                    if (t.postponeCount > 0 && !t.isCompleted) Pill("Postponed ${t.postponeCount}×", Baba.colors.warning)
                    if (t.isSnoozed(now)) Pill("Snoozed until ${t.snoozedUntil!!.atZone(zone).toLocalTime().format(TIME)}", Baba.colors.textMuted)
                }
                if (showReasons && !t.isCompleted) {
                    Text("Why: " + ranked.reasons.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                }
            }
        }
        if (!t.isCompleted) {
            if (manualMove != null) {
                IconButton(onClick = { manualMove(-1) }, Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Move up", tint = Baba.colors.textMuted) }
                IconButton(onClick = { manualMove(1) }, Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Move down", tint = Baba.colors.textMuted) }
            }
            IconButton(onClick = { app.report(tasks.setMostImportant(t.id, !t.isMit(today))) }, Modifier.size(30.dp)) {
                Icon(
                    if (t.isMit(today)) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    if (t.isMit(today)) "Unmark most important" else "Mark as today's most important task",
                    tint = if (t.isMit(today)) Baba.colors.saffron else Baba.colors.textMuted, modifier = Modifier.size(18.dp),
                )
            }
            Box {
                IconButton(onClick = { snoozeMenu = true }, Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.Schedule, "Snooze or reschedule", tint = Baba.colors.textMuted, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(snoozeMenu, onDismissRequest = { snoozeMenu = false }) {
                    DropdownMenuItem({ Text("Snooze 1 hour") }, onClick = { snoozeMenu = false; app.report(tasks.snooze(t.id, Duration.ofHours(1))) })
                    DropdownMenuItem({ Text("Snooze 3 hours") }, onClick = { snoozeMenu = false; app.report(tasks.snooze(t.id, Duration.ofHours(3))) })
                    DropdownMenuItem({ Text("Move to tomorrow") }, onClick = { snoozeMenu = false; app.report(tasks.reschedule(t.id, today.plusDays(1))) })
                    DropdownMenuItem({ Text("Move to next week") }, onClick = { snoozeMenu = false; app.report(tasks.reschedule(t.id, today.plusDays(7))) })
                    if (t.due == null) DropdownMenuItem({ Text("Plan for today") }, onClick = { snoozeMenu = false; app.report(tasks.reschedule(t.id, today)) })
                }
            }
        }
        if (hovered || selected) {
            IconButton(onClick = { app.report(tasks.delete(t.id), "Deleted \"${t.title.take(40)}\".") ; if (selected) app.editor = null }, Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Delete, "Delete task", tint = Baba.colors.textMuted, modifier = Modifier.size(18.dp))
            }
        } else Spacer(Modifier.width(30.dp))
    }
}

@Composable
fun CompleteCircle(done: Boolean, color: Color, onToggle: () -> Unit) {
    Box(
        Modifier.size(22.dp).clip(CircleShape)
            .background(if (done) color else Color.Transparent)
            .border(BorderStroke(2.dp, color), CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Icon(Icons.Filled.Check, "Mark not done", tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

@Composable
fun babaFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Baba.colors.saffron, unfocusedBorderColor = Baba.colors.cardBorder,
    focusedContainerColor = Baba.colors.card, unfocusedContainerColor = Baba.colors.card,
    cursorColor = Baba.colors.saffron, focusedLabelColor = Baba.colors.saffron,
)

/** One-line quick add. Supports "!" suffixes: "Pay rent !h tomorrow" → High, due tomorrow. */
@Composable
fun QuickAddField(app: AppController, defaultDue: LocalDate?, modifier: Modifier = Modifier, autoFocusHint: String = "Add a task… (try “Call bank !h tomorrow”)") {
    var text by remember { mutableStateOf("") }
    fun submit() {
        val draft = parseQuickAdd(text, defaultDue, app.core.today()) ?: return
        app.report(app.core.tasks.create(draft)).onSuccess { text = "" }
    }
    OutlinedTextField(
        value = text, onValueChange = { text = it }, modifier = modifier.fillMaxWidth().onPreviewKeyEvent {
            if (it.key == Key.Enter && it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) { submit(); true } else false
        },
        placeholder = { Text(autoFocusHint, color = Baba.colors.textMuted) }, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search.takeIf { false } ?: Icons.Filled.Check, null, tint = Baba.colors.textMuted.copy(alpha = 0.5f)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
        shape = RoundedCornerShape(10.dp), colors = babaFieldColors(), textStyle = MaterialTheme.typography.bodyLarge,
    )
}

/** Quick-add grammar: `!c` `!h` `!m` `!l` set priority; `today`, `tomorrow` set the date; `#work` etc. set category. */
fun parseQuickAdd(raw: String, defaultDue: LocalDate?, today: LocalDate): TaskDraft? {
    var title = raw.trim()
    if (title.isEmpty()) return null
    var priority = Priority.MEDIUM
    var due = defaultDue
    var category = Category.WORK
    val words = title.split(Regex("\\s+")).toMutableList()
    val kept = words.filter { w ->
        when (w.lowercase()) {
            "!c", "!!!" -> { priority = Priority.CRITICAL; false }
            "!h", "!!" -> { priority = Priority.HIGH; false }
            "!m" -> { priority = Priority.MEDIUM; false }
            "!l" -> { priority = Priority.LOW; false }
            "today" -> { due = today; false }
            "tomorrow" -> { due = today.plusDays(1); false }
            else -> {
                val cat = Category.entries.firstOrNull { "#" + it.name.lowercase() == w.lowercase() }
                if (cat != null) { category = cat; false } else true
            }
        }
    }
    title = kept.joinToString(" ").ifBlank { title }
    return TaskDraft(title = title, priority = priority, dueDate = due, category = category)
}

@Composable
fun SearchAndFilters(app: AppController) {
    val f = app.filter
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = f.query, onValueChange = { app.filter = f.copy(query = it) }, singleLine = true,
            placeholder = { Text("Search tasks", color = Baba.colors.textMuted) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Baba.colors.textMuted) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), colors = babaFieldColors(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Priority.entries.forEach { p ->
                SmallChip(p.label, p in f.priorities) {
                    app.filter = f.copy(priorities = if (p in f.priorities) f.priorities - p else f.priorities + p)
                }
            }
            Spacer(Modifier.width(8.dp))
            Category.entries.forEach { c ->
                SmallChip(c.label, c in f.categories) {
                    app.filter = f.copy(categories = if (c in f.categories) f.categories - c else f.categories + c)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sort:", style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted)
            SortMode.entries.forEach { m -> SmallChip(m.label, app.sort == m) { app.sort = m } }
        }
    }
}

@Composable
fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Baba.colors.saffronSoft, selectedLabelColor = Baba.colors.text,
            containerColor = Color.Transparent, labelColor = Baba.colors.textMuted,
        ),
        border = FilterChipDefaults.filterChipBorder(true, selected, borderColor = Baba.colors.cardBorder, selectedBorderColor = Baba.colors.saffron.copy(alpha = 0.5f)),
    )
}

@Composable
fun RepeatBadge() = Icon(Icons.Outlined.Repeat, "Repeating task", tint = Baba.colors.textMuted, modifier = Modifier.size(14.dp))
