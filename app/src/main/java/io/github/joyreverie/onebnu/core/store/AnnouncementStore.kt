package io.github.joyreverie.onebnu.core.store

import android.content.Context
import io.github.joyreverie.onebnu.BuildConfig

/** 启动公告的阅读状态；按 versionCode 记录，后续版本会再次展示。 */
object AnnouncementStore {
    private const val PREFS = "onebnu_announcement"
    private const val KEY_READ_VERSION = "read_version_code"

    fun shouldShow(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_READ_VERSION, 0) < BuildConfig.VERSION_CODE

    fun markRead(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_READ_VERSION, BuildConfig.VERSION_CODE)
            .apply()
    }
}
