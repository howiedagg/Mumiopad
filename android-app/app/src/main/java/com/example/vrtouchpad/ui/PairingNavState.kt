package com.example.vrtouchpad.ui

import com.example.vrtouchpad.data.DiscoveredServer

/**
 * 配對相關畫面的導覽狀態。取代原本 showPairDialog(Boolean) + targetServerToPair(nullable)
 * 兩個獨立旗標的組合判斷，讓「目前該顯示哪個畫面」只有一個唯一的真相來源，
 * UI 端只需要根據這個 state render，不需要自己拼湊條件式。
 */
sealed class PairingNavState {
    /** 未顯示任何配對相關畫面（日常使用觸控板時的狀態） */
    object Hidden : PairingNavState()

    /** 裝置清單畫面：信任裝置 + 新發現裝置 */
    object DeviceList : PairingNavState()

    /** 輸入配對碼畫面，鎖定要配對的目標裝置 */
    data class EnteringCode(val server: DiscoveredServer) : PairingNavState()
}
