package io.github.joyreverie.onebnu.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.joyreverie.onebnu.R
import io.github.joyreverie.onebnu.ui.notify.AlarmActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 闹钟提醒的响铃。
 *
 * 到点由 [ReminderReceiver] 拉起（精确闹钟会给应用一段临时白名单，后台也能启动前台服务）。
 * 声音走**闹钟音量**而不是通知音量，循环播放并震动，直到用户停止或 [AUTO_STOP_MILLIS] 到期；
 * 前台服务的通知带全屏意图，锁屏时直接弹出 [AlarmActivity]，亮屏时是横幅加「停止」。
 *
 * 响铃本身不依赖应用进程常驻 —— 定时由系统 AlarmManager 保管，进程被清理后仍会把这个服务拉起来。
 */
class AlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "上课提醒" }
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()

        ensureChannel(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(title, text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        current = title to text
        _ringing.value = true

        // 屏幕灭着时也要能出声：MediaPlayer 自己持一个 CPU 唤醒锁，服务再兜一层，两分钟后一定释放
        acquireWakeLock()
        startSound()
        startVibration()
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MILLIS)
        // 万一响铃期间进程被 ROM 杀掉，系统重启服务时把原来的 intent 带回来，
        // course 名与地点不会退化成通用标题
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        _ringing.value = false
        current = null
        super.onDestroy()
    }

    private fun startSound() {
        val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(this@AlarmService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_ALARM：走闹钟音量，静音 / 勿扰下也照常响
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                setWakeMode(this@AlarmService, PowerManager.PARTIAL_WAKE_LOCK)
                prepare()
                start()
            }
        }
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        vibrator = v
        val pattern = longArrayOf(0, 700, 600)
        runCatching {
            v.vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        runCatching {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "onebnu:alarm").apply {
                setReferenceCounted(false)
                acquire(AUTO_STOP_MILLIS + 10_000)
            }
        }
    }

    private fun notification(title: String, text: String): android.app.Notification {
        val full = PendingIntent.getActivity(
            this, REQUEST_FULL,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, REQUEST_STOP,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setColor(0xFF1B3C6E.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(0, "停止", stop)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "class_alarm"
        private const val ACTION_STOP = "io.github.joyreverie.onebnu.alarm.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val NOTIFICATION_ID = 3100
        private const val REQUEST_FULL = 2101
        private const val REQUEST_STOP = 2102

        /** 没人理会时自动停，免得一直响下去。 */
        const val AUTO_STOP_MILLIS = 120_000L

        private val _ringing = MutableStateFlow(false)

        /** 是否正在响铃，[AlarmActivity] 据此在停止后自动关闭。 */
        val ringing: StateFlow<Boolean> get() = _ringing

        /** 正在响的这一条的标题与副标题。 */
        @Volatile
        var current: Pair<String, String>? = null
            private set

        /** 拉起响铃；系统不允许后台启动前台服务时返回 false，调用方退回普通通知。 */
        fun start(context: Context, title: String, text: String): Boolean = runCatching {
            val intent = Intent(context, AlarmService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            true
        }.getOrDefault(false)

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_STOP)) }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "上课闹钟", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "闹钟方式的上课提醒"
                    setShowBadge(false)
                    // 声音与震动由服务自己按闹钟音量播放，渠道不要再响一次
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }
}
