package app.taskbaba.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.taskbaba.app.ui.components.BabaCard
import app.taskbaba.app.ui.components.ErrorText
import app.taskbaba.app.ui.components.Pill
import app.taskbaba.app.ui.components.PrimaryButton
import app.taskbaba.app.ui.components.QuietButton
import app.taskbaba.app.ui.components.SecondaryButton
import app.taskbaba.app.ui.components.SectionTitle
import app.taskbaba.app.ui.components.StatusDot
import app.taskbaba.app.ui.components.StatusTone
import app.taskbaba.app.ui.components.ToggleRow
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.integrations.ClassificationRule
import app.taskbaba.core.integrations.DeviceCodeLogin
import app.taskbaba.core.integrations.DeviceCodePrompt
import app.taskbaba.core.integrations.InboxPriority
import app.taskbaba.core.integrations.IntegrationSettings
import app.taskbaba.core.integrations.IntegrationsModule
import app.taskbaba.core.integrations.NotificationSource
import app.taskbaba.core.integrations.SourceState
import app.taskbaba.core.model.ClockTime
import app.taskbaba.core.model.TimeWindow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Integration settings. Not scrollable itself — place it inside the settings screen's scrolling column.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntegrationsSettings(module: IntegrationsModule) {
    val settings by module.settings.state.collectAsState()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<ClassificationRule?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Connected sources")
        module.sources.forEach { SourceCard(module, it) }

        SectionTitle("Alerts")
        BabaCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Alert immediately at", style = MaterialTheme.typography.bodyLarge, color = Baba.colors.text)
                    Text("Lower priorities wait for the digest.", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                }
                Choice(
                    current = settings.alertThreshold.label + " and above",
                    options = listOf(InboxPriority.CRITICAL, InboxPriority.HIGH, InboxPriority.NORMAL, InboxPriority.LOW).map { it.label + " and above" to it },
                    onPick = { p -> module.settings.update { it.copy(alertThreshold = p) } },
                )
            }
            ToggleRow("Critical breaks through", settings.urgentExceptions, { v -> module.settings.update { it.copy(urgentExceptions = v) } },
                "Critical items alert even during focus sessions and quiet hours.")
            ToggleRow("Show message text in alerts", settings.previewInAlerts, { v -> module.settings.update { it.copy(previewInAlerts = v) } },
                "Off: alerts only say which app has something, useful when screen sharing.")
            ToggleRow("Pause inbox alerts", settings.paused, { v -> module.settings.update { it.copy(paused = v) } },
                "Items are still collected; nothing pops up until you resume.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Max alerts per hour", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Baba.colors.text)
                Stepper(settings.maxAlertsPerHour, 1..30) { v -> module.settings.update { it.copy(maxAlertsPerHour = v) } }
            }
        }

        SectionTitle("Digest")
        BabaCard(Modifier.fillMaxWidth()) {
            ToggleRow("Scheduled digests", settings.digestEnabled, { v -> module.settings.update { it.copy(digestEnabled = v) } },
                "One summary notification of held items at these times (skipped during quiet hours and focus).")
            DigestTimes(settings) { times -> module.settings.update { it.copy(digestTimes = times) } }
            val scope = rememberCoroutineScope()
            var sent by remember { mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("Send digest now", onClick = { scope.launch { val n = module.inbox.sendDigest("Manual"); sent = if (n == 0) "Nothing held right now." else "Summarized $n item(s)." } })
                Spacer(Modifier.width(10.dp))
                sent?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted) }
            }
        }

        SectionTitle("Always alert for")
        BabaCard(Modifier.fillMaxWidth()) {
            ListField("Senders (comma-separated)", settings.allowSenders) { v -> module.settings.update { it.copy(allowSenders = v) } }
            ListField("Channels / projects (comma-separated)", settings.allowChannels) { v -> module.settings.update { it.copy(allowChannels = v) } }
            Text("Allowlisted senders and channels are treated as at least High. Quiet hours and focus still apply.",
                style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
        }

        SectionTitle("Rules", trailing = {
            QuietButton("Add rule", onClick = { editingRule = ClassificationRule(UUID.randomUUID().toString(), "", priority = InboxPriority.HIGH) })
        })
        BabaCard(Modifier.fillMaxWidth()) {
            if (settings.rules.isEmpty()) {
                Text("No rules yet. Built-in rules apply: urgent wording → Critical, DMs and mentions → High, bots and newsletters → Noise.",
                    style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted)
            }
            settings.rules.forEachIndexed { index, rule ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rule.enabled, { v -> module.settings.update { s -> s.copy(rules = s.rules.map { if (it.id == rule.id) it.copy(enabled = v) else it }) } })
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(rule.name, style = MaterialTheme.typography.bodyLarge, color = Baba.colors.text)
                            Pill("→ ${rule.priority.label}", priorityColor(rule.priority))
                            if (rule.autoCreateTask) Pill("creates task", Baba.colors.info)
                        }
                        Text(describe(rule, module), style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                    }
                    QuietButton("↑", enabled = index > 0, onClick = { module.settings.update { it.copy(rules = it.rules.move(index, index - 1)) } })
                    QuietButton("↓", enabled = index < settings.rules.lastIndex, onClick = { module.settings.update { it.copy(rules = it.rules.move(index, index + 1)) } })
                    QuietButton("Edit", onClick = { editingRule = rule })
                    QuietButton("Delete", onClick = { module.settings.update { s -> s.copy(rules = s.rules.filterNot { it.id == rule.id }) } })
                }
            }
            Text("Rules run top to bottom; the first match wins. Your per-item priority changes always override rules.",
                style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
        }

        SectionTitle("Stored notification data")
        BabaCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep items for", style = MaterialTheme.typography.bodyLarge, color = Baba.colors.text)
                    Text("Older items are deleted automatically. Data stays on this Mac.", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                }
                Choice(
                    current = "${settings.retentionDays} days",
                    options = listOf(1, 3, 7, 14, 30).map { "$it day${if (it == 1) "" else "s"}" to it },
                    onPick = { d -> module.settings.update { it.copy(retentionDays = d) }; module.inbox.purge() },
                )
            }
            SecondaryButton("Delete all stored notification data", danger = true, onClick = { confirmDeleteAll = true })
        }

        SectionTitle("Not supported")
        BabaCard(Modifier.fillMaxWidth()) {
            module.unsupported.forEach { s ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    StatusDot(StatusTone.NEUTRAL, s.displayName)
                    Text(s.setupHelp, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all inbox items?") },
            text = { Text("This removes every stored notification from Task Baba on this Mac. Connections and rules are kept. Tasks you created stay.") },
            confirmButton = { PrimaryButton("Delete", onClick = { module.inbox.deleteAll(); confirmDeleteAll = false }) },
            dismissButton = { SecondaryButton("Cancel", onClick = { confirmDeleteAll = false }) },
        )
    }
    editingRule?.let { rule ->
        RuleDialog(module, rule, onDismiss = { editingRule = null }) { saved ->
            module.settings.update { s ->
                s.copy(rules = if (s.rules.any { it.id == saved.id }) s.rules.map { if (it.id == saved.id) saved else it } else s.rules + saved)
            }
            editingRule = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(module: IntegrationsModule, source: NotificationSource) {
    val status by source.status.collectAsState()
    val settings by module.settings.state.collectAsState()
    val cfg = settings.source(source.id)
    val scope = rememberCoroutineScope()
    val values = remember(source.id) { mutableStateMapOf<String, String>().apply { putAll(source.currentConfig()) } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<DeviceCodePrompt?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val configured = source.isConfigured() && status.state != SourceState.NOT_CONFIGURED

    BabaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(source.displayName, style = MaterialTheme.typography.titleLarge, color = Baba.colors.text)
                Text(source.description, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
            }
            StatusDot(statusTone(status), status.message)
        }

        if (!configured || status.state == SourceState.AUTH_FAILED) {
            source.configFields.forEach { f ->
                OutlinedTextField(
                    value = values[f.key].orEmpty(),
                    onValueChange = { values[f.key] = it },
                    label = { Text(f.label + if (f.optional) " (optional)" else "") },
                    placeholder = { Text(f.placeholder) },
                    singleLine = true,
                    visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        prompt?.let { p ->
            BabaCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                Text("Sign in to Microsoft", style = MaterialTheme.typography.titleMedium, color = Baba.colors.text)
                Text("Open the page below and enter this code:", style = MaterialTheme.typography.bodyMedium, color = Baba.colors.textMuted)
                SelectionContainer { Text(p.userCode, style = MaterialTheme.typography.headlineSmall, color = Baba.colors.saffron) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Open ${p.verificationUri.removePrefix("https://")}", onClick = { module.openBrowser(p.verificationUri) })
                    SecondaryButton("Cancel sign-in", onClick = { loginJob?.cancel(); prompt = null; busy = false })
                }
                Text("Waiting for you to approve…", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
            }
        }

        error?.let { ErrorText(listOf(it)) }
        info?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Baba.colors.success) }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!configured || status.state == SourceState.AUTH_FAILED) {
                val deviceLogin = source as? DeviceCodeLogin
                PrimaryButton(if (busy) "Connecting…" else if (deviceLogin != null) "Save & sign in" else "Connect", enabled = !busy, onClick = {
                    busy = true; error = null; info = null
                    loginJob = scope.launch {
                        try {
                            source.configure(values.toMap()).getOrElse { error = it.message; return@launch }
                            // Never keep secrets in UI state once saved.
                            source.configFields.filter { it.secret }.forEach { values.remove(it.key) }
                            if (deviceLogin != null) {
                                val p = deviceLogin.startLogin().getOrElse { error = it.message; return@launch }
                                prompt = p
                                deviceLogin.awaitLogin(p).onFailure { error = it.message }.onSuccess { info = "Connected." }
                                prompt = null
                            } else {
                                info = "Connected."
                            }
                            if (source.isConfigured()) module.inbox.refresh(source.id)
                        } finally { busy = false }
                    }
                })
            } else {
                SecondaryButton(if (busy) "Checking…" else "Check now", enabled = !busy, onClick = {
                    busy = true; error = null; info = null
                    scope.launch {
                        try {
                            val out = module.inbox.pollOnce(source)
                            if (out.failure != null) error = out.failure!!.message else info = "Found ${out.stored} new item(s)."
                        } finally { busy = false }
                    }
                })
                SecondaryButton("Disconnect", danger = true, onClick = { confirmDisconnect = true })
            }
            QuietButton(if (showHelp) "Hide setup steps" else "Setup steps", onClick = { showHelp = !showHelp })
        }

        if (showHelp) {
            SelectionContainer { Text(source.setupHelp, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted) }
        }

        if (configured) {
            ToggleRow("Check this source", cfg.enabled, { v -> module.settings.update { it.copy(sources = it.sources + (source.id to cfg.copy(enabled = v))) } })
            ToggleRow("Mute alerts", cfg.muted, { v -> module.settings.update { it.copy(sources = it.sources + (source.id to cfg.copy(muted = v))) } },
                "Items still appear in the inbox.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Check every", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Baba.colors.text)
                Choice(
                    current = "${cfg.pollMinutes} min",
                    options = listOf(1, 2, 5, 10, 15, 30, 60).map { "$it min" to it },
                    onPick = { m -> module.settings.update { it.copy(sources = it.sources + (source.id to cfg.copy(pollMinutes = m))) } },
                )
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect ${source.displayName}?") },
            text = { Text("Task Baba forgets the saved sign-in (removed from your Keychain). You can also delete the items it already fetched.") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Disconnect & delete items", danger = true, onClick = {
                        confirmDisconnect = false
                        scope.launch { source.disconnect(); module.inbox.deleteSource(source.id); values.clear() }
                    })
                    PrimaryButton("Disconnect", onClick = {
                        confirmDisconnect = false
                        scope.launch { source.disconnect(); values.clear() }
                    })
                }
            },
            dismissButton = { SecondaryButton("Cancel", onClick = { confirmDisconnect = false }) },
        )
    }
}

