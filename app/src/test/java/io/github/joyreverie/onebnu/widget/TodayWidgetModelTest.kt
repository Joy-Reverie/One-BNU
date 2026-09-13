package io.github.joyreverie.onebnu.widget

import io.github.joyreverie.onebnu.core.store.Settings
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TodayWidgetModelTest {

    /** 2026-09-10 周四，官方校历第 1 周。 */
    private val today: LocalDate = LocalDate.of(2026, 9, 10)
    private val term = Term("2026", "0", "2026-2027学年秋季学期")

    private fun session(start: Int, end: Int, where: String, dow: Int = 4) =
        ClassSession((1..20).toSet(), "1-20", dow, start, end, where)

    private fun course(name: String, teacher: String, s: ClassSession) =
        Course(name, name, 2.0, 32, "01", listOf(teacher), listOf(s))

    /** 今天四节课：1-2、3-4、5-6、9-10 节。 */
    private val schedule = Schedule(
        term, "1", "同学", "班",
        listOf(
            course("高等数学", "张三", session(1, 2, "教七 201")),
            course("大学英语", "李四", session(3, 4, "教二 108")),
            course("近代史", "王五", session(5, 6, "京师学堂")),
            course("体育", "赵六", session(9, 10, "体育馆")),
            // 别的日子的课不该出现
            course("周五的课", "某某", session(1, 2, "教一", dow = 5)),
        ),
    )

    private fun input(
        schedule: Schedule? = this.schedule,
        heightDp: Int = 140,
        now: LocalTime = LocalTime.of(7, 0),
        hasCredentials: Boolean = true,
        refreshing: Boolean = false,
        lastError: String? = null,
        today: LocalDate = this.today,
        events: List<PersonalEvent> = emptyList(),
    ) = TodayWidgetModel.Input(schedule, events, hasCredentials, refreshing, lastError, today, now, Settings.PERIOD_TIMES, heightDp)

    private val lunchMeeting = PersonalEvent("e1", "导师组会", today, LocalTime.of(12, 0), LocalTime.of(13, 0), "生地楼 306")

    @Test
    fun `日程按时间插进课程之间，并计入表头`() {
        val m = TodayWidgetModel.build(input(heightDp = 500, events = listOf(lunchMeeting)))
        assertEquals(listOf("高等数学", "大学英语", "导师组会", "近代史", "体育"), m.rows.map { it.name })
        assertEquals("4 节课 · 1 日程", m.countLabel)
        val ev = m.rows[2]
        assertTrue(ev.isEvent)
        assertEquals("12:00", ev.start)
        assertEquals("日程 · 生地楼 306", ev.detail)
    }

    @Test
    fun `没登录但有日程时仍显示日程`() {
        val m = TodayWidgetModel.build(input(schedule = null, hasCredentials = false, events = listOf(lunchMeeting)))
        assertNull(m.message)
        assertEquals(listOf("导师组会"), m.rows.map { it.name })
        assertEquals("1 日程", m.countLabel)
    }

    @Test
    fun `小尺寸取第一节没结束的课并说明位置`() {
        val m = TodayWidgetModel.buildSmall(input(now = LocalTime.of(10, 30)))
        assertEquals("大学英语", m.focus?.name)
        assertEquals("进行中 · 还有 2 节", m.footer)
        assertEquals("今天还有 4 节", TodayWidgetModel.buildSmall(input(now = LocalTime.of(7, 0))).footer)
        val done = TodayWidgetModel.buildSmall(input(now = LocalTime.of(22, 0)))
        assertEquals("体育", done.focus?.name)
        assertEquals("今天的课已结束", done.footer)
        val none = TodayWidgetModel.buildSmall(input(today = LocalDate.of(2026, 9, 12)))
        assertNull(none.focus)
        assertEquals("今天没有课 ☕", none.message)
    }

    @Test
    fun `表头有日期、星期与周次`() {
        val m = TodayWidgetModel.build(input())
        assertEquals("9月10日", m.dateLabel)
        assertEquals("周四", m.weekdayLabel)
        assertEquals("第 1 周", m.weekLabel)
        assertEquals("4 节课", m.countLabel)
    }

    @Test
    fun `每节课带起止时间、教室、教师与节次`() {
        val m = TodayWidgetModel.build(input(heightDp = 400))
        assertEquals(4, m.rows.size)
        val first = m.rows[0]
        assertEquals("08:00", first.start)
        assertEquals("09:40", first.end)
        assertEquals("高等数学", first.name)
        assertEquals("教七 201 · 张三 · 第 1-2 节", first.detail)
        // 晚上 9-10 节 18:00 起
        assertEquals("18:00", m.rows[3].start)
        assertEquals("19:40", m.rows[3].end)
    }

    @Test
    fun `按当前时间标记已结束、进行中、未开始`() {
        val m = TodayWidgetModel.build(input(heightDp = 400, now = LocalTime.of(10, 30)))
        assertEquals(TodayWidgetModel.Status.FINISHED, m.rows[0].status)   // 08:00-09:40
        assertEquals(TodayWidgetModel.Status.ONGOING, m.rows[1].status)    // 10:00-11:40
        assertEquals(TodayWidgetModel.Status.UPCOMING, m.rows[2].status)
    }

    @Test
    fun `按高度算能放几行，先扣掉启动器内边距，全放得下时不给脚注留位`() {
        // 格子偏小的启动器上 2 格约 140dp（实际可画约 120dp）：只放得下 1 行
        assertEquals(1, TodayWidgetModel.capacity(140, total = 2))
        assertEquals(1, TodayWidgetModel.capacity(140, total = 4))
        // Pixel 启动器实测 2 格 239dp、3 格 359dp、4 格 478dp
        assertEquals(4, TodayWidgetModel.capacity(239, total = 4))
        assertEquals(3, TodayWidgetModel.capacity(239, total = 5))
        assertEquals(7, TodayWidgetModel.capacity(359, total = 7))
        assertEquals(10, TodayWidgetModel.capacity(478, total = 12))
    }

    @Test
    fun `最矮时放不下脚注就不显示脚注，不能压住课程`() {
        // 1 格高的 Pixel 启动器报 120dp、实际约 100dp：1 行课 + 脚注 = 110dp 放不下，脚注让位
        val m = TodayWidgetModel.build(input(heightDp = 120))
        assertEquals(1, m.rows.size)
        assertNull(m.footer)
        assertEquals("4 节课", m.countLabel)
        // 140dp 时 1 行 + 脚注放得下
        assertEquals("还有 3 节", TodayWidgetModel.build(input(heightDp = 140)).footer)
    }

    @Test
    fun `全放得下时没有脚注`() {
        val m = TodayWidgetModel.build(input(heightDp = 239))
        assertEquals(4, m.rows.size)
        assertNull(m.footer)
    }

    @Test
    fun `放不下时先挪走已结束的课，并在脚注说明`() {
        // 200dp 放 2 行（含脚注）；10:30 时第一节已结束
        val m = TodayWidgetModel.build(input(heightDp = 200, now = LocalTime.of(10, 30)))
        assertEquals(listOf("大学英语", "近代史"), m.rows.map { it.name })
        assertEquals("1 节已结束 · 还有 1 节", m.footer)
    }

    @Test
    fun `全部上完后仍显示最后几节`() {
        val m = TodayWidgetModel.build(input(heightDp = 200, now = LocalTime.of(22, 0)))
        assertEquals(listOf("近代史", "体育"), m.rows.map { it.name })
        assertEquals("2 节已结束", m.footer)
    }

    @Test
    fun `今天没有课`() {
        val m = TodayWidgetModel.build(input(today = LocalDate.of(2026, 9, 12)))  // 周六
        assertTrue(m.rows.isEmpty())
        assertEquals("今天没有课 ☕", m.message)
        assertEquals("9月12日", m.dateLabel)
        assertEquals("周六", m.weekdayLabel)
    }

    @Test
    fun `没有缓存时按登录状态给出提示`() {
        assertEquals("打开 One BNU 登录后显示今日课表", TodayWidgetModel.build(input(schedule = null, hasCredentials = false)).message)
        assertEquals("正在获取课表…", TodayWidgetModel.build(input(schedule = null, refreshing = true)).message)
        assertEquals("获取课表失败：网络异常", TodayWidgetModel.build(input(schedule = null, lastError = "网络异常")).message)
        // 没缓存也要有日期与周次
        assertEquals("第 1 周", TodayWidgetModel.build(input(schedule = null)).weekLabel)
    }

    @Test
    fun `缓存的学期与今天不一致时提示刷新`() {
        val m = TodayWidgetModel.build(input(today = LocalDate.of(2027, 3, 4)))  // 已进入春季学期
        assertEquals("学期已更换，点右上角刷新获取新课表", m.message)
    }

    @Test
    fun `开学前显示学期尚未开始`() {
        val m = TodayWidgetModel.build(input(today = LocalDate.of(2026, 9, 3)))
        assertEquals("开学前", m.weekLabel)
        assertEquals("学期尚未开始，9月7日开学", m.message)
    }

    @Test
    fun `刷新中表头显示刷新中`() {
        assertEquals("刷新中…", TodayWidgetModel.build(input(refreshing = true)).countLabel)
    }

    @Test
    fun `系统字体放大后每行更高，能放的行数相应变少`() {
        // widget_row.xml 用的是 minHeight，行会随字号长高；行数必须按同一倍数算，
        // 否则最后一行会被压在脚注下面或直接裁掉
        assertEquals(38, TodayWidgetModel.rowDp(1f))
        assertEquals(49, TodayWidgetModel.rowDp(1.3f))
        assertEquals(4, TodayWidgetModel.capacity(239, total = 4, fontScale = 1f))
        assertEquals(3, TodayWidgetModel.capacity(239, total = 4, fontScale = 1.3f))
        // 倍数再大也有上限，不至于算出 0 行
        assertEquals(1, TodayWidgetModel.capacity(140, total = 4, fontScale = 2.5f))
    }
}
