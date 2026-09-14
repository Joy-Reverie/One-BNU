package io.github.joyreverie.onebnu.core.update

import io.github.joyreverie.onebnu.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    /**
     * 发版前就挡住版本号写错：本包的版本名必须是 `<学年><学期>.<序号>`，必须比最后一个数字版本新，
     * versionCode 必须仍是 build.gradle.kts 里那条推导公式的结果（手改过就对不上）。
     */
    @Test
    fun `本包版本名与 versionCode 符合学期命名规则`() {
        val name = BuildConfig.VERSION_NAME
        val m = Regex("""^(\d{2})(\d{2})s([12])\.(\d{1,3})$""").matchEntire(name)
        assertTrue("版本名「$name」不符合 <学年><学期>.<序号>，例：2627s1.01", m != null)
        val (from, to, semester, serial) = m!!.destructured
        assertEquals("学年要写连续两年，如 2627", (from.toInt() + 1) % 100, to.toInt())
        assertTrue("序号从 01 起数", serial.toInt() >= 1)
        assertEquals(
            "versionCode 应当由版本名推导：学年 * 100000 + 学期 * 10000 + 序号",
            "$from$to".toInt() * 100_000 + semester.toInt() * 10_000 + serial.toInt(),
            BuildConfig.VERSION_CODE,
        )
        // 数字版本时代的最后一个包是 1.9.37 / versionCode 57，升级方向不能反
        assertTrue(UpdateChecker.isNewer(name, "1.9.37"))
        assertTrue(BuildConfig.VERSION_CODE > 57)
    }

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

    /** 学年 → 学期 → 序号，春季学期排在同学年秋季之后；旧的数字版本一律更旧。 */
    @Test
    fun `学期版本号按学年学期序号比较`() {
        assertTrue(UpdateChecker.isNewer("2627s1.01", "1.9.38"))
        assertTrue(UpdateChecker.isNewer("2627s1.02", "2627s1.01"))
        assertTrue(UpdateChecker.isNewer("2627s1.10", "2627s1.09"))
        // 第二学期（春）晚于第一学期（秋），哪怕秋季已经发到第 57 版
        assertTrue(UpdateChecker.isNewer("2627s2.01", "2627s1.57"))
        assertFalse(UpdateChecker.isNewer("2627s1.57", "2627s2.01"))
        // 跨学年
        assertTrue(UpdateChecker.isNewer("2728s1.01", "2627s2.99"))
        assertFalse(UpdateChecker.isNewer("2627s2.99", "2728s1.01"))
        // 同一版本、带 v 前缀、序号补不补零都等价
        assertFalse(UpdateChecker.isNewer("v2627s1.01", "2627s1.1"))
        assertEquals(0, UpdateChecker.compare("2627s1.01", "2627s1.1"))
        assertEquals("2627s1.01", UpdateChecker.normalize(" v2627s1.01 "))
        // 序号数到三位也照常比较
        assertTrue(UpdateChecker.isNewer("2627s1.100", "2627s1.99"))
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
