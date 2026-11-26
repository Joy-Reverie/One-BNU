package io.github.joyreverie.onebnu.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.store.OfflineCache
import io.github.joyreverie.onebnu.data.model.AcademicCalendar
import io.github.joyreverie.onebnu.data.model.Option
import io.github.joyreverie.onebnu.ui.classroom.ClassroomScreen
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo
import java.time.LocalDate

/**
 * 开发用：不登录直接打开空闲教室页。启动时把脱敏样例写进本地快照（学期、校区、楼房、
 * 教室课表、本人课表），页面走真实的缓存优先路径，断网也能出结果。
 * 加 `--ez reset true` 清掉上次查看的楼；加 `--ez noSchedule true` 不写本人课表快照。
 */
class ClassroomPreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        seed()
        setContent {
            OneBnuTheme {
                ProvideScreenInfo(calculateWindowSizeClass(this)) {
                    ClassroomScreen(onBack = { finish() })
                }
            }
        }
    }

    private fun seed() {
        val cache = ServiceLocator.offlineCache
        val (year, season) = AcademicCalendar.currentTerm(LocalDate.now(), true)
        val xq = if (season == AcademicCalendar.Season.AUTUMN) "0" else "1"
        val termCode = "$year,$xq"
        val termName = if (xq == "0") "$year-${year + 1}学年秋季学期" else "$year-${year + 1}学年春季学期"
        cache.saveOptions(OfflineCache.TERMS, listOf(Option(termCode, termName)))
        val campus = Option("01", "本部")
        cache.saveOptions(OfflineCache.CAMPUSES, listOf(campus))

        val fixture = assets.open("classroom_preview/classroom_list.html").bufferedReader().readText()
        val buildings = listOf(
            Option("B01", "办公楼") to null,
            Option("B02", "电子楼") to fixture.replace("京师大厦", "电子楼"),
            Option("B03", "教四楼") to fixture.replace("京师大厦", "教四楼"),
            Option("B04", "教七楼") to fixture.replace("京师大厦", "教七楼"),
            Option("B05", "京师大厦") to fixture,
        )
        cache.saveOptions(cache.key(OfflineCache.BUILDINGS, campus.code), buildings.map { it.first })
        buildings.forEach { (option, html) ->
            if (html != null) cache.saveText(cache.key(OfflineCache.CLASSROOMS, termCode, campus.code, option.code), html)
        }

        val scheduleKey = cache.key(OfflineCache.SCHEDULE, termCode)
        if (intent.getBooleanExtra("noSchedule", false)) {
            cache.saveText(scheduleKey, "<html><body></body></html>")
        } else {
            cache.saveText(scheduleKey, assets.open("classroom_preview/schedule_list.html").bufferedReader().readText())
        }
        if (intent.getBooleanExtra("reset", false)) ServiceLocator.settings.classroomLastBuilding = null
    }
}
