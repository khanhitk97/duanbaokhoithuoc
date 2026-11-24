package com.example.smokedetector

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    private var threshold = 5
    private val historyList = ArrayList<SmokeHistoryItem>()
    private lateinit var historyAdapter: HistoryAdapter

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            SmokeMonitorService.latestJsonData?.let { updateUiFromJson(it) }
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

        requestNotificationPermission()
        startMonitorService()

        findViewById<MaterialButton>(R.id.btnMinus).setOnClickListener {
            val current = etThreshold.text.toString().toIntOrNull() ?: threshold
            val next = (current - 50).coerceAtLeast(1)
            etThreshold.setText(next.toString())
        }

        findViewById<MaterialButton>(R.id.btnPlus).setOnClickListener {
            val current = etThreshold.text.toString().toIntOrNull() ?: threshold
            etThreshold.setText((current + 50).toString())
        }

        findViewById<MaterialButton>(R.id.btnSaveThreshold).setOnClickListener {
            val input = etThreshold.text.toString().toIntOrNull()
            if (input != null && input > 0) {
                etThreshold.clearFocus()
                saveThresholdToServer(input)
            } else {
                Toast.makeText(this, "Vui lòng nhập số lớn hơn 0", Toast.LENGTH_SHORT).show()
            }
        }

        btnStopAlarm.setOnClickListener {
            SmokeMonitorService.stopAlarm(this)
            btnStopAlarm.visibility = View.GONE
        }

        // Hiển thị ngay dữ liệu gần nhất nếu Service đã chạy sẵn trước đó
        SmokeMonitorService.latestJsonData?.let { updateUiFromJson(it) }
    }

    private fun startMonitorService() {
        val serviceIntent = Intent(this, SmokeMonitorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun saveThresholdToServer(newVal: Int) {
        thread {
            try {
                val url = URL("${SmokeMonitorService.BASE_GAS_URL}?action=set_threshold&value=$newVal")
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.inputStream.bufferedReader().use { it.readText() }

                runOnUiThread {
                    threshold = newVal
                    tvThresholdDisplay.text = "Ngưỡng: $threshold"
                    Toast.makeText(this, "Đã cập nhật ngưỡng: $newVal", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Lỗi mạng khi lưu ngưỡng", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateUiFromJson(response: String) {
        try {
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

            tvConnectionStatus.text = "● Đang chạy ngầm 24/7 (5s/lần)"
            tvConnectionStatus.setTextColor(Color.parseColor("#86EFAC"))

            threshold = serverThreshold
            if (!etThreshold.hasFocus()) {
                etThreshold.setText(threshold.toString())
            }

            tvCurrentSmoke.text = smoke.toString()
            tvThresholdDisplay.text = "Ngưỡng: $threshold"
            tvLastUpdate.text = "Cập nhật cảm biến: $updatedAt"

            val percent = ((smoke.toFloat() / threshold.coerceAtLeast(1)) * 75).toInt().coerceIn(5, 100)
            progressSmoke.setProgressCompat(percent, true)

            historyList.clear()
            historyList.addAll(tempHistory)
            historyAdapter.notifyDataSetChanged()
            tvHistoryCount.text = "${historyList.size} sự kiện"
            tvEmptyHistory.visibility = if (historyList.isEmpty()) View.VISIBLE else View.GONE

            btnStopAlarm.visibility = if (SmokeMonitorService.isAlarmRinging) View.VISIBLE else View.GONE

            if (smoke >= threshold) {
                tvStatusBadge.text = "CẢNH BÁO: PHÁT HIỆN KHÓI!"
                tvStatusBadge.setTextColor(Color.parseColor("#DC2626"))
                tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_danger)
                progressSmoke.setIndicatorColor(Color.parseColor("#DC2626"))
                tvCurrentSmoke.setTextColor(Color.parseColor("#DC2626"))
            } else {
                tvStatusBadge.text = "KHÔNG KHÍ AN TOÀN"
                tvStatusBadge.setTextColor(Color.parseColor("#15803D"))
                tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_safe)
                progressSmoke.setIndicatorColor(Color.parseColor("#10B981"))
                tvCurrentSmoke.setTextColor(Color.parseColor("#0F172A"))
            }
        } catch (_: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SmokeMonitorService.ACTION_DATA_UPDATED)
        ContextCompat.registerReceiver(this, dataReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        startMonitorService()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(dataReceiver)
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
}
