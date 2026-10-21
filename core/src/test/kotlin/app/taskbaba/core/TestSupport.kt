package app.taskbaba.core

import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.ClockProvider
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.platform.NotificationResponse
import app.taskbaba.core.platform.Notifier
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class MutableClock(var instant: Instant, var zone: ZoneId) : ClockProvider {
    override fun clock(): Clock = Clock.fixed(instant, zone)
    fun advance(d: Duration) { instant = instant.plus(d) }
    companion object {
        fun at(local: String, zone: String = "Asia/Kolkata"): MutableClock {
            val z = ZoneId.of(zone)
            return MutableClock(LocalDateTime.parse(local).atZone(z).toInstant(), z)
        }
    }
}

class FakeNotifier(var auth: NotificationAuth = NotificationAuth.AUTHORIZED, var failPosts: Boolean = false) : Notifier {
    val posted = mutableListOf<BabaNotification>()
    val removed = mutableListOf<String>()
    override val responses = MutableSharedFlow<NotificationResponse>(extraBufferCapacity = 16)
    override suspend fun authorization() = auth
    override suspend fun requestAuthorization() = auth
    override suspend fun post(notification: BabaNotification): Result<Unit> =
        if (failPosts || auth != NotificationAuth.AUTHORIZED) Result.failure(IllegalStateException("denied"))
        else Result.success(Unit).also { posted += notification }
    override suspend fun remove(id: String) { removed += id }
}
