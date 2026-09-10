package io.github.joyreverie.onebnu.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 用户自己添加的日程，如「9 月 11 日 08:00–10:00 体检」。
 * 与课程并列出现在首页「今日课程」、课表网格、桌面小组件和上课提醒里；只保存在本机。
 *
 * [repeatDays] 非空时是重复日程：从 [date] 起，每逢这些星期几都发生，直到 [repeatUntil]（含，null 为不限）。
 */
data class PersonalEvent(
    val id: String,
    val title: String,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val location: String = "",
    val note: String = "",
    /** 重复的星期几，1=周一 … 7=周日；空集表示只在 [date] 这一天。 */
    val repeatDays: Set<Int> = emptySet(),
    /** 重复到哪一天为止（含）；null 表示不限。 */
    val repeatUntil: LocalDate? = null,
) {
    /** 如「08:00–10:00」。 */
    val timeLabel: String get() = "${start.format(HM)}–${end.format(HM)}"

    val repeats: Boolean get() = repeatDays.isNotEmpty()

    /** 这一天是否有这条日程。 */
    fun occursOn(day: LocalDate): Boolean {
        if (!repeats) return day == date
        if (day.isBefore(date)) return false
        if (repeatUntil != null && day.isAfter(repeatUntil)) return false
        return day.dayOfWeek.value in repeatDays
    }

    /** 重复规则的说明，如「不重复」「每天」「工作日」「每周一、三」「每周四 · 至 12月31日」。 */
    val repeatLabel: String
        get() {
            val base = when {
                !repeats -> return "不重复"
                repeatDays.size == 7 -> "每天"
                repeatDays == WEEKDAYS -> "工作日"
                repeatDays == WEEKEND -> "周末"
                else -> "每周" + repeatDays.sorted().joinToString("、") { DAY_NAMES[it - 1] }
            }
            val until = repeatUntil?.let { " · 至 ${it.monthValue}月${it.dayOfMonth}日" } ?: ""
            return base + until
        }

    companion object {
        val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")
        val EVERY_DAY: Set<Int> = (1..7).toSet()
        val WEEKDAYS: Set<Int> = (1..5).toSet()
        val WEEKEND: Set<Int> = setOf(6, 7)
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** 日程列表的 JSON 编解码；解不开的条目跳过，不让一条坏数据拖垮整个列表。 */
object PersonalEventJson {

    fun encode(events: List<PersonalEvent>): String = JSONArray().also { arr ->
        events.forEach { e ->
            val o = JSONObject()
                .put("id", e.id)
                .put("title", e.title)
                .put("date", e.date.toString())
                .put("start", e.start.format(PersonalEvent.HM))
                .put("end", e.end.format(PersonalEvent.HM))
                .put("location", e.location)
                .put("note", e.note)
            if (e.repeats) {
                o.put("repeat", JSONArray().also { days -> e.repeatDays.sorted().forEach { days.put(it) } })
                e.repeatUntil?.let { o.put("until", it.toString()) }
            }
            arr.put(o)
        }
    }.toString()

    fun decode(text: String): List<PersonalEvent> {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val repeat = o.optJSONArray("repeat")?.let { r ->
                    (0 until r.length()).map { r.getInt(it) }.filter { it in 1..7 }.toSet()
                } ?: emptySet()
                PersonalEvent(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    date = LocalDate.parse(o.getString("date")),
                    start = LocalTime.parse(o.getString("start")),
                    end = LocalTime.parse(o.getString("end")),
                    location = o.optString("location"),
                    note = o.optString("note"),
                    repeatDays = repeat,
                    repeatUntil = o.optString("until").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
                )
            }.getOrNull()
        }
    }
}
