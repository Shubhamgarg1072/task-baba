package app.taskbaba.app.ui.monk.rig

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val Skin = Color(0xFFE8B48C)
private val SkinShade = Color(0xFFD9A27A)
private val Robe = Color(0xFFE07A1F)
private val RobeShade = Color(0xFFC6631A)
private val Ink = Color(0xFF3A2A20)
private val Bead = Color(0xFF7A3E1D)
private val Blush = Color(0xFFE8A07E)
private val Tilak = Color(0xFFD9480F)
private val Water = Color(0xFF6FB7E9)
private val Gold = Color(0xFFF2B233)
private val Pink = Color(0xFFE97DA0)
private val Leaf = Color(0xFF5DAE73)

object RigGeometry {
    const val SHOULDER_Y = 0.68f
    const val SHOULDER_L_X = 0.32f
    const val SHOULDER_R_X = 0.68f
    const val UPPER_ARM = 0.23f
    const val FOREARM = 0.22f

    /**
     * 2-bone IK: returns the elbow for a shoulder→hand chain, bending the elbow away from the body
     * centre. Unreachable targets are clamped to full extension.
     */
    fun elbow(sx: Float, sy: Float, hx: Float, hy: Float, outwardSign: Float): Offset {
        val a = UPPER_ARM
        val b = FOREARM
        val dx = hx - sx
        val dy = hy - sy
        val d = hypot(dx, dy).coerceIn(abs(a - b) + 1e-3f, a + b - 1e-3f)
        val theta = atan2(dy, dx)
        val alpha = acos(((a * a + d * d - b * b) / (2 * a * d)).coerceIn(-1f, 1f))
        val e1 = Offset(sx + a * cos(theta + alpha), sy + a * sin(theta + alpha))
        val e2 = Offset(sx + a * cos(theta - alpha), sy + a * sin(theta - alpha))
        // Prefer the elbow that points away from the body; when the hand is below the shoulder and both
        // candidates are outward, take the lower one so arms hang naturally instead of "chicken wings".
        val o1 = (e1.x - sx) * outwardSign
        val o2 = (e2.x - sx) * outwardSign
        if (hy > sy && o1 > 0f && o2 > 0f) return if (e1.y >= e2.y) e1 else e2
        return if (o1 >= o2) e1 else e2
    }

    /** Where the hand actually ends up after clamping to arm reach. */
    fun reachableHand(sx: Float, sy: Float, hx: Float, hy: Float): Offset {
        val dx = hx - sx
        val dy = hy - sy
        val d = hypot(dx, dy)
        val max = UPPER_ARM + FOREARM - 1e-3f
        return if (d <= max) Offset(hx, hy) else Offset(sx + dx / d * max, sy + dy / d * max)
    }
}

/** Draws Baba in [pose]; [time] (seconds) drives effects such as steam, confetti and floating Zzz. */
fun DrawScope.drawBaba(pose: Pose, time: Float) {
    val s = size.minDimension
    val cx = s / 2
    fun p(x: Float, y: Float) = Offset(x * s, y * s)

    translate(0f, pose.bodyY * s) {
        rotate(pose.bodyRot, pivot = p(0.5f, 1f)) {
            scale(1f, pose.bodyScaleY, pivot = p(0.5f, 1f)) {
                // Robe (extends below the canvas so bounces never reveal a gap)
                val robe = Path().apply {
                    moveTo(-s * 0.02f, s * 1.40f); lineTo(s * 0.05f, s * 1.05f)
                    cubicTo(s * 0.10f, s * 0.70f, s * 0.30f, s * 0.64f, cx, s * 0.64f)
                    cubicTo(s * 0.70f, s * 0.64f, s * 0.90f, s * 0.70f, s * 0.95f, s * 1.05f); lineTo(s * 1.02f, s * 1.40f); close()
                }
                drawPath(robe, Robe)
                drawPath(Path().apply {
                    moveTo(s * 0.33f, s * 0.67f); lineTo(s * 0.45f, s * 0.67f); lineTo(s * 0.98f, s * 1.40f); lineTo(s * 0.80f, s * 1.40f); close()
                }, RobeShade)
                drawRect(SkinShade, p(0.44f, 0.55f), Size(s * 0.12f, s * 0.11f))
                for (i in 0..10) {
                    val t = i / 10f
                    drawCircle(Bead, s * 0.021f, p(0.31f + t * 0.38f, 0.69f + (1 - (2 * t - 1) * (2 * t - 1)) * 0.07f))
                }

                translate(pose.headX * s, pose.headY * s) {
                    rotate(pose.headRot, pivot = p(0.5f, 0.56f)) { drawHead(pose, s, time) }
                }

                drawArm(pose.lHandX, pose.lHandY, RigGeometry.SHOULDER_L_X, -1f, pose.lProp, pose.propAlpha, s, time)
                drawArm(pose.rHandX, pose.rHandY, RigGeometry.SHOULDER_R_X, 1f, pose.rProp, pose.propAlpha, s, time)
            }
        }
    }
    if (pose.fx != Fx.NONE && pose.fxAlpha > 0.01f) drawFx(pose.fx, pose.fxAlpha.coerceIn(0f, 1f), s, time, pose)
}

