package com.example.vrtouchpad.ui.dialogs

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.vrtouchpad.R
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.SavedServer
import com.example.vrtouchpad.ui.ConnectionMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    connectionMode: ConnectionMode,
    onModeChange: (ConnectionMode) -> Unit,
    savedServers: List<SavedServer>,
    onlineSavedUuids: Set<String>,
    connectedUuid: String?,
    unpairedDiscovered: List<DiscoveredServer>,
    isScanning: Boolean,
    onSelectSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onDisconnect: () -> Unit,
    onStartPairing: (DiscoveredServer) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
    // 💡 藍牙專用參數
    btBondedDevices: List<BluetoothDevice>,
    btConnState: com.example.vrtouchpad.network.ConnState,
    onConnectBt: (BluetoothDevice) -> Unit,
    onDisconnectBt: () -> Unit,
    onMakeBtDiscoverable: () -> Unit
) {
    var serverToDelete by remember { mutableStateOf<SavedServer?>(null) }
    val haptic = LocalHapticFeedback.current

    if (serverToDelete != null) {
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text(stringResource(R.string.dialog_unpair_confirm_title)) },
            text = { Text(stringResource(R.string.dialog_unpair_confirm_msg, serverToDelete?.name ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        serverToDelete?.let { onDeleteSaved(it.uuid) }
                        serverToDelete = null
                    }
                ) { Text(stringResource(R.string.dialog_confirm), color = Color(0xFFEF5350)) }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.dialog_select_computer))
                if (connectionMode == ConnectionMode.WIFI) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onRescan()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.dialog_refresh),
                                tint = Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {

                // 💡 頂部極簡雙模切換膠囊
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1E1E1E))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val isWifi = connectionMode == ConnectionMode.WIFI

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isWifi) Color(0xFF2D2D2D) else Color.Transparent)
                            .clickable { onModeChange(ConnectionMode.WIFI) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Wi-Fi (PC)", color = if (isWifi) Color.White else Color.Gray, style = MaterialTheme.typography.bodyMedium)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (!isWifi) Color(0xFF2D2D2D) else Color.Transparent)
                            .clickable { onModeChange(ConnectionMode.BLUETOOTH) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("藍牙 (VR)", color = if (!isWifi) Color.White else Color.Gray, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // 名單渲染分流
                if (connectionMode == ConnectionMode.WIFI) {
                    // --- Wi-Fi 模式名冊 ---
                    val hasAny = savedServers.isNotEmpty() || unpairedDiscovered.isNotEmpty()
                    if (!hasAny) {
                        Text(
                            text = if (isScanning) stringResource(R.string.dialog_scanning) else stringResource(R.string.dialog_no_devices),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                            items(savedServers, key = { it.uuid }) { server ->
                                val isConnected = server.uuid == connectedUuid
                                val isOnline = onlineSavedUuids.contains(server.uuid)

                                val dotColor = when {
                                    isConnected -> Color(0xFF4CAF50)
                                    isOnline -> Color(0xFF42A5F5)
                                    else -> Color(0xFF757575)
                                }

                                val subtitle = when {
                                    isConnected -> null
                                    isOnline -> null
                                    else -> stringResource(R.string.dialog_offline)
                                }

                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            serverToDelete = server
                                            false
                                        } else false
                                    }
                                )

                                SwipeToDismissBox(
                                    state = dismissState,
                                    enableDismissFromStartToEnd = false,
                                    enableDismissFromEndToStart = true,
                                    backgroundContent = {
                                        val color by animateColorAsState(
                                            when (dismissState.targetValue) {
                                                SwipeToDismissBoxValue.EndToStart -> Color(0xFFEF5350)
                                                else -> Color.Transparent
                                            }, label = "dismiss_bg"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(color)
                                                .padding(horizontal = 16.dp),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Text(stringResource(R.string.dialog_unpair), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                ) {
                                    DeviceRow(
                                        name = server.name,
                                        subtitle = subtitle,
                                        dotColor = dotColor,
                                        onClick = { if (!isConnected) onSelectSaved(server.uuid) },
                                        onLongClick = {
                                            if (isConnected) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onDisconnect()
                                            }
                                        }
                                    )
                                }
                            }
                            items(unpairedDiscovered) { server ->
                                val displayName = server.name.ifBlank { stringResource(R.string.dialog_unpaired_pc_default) }
                                DeviceRow(
                                    name = displayName,
                                    subtitle = stringResource(R.string.dialog_unpaired),
                                    dotColor = Color(0xFFFFA000),
                                    onClick = { onStartPairing(server) }
                                )
                            }
                        }
                    }
                } else {
                    // --- 藍牙 (VR) 模式名冊 ---
                    Column(modifier = Modifier.fillMaxWidth()) {

                        // 一鍵開放配對按鈕
                        Button(
                            onClick = onMakeBtDiscoverable,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.bt_make_discoverable), style = MaterialTheme.typography.bodyMedium)
                        }

                        Text(stringResource(R.string.bt_bonded_devices_title), style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                        Spacer(Modifier.height(6.dp))

                        if (btBondedDevices.isEmpty()) {
                            Text(stringResource(R.string.bt_no_bonded_devices), style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                                items(btBondedDevices) { device ->
                                    val isConnected = btConnState == com.example.vrtouchpad.network.ConnState.CONNECTED
                                    val isConnecting = btConnState == com.example.vrtouchpad.network.ConnState.CONNECTING

                                    val dotColor = when {
                                        isConnected -> Color(0xFF4CAF50)
                                        isConnecting -> Color(0xFFFFA000)
                                        else -> Color(0xFF757575)
                                    }

                                    @SuppressLint("MissingPermission")
                                    val deviceName = device.name ?: stringResource(R.string.bt_unknown_device)
                                    val statusSubtitle = when {
                                        isConnected -> stringResource(R.string.status_connected)
                                        isConnecting -> stringResource(R.string.status_connecting_dots)
                                        else -> stringResource(R.string.status_disconnected)
                                    }

                                    DeviceRow(
                                        name = deviceName,
                                        subtitle = statusSubtitle,
                                        dotColor = dotColor,
                                        onClick = {
                                            if (!isConnected && !isConnecting) {
                                                onConnectBt(device)
                                            }
                                        },
                                        onLongClick = {
                                            if (isConnected) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onDisconnectBt()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    name: String,
    dotColor: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2D2D2D))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            }
        }
    }
}