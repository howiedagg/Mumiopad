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
import androidx.compose.ui.unit.sp
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
    isFirstLaunch: Boolean,
    connectionMode: ConnectionMode,
    savedBtAddresses: Set<String>,
    connectedBtAddress: String?
) {
    var selectedPath by remember {
        mutableStateOf<ConnectionMode?>(
            if (isFirstLaunch) null else connectionMode
        )
    }

    // 💡 統一規範：設備動態排序（🟢 已連線 -> 🟡 連線中 -> 🔵 歷史保存 -> ⚪ 未配對裝置）
    val sortedBtDevices = remember(btBondedDevices, connectedBtAddress, savedBtAddresses) {
        btBondedDevices.sortedWith(compareByDescending<BluetoothDevice> { device ->
            device.address == connectedBtAddress
        }.thenByDescending { device ->
            savedBtAddresses.contains(device.address)
        })
    }

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
                                                            .background(Color(0xFF757575)) // ⚪ 灰燈：符合規範
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

                        Button(
                            onClick = onMakeBtDiscoverable,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.bt_make_discoverable), style = MaterialTheme.typography.bodyMedium)
                        }

                        Spacer(Modifier.height(32.dp))

                        Text(stringResource(R.string.bt_bonded_devices_title), style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))

                        if (sortedBtDevices.isEmpty()) {
                            Text(stringResource(R.string.bt_no_bonded_devices), style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(sortedBtDevices, key = { it.address }) { device ->
                                    val isTarget = device.address == connectedBtAddress
                                    val isConnected = btConnState == ConnState.CONNECTED && isTarget
                                    val isConnecting = btConnState == ConnState.CONNECTING && isTarget
                                    val isSaved = savedBtAddresses.contains(device.address)

                                    val dotColor = when {
                                        isConnected -> Color(0xFF4CAF50)   // 🟢 綠燈：已連線
                                        isConnecting -> Color(0xFFFFA000)  // 🟡 橘燈：連線中
                                        isSaved -> Color(0xFF42A5F5)       // 🔵 藍燈：歷史配對
                                        else -> Color(0xFF757575)          // ⚪ 灰燈：未配對
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