package io.github.joyreverie.onebnu.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.joyreverie.onebnu.BuildConfig
import io.github.joyreverie.onebnu.core.update.AutoUpdate
import io.github.joyreverie.onebnu.core.update.UpdateChecker
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo
import io.github.joyreverie.onebnu.ui.update.AutoUpdatePrompt

/**
 * 演练启动时的自动检查：默认先清掉节流与忽略记录（`--ez reset false` 可保留，用来验证「以后再说」生效），
 * `--es version 1.0.0` 把当前版本伪装成旧版本。
 * `adb shell am start -n io.github.joyreverie.onebnu/.widget.AutoUpdatePreviewActivity --es version 1.0.0`
 */
class AutoUpdatePreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = intent.getStringExtra("version") ?: BuildConfig.VERSION_NAME
        if (intent.getBooleanExtra("reset", true)) AutoUpdate.resetForTesting(this)
        setContent {
            OneBnuTheme {
                ProvideScreenInfo(calculateWindowSizeClass(this)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("自动检查更新演练 · 当前版本 $version", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    AutoUpdatePrompt(checker = remember { UpdateChecker(currentVersion = version) })
                }
            }
        }
    }
}
