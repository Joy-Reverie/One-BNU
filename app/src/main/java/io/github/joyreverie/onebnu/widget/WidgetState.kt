package io.github.joyreverie.onebnu.widget

import android.content.Context

/** 小组件自己的少量状态：是否正在刷新、上次刷新的错误、上次尝试时间。与课表缓存分开存。 */
class WidgetState(context: Context) {

    private val prefs = context.getSharedPreferences("onebnu_widget", Context.MODE_PRIVATE)

    /**
     * 是否正在刷新。带超时：后台任务被系统推迟或进程中途被杀时，
     * 「刷新中…」不能一直挂在小组件上，超过 [REFRESH_TIMEOUT_MS] 自动视为已结束。
     */
    var refreshing: Boolean
        get() = prefs.getBoolean(KEY_REFRESHING, false) &&
            System.currentTimeMillis() - prefs.getLong(KEY_REFRESHING_SINCE, 0L) < REFRESH_TIMEOUT_MS
        set(value) = prefs.edit()
            .putBoolean(KEY_REFRESHING, value)
            .putLong(KEY_REFRESHING_SINCE, if (value) System.currentTimeMillis() else 0L)
            .apply()

    var lastError: String?
        get() = prefs.getString(KEY_ERROR, null)
        set(value) = prefs.edit().putString(KEY_ERROR, value).apply()

    /** 上次发起后台刷新的时间（毫秒），用来限制刷新频率。 */
    var lastAttempt: Long
        get() = prefs.getLong(KEY_ATTEMPT, 0L)
        set(value) = prefs.edit().putLong(KEY_ATTEMPT, value).apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_REFRESHING = "refreshing"
        const val KEY_REFRESHING_SINCE = "refreshing_since"
        const val KEY_ERROR = "last_error"
        const val KEY_ATTEMPT = "last_attempt"
        const val REFRESH_TIMEOUT_MS = 3 * 60 * 1000L
    }
}
