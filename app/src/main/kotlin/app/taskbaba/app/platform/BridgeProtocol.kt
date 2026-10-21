package app.taskbaba.app.platform

import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.platform.NotificationResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A line received from `babakit serve`. */
sealed interface BridgeMessage {
    data class Reply(val rid: Long, val ok: Boolean, val status: String?, val error: String?) : BridgeMessage
    data class Ready(val bundled: Boolean, val bundleId: String?) : BridgeMessage
    data class Response(val response: NotificationResponse) : BridgeMessage
    data class Unknown(val raw: String) : BridgeMessage
}

/** Newline-delimited JSON protocol shared with native/babakit/Notifications.swift. */
object BridgeProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    fun authStatus(rid: Long): String = request("authStatus", rid)
    fun requestAuth(rid: Long): String = request("requestAuth", rid)
    fun remove(rid: Long, id: String): String = buildJsonObject { put("op", "remove"); put("rid", rid); put("id", id) }.toString()

    fun post(rid: Long, n: BabaNotification): String = buildJsonObject {
        put("op", "post")
        put("rid", rid)
        put("id", n.id)
        put("kind", n.kind.name)
        put("title", n.title)
        put("body", n.body)
        put("sound", n.sound)
        put("actions", buildJsonArray {
            n.actions.forEach { a ->
                add(buildJsonObject { put("id", a.id); put("title", a.title); put("destructive", a.destructive) })
            }
        })
        put("userInfo", JsonObject(n.userInfo.mapValues { JsonPrimitive(it.value) }))
    }.toString()

    private fun request(op: String, rid: Long) = buildJsonObject { put("op", op); put("rid", rid) }.toString()

    fun decode(line: String): BridgeMessage {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return BridgeMessage.Unknown(line)
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        return when (str("event")) {
            "ready" -> BridgeMessage.Ready(
                bundled = (obj["bundled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                bundleId = str("bundleId"),
            )
            "response" -> {
                val info = (obj["userInfo"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                BridgeMessage.Response(
                    NotificationResponse(
                        notificationId = str("id") ?: "",
                        actionId = str("action") ?: NotificationResponse.DEFAULT_ACTION,
                        userInfo = info,
                    ),
                )
            }
            else -> {
                val rid = (obj["rid"] as? JsonPrimitive)?.longOrNull ?: return BridgeMessage.Unknown(line)
                BridgeMessage.Reply(
                    rid = rid,
                    ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    status = str("status"),
                    error = str("error"),
                )
            }
        }
    }

    fun authFrom(status: String?): NotificationAuth = when (status) {
        "authorized" -> NotificationAuth.AUTHORIZED
        "denied" -> NotificationAuth.DENIED
        "notDetermined" -> NotificationAuth.NOT_DETERMINED
        else -> NotificationAuth.UNAVAILABLE
    }
}