@Composable
private fun RuleDialog(module: IntegrationsModule, initial: ClassificationRule, onDismiss: () -> Unit, onSave: (ClassificationRule) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var source by remember { mutableStateOf(initial.source) }
    var sender by remember { mutableStateOf(initial.senderContains.orEmpty()) }
    var channel by remember { mutableStateOf(initial.channelContains.orEmpty()) }
    var keywords by remember { mutableStateOf(initial.keywords.joinToString(", ")) }
    var mention by remember { mutableStateOf(initial.requireMention) }
    var focus by remember { mutableStateOf(initial.duringFocus) }
    var windowStart by remember { mutableStateOf(initial.timeWindow?.start?.toString().orEmpty()) }
    var windowEnd by remember { mutableStateOf(initial.timeWindow?.end?.toString().orEmpty()) }
    var priority by remember { mutableStateOf(initial.priority) }
    var autoTask by remember { mutableStateOf(initial.autoCreateTask) }
    var errors by remember { mutableStateOf(emptyList<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "New rule" else "Edit rule") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Rule name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Source", Modifier.weight(1f), color = Baba.colors.text)
                    Choice(
                        current = module.sources.firstOrNull { it.id == source }?.displayName ?: "Any source",
                        options = listOf<Pair<String, String?>>("Any source" to null) + module.sources.map { it.displayName to it.id },
                        onPick = { source = it },
                    )
                }
                OutlinedTextField(sender, { sender = it }, label = { Text("Sender contains") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(channel, { channel = it }, label = { Text("Channel / project contains") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(keywords, { keywords = it }, label = { Text("Any of these keywords (comma-separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(mention, { mention = it }); Text("Only when I'm mentioned or messaged directly", color = Baba.colors.text)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Focus", color = Baba.colors.text)
                    listOf<Pair<String, Boolean?>>("Any time" to null, "During focus" to true, "Outside focus" to false).forEach { (l, v) ->
                        FilterChip(selected = focus == v, onClick = { focus = v }, label = { Text(l) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(windowStart, { windowStart = it }, label = { Text("From (HH:mm)") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(windowEnd, { windowEnd = it }, label = { Text("To (HH:mm)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("Then set priority", style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InboxPriority.entries.forEach { p -> FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.label) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(autoTask, { autoTask = it }); Text("Also create a task automatically (due today)", color = Baba.colors.text)
                }
                ErrorText(errors)
            }
        },
        confirmButton = {
            PrimaryButton("Save rule", onClick = {
                val start = parseClock(windowStart); val end = parseClock(windowEnd)
                val windowProblem = (windowStart.isNotBlank() || windowEnd.isNotBlank()) && (start == null || end == null)
                val rule = initial.copy(
                    name = name.trim(), source = source,
                    senderContains = sender.trim().ifBlank { null }, channelContains = channel.trim().ifBlank { null },
                    keywords = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    requireMention = mention, duringFocus = focus,
                    timeWindow = if (start != null && end != null) TimeWindow(start, end) else null,
                    priority = priority, autoCreateTask = autoTask,
                )
                val problems = rule.validate() + if (windowProblem) listOf("Enter both times as HH:mm, or leave both empty.") else emptyList()
                if (problems.isEmpty()) onSave(rule) else errors = problems
            })
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DigestTimes(settings: IntegrationSettings, onChange: (List<ClockTime>) -> Unit) {
    var newTime by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        settings.digestTimes.sorted().forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(t.toString(), Baba.colors.saffron)
                QuietButton("×", onClick = { onChange(settings.digestTimes - t) })
            }
        }
        OutlinedTextField(newTime, { newTime = it; error = null }, label = { Text("Add time (HH:mm)") }, singleLine = true, modifier = Modifier.width(170.dp))
        SecondaryButton("Add", onClick = {
            val t = parseClock(newTime)
            when {
                t == null -> error = "Use HH:mm, e.g. 16:30."
                t in settings.digestTimes -> error = "Already scheduled."
                settings.digestTimes.size >= 6 -> error = "Up to 6 digests a day."
                else -> { onChange((settings.digestTimes + t).sorted()); newTime = "" }
            }
        })
    }
    error?.let { ErrorText(listOf(it)) }
}

@Composable
private fun ListField(label: String, current: List<String>, onSave: (List<String>) -> Unit) {
    var text by remember(current) { mutableStateOf(current.joinToString(", ")) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        val parsed = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(50)
        SecondaryButton("Save", enabled = parsed != current, onClick = { onSave(parsed) })
    }
}

@Composable
private fun <T> Choice(current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        SecondaryButton("$current ▾", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (label, value) -> DropdownMenuItem(text = { Text(label) }, onClick = { onPick(value); open = false }) }
        }
    }
}

@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton("−", enabled = value > range.first, onClick = { onChange(value - 1) })
        Text("$value", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium, color = Baba.colors.text)
        SecondaryButton("+", enabled = value < range.last, onClick = { onChange(value + 1) })
    }
}

private fun parseClock(s: String): ClockTime? {
    val m = Regex("^(\\d{1,2}):(\\d{2})$").find(s.trim()) ?: return null
    val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) ClockTime.of(h, min) else null
}

private fun describe(rule: ClassificationRule, module: IntegrationsModule): String = buildList {
    rule.source?.let { id -> add("from ${module.sources.firstOrNull { it.id == id }?.displayName ?: id}") }
    rule.senderContains?.let { add("sender ~ \"$it\"") }
    rule.channelContains?.let { add("channel ~ \"$it\"") }
    if (rule.keywords.isNotEmpty()) add("keywords: ${rule.keywords.joinToString()}")
    if (rule.requireMention) add("mentions/DMs only")
    rule.duringFocus?.let { add(if (it) "during focus" else "outside focus") }
    rule.timeWindow?.let { add("${it.start}–${it.end}") }
}.joinToString(" · ").ifBlank { "No conditions" }

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (to !in indices) return this
    val m = toMutableList(); val x = m.removeAt(from); m.add(to, x); return m
}
