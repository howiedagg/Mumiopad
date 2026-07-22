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
import com.example.vrtouchpad.engine.ClickAction
import com.example.vrtouchpad.engine.GestureDirection
import com.example.vrtouchpad.engine.GestureType
import com.example.vrtouchpad.engine.MouseButton
import com.example.vrtouchpad.engine.SystemKey
import com.example.vrtouchpad.engine.TouchOutEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var mScrollAccumulator: Float = 0f
    private val clientScope = CoroutineScope(Dispatchers.Default + Job())
    private var connectJob: Job? = null
    private val keyboardMutex = Mutex() // 用於保護實體鍵盤時序序列，防止交錯與卡鍵

    private val HID_DESCRIPTOR_COMBO = intArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xa1, 0x01, 0x85, 0x01, 0x09, 0x01, 0xa1, 0x00, 0x05, 0x09, 0x19, 0x01,
        0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02, 0x75, 0x05, 0x95, 0x01,
        0x81, 0x01, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7f, 0x75, 0x08,
        0x95, 0x03, 0x81, 0x06, 0xc0, 0xc0,
        0x05, 0x01, 0x09, 0x06, 0xa1, 0x01, 0x85, 0x02, 0x05, 0x07, 0x19, 0xe0, 0x29, 0xe7, 0x15, 0x00,
        0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02, 0x95, 0x01, 0x75, 0x08, 0x81, 0x01, 0x95, 0x06,
        0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xc0,
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
            Log.d("BT_HID", "onAppStatusChanged: registered = $registered")
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
                    Log.d("BT_HID", "Connected: ${device?.address}")
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
        open()
    }

    fun open() {
        if (mHidDevice == null) {
            _isAppRegistered.value = false
            mBtAdapter?.getProfileProxy(context, mProfileListener, BluetoothProfile.HID_DEVICE)
        } else {
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
            Log.d("BT_HID", "Sync: ${mHostDevice?.address}")
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
                mScrollAccumulator = 0f
                val dxByte = event.dx.coerceIn(-127f, 127f).toInt().toByte()
                val dyByte = event.dy.coerceIn(-127f, 127f).toInt().toByte()
                sendMouseReport(dxByte, dyByte, 0)
            }
            is TouchOutEvent.Scroll -> {
                val wheelByte = event.dy.toInt().toByte()
                sendMouseReport(0, 0, wheelByte)
            }
            is TouchOutEvent.Click -> {
                mScrollAccumulator = 0f
                val bitMask: Byte = when (event.button) {
                    MouseButton.LEFT -> 0x01
                    MouseButton.RIGHT -> 0x02
                    MouseButton.MIDDLE -> 0x04
                }
                when (event.action) {
                    ClickAction.DOWN -> {
                        mButtonState = (mButtonState.toInt() or bitMask.toInt()).toByte()
                        sendMouseReport(0, 0, 0)
                    }
                    ClickAction.UP -> {
                        mButtonState = (mButtonState.toInt() and bitMask.toInt().inv()).toByte()
                        sendMouseReport(0, 0, 0)
                    }
                    ClickAction.CLICK -> {
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
            is TouchOutEvent.Zoom -> {
                val steps = event.delta.toInt()
                Log.d("BT_DEBUG", "【手勢引擎】偵測到縮放事件, steps = $steps")
                if (steps == 0) return

                val isZoomIn = steps > 0
                val loopCount = kotlin.math.abs(steps)

                clientScope.launch {
                    keyboardMutex.withLock {
                        try {
                            repeat(loopCount) {
                                if (isZoomIn) {
                                    // 模擬實體鍵盤：Win (0x08) + 數字鍵盤加號 Keypad Plus (0x57)
                                    sendKeyboardReport(0x08, 0x57.toByte())
                                    delay(80)
                                    sendKeyboardReport(0x00, 0x00)
                                } else {
                                    // 模擬實體鍵盤：Win (0x08) + 數字鍵盤減號 Keypad Minus (0x56)
                                    sendKeyboardReport(0x08, 0x56.toByte())
                                    delay(80)
                                    sendKeyboardReport(0x00, 0x00)
                                }
                                delay(40) // 每次連擊物理間隔縮短至 40ms，大幅緩解排隊塞車
                            }
                        } finally {
                            sendKeyboardReport(0x00, 0x00)
                        }
                    }
                }
            }
            is TouchOutEvent.Gesture -> {
                when {
                    event.name == GestureType.DESKTOP && (event.direction == GestureDirection.DOWN || event.direction == GestureDirection.UP) -> {
                        clientScope.launch {
                            sendKeyboardReport(0x08, 0x07.toByte())
                            delay(15)
                            sendKeyboardReport(0x00, 0x00)
                        }
                    }
                    event.name == GestureType.MULTITASK && event.direction == GestureDirection.TAP -> {
                        clientScope.launch {
                            sendKeyboardReport(0x08, 0x2B.toByte())
                            delay(15)
                            sendKeyboardReport(0x00, 0x00)
                        }
                    }
                }
            }
            is TouchOutEvent.Keypress -> {
                when (event.key) {
                    // 瀏覽器上一頁：Alt + Left Arrow
                    SystemKey.BROWSER_BACK -> {
                        clientScope.launch {
                            keyboardMutex.withLock {
                                try {
                                    sendKeyboardReport(0x04, 0x50.toByte())
                                    delay(25)
                                    sendKeyboardReport(0x00, 0x00)
                                } finally {
                                    sendKeyboardReport(0x00, 0x00)
                                }
                            }
                        }
                    }
                    // 瀏覽器下一頁：Alt + Right Arrow
                    SystemKey.BROWSER_FORWARD -> {
                        clientScope.launch {
                            keyboardMutex.withLock {
                                try {
                                    sendKeyboardReport(0x04, 0x4F.toByte())
                                    delay(25)
                                    sendKeyboardReport(0x00, 0x00)
                                } finally {
                                    sendKeyboardReport(0x00, 0x00)
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
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

    override fun sendKeypress(key: SystemKey) {
        clientScope.launch {
            when (key) {
                SystemKey.BACKSPACE -> {
                    sendKeyboardReport(0x00, 0x2A.toByte())
                    delay(10)
                    sendKeyboardReport(0x00, 0x00)
                }
                SystemKey.ENTER -> {
                    sendKeyboardReport(0x00, 0x28.toByte())
                    delay(10)
                    sendKeyboardReport(0x00, 0x00)
                }
                SystemKey.VOLUME_UP -> {
                    sendConsumerReport(0x01)
                    delay(15)
                    sendConsumerReport(0x00)
                }
                SystemKey.VOLUME_DOWN -> {
                    sendConsumerReport(0x02)
                    delay(15)
                    sendConsumerReport(0x00)
                }
                // 映射 Android 實體返回鍵 (BROWSER_BACK) 轉為實體藍牙鍵盤 Alt (0x04) + Left Arrow (0x50)
                SystemKey.BROWSER_BACK -> {
                    keyboardMutex.withLock {
                        try {
                            sendKeyboardReport(0x04, 0x50.toByte())
                            delay(25)
                            sendKeyboardReport(0x00, 0x00)
                        } finally {
                            sendKeyboardReport(0x00, 0x00)
                        }
                    }
                }
                // 映射 BROWSER_FORWARD 轉為 Alt (0x04) + Right Arrow (0x4F)
                SystemKey.BROWSER_FORWARD -> {
                    keyboardMutex.withLock {
                        try {
                            sendKeyboardReport(0x04, 0x4F.toByte())
                            delay(25)
                            sendKeyboardReport(0x00, 0x00)
                        } finally {
                            sendKeyboardReport(0x00, 0x00)
                        }
                    }
                }
                else -> Unit
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
    fun removeBond(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("removeBond")
            val result = method.invoke(device) as Boolean
            Log.d("BT_HID", "removeBond result: $result")
            result
        } catch (e: Exception) {
            Log.e("BT_HID", "removeBond error: ${e.message}", e)
            false
        }
    }

    // 內部斷開的方法（避免操作 connectJob，防止新協程自我取消）
    @SuppressLint("MissingPermission")
    private fun disconnectHostInternal() {
        val hid = mHidDevice
        val host = mHostDevice
        if (hid != null && host != null) {
            hid.disconnect(host)
            mHostDevice = null
            _connState.value = ConnState.DISCONNECTED
            Log.d("BT_HID", "已透過內部程序中斷舊連線，狀態重設為 DISCONNECTED")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val hid = mHidDevice
        if (hid != null) {
            val connectedDevices = hid.getDevicesMatchingConnectionStates(
                intArrayOf(BluetoothProfile.STATE_CONNECTED)
            )
            // 如果目標連線對象本來就已經處於連線狀態，則不重複發起連線
            if (connectedDevices.any { it.address == device.address }) {
                Log.d("BT_HID", "目標裝置已在連線狀態中")
                mHostDevice = device
                _connState.value = ConnState.CONNECTED
                return
            }

            // 先取消上一次可能還在嘗試連線的背景任務
            connectJob?.cancel()

            // 關鍵修正：若目前有其他裝置在連線中、或狀態判定為 CONNECTED，則先執行斷線與重設
            if (connectedDevices.isNotEmpty() || mHostDevice != null || _connState.value == ConnState.CONNECTED) {
                Log.d("BT_HID", "偵測到切換裝置需求：主動斷開舊裝置，再連線至新裝置: ${device.address}")
                disconnectHostInternal()
                _connState.value = ConnState.DISCONNECTED // 強制重設狀態，確保底下的 while 迴圈能順利執行
            }

            connectJob = clientScope.launch {
                // 給予藍牙晶片 500ms 的緩衝時間，處理斷線重置的硬體狀態
                delay(500)

                var success = false
                var attempts = 0
                val maxAttempts = 3

                while (_connState.value != ConnState.CONNECTED && attempts < maxAttempts) {
                    if (attempts > 0 && mHostDevice == null) {
                        Log.d("BT_HID", "Cancelled by user")
                        break
                    }

                    attempts++
                    mHostDevice = device
                    _connState.value = ConnState.CONNECTING

                    success = hid.connect(device)
                    Log.d("BT_HID", "Connect attempt $attempts: $success")

                    if (success) {
                        var waitTime = 0
                        while (_connState.value != ConnState.CONNECTED && waitTime < 4000) {
                            delay(100)
                            waitTime += 100
                        }

                        if (_connState.value == ConnState.CONNECTED) {
                            Log.d("BT_HID", "Connected successfully")
                            break
                        }
                    }

                    if (_connState.value != ConnState.CONNECTED && attempts < maxAttempts) {
                        delay(1500)
                    }
                }

                if (_connState.value != ConnState.CONNECTED && mHostDevice != null) {
                    Log.w("BT_HID", "Connect failed")
                    _connState.value = ConnState.DISCONNECTED
                    mHostDevice = null
                }
            }
        } else {
            Log.w("BT_HID", "HID Device proxy not ready")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectHost() {
        connectJob?.cancel()
        connectJob = null

        val hid = mHidDevice
        val host = mHostDevice
        if (hid != null && host != null) {
            hid.disconnect(host)
            mHostDevice = null
            _connState.value = ConnState.DISCONNECTED
        }
    }
}