package app.taskbaba.app.ui.monk.rig

data class Keyframe(val timeMs: Int, val pose: Pose, val ease: Ease)

/**
 * A keyframed clip. [signatureMs] is the moment that best represents it — shown as a still frame when
 * the user prefers reduced motion.
 */
data class BabaAnimation(
    val id: String,
    val label: String,
    val durationMs: Int,
    val loop: Boolean,
    val keys: List<Keyframe>,
    val signatureMs: Int,
    /** Slow, gentle loops (breathing, dozing) that look smooth at a low frame rate. */
    val calm: Boolean = false,
) {
    /** The pose at [elapsedMs]; loops wrap, one-shots hold their last frame. */
    fun sample(elapsedMs: Long): Pose {
        val t = if (loop) (elapsedMs % durationMs).toInt() else elapsedMs.coerceIn(0, durationMs.toLong()).toInt()
        if (t <= keys.first().timeMs) return keys.first().pose
        for (i in 1 until keys.size) {
            val b = keys[i]
            if (t <= b.timeMs) {
                val a = keys[i - 1]
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1)
                return lerpPose(a.pose, b.pose, b.ease.apply((t - a.timeMs).toFloat() / span))
            }
        }
        return keys.last().pose
    }

    fun isFinished(elapsedMs: Long) = !loop && elapsedMs >= durationMs
}

/** Keyframes carry over: each `key` starts from the previous key's pose, so only changes are written. */
class AnimBuilder(private val id: String, private val label: String, private val duration: Int, private val loop: Boolean) {
    private val keys = mutableListOf<Keyframe>()
    private var current = Pose.BASE
    var signature = duration / 2
    var calm = false

    fun key(ms: Int, ease: Ease = Ease.IN_OUT, change: Pose.() -> Pose = { this }) {
        current = current.change()
        keys += Keyframe(ms, current, ease)
    }

    /** Back to the neutral pose (props and effects faded out). */
    fun rest(ms: Int, ease: Ease = Ease.IN_OUT) = key(ms, ease) { Pose.BASE.copy(lProp = lProp, rProp = rProp, fx = fx) }

    fun build() = BabaAnimation(id, label, duration, loop, keys.toList(), signature, calm)
}

private fun anim(id: String, label: String, ms: Int, loop: Boolean = false, block: AnimBuilder.() -> Unit) =
    AnimBuilder(id, label, ms, loop).apply(block).build()

// Small pose helpers so the library reads like stage directions.
fun Pose.hands(lx: Float, ly: Float, rx: Float, ry: Float) = copy(lHandX = lx, lHandY = ly, rHandX = rx, rHandY = ry)
fun Pose.left(x: Float, y: Float) = copy(lHandX = x, lHandY = y)
fun Pose.right(x: Float, y: Float) = copy(rHandX = x, rHandY = y)
fun Pose.happy(on: Boolean = true) = copy(eyeHappy = if (on) 1f else 0f, mouthSmile = if (on) 0.9f else 0.4f)
fun Pose.closedEyes() = copy(eyeOpen = 0f, eyeHappy = 0f)
fun Pose.openEyes() = copy(eyeOpen = 1f)
fun Pose.holding(l: Prop = lProp, r: Prop = rProp, alpha: Float = 1f) = copy(lProp = l, rProp = r, propAlpha = alpha)
fun Pose.effect(f: Fx, alpha: Float = 1f) = copy(fx = f, fxAlpha = alpha)
fun Pose.noEffect() = copy(fxAlpha = 0f)
fun Pose.noProps() = copy(propAlpha = 0f)

