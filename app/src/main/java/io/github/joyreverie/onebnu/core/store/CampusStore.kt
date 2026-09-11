package io.github.joyreverie.onebnu.core.store

import android.content.Context

/** 只保存当前登录入口的选择；账号、Cookie 和业务缓存不放在这里。 */
class CampusStore(context: Context) {
    private val prefs = context.getSharedPreferences("onebnu_campus", Context.MODE_PRIVATE)

    var selected: Campus
        get() = runCatching {
            Campus.valueOf(prefs.getString(KEY, Campus.BEIJING.name) ?: Campus.BEIJING.name)
        }.getOrDefault(Campus.BEIJING)
        set(value) { prefs.edit().putString(KEY, value.name).apply() }

    private companion object { const val KEY = "selected_campus" }
}
