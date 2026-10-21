package app.taskbaba.core

import app.taskbaba.core.data.DatabaseFactory
import app.taskbaba.core.data.MonkHistoryRepository
import app.taskbaba.core.model.Personality
import app.taskbaba.core.monk.MessageLibrary
import app.taskbaba.core.monk.MonkContext
import app.taskbaba.core.monk.MonkEngine
import app.taskbaba.core.monk.MonkEvent
import app.taskbaba.core.monk.MonkVoice
import app.taskbaba.core.monk.Tone
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonkEngineTest {
    private val friendly = MonkVoice(Personality.FRIENDLY, sarcasmEnabled = true, muted = false)
    private val savage = MonkVoice(Personality.SAVAGE, sarcasmEnabled = true, muted = false)
    private val adaptive = MonkVoice(Personality.ADAPTIVE, sarcasmEnabled = true, muted = false)

    @Test fun everyEventHasFriendlyLinesAndUniqueIds() {
        MonkEvent.entries.forEach { assertTrue(MessageLibrary.forEvent(it, Tone.FRIENDLY).isNotEmpty(), "no friendly lines for $it") }
        assertEquals(MessageLibrary.templates.size, MessageLibrary.templates.map { it.id }.toSet().size)
        assertTrue(MessageLibrary.templates.size >= 150)
    }

    @Test fun adaptiveTurnsSavageAfterRepeatedPostponements() {
        val e = MonkEngine(random = Random(1))
        assertEquals(Tone.FRIENDLY, e.line(MonkEvent.TASK_POSTPONED, adaptive, MonkContext("Taxes", postponeCount = 1)).tone)
        val line = e.line(MonkEvent.TASK_POSTPONED, adaptive, MonkContext("Taxes", postponeCount = 4))
        assertEquals(Tone.SAVAGE, line.tone)
        assertEquals(MonkEvent.TASK_POSTPONED_REPEAT, line.event)
        assertTrue("{" !in line.text)
    }

    @Test fun sarcasmOffForcesFriendlyEvenForSavage() {
        val e = MonkEngine(random = Random(2))
        val v = savage.copy(sarcasmEnabled = false)
        repeat(10) { assertEquals(Tone.FRIENDLY, e.line(MonkEvent.TASK_OVERDUE, v, MonkContext("x")).tone) }
    }

    @Test fun healthSkipsAreNeverSavageAndAdaptiveHealthIsFriendly() {
        val e = MonkEngine(random = Random(3))
        assertEquals(Tone.FRIENDLY, e.line(MonkEvent.HEALTH_SKIPPED, savage).tone)
        assertEquals(Tone.FRIENDLY, e.line(MonkEvent.HEALTH_WATER, adaptive, MonkContext(postponeCount = 9, overdue = 9)).tone)
    }

    @Test fun noRepeatsUntilPoolIsExhausted() {
        val e = MonkEngine(random = Random(4))
        val pool = MessageLibrary.forEvent(MonkEvent.TASK_DONE, Tone.FRIENDLY).size
        val ids = (1..pool).map { e.line(MonkEvent.TASK_DONE, friendly, MonkContext("t")).id }
        assertEquals(pool, ids.toSet().size)
        // After exhaustion it reuses the least-recently shown line, not the one just shown.
        assertTrue(e.line(MonkEvent.TASK_DONE, friendly, MonkContext("t")).id != ids.last())
    }

    @Test fun historyPersistsAcrossRestarts() {
        val db = DatabaseFactory.inMemory()
        val first = MonkEngine(MonkHistoryRepository(db), Random(5)).line(MonkEvent.HEALTH_EYES, friendly).id
        val again = MonkEngine(MonkHistoryRepository(db), Random(5)).line(MonkEvent.HEALTH_EYES, friendly).id
        assertTrue(first != again)
    }

    @Test fun mutedMonkUsesPlainText() {
        val line = MonkEngine().line(MonkEvent.TASK_OVERDUE, friendly.copy(muted = true), MonkContext("Invoice"))
        assertNull(line.tone)
        assertEquals("\"Invoice\" is overdue.", line.text)
    }

    @Test fun linesNeedingATaskAreSkippedWithoutOne() {
        val e = MonkEngine(random = Random(6))
        repeat(20) { assertFalse(e.line(MonkEvent.FOCUS_TIP, savage, MonkContext()).text.contains("your next task\"")) }
    }

    @Test fun cooldownLimitsChattyEvents() {
        val e = MonkEngine()
        val t0 = Instant.parse("2026-10-21T10:00:00Z")
        e.line(MonkEvent.RETURN_FROM_BREAK, friendly, MonkContext("x"), t0)
        assertFalse(e.mayRepeat(MonkEvent.RETURN_FROM_BREAK, t0.plus(Duration.ofMinutes(10))))
        assertTrue(e.mayRepeat(MonkEvent.RETURN_FROM_BREAK, t0.plus(Duration.ofMinutes(50))))
        assertTrue(e.mayRepeat(MonkEvent.TASK_DONE, t0))
    }
}
