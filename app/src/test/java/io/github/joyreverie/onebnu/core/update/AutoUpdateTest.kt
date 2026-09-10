package io.github.joyreverie.onebnu.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutoUpdateTest {

    private val interval = AutoUpdate.MIN_INTERVAL_MS

    @Test
    fun `到期判定：开关、联网、进程内只查一次、最小间隔、时钟回拨`() {
        assertTrue(AutoUpdate.isDue(enabled = true, online = true, alreadyChecked = false, now = 1_000_000L, lastCheck = 0L))
        assertFalse(AutoUpdate.isDue(enabled = false, online = true, alreadyChecked = false, now = 1_000_000L, lastCheck = 0L))
        assertFalse(AutoUpdate.isDue(enabled = true, online = false, alreadyChecked = false, now = 1_000_000L, lastCheck = 0L))
        assertFalse(AutoUpdate.isDue(enabled = true, online = true, alreadyChecked = true, now = 1_000_000L, lastCheck = 0L))
        assertFalse(AutoUpdate.isDue(enabled = true, online = true, alreadyChecked = false, now = interval + 100, lastCheck = 200))
        assertTrue(AutoUpdate.isDue(enabled = true, online = true, alreadyChecked = false, now = interval + 200, lastCheck = 200))
        assertTrue(AutoUpdate.isDue(enabled = true, online = true, alreadyChecked = false, now = 100, lastCheck = 5_000))
    }

    @Test
    fun `点过以后再说的版本、正在下载或已下载的版本不再提示`() {
        val r = ReleaseInfo("1.9.0", "v1.9.0", "One BNU v1.9.0", "", "", "", null, null, 0)
        assertFalse(AutoUpdate.shouldPrompt(r, dismissedVersion = "1.9.0", download = UpdateInstaller.DownloadState.None))
        assertTrue(AutoUpdate.shouldPrompt(r, dismissedVersion = "1.8.1", download = UpdateInstaller.DownloadState.None))
        assertTrue(AutoUpdate.shouldPrompt(r, dismissedVersion = null, download = UpdateInstaller.DownloadState.None))
        assertFalse(AutoUpdate.shouldPrompt(r, null, UpdateInstaller.DownloadState.Running("1.9.0", 0.5f)))
        assertTrue(AutoUpdate.shouldPrompt(r, null, UpdateInstaller.DownloadState.Running("1.8.5", null)))
        assertFalse(AutoUpdate.shouldPrompt(r, null, UpdateInstaller.DownloadState.Ready("1.9.0", File("x.apk"))))
    }
}
