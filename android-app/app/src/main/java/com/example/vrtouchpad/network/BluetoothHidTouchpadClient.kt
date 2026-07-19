package com.example.vrtouchpad.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile // 💡 修正打字錯誤
import android.content.Context
import android.util.Log
import com.example.vrtouchpad.engine.TouchOutEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import kotlin.experimental.and
import kotlin.experimental.or

class BluetoothHidTouchpadClient(private val context: Context) : ConnectionClient {

    private val mBtAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var mHidDevice: BluetoothHidDevice? = null
    private var mHostDevice: BluetoothDevice? = null

    private val _connState = MutableStateFlow(ConnState.DISCONNECTED)
    override val connState: StateFlow<ConnState> = _connState

    // 儲存當前實體滑鼠按鍵狀態（第0位: 左鍵, 第1位: 右鍵, 第2位: 中鍵）
    private var mButtonState: Byte = 0x00

    // 標準藍牙 HID 3 鍵滾輪滑鼠的 SDP 描述符描述檔
    private val HID_DESCRIPTOR_MOUSE = byteArrayOf(
        0x05, 0x01,         // Usage Page (Generic Desktop)
        0x09, 0x02,         // Usage (Mouse)
        0xa1, 0x01,         // Collection (Application)
        0x09, 0x01,         //   Usage (Pointer)
        0xa1, 0x00,         //   Collection (Physical)
        0x05, 0x09,         //     Usage Page (Button)
        0x19, 0x01,         //     Usage Minimum (Button 1)
        0x29, 0x03,         //     Usage Maximum (Button 3)
        0x15, 0x00,         //     Logical Minimum (0)
        0x25, 0x01,         //     Logical Maximum (1)
        0x95, 0x03,         //     Report Count (3)
        0x75, 0x01,         //     Report Size (1)
        0x81, 0x02,         //     Input (Data, Variable, Absolute)
        0x95, 0x01,         //     Report Count (1)
        0x75, 0x05,         //     Report Size (5)
        0x81, 0x01,         //     Input (Constant)
        0x05, 0x01,         //     Usage Page (Generic Desktop)
        0x09, 0x30,         //     Usage (X)
        0x09, 0x31,         //     Usage (Y)
        0x15, 0x81,         //     Logical Minimum (-127)
        0x25, 0x7f,         //     Logical Maximum (127)
        0x75, 0x08,         //     Report Size (8)
        0x95, 0x02,         //     Report Count (2)
        0x81, 0x06,         //     Input (Data, Variable, Relative)
        0x09, 0x38,         //     Usage (Wheel)
        0x15, 0x81,         //     Logical Minimum (-127)
        0x25, 0x7f,         //     Logical Maximum (127)
        0x75, 0x08,         //     Report Size (8)
        0x95, 0x01,         //     Report Count (1)
        0x81, 0x06,         //     Input (Data, Variable, Relative)
        0xc0,               //   End Collection
        0xc0                // End Collection
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
                _connState.value = ConnState.DISCONNECTED
            }
        }
    }

    private val mCallback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mHostDevice = device
                    _connState.value = ConnState.CONNECTED
                    Log.d("BT_HID", "已成功連接至 VR 設備: ${device?.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    mHostDevice = null
                    _connState.value = ConnState.DISCONNECTED
                    Log.d("BT_HID", "已與 VR 設備中斷連線")
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _connState.value = ConnState.CONNECTING
                }
            }
        }
    }

    init {
        // 向系統註冊並取得藍牙 HID 代理物件
        mBtAdapter?.getProfileProxy(context, mProfileListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Mumiopad Mouse",
            "Mumiopad Virtual Touchpad",
            "Mumiopad",
            BluetoothHidDevice.SUBCLASS1_MOUSE, // 宣告為滑鼠子類
            HID_DESCRIPTOR_MOUSE
        )
        // 註冊本 App 為實體藍牙設備
        mHidDevice?.registerApp(
            sdpSettings,
            null,
            null,
            Executors.newSingleThreadExecutor(),
            mCallback
        )
    }

    /**
     * 發送藍牙 HID 滑鼠報告封包（Report ID 預設為 0）
     */
    @SuppressLint("MissingPermission")
    private fun sendMouseReport(dx: Byte, dy: Byte, wheel: Byte) {
        val device = mHostDevice
        val hid = mHidDevice
        if (device != null && hid != null) {
            val report = byteArrayOf(mButtonState, dx, dy, wheel)
            hid.sendReport(device, 0, report)
        }
    }

    override fun sendEvent(event: TouchOutEvent) {
        when (event) {
            is TouchOutEvent.Move -> {
                // 將 Float 位移量限縮於標準滑鼠範圍 (-127 到 127) 內並轉為 Byte
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
                    "left" -> 0x01   // 第 0 位
                    "right" -> 0x02  // 第 1 位
                    "middle" -> 0x04 // 第 2 位
                    else -> 0x00
                }

                when (event.action) {
                    "down" -> {
                        mButtonState = mButtonState or bitMask
                        sendMouseReport(0, 0, 0)
                    }
                    "up" -> {
                        mButtonState = mButtonState and bitMask.inv()
                        sendMouseReport(0, 0, 0)
                    }
                    "click" -> {
                        // 模擬完整的一次點擊與釋放，確保零失誤
                        mButtonState = mButtonState or bitMask
                        sendMouseReport(0, 0, 0)
                        Thread.sleep(15) // 給予設備微小反應時間
                        mButtonState = mButtonState and bitMask.inv()
                        sendMouseReport(0, 0, 0)
                    }
                }
            }
            else -> {
                // VR 設備原生不支援純鍵盤文字（Zoom, Keypress等），
                // 藍牙 HID 滑鼠模式下在此不進行多餘處理，維持低耦合乾淨度。
            }
        }
    }

    override fun sendText(value: String) {}
    override fun sendKeypress(key: String) {}

    @SuppressLint("MissingPermission")
    override fun close() {
        mBtAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, mHidDevice)
        _connState.value = ConnState.DISCONNECTED
    }

    /**
     * 獲取系統中已配對（Bonded）的藍牙裝置名單
     */
    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        return mBtAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    /**
     * 主動向指定的已配對裝置（例如 VR 設備）發起安全連線
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val hid = mHidDevice
        if (hid != null) {
            mHostDevice = device
            _connState.value = ConnState.CONNECTING
            // 主動調用系統連線方法
            val success = hid.connect(device)
            Log.d("BT_HID", "嘗試主動連線至 ${device.name ?: "Device"}: $success")
        } else {
            Log.w("BT_HID", "無法連線：HID Device 代理尚未就緒")
        }
    }

    /**
     * 主動斷開與當前主機的連線
     */
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