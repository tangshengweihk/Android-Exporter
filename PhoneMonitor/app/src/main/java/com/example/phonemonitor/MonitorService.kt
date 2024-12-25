package com.example.phonemonitor

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.RandomAccessFile
import android.util.Log
import kotlin.math.max
import android.view.WindowManager
import android.util.DisplayMetrics
import android.os.Environment
import android.os.StatFs
import android.net.wifi.WifiManager
import android.net.wifi.SupplicantState
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class MonitorService : Service() {
    companion object {
        private const val TAG = "MonitorService"
        private const val NOTIFICATION_CHANNEL_ID = "monitor_service"
        private const val NOTIFICATION_ID = 1
        private const val CPU_MEMORY_UPDATE_INTERVAL = 5000L // 5秒
        private const val BATTERY_UPDATE_INTERVAL = 30000L // 30秒
    }

    private var displayStreamer: DisplayStreamer? = null
    private var server: ApplicationEngine? = null
    private var metricsUpdateJob: kotlinx.coroutines.Job? = null
    
    // 缓存的指标数据
    private var cachedMetrics = StringBuilder()
    private val metricsLock = Any()

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            
            displayStreamer = DisplayStreamer()
            
            // 启动定期更新任务
            startMetricsUpdate()
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.d(TAG, "Starting server...")
                    server = embeddedServer(Netty, 
                        host = "0.0.0.0",
                        port = 8080
                    ) {
                        routing {
                            get("/") {
                                Log.d(TAG, "Received request on /")
                                call.respondText("Phone Monitor Service is running\n")
                            }
                            get("/test") {
                                Log.d(TAG, "Received request on /test")
                                call.respondText("Test endpoint is working\n")
                            }
                            get("/metrics") {
                                Log.d(TAG, "Received request on /metrics")
                                try {
                                    val metrics = synchronized(metricsLock) {
                                        cachedMetrics.toString()
                                    }
                                    call.respondText(metrics)
                                } catch (e: Exception) {
                                    val errorMsg = "Error serving metrics: ${e.message}"
                                    Log.e(TAG, errorMsg, e)
                                    call.respondText("$errorMsg\n")
                                }
                            }
                        }
                    }
                    
                    server?.start(wait = false)
                    Log.d(TAG, "Server started successfully")
                    
                    displayStreamer?.start()
                    Log.d(TAG, "DisplayStreamer started successfully")
                } catch (e: Exception) {
                    val errorMsg = "Error starting server: ${e.message}"
                    Log.e(TAG, errorMsg, e)
                    e.printStackTrace()
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            val errorMsg = "Error in onCreate: ${e.message}"
            Log.e(TAG, errorMsg, e)
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun startMetricsUpdate() {
        metricsUpdateJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    // 更新CPU和内存信息
                    updateCpuAndMemoryMetrics()
                    kotlinx.coroutines.delay(CPU_MEMORY_UPDATE_INTERVAL)
                    
                    // 更新电池信息
                    if ((System.currentTimeMillis() / CPU_MEMORY_UPDATE_INTERVAL) % 
                        (BATTERY_UPDATE_INTERVAL / CPU_MEMORY_UPDATE_INTERVAL) == 0L) {
                        updateBatteryMetrics()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating metrics: ${e.message}", e)
                }
            }
        }
    }

    private fun updateCpuAndMemoryMetrics() {
        synchronized(metricsLock) {
            cachedMetrics.setLength(0)
            cachedMetrics.append(collectSystemMetrics())
            cachedMetrics.append(collectCpuMetrics())
            cachedMetrics.append(collectMemoryMetrics())
            cachedMetrics.append(collectBatteryMetrics())
        }
    }

    private fun updateBatteryMetrics() {
        synchronized(metricsLock) {
            cachedMetrics.append(collectBatteryMetrics())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        try {
            metricsUpdateJob?.cancel()
            metricsUpdateJob = null
            displayStreamer?.stop()
            displayStreamer = null
            server?.stop(1000, 1000)
            server = null
            Log.d(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun collectCpuMetrics(): String {
        val sb = StringBuilder()
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val cpuLine = reader.readLine()
            reader.close()

            val cpuData = cpuLine.split("\\s+".toRegex()).drop(1)
            val user = cpuData[0].toLong()
            val nice = cpuData[1].toLong()
            val system = cpuData[2].toLong()
            val idle = cpuData[3].toLong()
            val total = user + nice + system + idle

            sb.append("# HELP phone_cpu_usage CPU使用率\n")
            sb.append("# TYPE phone_cpu_usage gauge\n")
            sb.append("phone_cpu_usage ").append(
                max(0.0, (1 - idle.toDouble() / total) * 100)
            ).append("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting CPU metrics: ${e.message}", e)
            sb.append("# ERROR collecting CPU metrics: ${e.message}\n")
        }
        return sb.toString()
    }

    @Suppress("SpellCheckingInspection")
    private fun collectMemoryMetrics(): String {
        val sb = StringBuilder()
        try {
            // /proc/meminfo 是Linux系统文件，用于提供内存使用信息
            val reader = RandomAccessFile("/proc/meminfo", "r")
            var line: String?
            var totalMem = 0L
            var freeMem = 0L
            while (reader.readLine().also { line = it } != null) {
                when {
                    line!!.startsWith("MemTotal:") -> 
                        totalMem = line!!.split("\\s+".toRegex())[1].toLong()
                    line!!.startsWith("MemFree:") -> 
                        freeMem = line!!.split("\\s+".toRegex())[1].toLong()
                }
            }
            reader.close()

            sb.append("# HELP phone_memory_total 总内存(KB)\n")
            sb.append("# TYPE phone_memory_total gauge\n")
            sb.append("phone_memory_total ").append(totalMem).append("\n")
            
            sb.append("# HELP phone_memory_free 空闲内存(KB)\n")
            sb.append("# TYPE phone_memory_free gauge\n")
            sb.append("phone_memory_free ").append(freeMem).append("\n")
            
            sb.append("# HELP phone_memory_usage_percent 内存使用率\n")
            sb.append("# TYPE phone_memory_usage_percent gauge\n")
            sb.append("phone_memory_usage_percent ").append(
                ((totalMem - freeMem).toDouble() / totalMem * 100)
            ).append("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting memory metrics: ${e.message}", e)
            sb.append("# ERROR collecting memory metrics: ${e.message}\n")
        }
        return sb.toString()
    }

    private fun collectBatteryMetrics(): String {
        val sb = StringBuilder()
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            
            // 电池电量
            val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            sb.append("# HELP phone_battery_level 电池电量\n")
            sb.append("# TYPE phone_battery_level gauge\n")
            sb.append("phone_battery_level ").append(batteryLevel).append("\n")
            
            // 是否在充电
            val isCharging = bm.isCharging
            sb.append("# HELP phone_battery_charging 充电状态\n")
            sb.append("# TYPE phone_battery_charging gauge\n")
            sb.append("phone_battery_charging ").append(if (isCharging) 1 else 0).append("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting battery metrics: ${e.message}", e)
            sb.append("# ERROR collecting battery metrics: ${e.message}\n")
        }
        return sb.toString()
    }

    private fun collectSystemMetrics(): String {
        val sb = StringBuilder()
        try {
            // 设备信息
            sb.append("# HELP phone_device_info 设备信息\n")
            sb.append("# TYPE phone_device_info gauge\n")
            sb.append("phone_device_info{model=\"").append(Build.MODEL)
                .append("\",android_version=\"").append(Build.VERSION.RELEASE)
                .append("\",api_level=\"").append(Build.VERSION.SDK_INT)
                .append("\"} 1\n")

            // 屏幕信息
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds.let {
                    DisplayMetrics().apply {
                        widthPixels = it.width()
                        heightPixels = it.height()
                    }
                }
            } else {
                DisplayMetrics().also { metrics ->
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getRealMetrics(metrics)
                }
            }
            
            sb.append("# HELP phone_screen_resolution 屏幕分辨率\n")
            sb.append("# TYPE phone_screen_resolution gauge\n")
            sb.append("phone_screen_resolution{width=\"").append(metrics.widthPixels)
                .append("\",height=\"").append(metrics.heightPixels)
                .append("\"} 1\n")
            
            // 存储信息
            val statFs = StatFs(Environment.getDataDirectory().path)
            val totalBytes = statFs.totalBytes
            val availableBytes = statFs.availableBytes
            val usedBytes = totalBytes - availableBytes
            
            sb.append("# HELP phone_storage_total 总存储空间(GB)\n")
            sb.append("# TYPE phone_storage_total gauge\n")
            sb.append("phone_storage_total ").append(totalBytes.toDouble() / (1024 * 1024 * 1024)).append("\n")
            
            sb.append("# HELP phone_storage_available 可用存储空间(GB)\n")
            sb.append("# TYPE phone_storage_available gauge\n")
            sb.append("phone_storage_available ").append(availableBytes.toDouble() / (1024 * 1024 * 1024)).append("\n")
            
            sb.append("# HELP phone_storage_usage_percent 存储空间使用率\n")
            sb.append("# TYPE phone_storage_usage_percent gauge\n")
            sb.append("phone_storage_usage_percent ").append(usedBytes.toDouble() / totalBytes * 100).append("\n")

            // WiFi信息
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            val isWifiConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } else {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled && wifiManager.connectionInfo.supplicantState == SupplicantState.COMPLETED
            }
            
            sb.append("# HELP phone_wifi_connected WiFi连接状态\n")
            sb.append("# TYPE phone_wifi_connected gauge\n")
            sb.append("phone_wifi_connected ").append(if (isWifiConnected) 1 else 0).append("\n")
            
            // WiFi信号强度和速度
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)?.let { capabilities ->
                    val signalStrength = capabilities.signalStrength
                    val linkSpeed = capabilities.linkUpstreamBandwidthKbps / 1000 // 转换为Mbps
                    
                    sb.append("# HELP phone_wifi_signal_strength WiFi信号强度\n")
                    sb.append("# TYPE phone_wifi_signal_strength gauge\n")
                    sb.append("phone_wifi_signal_strength ").append(signalStrength).append("\n")
                    
                    sb.append("# HELP phone_wifi_link_speed WiFi连接速度(Mbps)\n")
                    sb.append("# TYPE phone_wifi_link_speed gauge\n")
                    sb.append("phone_wifi_link_speed ").append(linkSpeed).append("\n")
                }
            } else {
                @Suppress("DEPRECATION")
                val wifiInfo = wifiManager.connectionInfo
                val signalStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val rssi = wifiInfo.rssi
                    if (rssi <= -100) 0
                    else if (rssi >= -50) 4
                    else ((rssi + 100) / 12.5).toInt()
                } else {
                    // 自定义信号强度计算方法，替代废弃的calculateSignalLevel
                    val rssi = wifiInfo.rssi
                    when {
                        rssi <= -100 -> 0  // 非常弱
                        rssi <= -85 -> 1   // 弱
                        rssi <= -70 -> 2   // 中等
                        rssi <= -55 -> 3   // 良好
                        else -> 4          // 优秀
                    }
                }
                
                sb.append("# HELP phone_wifi_signal_strength WiFi信号强度\n")
                sb.append("# TYPE phone_wifi_signal_strength gauge\n")
                sb.append("phone_wifi_signal_strength ").append(signalStrength).append("\n")
                
                sb.append("# HELP phone_wifi_link_speed WiFi连接速度(Mbps)\n")
                sb.append("# TYPE phone_wifi_link_speed gauge\n")
                sb.append("phone_wifi_link_speed ").append(wifiInfo.linkSpeed).append("\n")
            }

            // USB连接状态
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            sb.append("# HELP phone_usb_connected USB连接状态\n")
            sb.append("# TYPE phone_usb_connected gauge\n")
            sb.append("phone_usb_connected ").append(if (usbManager.deviceList.isNotEmpty()) 1 else 0).append("\n")

        } catch (e: Exception) {
            Log.e(TAG, "Error collecting system metrics: ${e.message}", e)
            sb.append("# ERROR collecting system metrics: ${e.message}\n")
        }
        return sb.toString()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Phone Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于监控手机状态的服务"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("手机监控服务")
        .setContentText("监控服务正在运行")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()
} 