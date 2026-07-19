package com.example.vrtouchpad.network

import com.example.vrtouchpad.engine.TouchOutEvent
import kotlinx.coroutines.flow.StateFlow

/**
 * 統一的連線傳輸介面。
 * 讓 UI 與 ViewModel 徹底與底層傳輸協議（Wi-Fi / 藍牙）解耦。
 */
interface ConnectionClient {
    /** 供 UI 監聽的統一連線狀態流 */
    val connState: StateFlow<ConnState>

    /** 發送滑鼠、滾動、按鍵等事件 */
    fun sendEvent(event: TouchOutEvent)

    /** 發送鍵盤純文字 */
    fun sendText(value: String)

    /** 發送快捷鍵 */
    fun sendKeypress(key: String)

    /** 斷開連線並釋放資源 */
    fun close()
}