package app.taskbaba.app
import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.taskbaba.app.platform.BridgeLocator
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Task Baba") {
        Text("Karma is watching. Native bridge: ${BridgeLocator.locate() ?: "not found"}")
    }
}
