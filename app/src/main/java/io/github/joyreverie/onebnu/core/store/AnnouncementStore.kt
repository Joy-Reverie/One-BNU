package io.github.joyreverie.onebnu.core.store

import android.content.Context

/**
 * 启动公告的阅读状态。
 *
 * 按**公告自己的 id**（发布日期串）记，不按 versionCode：一天发五个版本时，
 * 同一份公告不该被重读五遍，还每次都要走两步确认。真有新公告时换一个 id 即可。
 */
object AnnouncementStore {
    private const val PREFS = "onebnu_announcement"
    private const val KEY_READ_IDS = "read_announcement_ids"

    /** 1.9.33 及更早按 versionCode 记；迁移时把它当作「当时那份公告已读」。 */
    private const val KEY_LEGACY_READ_VERSION = "read_version_code"
    private const val LEGACY_ANNOUNCEMENT_ID = "2026-09-13"

    fun shouldShow(context: Context, id: String): Boolean = id !in readIds(context)

    fun markRead(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_READ_IDS, readIds(context) + id).apply()
    }

    private fun readIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_READ_IDS, null)
        if (stored != null) return stored
        // 老用户已经确认过 1.9.32/33 的那份公告，升级后不要再弹一次
        return if (prefs.getInt(KEY_LEGACY_READ_VERSION, 0) > 0) setOf(LEGACY_ANNOUNCEMENT_ID) else emptySet()
    }
}
