package io.github.joyreverie.onebnu.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.data.model.GpaCalculator
import io.github.joyreverie.onebnu.data.model.GpaScale
import io.github.joyreverie.onebnu.data.model.Grade
import io.github.joyreverie.onebnu.data.repo.DataFreshness
import io.github.joyreverie.onebnu.ui.components.CacheBanner
import io.github.joyreverie.onebnu.ui.grade.GradeContent
import io.github.joyreverie.onebnu.ui.grade.GradeUiState
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo

/**
 * 开发用：不登录直接检查成绩卡、缓考排除提示与手动计算范围弹窗。
 * 加 `--ez cached true`（可再加 `--ez refreshing true`）可以看缓存优先时的那一行提示。
 */
class GradePreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        fun grade(
            term: String,
            code: String,
            name: String,
            score: Double,
            point: Double,
            remark: String = "",
        ) = Grade(
            xn = if (term.startsWith("2026")) "2026" else "2025",
            xq = if (term.contains("秋季")) "0" else "1",
            termLabel = term,
            courseCode = code,
            courseName = name,
            credits = 2.0,
            scoreText = score.toInt().toString(),
            score = score,
            officialPoint = point,
            courseType = "学位专业课",
            remark = remark,
        )

        val grades = listOf(
            grade("2026-2027 秋季学期", "AIS001", "机器学习", 95.0, 4.5),
            grade("2026-2027 秋季学期", "AIS002", "统计学习", 82.0, 3.2),
            grade("2026-2027 秋季学期", "AIS003", "深度学习", 0.0, 0.0, remark = "缓考"),
            grade("2025-2026 春季学期", "GRA001", "学术英语", 88.0, 3.8),
        )

        val cached = intent.getBooleanExtra("cached", false)
        val refreshing = intent.getBooleanExtra("refreshing", false)

        setContent {
            OneBnuTheme {
                ProvideScreenInfo(calculateWindowSizeClass(this)) {
                    var manuallyExcluded by remember { mutableStateOf(setOf(grades[1].calculationKey)) }
                    val scale = GpaScale.OFFICIAL
                    val state = GradeUiState(
                        loading = false,
                        refreshing = refreshing,
                        freshness = if (cached) {
                            DataFreshness(System.currentTimeMillis() - 3_600_000L, cached = true)
                        } else {
                            null
                        },
                        grades = grades,
                        scale = scale,
                        overall = GpaCalculator.summarize(grades, scale, manuallyExcluded),
                        byTerm = GpaCalculator.byTerm(grades, scale, manuallyExcluded),
                        manuallyExcludedCourseKeys = manuallyExcluded,
                    )
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                        if (state.fromCache) {
                            CacheBanner(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                refreshing = state.refreshing,
                            )
                        }
                        GradeContent(state, onSetIncludedCourses = { included ->
                            val eligible = grades.filter { GpaCalculator.isEligible(it, scale) }
                                .map { it.calculationKey }
                                .toSet()
                            manuallyExcluded = eligible - included
                        })
                        }
                    }
                }
            }
        }
    }
}
