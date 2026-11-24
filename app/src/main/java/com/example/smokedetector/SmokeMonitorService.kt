package com.example.smokedetector

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SmokeMonitorService : Service() {

    companion object {
        const val BASE_GAS_URL =
            "https://script.google.com/macros/s/AKfycbw2irVWdtfZuSXizNhjKqvBSh2z9dfK5bcnuxUAI-qrUkVUuUe5pv4Y4ti_qg4kwdtu/exec"

        const val ACTION_DATA_UPDATED = "com.example.smokedetector.DATA_UPDATED"
        const val ACTION_STOP_ALARM = "com.example.smokedetector.STOP_ALARM"
        const val ACTION_STOP_SERVICE = "com.example.smokedetector.STOP_SERVICE"

        private const val MONITOR_CHANNEL_ID = "SMOKEGUARD_MONITOR_CHANNEL"
        private const val ALARM_CHANNEL_ID = "SMOKEGUARD_ALARM_CHANNEL"
        private const val MONITOR_NOTIF_ID = 100
        private const val ALARM_NOTIF_ID = 1001

        var latestJsonData: String? = null
        var isAlarmRinging: Boolean = false
        private var activeRingtone: Ringtone? = null

        fun stopAlarm(context: Context) {
            try {
                if (activeRingtone?.isPlaying == true) {
                    activeRingtone?.stop()
                }
            } catch (_: Exception) {}
            isAlarmRinging = false
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(ALARM_NOTIF_ID)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isAlertActive = false

    private val pollingRunnable = object : Runnable {
        override fun run() {
            fetchSmokeData()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()

        // Giữ CPU không ngủ sâu khi tắt màn hình để chu kỳ 5s luôn chính xác
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmokeGuard::MonitorWakeLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // Tối đa 24h mỗi phiên
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALARM -> {
                stopAlarm(this)
                sendBroadcast(Intent(ACTION_DATA_UPDATED).setPackage(packageName))
                return START_STICKY
            }
            ACTION_STOP_SERVICE -> {
                stopAlarm(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notification = buildMonitorNotification("Đang giám sát không khí (5s/lần)...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MONITOR_NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(MONITOR_NOTIF_ID, notification)
        }

        handler.removeCallbacks(pollingRunnable)
        handler.post(pollingRunnable)

        return START_STICKY // Tự khởi động lại nếu hệ thống giải phóng bộ nhớ
    }

    private fun fetchSmokeData() {
        thread {
            try {
                val url = URL("$BASE_GAS_URL?action=read")
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 4000
                conn.readTimeout = 4000

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                latestJsonData = response

                val json = JSONObject(response)
                val smoke = json.getInt("smoke")
                val threshold = json.getInt("threshold")

                // Cập nhật nội dung thông báo chạy ngầm
                val statusText = if (smoke >= threshold) {
                    "CẢNH BÁO KHÓI: $smoke / $threshold"
                } else {
                    "An toàn • Mức khói: $smoke / $threshold"
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(MONITOR_NOTIF_ID, buildMonitorNotification(statusText))

                // Kiểm tra ngưỡng kích hoạt báo động
                if (smoke >= threshold) {
                    if (!isAlertActive) {
                        isAlertActive = true
                        triggerAlarm(smoke, threshold)
                    }
                } else {
                    if (isAlertActive) {
                        isAlertActive = false
                        stopAlarm(this)
                    }
                }

                // Gửi dữ liệu về MainActivity nếu App đang mở trên màn hình
                sendBroadcast(Intent(ACTION_DATA_UPDATED).setPackage(packageName))
            } catch (_: Exception) {
                // Giữ nguyên vòng lặp thử lại sau 5 giây nếu rớt mạng tạm thời
            }
        }
    }

    private fun triggerAlarm(smokeVal: Int, threshold: Int) {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        try {
            stopAlarm(this)
            activeRingtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            activeRingtone?.play()
            isAlarmRinging = true
        } catch (_: Exception) {}

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAlarmIntent = PendingIntent.getService(
            this, 1, Intent(this, SmokeMonitorService::class.java).apply { action = ACTION_STOP_ALARM },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CẢNH BÁO KHÓI THUỐC LÁ!")
            .setContentText("Nồng độ khói đạt $smokeVal (Vượt ngưỡng $threshold)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tắt chuông", stopAlarmIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .setAutoCancel(true)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ALARM_NOTIF_ID, builder.build())
    }

    private fun buildMonitorNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopServiceIntent = PendingIntent.getService(
            this, 2, Intent(this, SmokeMonitorService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SmokeGuard IoT đang chạy ngầm")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng giám sát", stopServiceIntent)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Kênh chạy ngầm im lặng (không kêu bíp mỗi 5s)
            val monitorChannel = NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Trạng thái giám sát ngầm",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(monitorChannel)

            // Kênh báo động khẩn cấp
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val audioAttr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                "Cảnh báo khói thuốc",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(alarmSound, audioAttr)
                enableVibration(true)
            }
            manager.createNotificationChannel(alarmChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollingRunnable)
        stopAlarm(this)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
