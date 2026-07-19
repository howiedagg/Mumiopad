package com.example.vrtouchpad.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
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

@RequiresApi(Build.VERSION_CODES.P)
class BluetoothHidTouchpadClient(private val context: Context) : ConnectionClient {

    private val mBtAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var mHidDevice: BluetoothHidDevice? = null
    private var mHostDevice: BluetoothDevice? = null

    private val _connState = MutableStateFlow(ConnState.DISCONNECTED)
    override val connState: StateFlow<ConnState> = _connState

    private var mButtonState: Byte = 0x00
    private val clientScope = CoroutineScope(Dispatchers.Default + Job())

    // 💡 藍牙 Combo 描述符：同時宣告滑鼠 (ID 1)、鍵盤 (ID 2)、消費性控制器/音量 (ID 3)
    private val HID_DESCRIPTOR_COMBO = intArrayOf(
        // === 1. 標準滑鼠 (Report ID 1) ===
        0x05, 0x01,         // Usage Page (Generic Desktop)
        0x09, 0x02,         // Usage (Mouse)
        0xa1, 0x01,         // Collection (Application)
        0x85, 0x01,         //   Report ID (1)
        0x09, 0x01,         //   Usage (Pointer)
        0xa1, 0x00,         //   Collection (Physical)
        0x05, 0x09,         //     Usage Page (Buttons)
        0x19, 0x01,         //     Usage Minimum (1)
        0x29, 0x03,         //     Usage Maximum (3)
        0x15, 0x00,         //     Logical Minimum (0)
        0x25, 0x01,         //     Logical Maximum (1)
        0x75, 0x01,         //     Report Size (1)
        0x95, 0x03,         //     Report Count (3)
        0x81, 0x02,         //     Input (Data, Variable, Absolute)
        0x75, 0x05,         //     Report Size (5)
        0x95, 0x01,         //     Report Count (1)
        0x81, 0x01,         //     Input (Constant) ; 5-bit padding
        0x05, 0x01,         //     Usage Page (Generic Desktop)
        0x09, 0x30,         //     Usage (X)
        0x09, 0x31,         //     Usage (Y)
        0x09, 0x38,         //     Usage (Wheel)
        0x15, 0x81,         //     Logical Minimum (-127)
        0x25, 0x7f,         //     Logical Maximum (127)
        0x75, 0x08,         //     Report Size (8)
        0x95, 0x03,         //     Report Count (3)
        0x81, 0x06,         //     Input (Data, Variable, Relative)
        0xc0,               //   End Collection
        0xc0,               // End Collection

        // === 2. 標準鍵盤 (Report ID 2) ===
        0x05, 0x01,         // Usage Page (Generic Desktop)
        0x09, 0x06,         // Usage (Keyboard)
        0xa1, 0x01,         // Collection (Application)
        0x85, 0x02,         //   Report ID (2)
        0x05, 0x07,         //   Usage Page (Keyboard)
        0x19, 0xe0,         //   Usage Minimum (Keyboard LeftControl)
        0x29, 0xe7,         //   Usage Maximum (Keyboard Right GUI)
        0x15, 0x00,         //   Logical Minimum (0)
        0x25, 0x01,         //   Logical Maximum (1)
        0x75, 0x01,         //   Report Size (1)
        0x95, 0x08,         //   Report Count (8)
        0x81, 0x02,         //   Input (Data, Variable, Absolute) ; Modifier keys byte
        0x95, 0x01,         //   Report Count (1)
        0x75, 0x08,         //   Report Size (8)
        0x81, 0x01,         //   Input (Constant) ; Reserved byte
        0x95, 0x06,         //   Report Count (6)
        0x75, 0x08,         //   Report Size (8)
        0x15, 0x00,         //   Logical Minimum (0)
        0x25, 0x65,         //   Logical Maximum (101)
        0x19, 0x00,         //   Usage Minimum (0)
        0x29, 0x65,         //   Usage Maximum (101)
        0x81, 0x00,         //   Input (Data, Array) ; 6 key rolls
        0xc0,               // End Collection

        // === 3. 消費性控制器/音量控制 (Report ID 3) ===
        0x05, 0x0c,         // Usage Page (Consumer)
        0x09, 0x01,         // Usage (Consumer Control)
        0xa1, 0x01,         // Collection (Application)
        0x85, 0x03,         //   Report ID (3)
        0x15, 0x00,         //   Logical Minimum (0)
        0x25, 0x01,         //   Logical Maximum (1)
        0x75, 0x01,         //   Report Size (1)
        0x95, 0x03,         //   Report Count (3)
        0x09, 0xe9,         //   Usage (Volume Increment)
        0x09, 0xea,         //   Usage (Volume Decrement)
        0x09, 0xe2,         //   Usage (Mute)
        0x81, 0x02,         //   Input (Data, Variable, Absolute)
        0x95, 0x01,         //   Report Count (1)
        0x75, 0x05,         //   Report Size (5)
        0x81, 0x01,         //   Input (Constant) ; Padding
        0xc0                // End Collection
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
                runCatching { proxy.unregisterApp() }
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                mHidDevice = null
                _connState.value = ConnState.DISCONNECTED
            }
        }
    }

    private val mCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
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
        mBtAdapter?.getProfileProxy(context, mProfileListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Mumiopad Combo",
            "Mumiopad Virtual Input Device",
            "Mumiopad",
            BluetoothHidDevice.SUBCLASS1_COMBO, // 💡 宣告為鍵盤滑鼠複合式子類 (Combo)
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
                    delay(15)
                    sendKeyboardReport(0, 0)
                    delay(15)
                }
            }
        }
    }

    override fun sendKeypress(key: String) {
        clientScope.launch {
            when (key) {
                "BACKSPACE" -> {
                    sendKeyboardReport(0, 0x2A.toByte())
                    delay(15)
                    sendKeyboardReport(0, 0)
                }
                "ENTER" -> {
                    sendKeyboardReport(0, 0x28.toByte())
                    delay(15)
                    sendKeyboardReport(0, 0)
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

    @SuppressLint("MissingPermission")
    override fun close() {
        runCatching { mHidDevice?.unregisterApp() }
        mBtAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, mHidDevice)
        _connState.value = ConnState.DISCONNECTED
        clientScope.coroutineContext[Job]?.cancelChildren()
    }

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        return mBtAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val hid = mHidDevice
        if (hid != null) {
            mHostDevice = device
            _connState.value = ConnState.CONNECTING
            hid.connect(device)
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