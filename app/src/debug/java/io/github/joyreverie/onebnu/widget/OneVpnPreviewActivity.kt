package io.github.joyreverie.onebnu.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo
import io.github.joyreverie.onebnu.ui.web.WebScreen

/**
 * 开发用：检查 OneVPN 登录中转。
 * 不提供任何账号数据；未登录时应只显示学校官方登录页，已登录北京会话时才会走 CAS SSO。
 */
class OneVpnPreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OneBnuTheme {
                ProvideScreenInfo(calculateWindowSizeClass(this)) {
                    WebScreen(
                        title = "OneVPN SSO 预览",
                        url = COURSE_CENTER_URL,
                        useSso = false,
                        useOneVpnSso = true,
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    private companion object {
        const val COURSE_CENTER_URL =
            "https://onevpn.bnu.edu.cn/https/77726476706e69737468656265737421fbf45b8469326645300d8db9d6562d/www/dd/vue/spa/jw-pyfa#/pyfa"
    }
}
