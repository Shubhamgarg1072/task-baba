package app.taskbaba.core.ai

import app.taskbaba.core.model.Personality

/**
 * Builds the smallest prompt that lets the model rewrite one monk line. Only whitelisted, consented
 * context keys are sent; the task database, descriptions, notes and schedules never are.
 */
class PromptBuilder {

    fun build(request: MonkAiRequest, settings: AiSettings): List<ChatMessage> {
        val context = filterContext(request.context, settings)
        val base = redactFallback(request.fallback, request.context, settings)
        val tone = when (request.personality) {
            Personality.FRIENDLY -> "warm, encouraging and gentle"
            Personality.SAVAGE -> if (request.sarcasmAllowed) "witty, playfully sarcastic and direct" else "direct but kind"
            Personality.ADAPTIVE -> if (request.sarcasmAllowed) "friendly, with a light teasing edge" else "friendly and encouraging"
        }
        val facts = if (context.isEmpty()) "none" else context.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val user = buildString {
            append("Event: ").append(sanitize(request.event, 40)).append('\n')
            append("Tone: ").append(tone).append('\n')
            append("Facts: ").append(facts).append('\n')
            append("Base line: \"").append(base).append("\"\n")
            append("Rewrite the base line as Baba in one or two short sentences. Reply with the line only. /no_think")
        }
        return listOf(ChatMessage("system", SYSTEM_PROMPT), ChatMessage("user", user))
    }

    fun filterContext(context: Map<String, String>, settings: AiSettings): Map<String, String> =
        context.filterKeys { key ->
            key in ALWAYS_ALLOWED ||
                (key == KEY_CATEGORY && settings.shareCategories) ||
                (key == KEY_TASK_TITLE && settings.shareTaskTitles)
        }.mapValues { (_, v) -> sanitize(v, 80) }.filterValues { it.isNotEmpty() }

    /** If titles may not be shared, make sure the deterministic line doesn't leak one either. */
    fun redactFallback(fallback: String, context: Map<String, String>, settings: AiSettings): String {
        var text = fallback
        if (!settings.shareTaskTitles) {
            context[KEY_TASK_TITLE]?.trim()?.takeIf { it.length >= 2 }?.let { title ->
                text = text.replace(title, "this task", ignoreCase = true)
            }
        }
        return sanitize(text, 300)
    }

    private fun sanitize(s: String, max: Int): String =
        s.replace(Regex("[\\p{Cntrl}\"]"), " ").replace(Regex("\\s+"), " ").trim().take(max)

    companion object {
        const val KEY_TASK_TITLE = "taskTitle"
        const val KEY_CATEGORY = "category"

        /** Non-identifying facts that may be sent once cloud consent is given. */
        val ALWAYS_ALLOWED = setOf(
            "priority", "postponeCount", "completedToday", "plannedToday", "overdueCount",
            "streakDays", "timeOfDay", "healthType", "minutesSinceBreak",
        )

        val SYSTEM_PROMPT = """
            You are Baba, the funny, wise monk mascot of the Task Baba productivity app.
            Write in warm Indian English with gentle, original humour. You may use light references such as
            karma, tapasya, chai or the universe, always respectfully: never mock deities, rituals, scriptures
            or any religion, and never use hateful or demeaning content.
            Never give medical advice or health claims; for health reminders just nudge kindly, never shame.
            Never tell the user to click, run, install, download or delete anything, and never include links,
            code, lists or markdown. Reply with one or two short sentences (under 220 characters), at most one emoji.
        """.trimIndent()
    }
}
