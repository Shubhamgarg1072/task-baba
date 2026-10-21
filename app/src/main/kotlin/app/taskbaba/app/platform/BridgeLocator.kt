package app.taskbaba.app.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Finds the `babakit` helper. In a packaged app it sits next to the launcher in `Task Baba.app/Contents/MacOS`.
 * `TASKBABA_BRIDGE` overrides the location (useful for dev runs). Returns null when no helper exists,
 * e.g. under `./gradlew run`, so callers degrade gracefully.
 */
object BridgeLocator {
    const val HELPER_NAME = "babakit"

    fun locate(
        env: Map<String, String> = System.getenv(),
        appPath: String? = System.getProperty("jpackage.app-path"),
        command: String? = ProcessHandle.current().info().command().orElse(null),
        isExecutable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
    ): Path? {
        env["TASKBABA_BRIDGE"]?.takeIf { it.isNotBlank() }?.let { p ->
            return Paths.get(p).takeIf(isExecutable)
        }
        return candidates(appPath, command).firstOrNull(isExecutable)
    }

    internal fun candidates(appPath: String?, command: String?): List<Path> = buildList {
        appPath?.let { Paths.get(it).parent?.resolve(HELPER_NAME)?.let(::add) }
        listOfNotNull(appPath, command).forEach { raw ->
            bundleContents(Paths.get(raw))?.let { add(it.resolve("MacOS").resolve(HELPER_NAME)) }
        }
    }.distinct()

    /** Walks up from any path inside a bundle to its `X.app/Contents` directory. */
    internal fun bundleContents(path: Path): Path? {
        var p: Path? = path.toAbsolutePath()
        while (p != null) {
            if (p.fileName?.toString() == "Contents" && p.parent?.fileName?.toString()?.endsWith(".app") == true) return p
            p = p.parent
        }
        return null
    }
}
