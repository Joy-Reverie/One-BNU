package io.github.joyreverie.onebnu.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.joyreverie.onebnu.BuildConfig
import io.github.joyreverie.onebnu.ui.settings.ServiceEntriesScreen
import io.github.joyreverie.onebnu.ui.settings.SettingsScreen
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo

/**
 * 不登录直接看设置页；`--es version 1.0.0` 可把「当前版本」伪装成旧版本，
 * 用来演练「检查更新 → 下载 → 安装」整条链路：
 * `adb shell am start -n io.github.joyreverie.onebnu/.widget.SettingsPreviewActivity --es version 1.0.0`
 *
 * 「首页 → 校园服务」点进去是入口管理页；`--es page services` 直接打开它。
 */
class SettingsPreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = intent.getStringExtra("version") ?: BuildConfig.VERSION_NAME
        val startPage = intent.getStringExtra("page")
        setContent {
            OneBnuTheme {
                ProvideScreenInfo(calculateWindowSizeClass(this)) {
                    var page by rememberSaveable { mutableStateOf(startPage) }
                    BackHandler(enabled = page != null) { page = null }
                    when (page) {
                        "services" -> ServiceEntriesScreen(onBack = { page = null })
                        else -> SettingsScreen(
                            onBack = { finish() },
                            onServiceEntries = { page = "services" },
                            currentVersion = version,
                        )
                    }
                }
            }
        }
    }
}
