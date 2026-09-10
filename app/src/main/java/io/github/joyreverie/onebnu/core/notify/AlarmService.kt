package io.github.joyreverie.onebnu.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
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
 * 声音走**闹钟音量**而不是通知音量，循环播放并震动，直到停止或 [AUTO_STOP_MILLIS] 到期。
 *
 * 用户随时能停下来，一共四条路，任何一条失效都还有别的：
 *  1. 通知上的「停止」（亮屏时是横幅，锁屏时在通知里）；
 *  2. 锁屏 / 灭屏时弹出的 [AlarmActivity] 上的大按钮；
 *  3. 应用内「我的 → 上课提醒」卡片上的「停止」；
 *  4. 两分钟自动停。
 *
 * 重复调用 [start] 不会叠加：每次都先把上一次的播放器与震动收掉，全程只有一个 MediaPlayer。
 * 勿扰模式下不出声，只震动。
 */
class AlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "上课提醒" }
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()

        ensureChannel(this)
        // 无论如何先满足前台服务的契约，否则系统会判定「起了前台服务却没 startForeground」
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(title, text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        current = title to text
        _ringing.value = true

        // 再响一次之前先把上一次收干净：否则每点一次就多一个播放器，旧的再也停不掉
        stopPlayback()
        acquireWakeLock()
        if (!silencedByDnd(this)) startSound()
        startVibration()
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MILLIS)
        // 万一响铃期间进程被 ROM 杀掉，系统重启服务时把原来的 intent 带回来，
        // 课名与地点不会退化成通用标题
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        stopPlayback()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        _ringing.value = false
        current = null
        super.onDestroy()
    }

    /** 停掉声音与震动；可重复调用。 */
    private fun stopPlayback() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
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
                        // USAGE_ALARM：走闹钟音量，静音与响铃模式都照常响
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                setWakeMode(this@AlarmService, PowerManager.PARTIAL_WAKE_LOCK)
                prepare()
                start()
            }
        }.onFailure { player = null }
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        vibrator = v
        runCatching {
            v.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 700, 600), 0),
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
        // 「停止」走广播而不是 startService：应用在后台时给服务发 startService 会受后台启动限制，
        // 广播接收器里再 stopService 则不受限。
        val stop = PendingIntent.getBroadcast(
            this, REQUEST_STOP,
            Intent(this, AlarmStopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = if (silencedByDnd(this)) {
            listOf(text, "勿扰模式已开，只震动").filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            text
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setColor(0xFF1B3C6E.toInt())
            .setContentTitle(title)
            .setContentText(body)
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
        internal const val ACTION_STOP = "io.github.joyreverie.onebnu.alarm.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val NOTIFICATION_ID = 3100
        private const val REQUEST_FULL = 2101
        private const val REQUEST_STOP = 2102

        /** 没人理会时自动停，免得一直响下去。 */
        const val AUTO_STOP_MILLIS = 120_000L

        private val _ringing = MutableStateFlow(false)

        /** 是否正在响铃；界面据此把「试一下」换成「停止」，[AlarmActivity] 据此自动关闭。 */
        val ringing: StateFlow<Boolean> get() = _ringing

        /** 正在响的这一条的标题与副标题。 */
        @Volatile
        var current: Pair<String, String>? = null
            private set

        /**
         * 勿扰模式下是否静音。开着勿扰（含「仅闹钟」「完全静音」）就只震动不出声 ——
         * 闹钟音量本来能穿透勿扰，这里主动让步，免得在图书馆、会议里炸响。
         */
        fun silencedByDnd(context: Context): Boolean {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            return runCatching {
                nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                    nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }.getOrDefault(false)
        }

        /** 拉起响铃；系统不允许后台启动前台服务时返回 false，调用方退回普通通知。 */
        fun start(context: Context, title: String, text: String): Boolean = runCatching {
            val intent = Intent(context, AlarmService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            true
        }.getOrDefault(false)

        /** 停止响铃。stopService 不受后台启动限制，任何入口都能调。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AlarmService::class.java)) }
            _ringing.value = false
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

/** 通知上的「停止」。用广播而不是直接 startService，后台也能停。 */
class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmService.ACTION_STOP) AlarmService.stop(context)
    }
}
