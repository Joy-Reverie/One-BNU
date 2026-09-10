package io.github.joyreverie.onebnu.ui.notify

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.core.notify.AlarmService
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme

/**
 * 闹钟响铃时的全屏页：锁屏上也能显示并点亮屏幕，只有课名、时间地点和一个「停止」。
 * 响铃停止（手动或自动）后自己关闭。
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val (title, text) = AlarmService.current ?: ("上课提醒" to "")
        setContent {
            OneBnuTheme {
                val ringing by AlarmService.ringing.collectAsState()
                LaunchedEffect(ringing) { if (!ringing) finish() }
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
