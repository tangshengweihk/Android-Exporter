package com.example.phonemonitor

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.RandomAccessFile
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private var isServiceRunningState: Boolean = false

    companion object {
        private const val PROC_STAT_FILE = "/proc/stat"
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhoneMonitorApp()
        }
    }

    @Composable
    private fun PhoneMonitorApp() {
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                MonitorScreen()
            }
        }
    }

    @Composable
    private fun MonitorScreen() {
        var serviceRunning by rememberSaveable { mutableStateOf(isServiceRunningState) }
        var cpuUsage by rememberSaveable { mutableDoubleStateOf(0.0) }
        var memoryUsage by rememberSaveable { mutableDoubleStateOf(0.0) }
        var batteryLevel by rememberSaveable { mutableIntStateOf(0) }
        var isCharging by rememberSaveable { mutableStateOf(false) }
        var isUsbConnected by rememberSaveable { mutableStateOf(false) }
        var isEthernetConnected by rememberSaveable { mutableStateOf(false) }
        
        // 新增状态变量
        var totalStorage by rememberSaveable { mutableLongStateOf(0L) }
        var availableStorage by rememberSaveable { mutableLongStateOf(0L) }
        var screenWidth by rememberSaveable { mutableIntStateOf(0) }
        var screenHeight by rememberSaveable { mutableIntStateOf(0) }
        var screenDensity by rememberSaveable { mutableFloatStateOf(0f) }
        var deviceModel by rememberSaveable { mutableStateOf("") }
        var androidVersion by rememberSaveable { mutableStateOf("") }
        var processorInfo by rememberSaveable { mutableStateOf("") }
        var totalRam by rememberSaveable { mutableLongStateOf(0L) }
        var availableRam by rememberSaveable { mutableLongStateOf(0L) }
        
        // 新增WiFi状态
        var wifiEnabled by rememberSaveable { mutableStateOf(false) }
        var wifiStrength by rememberSaveable { mutableIntStateOf(0) }
        var wifiLinkSpeed by rememberSaveable { mutableIntStateOf(0) }
        var isWifiConnected by rememberSaveable { mutableStateOf(false) }

        var isHeadsetConnected by rememberSaveable { mutableStateOf(false) }

        // 定期更新数据
        LaunchedEffect(serviceRunning) {
            while (serviceRunning) {
                try {
                    // 更新CPU使用率
                    val reader = RandomAccessFile(PROC_STAT_FILE, "r")
                    val cpuLine = reader.readLine()
                    reader.close()
                    val cpuData = cpuLine.split("\\s+".toRegex()).drop(1)
                    val user = cpuData[0].toLong()
                    val nice = cpuData[1].toLong()
                    val system = cpuData[2].toLong()
                    val idle = cpuData[3].toLong()
                    val total = user + nice + system + idle
                    cpuUsage = max(0.0, (1 - idle.toDouble() / total) * 100)

                    // 更新内存使用情况
                    val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    activityManager.getMemoryInfo(memInfo)
                    totalRam = memInfo.totalMem
                    availableRam = memInfo.availMem
                    memoryUsage = ((totalRam - availableRam).toDouble() / totalRam * 100)

                    // 更新电池和充电信息
                    val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                    batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    isCharging = bm.isCharging

                    // 更新USB连接状态
                    val usbManager = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
                    isUsbConnected = usbManager.deviceList.isNotEmpty()

                    // 更新网络连接状态
                    val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val activeNetwork = cm.activeNetwork
                    isEthernetConnected = if (activeNetwork != null) {
                        cm.getNetworkCapabilities(activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                    } else {
                        false
                    }

                    // 更新耳机连接状态
                    val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        isHeadsetConnected = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                            device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            device.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        isHeadsetConnected = am.isWiredHeadsetOn
                    }

                    // 更新WiFi状态
                    val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                    wifiEnabled = wm.isWifiEnabled
                    if (wifiEnabled) {
                        val (connected, rssi, speed) = updateWifiInfo(wm)
                        isWifiConnected = connected
                        wifiStrength = getSignalLevel(rssi)
                        wifiLinkSpeed = speed
                    } else {
                        isWifiConnected = false
                        wifiStrength = 0
                        wifiLinkSpeed = 0
                    }

                    // 更新存储信息
                    val stat = StatFs(Environment.getExternalStorageDirectory().path)
                    totalStorage = stat.totalBytes
                    availableStorage = stat.availableBytes

                    // 更新屏幕信息
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getMetrics(metrics)
                    screenWidth = metrics.widthPixels
                    screenHeight = metrics.heightPixels
                    screenDensity = metrics.density

                    // 更新设备信息
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                    androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                    processorInfo = Build.HARDWARE

                } catch (e: Exception) {
                    Log.e(TAG, "Error updating metrics: ${e.message}", e)
                }
                delay(1000)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                isServiceRunningState = serviceRunning
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "手机监控服务",
                style = MaterialTheme.typography.headlineMedium
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            if (serviceRunning) {
                Card(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        // 设备基本信息
                        Text(
                            text = "设备型号: $deviceModel",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "系统版本: $androidVersion",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "处理器: $processorInfo",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 性能信息
                        Text(
                            text = "CPU使用率: %.1f%%".format(cpuUsage),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "内存使用: ${formatSize(totalRam - availableRam)} / ${formatSize(totalRam)} (%.1f%%)".format(memoryUsage),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "存储空间: ${formatSize(availableStorage)} / ${formatSize(totalStorage)} 可用",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 屏幕信息
                        Text(
                            text = "屏幕分辨率: ${screenWidth}x${screenHeight}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "屏幕密度: ${screenDensity}x",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 电池信息
                        Text(
                            text = "电池电量: $batteryLevel%",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "充电状态: ${if (isCharging) "充电中" else "未充电"}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 连接状态
                        Text(
                            text = "USB状态: ${if (isUsbConnected) "已连接" else "未连接"}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "网线状态: ${if (isEthernetConnected) "已连接" else "未连接"}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "耳机状态: ${if (isHeadsetConnected) "已连接" else "未连接"}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // WiFi信息
                        Text(
                            text = "WiFi状态: ${if (wifiEnabled) "已开启" else "已关闭"}${if (isWifiConnected) "（已连接）" else ""}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (wifiEnabled && isWifiConnected) {
                            Text(
                                text = "信号强度: $wifiStrength/4",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "连接速度: ${wifiLinkSpeed}Mbps",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (!serviceRunning) {
                        if (!hasUsageStatsPermission()) {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        } else {
                            startMonitorService()
                            serviceRunning = true
                            isServiceRunningState = true
                        }
                    } else {
                        stopMonitorService()
                        serviceRunning = false
                        isServiceRunningState = false
                    }
                }
            ) {
                Text(text = if (serviceRunning) "停止监控" else "开始监控")
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unit = 0
        while (size >= 1024 && unit < units.size - 1) {
            size /= 1024
            unit++
        }
        return "%.1f %s".format(size, units[unit])
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(APP_OPS_SERVICE)
            val mode = appOps?.javaClass?.getMethod("checkOpNoThrow", Int::class.java, Int::class.java, String::class.java)
                ?.invoke(appOps, 43, android.os.Process.myUid(), packageName) as? Int
            mode == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun startMonitorService() {
        try {
            val serviceIntent = Intent(this, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "Service started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service: ${e.message}", e)
            Toast.makeText(this, "启动服务失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitorService() {
        try {
            stopService(Intent(this, MonitorService::class.java))
            Log.d(TAG, "Service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service: ${e.message}", e)
            Toast.makeText(this, "停止服务失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateWifiInfo(wm: WifiManager): Triple<Boolean, Int, Int> {
        // 检查定位权限
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return Triple(false, -100, 0)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val currentNetwork = connectivityManager.activeNetwork ?: return Triple(false, -100, 0)
                val capabilities = connectivityManager.getNetworkCapabilities(currentNetwork) ?: return Triple(false, -100, 0)

                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return Triple(false, -100, 0)
                }

                val wifiInfo = capabilities.transportInfo
                if (wifiInfo !is android.net.wifi.WifiInfo) {
                    return Triple(false, -100, 0)
                }

                Triple(true, wifiInfo.rssi, wifiInfo.txLinkSpeedMbps)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting WiFi info: ${e.message}", e)
                Triple(false, -100, 0)
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val wifiInfo = wm.connectionInfo ?: return Triple(false, -100, 0)
                Triple(true, wifiInfo.rssi, wifiInfo.linkSpeed)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting WiFi info: ${e.message}", e)
                Triple(false, -100, 0)
            }
        }
    }

    private fun getSignalLevel(rssi: Int): Int {
        @Suppress("DEPRECATION")
        return WifiManager.calculateSignalLevel(rssi, 5)
    }
}