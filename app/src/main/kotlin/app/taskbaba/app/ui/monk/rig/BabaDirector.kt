package app.taskbaba.app.ui.monk.rig

import app.taskbaba.core.monk.MonkEvent
import app.taskbaba.core.monk.MonkMood
import app.taskbaba.core.monk.Tone
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Chooses which clip Baba performs for each situation. */
object BabaDirector {
    fun reactionFor(event: MonkEvent, tone: Tone?, variant: Int = 0): String {
        val savage = tone == Tone.SAVAGE
        fun pick(vararg ids: String) = ids[abs(variant) % ids.size]
        if (tone == null) return pick("nod", "namaste") // muted: calm, no theatrics
        return when (event) {
            MonkEvent.GREETING_MORNING -> pick("wave_hello", "namaste")
            MonkEvent.GREETING_AFTERNOON -> pick("namaste", "wave_hello", "deep_breath")
            MonkEvent.GREETING_EVENING -> pick("yawn", "namaste")
            MonkEvent.EMPTY_DAY -> if (savage) "eyebrow_raise" else "float_meditate"
            MonkEvent.TASK_CREATED -> "write_note"
            MonkEvent.MIT_CHOSEN -> "point_star"
            MonkEvent.TASK_DONE -> if (savage) pick("nod_proud", "eyebrow_raise") else pick("fist_pump", "nod_proud")
            MonkEvent.IMPORTANT_DONE -> "dance"
            MonkEvent.ALL_PRIORITIES_DONE -> "celebrate"
            MonkEvent.TASK_DUE_SOON -> if (savage) "surprised" else "tap_watch"
            MonkEvent.TASK_OVERDUE -> if (savage) pick("wag_finger", "tap_watch") else "sweat_worry"
            MonkEvent.TASK_POSTPONED -> if (savage) "eyebrow_raise" else "shrug"
            MonkEvent.TASK_POSTPONED_REPEAT -> if (savage) "facepalm" else "scratch_head"
            MonkEvent.HEALTH_WATER -> "drink_water"
            MonkEvent.HEALTH_EYES -> "eye_rest"
            MonkEvent.HEALTH_STRETCH -> "stretch_up"
            MonkEvent.HEALTH_WALK -> "walk_in_place"
            MonkEvent.HEALTH_MEAL -> "eat"
            MonkEvent.HEALTH_REST -> pick("chai_sip", "deep_breath")
            MonkEvent.HEALTH_DONE -> if (savage) "love_it" else "clap"
            MonkEvent.HEALTH_SKIPPED -> "gentle_shrug"
            MonkEvent.RETURN_FROM_BREAK -> "welcome_jump"
            MonkEvent.PRODUCTIVE_DAY -> "bow_blessing"
            MonkEvent.UNPRODUCTIVE_DAY -> if (savage) "sigh" else "head_shake_kind"
            MonkEvent.FOCUS_TIP -> pick("lightbulb", "meditate", "mala_counting")
        }
    }

    /** A still pose for places that only know a mood (sidebar icon, custom-art fallback). */
    fun poseFor(mood: MonkMood): Pose = when (mood) {
        MonkMood.CALM -> Pose.BASE.closedEyes().copy(mouthSmile = 0.45f)
        MonkMood.HAPPY -> Pose.BASE.happy()
        MonkMood.STERN -> Pose.BASE.copy(browAngle = -1f, mouthSmile = -0.25f)
        MonkMood.CELEBRATE -> Pose.BASE.happy().hands(0.22f, 0.32f, 0.78f, 0.32f).copy(mouthOpen = 0.5f)
        MonkMood.SLEEPY -> Pose.BASE.closedEyes().copy(headRot = 8f, mouthSmile = 0.2f)
    }
}

/**
 * Paces redraws at [fps]. Instead of being asked on every display refresh (which in Compose Desktop
 * renders the whole window each time), the animator sleeps until the next frame is due. Deadlines
 * accumulate, so 40 fps on a 60 Hz display alternates 1- and 2-refresh gaps and still averages 40.
 */
class FramePacer(private val vsyncSlackNanos: Long = 8_000_000L) {
    private var deadline = Long.MIN_VALUE

