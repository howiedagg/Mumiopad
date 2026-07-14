package com.example.vrtouchpad.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.vrtouchpad.engine.TouchOutEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class ConnState { DISCONNECTED, CONNECTING, PAIRING, CONNECTED, AUTH_FAILED }

class TouchpadWebSocketClient(
    private val onStateChange: (ConnState) -> Unit,
    private val onPairSuccess: (token: String, pcName: String) -> Unit, // 【修改】：回呼新增 pcName
    private val onPairFail: (reason: String) -> Unit
) {
    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var isPairingMode = false

    fun connectForPairing(host: String, port: Int, pairCode: String) {
        isPairingMode = true
        onStateChange(ConnState.CONNECTING)
        open(host, port) { activeWs ->
            mainHandler.post { onStateChange(ConnState.PAIRING) }

            val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            val json = JSONObject().apply {
                put("type", "pair_request")
                put("code", pairCode)
                put("device_name", deviceName)
            }
            activeWs.send(json.toString())
            Log.d("WS_CLIENT", "已成功發送配對請求: $json")
        }
    }

    fun connectWithToken(host: String, port: Int, token: String) {
        isPairingMode = false
        onStateChange(ConnState.CONNECTING)
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
            onStateChange(ConnState.DISCONNECTED)
            if (isPairingMode) {
                mainHandler.post { onPairFail("network_error") }
            }
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
                mainHandler.post { onStateChange(ConnState.DISCONNECTED) }
                if (isPairingMode) {
                    mainHandler.post { onPairFail("network_error") }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WS_CLIENT", "連線關閉 (onClosed): 代碼=$code, 原因=$reason")
                ws = null
                mainHandler.post { onStateChange(ConnState.DISCONNECTED) }
                if (isPairingMode) {
                    mainHandler.post { onPairFail("network_error") }
                }
            }
        })
    }

    private fun handleIncoming(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        mainHandler.post {
            when (json.optString("type")) {
                "pair_success" -> {
                    isPairingMode = false
                    val token = json.optString("token")
                    val pcName = json.optString("pc_name", "我的電腦") // 【新增】：提取電腦實際名稱
                    onPairSuccess(token, pcName)
                }
                "pair_fail" -> {
                    isPairingMode = false
                    onPairFail(json.optString("reason"))
                }
                "auth_ok" -> onStateChange(ConnState.CONNECTED)
                "auth_fail" -> onStateChange(ConnState.AUTH_FAILED)
                "pong" -> Unit
            }
        }
    }

    private fun send(json: JSONObject) {
        val activeWs = ws
        if (activeWs != null) {
            val success = activeWs.send(json.toString())
            if (!success) {
                Log.w("WS_CLIENT", "資料發送失敗，主動判定為連線中斷")
                close()
                mainHandler.post { onStateChange(ConnState.DISCONNECTED) }
            }
        } else {
            Log.w("WS_CLIENT", "嘗試發送資料，但 WebSocket 目前未連線")
        }
    }

    fun sendEvent(event: TouchOutEvent) {
        val json = when (event) {
            is TouchOutEvent.Move -> JSONObject()
                .put("type", "move").put("dx", event.dx).put("dy", event.dy)

            is TouchOutEvent.Click -> JSONObject()
                .put("type", "click").put("button", event.button).put("action", event.action)

            is TouchOutEvent.Scroll -> JSONObject()
                .put("type", "scroll").put("dy", event.dy)

            is TouchOutEvent.Gesture -> JSONObject()
                .put("type", "gesture").put("name", event.name).put("direction", event.direction)
        }
        send(json)
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