private fun DrawScope.drawHead(pose: Pose, s: Float, time: Float) {
    val cx = s / 2
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    drawOval(Skin, p(0.25f, 0.30f), Size(s * 0.09f, s * 0.14f))
    drawOval(Skin, p(0.66f, 0.30f), Size(s * 0.09f, s * 0.14f))
    drawOval(Skin, p(0.29f, 0.10f), Size(s * 0.42f, s * 0.50f))
    drawOval(Color.White.copy(alpha = 0.18f), p(0.37f, 0.13f), Size(s * 0.16f, s * 0.08f))
    val tl = s * 0.006f
    listOf(0.205f, 0.225f, 0.245f).forEachIndexed { i, y ->
        val half = if (i == 1) s * 0.07f else s * 0.055f
        drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(cx - half, s * y - tl), Size(half * 2, tl * 2.4f), CornerRadius(tl))
    }
    drawCircle(Tilak, s * 0.018f, p(0.5f, 0.225f))
    val blush = Blush.copy(alpha = 0.7f * pose.blush.coerceIn(0f, 1.4f).coerceAtMost(1f))
    drawOval(blush, p(0.33f, 0.38f), Size(s * 0.08f, s * 0.04f))
    drawOval(blush, p(0.59f, 0.38f), Size(s * 0.08f, s * 0.04f))

    val stroke = Stroke(width = s * 0.022f, cap = StrokeCap.Round)
    val eyeY = s * 0.33f
    val ew = s * 0.055f
    // Brows
    listOf(0.40f to 1f, 0.60f to -1f).forEach { (x, side) ->
        val bx = x * s
        val by = s * (0.272f - pose.browRaise * 0.022f)
        val inner = pose.browAngle * s * 0.014f
        val path = Path().apply {
            // "inner" end faces the nose: right end for the left brow, left end for the right brow
            val leftY = by + if (side > 0) 0f else -inner
            val rightY = by + if (side > 0) -inner else 0f
            moveTo(bx - ew, leftY); quadraticTo(bx, by - s * 0.018f, bx + ew, rightY)
        }
        drawPath(path, Ink, style = stroke)
    }
    // Eyes
    listOf(0.40f, 0.60f).forEach { x ->
        val ex = x * s
        when {
            pose.eyeHappy > 0.5f -> drawPath(Path().apply {
                moveTo(ex - ew, eyeY + s * 0.012f); quadraticTo(ex, eyeY - s * 0.028f, ex + ew, eyeY + s * 0.012f)
            }, Ink, style = stroke)
            pose.eyeOpen < 0.18f -> drawPath(Path().apply {
                moveTo(ex - ew, eyeY); quadraticTo(ex, eyeY + s * 0.03f, ex + ew, eyeY)
            }, Ink, style = stroke)
            else -> {
                val h = s * 0.052f * pose.eyeOpen.coerceAtMost(1.35f)
                val w = s * 0.040f * (if (pose.eyeOpen > 1f) 1.1f else 1f)
                val ox = pose.lookX * s * 0.014f
                val oy = pose.lookY * s * 0.010f
                drawOval(Ink, Offset(ex - w / 2 + ox, eyeY - h / 2 + oy), Size(w, h))
                drawCircle(Color.White, s * 0.008f, Offset(ex + ox + w * 0.18f, eyeY + oy - h * 0.2f))
            }
        }
    }
    // Mouth
    val my = s * 0.455f
    val mw = s * 0.06f * pose.mouthWidth
    val smile = pose.mouthSmile * s * 0.035f
    if (pose.mouthOpen > 0.08f) {
        val depth = s * (0.012f + 0.05f * pose.mouthOpen)
        val path = Path().apply {
            moveTo(cx - mw, my); quadraticTo(cx, my + smile * 0.6f, cx + mw, my)
            quadraticTo(cx, my + smile + depth * 1.6f, cx - mw, my); close()
        }
        drawPath(path, Ink)
        drawOval(Color(0xFFD9767A), Offset(cx - mw * 0.45f, my + smile * 0.5f + depth * 0.35f), Size(mw * 0.9f, depth * 0.6f))
    } else {
        drawPath(Path().apply { moveTo(cx - mw, my - smile * 0.2f); quadraticTo(cx, my + smile, cx + mw, my - smile * 0.2f) }, Ink, style = stroke)
    }
    if (pose.fx == Fx.SWEAT && pose.fxAlpha > 0.01f) {
        val slide = ((time * 0.5f) % 1f) * s * 0.06f
        drawDrop(Offset(s * 0.70f, s * 0.22f + slide), s * 0.035f, Water.copy(alpha = pose.fxAlpha))
    }
}

