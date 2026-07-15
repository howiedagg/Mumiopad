package com.example.vrtouchpad

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(0xFF1E1E1E.toInt()))

        setContent {
            MaterialTheme {
                val context = androidx.compose.ui.platform.LocalContext.current.applicationContext

                val viewModel: TouchpadViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return TouchpadViewModel(context) as T
                        }
                    }
                )
                AppRoot(viewModel)
            }
        }
    }
}

@Composable
fun AppRoot(viewModel: TouchpadViewModel) {
    val scope = rememberCoroutineScope()

    val connState by viewModel.connState.collectAsState()
    val pairingNavState by viewModel.pairingNavState.collectAsState()
    val isKeyboardActive by viewModel.isKeyboardActive.collectAsState()

    val mouseSpeed by viewModel.mouseSpeed.collectAsState()
    val scrollSpeed by viewModel.scrollSpeed.collectAsState()
    val reverseScroll by viewModel.reverseScroll.collectAsState()

    val pairCodeInput by viewModel.pairCodeInput.collectAsState()

    val unpairedDiscovered by viewModel.unpairedDiscovered.collectAsState()
    val savedServers by viewModel.savedServers.collectAsState()
    val isPairingBusy by viewModel.isPairingBusy.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val pairingError by viewModel.pairingError.collectAsState()

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
            viewModel.wsClient.sendKeypress("BROWSER_BACK")
        }
    }

    // 【新增】：從未配對過任何電腦、且目前沒有連線、也沒有配對畫面開著時，
    // 顯示全螢幕引導，取代原本讓使用者自己猜的空白觸控板。
    val showOnboarding = savedServers.isEmpty() &&
        connState != ConnState.CONNECTED &&
        pairingNavState == PairingNavState.Hidden

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            StatusBar(
                connState = connState,
                isKeyboardOpen = isKeyboardActive,
                onSettingsClick = { showSettings = true },
                onStatusClick = { viewModel.openServerSelector() },
                onToggleKeyboard = { viewModel.toggleKeyboard() }
            )

            // 【修改】：原本固定高度的 Row 提示改為可自動收合的 snackbar，
            // 且點擊一律導向完整裝置清單，不會漏掉除了第一台以外的裝置。
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
                onSendText = { viewModel.wsClient.sendText(it) },
                onSendKey = { viewModel.wsClient.sendKeypress(it) },
                onKeyboardDismissed = { viewModel.setKeyboardActive(false) }
            )

            if (showOnboarding) {
                WelcomeOnboarding(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onFindComputer = { viewModel.openServerSelector() },
                )
            } else {
                Touchpad(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    enabled = connState == ConnState.CONNECTED,
                    mouseSpeed = mouseSpeed,
                    scrollSpeed = scrollSpeed,
                    reverseScroll = reverseScroll,
                    scope = scope,
                    onOutEvent = { viewModel.wsClient.sendEvent(it) },
                )
            }
        }

        // 【修改】：原本單一 PairDialog 改為 PairingHost，依 PairingNavState
        // 分派到 DeviceListScreen 或 PairingCodeScreen，彼此獨立不互相耦合。
        PairingHost(
            navState = pairingNavState,
            savedServers = savedServers,
            unpairedDiscovered = unpairedDiscovered,
            isScanning = isScanning,
            pairCode = pairCodeInput,
            isPairingBusy = isPairingBusy,
            pairingError = pairingError,
            onSelectSaved = { uuid -> viewModel.selectServer(uuid) },
            onDeleteSaved = { uuid -> viewModel.deleteServer(uuid) },
            onStartPairing = { server -> viewModel.triggerPairing(server) },
            onPairCodeChange = { viewModel.updatePairCode(it) },
            onConfirmPairing = { code -> viewModel.startPairing(code) },
            onBackToList = { viewModel.cancelPairing() },
            onDismiss = { viewModel.closeServerSelector() },
        )

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
