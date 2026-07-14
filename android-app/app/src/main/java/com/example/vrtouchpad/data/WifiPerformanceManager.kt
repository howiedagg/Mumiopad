package com.example.vrtouchpad.data

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * 負責在連線期間持有 WiFi 低延遲/高效能鎖，
 * 避免 WiFi 晶片在間歇性小封包流量下進入省電模式導致的週期性微卡頓。
 * 只在 CONNECTED 狀態時 acquire，斷線或 ViewModel 銷毀時 release。
 */
class WifiPerformanceManager(context: Context) {
    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var lock: WifiManager.WifiLock? = null

    fun acquire() {
        try {
            if (lock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                lock = wifiManager.createWifiLock(mode, "vrtouchpad_lowlatency_lock")
                lock?.setReferenceCounted(false)
            }
            if (lock?.isHeld == false) {
                lock?.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        try {
            if (lock?.isHeld == true) {
                lock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}