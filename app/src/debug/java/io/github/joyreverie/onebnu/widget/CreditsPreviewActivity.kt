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
import io.github.joyreverie.onebnu.data.model.CategoryRules
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.CourseCategory
import io.github.joyreverie.onebnu.data.model.Grade
import io.github.joyreverie.onebnu.data.model.InfoItem
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.Term
import io.github.joyreverie.onebnu.ui.profile.CreditsContent
import io.github.joyreverie.onebnu.ui.profile.CreditsUiState
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo

/** 开发用：不登录直接看「学分核算」页，点课程行改模块也能试。 */
class CreditsPreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        fun c(code: String, name: String, credits: Double, study: String = "初修") =
            Course(code, name, credits, null, "01", emptyList(), emptyList(), studyType = study)
        val autumn = Term("2026", "0", "2026-2027学年秋季学期")
        val spring = Term("2026", "1", "2026-2027学年春季学期")
        val schedules = listOf(
            Schedule(autumn, "1", "张三", "", listOf(
                c("AIS21197082", "高级算法设计", 3.0),
                c("AIS21158302", "机器学习", 2.0),
                c("AIS21160001", "科技论文阅读与写作", 2.0),
                c("AIS21160002", "计算机视觉", 2.0),
                c("GRA20225821", "理论与实践课", 2.0),
                c("GRA20221101", "综合学术英语", 2.0),
                c("GRA20220901", "数据与智能技术应用", 2.0),
                c("GRA20223301", "科研伦理与学术规范", 1.0),
            )),
            Schedule(spring, "1", "张三", "", listOf(
                c("AIS21197001", "概率图模型", 3.0),
                c("AIS21160003", "分布式系统", 2.0),
                c("AIS21160002", "计算机视觉", 2.0, study = "重修"),
                c("GRA20223302", "中国教育改革与发展", 1.0),
            )),
        )
        val grades = listOf(Grade("2026", "0", "", "AIS21160001", "科技论文阅读与写作", 2.0, "92", 92.0, null, courseType = "学位专业课"))
        setContent {
            OneBnuTheme {
                ProvideScreenInfo(calculateWindowSizeClass(this)) {
                    var manual by remember { mutableStateOf(mapOf("AIS21158302" to CourseCategory.EXPANSION)) }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CreditsContent(
                            s = CreditsUiState(
                                loading = false,
                                ledger = CategoryRules.build(schedules, grades, manual),
                                requirements = listOf(InfoItem("总学分", "35"), InfoItem("学位基础课", "6"), InfoItem("学位专业课", "12")),
                            ),
                            onRetry = {},
                            onSetCategory = { code, cat -> manual = if (cat == null) manual - code else manual + (code to cat) },
                        )
                    }
                }
            }
        }
    }
}
