package com.example.vrtouchpad

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

import com.example.vrtouchpad.ui.TouchpadViewModel
import com.example.vrtouchpad.ui.components.StatusBar
import com.example.vrtouchpad.ui.components.InvisibleKeyboardInput
import com.example.vrtouchpad.ui.components.Touchpad

import com.example.vrtouchpad.ui.dialogs.PairDialog
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
    val showPairDialog by viewModel.showPairDialog.collectAsState()
    val isKeyboardActive by viewModel.isKeyboardActive.collectAsState()

    val mouseSpeed by viewModel.mouseSpeed.collectAsState()
    val scrollSpeed by viewModel.scrollSpeed.collectAsState()
    val reverseScroll by viewModel.reverseScroll.collectAsState()

    val pairCodeInput by viewModel.pairCodeInput.collectAsState()

    val unpairedDiscovered by viewModel.unpairedDiscovered.collectAsState()
    val savedServers by viewModel.savedServers.collectAsState()
    val isPairingBusy by viewModel.isPairingBusy.collectAsState()
    val pairingError by viewModel.pairingError.collectAsState()
    val targetServerToPair by viewModel.targetServerToPair.collectAsState()

    var showSettings by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
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

    // 【新增】：攔截 Android 系統原生返回按鍵與手勢
    // 只有在與電腦連線成功 (CONNECTED) 時此攔截器才會啟用
    BackHandler(enabled = connState == ConnState.CONNECTED) {
        if (isKeyboardActive) {
            // 若虛擬鍵盤正在顯示，返回鍵優先關閉鍵盤
            viewModel.setKeyboardActive(false)
        } else {
            // 鍵盤關閉狀態下，將返回事件轉化為對應 PC 端的上一頁操作
            viewModel.wsClient.sendKeypress("BROWSER_BACK")
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            StatusBar(
                connState = connState,
                isKeyboardOpen = isKeyboardActive,
                onSettingsClick = { showSettings = true },
                onStatusClick = { viewModel.openServerSelector() },
                onToggleKeyboard = { viewModel.toggleKeyboard() }
            )

            AnimatedVisibility(
                visible = connState != ConnState.CONNECTED && unpairedDiscovered.isNotEmpty()
            ) {
                val targetServer = unpairedDiscovered.firstOrNull()
                if (targetServer != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0D47A1))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("發現附近新電腦", style = MaterialTheme.typography.titleSmall, color = Color.White)
                            Text(targetServer.name, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                        }
                        Button(
                            onClick = { viewModel.triggerPairing(targetServer) }
                        ) {
                            Text("配對連線")
                        }
                    }
                }
            }

            InvisibleKeyboardInput(
                active = isKeyboardActive && connState == ConnState.CONNECTED,
                onSendText = { viewModel.wsClient.sendText(it) },
                onSendKey = { viewModel.wsClient.sendKeypress(it) },
                onKeyboardDismissed = { viewModel.setKeyboardActive(false) }
            )

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

        if (showPairDialog) {
            PairDialog(
                pairCode = pairCodeInput,
                savedServers = savedServers,
                unpairedDiscovered = unpairedDiscovered,
                targetServerToPair = targetServerToPair,
                isPairingBusy = isPairingBusy,
                pairingError = pairingError,
                onSelectServer = { uuid -> viewModel.selectServer(uuid) },
                onDeleteServer = { uuid -> viewModel.deleteServer(uuid) },
                onStartPairing = { server -> viewModel.triggerPairing(server) },
                onCancelPairing = { viewModel.cancelPairing() },
                onPairCodeChange = { viewModel.updatePairCode(it) },
                onConfirm = { code -> viewModel.startPairing(code) },
                onDismiss = { viewModel.closeServerSelector() }
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