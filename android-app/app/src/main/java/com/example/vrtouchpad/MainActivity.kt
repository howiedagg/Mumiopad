package com.example.vrtouchpad

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
import androidx.compose.runtime.rememberCoroutineScope // 修正問題 1：補上遺漏的導入
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.graphics.drawable.toDrawable // 修正問題 2：導入 KTX 擴充函式
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

        // 修正問題 2：使用 KTX 的 toDrawable() 來設定背景顏色
        window.setBackgroundDrawable(0xFF1E1E1E.toInt().toDrawable())

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
    val scope = rememberCoroutineScope() // 這裡現在可正常解析，不會再出現 Unresolved reference 錯誤

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

        PairingHost(
            navState = pairingNavState,
            savedServers = savedServers,
            unpairedDiscovered = unpairedDiscovered,
            isScanning = isScanning,
            isPairingBusy = isPairingBusy,
            pairingError = pairingError,
            onSelectSaved = { uuid -> viewModel.selectServer(uuid) },
            onDeleteSaved = { uuid -> viewModel.deleteServer(uuid) },
            onStartPairing = { server -> viewModel.triggerPairing(server) },
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