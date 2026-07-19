// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/network/BluetoothHidTouchpadClient.kt

package com.example.vrtouchpad.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.example.vrtouchpad.engine.TouchOutEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

class BluetoothHidTouchpadClient(private val context: Context) : ConnectionClient {

    private val mBtAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var mHidDevice: BluetoothHidDevice? = null
    private var mHostDevice: BluetoothDevice? = null

    val connectedDevice: BluetoothDevice? get() = mHostDevice

    private val _connState = MutableStateFlow(ConnState.DISCONNECTED)
    override val connState: StateFlow<ConnState> = _connState

    private val _isAppRegistered = MutableStateFlow(false)
    val isAppRegistered: StateFlow<Boolean> = _isAppRegistered

    private var mButtonState: Byte = 0x00
    private val clientScope = CoroutineScope(Dispatchers.Default + Job())

    private val HID_DESCRIPTOR_COMBO = intArrayOf(
        // === 1. 標準滑鼠 (Report ID 1) ===
        0x05, 0x01, 0x09, 0x02, 0xa1, 0x01, 0x85, 0x01, 0x09, 0x01, 0xa1, 0x00, 0x05, 0x09, 0x19, 0x01,
        0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02, 0x75, 0x05, 0x95, 0x01,
        0x81, 0x01, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7f, 0x75, 0x08,
        0x95, 0x03, 0x81, 0x06, 0xc0, 0xc0,
        // === 2. 標準鍵盤 (Report ID 2) ===
        0x05, 0x01, 0x09, 0x06, 0xa1, 0x01, 0x85, 0x02, 0x05, 0x07, 0x19, 0xe0, 0x29, 0xe7, 0x15, 0x00,
        0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02, 0x95, 0x01, 0x75, 0x08, 0x81, 0x01, 0x95, 0x06,
        0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xc0,
        // === 3. 多媒體消費性按鍵 (Report ID 3) ===
        0x05, 0x0c, 0x09, 0x01, 0xa1, 0x01, 0x85, 0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03,
        0x09, 0xe9, 0x09, 0xea, 0x09, 0xe2, 0x81, 0x02, 0x95, 0x01, 0x75, 0x05, 0x81, 0x01, 0xc0
    ).map { it.toByte() }.toByteArray()

