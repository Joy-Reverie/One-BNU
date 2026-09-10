package io.github.joyreverie.onebnu.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleJsonTest {

    @Test
    fun `课表 JSON 往返后逐字段相等`() {
        val schedule = Schedule(
            term = Term("2026", "0", "2026-2027学年秋季学期"),
            studentId = "200000000000",
            studentName = "同学",
            className = "2026 级 1 班",
            courses = listOf(
                Course(
                    code = "AIS21158302", name = "机器学习", credits = 2.5, hours = 40, classNo = "01",
                    teachers = listOf("张三", "李四"),
                    sessions = listOf(
                        ClassSession(setOf(1, 3, 5, 7), "1-7(单)", 2, 3, 4, "教七楼 201", capacity = 120),
                        ClassSession((9..16).toSet(), "9-16", 4, 9, 10, "京师学堂", capacity = null),
                    ),
                    studyType = "初修", majorType = "主修",
                ),
                Course("C2", "无学时的课", 1.0, null, "", emptyList(), emptyList()),
            ),
        )
        val json = ScheduleJson.encode(schedule)
        assertEquals(schedule, ScheduleJson.decode(json))
    }

    @Test
    fun `坏掉的缓存当作没有缓存`() {
        assertNull(ScheduleJson.decode("not json"))
        assertNull(ScheduleJson.decode("""{"term":{"xn":"2026"}}"""))
    }
}
