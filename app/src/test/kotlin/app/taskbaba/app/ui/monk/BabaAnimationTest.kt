package app.taskbaba.app.ui.monk

import app.taskbaba.app.ui.monk.rig.BabaAnimations
import app.taskbaba.app.ui.monk.rig.BabaDirector
import app.taskbaba.app.ui.monk.rig.BabaPerformer
import app.taskbaba.app.ui.monk.rig.FramePacer
import app.taskbaba.app.ui.monk.rig.Pose
import app.taskbaba.app.ui.monk.rig.RigGeometry
import app.taskbaba.core.monk.MonkEvent
import app.taskbaba.core.monk.Tone
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BabaAnimationTest {
    @Test fun libraryHasFortyWellFormedClips() {
        val all = BabaAnimations.all
        assertTrue(all.size >= 38, "only ${all.size} animations")
        assertEquals(all.size, all.map { it.id }.toSet().size, "duplicate ids")
        for (a in all) {
            assertEquals(0, a.keys.first().timeMs, "${a.id} must start at 0")
            assertTrue(a.keys.zipWithNext().all { (x, y) -> y.timeMs >= x.timeMs }, "${a.id} keys out of order")
            assertTrue(a.keys.last().timeMs <= a.durationMs, "${a.id} has keys past its duration")
            assertTrue(a.signatureMs in 0..a.durationMs, "${a.id} signature outside clip")
            assertTrue(a.label.isNotBlank())
        }
    }

    @Test fun oneShotClipsEndAtRestSoTheReturnToIdleIsSeamless() {
        for (a in BabaAnimations.all.filter { !it.loop }) {
            val end = a.sample(a.durationMs.toLong())
            assertTrue(abs(end.rHandY - Pose.REST_Y) < 0.02f && abs(end.lHandY - Pose.REST_Y) < 0.02f, "${a.id} ends with raised hands")
            assertTrue(end.propAlpha < 0.05f, "${a.id} ends still holding a prop")
            assertTrue(end.fxAlpha < 0.05f, "${a.id} ends with a visible effect")
            assertTrue(abs(end.bodyY) < 0.01f && abs(end.headRot) < 1f, "${a.id} ends off-balance")
        }
    }

    @Test fun everyEventAndToneMapsToARealClip() {
        val ids = BabaAnimations.all.map { it.id }.toSet()
        for (e in MonkEvent.entries) for (tone in listOf(Tone.FRIENDLY, Tone.SAVAGE, null)) for (v in 0..3) {
            val id = BabaDirector.reactionFor(e, tone, v)
            assertTrue(id in ids, "$e/$tone → unknown clip $id")
        }
        assertEquals("drink_water", BabaDirector.reactionFor(MonkEvent.HEALTH_WATER, Tone.FRIENDLY))
        assertEquals("facepalm", BabaDirector.reactionFor(MonkEvent.TASK_POSTPONED_REPEAT, Tone.SAVAGE))
        assertEquals("celebrate", BabaDirector.reactionFor(MonkEvent.ALL_PRIORITIES_DONE, Tone.FRIENDLY))
    }

    @Test fun mostClipsAreReachableFromEventsOrIdle() {
        val used = MonkEvent.entries.flatMap { e -> listOf(Tone.FRIENDLY, Tone.SAVAGE, null).flatMap { t -> (0..3).map { BabaDirector.reactionFor(e, t, it) } } }.toSet() +
            BabaAnimations.IDLE_VARIETY + BabaAnimations.NIGHT_IDLE + BabaAnimations.IDLE
        val unused = BabaAnimations.all.map { it.id } - used
        assertTrue(unused.isEmpty(), "never played automatically: $unused")
    }

    /** Simulates a display: after each sleep, the next frame arrives on the following vsync. */
    private fun framesInOneSecond(targetFps: Int, displayHz: Int): Int {
        val vsync = 1_000_000_000L / displayHz
        val pacer = FramePacer()
        var now = 0L
        var frames = 0
        while (now < 1_000_000_000L) {
            frames++
            val sleep = pacer.delayAfterFrame(now, targetFps)
            now = ((now + sleep) / vsync + 1) * vsync
        }
        return frames
    }

    @Test fun pacerHitsTargetRatesOnRealDisplays() {
        for ((fps, hz) in listOf(30 to 60, 40 to 60, 60 to 60, 30 to 120, 40 to 120, 60 to 120, 12 to 60)) {
            val got = framesInOneSecond(fps, hz)
            assertTrue(got in fps - 2..fps + 1, "$fps fps on $hz Hz gave $got")
        }
    }

    @Test fun pacerDoesNotBurstAfterAStall() {
        val pacer = FramePacer()
        pacer.delayAfterFrame(0, 40)
        val afterStall = pacer.delayAfterFrame(5_000_000_000L, 40) // window hidden for 5 s
        assertTrue(afterStall in 10_000_000L..25_000_000L, "should resume at normal pace, got $afterStall")
    }

    @Test fun inverseKinematicsKeepsBoneLengths() {
        val sx = RigGeometry.SHOULDER_R_X
        val sy = RigGeometry.SHOULDER_Y
        for ((hx, hy) in listOf(0.58f to 0.52f, 0.80f to 0.30f, 0.70f to 1.04f, 0.53f to 0.62f)) {
            val hand = RigGeometry.reachableHand(sx, sy, hx, hy)
            val e = RigGeometry.elbow(sx, sy, hand.x, hand.y, 1f)
            assertTrue(abs(hypot(e.x - sx, e.y - sy) - RigGeometry.UPPER_ARM) < 0.005f)
            assertTrue(abs(hypot(hand.x - e.x, hand.y - e.y) - RigGeometry.FOREARM) < 0.005f)
            assertTrue(e.x >= minOf(sx, hand.x) - 0.001f, "elbow should bend outward")
        }
        // Out-of-reach targets are clamped to full extension, not stretched.
        val far = RigGeometry.reachableHand(sx, sy, 0.68f, -1f)
        assertTrue(hypot(far.x - sx, far.y - sy) <= RigGeometry.UPPER_ARM + RigGeometry.FOREARM)
    }

    @Test fun performerCrossfadesReturnsToIdleBlinksAndTalks() {
        val p = BabaPerformer(Random(1), hourOfDay = { 14 })
        val ms = 1_000_000L
        var t = 1_000 * ms
        val before = p.poseAt(t)
        p.play("celebrate", t, talk = true)
        val justAfter = p.poseAt(t + 16 * ms)
        assertTrue(abs(justAfter.rHandY - before.rHandY) < 0.05f, "crossfade must avoid a jump")

        // Lip-sync while the line is fresh (celebrate's first key keeps the mouth closed).
        assertTrue((0 until 20).any { p.poseAt(t + (20L + it * 25) * ms).mouthOpen > 0.15f })

        t += 5_000 * ms // celebrate is 4.2 s
        p.poseAt(t)
        assertEquals(BabaAnimations.IDLE, p.clip.id)

        // Over 10 s of idle, Baba blinks at least once.
        val closed = (0 until 400).count { p.poseAt(t + it * 25L * ms).eyeOpen < 0.1f }
        assertTrue(closed > 0, "never blinked")
    }

    @Test fun idleVarietyPlaysAndNightIsSleepy() {
        val day = BabaPerformer(Random(2), hourOfDay = { 11 })
        val ms = 1_000_000L
        val seen = mutableSetOf<String>()
        for (i in 0 until 3_600) { day.poseAt(i * 50L * ms); seen += day.clip.id } // 3 minutes
        assertTrue(seen.any { it in BabaAnimations.IDLE_VARIETY }, "no idle variety: $seen")

        val night = BabaPerformer(Random(3), hourOfDay = { 23 })
        val nightSeen = mutableSetOf<String>()
        for (i in 0 until 3_600) { night.poseAt(i * 50L * ms); nightSeen += night.clip.id }
        assertTrue(nightSeen.none { it in BabaAnimations.IDLE_VARIETY } && nightSeen.any { it in BabaAnimations.NIGHT_IDLE })
    }
}

class BabaCalmTest {
    @Test fun calmOnlyWhileBreathingAndNotDuringReactions() {
        val p = BabaPerformer(Random(4), hourOfDay = { 12 })
        val ms = 1_000_000L
        p.poseAt(0)
        val doze = BabaPerformer(Random(5), hourOfDay = { 23 })
        doze.poseAt(0); doze.play("doze", 1_000 * ms, talk = false); doze.poseAt(1_400 * ms)
        assertTrue((0 until 40).any { doze.isCalm(1_400 * ms + it * 25L * ms) }, "dozing is a slow loop and must count as calm")
        val calmFraction = (1 until 400).count { p.poseAt(it * 25L * ms); p.isCalm(it * 25L * ms) } / 400f
        assertTrue(calmFraction > 0.6f, "idle should be calm most of the time, was $calmFraction")
        val t = 20_000 * ms
        p.play("dance", t, talk = true)
        p.poseAt(t + 100 * ms)
        assertTrue(!p.isCalm(t + 100 * ms))
    }
}
