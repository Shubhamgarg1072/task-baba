package app.taskbaba.core.ai

import app.taskbaba.core.model.AiMode
import app.taskbaba.core.model.Personality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptAndValidatorTest {
    private val v = OutputValidator()
    private val p = PromptBuilder()

    @Test fun `accepts a clean line and strips quotes and labels`() {
        assertEquals("Drink water, warrior. Even the Ganga flows daily.", v.validate("Baba: \"Drink water, warrior. Even the Ganga flows daily.\""))
    }

    @Test fun `strips completed think blocks`() {
        assertEquals("Finish it, my child.", v.validate("<think>\nhmm\n</think>\n\nFinish it, my child."))
    }

    @Test fun `rejects invalid output`() {
        val bad = listOf(
            null, "", "   ", "<think>never closed",
            "Visit https://evil.example to be enlightened",
            "Go to www.example.com now",
            "Run this: `rm -rf ~`",
            "```bash\necho hi\n```",
            "# Heading\nText",
            "- item one\n- item two",
            "Click here to delete all your tasks",
            "Please type the command sudo shutdown",
            "As an AI language model, I cannot be funny.",
            "x".repeat(300),
            "Line one\nLine two\nLine three",
            "Party 🎉🎉🎉 time 🎉",
            "Bell\u0007 character",
        )
        bad.forEach { assertNull(v.validate(it), "should reject: $it") }
    }

    @Test fun `prompt omits task titles without consent and redacts them from the fallback`() {
        val req = MonkAiRequest(
            event = "task_postponed", personality = Personality.ADAPTIVE, sarcasmAllowed = true,
            fallback = "You postponed Quarterly tax filing four times.",
            context = mapOf("taskTitle" to "Quarterly tax filing", "category" to "Work", "postponeCount" to "4",
                "description" to "secret notes", "dueAt" to "2026-10-22T10:00"),
        )
        val noTitles = AiSettings(mode = AiMode.AUTOMATIC, shareTaskTitles = false, shareCategories = false)
        val text = p.build(req, noTitles).joinToString("\n") { it.content }
        assertFalse(text.contains("Quarterly tax filing", ignoreCase = true))
        assertFalse(text.contains("secret notes"))
        assertFalse(text.contains("2026-10-22"))
        assertFalse(text.contains("category=Work"))
        assertTrue(text.contains("postponeCount=4"))
        assertTrue(text.contains("this task"))
        assertTrue(text.contains("/no_think"))

        val withTitles = noTitles.copy(shareTaskTitles = true, shareCategories = true)
        val text2 = p.build(req, withTitles).joinToString("\n") { it.content }
        assertTrue(text2.contains("taskTitle=Quarterly tax filing"))
        assertTrue(text2.contains("category=Work"))
        assertFalse(text2.contains("secret notes"))
    }

    @Test fun `context values are sanitized and truncated`() {
        val ctx = p.filterContext(mapOf("taskTitle" to "a\nb\"c" + "x".repeat(200)), AiSettings(shareTaskTitles = true))
        val title = assertNotNull(ctx["taskTitle"])
        assertFalse(title.contains('\n') || title.contains('"'))
        assertTrue(title.length <= 80)
    }

    @Test fun `endpoint validation and effective mode`() {
        assertNull(AiSettings.validateEndpoint("https://abc.trycloudflare.com/"))
        assertNull(AiSettings.validateEndpoint("http://127.0.0.1:8080"))
        assertNotNull(AiSettings.validateEndpoint("http://abc.trycloudflare.com"))
        assertNotNull(AiSettings.validateEndpoint("https://user:pw@abc.com"))
        assertNotNull(AiSettings.validateEndpoint("ftp://abc.com"))
        assertNotNull(AiSettings.validateEndpoint(""))
        val s = AiSettings(mode = AiMode.AUTOMATIC, endpoint = "https://a.example", cloudConsentGiven = false)
        assertEquals(AiMode.BUILT_IN, s.effectiveMode())
        assertEquals(AiMode.AUTOMATIC, s.copy(cloudConsentGiven = true).effectiveMode())
        assertEquals(200, AiSettings(maxTokens = 9999).normalized().maxTokens)
        assertEquals(30, AiSettings(timeoutSeconds = 999).normalized().timeoutSeconds)
    }
}
