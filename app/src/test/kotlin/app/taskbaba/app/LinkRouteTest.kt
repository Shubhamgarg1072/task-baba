package app.taskbaba.app

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkRouteTest {
    @Test fun systemSettingsLinksDoNotGoToTheBrowser() {
        assertEquals(LinkRoute.SYSTEM_OPEN, linkRoute("x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=app.taskbaba.TaskBaba"))
        assertEquals(LinkRoute.SYSTEM_OPEN, linkRoute("mailto:someone@example.com"))
        assertEquals(LinkRoute.BROWSER, linkRoute("https://github.com/Shubhamgarg1072/task-baba"))
        assertEquals(LinkRoute.REFUSED, linkRoute("file:///etc/passwd"))
        assertEquals(LinkRoute.REFUSED, linkRoute("javascript:alert(1)"))
        assertEquals(LinkRoute.REFUSED, linkRoute("not a url"))
    }
}
