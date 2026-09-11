package io.github.joyreverie.onebnu.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import io.github.joyreverie.onebnu.ui.profile.CultivationPlanScreen
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo

/** 开发用：不经 OneVPN 登录直接检查培养方案入口页布局。 */
class CultivationPlanPreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OneBnuTheme {
                ProvideScreenInfo(calculateWindowSizeClass(this)) {
                    CultivationPlanScreen(onBack = { finish() }, onOpenOfficialPage = { _, _ -> })
                }
            }
        }
    }
}
