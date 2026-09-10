package io.github.joyreverie.onebnu.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.LocalTime
import androidx.compose.ui.Modifier
import io.github.joyreverie.onebnu.core.store.Settings
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.Term
import io.github.joyreverie.onebnu.ui.event.EventEditorSheet
import io.github.joyreverie.onebnu.ui.schedule.ScheduleGrid
import io.github.joyreverie.onebnu.ui.schedule.ScheduleLayout
import io.github.joyreverie.onebnu.ui.schedule.ScheduleUiState
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo
import java.time.LocalDate

/**
 * 开发用：不登录直接看课表网格在当前屏幕（含横屏）下的行高与缩放效果。
 * 启动：adb shell am start -n io.github.joyreverie.onebnu/.widget.SchedulePreviewActivity [--ef zoom 1.3]
 * 只在 debug 包里。
 */
class SchedulePreviewActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initialZoom = intent.getFloatExtra("zoom", 1f)
        val state = ScheduleUiState(
            loading = false,
            term = Term("2026", "0", "2026-2027学年秋季学期"),
            schedule = sample(),
            week = 2,
            currentWeek = 2,
            maxWeek = 20,
            periodTimes = Settings.PERIOD_TIMES,
            termStart = LocalDate.of(2026, 9, 7),
            events = listOf(
                PersonalEvent("e1", "体检", LocalDate.of(2026, 9, 18), LocalTime.of(8, 0), LocalTime.of(10, 0), "校医院", "带学生卡"),
                // 与周四 5-6 节的课重叠，验证并排与上下对齐
                PersonalEvent("e2", "导师组会", LocalDate.of(2026, 9, 17), LocalTime.of(14, 0), LocalTime.of(16, 0), "生地楼 306"),
                PersonalEvent("e3", "讲座：人工智能前沿", LocalDate.of(2026, 9, 14), LocalTime.of(19, 0), LocalTime.of(20, 30), "京师学堂"),
                // 重复日程：从 9/7 起每周二、周六 07:00 晨跑
                PersonalEvent(
                    "e4", "晨跑", LocalDate.of(2026, 9, 7), LocalTime.of(7, 0), LocalTime.of(7, 45), "操场",
                    repeatDays = setOf(2, 6),
                ),
            ),
        )
        setContent {
            OneBnuTheme {
                ProvideScreenInfo(calculateWindowSizeClass(this)) {
                    var zoom by remember { mutableFloatStateOf(ScheduleLayout.clampZoom(initialZoom)) }
                    var editing by remember { mutableStateOf<PersonalEvent?>(null) }
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("2026-2027学年秋季学期 · 第 2 周") },
                                actions = { io.github.joyreverie.onebnu.ui.schedule.ZoomBadge(zoom) },
                            )
                        },
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            ScheduleGrid(
                                schedule = state.schedule!!,
                                state = state,
                                zoom = zoom,
                                onZoom = { zoom = ScheduleLayout.clampZoom(zoom * it) },
                                onZoomEnd = {},
                                onClick = { _, _ -> },
                                onEventClick = { editing = it },
                            )
                        }
                    }
                    editing?.let { e ->
                        EventEditorSheet(initial = e, defaultDate = e.date, onDismiss = { editing = null }, onSave = { editing = null }, onDelete = { editing = null })
                    }
                }
            }
        }
    }

    /** 照着真实课表的样子造的一周示例。 */
    private fun sample(): Schedule {
        val weeks = (1..16).toSet()
        fun s(dow: Int, a: Int, b: Int, where: String) = ClassSession(weeks, "1-16", dow, a, b, where)
        fun c(name: String, teacher: String, vararg sess: ClassSession) =
            Course(name, name, 2.0, 32, "01", listOf(teacher), sess.toList())
        return Schedule(
            Term("2026", "0", "2026-2027学年秋季学期"), "1", "示例", "示例班",
            listOf(
                c("高级算法设计", "王老师", s(5, 1, 2, "四117"), s(5, 3, 4, "在线教学")),
                c("计算机视觉", "李老师", s(2, 3, 4, "八406")),
                c("马克思主义与社会科学方法论", "张老师", s(4, 5, 6, "四107")),
                c("科技论文阅读与写作", "赵老师", s(5, 5, 6, "二112")),
                c("机器学习", "陈老师", s(2, 7, 8, "四117")),
                c("人工智能前沿讲座", "刘老师", s(3, 7, 8, "九204")),
                c("理论与实践课", "周老师", s(4, 7, 8, "七103")),
                c("研究生英语", "Sarah", s(1, 9, 10, "教二 305")),
            ),
        )
    }
}