    private val mQosSettings = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
        800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
    )

    private val mProfileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE && proxy is BluetoothHidDevice) {
                mHidDevice = proxy

                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                mHidDevice = null
                _isAppRegistered.value = false
                _connState.value = ConnState.DISCONNECTED
            }
        }
    }

    private val mCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            Log.d("BT_HID", "onAppStatusChanged: 註冊狀態 = $registered")
            _isAppRegistered.value = registered
            if (registered) {
                checkCurrentConnections()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mHostDevice = device
                    _connState.value = ConnState.CONNECTED
                    Log.d("BT_HID", "已連接至設備: ${device?.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    mHostDevice = null
                    _connState.value = ConnState.DISCONNECTED
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _connState.value = ConnState.CONNECTING
                }
            }
        }
    }

    init {
        // 💡 首次啟動，開機綁定代理
        open()
    }

    // 💡 新增 1：開啟/重新開啟藍牙服務代理與註冊流程
    fun open() {
        if (mHidDevice == null) {
            _isAppRegistered.value = false
            mBtAdapter?.getProfileProxy(context, mProfileListener, BluetoothProfile.HID_DEVICE)
        } else {
            // 如果 Proxy 還在，直接嘗試重新註冊 App，避免背景切回後 SDP 註冊丟失
            registerHidApp()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Mumiopad Combo",
            "Mumiopad Virtual Input Device",
            "Mumiopad",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HID_DESCRIPTOR_COMBO
        )
        mHidDevice?.registerApp(
            sdpSettings, null, mQosSettings,
            Executors.newSingleThreadExecutor(), mCallback
        )
    }

    @SuppressLint("MissingPermission")
    private fun checkCurrentConnections() {
        val hid = mHidDevice ?: return
        val connectedDevices = hid.getDevicesMatchingConnectionStates(
            intArrayOf(BluetoothProfile.STATE_CONNECTED)
        )
        if (connectedDevices.isNotEmpty()) {
            mHostDevice = connectedDevices[0]
            _connState.value = ConnState.CONNECTED
            Log.d("BT_HID", "同步：檢測到已建立的藍牙連線: ${mHostDevice?.address}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendMouseReport(dx: Byte, dy: Byte, wheel: Byte) {
        val device = mHostDevice
        val hid = mHidDevice
        if (device != null && hid != null) {
            val report = byteArrayOf(mButtonState, dx, dy, wheel)
            hid.sendReport(device, 1, report)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendKeyboardReport(modifiers: Byte, key: Byte) {
        val device = mHostDevice
        val hid = mHidDevice
        if (device != null && hid != null) {
            val report = byteArrayOf(modifiers, 0, key, 0, 0, 0, 0, 0)
            hid.sendReport(device, 2, report)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendConsumerReport(controlMask: Byte) {
        val device = mHostDevice
        val hid = mHidDevice
        if (device != null && hid != null) {
            val report = byteArrayOf(controlMask)
            hid.sendReport(device, 3, report)
        }
    }

    override fun sendEvent(event: TouchOutEvent) {
        when (event) {
            is TouchOutEvent.Move -> {
                val dxByte = event.dx.coerceIn(-127f, 127f).toInt().toByte()
                val dyByte = event.dy.coerceIn(-127f, 127f).toInt().toByte()
                sendMouseReport(dxByte, dyByte, 0)
            }
            is TouchOutEvent.Scroll -> {
                val wheelByte = event.dy.coerceIn(-127f, 127f).toInt().toByte()
                sendMouseReport(0, 0, wheelByte)
            }
            is TouchOutEvent.Click -> {
                val bitMask: Byte = when (event.button) {
                    "left" -> 0x01
                    "right" -> 0x02
                    "middle" -> 0x04
                    else -> 0x00
                }
                when (event.action) {
                    "down" -> {
                        mButtonState = (mButtonState.toInt() or bitMask.toInt()).toByte()
                        sendMouseReport(0, 0, 0)
                    }
                    "up" -> {
                        mButtonState = (mButtonState.toInt() and bitMask.toInt().inv()).toByte()
                        sendMouseReport(0, 0, 0)
                    }
                    "click" -> {
                        clientScope.launch {
                            mButtonState = (mButtonState.toInt() or bitMask.toInt()).toByte()
                            sendMouseReport(0, 0, 0)
                            delay(15)
                            mButtonState = (mButtonState.toInt() and bitMask.toInt().inv()).toByte()
                            sendMouseReport(0, 0, 0)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    override fun sendText(value: String) {
        clientScope.launch {
            for (char in value) {
                val isShift = char.isUpperCase()
                val keycode = getHidKeyCodeForChar(char.lowercaseChar())
                if (keycode != 0.toByte()) {
                    val modifiers: Byte = if (isShift) 0x02 else 0x00
                    sendKeyboardReport(modifiers, keycode)
                    delay(10)
                    sendKeyboardReport(0x00, 0x00)
                    delay(10)
                }
            }
        }
    }

    override fun sendKeypress(key: String) {
        clientScope.launch {
            when (key) {
                "BACKSPACE" -> {
                    sendKeyboardReport(0x00, 0x2A.toByte())
                    delay(10)
                    sendKeyboardReport(0x00, 0x00)
                }
                "ENTER" -> {
                    sendKeyboardReport(0x00, 0x28.toByte())
                    delay(10)
                    sendKeyboardReport(0x00, 0x00)
                }
                "VOLUME_UP" -> {
                    sendConsumerReport(0x01)
                    delay(15)
                    sendConsumerReport(0x00)
                }
                "VOLUME_DOWN" -> {
                    sendConsumerReport(0x02)
                    delay(15)
                    sendConsumerReport(0x00)
                }
            }
        }
    }

    private fun getHidKeyCodeForChar(char: Char): Byte {
        return when (char) {
            in 'a'..'z' -> (char - 'a' + 4).toByte()
            in '1'..'9' -> (char - '1' + 30).toByte()
            '0' -> 39.toByte()
            ' ' -> 44.toByte()
            '\n' -> 40.toByte()
            else -> 0.toByte()
        }
    }

    // 💡 修正 2：切出背景或手動斷連時，安全關閉並釋放代理服務，符合系統背景規範
    @SuppressLint("MissingPermission")
    override fun close() {
        disconnectHost()
        runCatching { mHidDevice?.unregisterApp() }
        mBtAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, mHidDevice)
        mHidDevice = null
        _isAppRegistered.value = false
        _connState.value = ConnState.DISCONNECTED
        clientScope.coroutineContext[Job]?.cancelChildren()
    }

    @SuppressLint("MissingPermission")
    fun destroy() {
        close()
    }

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        return mBtAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val hid = mHidDevice
        if (hid != null) {
            // 1. 狀態同步防線：若系統底層已連線，直接同步
            val connectedDevices = hid.getDevicesMatchingConnectionStates(
                intArrayOf(BluetoothProfile.STATE_CONNECTED)
            )
            if (connectedDevices.any { it.address == device.address }) {
                Log.d("BT_HID", "同步：該裝置在系統層面已是連線狀態，直接同步，無需重連")
                mHostDevice = device
                _connState.value = ConnState.CONNECTED
                return
            }

            // 2. 啟動高頻探針自適應重試線
            clientScope.launch {
                var success = false
                var attempts = 0
                val maxAttempts = 3

                // 只要當前狀態不是 CONNECTED，且重試次數未滿，就繼續重試
                while (_connState.value != ConnState.CONNECTED && attempts < maxAttempts) {
                    // 安全退場閥
                    if (attempts > 0 && mHostDevice == null) {
                        Log.d("BT_HID", "使用者已取消連線，中止重試")
                        break
                    }

                    attempts++
                    mHostDevice = device
                    _connState.value = ConnState.CONNECTING

                    success = hid.connect(device)
                    Log.d("BT_HID", "嘗試主動連線至 ${device.name ?: "Device"} (第 $attempts 次嘗試): $success")

                    if (success) {
                        // 💡 修正核心：改用 100ms 高頻極速探針。最長等待 4 秒。
                        // 只要系統一回報已連線，在 0.1 秒內探針就會瞬間捕捉到，並「立刻結束重試」！
                        // 這能徹底杜絕因系統廣播排隊延遲而引發的重複重連 Bug，且提早連上會秒斷結束，不浪費一絲電量！
                        var waitTime = 0
                        while (_connState.value != ConnState.CONNECTED && waitTime < 4000) {
                            delay(100)
                            waitTime += 100
                        }

                        if (_connState.value == ConnState.CONNECTED) {
                            Log.d("BT_HID", "藍牙實體連線已建立，成功中止重試線")
                            break
                        }
                    }

                    if (_connState.value != ConnState.CONNECTED && attempts < maxAttempts) {
                        // 失敗後，等待 1.5 秒讓晶片稍微緩衝，再次發起重試
                        delay(1500)
                    }
                }

                // 💡 3. 自適應超時判定
                if (_connState.value != ConnState.CONNECTED && mHostDevice != null) {
                    Log.w("BT_HID", "藍牙自適應重試結束，所有嘗試均失敗，重置狀態")
                    _connState.value = ConnState.DISCONNECTED
                    mHostDevice = null
                }
            }
        } else {
            Log.w("BT_HID", "無法連線：HID Device 代理尚未就緒")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectHost() {
        val hid = mHidDevice
        val host = mHostDevice
        if (hid != null && host != null) {
            hid.disconnect(host)
            mHostDevice = null
            _connState.value = ConnState.DISCONNECTED
        }
    }
}