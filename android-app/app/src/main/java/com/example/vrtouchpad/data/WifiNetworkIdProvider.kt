package com.example.vrtouchpad.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

/**
 * 負責判斷「目前連的是哪個路由器」。
 *
 * 只做一件事：回傳目前 Wi-Fi 的 BSSID(路由器實體 MAC),
 * 不知道任何跟配對、連線、UI 有關的邏輯。
 *
 * 需要 ACCESS_FINE_LOCATION 權限(Android 系統限制,取得 Wi-Fi 詳細資訊必須有此權限）。
 * 若權限未授予、或系統回傳無效值，一律回傳 null，呼叫端要自行處理「不知道在哪」的情況
 * （例如：不自動連、讓使用者手動選）。
 */
class WifiNetworkIdProvider(private val context: Context) {

    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    /** 系統在沒有權限時，會回傳這個佔位 MAC，視同「拿不到」。 */
    private val INVALID_BSSID_PLACEHOLDER = "02:00:00:00:00:00"

    fun hasRequiredPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 回傳目前路由器的 BSSID（例如 "a4:12:xx:xx:xx:xx"）。
     * 拿不到（沒權限 / 沒連 Wi-Fi / 系統限制）就回傳 null。
     */
    fun getCurrentBssid(): String? {
        if (!hasRequiredPermission()) return null

        return try {
            val info = wifiManager.connectionInfo ?: return null
            val bssid = info.bssid ?: return null
            if (bssid.isBlank() || bssid == INVALID_BSSID_PLACEHOLDER) null else bssid
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
