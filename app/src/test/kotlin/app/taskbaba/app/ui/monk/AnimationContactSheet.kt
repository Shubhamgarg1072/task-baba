package app.taskbaba.app.ui.monk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import app.taskbaba.app.ui.monk.rig.BabaAnimation
import app.taskbaba.app.ui.monk.rig.BabaAnimations
import app.taskbaba.app.ui.monk.rig.drawBaba
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test

/** Renders every clip's key frame, plus motion strips, to app/build/screenshots/ for visual review. */
class AnimationContactSheet {
    private fun save(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val out = Paths.get("build", "screenshots").also { Files.createDirectories(it) }
        ImageComposeScene(w, h, Density(1f)) { content() }.use { scene ->
            val img = scene.render()
            Files.write(out.resolve("$name.png"), img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Composable
    private fun Cell(a: BabaAnimation, ms: Int, caption: String) {
        Column(Modifier.width(170.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(150.dp).clipToBounds()) { drawBaba(a.sample(ms.toLong()), ms / 1000f) }
            Text(caption, fontSize = 11.sp, color = Color(0xFF2A2724), maxLines = 1)
        }
    }

    @Test fun renderSheets() {
        val all = BabaAnimations.all
        val rows = all.chunked(8)
        save("anim-keyframes", 8 * 170 + 20, rows.size * 180 + 20) {
            Column(Modifier.fillMaxSize().background(Color(0xFFFBF6EE)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row -> Row { row.forEach { Cell(it, it.signatureMs, it.label) } } }
            }
        }
        val strips = listOf("drink_water", "facepalm", "dance", "stretch_up", "eat", "wave_hello", "walk_in_place", "celebrate")
        save("anim-strips", 7 * 170 + 20, strips.size * 180 + 20) {
            Column(Modifier.fillMaxSize().background(Color(0xFFFBF6EE)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                strips.forEach { id ->
                    val a = BabaAnimations[id]
                    Row { (0..6).forEach { i -> val ms = a.durationMs * i / 6; Cell(a, ms, "${a.id} ${ms}ms") } }
                }
            }
        }
    }
}
