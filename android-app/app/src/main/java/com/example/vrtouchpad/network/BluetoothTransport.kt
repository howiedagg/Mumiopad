package com.example.vrtouchpad.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.vrtouchpad.engine.TouchOutEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class BluetoothTransport(
    private val context: Context,
    private val onStateChange: (ConnState) -> Unit
) : ConnectionTransport {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val writeMutex = Mutex()

    @SuppressLint("MissingPermission")
    fun connectToDevice(macAddress: String) {
        val adapter = bluetoothAdapter ?: run {
            onStateChange(ConnState.DISCONNECTED)
            return
        }

        if (!adapter.isEnabled) {
            onStateChange(ConnState.DISCONNECTED)
            return
        }

        onStateChange(ConnState.CONNECTING)
        close()

        ioScope.launch {
            try {
                val device = adapter.getRemoteDevice(macAddress)
                Log.d("BT_TRANSPORT", "開始連線藍牙設備: ${device.name} ($macAddress)")

                var connectedSocket: BluetoothSocket? = null

                // 嘗試 1：使用標準 SPP UUID 管道連線 (部分系統可用)
                try {
                    val standardSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    standardSocket.connect()
                    connectedSocket = standardSocket
                    Log.d("BT_TRANSPORT", "通道嘗試：使用標準 UUID 藍牙連接成功")
                } catch (e: Exception) {
                    Log.w("BT_TRANSPORT", "標準 UUID 連接失敗，啟用多通道主動掃描機制...")
                }

                // 嘗試 2：多通道掃描器 (Channel 1 -> 5)
                if (connectedSocket == null) {
                    for (channel in 1..5) {
                        try {
                            Log.d("BT_TRANSPORT", "正在嘗試連線至藍牙 Channel: $channel")
                            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            val tempSocket = m.invoke(device, channel) as BluetoothSocket

                            // 進行連線嘗試
                            tempSocket.connect()

                            // 若無拋出異常，代表成功連上我們自己的 Python 服務
                            connectedSocket = tempSocket
                            Log.d("BT_TRANSPORT", "成功連上作用中的藍牙 Channel: $channel")
                            break
                        } catch (e: Exception) {
                            Log.w("BT_TRANSPORT", "藍牙 Channel $channel 連線遭拒 (或為系統空殼)，嘗試下一通道...")
                        }
                    }
                }

                if (connectedSocket == null) {
                    throw IOException("無法連線上 PC 的藍牙服務，請確認電腦端的 Python 程式已啟動。")
                }

                socket = connectedSocket
                outputStream = connectedSocket.outputStream

                mainHandler.post {
                    onStateChange(ConnState.CONNECTED)
                    Log.d("BT_TRANSPORT", "藍牙雙向通道建立完成，開始傳輸資料")
                }
            } catch (e: Exception) {
                Log.e("BT_TRANSPORT", "藍牙連線程序最終失敗", e)
                close()
                mainHandler.post { onStateChange(ConnState.DISCONNECTED) }
            }
        }
    }

    override fun sendEvent(event: TouchOutEvent) {
        val jsonStr = when (event) {
            is TouchOutEvent.Move -> """{"type":"move","dx":${event.dx},"dy":${event.dy}}"""
            is TouchOutEvent.Click -> """{"type":"click","button":"${event.button}","action":"${event.action}"}"""
            is TouchOutEvent.Scroll -> """{"type":"scroll","dy":${event.dy}}"""
            is TouchOutEvent.Gesture -> """{"type":"gesture","name":"${event.name}","direction":"${event.direction}"}"""
        }
        writeRaw(jsonStr + "\n")
    }

    override fun sendText(value: String) {
        val payload = """{"type":"text","value":"$value"}"""
        writeRaw(payload + "\n")
    }

    override fun sendKeypress(key: String) {
        val payload = """{"type":"keypress","key":"$key"}"""
        writeRaw(payload + "\n")
    }

    override fun sendUnpairRequest() {
        val payload = """{"type":"unpair"}"""
        writeRaw(payload + "\n")
    }

    private fun writeRaw(data: String) {
        val out = outputStream ?: return
        ioScope.launch {
            writeMutex.withLock {
                try {
                    out.write(data.toByteArray(Charsets.UTF_8))
                    out.flush()
                } catch (e: IOException) {
                    Log.e("BT_TRANSPORT", "藍牙發送失敗，中斷連線", e)
                    close()
                    mainHandler.post { onStateChange(ConnState.DISCONNECTED) }
                }
            }
        }
    }

    override fun close() {
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }
}