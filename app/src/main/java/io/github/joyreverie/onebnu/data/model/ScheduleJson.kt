package io.github.joyreverie.onebnu.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 课表的 JSON 编解码，供桌面小组件的本地缓存使用。
 * 字段名一旦发布就不要改；解不开的旧缓存按「没有缓存」处理，不做迁移。
 */
object ScheduleJson {

    fun encode(s: Schedule): String = JSONObject().apply {
        put("term", JSONObject().put("xn", s.term.xn).put("xq", s.term.xq).put("name", s.term.name))
        put("studentId", s.studentId)
        put("studentName", s.studentName)
        put("className", s.className)
        put("courses", JSONArray().also { arr -> s.courses.forEach { arr.put(course(it)) } })
    }.toString()

    fun decode(text: String): Schedule? = runCatching {
        val o = JSONObject(text)
        val t = o.getJSONObject("term")
        Schedule(
            term = Term(t.getString("xn"), t.getString("xq"), t.optString("name")),
            studentId = o.optString("studentId"),
            studentName = o.optString("studentName"),
            className = o.optString("className"),
            courses = o.getJSONArray("courses").objects { course(it) },
        )
    }.getOrNull()

    private fun course(c: Course): JSONObject = JSONObject().apply {
        put("code", c.code)
        put("name", c.name)
        put("credits", c.credits)
        c.hours?.let { put("hours", it) }
        put("classNo", c.classNo)
        put("teachers", JSONArray(c.teachers))
        put("studyType", c.studyType)
        put("majorType", c.majorType)
        put("sessions", JSONArray().also { arr -> c.sessions.forEach { arr.put(session(it)) } })
    }

    private fun course(o: JSONObject): Course = Course(
        code = o.optString("code"),
        name = o.getString("name"),
        credits = o.optDouble("credits", 0.0),
        hours = o.intOrNull("hours"),
        classNo = o.optString("classNo"),
        teachers = o.optJSONArray("teachers")?.strings() ?: emptyList(),
        sessions = o.optJSONArray("sessions")?.objects { session(it) } ?: emptyList(),
        studyType = o.optString("studyType"),
        majorType = o.optString("majorType"),
    )

    private fun session(s: ClassSession): JSONObject = JSONObject().apply {
        put("weeks", JSONArray(s.weeks.sorted()))
        put("weeksLabel", s.weeksLabel)
        put("dayOfWeek", s.dayOfWeek)
        put("startPeriod", s.startPeriod)
        put("endPeriod", s.endPeriod)
        put("location", s.location)
        s.capacity?.let { put("capacity", it) }
    }

    private fun session(o: JSONObject): ClassSession = ClassSession(
        weeks = o.getJSONArray("weeks").ints().toSet(),
        weeksLabel = o.optString("weeksLabel"),
        dayOfWeek = o.getInt("dayOfWeek"),
        startPeriod = o.getInt("startPeriod"),
        endPeriod = o.getInt("endPeriod"),
        location = o.optString("location"),
        capacity = o.intOrNull("capacity"),
    )

    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONArray.ints(): List<Int> = (0 until length()).map { getInt(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    private inline fun <T> JSONArray.objects(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }
}
