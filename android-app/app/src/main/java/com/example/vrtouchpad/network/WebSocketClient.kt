package com.example.vrtouchpad.network

import android.util.Log
import com.example.vrtouchpad.engine.TouchOutEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

enum class ConnState { DISCONNECTED, CONNECTING, PAIRING, CONNECTED, AUTH_FAILED }

private class NoDelaySocketFactory : SocketFactory() {
    private fun Socket.applyNoDelay(): Socket = apply { tcpNoDelay = true }

    @Throws(IOException::class)
    override fun createSocket(): Socket = Socket().applyNoDelay()

    @Throws(IOException::class)
    override fun createSocket(host: String, port: Int): Socket =
        Socket(host, port).applyNoDelay()

    @Throws(IOException::class)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        Socket(host, port, localHost, localPort).applyNoDelay()

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress, port: Int): Socket =
        Socket(host, port).applyNoDelay()

    @Throws(IOException::class)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        Socket(address, port, localAddress, localPort).applyNoDelay()
}

/**
 * 狀態改用 StateFlow 廣播（[connState]），任何人都可以訂閱，
 * 彼此不需要知道對方存在：ViewModel 訂閱來更新畫面，
 * ConnectionOrchestrator 訂閱來判斷一次 dial 有沒有成功。
 *
 * 配對流程（pair_request / pair_success / pair_fail）只有 ViewModel 會用到，
 * 沿用建構子 callback 即可，不需要一併搬去 StateFlow。
 */
class TouchpadWebSocketClient(
    private val onPairSuccess: (token: String, pcName: String) -> Unit,
    private val onPairFail: (reason: String) -> Unit
) {
    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .socketFactory(NoDelaySocketFactory())
        .build()

    private val _connState = MutableStateFlow(ConnState.DISCONNECTED)
    val connState: StateFlow<ConnState> = _connState

    private var isPairingMode = false

    fun connectForPairing(host: String, port: Int) {
        isPairingMode = true
        _connState.value = ConnState.CONNECTING
        open(host, port) { activeWs ->
            _connState.value = ConnState.PAIRING

            val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            val json = JSONObject().apply {
                put("type", "pair_request")
                put("device_name", deviceName)
            }
            activeWs.send(json.toString())
            Log.d("WS_CLIENT", "已成功發送配對請求: $json")
        }
    }

    fun connectWithToken(host: String, port: Int, token: String) {
        isPairingMode = false
        _connState.value = ConnState.CONNECTING
        open(host, port) { activeWs ->
            val json = JSONObject().apply {
                put("type", "auth")
                put("token", token)
            }
            activeWs.send(json.toString())
            Log.d("WS_CLIENT", "已成功發送 Token 驗證請求")
        }
    }

    private fun open(host: String, port: Int, onOpenSend: (WebSocket) -> Unit) {
        close()

        val urlString = "ws://$host:$port"
        Log.d("WS_CLIENT", "準備連線至: $urlString")

        val request = try {
            Request.Builder().url(urlString).build()
        } catch (e: Exception) {
            Log.e("WS_CLIENT", "建立 Request 失敗", e)
            _connState.value = ConnState.DISCONNECTED
            if (isPairingMode) onPairFail("network_error")
            return
        }

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WS_CLIENT", "連線成功建立 (onOpen)")
                ws = webSocket
                onOpenSend(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WS_CLIENT", "收到伺服器訊息: $text")
                handleIncoming(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WS_CLIENT", "連線失敗 (onFailure): ${t.message}", t)
                ws = null
                _connState.value = ConnState.DISCONNECTED
                if (isPairingMode) onPairFail("network_error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WS_CLIENT", "連線關閉 (onClosed): 代碼=$code, 原因=$reason")
                ws = null
                _connState.value = ConnState.DISCONNECTED
                if (isPairingMode) onPairFail("network_error")
            }
        })
    }

    private fun handleIncoming(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "pair_success" -> {
                isPairingMode = false
                val token = json.optString("token")
                val pcName = json.optString("pc_name", "我的電腦")
                onPairSuccess(token, pcName)
            }
            "pair_fail" -> {
                isPairingMode = false
                onPairFail(json.optString("reason"))
            }
            "auth_ok" -> _connState.value = ConnState.CONNECTED
            "auth_fail" -> _connState.value = ConnState.AUTH_FAILED
            "pong" -> Unit
        }
    }

    private fun send(json: JSONObject) {
        val activeWs = ws
        if (activeWs != null) {
            val success = activeWs.send(json.toString())
            if (!success) {
                Log.w("WS_CLIENT", "資料發送失敗，主動判定為連線中斷")
                close()
                _connState.value = ConnState.DISCONNECTED
            }
        } else {
            Log.w("WS_CLIENT", "嘗試發送資料，但 WebSocket 目前未連線")
        }
    }

    fun sendEvent(event: TouchOutEvent) {
        val jsonStr = when (event) {
            is TouchOutEvent.Move ->
                """{"type":"move","dx":${event.dx},"dy":${event.dy}}"""
            is TouchOutEvent.Click ->
                """{"type":"click","button":"${event.button}","action":"${event.action}"}"""
            is TouchOutEvent.Scroll ->
                """{"type":"scroll","dy":${event.dy}}"""
            is TouchOutEvent.Gesture ->
                """{"type":"gesture","name":"${event.name}","direction":"${event.direction}"}"""
            is TouchOutEvent.Keypress ->
                """{"type":"keypress","key":"${event.key}"}"""
        }

        val activeWs = ws
        if (activeWs != null) {
            val success = activeWs.send(jsonStr)
            if (!success) {
                Log.w("WS_CLIENT", "資料發送失敗，主動判定為連線中斷")
                close()
                _connState.value = ConnState.DISCONNECTED
            }
        } else {
            Log.w("WS_CLIENT", "嘗試發送資料，但 WebSocket 目前未連線")
        }
    }

    fun sendText(value: String) {
        send(JSONObject().put("type", "text").put("value", value))
    }

    fun sendKeypress(key: String) {
        send(JSONObject().put("type", "keypress").put("key", key))
    }

    fun sendUnpairRequest() {
        send(JSONObject().put("type", "unpair"))
        Log.d("WS_CLIENT", "已向 PC 發送註銷配對請求 (unpair)")
    }

    fun close() {
        ws?.close(1000, "bye")
        ws = null
    }
}
