// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/ui/components/WelcomeOnboarding.kt

package com.example.vrtouchpad.ui.components

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp // 💡 補上 sp 導入，用於極簡 glyph 圖示
import com.example.vrtouchpad.R
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.network.ConnState
import com.example.vrtouchpad.ui.ConnectionMode
import com.example.vrtouchpad.ui.PairingError
import com.example.vrtouchpad.ui.PairingNavState

@Composable
fun WelcomeOnboarding(
    modifier: Modifier = Modifier,
    unpairedDiscovered: List<DiscoveredServer>,
    isScanning: Boolean,
    pairingError: PairingError?,
    pairingNavState: PairingNavState,
    onStartPairing: (DiscoveredServer) -> Unit,
    onCancelPairing: () -> Unit,
    onModeChange: (ConnectionMode) -> Unit,
    onMakeBtDiscoverable: () -> Unit,
    btBondedDevices: List<BluetoothDevice>,
    btConnState: ConnState,
    onConnectBt: (BluetoothDevice) -> Unit,
    // 💡 修正 1：傳入首次開卡標記與當前模式
    isFirstLaunch: Boolean,
    connectionMode: ConnectionMode
) {
    // 💡 修正 2：初始化邏輯。若非首次開卡，直接切入對應的引導頁，徹底消滅切換時的中斷感！
    var selectedPath by remember {
        mutableStateOf<ConnectionMode?>(
            if (isFirstLaunch) null else connectionMode
        )
    }

    // 💡 修正 3：當在 Dialog 中點擊切換模式時，同步更新 Onboarding 引導頁路由
    LaunchedEffect(connectionMode) {
        if (!isFirstLaunch) {
            selectedPath = connectionMode
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = selectedPath,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboarding_main_flow"
        ) { path ->
            when (path) {
                // ================== 1. 首頁：純視覺雙正方形卡片 ==================
                null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Mumiopad",
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White
                        )
                        Spacer(Modifier.height(48.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // 📶 Wi-Fi 按鈕卡片 (1:1 正方形)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF2D2D2D))
                                    .clickable {
                                        selectedPath = ConnectionMode.WIFI
                                        onModeChange(ConnectionMode.WIFI)
                                    }
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("📶", fontSize = 28.sp)
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = stringResource(R.string.onboarding_choice_wifi),
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }

                            // ᛒ 藍牙按鈕卡片 (1:1 正方形)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF2D2D2D))
                                    .clickable {
                                        selectedPath = ConnectionMode.BLUETOOTH
                                        onModeChange(ConnectionMode.BLUETOOTH)
                                    }
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("ᛒ", fontSize = 28.sp)
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = stringResource(R.string.onboarding_choice_bluetooth),
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                        }
                    }
                }

                // ================== 2. Wi-Fi 連線引導頁（維持原有自動重整極簡） ==================
                ConnectionMode.WIFI -> {
                    AnimatedContent(
                        targetState = pairingNavState,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "wifi_onboarding_step"
                    ) { state ->
                        when (state) {
                            is PairingNavState.PairingWaiting -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.pairing_request_sent),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    val targetName = state.server.name.ifBlank { stringResource(R.string.dialog_unpaired_pc_default) }
                                    Text(
                                        text = stringResource(R.string.pairing_waiting_instruction, targetName),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(32.dp))

                                    if (pairingError == null) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(32.dp),
                                            strokeWidth = 3.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        val errorText = when (pairingError) {
                                            PairingError.Denied -> stringResource(R.string.err_denied)
                                            PairingError.NetworkError -> stringResource(R.string.err_network)
                                            is PairingError.Unknown -> stringResource(R.string.err_fallback, pairingError.reason)
                                        }
                                        Text(
                                            text = errorText,
                                            color = Color(0xFFEF5350),
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = TextAlign.Center
                                        )
                                    }

                                    Spacer(Modifier.height(40.dp))
                                    TextButton(onClick = onCancelPairing) {
                                        Text(stringResource(R.string.dialog_cancel), color = Color.Gray)
                                    }
                                }
                            }
                            else -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (unpairedDiscovered.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.onboarding_searching),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            text = stringResource(R.string.onboarding_hint),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(Modifier.height(32.dp))
                                        if (isScanning) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.5.dp,
                                                color = Color.Gray
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = stringResource(R.string.onboarding_select_pc),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White
                                        )
                                        Spacer(Modifier.height(24.dp))

                                        LazyColumn(
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            items(unpairedDiscovered) { server ->
                                                val displayName = server.name.ifBlank { stringResource(R.string.dialog_unpaired_pc_default) }
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(Color(0xFF2D2D2D))
                                                        .clickable { onStartPairing(server) }
                                                        .padding(horizontal = 20.dp, vertical = 16.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(0xFFFFA000))
                                                    )
                                                    Spacer(Modifier.width(16.dp))
                                                    Column {
                                                        Text(
                                                            text = displayName,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = Color.White
                                                        )
                                                        Text(
                                                            text = stringResource(R.string.onboarding_click_to_pair),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = Color.Gray
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(32.dp))
                                    TextButton(onClick = { selectedPath = null }) {
                                        Text(stringResource(R.string.dialog_back), color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }

                // ================== 3. 藍牙配對頁（徹底去除步驟文字說明，直覺極簡） ==================
                ConnectionMode.BLUETOOTH -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_bt_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Spacer(Modifier.height(32.dp))

                        // 開放配對按鈕
                        Button(
                            onClick = onMakeBtDiscoverable,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.bt_make_discoverable), style = MaterialTheme.typography.bodyMedium)
                        }

                        Spacer(Modifier.height(32.dp))

                        // 已配對裝置清單（視覺風格與 Wi-Fi 一致）
                        Text(stringResource(R.string.bt_bonded_devices_title), style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))

                        if (btBondedDevices.isEmpty()) {
                            Text(stringResource(R.string.bt_no_bonded_devices), style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(btBondedDevices) { device ->
                                    val isConnecting = btConnState == ConnState.CONNECTING
                                    val isConnected = btConnState == ConnState.CONNECTED

                                    val dotColor = when {
                                        isConnected -> Color(0xFF4CAF50)
                                        isConnecting -> Color(0xFFFFA000)
                                        else -> Color(0xFF757575)
                                    }

                                    @SuppressLint("MissingPermission")
                                    val deviceName = device.name ?: stringResource(R.string.bt_unknown_device)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF2D2D2D))
                                            .clickable { if (!isConnected && !isConnecting) onConnectBt(device) }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                                        Spacer(Modifier.width(12.dp))
                                        Text(deviceName, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(40.dp))
                        TextButton(onClick = { selectedPath = null }) {
                            Text(stringResource(R.string.dialog_back), color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingChoiceCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF2D2D2D))
            .clickable { onClick() }
            .padding(20.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
    }
}