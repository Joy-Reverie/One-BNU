package io.github.joyreverie.onebnu.widget

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.ui.profile.ReminderCard
import io.github.joyreverie.onebnu.core.notify.AlarmService
import io.github.joyreverie.onebnu.ui.profile.WidgetPinCard
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo

/** 开发用：不登录直接看「我的」页的上课提醒卡与小组件卡。 */
class ProfileCardsPreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // --ez miui true：模拟「小米且未授权桌面快捷方式」，看小组件卡上的授权提示
        if (intent.getBooleanExtra("miui", false)) MiuiShortcutPermission.previewOverride = true
        // --ez alarm true [--ei delay 秒]：延迟起铃，便于先锁屏 / 灭屏再看闹钟的全屏页
        if (intent.getBooleanExtra("alarm", false)) {
            val delay = intent.getIntExtra("delay", 5).coerceIn(0, 120) * 1000L
            Handler(Looper.getMainLooper()).postDelayed(
                { AlarmService.start(this, "高等数学（一）", "10 分钟后开始 · 教七楼 201") },
                delay,
            )
        }
        setContent {
            OneBnuTheme {
                ProvideScreenInfo(calculateWindowSizeClass(this)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 48.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item { ReminderCard() }
                            item { WidgetPinCard() }
                        }
                    }
                }
            }
        }
    }
}