private fun DrawScope.drawArm(hx: Float, hy: Float, shoulderX: Float, outward: Float, prop: Prop, propAlpha: Float, s: Float, time: Float) {
    val sy = RigGeometry.SHOULDER_Y
    val hand = RigGeometry.reachableHand(shoulderX, sy, hx, hy)
    val elbow = RigGeometry.elbow(shoulderX, sy, hand.x, hand.y, outward)
    val sh = Offset(shoulderX * s, sy * s)
    val el = Offset(elbow.x * s, elbow.y * s)
    val hd = Offset(hand.x * s, hand.y * s)
    listOf(RobeShade to 0.092f, Robe to 0.076f).forEach { (c, w) ->
        drawLine(c, sh, el, strokeWidth = s * w, cap = StrokeCap.Round)
        drawLine(c, el, hd, strokeWidth = s * w, cap = StrokeCap.Round)
    }
    drawCircle(Skin, s * 0.04f, hd)
    drawCircle(SkinShade, s * 0.04f, hd, style = Stroke(s * 0.006f))
    if (prop != Prop.NONE && propAlpha > 0.01f) drawProp(prop, hd, s, propAlpha.coerceIn(0f, 1f), time)
}

private fun DrawScope.drawProp(prop: Prop, at: Offset, s: Float, a: Float, time: Float) {
    when (prop) {
        Prop.GLASS -> {
            val tl = at + Offset(-s * 0.035f, -s * 0.10f)
            drawRoundRect(Color.White.copy(alpha = 0.55f * a), tl, Size(s * 0.07f, s * 0.10f), CornerRadius(s * 0.01f))
            drawRoundRect(Water.copy(alpha = 0.85f * a), tl + Offset(0f, s * 0.035f), Size(s * 0.07f, s * 0.065f), CornerRadius(s * 0.01f))
            drawRoundRect(Color(0xFF9CC9E8).copy(alpha = a), tl, Size(s * 0.07f, s * 0.10f), CornerRadius(s * 0.01f), style = Stroke(s * 0.006f))
        }
        Prop.CHAI -> {
            val tl = at + Offset(-s * 0.04f, -s * 0.075f)
            drawRoundRect(Color(0xFFFFF4E2).copy(alpha = a), tl, Size(s * 0.08f, s * 0.065f), CornerRadius(s * 0.015f))
            drawRoundRect(Color(0xFFB9783F).copy(alpha = a), tl + Offset(s * 0.008f, s * 0.006f), Size(s * 0.064f, s * 0.014f), CornerRadius(s * 0.006f))
            drawCircle(Color(0xFFFFF4E2).copy(alpha = a), s * 0.018f, tl + Offset(s * 0.085f, s * 0.03f), style = Stroke(s * 0.008f))
            for (i in 0..1) {
                val x = tl.x + s * (0.025f + i * 0.03f)
                val phase = time * 3f + i
                val steam = Path().apply {
                    moveTo(x, tl.y - s * 0.005f)
                    for (k in 1..3) quadraticTo(x + sin(phase + k) * s * 0.015f, tl.y - s * 0.02f * k, x, tl.y - s * 0.025f * k)
                }
                drawPath(steam, Color.White.copy(alpha = 0.7f * a), style = Stroke(s * 0.006f, cap = StrokeCap.Round))
            }
        }
        Prop.BOWL -> {
            val c = at + Offset(0f, -s * 0.035f)
            drawArc(Color(0xFF8C5A3C).copy(alpha = a), 0f, 180f, true, c - Offset(s * 0.06f, s * 0.04f), Size(s * 0.12f, s * 0.08f))
            for (i in 0..5) drawCircle(Color.White.copy(alpha = a), s * 0.009f, c + Offset((i - 2.5f) * s * 0.018f, -s * 0.004f * (i % 2)))
            drawCircle(Color(0xFFF0C24B).copy(alpha = a), s * 0.012f, c + Offset(s * 0.02f, -s * 0.012f))
        }
        Prop.WATCH -> {
            drawRoundRect(Color(0xFF5B4636).copy(alpha = a), at + Offset(-s * 0.045f, -s * 0.012f), Size(s * 0.09f, s * 0.024f), CornerRadius(s * 0.01f))
            drawCircle(Color.White.copy(alpha = a), s * 0.026f, at)
            drawCircle(Ink.copy(alpha = a), s * 0.026f, at, style = Stroke(s * 0.006f))
            val ang = time * 2f
            drawLine(Ink.copy(alpha = a), at, at + Offset(sin(ang) * s * 0.018f, -cos(ang) * s * 0.018f), s * 0.005f, StrokeCap.Round)
        }
        Prop.NOTEPAD -> {
            val tl = at + Offset(-s * 0.05f, -s * 0.11f)
            drawRoundRect(Color(0xFFFFFBEF).copy(alpha = a), tl, Size(s * 0.10f, s * 0.12f), CornerRadius(s * 0.008f))
            for (i in 1..4) drawLine(Color(0xFFB7C4D6).copy(alpha = a), tl + Offset(s * 0.012f, s * 0.024f * i), tl + Offset(s * 0.088f, s * 0.024f * i), s * 0.004f)
        }
        Prop.PEN -> drawLine(Color(0xFF3D6FA8).copy(alpha = a), at + Offset(-s * 0.01f, -s * 0.07f), at + Offset(s * 0.012f, s * 0.01f), s * 0.012f, StrokeCap.Round)
        Prop.STAR -> drawStar(at + Offset(0f, -s * 0.07f), s * 0.05f, Gold.copy(alpha = a), time)
        Prop.LOTUS -> {
            val c = at + Offset(0f, -s * 0.05f)
            for (i in -2..2) rotate(i * 28f, pivot = c + Offset(0f, s * 0.03f)) {
                drawOval(Pink.copy(alpha = a), c - Offset(s * 0.016f, s * 0.03f), Size(s * 0.032f, s * 0.07f))
            }
            drawOval(Leaf.copy(alpha = a), c + Offset(-s * 0.06f, s * 0.03f), Size(s * 0.12f, s * 0.025f))
        }
        Prop.MALA -> for (i in 0 until 9) {
            val ang = (i / 9f) * 2 * PI.toFloat() + time * 1.5f
            drawCircle(Bead.copy(alpha = a), s * 0.012f, at + Offset(cos(ang) * s * 0.035f, sin(ang) * s * 0.035f + s * 0.03f))
        }
        Prop.NONE -> Unit
    }
}

