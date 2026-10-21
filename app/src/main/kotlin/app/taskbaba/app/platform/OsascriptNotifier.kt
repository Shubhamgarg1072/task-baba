package app.taskbaba.app.platform

import app.taskbaba.core.platform.BabaNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Fallback banners for dev runs without the native helper. Text is passed to AppleScript as `argv`
 * items, never spliced into the script source, so quotes or backslashes can't inject AppleScript.
 * No action buttons are possible here.
 */
class OsascriptNotifier(private val runner: (List<String>) -> Int = ::run) {
    suspend fun post(n: BabaNotification): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val code = runner(command(n.title, n.body, n.sound))
            check(code == 0) { "osascript exited with $code" }
        }
    }

    companion object {
        private const val MAX_TITLE = 120
        private const val MAX_BODY = 600

        fun command(title: String, body: String, sound: Boolean): List<String> {
            val script = buildList {
                add("on run argv")
                add(
                    if (sound) "display notification (item 2 of argv) with title (item 1 of argv) sound name \"Glass\""
                    else "display notification (item 2 of argv) with title (item 1 of argv)",
                )
                add("end run")
            }
            return buildList {
                add("/usr/bin/osascript")
                script.forEach { add("-e"); add(it) }
                add(sanitize(title, MAX_TITLE))
                add(sanitize(body, MAX_BODY))
            }
        }

        /** Flattens newlines, strips control characters and truncates; argv passing makes quoting unnecessary. */
        fun sanitize(s: String, max: Int): String {
            val clean = s.replace(Regex("[\\p{Cntrl}&&[^\\n]]"), "").replace('\n', ' ').trim()
            return if (clean.length <= max) clean else clean.take(max - 1) + "…"
        }

        private fun run(cmd: List<String>): Int {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroy(); return -1 }
            return p.exitValue()
        }
    }
}
