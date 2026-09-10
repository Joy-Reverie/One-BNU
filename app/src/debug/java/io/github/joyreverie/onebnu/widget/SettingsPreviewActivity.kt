package io.github.joyreverie.onebnu.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import io.github.joyreverie.onebnu.BuildConfig
import io.github.joyreverie.onebnu.ui.settings.SettingsScreen
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo

/**
 * 不登录直接看设置页；`--es version 1.0.0` 可把「当前版本」伪装成旧版本，
 * 用来演练「检查更新 → 下载 → 安装」整条链路：
 * `adb shell am start -n io.github.joyreverie.onebnu/.widget.SettingsPreviewActivity --es version 1.0.0`
 */
class SettingsPreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = intent.getStringExtra("version") ?: BuildConfig.VERSION_NAME
        setContent {
            OneBnuTheme {
                ProvideScreenInfo(calculateWindowSizeClass(this)) {
                    SettingsScreen(onBack = { finish() }, currentVersion = version)
                }
            }
        }
    }
}