private fun DrawScope.drawFx(fx: Fx, a: Float, s: Float, t: Float, pose: Pose) {
    val headTop = Offset(s * (0.5f + pose.headX), s * (0.06f + pose.headY + pose.bodyY))
    when (fx) {
        Fx.ZZZ -> for (i in 0..2) {
            val phase = ((t * 0.45f) + i / 3f) % 1f
            val size = s * (0.035f + 0.03f * phase)
            val o = Offset(s * (0.72f + 0.10f * phase), s * (0.26f - 0.22f * phase))
            val path = Path().apply {
                moveTo(o.x, o.y); lineTo(o.x + size, o.y); lineTo(o.x, o.y + size); lineTo(o.x + size, o.y + size)
            }
            drawPath(path, Ink.copy(alpha = a * (1f - phase)), style = Stroke(s * 0.01f, cap = StrokeCap.Round))
        }
        Fx.CONFETTI -> for (i in 0 until 22) {
            val seedX = ((i * 37) % 100) / 100f
            val speed = 0.25f + ((i * 13) % 10) / 30f
            val y = ((t * speed + ((i * 53) % 100) / 100f) % 1f)
            val x = seedX + sin(t * 2f + i) * 0.02f
            val colors = listOf(Robe, Gold, Pink, Leaf, Water)
            rotate(t * 180f + i * 40f, pivot = Offset(x * s, y * s)) {
                drawRect(colors[i % colors.size].copy(alpha = a), Offset(x * s, y * s), Size(s * 0.022f, s * 0.012f))
            }
        }
        Fx.SWEAT -> Unit // drawn with the head so it follows head movement
        Fx.BULB -> {
            val c = headTop + Offset(0f, -s * 0.005f)
            val glow = 0.5f + 0.5f * sin(t * 6f)
            drawCircle(Gold.copy(alpha = 0.25f * a * glow), s * 0.075f, c)
            drawCircle(Color(0xFFFFE07A).copy(alpha = a), s * 0.04f, c)
            drawRect(Color(0xFF9A9A9A).copy(alpha = a), c + Offset(-s * 0.018f, s * 0.035f), Size(s * 0.036f, s * 0.02f))
        }
        Fx.HEARTS -> for (i in 0..2) {
            val phase = ((t * 0.5f) + i / 3f) % 1f
            drawHeart(Offset(s * (0.22f + 0.28f * i), s * (0.35f - 0.3f * phase)), s * 0.03f, Pink.copy(alpha = a * (1f - phase)))
        }
        Fx.SPARKLES -> for (i in 0 until 6) {
            val ang = i / 6f * 2 * PI.toFloat()
            val tw = 0.5f + 0.5f * sin(t * 5f + i * 1.3f)
            val c = Offset(s * (0.5f + 0.40f * cos(ang)), s * (0.42f + 0.34f * sin(ang)))
            drawStar(c, s * 0.022f * (0.6f + tw * 0.6f), Gold.copy(alpha = a * tw), 0f)
        }
        Fx.QUESTION -> {
            val c = headTop + Offset(s * 0.22f, s * 0.02f)
            val path = Path().apply {
                moveTo(c.x - s * 0.025f, c.y); quadraticTo(c.x - s * 0.025f, c.y - s * 0.04f, c.x + s * 0.005f, c.y - s * 0.04f)
                quadraticTo(c.x + s * 0.035f, c.y - s * 0.04f, c.x + s * 0.03f, c.y - s * 0.01f)
                quadraticTo(c.x + s * 0.025f, c.y + s * 0.01f, c.x, c.y + s * 0.02f); lineTo(c.x, c.y + s * 0.035f)
            }
            drawPath(path, Ink.copy(alpha = a), style = Stroke(s * 0.012f, cap = StrokeCap.Round))
            drawCircle(Ink.copy(alpha = a), s * 0.008f, c + Offset(0f, s * 0.06f))
        }
        Fx.EXCLAIM -> {
            val c = headTop + Offset(s * 0.22f, 0f)
            val bounce = abs(sin(t * 8f)) * s * 0.01f
            drawRoundRect(Tilak.copy(alpha = a), c + Offset(-s * 0.012f, -s * 0.05f - bounce), Size(s * 0.024f, s * 0.07f), CornerRadius(s * 0.012f))
            drawCircle(Tilak.copy(alpha = a), s * 0.013f, c + Offset(0f, s * 0.045f - bounce))
        }
        Fx.MUSIC -> for (i in 0..2) {
            val phase = ((t * 0.4f) + i / 3f) % 1f
            val c = Offset(s * (0.18f + 0.32f * i), s * (0.38f - 0.28f * phase) + sin(t * 4f + i) * s * 0.01f)
            val col = Ink.copy(alpha = a * (1f - phase))
            drawOval(col, c, Size(s * 0.03f, s * 0.022f))
            drawLine(col, c + Offset(s * 0.028f, s * 0.01f), c + Offset(s * 0.028f, -s * 0.05f), s * 0.006f)
            drawLine(col, c + Offset(s * 0.028f, -s * 0.05f), c + Offset(s * 0.05f, -s * 0.04f), s * 0.006f)
        }
        Fx.DUST -> for (i in 0..3) {
            val phase = ((t * 1.2f) + i / 4f) % 1f
            val side = if (i % 2 == 0) -1f else 1f
            drawCircle(Color(0xFFBFAF9C).copy(alpha = a * (1f - phase) * 0.8f), s * (0.02f + 0.03f * phase),
                Offset(s * (0.5f + side * (0.30f + 0.1f * phase)), s * (0.97f - 0.05f * phase)))
        }
        Fx.STEAM, Fx.NONE -> Unit
    }
}

private fun DrawScope.drawStar(c: Offset, r: Float, color: Color, spin: Float) {
    val path = Path()
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) r else r * 0.45f
        val ang = -PI.toFloat() / 2 + i * PI.toFloat() / 5 + spin * 0.5f
        val pt = Offset(c.x + cos(ang) * rad, c.y + sin(ang) * rad)
        if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
    }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawHeart(c: Offset, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(c.x, c.y + r * 0.9f)
        cubicTo(c.x - r * 1.4f, c.y - r * 0.2f, c.x - r * 0.6f, c.y - r * 1.2f, c.x, c.y - r * 0.4f)
        cubicTo(c.x + r * 0.6f, c.y - r * 1.2f, c.x + r * 1.4f, c.y - r * 0.2f, c.x, c.y + r * 0.9f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawDrop(c: Offset, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + r * 0.8f, c.y + r * 0.2f, c.x, c.y + r * 0.6f)
        quadraticTo(c.x - r * 0.8f, c.y + r * 0.2f, c.x, c.y - r)
        close()
    }
    drawPath(path, color)
}
