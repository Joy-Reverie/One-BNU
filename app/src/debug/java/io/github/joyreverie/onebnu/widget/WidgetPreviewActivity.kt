package io.github.joyreverie.onebnu.widget

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.Term
import java.time.LocalDate

/**
 * 开发用：把今日课表小组件按几种高度各画一份放在一页里，不必真的登录、也不必往桌面拖。
 * 启动：adb shell am start -n io.github.joyreverie.onebnu/.widget.WidgetPreviewActivity [--es mode sample|empty|loggedout|error|refreshing] [--es now 14:00]
 * 只在 debug 包里，release 不含此类。
 */
class WidgetPreviewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // --ez pin true：顺带试一下「添加到桌面」的系统确认框；--ez notify true：发一条测试通知
        if (intent.getBooleanExtra("pin", false)) TodayWidgetProvider.requestPin(this)
        if (intent.getBooleanExtra("notify", false)) io.github.joyreverie.onebnu.core.notify.ClassReminder.showTest(this)
        val mode = intent.getStringExtra("mode") ?: "sample"
        val base = when (mode) {
            "loggedout" -> TodayWidgetRenderer.Base(null, hasCredentials = false, refreshing = false, lastError = null)
            "error" -> TodayWidgetRenderer.Base(null, hasCredentials = true, refreshing = false, lastError = "无法连接到教务系统，请检查网络")
            "empty" -> TodayWidgetRenderer.Base(sample(today = false), hasCredentials = true, refreshing = false, lastError = null)
            "refreshing" -> TodayWidgetRenderer.Base(sample(today = true), hasCredentials = true, refreshing = true, lastError = null)
            else -> TodayWidgetRenderer.Base(
                sample(today = true), hasCredentials = true, refreshing = false, lastError = null,
                events = listOf(
                    io.github.joyreverie.onebnu.data.model.PersonalEvent(
                        "e1", "导师组会", LocalDate.now(), java.time.LocalTime.of(12, 0), java.time.LocalTime.of(13, 0), "生地楼 306",
                    ),
                ),
            )
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#8A98A8"))
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        // 各启动器 1 格高约 65～120dp：140dp 是格子偏小的启动器上的 2 格，239/359/478 是 Pixel 启动器上实测的 2/3/4 格
        // --es now 14:00 可指定「现在」是几点，用来看已结束 / 进行中的样式
        val now = intent.getStringExtra("now")?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
            ?: java.time.LocalTime.now()
        // (label, widthDp, heightDp)：前两个是 2×2 小版式（小格 / Pixel 启动器），其余为 4 格宽
        listOf(
            Triple("2×2 小格启动器", 150, 150), Triple("2×2", 187, 239),
            Triple("2×4 小格启动器", 300, 140), Triple("2×4", 300, 239), Triple("3×4", 300, 359), Triple("4×4", 300, 478),
        ).forEach { (label, w, h) ->
            column.addView(TextView(this).apply {
                text = "$label  (${w}×${h}dp)"
                setTextColor(Color.WHITE)
                setPadding(0, dp(6), 0, dp(4))
            })
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(h)).apply { bottomMargin = dp(10) }
            }
            val rv = TodayWidgetRenderer.render(this, base, w, h, now = now)
            frame.addView(rv.apply(this, frame))
            column.addView(frame)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** 今天有 5 节课的示例课表（today=false 时排在别的日子，用来看「今天没有课」）。 */
    private fun sample(today: Boolean): Schedule {
        val dow = LocalDate.now().dayOfWeek.value.let { if (today) it else (it % 7) + 1 }
        val weeks = (1..20).toSet()
        fun s(start: Int, end: Int, where: String) =
            ClassSession(weeks, "1-20", dow, start, end, where)
        val courses = listOf(
            Course("C1", "高等数学（一）", 4.0, 64, "01", listOf("张伟"), listOf(s(1, 2, "教七楼 201"))),
            Course("C2", "大学英语读写", 2.0, 32, "03", listOf("Sarah Lee"), listOf(s(3, 4, "教二楼 108"))),
            Course("C3", "中国近现代史纲要", 3.0, 48, "02", listOf("王芳", "李强"), listOf(s(5, 6, "京师学堂 京师厅"))),
            Course("C4", "程序设计基础", 3.0, 48, "01", listOf("刘洋"), listOf(s(7, 8, "教九楼 305 机房"))),
            Course("C5", "大学体育（羽毛球）", 1.0, 32, "05", listOf("陈静"), listOf(s(9, 10, "邱季端体育馆 羽毛球馆"))),
        )
        return Schedule(Term("2026", "0", "2026-2027学年秋季学期"), "200000000000", "示例", "示例班", courses)
    }
}
