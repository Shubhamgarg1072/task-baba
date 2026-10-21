package app.taskbaba.core.ai

/**
 * Accepts model output only as short display text. Anything that looks like reasoning, links, code,
 * markup or instructions is rejected (null), and the caller keeps its deterministic line.
 */
class OutputValidator(private val maxLength: Int = 240) {

    sealed interface Verdict {
        data class Accepted(val text: String) : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    fun validate(raw: String?): String? = (check(raw) as? Verdict.Accepted)?.text

    fun check(raw: String?): Verdict {
        if (raw == null) return Verdict.Rejected("empty")
        var text = raw.replace(THINK_BLOCK, "")
        if (text.contains("<think", ignoreCase = true) || text.contains("</think", ignoreCase = true)) {
            return Verdict.Rejected("unterminated reasoning block")
        }
        text = text.trim()
        text = LABEL_PREFIX.replace(text, "").trim()
        text = stripWrappingQuotes(text)
        if (text.isEmpty()) return Verdict.Rejected("empty")
        if (text.any { it.isISOControl() && it != '\n' }) return Verdict.Rejected("control characters")
        if (text.lines().count { it.isNotBlank() } > 2) return Verdict.Rejected("too many lines")
        text = text.replace(Regex("\\s+"), " ")
        if (text.length > maxLength) return Verdict.Rejected("too long (${text.length} chars)")
        if (URL.containsMatchIn(text)) return Verdict.Rejected("contains a link")
        if (text.contains('`') || text.contains("```")) return Verdict.Rejected("contains code")
        if (MARKUP.containsMatchIn(text)) return Verdict.Rejected("contains markup")
        if (INSTRUCTION.containsMatchIn(text)) return Verdict.Rejected("contains an instruction")
        if (META.containsMatchIn(text)) return Verdict.Rejected("breaks character")
        if (emojiCount(text) > 2) return Verdict.Rejected("too many emoji")
        return Verdict.Accepted(text)
    }

    private fun stripWrappingQuotes(s: String): String {
        var t = s
        val pairs = listOf('"' to '"', '\'' to '\'', '“' to '”', '‘' to '’', '«' to '»')
        var changed = true
        while (changed && t.length >= 2) {
            changed = false
            for ((open, close) in pairs) {
                if (t.first() == open && t.last() == close) {
                    t = t.substring(1, t.length - 1).trim(); changed = true
                }
            }
        }
        return t
    }

    private fun emojiCount(s: String): Int {
        var count = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF) count++
            i += Character.charCount(cp)
        }
        return count
    }

    private companion object {
        val THINK_BLOCK = Regex("(?is)<think>.*?</think>")
        val LABEL_PREFIX = Regex("^(?i)(baba|task baba|assistant)\\s*:\\s*")
        val URL = Regex("(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|net|org|io|in|ai|dev|app)\\b)")
        val MARKUP = Regex("(?m)(^\\s*([#>*-]|\\d+\\.)\\s)|(<[a-zA-Z/][^>]*>)|(\\*\\*)|(\\[[^]]*]\\()")
        val INSTRUCTION = Regex(
            "(?i)\\b(click|tap here|run (the|this)|execute|sudo|rm -rf|install|download|delete (the|your|all)|" +
                "open (the|this) (link|file)|type (the|this) command|terminal|password|api key)\\b",
        )
        val META = Regex("(?i)(as an ai|language model|i cannot|i can't help|system prompt)")
    }
}
