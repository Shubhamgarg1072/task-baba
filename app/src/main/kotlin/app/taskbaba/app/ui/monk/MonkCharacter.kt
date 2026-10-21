package app.taskbaba.app.ui.monk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import app.taskbaba.app.ui.monk.rig.BabaAnimations
import app.taskbaba.app.ui.monk.rig.BabaDirector
import app.taskbaba.app.ui.monk.rig.BabaPerformer
import app.taskbaba.app.ui.monk.rig.FramePacer
import kotlinx.coroutines.delay
import app.taskbaba.app.ui.monk.rig.drawBaba
import app.taskbaba.app.ui.theme.Baba
import app.taskbaba.core.data.AppPaths
import app.taskbaba.core.monk.MonkLine
import app.taskbaba.core.monk.MonkMood
import java.nio.file.Files

/** Set TASKBABA_DEBUG_FPS=1 to log each animated Baba's real frame rate every 10 s (diagnostics only). */
private val DEBUG_FPS = System.getenv("TASKBABA_DEBUG_FPS") == "1"

/** Ask Baba to perform a specific clip (gallery previews). A new [nonce] replays the same clip. */
data class PlayRequest(val animationId: String, val nonce: Long = System.nanoTime())

/**
 * Animated Baba. Reacts to each new [line] with the matching clip, idles with blinks and occasional
 * variety, and redraws at most [fps] times per second during movement — and only ~5 times per second
 * while he is just breathing. Each frame repaints the whole window in Compose Desktop, so [paused]
 * (e.g. the window isn't focused) freezes him on his current pose until it's lifted. With reduced motion he holds the clip's
 * signature pose instead of moving. Only the draw phase is invalidated per frame (no recomposition).
 */
@Composable
fun AnimatedBaba(line: MonkLine?, size: Dp, fps: Int, modifier: Modifier = Modifier, request: PlayRequest? = null, paused: Boolean = false) {
    val reduce = Baba.reduceMotion
    val performer = remember { BabaPerformer() }
    var frameNanos by remember { mutableLongStateOf(0L) }
    var label by remember { mutableStateOf(performer.clip.label) }

    LaunchedEffect(line?.id) {
        if (line != null) {
            val id = BabaDirector.reactionFor(line.event, line.tone, line.id.hashCode())
            performer.play(id, withFrameNanos { it }, talk = true)
            label = performer.clip.label
        }
    }
    LaunchedEffect(request) {
        if (request != null) {
            performer.play(request.animationId, withFrameNanos { it }, talk = false)
            label = performer.clip.label
        }
    }
    LaunchedEffect(fps, reduce, paused) {
        if (reduce || paused) return@LaunchedEffect
        val pacer = FramePacer()
        var counted = 0
        var windowStart = 0L
        while (true) {
            val now = withFrameNanos { it }
            frameNanos = now
            if (DEBUG_FPS) {
                counted++
                if (windowStart == 0L) windowStart = now
                if (now - windowStart >= 10_000_000_000L) {
                    System.err.println("[baba ${size.value.toInt()}dp] ${"%.1f".format(counted * 1e9 / (now - windowStart))} fps (${performer.clip.id})")
                    counted = 0; windowStart = now
                }
            }
            if (performer.clip.label != label) label = performer.clip.label
            val target = if (performer.isCalm(now)) BabaPerformer.CALM_FPS else fps
            delay(pacer.delayAfterFrame(now, target) / 1_000_000L)
        }
    }

    // Own graphics layer: each frame re-records only Baba, not the screen around him.
    Box(modifier.size(size).semantics { contentDescription = "Task Baba: $label" }) {
        Canvas(Modifier.fillMaxSize().graphicsLayer().clipToBounds()) {
            if (reduce) {
                val clip = performer.clip
                drawBaba(clip.sample(clip.signatureMs.toLong()), clip.signatureMs / 1000f)
            } else {
                val now = frameNanos
                if (now != 0L) drawBaba(performer.poseAt(now), performer.elapsedSeconds(now))
                else drawBaba(BabaAnimations[BabaAnimations.IDLE].sample(0), 0f)
            }
        }
    }
}

/**
 * Still Baba for small places (sidebar). To replace the artwork, drop `calm.svg`, `happy.svg`,
 * `stern.svg`, `celebrate.svg` or `sleepy.svg` into `~/Library/Application Support/TaskBaba/character/`.
 */
@Composable
fun MonkCharacter(mood: MonkMood, size: Dp, modifier: Modifier = Modifier, @Suppress("UNUSED_PARAMETER") animate: Boolean = false) {
    val custom = rememberCustomCharacter(mood)
    Box(modifier.size(size).semantics { contentDescription = "Task Baba, looking ${mood.name.lowercase()}" }) {
        if (custom != null) Image(custom, contentDescription = null, modifier = Modifier.fillMaxSize())
        else Canvas(Modifier.fillMaxSize().clipToBounds()) { drawBaba(BabaDirector.poseFor(mood), 0f) }
    }
}

@Composable
private fun rememberCustomCharacter(mood: MonkMood): Painter? = remember(mood) {
    runCatching {
        val file = AppPaths.home.resolve("character").resolve("${mood.name.lowercase()}.svg")
        if (!Files.isRegularFile(file)) null else Files.newInputStream(file).use { loadSvgPainter(it, Density(1f)) }
    }.getOrNull()
}
