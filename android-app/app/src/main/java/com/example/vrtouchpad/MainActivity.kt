// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/MainActivity.kt
package com.example.vrtouchpad

import android.Manifest
import androidx.compose.runtime.rememberCoroutineScope
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager // 💡 引入 WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge // 💡 引入 Edge-to-Edge
import androidx.activity.result.contract.ActivityResultContracts // 💡 補回這一行即可修正錯誤！
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding // 💡 引入底部導覽列避讓
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

import com.example.vrtouchpad.ui.ConnectionMode
import com.example.vrtouchpad.ui.PairingNavState
import com.example.vrtouchpad.ui.TouchpadViewModel
import com.example.vrtouchpad.ui.components.DiscoveredDeviceSnackbar
import com.example.vrtouchpad.ui.components.InvisibleKeyboardInput
import com.example.vrtouchpad.ui.components.StatusBar
import com.example.vrtouchpad.ui.components.Touchpad
import com.example.vrtouchpad.ui.components.WelcomeOnboarding

import com.example.vrtouchpad.ui.dialogs.PairingHost
import com.example.vrtouchpad.ui.dialogs.SettingsDialog
import com.example.vrtouchpad.network.ConnState

import com.example.vrtouchpad.data.PairingManager
import com.example.vrtouchpad.data.SettingsStore
import com.example.vrtouchpad.engine.SystemKey
import com.example.vrtouchpad.data.WifiPerformanceManager
import com.example.vrtouchpad.data.WifiNetworkIdProvider
import com.example.vrtouchpad.data.NetworkProfileStore

class TouchpadViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val appContext = context.applicationContext
        val pairingManager = PairingManager(appContext)
        val settingsStore = SettingsStore(appContext)
        val wifiPerformanceManager = WifiPerformanceManager(appContext)
        val wifiNetworkIdProvider = WifiNetworkIdProvider(appContext)
        val networkProfileStore = NetworkProfileStore(appContext)

        return TouchpadViewModel(
            pairingManager = pairingManager,
            settingsStore = settingsStore,
            wifiPerformanceManager = wifiPerformanceManager,
            wifiNetworkIdProvider = wifiNetworkIdProvider,
            networkProfileStore = networkProfileStore,
            context = appContext
        ) as T
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var touchpadViewModel: TouchpadViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        val btConnectGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
        } else {
            true
        }

        if (fineGranted || coarseGranted || btConnectGranted) {
            if (::touchpadViewModel.isInitialized) {
                touchpadViewModel.forceReconnect()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 💡 1. 啟用螢幕恆亮（只要 App 還在前景運作，手機就不會自動休眠黑屏）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 💡 2. 啟用滿版沉浸式設計，並指定「暗色狀態列（顯示白色文字）」確保 Mumiopad 的深灰色背景能融入最頂端
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            )
        )

        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(0xFF1E1E1E.toInt().toDrawable())
        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                val context = androidx.compose.ui.platform.LocalContext.current
                val factory = remember { TouchpadViewModelFactory(context) }
                touchpadViewModel = viewModel(factory = factory)
                AppRoot(touchpadViewModel)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::touchpadViewModel.isInitialized && touchpadViewModel.connState.value == ConnState.CONNECTED) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    touchpadViewModel.sendKeypress(SystemKey.VOLUME_UP)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    touchpadViewModel.sendKeypress(SystemKey.VOLUME_DOWN)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun AppRoot(viewModel: TouchpadViewModel) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val connState by viewModel.connState.collectAsState()
    val pairingNavState by viewModel.pairingNavState.collectAsState()
    val isKeyboardActive by viewModel.isKeyboardActive.collectAsState()

    val mouseSpeed by viewModel.mouseSpeed.collectAsState()
    val scrollSpeed by viewModel.scrollSpeed.collectAsState()
    val reverseScroll by viewModel.reverseScroll.collectAsState()

    val unpairedDiscovered by viewModel.unpairedDiscovered.collectAsState()
    val savedServers by viewModel.savedServers.collectAsState()
    val isPairingBusy by viewModel.isPairingBusy.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val pairingError by viewModel.pairingError.collectAsState()

    val connectedServerName by viewModel.connectedServerName.collectAsState()
    val connectedServerUuid by viewModel.connectedServerUuid.collectAsState()
    val onlineSavedUuids by viewModel.onlineSavedUuids.collectAsState()

    val connectionMode by viewModel.connectionMode.collectAsState()
    val btBondedDevices by viewModel.btBondedDevices.collectAsState()

    val savedBtAddresses by viewModel.savedBtAddresses.collectAsState()
    val connectedBtAddress by viewModel.connectedBtAddress.collectAsState()

    var showSettings by remember { mutableStateOf(false) }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.updateAppActive(true)
                Lifecycle.Event.ON_STOP -> viewModel.updateAppActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = connState == ConnState.CONNECTED) {
        if (isKeyboardActive) {
            viewModel.setKeyboardActive(false)
        } else {
            viewModel.sendKeypress(SystemKey.BROWSER_BACK)
        }
    }

    val showOnboarding = if (connectionMode == ConnectionMode.BLUETOOTH) {
        savedBtAddresses.isEmpty() && connState != ConnState.CONNECTED
    } else {
        savedServers.isEmpty() && connState != ConnState.CONNECTED
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {

            if (!showOnboarding) {
                StatusBar(
                    connState = connState,
                    connectedServerName = connectedServerName,
                    isKeyboardOpen = isKeyboardActive,
                    onSettingsClick = { showSettings = true },
                    onStatusClick = { viewModel.openServerSelector() },
                    onToggleKeyboard = { viewModel.toggleKeyboard() }
                )
            }

            if (connState != ConnState.CONNECTED &&
                pairingNavState == PairingNavState.Hidden &&
                unpairedDiscovered.isNotEmpty() &&
                !showOnboarding
            ) {
                DiscoveredDeviceSnackbar(
                    deviceCount = unpairedDiscovered.size,
                    onClick = { viewModel.openServerSelector() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            InvisibleKeyboardInput(
                active = isKeyboardActive && connState == ConnState.CONNECTED,
                onSendText = { viewModel.sendText(it) },
                onSendKey = { viewModel.sendKeypress(it) },
                onKeyboardDismissed = { viewModel.setKeyboardActive(false) }
            )

            if (showOnboarding) {
                WelcomeOnboarding(
                    // 💡 額外加上 navigationBarsPadding()，防範底部「返回」按鈕被系統的三鍵虛擬導覽列或手勢條遮擋
                    modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
                    unpairedDiscovered = unpairedDiscovered,
                    isScanning = isScanning,
                    pairingError = pairingError,
                    pairingNavState = pairingNavState,
                    onStartPairing = { server -> viewModel.triggerPairing(server) },
                    onCancelPairing = { viewModel.cancelPairing() },
                    onModeChange = { mode -> viewModel.setConnectionMode(mode) },
                    onMakeBtDiscoverable = {
                        viewModel.disconnectBluetooth()
                        val discoverableIntent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                            putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                        }
                        context.startActivity(discoverableIntent)
                    },
                    btBondedDevices = btBondedDevices,
                    btConnState = connState,
                    onConnectBt = { device -> viewModel.connectBluetooth(device) },
                    isFirstLaunch = viewModel.isFirstLaunch,
                    connectionMode = connectionMode,
                    savedBtAddresses = savedBtAddresses,
                    connectedBtAddress = connectedBtAddress
                )
            } else {
                Touchpad(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    enabled = connState == ConnState.CONNECTED,
                    mouseSpeed = mouseSpeed,
                    scrollSpeed = scrollSpeed,
                    reverseScroll = reverseScroll,
                    scope = scope,
                    isKeyboardActive = isKeyboardActive,
                    onOutEvent = { viewModel.sendEvent(it) },
                    onToggleKeyboard = { viewModel.toggleKeyboard() }
                )
            }
        }

        if (!showOnboarding) {
            PairingHost(
                navState = pairingNavState,
                connectionMode = connectionMode,
                onModeChange = { viewModel.setConnectionMode(it) },
                savedServers = savedServers,
                onlineSavedUuids = onlineSavedUuids,
                connectedUuid = if (connectionMode == ConnectionMode.BLUETOOTH) connectedBtAddress else connectedServerUuid,
                unpairedDiscovered = unpairedDiscovered,
                isScanning = isScanning,
                isPairingBusy = isPairingBusy,
                pairingError = pairingError,
                onSelectSaved = { uuid -> viewModel.selectServer(uuid) },
                onDeleteSaved = { uuid -> viewModel.deleteServer(uuid) },
                onDisconnect = { viewModel.disconnect() },
                onStartPairing = { server -> viewModel.triggerPairing(server) },

                // 💡 之前修正過的藍牙/Wi-Fi 動態重整分流
                onRescan = {
                    if (connectionMode == ConnectionMode.BLUETOOTH) {
                        viewModel.refreshBtDevicesWithSpinner()
                    } else {
                        viewModel.startPairingScan(clearExisting = true)
                    }
                },

                onBackToList = { viewModel.cancelPairing() },
                onDismiss = { viewModel.closeServerSelector() },
                btBondedDevices = btBondedDevices,
                btConnState = connState,
                onConnectBt = { device -> viewModel.connectBluetooth(device) },
                onDisconnectBt = { viewModel.disconnectBluetooth() },
                onMakeBtDiscoverable = {
                    viewModel.disconnectBluetooth()
                    val discoverableIntent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                    }
                    context.startActivity(discoverableIntent)
                },
                savedBtAddresses = savedBtAddresses
            )
        }
        if (showSettings) {
            SettingsDialog(
                mouseSpeed = mouseSpeed,
                scrollSpeed = scrollSpeed,
                reverseScroll = reverseScroll,
                onMouseSpeedChange = { viewModel.updateMouseSpeed(it) },
                onScrollSpeedChange = { viewModel.updateScrollSpeed(it) },
                onReverseScrollChange = { viewModel.updateReverseScroll(it) },
                onDismiss = { showSettings = false }
            )
        }
    }
}