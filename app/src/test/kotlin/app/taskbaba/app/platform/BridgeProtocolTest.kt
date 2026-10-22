package app.taskbaba.app.platform

import app.taskbaba.core.platform.BabaNotification
import app.taskbaba.core.platform.LoginItemStatus
import app.taskbaba.core.platform.NotificationAction
import app.taskbaba.core.platform.NotificationAuth
import app.taskbaba.core.platform.NotificationKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeProtocolTest {
    @Test
    fun `post encodes all fields with special characters intact`() {
        val n = BabaNotification(
            id = "health.water",
            kind = NotificationKind.HEALTH,
            title = "Baba says \"drink\"",
            body = "Line1\nLine2 \\ ✓ 💧",
            actions = listOf(NotificationAction("done", "Done"), NotificationAction("skip", "Skip", destructive = true)),
            sound = false,
            userInfo = mapOf("type" to "WATER"),
        )
        val obj = Json.parseToJsonElement(BridgeProtocol.post(7, n)).jsonObject
        assertEquals("post", obj["op"]!!.jsonPrimitive.content)
        assertEquals(7, obj["rid"]!!.jsonPrimitive.long)
        assertEquals("HEALTH", obj["kind"]!!.jsonPrimitive.content)
        assertEquals(n.title, obj["title"]!!.jsonPrimitive.content)
        assertEquals(n.body, obj["body"]!!.jsonPrimitive.content)
        assertEquals(false, obj["sound"]!!.jsonPrimitive.boolean)
        val actions = obj["actions"]!!.jsonArray
        assertEquals(2, actions.size)
        assertEquals(true, actions[1].jsonObject["destructive"]!!.jsonPrimitive.boolean)
        assertEquals("WATER", obj["userInfo"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue('\n' !in BridgeProtocol.post(7, n), "must be a single protocol line")
    }

    @Test
    fun `decodes replies, ready and responses`() {
        val reply = BridgeProtocol.decode("""{"ok":true,"rid":3,"status":"authorized"}""")
        assertEquals(BridgeMessage.Reply(3, true, "authorized", null), reply)

        val err = BridgeProtocol.decode("""{"error":"notAllowed","ok":false,"rid":4}""")
        assertEquals("notAllowed", (err as BridgeMessage.Reply).error)

        val ready = BridgeProtocol.decode("""{"bundled":false,"bundleId":null,"event":"ready"}""")
        assertEquals(BridgeMessage.Ready(false, null), ready)

        val resp = BridgeProtocol.decode("""{"action":"done","event":"response","id":"task.1","userInfo":{"taskId":"1"}}""")
        assertIs<BridgeMessage.Response>(resp)
        assertEquals("done", resp.response.actionId)
        assertEquals("task.1", resp.response.notificationId)
        assertEquals(mapOf("taskId" to "1"), resp.response.userInfo)
    }

    @Test
    fun `garbage lines are unknown, not crashes`() {
        assertIs<BridgeMessage.Unknown>(BridgeProtocol.decode("not json"))
        assertIs<BridgeMessage.Unknown>(BridgeProtocol.decode("""{"ok":true}"""))
        assertIs<BridgeMessage.Unknown>(BridgeProtocol.decode("[1,2]"))
    }

    @Test
    fun `auth status mapping`() {
        assertEquals(NotificationAuth.AUTHORIZED, BridgeProtocol.authFrom("authorized"))
        assertEquals(NotificationAuth.DENIED, BridgeProtocol.authFrom("denied"))
        assertEquals(NotificationAuth.NOT_DETERMINED, BridgeProtocol.authFrom("notDetermined"))
        assertEquals(NotificationAuth.UNAVAILABLE, BridgeProtocol.authFrom("unavailable"))
        assertEquals(NotificationAuth.UNAVAILABLE, BridgeProtocol.authFrom(null))
    }

    @Test
    fun `login status parsing`() {
        assertEquals(LoginItemStatus.ENABLED, MacLoginItemController.parse("enabled\n", 0))
        assertEquals(LoginItemStatus.DISABLED, MacLoginItemController.parse("disabled", 0))
        assertEquals(LoginItemStatus.REQUIRES_APPROVAL, MacLoginItemController.parse("requiresApproval", 0))
        // Bundled but never registered: just off.
        assertEquals(LoginItemStatus.DISABLED, MacLoginItemController.parse("notFound", 0))
        // Helper outside an app bundle.
        assertEquals(LoginItemStatus.UNAVAILABLE, MacLoginItemController.parse("notFound\n", 2))
        assertEquals(LoginItemStatus.UNAVAILABLE, MacLoginItemController.parse(null, 1))
    }
}

class OsascriptNotifierTest {
    @Test
    fun `text is passed as argv, never inside the script`() {
        val evil = "\" & (do shell script \"rm -rf ~\") & \""
        val cmd = OsascriptNotifier.command(evil, "body \\ \"x\"", sound = true)
        val scriptParts = cmd.withIndex().filter { (i, _) -> i > 0 && cmd[i - 1] == "-e" }.map { it.value }
        assertTrue(scriptParts.none { "rm -rf" in it || "body" in it }, "user text must not appear in AppleScript source")
        assertEquals(evil, cmd[cmd.size - 2])
        assertEquals("body \\ \"x\"", cmd.last())
        assertEquals("/usr/bin/osascript", cmd.first())
    }

    @Test
    fun `sanitize strips control chars, flattens newlines and truncates`() {
        assertEquals("a b", OsascriptNotifier.sanitize("a\u0007\nb", 50))
        val long = "x".repeat(200)
        val out = OsascriptNotifier.sanitize(long, 10)
        assertEquals(10, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `fallback reports runner failure`() = runTest {
        val notifier = OsascriptNotifier(runner = { 1 })
        val r = notifier.post(BabaNotification("t", NotificationKind.TEST, "T", "B"))
        assertTrue(r.isFailure)
        assertTrue(OsascriptNotifier(runner = { 0 }).post(BabaNotification("t", NotificationKind.TEST, "T", "B")).isSuccess)
    }
}

class BridgeLocatorTest {
    @Test
    fun `finds bundle contents from launcher or runtime paths`() {
        val launcher = "/Applications/Task Baba.app/Contents/MacOS/Task Baba"
        assertEquals(Paths.get("/Applications/Task Baba.app/Contents"), BridgeLocator.bundleContents(Paths.get(launcher)))
        val java = "/Applications/Task Baba.app/Contents/runtime/Contents/Home/bin/java"
        // The innermost Contents belongs to the runtime, not an .app, so the walk continues outward.
        assertEquals(Paths.get("/Applications/Task Baba.app/Contents"), BridgeLocator.bundleContents(Paths.get(java)))
        assertNull(BridgeLocator.bundleContents(Paths.get("/usr/bin/java")))
    }

    @Test
    fun `env override wins and missing helper yields null`() {
        val exists = setOf(Paths.get("/opt/dev/babakit"), Paths.get("/Applications/Task Baba.app/Contents/MacOS/babakit"))
        val found = BridgeLocator.locate(
            env = mapOf("TASKBABA_BRIDGE" to "/opt/dev/babakit"),
            appPath = "/Applications/Task Baba.app/Contents/MacOS/Task Baba",
            command = null,
            isExecutable = { it in exists },
        )
        assertEquals(Paths.get("/opt/dev/babakit"), found)

        val bundled = BridgeLocator.locate(emptyMap(), "/Applications/Task Baba.app/Contents/MacOS/Task Baba", null) { it in exists }
        assertEquals(Paths.get("/Applications/Task Baba.app/Contents/MacOS/babakit"), bundled)

        assertNull(BridgeLocator.locate(emptyMap(), null, "/usr/bin/java") { it in exists })
    }

    @Test
    fun `without helper everything degrades`() = runTest {
        val bridge = MacBridge(this, helper = null)
        assertEquals(NotificationAuth.UNAVAILABLE, bridge.notifier.authorization())
        assertEquals(LoginItemStatus.UNAVAILABLE, bridge.loginItem.status())
        assertTrue(bridge.loginItem.setEnabled(true).isFailure)
        assertEquals(false, bridge.secrets.isPersistent)
        bridge.secrets.set("k", "v")
        assertEquals("v", bridge.secrets.get("k"))
    }
}

class NotAllowedDetectionTest {
    @kotlin.test.Test fun recognisesBothFormsOfTheRefusal() {
        kotlin.test.assertTrue(BridgeProtocol.isNotAllowed("notAllowed"))
        kotlin.test.assertTrue(BridgeProtocol.isNotAllowed("Notifications are not allowed for this application"))
        kotlin.test.assertTrue(BridgeProtocol.isNotAllowed("The operation couldn’t be completed. (UNErrorDomain error 1.)"))
        kotlin.test.assertTrue(!BridgeProtocol.isNotAllowed(null) && !BridgeProtocol.isNotAllowed("timeout"))
    }
}
