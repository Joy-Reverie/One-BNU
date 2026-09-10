package io.github.joyreverie.onebnu.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.ui.home.HomeContent
import io.github.joyreverie.onebnu.ui.home.HomeUiState
import io.github.joyreverie.onebnu.ui.home.TodayCourse
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo
import java.time.LocalDate
import java.time.LocalTime

/**
 * 开发用：不登录直接看首页「今日课程」时间轴里课程与日程混排、「+ 日程」与编辑弹层。
 * --es mode empty 看没课没日程的空态，--es mode one 看只有一节课时卡片的最小高度。
 */
class HomePreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode")
        val empty = mode == "empty"
        val single = mode == "one"
        val today = LocalDate.now()
        fun course(name: String, teacher: String, start: Int, end: Int, room: String) = TodayCourse(
            Course("C$start", name, 2.0, 32, "01", listOf(teacher), emptyList()),
            ClassSession(setOf(1), "1-16", today.dayOfWeek.value, start, end, room),
        )
        val courses = if (empty) emptyList() else if (single) listOf(course("高等数学（一）", "张伟", 1, 2, "教七楼 201")) else listOf(
            course("高等数学（一）", "张伟", 1, 2, "教七楼 201"),
            course("大学英语读写", "Sarah Lee", 3, 4, "教二楼 108"),
            course("中国近现代史纲要", "王芳", 5, 6, "京师学堂 京师厅"),
            course("程序设计基础", "刘洋", 7, 8, "教九楼 305 机房"),
        )
        val initialEvents = if (empty || single) emptyList() else listOf(
            PersonalEvent("e1", "导师组会", today, LocalTime.of(12, 0), LocalTime.of(13, 0), "生地楼 306", "带上周报"),
            PersonalEvent("e2", "体检", today, LocalTime.of(17, 30), LocalTime.of(18, 30), "校医院"),
            PersonalEvent("e3", "晨跑", today.minusWeeks(1), LocalTime.of(7, 0), LocalTime.of(7, 45), "操场", repeatDays = PersonalEvent.EVERY_DAY),
        )
        setContent {
            OneBnuTheme {
                ProvideScreenInfo(calculateWindowSizeClass(this)) {
                    var events by remember { mutableStateOf(initialEvents) }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        HomeContent(
                            s = HomeUiState(
                                loading = false,
                                userName = "张同学",
                                greeting = "上午好",
                                subtitle = "2026-2027学年秋季学期 · 第 1 周 · 周四",
                                todayCourses = courses,
                                todayEvents = events.sortedBy { it.start },
                                todayHint = "第 1 周 周四 没有排课",
                                periodTimes = ServiceLocator.settings.periodTimes,
                            ),
                            onRetry = {},
                            onNavigate = {},
                            onSaveEvent = { e -> events = events.filter { it.id != e.id } + e },
                            onDeleteEvent = { e -> events = events.filter { it.id != e.id } },
                        )
                    }
                }
            }
        }
    }
}
