package app.taskbaba.app.ui.monk

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.data.AppPaths
import app.taskbaba.core.monk.MonkMood
import java.nio.file.Files

private val Skin = Color(0xFFE8B48C)
private val SkinShade = Color(0xFFD9A27A)
private val Robe = Color(0xFFE07A1F)
private val RobeShade = Color(0xFFC6631A)
private val Ink = Color(0xFF3A2A20)
private val Bead = Color(0xFF7A3E1D)
private val Blush = Color(0xFFE8A07E)
private val Tilak = Color(0xFFD9480F)

/**
 * Baba himself. Drawn from primitives (original artwork). To replace him, drop `calm.svg`, `happy.svg`,
 * `stern.svg`, `celebrate.svg` or `sleepy.svg` into `~/Library/Application Support/TaskBaba/character/`.
 */
@Composable
fun MonkCharacter(mood: MonkMood, size: Dp, modifier: Modifier = Modifier, animate: Boolean = true) {
    val custom = rememberCustomCharacter(mood)
    val breathe = if (animate && !Baba.reduceMotion) {
        val t = rememberInfiniteTransition(label = "breath")
        t.animateFloat(1f, 1.025f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s").value
    } else 1f
    val desc = "Task Baba, looking ${mood.name.lowercase()}"
    Box(modifier.size(size).semantics { contentDescription = desc }) {
        if (custom != null) {
            Image(custom, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Canvas(Modifier.fillMaxSize()) { scale(breathe, pivot = Offset(this.size.width / 2, this.size.height)) { drawMonk(mood) } }
        }
    }
}

@Composable
private fun rememberCustomCharacter(mood: MonkMood): Painter? = remember(mood) {
    runCatching {
        val file = AppPaths.home.resolve("character").resolve("${mood.name.lowercase()}.svg")
        if (!Files.isRegularFile(file)) null else Files.newInputStream(file).use { loadSvgPainter(it, Density(1f)) }
    }.getOrNull()
}

private fun DrawScope.drawMonk(mood: MonkMood) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    // Robe
    val robe = Path().apply {
        moveTo(w * 0.06f, h); cubicTo(w * 0.12f, h * 0.70f, w * 0.30f, h * 0.64f, cx, h * 0.64f)
        cubicTo(w * 0.70f, h * 0.64f, w * 0.88f, h * 0.70f, w * 0.94f, h); close()
    }
    drawPath(robe, Robe)
    val sash = Path().apply {
        moveTo(w * 0.33f, h * 0.67f); lineTo(w * 0.45f, h * 0.67f); lineTo(w * 0.74f, h); lineTo(w * 0.60f, h); close()
    }
    drawPath(sash, RobeShade)
    // Neck
    drawRect(SkinShade, Offset(cx - w * 0.06f, h * 0.56f), Size(w * 0.12f, h * 0.1f))
    // Mala
    for (i in 0..10) {
        val t = i / 10f
        val x = w * 0.31f + t * w * 0.38f
        val y = h * 0.69f + (1 - (2 * t - 1) * (2 * t - 1)) * h * 0.07f
        drawCircle(Bead, radius = w * 0.022f, center = Offset(x, y))
    }
    // Ears + head
    drawOval(Skin, Offset(w * 0.25f, h * 0.30f), Size(w * 0.09f, h * 0.14f))
    drawOval(Skin, Offset(w * 0.66f, h * 0.30f), Size(w * 0.09f, h * 0.14f))
    drawOval(Skin, Offset(w * 0.29f, h * 0.10f), Size(w * 0.42f, h * 0.50f))
    drawOval(Color.White.copy(alpha = 0.18f), Offset(w * 0.37f, h * 0.13f), Size(w * 0.16f, h * 0.08f))
    // Tilak
    val tl = w * 0.006f
    listOf(0.205f, 0.225f, 0.245f).forEachIndexed { i, y ->
        val half = if (i == 1) w * 0.07f else w * 0.055f
        drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(cx - half, h * y - tl), Size(half * 2, tl * 2.4f), CornerRadius(tl))
    }
    drawCircle(Tilak, radius = w * 0.018f, center = Offset(cx, h * 0.225f))
    // Cheeks
    drawOval(Blush.copy(alpha = 0.7f), Offset(w * 0.33f, h * 0.38f), Size(w * 0.08f, h * 0.04f))
    drawOval(Blush.copy(alpha = 0.7f), Offset(w * 0.59f, h * 0.38f), Size(w * 0.08f, h * 0.04f))

    val stroke = Stroke(width = w * 0.022f, cap = StrokeCap.Round)
    val lx = w * 0.40f
    val rx = w * 0.60f
    val eyeY = h * 0.33f
    val ew = w * 0.055f
    // Brows
    val browLift = when (mood) { MonkMood.STERN -> -0.01f; MonkMood.CELEBRATE -> 0.02f; else -> 0f }
    fun brow(x: Float, inward: Float) {
        val p = Path().apply {
            moveTo(x - ew, h * (0.29f - browLift) + inward)
            quadraticTo(x, h * (0.27f - browLift), x + ew, h * (0.29f - browLift) - inward)
        }
        drawPath(p, Ink, style = stroke)
    }
    val tilt = if (mood == MonkMood.STERN) h * 0.012f else 0f
    brow(lx, -tilt); brow(rx, tilt)
    // Eyes
    when (mood) {
        MonkMood.CALM, MonkMood.SLEEPY -> listOf(lx, rx).forEach { x ->
            val p = Path().apply { moveTo(x - ew, eyeY); quadraticTo(x, eyeY + h * 0.03f, x + ew, eyeY) }
            drawPath(p, Ink, style = stroke)
        }
        MonkMood.HAPPY, MonkMood.CELEBRATE -> listOf(lx, rx).forEach { x ->
            val p = Path().apply { moveTo(x - ew, eyeY + h * 0.012f); quadraticTo(x, eyeY - h * 0.025f, x + ew, eyeY + h * 0.012f) }
            drawPath(p, Ink, style = stroke)
        }
        MonkMood.STERN -> listOf(lx, rx).forEach { x ->
            drawCircle(Ink, radius = w * 0.02f, center = Offset(x, eyeY + h * 0.005f))
            drawLine(Skin, Offset(x - ew, eyeY - h * 0.012f), Offset(x + ew, eyeY - h * 0.012f), strokeWidth = w * 0.02f)
        }
    }
    // Mouth
    val my = h * 0.45f
    when (mood) {
        MonkMood.CALM -> drawPath(Path().apply { moveTo(cx - w * 0.05f, my); quadraticTo(cx, my + h * 0.025f, cx + w * 0.05f, my) }, Ink, style = stroke)
        MonkMood.HAPPY -> drawPath(Path().apply { moveTo(cx - w * 0.065f, my - h * 0.005f); quadraticTo(cx, my + h * 0.045f, cx + w * 0.065f, my - h * 0.005f) }, Ink, style = stroke)
        MonkMood.CELEBRATE -> {
            val p = Path().apply { moveTo(cx - w * 0.07f, my - h * 0.01f); quadraticTo(cx, my + h * 0.07f, cx + w * 0.07f, my - h * 0.01f); close() }
            drawPath(p, Ink)
        }
        MonkMood.STERN -> drawLine(Ink, Offset(cx - w * 0.045f, my + h * 0.01f), Offset(cx + w * 0.045f, my + h * 0.005f), strokeWidth = w * 0.022f, cap = StrokeCap.Round)
        MonkMood.SLEEPY -> drawOval(Ink, Offset(cx - w * 0.018f, my - h * 0.005f), Size(w * 0.036f, h * 0.03f))
    }
    if (mood == MonkMood.SLEEPY) {
        drawLine(Ink.copy(alpha = 0.5f), Offset(w * 0.78f, h * 0.12f), Offset(w * 0.84f, h * 0.12f), strokeWidth = w * 0.012f)
        drawLine(Ink.copy(alpha = 0.5f), Offset(w * 0.84f, h * 0.12f), Offset(w * 0.78f, h * 0.18f), strokeWidth = w * 0.012f)
        drawLine(Ink.copy(alpha = 0.5f), Offset(w * 0.78f, h * 0.18f), Offset(w * 0.84f, h * 0.18f), strokeWidth = w * 0.012f)
    }
    if (mood == MonkMood.CELEBRATE) {
        listOf(Offset(w * 0.15f, h * 0.15f), Offset(w * 0.85f, h * 0.22f), Offset(w * 0.12f, h * 0.45f)).forEach {
            drawCircle(Robe.copy(alpha = 0.8f), radius = w * 0.018f, center = it)
        }
    }
}
