package com.example.vrtouchpad.ui

import com.example.vrtouchpad.data.DiscoveredServer

/**
 * 配對相關畫面的導覽狀態。
 */
sealed class PairingNavState {
    /** 未顯示任何配對相關畫面 */
    object Hidden : PairingNavState()

    /** 裝置清單畫面：信任裝置 + 新發現裝置 */
    object DeviceList : PairingNavState()

    /** 等待電腦端點擊允許的授權畫面 */
    data class PairingWaiting(val server: DiscoveredServer) : PairingNavState()
}