/** Baba's full repertoire. Ids are stable; the director maps events to them. */
object BabaAnimations {
    val all: List<BabaAnimation> = listOf(
        // ── Idle ─────────────────────────────────────────────────────────────
        anim("idle_breathe", "Breathing calmly", 4000, loop = true) {
            calm = true
            key(0)
            key(2000) { copy(bodyScaleY = 1.015f, headY = -0.004f) }
            key(4000) { Pose.BASE }
        },
        anim("look_around", "Looking around", 3200) {
            key(0)
            key(500) { copy(lookX = -1f, headRot = -6f) }
            key(1300)
            key(1800) { copy(lookX = 1f, headRot = 6f) }
            key(2600)
            rest(3200)
            signature = 600
        },
        anim("mala_counting", "Counting mala beads", 4400) {
            key(0)
            key(600) { right(0.58f, 0.80f).holding(r = Prop.MALA).closedEyes().copy(mouthSmile = 0.55f) }
            key(3800)
            rest(4400)
            signature = 2000
        },
        anim("nod", "Nodding", 1600) {
            key(0)
            key(300) { copy(headY = 0.02f, headRot = 2f) }
            key(600) { copy(headY = 0f, headRot = 0f) }
            key(900) { copy(headY = 0.02f, headRot = 2f) }
            key(1200) { copy(headY = 0f, headRot = 0f) }
            rest(1600)
        },
        anim("hum", "Humming a bhajan", 3800) {
            key(0)
            key(400) { effect(Fx.MUSIC).closedEyes().copy(mouthOpen = 0.22f, headRot = 5f, talkable = false) }
            key(1200) { copy(headRot = -5f, mouthOpen = 0.12f) }
            key(2000) { copy(headRot = 5f, mouthOpen = 0.22f) }
            key(2900) { copy(headRot = -5f, mouthOpen = 0.12f) }
            key(3800) { Pose.BASE.copy(fx = Fx.MUSIC) }
            signature = 1000
        },
        anim("scratch_head", "Scratching his head", 2800) {
            key(0)
            key(500) { right(0.76f, 0.22f).effect(Fx.QUESTION).copy(headRot = 6f, lookX = 0.6f, lookY = -0.6f, mouthSmile = 0f) }
            key(900) { right(0.73f, 0.25f) }
            key(1300) { right(0.76f, 0.22f) }
            key(1700) { right(0.73f, 0.25f) }
            key(2200)
            rest(2800)
            signature = 900
        },
        anim("deep_breath", "Taking a deep breath", 4200) {
            key(0)
            key(1600) { closedEyes().copy(bodyScaleY = 1.045f, headY = -0.012f, mouthSmile = 0.3f) }
            key(2000)
            key(3500) { copy(bodyScaleY = 0.99f, headY = 0.004f, mouthOpen = 0.15f, talkable = false) }
            rest(4200)
            signature = 1700
        },

        // ── Greetings & time of day ──────────────────────────────────────────
        anim("wave_hello", "Waving hello", 2600) {
            key(0)
            key(400, Ease.BACK_OUT) { right(0.84f, 0.36f).happy().copy(mouthOpen = 0.3f) }
            key(700) { right(0.90f, 0.40f) }
            key(1000) { right(0.82f, 0.34f) }
            key(1300) { right(0.90f, 0.40f) }
            key(1600) { right(0.82f, 0.34f) }
            key(2000)
            rest(2600)
            signature = 700
        },
        anim("namaste", "Namaste", 2800) {
            key(0)
            key(600) { hands(0.475f, 0.70f, 0.525f, 0.70f).closedEyes().copy(mouthSmile = 0.6f) }
            key(1200) { copy(headY = 0.025f, bodyScaleY = 0.985f) }
            key(1900) { copy(headY = 0f, bodyScaleY = 1f) }
            key(2300)
            rest(2800)
            signature = 1300
        },
        anim("yawn", "Yawning", 3200) {
            key(0)
            key(700) { right(0.57f, 0.50f).closedEyes().copy(mouthOpen = 1f, mouthWidth = 0.75f, bodyScaleY = 1.03f, talkable = false) }
            key(1800)
            key(2300) { right(Pose.REST_R_X, Pose.REST_Y).copy(mouthOpen = 0f, mouthWidth = 1f, bodyScaleY = 1f, eyeOpen = 0.35f) }
            key(2800) { effect(Fx.ZZZ, 0.7f) }
            rest(3200)
            signature = 1000
        },
        anim("doze", "Dozing off", 6000, loop = true) {
            calm = true
            key(0) { closedEyes().effect(Fx.ZZZ).copy(headRot = 8f, headY = 0.015f, mouthSmile = 0.2f, talkable = false) }
            key(3000) { copy(headRot = 11f, headY = 0.025f, bodyScaleY = 1.01f) }
            key(6000) { copy(headRot = 8f, headY = 0.015f, bodyScaleY = 1f) }
        },

        // ── Calm & spiritual ────────────────────────────────────────────────
        anim("meditate", "Meditating", 5000) {
            key(0)
            key(800) { hands(0.40f, 0.88f, 0.60f, 0.88f).closedEyes().effect(Fx.SPARKLES, 0.5f).copy(mouthSmile = 0.5f, bodyY = -0.01f) }
            key(2500) { copy(bodyY = -0.022f) }
            key(4200) { copy(bodyY = -0.01f) }
            rest(5000)
            signature = 2500
        },
        anim("float_meditate", "Floating in peace", 6000) {
            key(0)
            key(1000) { hands(0.40f, 0.86f, 0.60f, 0.86f).closedEyes().effect(Fx.SPARKLES, 0.8f).copy(bodyY = -0.035f, mouthSmile = 0.6f) }
            key(2500) { copy(bodyY = -0.05f, bodyRot = 1.5f) }
            key(4000) { copy(bodyY = -0.035f, bodyRot = -1.5f) }
            key(5200)
            rest(6000)
            signature = 2500
        },
        anim("lightbulb", "Bright idea", 3000) {
            key(0)
            key(400, Ease.BACK_OUT) { right(0.76f, 0.40f).effect(Fx.BULB).copy(eyeOpen = 1.3f, browRaise = 1f, mouthOpen = 0.3f) }
            key(1200) { right(0.77f, 0.37f) }
            key(2300) { copy(mouthOpen = 0f, mouthSmile = 0.8f) }
            rest(3000)
            signature = 900
        },

        // ── Tasks ───────────────────────────────────────────────────────────
        anim("write_note", "Writing it down", 3400) {
            key(0)
            key(500) { hands(0.40f, 0.74f, 0.47f, 0.72f).holding(Prop.NOTEPAD, Prop.PEN).copy(lookY = 1f, lookX = -0.4f, headRot = -4f) }
            key(900) { right(0.50f, 0.74f) }
            key(1300) { right(0.46f, 0.76f) }
            key(1700) { right(0.50f, 0.78f) }
            key(2100) { right(0.47f, 0.72f) }
            key(2600) { copy(lookY = 0f, lookX = 0f, headRot = 0f).happy() }
            rest(3400)
            signature = 1000
        },
        anim("point_star", "Choosing the important one", 3000) {
            key(0)
            key(500, Ease.BACK_OUT) { right(0.80f, 0.30f).holding(r = Prop.STAR).effect(Fx.SPARKLES).happy().copy(lookX = 0.8f, lookY = -0.6f) }
            key(2200)
            rest(3000)
            signature = 1200
        },
        anim("fist_pump", "Yes! Done!", 2200) {
            key(0)
            key(250) { right(0.76f, 0.60f).copy(bodyScaleY = 0.98f) }
            key(550, Ease.BACK_OUT) { right(0.80f, 0.30f).happy().copy(bodyY = -0.02f, mouthOpen = 0.4f) }
            key(900) { right(0.78f, 0.40f) }
            key(1200) { right(0.80f, 0.30f) }
            key(1700) { copy(bodyY = 0f, mouthOpen = 0f) }
            rest(2200)
            signature = 600
        },
        anim("nod_proud", "Proud nod", 2400) {
            key(0)
            key(400) { happy().effect(Fx.SPARKLES, 0.7f).copy(headY = 0.02f) }
            key(800) { copy(headY = 0f) }
            key(1200) { copy(headY = 0.02f) }
            key(1600) { copy(headY = 0f) }
            rest(2400)
            signature = 500
        },
        anim("dance", "Victory dance", 4000) {
            key(0)
            key(300) { hands(0.18f, 0.36f, 0.70f, 0.95f).happy().effect(Fx.MUSIC).copy(bodyRot = -6f, bodyY = -0.02f, mouthOpen = 0.35f) }
            key(650) { copy(bodyY = 0f) }
            key(1000) { hands(0.30f, 0.95f, 0.82f, 0.36f).copy(bodyRot = 6f, bodyY = -0.02f) }
            key(1350) { copy(bodyY = 0f) }
            key(1700) { hands(0.18f, 0.36f, 0.70f, 0.95f).copy(bodyRot = -6f, bodyY = -0.02f) }
            key(2050) { copy(bodyY = 0f) }
            key(2400) { hands(0.30f, 0.95f, 0.82f, 0.36f).copy(bodyRot = 6f, bodyY = -0.02f) }
            key(2750) { copy(bodyY = 0f) }
            key(3200) { hands(0.22f, 0.34f, 0.78f, 0.34f).copy(bodyRot = 0f) }
            rest(4000)
            signature = 1000
        },
        anim("celebrate", "Celebration!", 4200) {
            key(0)
            key(300) { copy(bodyScaleY = 0.96f) }
            key(650, Ease.BACK_OUT) { hands(0.20f, 0.28f, 0.80f, 0.28f).happy().effect(Fx.CONFETTI).copy(bodyY = -0.05f, bodyScaleY = 1.03f, mouthOpen = 0.6f) }
            key(1000) { copy(bodyY = 0f, bodyScaleY = 1f) }
            key(1350, Ease.BACK_OUT) { copy(bodyY = -0.04f) }
            key(1700) { copy(bodyY = 0f) }
            key(3300) { hands(0.24f, 0.32f, 0.76f, 0.32f).copy(mouthOpen = 0.3f) }
            rest(4200)
            signature = 700
        },

        // ── Deadlines & postponing ──────────────────────────────────────────
        anim("tap_watch", "Tapping his watch", 3000) {
            key(0)
            key(500) { hands(0.40f, 0.72f, 0.52f, 0.66f).holding(l = Prop.WATCH).copy(lookY = 1f, lookX = -0.6f, browAngle = -0.5f, mouthSmile = 0f) }
            key(750) { right(0.45f, 0.70f) }
            key(1000) { right(0.52f, 0.66f) }
            key(1250) { right(0.45f, 0.70f) }
            key(1500) { right(0.52f, 0.66f) }
            key(1900) { copy(lookY = 0f, lookX = 0f) }
            key(2500)
            rest(3000)
            signature = 800
        },
        anim("surprised", "Surprised!", 2400) {
            key(0)
            key(250, Ease.BACK_OUT) { hands(0.36f, 0.48f, 0.64f, 0.48f).effect(Fx.EXCLAIM).copy(eyeOpen = 1.35f, browRaise = 1f, mouthOpen = 0.55f, mouthWidth = 0.6f, bodyY = -0.015f, talkable = false) }
            key(1700)
            rest(2400)
            signature = 600
        },
        anim("sweat_worry", "A little worried", 3200) {
            key(0)
            key(500) { hands(0.46f, 0.72f, 0.54f, 0.72f).effect(Fx.SWEAT).copy(browAngle = 1f, mouthSmile = -0.2f, eyeOpen = 1.12f, lookX = -0.5f) }
            key(1400) { copy(lookX = 0.5f) }
            key(2300) { copy(lookX = 0f) }
            rest(3200)
            signature = 900
        },
        anim("wag_finger", "Wagging a finger", 2800) {
            key(0)
            key(400) { right(0.74f, 0.42f).copy(browAngle = -1f, mouthSmile = -0.3f, headRot = -4f) }
            key(650) { right(0.80f, 0.42f) }
            key(900) { right(0.70f, 0.42f) }
            key(1150) { right(0.80f, 0.42f) }
            key(1400) { right(0.70f, 0.42f) }
            key(1650) { right(0.76f, 0.42f) }
            key(2200)
            rest(2800)
            signature = 650
        },
        anim("eyebrow_raise", "Raising an eyebrow", 2600) {
            key(0)
            key(400) { effect(Fx.QUESTION).copy(browRaise = 1f, lookX = 0.7f, headRot = 6f, mouthSmile = 0.05f, mouthWidth = 0.8f) }
            key(1900)
            rest(2600)
            signature = 900
        },
        anim("shrug", "Shrugging", 2400) {
            key(0)
            key(450, Ease.BACK_OUT) { hands(0.18f, 0.64f, 0.82f, 0.64f).copy(bodyY = -0.012f, browRaise = 0.8f, headRot = 8f, mouthSmile = 0.1f) }
            key(1600)
            rest(2400)
            signature = 800
        },
        anim("facepalm", "Facepalm", 3200) {
            key(0)
            key(500) { left(0.45f, 0.30f).closedEyes().copy(headRot = -8f, headY = 0.02f, mouthSmile = -0.4f, bodyScaleY = 0.98f) }
            key(1200) { copy(headRot = -11f) }
            key(1800) { copy(headRot = -6f) }
            key(2500)
            rest(3200)
            signature = 1100
        },

        // ── Health ──────────────────────────────────────────────────────────
        anim("drink_water", "Drinking water", 3800) {
            key(0)
            key(500) { right(0.62f, 0.66f).holding(r = Prop.GLASS) }
            key(1100) { right(0.58f, 0.53f).closedEyes().copy(headRot = -5f, headY = -0.006f, talkable = false) }
            key(2300) { right(0.57f, 0.51f) }
            key(2800) { right(0.62f, 0.66f).happy().copy(headRot = 0f, headY = 0f, mouthOpen = 0.25f) }
            key(3300) { noProps().copy(mouthOpen = 0f) }
            rest(3800)
            signature = 1600
        },
        anim("eye_rest", "Resting his eyes", 4400) {
            key(0)
            key(600) { hands(0.40f, 0.33f, 0.60f, 0.33f).copy(eyeOpen = 0f, mouthSmile = 0.5f, headY = 0.01f) }
            key(2200)
            key(2800) { hands(Pose.REST_L_X, Pose.REST_Y, Pose.REST_R_X, Pose.REST_Y).copy(eyeOpen = 1f, lookX = 1f, lookY = -0.7f, headRot = 4f, headY = 0f) }
            key(3800)
            rest(4400)
            signature = 1400
        },
        anim("stretch_up", "Stretching", 4400) {
            key(0)
            key(800) { hands(0.26f, 0.24f, 0.74f, 0.24f).closedEyes().copy(bodyScaleY = 1.045f, bodyY = -0.012f, mouthOpen = 0.3f, talkable = false) }
            key(1500) { copy(bodyRot = -6f) }
            key(2300) { copy(bodyRot = 6f) }
            key(3000) { copy(bodyRot = 0f, mouthOpen = 0f).happy() }
            key(3700) { hands(Pose.REST_L_X, Pose.REST_Y, Pose.REST_R_X, Pose.REST_Y).copy(bodyScaleY = 1f, bodyY = 0f) }
            rest(4400)
            signature = 900
        },
        anim("walk_in_place", "Walking around", 3600) {
            key(0) { effect(Fx.DUST, 0f) }
            for (i in 0 until 8) {
                val t = 300 + i * 360
                val even = i % 2 == 0
                key(t) {
                    hands(if (even) 0.27f else 0.32f, if (even) 0.98f else 1.08f, if (even) 0.68f else 0.73f, if (even) 1.08f else 0.98f)
                        .effect(Fx.DUST).copy(bodyY = if (even) -0.02f else 0f, bodyRot = if (even) -2f else 2f, mouthSmile = 0.6f)
                }
            }
            rest(3600)
            signature = 300
        },
        anim("eat", "Enjoying a meal", 4600) {
            key(0)
            key(500) { hands(0.44f, 0.76f, 0.56f, 0.70f).holding(l = Prop.BOWL).copy(lookY = 1f, talkable = false) }
            key(1000) { right(0.53f, 0.52f).copy(lookY = 0f, mouthOpen = 0.4f) }
            key(1300) { right(0.56f, 0.70f).copy(mouthOpen = 0.05f) }
            key(1600) { copy(mouthOpen = 0.2f) }
            key(1900) { copy(mouthOpen = 0.05f) }
            key(2400) { right(0.53f, 0.52f).copy(mouthOpen = 0.4f) }
            key(2700) { right(0.56f, 0.70f).copy(mouthOpen = 0.05f) }
            key(3000) { copy(mouthOpen = 0.2f) }
            key(3400) { happy().copy(mouthOpen = 0f) }
            key(4000) { noProps() }
            rest(4600)
            signature = 1000
        },
        anim("chai_sip", "Sipping chai", 4200) {
            key(0)
            key(500) { right(0.60f, 0.64f).holding(r = Prop.CHAI) }
            key(1200) { right(0.57f, 0.53f).closedEyes().copy(blush = 1f, talkable = false) }
            key(2200)
            key(2800) { right(0.60f, 0.64f).happy().copy(blush = 0.9f) }
            key(3600) { noProps() }
            rest(4200)
            signature = 1500
        },
        anim("clap", "Clapping", 2400) {
            key(0)
            for (i in 0 until 5) {
                key(250 + i * 340) { hands(0.47f, 0.62f, 0.53f, 0.62f).happy().copy(mouthOpen = 0.3f) }
                key(420 + i * 340) { hands(0.38f, 0.60f, 0.62f, 0.60f) }
            }
            rest(2400)
            signature = 250
        },
        anim("love_it", "Delighted", 2800) {
            key(0)
            key(400) { hands(0.47f, 0.62f, 0.53f, 0.62f).happy().effect(Fx.HEARTS).copy(blush = 1.2f, headRot = 6f) }
            key(1100) { copy(headRot = -6f) }
            key(1800) { copy(headRot = 4f) }
            rest(2800)
            signature = 600
        },
        anim("gentle_shrug", "No worries", 2400) {
            key(0)
            key(450) { hands(0.24f, 0.70f, 0.76f, 0.70f).copy(headRot = 6f, mouthSmile = 0.6f, eyeOpen = 0.7f) }
            key(1600)
            rest(2400)
            signature = 800
        },

        // ── Coming & going, day summaries ───────────────────────────────────
        anim("welcome_jump", "Welcome back!", 3000) {
            key(0)
            key(250) { copy(bodyScaleY = 0.95f) }
            key(600, Ease.BACK_OUT) { hands(0.20f, 0.30f, 0.80f, 0.30f).happy().effect(Fx.HEARTS).copy(bodyY = -0.05f, bodyScaleY = 1.03f, mouthOpen = 0.5f) }
            key(950) { copy(bodyY = 0f, bodyScaleY = 1f) }
            key(1300) { right(0.88f, 0.38f) }
            key(1600) { right(0.80f, 0.32f) }
            key(1900) { right(0.88f, 0.38f) }
            key(2300) { copy(mouthOpen = 0f) }
            rest(3000)
            signature = 650
        },
        anim("bow_blessing", "Blessing you", 4200) {
            key(0)
            key(600) { hands(0.475f, 0.70f, 0.525f, 0.70f).closedEyes().copy(mouthSmile = 0.6f) }
            key(1300) { copy(headY = 0.03f, bodyScaleY = 0.975f) }
            key(2000) { copy(headY = 0f, bodyScaleY = 1f) }
            key(2600, Ease.BACK_OUT) { right(0.78f, 0.44f).holding(r = Prop.LOTUS).effect(Fx.SPARKLES).happy() }
            key(3500)
            rest(4200)
            signature = 2800
        },
        anim("head_shake_kind", "Gentle head shake", 2800) {
            key(0)
            key(400) { copy(headRot = -7f, eyeOpen = 0.6f, browAngle = 0.6f, mouthSmile = 0.25f) }
            key(900) { copy(headRot = 7f) }
            key(1400) { copy(headRot = -7f) }
            key(1900) { copy(headRot = 0f) }
            rest(2800)
            signature = 400
        },
        anim("sigh", "A long sigh", 3200) {
            key(0)
            key(900) { copy(bodyScaleY = 1.025f, eyeOpen = 0.5f) }
            key(2000) { hands(0.28f, 1.08f, 0.72f, 1.08f).copy(bodyScaleY = 0.965f, headY = 0.015f, eyeOpen = 0.3f, mouthOpen = 0.15f, mouthSmile = -0.15f, talkable = false) }
            key(2700)
            rest(3200)
            signature = 2000
        },
    )

    private val byId = all.associateBy { it.id }
    operator fun get(id: String): BabaAnimation = byId[id] ?: byId.getValue(IDLE)

    const val IDLE = "idle_breathe"
    val IDLE_VARIETY = listOf("look_around", "mala_counting", "nod", "hum", "scratch_head", "deep_breath")
    val NIGHT_IDLE = listOf("yawn", "doze")
}
