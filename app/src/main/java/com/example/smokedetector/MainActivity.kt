package com.example.smokedetector

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class SmokeHistoryItem(
    val time: String,
    val smoke: Int,
    val threshold: Int
)

class HistoryAdapter(private val items: List<SmokeHistoryItem>) :
    RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSmoke: TextView = view.findViewById(R.id.tvItemSmoke)
        val tvTime: TextView = view.findViewById(R.id.tvItemTime)
        val tvThreshold: TextView = view.findViewById(R.id.tvItemThreshold)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvSmoke.text = "Phát hiện khói: ${item.smoke}"
        holder.tvTime.text = item.time
        holder.tvThreshold.text = "Ngưỡng: ${item.threshold}"
    }

    override fun getItemCount() = items.size
}

class MainActivity : AppCompatActivity() {

    private val baseGasUrl =
        "https://script.google.com/macros/s/AKfycbw2irVWdtfZuSXizNhjKqvBSh2z9dfK5bcnuxUAI-qrUkVUuUe5pv4Y4ti_qg4kwdtu/exec"
    private val channelId = "SMOKEGUARD_ALARM_CHANNEL"

    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var progressSmoke: CircularProgressIndicator
    private lateinit var tvCurrentSmoke: TextView
    private lateinit var tvThresholdDisplay: TextView
    private lateinit var tvLastUpdate: TextView
    private lateinit var etThreshold: EditText
    private lateinit var btnStopAlarm: MaterialButton
    private lateinit var tvHistoryCount: TextView
    private lateinit var tvEmptyHistory: TextView
    private lateinit var rvHistory: RecyclerView

    private val handler = Handler(Looper.getMainLooper())
    private var threshold = 5
    private var isAlarmActive = false
    private var activeRingtone: Ringtone? = null

    private val historyList = ArrayList<SmokeHistoryItem>()
    private lateinit var historyAdapter: HistoryAdapter

    private val pollingRunnable = object : Runnable {
        override fun run() {
            fetchSmokeData()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        progressSmoke = findViewById(R.id.progressSmoke)
        tvCurrentSmoke = findViewById(R.id.tvCurrentSmoke)
        tvThresholdDisplay = findViewById(R.id.tvThresholdDisplay)
        tvLastUpdate = findViewById(R.id.tvLastUpdate)
        etThreshold = findViewById(R.id.etThreshold)
        btnStopAlarm = findViewById(R.id.btnStopAlarm)
        tvHistoryCount = findViewById(R.id.tvHistoryCount)
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory)
        rvHistory = findViewById(R.id.rvHistory)

        historyAdapter = HistoryAdapter(historyList)
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter

        createNotificationChannel()
        requestNotificationPermission()

        // Nút giảm nhanh ngưỡng (Tiện demo khi thi)
        findViewById<MaterialButton>(R.id.btnMinus).setOnClickListener {
            val current = etThreshold.text.toString().toIntOrNull() ?: threshold
            val next = (current - 50).coerceAtLeast(1)
            etThreshold.setText(next.toString())
        }

        // Nút tăng nhanh ngưỡng
        findViewById<MaterialButton>(R.id.btnPlus).setOnClickListener {
            val current = etThreshold.text.toString().toIntOrNull() ?: threshold
            etThreshold.setText((current + 50).toString())
        }

        // Lưu ngưỡng lên Google Sheets
        findViewById<MaterialButton>(R.id.btnSaveThreshold).setOnClickListener {
            val input = etThreshold.text.toString().toIntOrNull()
            if (input != null && input > 0) {
                etThreshold.clearFocus()
                saveThresholdToServer(input)
            } else {
                Toast.makeText(this, "Vui lòng nhập số lớn hơn 0", Toast.LENGTH_SHORT).show()
            }
        }

        // Tắt chuông trực tiếp trên màn hình
        btnStopAlarm.setOnClickListener {
            stopAlarmSound()
            btnStopAlarm.visibility = View.GONE
        }

        handler.post(pollingRunnable)
    }

