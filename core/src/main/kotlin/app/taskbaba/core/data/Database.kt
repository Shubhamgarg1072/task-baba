package app.taskbaba.core.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.taskbaba.db.TaskBabaDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

object AppPaths {
    /** ~/Library/Application Support/TaskBaba, overridable with TASKBABA_HOME for tests and dev runs. */
    val home: Path by lazy {
        val override = System.getenv("TASKBABA_HOME")
        val dir = if (!override.isNullOrBlank()) Paths.get(override)
        else Paths.get(System.getProperty("user.home"), "Library", "Application Support", "TaskBaba")
        Files.createDirectories(dir)
        dir
    }
    val database: Path get() = home.resolve("taskbaba.db")
}

object DatabaseFactory {
    /** Current schema version: 1 + the number of `migrations/N.sqm` files. */
    val SCHEMA_VERSION: Long get() = TaskBabaDatabase.Schema.version

    fun file(path: Path = AppPaths.database): TaskBabaDatabase =
        open(JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}", Properties().apply { put("foreign_keys", "true") }))

    fun inMemory(): TaskBabaDatabase = open(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

    private fun open(driver: SqlDriver): TaskBabaDatabase {
        val current = driver.executeQuery(null, "PRAGMA user_version", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L)
        }, 0).value
        if (current == 0L) {
            TaskBabaDatabase.Schema.create(driver)
        } else if (current < SCHEMA_VERSION) {
            TaskBabaDatabase.Schema.migrate(driver, current, SCHEMA_VERSION)
        }
        driver.execute(null, "PRAGMA user_version = $SCHEMA_VERSION", 0)
        driver.execute(null, "PRAGMA journal_mode = WAL", 0)
        return TaskBabaDatabase(driver)
    }
}