    /** Call after drawing a frame at [nowNanos]; returns how long to sleep before requesting the next one. */
    fun delayAfterFrame(nowNanos: Long, fps: Int): Long {
        val interval = 1_000_000_000L / fps.coerceIn(1, 240)
        deadline = if (deadline == Long.MIN_VALUE || deadline < nowNanos - interval) nowNanos + interval else deadline + interval
        return (deadline - nowNanos - vsyncSlackNanos).coerceAtLeast(0L)
    }
}

/**
 * Plays clips over time: reactions, then idle with occasional variety; crossfades between clips,
 * blinks at random, and lip-syncs while a new line is fresh. Pure (time in, pose out) so it is testable.
 */
class BabaPerformer(private val random: Random = Random.Default, private val hourOfDay: () -> Int = { java.time.LocalTime.now().hour }) {
    var clip: BabaAnimation = BabaAnimations[BabaAnimations.IDLE]
        private set
    private var clipStart = 0L
    private var started = false
    private var blendFrom: Pose? = null
    private var blendStart = 0L
    private var lastPose = Pose.BASE
    private var nextIdleVariety = 0L
    private var nextBlink = 0L
    private var talkUntil = 0L

    fun play(id: String, nowNanos: Long, talk: Boolean) {
        blendFrom = lastPose
        blendStart = nowNanos
        clip = BabaAnimations[id]
        clipStart = nowNanos
        started = true
        if (talk) talkUntil = nowNanos + TALK_NANOS
    }

    /**
     * True while Baba is only breathing (no reaction, blink, lip-sync or crossfade in progress). The
     * animator redraws far less often then, since nothing moves fast.
     */
    fun isCalm(nowNanos: Long): Boolean =
        started && clip.calm && blendFrom == null && nowNanos >= talkUntil &&
            nowNanos < nextBlink - BLINK_LEAD_NANOS &&
            (clip.id != BabaAnimations.IDLE || nowNanos < nextIdleVariety - BLINK_LEAD_NANOS)

    fun elapsedSeconds(nowNanos: Long): Float = (nowNanos - clipStart) / 1e9f

    fun poseAt(nowNanos: Long): Pose {
        if (!started) { clipStart = nowNanos; started = true; scheduleIdle(nowNanos); nextBlink = nowNanos + blinkGap() }
        if (clip.isFinished(ms(nowNanos - clipStart))) {
            play(BabaAnimations.IDLE, nowNanos, talk = false)
            scheduleIdle(nowNanos)
        } else if (clip.id == BabaAnimations.IDLE && nowNanos >= nextIdleVariety) {
            val night = hourOfDay() >= 22 || hourOfDay() < 6
            val pool = if (night) BabaAnimations.NIGHT_IDLE else BabaAnimations.IDLE_VARIETY
            play(pool[random.nextInt(pool.size)], nowNanos, talk = false)
        }

        var pose = clip.sample(ms(nowNanos - clipStart))
        blendFrom?.let { from ->
            val t = (nowNanos - blendStart).toFloat() / BLEND_NANOS
            if (t >= 1f) blendFrom = null else pose = lerpPose(from, pose, Ease.IN_OUT.apply(t))
        }
        if (nowNanos < talkUntil && pose.talkable && pose.mouthOpen < 0.15f) {
            val phase = (nowNanos - (talkUntil - TALK_NANOS)) / 1e9f
            pose = pose.copy(mouthOpen = 0.12f + 0.3f * abs(sin(phase * 13f)) * abs(sin(phase * 5.3f + 0.7f)))
        }
        if (nowNanos >= nextBlink) {
            if (nowNanos < nextBlink + BLINK_NANOS) {
                if (pose.eyeOpen > 0.3f && pose.eyeHappy < 0.5f) pose = pose.copy(eyeOpen = 0.05f)
            } else nextBlink = nowNanos + blinkGap()
        }
        lastPose = pose
        return pose
    }

    private fun scheduleIdle(now: Long) { nextIdleVariety = now + (18_000L + random.nextLong(22_000L)) * 1_000_000L }
    private fun blinkGap() = (2_400L + random.nextLong(3_800L)) * 1_000_000L
    private fun ms(nanos: Long) = nanos / 1_000_000L

    companion object {
        const val BLEND_NANOS = 280_000_000L
        const val BLINK_NANOS = 140_000_000L
        const val TALK_NANOS = 2_200_000_000L
        /** Switch back to full frame rate slightly before a blink or idle move starts. */
        const val BLINK_LEAD_NANOS = 60_000_000L
        const val CALM_FPS = 5
    }
}
