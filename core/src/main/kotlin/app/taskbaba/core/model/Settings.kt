package app.taskbaba.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Personality(val label: String, val blurb: String) {
    FRIENDLY("Friendly Baba", "Encouraging and supportive."),
    SAVAGE("Savage Baba", "Witty, sarcastic, and playfully direct."),
    ADAPTIVE("Adaptive Baba", "Starts friendly; gets direct after repeated postponements."),
}

@Serializable
enum class MotionPreference(val label: String) { FOLLOW_SYSTEM("Follow system"), FULL("Full animations"), REDUCED("Reduced motion") }

@Serializable
enum class AiMode(val label: String, val blurb: String) {
    AUTOMATIC("Automatic", "Use your Colab model when it is reachable; otherwise Baba's built-in messages."),
    CLOUD_ONLY("Cloud only", "Only your Colab model. If it is down, built-in messages are used and nothing else is tried."),
    BUILT_IN("Built-in only", "Never send anything to a model. Baba uses his offline message library."),
}

/**
 * Core preferences. Feature modules (AI, Drive, integrations) keep their own settings objects under
 * separate keys in the same key-value store so they evolve independently.
 */
@Serializable
data class AppSettings(
    val onboarded: Boolean = false,
    val personality: Personality = Personality.ADAPTIVE,
    val sarcasmEnabled: Boolean = true,
    val monkMuted: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val taskNotifications: Boolean = true,
    val healthNotifications: Boolean = true,
    val maxNotificationsPerHour: Int = 6,
    val quietHoursEnabled: Boolean = true,
    val quietHours: TimeWindow = TimeWindow(ClockTime.of(22), ClockTime.of(7)),
    val workday: TimeWindow = TimeWindow(ClockTime.of(9), ClockTime.of(18)),
    val workdays: Set<Weekday> = Weekday.WORKDAYS,
    val soundEnabled: Boolean = true,
    val motion: MotionPreference = MotionPreference.FOLLOW_SYSTEM,
    /** Frame-rate cap for Baba's animations: 30, 40 or 60. */
    val animationFps: Int = 40,
    val floatingMonk: Boolean = false,
    val floatingMonkX: Int? = null,
    val floatingMonkY: Int? = null,
    val launchAtLogin: Boolean = false,
    val dailyGoalPercent: Int = 60,
    val endOfDaySummary: Boolean = true,
    val health: List<HealthReminderConfig> = HealthReminderConfig.defaults(),
    /** Epoch millis until which all Baba notifications are paused ("snooze everything"). */
    val pausedUntil: Long? = null,
) {
    companion object {
        val ANIMATION_FPS_OPTIONS = listOf(30, 40, 60)
    }

    fun healthConfig(type: HealthType): HealthReminderConfig =
        health.firstOrNull { it.type == type } ?: HealthReminderConfig.defaults().first { it.type == type }

    fun validate(): List<String> = buildList {
        if (maxNotificationsPerHour !in 1..30) add("Max notifications per hour must be 1–30.")
        if (dailyGoalPercent !in 10..100) add("Daily goal must be 10–100%.")
        if (animationFps !in ANIMATION_FPS_OPTIONS) add("Animation smoothness must be 30, 40 or 60 fps.")
        health.forEach { addAll(it.validate()) }
    }
}
