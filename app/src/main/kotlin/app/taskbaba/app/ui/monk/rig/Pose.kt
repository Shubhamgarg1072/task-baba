package app.taskbaba.app.ui.monk.rig

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/** Something Baba holds in a hand. */
enum class Prop { NONE, GLASS, CHAI, BOWL, WATCH, NOTEPAD, PEN, STAR, LOTUS, MALA }

/** A screen effect drawn around Baba; animated by elapsed time. */
enum class Fx { NONE, ZZZ, CONFETTI, SWEAT, BULB, HEARTS, SPARKLES, QUESTION, EXCLAIM, MUSIC, DUST, STEAM }

/**
 * Every animatable parameter of the rig. Positions are fractions of the canvas (0..1, square canvas);
 * angles are degrees. Hands are placed by target position and the elbows are solved with 2-bone IK,
 * which makes animations easy to author ("right hand at mouth").
 */
data class Pose(
    val bodyY: Float = 0f,
    val bodyRot: Float = 0f,
    val bodyScaleY: Float = 1f,
    val headRot: Float = 0f,
    val headX: Float = 0f,
    val headY: Float = 0f,
    /** 1 = open, 0 = closed, >1 = wide. */
    val eyeOpen: Float = 1f,
    /** 0 = neutral open eyes, 1 = happy upward arcs. */
    val eyeHappy: Float = 0f,
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    val browRaise: Float = 0f,
    /** −1 = angry (inner ends down), +1 = worried (inner ends up). */
    val browAngle: Float = 0f,
    val mouthOpen: Float = 0f,
    /** −1 frown … +1 big smile. */
    val mouthSmile: Float = 0.4f,
    val mouthWidth: Float = 1f,
    val blush: Float = 0.6f,
    val lHandX: Float = REST_L_X,
    val lHandY: Float = REST_Y,
    val rHandX: Float = REST_R_X,
    val rHandY: Float = REST_Y,
    val lProp: Prop = Prop.NONE,
    val rProp: Prop = Prop.NONE,
    val propAlpha: Float = 0f,
    val fx: Fx = Fx.NONE,
    val fxAlpha: Float = 0f,
    /** Whether the director may add lip-sync while Baba "speaks" a fresh line. */
    val talkable: Boolean = true,
) {
    companion object {
        // Resting hands sit in the lap, just below the visible edge of the robe.
        const val REST_L_X = 0.30f
        const val REST_R_X = 0.70f
        const val REST_Y = 1.04f
        val BASE = Pose()
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Props/effects: whichever side has one is shown, so they fade in and out with their alpha instead of popping. */
fun lerpPose(a: Pose, b: Pose, t: Float): Pose = Pose(
    bodyY = lerp(a.bodyY, b.bodyY, t),
    bodyRot = lerp(a.bodyRot, b.bodyRot, t),
    bodyScaleY = lerp(a.bodyScaleY, b.bodyScaleY, t),
    headRot = lerp(a.headRot, b.headRot, t),
    headX = lerp(a.headX, b.headX, t),
    headY = lerp(a.headY, b.headY, t),
    eyeOpen = lerp(a.eyeOpen, b.eyeOpen, t),
    eyeHappy = lerp(a.eyeHappy, b.eyeHappy, t),
    lookX = lerp(a.lookX, b.lookX, t),
    lookY = lerp(a.lookY, b.lookY, t),
    browRaise = lerp(a.browRaise, b.browRaise, t),
    browAngle = lerp(a.browAngle, b.browAngle, t),
    mouthOpen = lerp(a.mouthOpen, b.mouthOpen, t),
    mouthSmile = lerp(a.mouthSmile, b.mouthSmile, t),
    mouthWidth = lerp(a.mouthWidth, b.mouthWidth, t),
    blush = lerp(a.blush, b.blush, t),
    lHandX = lerp(a.lHandX, b.lHandX, t),
    lHandY = lerp(a.lHandY, b.lHandY, t),
    rHandX = lerp(a.rHandX, b.rHandX, t),
    rHandY = lerp(a.rHandY, b.rHandY, t),
    lProp = a.lProp.takeIf { it != Prop.NONE } ?: b.lProp,
    rProp = a.rProp.takeIf { it != Prop.NONE } ?: b.rProp,
    propAlpha = lerp(a.propAlpha, b.propAlpha, t),
    fx = a.fx.takeIf { it != Fx.NONE } ?: b.fx,
    fxAlpha = lerp(a.fxAlpha, b.fxAlpha, t),
    talkable = if (t < 0.5f) a.talkable else b.talkable,
)

enum class Ease {
    LINEAR, IN_OUT, OUT, IN, BACK_OUT;

    fun apply(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            IN_OUT -> (0.5 - 0.5 * cos(PI * t)).toFloat()
            OUT -> 1f - (1f - t).pow(3)
            IN -> t * t * t
            BACK_OUT -> {
                val c1 = 1.70158f
                val c3 = c1 + 1f
                1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
            }
        }
    }
}
