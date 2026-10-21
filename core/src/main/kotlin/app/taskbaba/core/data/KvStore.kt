package app.taskbaba.core.data

import app.taskbaba.db.TaskBabaDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

val BabaJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** Small typed key-value persistence on top of the `kv` table. Never use it for secrets. */
class KvStore(private val db: TaskBabaDatabase) {
    fun <T> load(key: String, serializer: KSerializer<T>, default: () -> T): T {
        val raw = db.kvQueries.getValue(key).executeAsOneOrNull() ?: return default()
        return runCatching { BabaJson.decodeFromString(serializer, raw) }.getOrElse { default() }
    }

    fun <T> save(key: String, serializer: KSerializer<T>, value: T) {
        db.kvQueries.putValue(key, BabaJson.encodeToString(serializer, value))
    }

    fun delete(key: String) = db.kvQueries.deleteValue(key)

    /** A persisted, observable value. Updates are written through immediately. */
    fun <T> state(key: String, serializer: KSerializer<T>, default: () -> T): PersistedState<T> =
        PersistedState(this, key, serializer, load(key, serializer, default))
}

class PersistedState<T> internal constructor(
    private val store: KvStore,
    private val key: String,
    private val serializer: KSerializer<T>,
    initial: T,
) {
    private val flow = MutableStateFlow(initial)
    val value: T get() = flow.value
    val state: StateFlow<T> = flow.asStateFlow()

    @Synchronized
    fun update(transform: (T) -> T): T {
        val next = transform(flow.value)
        store.save(key, serializer, next)
        flow.value = next
        return next
    }
}