    private fun saveThresholdToServer(newVal: Int) {
        thread {
            try {
                val url = URL("$baseGasUrl?action=set_threshold&value=$newVal")
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.inputStream.bufferedReader().use { it.readText() }

                runOnUiThread {
                    threshold = newVal
                    isAlarmActive = false
                    tvThresholdDisplay.text = "Ngưỡng: $threshold"
                    Toast.makeText(this, "Đã cập nhật ngưỡng: $newVal", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Lỗi mạng khi lưu ngưỡng", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchSmokeData() {
        thread {
            try {
                val url = URL("$baseGasUrl?action=read")
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 4000
                conn.readTimeout = 4000

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val smoke = json.getInt("smoke")
                val updatedAt = json.getString("updated_at")
                val serverThreshold = json.getInt("threshold")
                val historyArr = json.getJSONArray("history")

                val tempHistory = ArrayList<SmokeHistoryItem>()
                for (i in 0 until historyArr.length()) {
                    val item = historyArr.getJSONObject(i)
                    tempHistory.add(
                        SmokeHistoryItem(
                            time = item.getString("time"),
                            smoke = item.getInt("smoke"),
                            threshold = item.getInt("threshold")
                        )
                    )
                }

                runOnUiThread {
                    tvConnectionStatus.text = "● Đã kết nối trạm cảm biến (5s/lần)"
                    tvConnectionStatus.setTextColor(Color.parseColor("#86EFAC"))

                    threshold = serverThreshold
                    if (!etThreshold.hasFocus()) {
                        etThreshold.setText(threshold.toString())
                    }

                    tvCurrentSmoke.text = smoke.toString()
                    tvThresholdDisplay.text = "Ngưỡng: $threshold"
                    tvLastUpdate.text = "Cập nhật cảm biến: $updatedAt"

                    // Cập nhật thanh đo vòng cung (tỉ lệ % so với ngưỡng)
                    val percent = ((smoke.toFloat() / threshold.coerceAtLeast(1)) * 75).toInt().coerceIn(5, 100)
                    progressSmoke.setProgressCompat(percent, true)

                    // Cập nhật lịch sử
                    historyList.clear()
                    historyList.addAll(tempHistory)
                    historyAdapter.notifyDataSetChanged()
                    tvHistoryCount.text = "${historyList.size} sự kiện"
                    tvEmptyHistory.visibility = if (historyList.isEmpty()) View.VISIBLE else View.GONE

                    // Kiểm tra trạng thái báo động
                    if (smoke >= threshold) {
                        tvStatusBadge.text = "CẢNH BÁO: PHÁT HIỆN KHÓI!"
                        tvStatusBadge.setTextColor(Color.parseColor("#DC2626"))
                        tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_danger)
                        progressSmoke.setIndicatorColor(Color.parseColor("#DC2626"))
                        tvCurrentSmoke.setTextColor(Color.parseColor("#DC2626"))

                        if (!isAlarmActive) {
                            isAlarmActive = true
                            btnStopAlarm.visibility = View.VISIBLE
                            triggerAlarm(smoke)
                        }
                    } else {
                        tvStatusBadge.text = "KHÔNG KHÍ AN TOÀN"
                        tvStatusBadge.setTextColor(Color.parseColor("#15803D"))
                        tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_safe)
                        progressSmoke.setIndicatorColor(Color.parseColor("#10B981"))
                        tvCurrentSmoke.setTextColor(Color.parseColor("#0F172A"))

                        isAlarmActive = false
                        btnStopAlarm.visibility = View.GONE
                        stopAlarmSound()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvConnectionStatus.text = "● Đang thử kết nối lại máy chủ..."
                    tvConnectionStatus.setTextColor(Color.parseColor("#FDE047"))
                }
            }
        }
    }

    private fun triggerAlarm(smokeVal: Int) {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        // Phát chuông trực tiếp trên App để giám khảo nghe rõ khi demo
        try {
            stopAlarmSound()
            activeRingtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            activeRingtone?.play()
        } catch (_: Exception) {}

        // Đẩy thông báo hệ thống
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CẢNH BÁO KHÓI THUỐC LÁ!")
            .setContentText("Phát hiện nồng độ khói $smokeVal (Vượt ngưỡng $threshold)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(alarmUri)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .setAutoCancel(true)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, builder.build())
    }

    private fun stopAlarmSound() {
        try {
            if (activeRingtone?.isPlaying == true) {
                activeRingtone?.stop()
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val audioAttr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            val channel = NotificationChannel(channelId, "Cảnh báo khói thuốc", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(alarmSound, audioAttr)
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarmSound()
        handler.removeCallbacks(pollingRunnable)
    }
}
