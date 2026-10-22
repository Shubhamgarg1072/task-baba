package app.taskbaba.app.platform

import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.Notifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Passes everything through to [delegate] and announces each notification that was actually delivered. */
class ObservedNotifier(private val delegate: Notifier) : Notifier by delegate {
    private val _posted = MutableSharedFlow<BabaNotification>(extraBufferCapacity = 32)
    val posted: SharedFlow<BabaNotification> = _posted.asSharedFlow()

    override suspend fun post(notification: BabaNotification): Result<Unit> =
        delegate.post(notification).onSuccess { _posted.tryEmit(notification) }
}
