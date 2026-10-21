package app.taskbaba.app.ui.tasks

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.taskbaba.app.AppController
import app.taskbaba.app.Editor
import app.taskbaba.app.ui.components.BabaCard
import app.taskbaba.app.ui.components.ErrorText
import app.taskbaba.app.ui.components.PrimaryButton
import app.taskbaba.app.ui.components.SecondaryButton
import app.taskbaba.app.ui.components.SectionTitle
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.model.Category
import app.taskbaba.core.model.Due
import app.taskbaba.core.model.Importance
import app.taskbaba.core.model.Priority
import app.taskbaba.core.model.RecurrenceRule
import app.taskbaba.core.model.Task
import app.taskbaba.core.model.TaskDraft
import app.taskbaba.core.tasks.ValidationException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private data class Form(
    val title: String = "",
    val description: String = "",
    val notes: String = "",
    val category: Category = Category.WORK,
    val priority: Priority = Priority.MEDIUM,
    val importance: Importance = Importance.NORMAL,
    val date: String = "",
    val time: String = "",
    val estimate: String = "",
    val recurrence: RecurrenceRule = RecurrenceRule.NONE,
)

private fun Form.toDraft(): Pair<TaskDraft?, List<String>> {
    val errors = mutableListOf<String>()
    val d = date.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrElse { errors += "Date must look like 2026-10-24."; null } }
    val t = time.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(if (it.length == 4) "0$it" else it) }.getOrElse { errors += "Time must look like 17:30."; null } }
    val est = estimate.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: run { errors += "Estimate must be a number of minutes."; null } }
    if (errors.isNotEmpty()) return null to errors
    val draft = TaskDraft(title, description, notes, category, priority, importance, d, t, est, recurrence)
    return draft to draft.validate()
}

private fun Task.toForm(zone: java.time.ZoneId) = Form(
    title = title, description = description, notes = notes, category = category, priority = priority, importance = importance,
    date = due?.localDate(zone)?.toString() ?: "",
    time = (due as? Due.At)?.instant?.atZone(zone)?.toLocalTime()?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "",
    estimate = estimatedMinutes?.toString() ?: "",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditorPane(app: AppController, editor: Editor, defaultDate: LocalDate?, modifier: Modifier = Modifier) {
    val zone = app.core.zone()
    val today = app.core.today()
    val existing = (editor as? Editor.Edit)?.let { app.core.tasks.get(it.id) }
    if (editor is Editor.Edit && existing == null) {
        LaunchedEffect(editor) { app.editor = null }
        return
    }
    var form by remember(editor) { mutableStateOf(existing?.toForm(zone) ?: Form(date = defaultDate?.toString() ?: "")) }
    var errors by remember(editor) { mutableStateOf(emptyList<String>()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(editor) { if (editor is Editor.New) runCatching { focus.requestFocus() } }

    fun save() {
        val (draft, problems) = form.toDraft()
        errors = problems
        if (draft == null || problems.isNotEmpty()) return
        val result = if (existing == null) app.core.tasks.create(draft) else app.core.tasks.update(existing.id, draft)
        result.onSuccess { app.editor = null; app.say(if (existing == null) "Task added." else "Task saved.") }
            .onFailure { errors = (it as? ValidationException)?.errors ?: listOf(it.message ?: "Couldn't save.") }
    }

    BabaCard(modifier.fillMaxWidth()) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (existing == null) "New task" else "Edit task", style = MaterialTheme.typography.titleLarge, color = Baba.colors.text)
            OutlinedTextField(form.title, { form = form.copy(title = it) }, label = { Text("Title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus), colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))
            OutlinedTextField(form.description, { form = form.copy(description = it) }, label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp), colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))

            SectionTitle("Priority")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Priority.entries.forEach { p -> SmallChip(p.label, form.priority == p) { form = form.copy(priority = p) } }
            }
            SectionTitle("Importance to you")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Importance.entries.forEach { i -> SmallChip(i.label, form.importance == i) { form = form.copy(importance = i) } }
            }
            SectionTitle("Category")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Category.entries.forEach { c -> SmallChip(c.label, form.category == c) { form = form.copy(category = c) } }
            }
            SectionTitle("Deadline")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip("Today", form.date == today.toString()) { form = form.copy(date = today.toString()) }
                SmallChip("Tomorrow", form.date == today.plusDays(1).toString()) { form = form.copy(date = today.plusDays(1).toString()) }
                SmallChip("Next week", form.date == today.plusDays(7).toString()) { form = form.copy(date = today.plusDays(7).toString()) }
                SmallChip("No date", form.date.isEmpty()) { form = form.copy(date = "", time = "", recurrence = RecurrenceRule.NONE) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.date, { form = form.copy(date = it) }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true,
                    modifier = Modifier.weight(1.3f), colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))
                OutlinedTextField(form.time, { form = form.copy(time = it) }, label = { Text("Time (optional)") }, singleLine = true,
                    placeholder = { Text("17:30") }, modifier = Modifier.weight(1f), colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))
            }
            OutlinedTextField(form.estimate, { form = form.copy(estimate = it.filter(Char::isDigit).take(4)) }, label = { Text("Estimated minutes (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))

            if (existing == null) {
                SectionTitle("Repeat")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RecurrenceRule.entries.forEach { r -> SmallChip(r.label, form.recurrence == r) { form = form.copy(recurrence = r) } }
                }
                if (form.recurrence != RecurrenceRule.NONE) {
                    Text("One task is created for each day it repeats, starting on the date above. Deleting one day's task won't bring it back.",
                        style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
                }
            } else if (existing.seriesId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RepeatBadge(); Spacer(Modifier.width(6.dp))
                    Text("Part of a repeating series", style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted, modifier = Modifier.weight(1f))
                    SecondaryButton("Stop repeating", { app.report(app.core.tasks.stopRepeating(existing), "Future repeats stopped. Existing tasks were kept.") })
                }
            }

            OutlinedTextField(form.notes, { form = form.copy(notes = it) }, label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), colors = babaFieldColors(), shape = RoundedCornerShape(10.dp))

            if (existing != null) {
                Text(
                    buildString {
                        append("Created ${existing.createdAt.atZone(zone).toLocalDate()}")
                        if (existing.postponeCount > 0) append(" · postponed ${existing.postponeCount}×")
                        existing.completedAt?.let { append(" · completed ${it.atZone(zone).toLocalDate()}") }
                    },
                    style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted,
                )
            }
            ErrorText(errors)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(if (existing == null) "Add task" else "Save", ::save)
                SecondaryButton("Cancel", { app.editor = null })
                Spacer(Modifier.weight(1f))
                if (existing != null) {
                    SecondaryButton(if (existing.isCompleted) "Reopen" else "Complete", {
                        app.report(if (existing.isCompleted) app.core.tasks.reopen(existing.id) else app.core.tasks.complete(existing.id))
                        app.editor = null
                    })
                    SecondaryButton("Delete", { app.report(app.core.tasks.delete(existing.id), "Task deleted."); app.editor = null }, danger = true)
                }
            }
            Spacer(Modifier.padding(2.dp))
        }
    }
}
