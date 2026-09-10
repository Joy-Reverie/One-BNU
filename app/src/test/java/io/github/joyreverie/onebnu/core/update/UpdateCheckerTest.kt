package io.github.joyreverie.onebnu.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `版本号逐段比较`() {
        assertTrue(UpdateChecker.isNewer("1.8.0", "1.7.0"))
        assertTrue(UpdateChecker.isNewer("v1.10.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.99.99"))
        assertFalse(UpdateChecker.isNewer("1.8.0", "1.8.0"))
        assertFalse(UpdateChecker.isNewer("1.8", "1.8.0"))
        assertFalse(UpdateChecker.isNewer("1.7.9", "1.8.0"))
        // 后缀不参与比较
        assertEquals(0, UpdateChecker.compare("1.8.0-beta.1", "1.8.0+42"))
        assertEquals("1.8.0", UpdateChecker.normalize(" v1.8.0 "))
    }

    @Test
    fun `解析 GitHub releases latest 响应`() {
        val json = """
            {
              "tag_name": "v1.8.0",
              "name": "One BNU v1.8.0",
              "html_url": "https://github.com/Joy-Reverie/One-BNU/releases/tag/v1.8.0",
              "published_at": "2026-09-10T08:00:00Z",
              "body": "## 本次更新\r\n- **检查更新**\r\n- 登录页换图标\r\n\r\n\r\n安装即可",
              "assets": [
                {"name": "One-BNU-1.8.0.apk.sha256", "browser_download_url": "https://x/a.sha256", "size": 100},
                {"name": "One-BNU-1.8.0.apk", "browser_download_url": "https://x/One-BNU-1.8.0.apk", "size": 5242880}
              ]
            }
        """.trimIndent()
        val r = UpdateChecker.parse(json)
        assertEquals("1.8.0", r.version)
        assertEquals("v1.8.0", r.tag)
        assertEquals("One BNU v1.8.0", r.title)
        assertEquals("2026-09-10", r.publishedAt)
        assertEquals("https://x/One-BNU-1.8.0.apk", r.apkUrl)
        assertEquals("One-BNU-1.8.0.apk", r.apkName)
        assertEquals(5242880L, r.apkSize)
        assertEquals("本次更新\n• 检查更新\n• 登录页换图标\n\n安装即可", r.plainNotes())
    }

    @Test
    fun `没有 apk 附件时直链为 null`() {
        val r = UpdateChecker.parse("""{"tag_name":"v2.0.0","assets":[]}""")
        assertNull(r.apkUrl)
        assertEquals("v2.0.0", r.title)
        assertEquals("", r.publishedAt)
    }
}
