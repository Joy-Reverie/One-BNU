package io.github.joyreverie.onebnu.core.store

import android.content.Context
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.ScheduleJson
import java.io.File

/**
 * 当前学期课表的本地缓存，给桌面小组件用。
 *
 * 小组件在自己的广播里渲染，进程里没有登录会话；应用每次成功拉到当前学期课表就写一份到这里，
 * 小组件直接读缓存，不必每次都联网。只缓存课表本身（课程、时间、地点、教师），不含任何凭据。
 * [onChanged] 在写入 / 清除后回调，用来通知小组件重绘。
 */
class ScheduleCache(
    context: Context,
    campus: Campus = Campus.BEIJING,
    private val onChanged: () -> Unit = {},
) {

    data class Cached(val schedule: Schedule, val savedAt: Long)

    private val file = File(context.filesDir, if (campus == Campus.BEIJING) FILE_NAME else "${FILE_NAME}_${campus.storageKey}")
    private val meta = context.getSharedPreferences(
        if (campus == Campus.BEIJING) "onebnu_schedule_cache" else "onebnu_schedule_cache_${campus.storageKey}",
        Context.MODE_PRIVATE,
    )

    /** 上次成功写入的时间（毫秒）；从未写入为 0。 */
    val savedAt: Long get() = meta.getLong(KEY_SAVED_AT, 0L)

    @Synchronized
    fun save(schedule: Schedule) {
        // 先写临时文件再改名，进程中途被杀也不会留下半截 JSON
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(ScheduleJson.encode(schedule))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        meta.edit().putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply()
        onChanged()
    }

    @Synchronized
    fun load(): Cached? {
        if (!file.exists()) return null
        val schedule = runCatching { ScheduleJson.decode(file.readText()) }.getOrNull() ?: return null
        return Cached(schedule, savedAt)
    }

    @Synchronized
    fun clear() {
        file.delete()
        meta.edit().clear().apply()
        onChanged()
    }

    private companion object {
        const val FILE_NAME = "widget_schedule.json"
        const val KEY_SAVED_AT = "saved_at"
    }
}
