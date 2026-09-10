package io.github.joyreverie.onebnu.core.store

import android.content.Context
import io.github.joyreverie.onebnu.data.model.GpaScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * 应用设置。用普通 SharedPreferences 即可 —— 这里不存任何凭据。
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("onebnu_settings", Context.MODE_PRIVATE)

    /** 学期第一周的周一。课表算「第几周」全靠它，默认值需要用户按校历确认。 */
    var termStart: LocalDate
        get() = prefs.getString(KEY_TERM_START, null)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        } ?: DEFAULT_TERM_START
        set(value) { prefs.edit().putString(KEY_TERM_START, value.toString()).apply() }

    val termStartConfigured: Boolean
        get() = prefs.contains(KEY_TERM_START)

    var gpaScale: GpaScale
        get() = runCatching { GpaScale.valueOf(prefs.getString(KEY_GPA_SCALE, null) ?: "") }
            .getOrDefault(GpaScale.OFFICIAL)
        set(value) { prefs.edit().putString(KEY_GPA_SCALE, value.name).apply() }

    /** 节次作息时间，"HH:mm-HH:mm" 共 12 节。学校统一作息，不提供自定义。 */
    val periodTimes: List<String> get() = PERIOD_TIMES

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
    )

    /** 深浅色模式。以流的形式暴露，主题在设置页改动后整个界面立即重绘，不重建 Activity。 */
    val themeMode: StateFlow<ThemeMode> get() = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    /** 是否在进入应用时要求生物识别 / 设备锁验证。 */
    var requireUnlock: Boolean
        get() = prefs.getBoolean(KEY_LOCK, false)
        set(value) { prefs.edit().putBoolean(KEY_LOCK, value).apply() }

    /** 课表网格的双指缩放倍数，跨启动记住。 */
    var scheduleZoom: Float
        get() = prefs.getFloat(KEY_SCHEDULE_ZOOM, 1f)
        set(value) { prefs.edit().putFloat(KEY_SCHEDULE_ZOOM, value).apply() }

    /** 上课提醒开关。 */
    var remindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMIND, false)
        set(value) { prefs.edit().putBoolean(KEY_REMIND, value).apply() }

    /** 上课前提前多少分钟提醒，1～120。 */
    var reminderLeadMinutes: Int
        get() = prefs.getInt(KEY_REMIND_LEAD, 10).coerceIn(1, 120)
        set(value) { prefs.edit().putInt(KEY_REMIND_LEAD, value.coerceIn(1, 120)).apply() }

    companion object {
        private const val KEY_TERM_START = "term_start"
        private const val KEY_GPA_SCALE = "gpa_scale"
        private const val KEY_LOCK = "require_unlock"
        private const val KEY_SCHEDULE_ZOOM = "schedule_zoom"
        private const val KEY_REMIND = "reminders_enabled"
        private const val KEY_REMIND_LEAD = "reminder_lead_minutes"
        private const val KEY_THEME = "theme_mode"

        /**
         * 2026-2027 学年秋季学期第一周周一，取自学校校历。
         */
        val DEFAULT_TERM_START: LocalDate = LocalDate.of(2026, 9, 7)

        /**
         * 学校统一作息，每节 45 分钟：
         * 上午 8:00 起，2-3 节之间 20 分钟大课间，其余 10 分钟；
         * 下午 13:30 起，同样的间隔安排；晚上 18:00 起，间隔一律 10 分钟。
         */
        val PERIOD_TIMES: List<String> = buildPeriods()

        private fun buildPeriods(): List<String> {
            // 每段的起始时刻，以及该段内「上一节结束到下一节开始」的间隔
            val blocks = listOf(
                Triple(8 * 60, 4, listOf(10, 20, 10)),        // 上午 1-4 节
                Triple(13 * 60 + 30, 4, listOf(10, 20, 10)),  // 下午 5-8 节
                Triple(18 * 60, 4, listOf(10, 10, 10)),       // 晚上 9-12 节
            )
            val out = mutableListOf<String>()
            for ((start, count, gaps) in blocks) {
                var t = start
                repeat(count) { i ->
                    out += "${hhmm(t)}-${hhmm(t + LESSON_MINUTES)}"
                    t += LESSON_MINUTES + gaps.getOrElse(i) { 0 }
                }
            }
            return out
        }

        private const val LESSON_MINUTES = 45

        private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    }
}
