package app.taskbaba.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.taskbaba.app.ui.components.BabaCard
import app.taskbaba.app.ui.components.ErrorText
import app.taskbaba.app.ui.components.Pill
import app.taskbaba.app.ui.components.PrimaryButton
import app.taskbaba.app.ui.components.QuietButton
import app.taskbaba.app.ui.components.ScreenHeader
import app.taskbaba.app.ui.components.SecondaryButton
import app.taskbaba.app.ui.components.SectionTitle
import app.taskbaba.app.ui.components.StatusDot
import app.taskbaba.app.ui.components.StatusTone
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.integrations.InboxItem
import app.taskbaba.core.integrations.InboxPriority
import app.taskbaba.core.integrations.InboxStatus
import app.taskbaba.core.integrations.IntegrationsModule
import app.taskbaba.core.integrations.SourceState
import app.taskbaba.core.integrations.SourceStatus
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.Task
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class InboxFilter(val label: String) { NEW("New"), SNOOZED("Snoozed"), REVIEWED("Reviewed"), ALL("All") }

/**
 * Smart notification inbox. [tasks] feeds "Link to task"; [onOpenSettings] jumps to integration settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InboxScreen(
    module: IntegrationsModule,
    openUrl: (String) -> Unit,
    tasks: List<Task> = emptyList(),
    onOpenSettings: () -> Unit = {},
) {
    val inbox = module.inbox
    val items by inbox.items.collectAsState()
    val settings by module.settings.state.collectAsState()
    val statuses = module.sources.map { it.id to it.status.collectAsState().value }
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(InboxFilter.NEW) }
    var showNoise by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var createFor by remember { mutableStateOf<InboxItem?>(null) }
    var linkFor by remember { mutableStateOf<InboxItem?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }

    val connected = module.sources.filter { it.isConfigured() }
    val visible = items.filter { item ->
        val statusOk = when (filter) {
            InboxFilter.NEW -> item.status == InboxStatus.NEW
            InboxFilter.SNOOZED -> item.status == InboxStatus.SNOOZED
            InboxFilter.REVIEWED -> item.status == InboxStatus.REVIEWED || item.status == InboxStatus.DISMISSED
            InboxFilter.ALL -> true
        }
        statusOk && (showNoise || item.priority != InboxPriority.NOISE || filter == InboxFilter.ALL)
    }
    val grouped = visible.sortedWith(compareByDescending<InboxItem> { it.priority.rank }.thenByDescending { it.receivedAt })
        .groupBy { it.priority }
    val noiseHidden = items.count { it.status == InboxStatus.NEW && it.priority == InboxPriority.NOISE }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
    ) {
        item {
            val newCount = items.count { it.status == InboxStatus.NEW && it.priority != InboxPriority.NOISE }
            ScreenHeader(
                "Inbox",
                if (connected.isEmpty()) "Nothing connected yet" else "$newCount to triage · from ${connected.joinToString { it.displayName }}",
            ) {
                if (connected.isNotEmpty()) {
                    SecondaryButton(if (checking) "Checking…" else "Check now", enabled = !checking, onClick = {
                        checking = true
                        scope.launch {
                            try { connected.forEach { inbox.pollOnce(it) } } finally { checking = false }
                        }
                    })
                }
                QuietButton("Integrations", onClick = onOpenSettings)
            }
        }

        item { FocusCard(module) }

        if (connected.isEmpty()) {
            item {
                BabaCard(Modifier.fillMaxWidth()) {
                    Text("Baba can't peek at other apps on his own", style = MaterialTheme.typography.titleLarge, color = Baba.colors.text)
                    Text(
                        "macOS doesn't let one app read another app's notifications, and Baba won't sneak around that. " +
                            "Connect Microsoft 365 (Teams, Outlook, Calendar), Slack, or Jira with their official sign-in, and " +
                            "their important messages will be sorted here.",
                        style = MaterialTheme.typography.bodyLarge, color = Baba.colors.textMuted,
                    )
                    PrimaryButton("Connect a source", onClick = onOpenSettings)
                }
            }
        }

        val problems = statuses.filter { (id, s) -> s.isProblem && module.sources.first { it.id == id }.isConfigured() }
        if (problems.isNotEmpty()) {
            item {
                BabaCard(Modifier.fillMaxWidth()) {
                    problems.forEach { (id, s) ->
                        val name = module.sources.first { it.id == id }.displayName
                        StatusDot(statusTone(s), "$name: ${s.message}")
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InboxFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f, onClick = { filter = f }, label = { Text(f.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Baba.colors.saffronSoft),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (filter != InboxFilter.ALL && noiseHidden > 0) {
                    QuietButton(if (showNoise) "Hide noise" else "Show $noiseHidden noise", onClick = { showNoise = !showNoise })
                }
            }
        }

        if (visible.isEmpty() && connected.isNotEmpty()) {
            item {
                Text(
                    when (filter) {
                        InboxFilter.NEW -> "Inbox zero. Even the rishis would be impressed, my child."
                        InboxFilter.SNOOZED -> "Nothing snoozed."
                        InboxFilter.REVIEWED -> "Nothing reviewed yet."
                        InboxFilter.ALL -> "No stored items. Items are kept for ${settings.retentionDays} days."
                    },
                    style = MaterialTheme.typography.bodyLarge, color = Baba.colors.textMuted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }

        InboxPriority.entries.forEach { p ->
            val group = grouped[p].orEmpty()
            if (group.isNotEmpty()) {
                item(key = "header-${p.name}") { SectionTitle("${p.label} · ${group.size}") }
                items(group, key = { it.id }) { item ->
                    InboxItemCard(
                        item = item,
                        sourceName = module.sources.firstOrNull { it.id == item.source }?.displayName ?: item.source,
                        canOpen = item.url != null && module.sources.firstOrNull { it.id == item.source }?.capabilities?.canOpenOriginal == true,
                        linkedTaskTitle = item.linkedTaskId?.let { id -> tasks.firstOrNull { it.id == id }?.title ?: "a task" },
                        now = now,
                        module = module,
                        onCreateTask = { createFor = item },
                        onLinkTask = { linkFor = item },
                        canLink = tasks.any { !it.isCompleted },
                    )
                }
            }
        }

        item {
            Text(
                "\"Reviewed\" is Baba's own bookkeeping — it doesn't mark anything read in Slack, Teams, Outlook, or Jira. " +
                    "Priorities come from your rules and simple built-in rules, not AI.",
                style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
            )
        }
    }

    createFor?.let { item -> CreateTaskDialog(module, item) { createFor = null } }
    linkFor?.let { item -> LinkTaskDialog(module, item, tasks.filter { !it.isCompleted }) { linkFor = null } }
}

@Composable
private fun FocusCard(module: IntegrationsModule) {
    val settings by module.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); tick++ } }
    val now = remember(tick, settings) { Instant.now() }
    val focus = settings.focus
    BabaCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (focus != null && settings.focusActive(now)) {
                    val end = Instant.ofEpochMilli(focus.endsAt).atZone(ZoneId.systemDefault()).toLocalTime()
                    Text("Focus session until ${end.format(HM)}", style = MaterialTheme.typography.titleMedium, color = Baba.colors.text)
                    Text(
                        if (settings.urgentExceptions) "Alerts are held for a digest; only Critical items break through."
                        else "All alerts are held for a digest.",
                        style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
                    )
                } else {
                    Text("Focus session", style = MaterialTheme.typography.titleMedium, color = Baba.colors.text)
                    Text("Hold inbox alerts and get one digest when you're done.", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                }
            }
            if (focus != null && settings.focusActive(now)) {
                SecondaryButton("End focus", onClick = { scope.launch { module.inbox.stopFocus() } })
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(25, 50, 90).forEach { m -> SecondaryButton("$m min", onClick = { module.inbox.startFocus(m) }) }
                }
            }
            Spacer(Modifier.width(8.dp))
            SecondaryButton(
                if (settings.paused) "Resume alerts" else "Pause alerts",
                onClick = { module.settings.update { it.copy(paused = !it.paused) } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxItemCard(
    item: InboxItem,
    sourceName: String,
    canOpen: Boolean,
    linkedTaskTitle: String?,
    now: Instant,
    module: IntegrationsModule,
    onCreateTask: () -> Unit,
    onLinkTask: () -> Unit,
    canLink: Boolean,
) {
    val inbox = module.inbox
    var snoozeMenu by remember { mutableStateOf(false) }
    var priorityMenu by remember { mutableStateOf(false) }
    BabaCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(item.priority.label, priorityColor(item.priority))
            if (item.userPriority != null) Pill("Set by you", Baba.colors.textMuted)
            Text(
                listOf(sourceName, item.sender, item.channel).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(relative(item.receivedAt, now), style = MaterialTheme.typography.labelSmall, color = Baba.colors.textMuted)
        }
        Text(item.title, style = MaterialTheme.typography.titleMedium, color = Baba.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.snippet.isNotBlank() && item.snippet != item.title) {
            Text(item.snippet, style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Text(
            buildString {
                append("Suggested: ${item.recommendedAction}")
                item.matchedRule?.let { append("  ·  Why: $it") }
                if (item.status == InboxStatus.SNOOZED) item.snoozedUntil?.let { append("  ·  Snoozed until ${it.atZone(ZoneId.systemDefault()).toLocalTime().format(HM)}") }
                if (item.status != InboxStatus.NEW && item.status != InboxStatus.SNOOZED) append("  ·  ${item.status.label}")
            },
            style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
        )
        if (linkedTaskTitle != null) {
            Text("Linked task: $linkedTaskTitle", style = MaterialTheme.typography.bodySmall, color = Baba.colors.success)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canOpen) PrimaryButton("Open original", onClick = { inbox.openOriginal(item.id) })
            if (item.status == InboxStatus.NEW || item.status == InboxStatus.SNOOZED) {
                SecondaryButton("Mark reviewed", onClick = { inbox.markReviewed(item.id) })
            } else {
                SecondaryButton("Move back to New", onClick = { inbox.reopen(item.id) })
            }
            Column {
                SecondaryButton("Snooze", onClick = { snoozeMenu = true })
                DropdownMenu(expanded = snoozeMenu, onDismissRequest = { snoozeMenu = false }) {
                    listOf("1 hour" to Duration.ofHours(1), "3 hours" to Duration.ofHours(3), "Until tomorrow 9:00" to untilTomorrowNine())
                        .forEach { (label, d) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { inbox.snooze(item.id, d); snoozeMenu = false })
                        }
                }
            }
            if (item.linkedTaskId == null) {
                SecondaryButton("Create task", onClick = onCreateTask)
                SecondaryButton("Link to task", onClick = onLinkTask, enabled = canLink)
            } else {
                SecondaryButton("Unlink task", onClick = { inbox.linkTask(item.id, null) })
            }
            Column {
                SecondaryButton("Priority", onClick = { priorityMenu = true })
                DropdownMenu(expanded = priorityMenu, onDismissRequest = { priorityMenu = false }) {
                    DropdownMenuItem(text = { Text("Automatic (${item.autoPriority.label})") }, onClick = { inbox.setUserPriority(item.id, null); priorityMenu = false })
                    InboxPriority.entries.forEach { p ->
                        DropdownMenuItem(text = { Text(p.label) }, onClick = { inbox.setUserPriority(item.id, p); priorityMenu = false })
                    }
                }
            }
            if (item.status != InboxStatus.DISMISSED) SecondaryButton("Dismiss", onClick = { inbox.dismiss(item.id) })
        }
    }
}

@Composable
private fun CreateTaskDialog(module: IntegrationsModule, item: InboxItem, onDone: () -> Unit) {
    var title by remember { mutableStateOf("Follow up: ${item.title}".take(200)) }
    var priority by remember { mutableStateOf(defaultTaskPriority(item.priority)) }
    var dueChoice by remember { mutableStateOf("Today") }
    var customDate by remember { mutableStateOf(LocalDate.now().plusDays(2).toString()) }
    var time by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Create a follow-up task") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Priority", style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Priority.entries.forEach { p -> FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.label) }) }
                }
                Text("Deadline", style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("None", "Today", "Tomorrow", "Pick date").forEach { c -> FilterChip(selected = dueChoice == c, onClick = { dueChoice = c }, label = { Text(c) }) }
                }
                if (dueChoice == "Pick date") {
                    OutlinedTextField(customDate, { customDate = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                }
                if (dueChoice != "None") {
                    OutlinedTextField(time, { time = it }, label = { Text("Time (HH:mm, optional)") }, singleLine = true)
                }
                Text("The task links back to this ${module.sources.firstOrNull { it.id == item.source }?.displayName ?: "item"} and the item is marked reviewed.",
                    style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                ErrorText(errors)
            }
        },
        confirmButton = {
            PrimaryButton("Create task", onClick = {
                val parsedDate: LocalDate? = when (dueChoice) {
                    "None" -> null
                    "Today" -> LocalDate.now()
                    "Tomorrow" -> LocalDate.now().plusDays(1)
                    else -> runCatching { LocalDate.parse(customDate.trim()) }.getOrNull()
                }
                val parsedTime: LocalTime? = time.trim().takeIf { it.isNotEmpty() && dueChoice != "None" }?.let { t -> runCatching { LocalTime.parse(t) }.getOrNull() }
                val problems = buildList {
                    if (dueChoice == "Pick date" && parsedDate == null) add("Enter the date as YYYY-MM-DD.")
                    if (time.isNotBlank() && dueChoice != "None" && parsedTime == null) add("Enter the time as HH:mm (24-hour).")
                }
                if (problems.isNotEmpty()) { errors = problems; return@PrimaryButton }
                module.inbox.createFollowUpTask(item.id, priority, parsedDate, parsedTime, title)
                    .onSuccess { onDone() }
                    .onFailure { errors = listOf(it.message ?: "Couldn't create the task.") }
            })
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDone) },
    )
}

@Composable
private fun LinkTaskDialog(module: IntegrationsModule, item: InboxItem, tasks: List<Task>, onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = tasks.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }.take(30)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Link to an existing task") },
        text = {
            Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search tasks") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (matches.isEmpty()) Text("No open tasks match.", color = Baba.colors.textMuted)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(matches, key = { it.id }) { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Baba.colors.text)
                            QuietButton("Link", onClick = { module.inbox.linkTask(item.id, t.id); onDone() })
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { SecondaryButton("Cancel", onClick = onDone) },
    )
}

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun untilTomorrowNine(): Duration {
    val target = LocalDate.now().plusDays(1).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant()
    return Duration.between(Instant.now(), target).coerceAtLeast(Duration.ofMinutes(5))
}

private fun defaultTaskPriority(p: InboxPriority) = when (p) {
    InboxPriority.CRITICAL -> Priority.CRITICAL
    InboxPriority.HIGH -> Priority.HIGH
    InboxPriority.NORMAL -> Priority.MEDIUM
    else -> Priority.LOW
}

internal fun relative(at: Instant, now: Instant): String {
    val d = Duration.between(at, now)
    return when {
        d.isNegative -> {
            val m = d.negated().toMinutes()
            if (m < 60) "in $m min" else at.atZone(ZoneId.systemDefault()).toLocalTime().format(HM)
        }
        d.toMinutes() < 1 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        else -> at.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM"))
    }
}

@Composable
internal fun priorityColor(p: InboxPriority): Color = when (p) {
    InboxPriority.CRITICAL -> Baba.colors.critical
    InboxPriority.HIGH -> Baba.colors.high
    InboxPriority.NORMAL -> Baba.colors.medium
    InboxPriority.LOW -> Baba.colors.low
    InboxPriority.NOISE -> Baba.colors.textMuted
}

internal fun statusTone(s: SourceStatus): StatusTone = when (s.state) {
    SourceState.CONNECTED -> StatusTone.OK
    SourceState.CONNECTING -> StatusTone.BUSY
    SourceState.NOT_CONFIGURED, SourceState.UNSUPPORTED -> StatusTone.NEUTRAL
    SourceState.OFFLINE -> StatusTone.WARN
    SourceState.AUTH_FAILED, SourceState.PERMISSION_DENIED, SourceState.ERROR -> StatusTone.ERROR
}
