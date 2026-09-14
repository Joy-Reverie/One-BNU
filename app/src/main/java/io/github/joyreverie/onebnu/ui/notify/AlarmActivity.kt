package io.github.joyreverie.onebnu.ui.notify

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.core.notify.AlarmService
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import kotlinx.coroutines.delay

/**
 * 闹钟响铃时的全屏页：锁屏上也能显示并点亮屏幕，只有课名、时间地点和一个「停止」。
 * 响铃停止（手动或自动）后自己关闭。
 *
 * 标题与副标题优先取启动 intent 里的：这个页面可能比 [AlarmService] 先起来（前台服务是异步启动的），
 * 那时 [AlarmService.current] 还是空的。
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val current = AlarmService.current
        val title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: current?.first ?: "上课提醒"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: current?.second ?: ""
        setContent {
            OneBnuTheme {
                val ringing by AlarmService.ringing.collectAsState()
                // 服务可能还没来得及开始响：只在「响过又停了」、或等了几秒仍没响起来时才关闭，
                // 不然刚打开就因为 ringing 还是 false 把自己关掉，用户连「停止」都没见到。
                var sawRinging by remember { mutableStateOf(ringing) }
                LaunchedEffect(ringing) {
                    when {
                        ringing -> sawRinging = true
                        sawRinging -> finish()
                        else -> {
                            delay(START_GRACE_MILLIS)
                            if (!AlarmService.ringing.value) finish()
                        }
                    }
                }
                AlarmScreen(title = title, text = text) {
                    AlarmService.stop(this)
                    finish()
                }
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        /** 服务异步启动到真正响起来的宽限；超过它还没响就当作没起来，页面自行关闭。 */
        private const val START_GRACE_MILLIS = 4_000L

        /** 全屏页的启动 intent，带上课名与副标题；新任务、盖掉旧页。 */
        fun intent(context: Context, title: String, text: String): Intent =
            Intent(context, AlarmActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

@Composable
private fun AlarmScreen(title: String, text: String, onStop: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxSize().background(LocalAccents.current.heroGradientSoft),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.NotificationsActive, null,
                    Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (text.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(48.dp))
                Button(
                    onClick = onStop,
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text("停止", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            }
        }
    }
}
