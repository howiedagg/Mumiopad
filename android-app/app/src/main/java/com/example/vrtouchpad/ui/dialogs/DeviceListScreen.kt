// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/ui/dialogs/DeviceListScreen.kt

package com.example.vrtouchpad.ui.dialogs

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
// 【新增】：匯入 M3 內建的極簡 Refresh 圖示
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.SavedServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    savedServers: List<SavedServer>,
    onlineSavedUuids: Set<String>,
    connectedUuid: String?,
    unpairedDiscovered: List<DiscoveredServer>,
    isScanning: Boolean,
    onSelectSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onDisconnect: () -> Unit,
    onStartPairing: (DiscoveredServer) -> Unit,
    onRescan: () -> Unit, // 【新增】：重新整理連線對話框的回呼
    onDismiss: () -> Unit,
) {
    var serverToDelete by remember { mutableStateOf<SavedServer?>(null) }
    val haptic = LocalHapticFeedback.current

    if (serverToDelete != null) {
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text("解除配對") },
            text = { Text("確定要解除配對並刪除「${serverToDelete?.name}」嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        serverToDelete?.let { onDeleteSaved(it.uuid) }
                        serverToDelete = null
                    }
                ) { Text("確定解除", color = Color(0xFFEF5350)) }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) { Text("取消") }
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
                Text("選擇電腦")
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    // 【新增】：極簡無文字的「重新整理 🔄」IconButton，只在非掃描時悄悄出現
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onRescan()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "重新整理",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        text = {
            val hasAny = savedServers.isNotEmpty() || unpairedDiscovered.isNotEmpty()
            if (!hasAny) {
                Text(
                    text = if (isScanning) "搜尋附近電腦中..." else "未發現任何電腦，請確認電腦端伺服器已開啟",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
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
                            else -> "離線"
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
                                    Text("解除配對", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        ) {
                            DeviceRow(
                                name = server.name,
                                subtitle = subtitle,
                                dotColor = dotColor,
                                onClick = {
                                    if (!isConnected) {
                                        onSelectSaved(server.uuid)
                                    }
                                },
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
                        DeviceRow(
                            name = server.name,
                            subtitle = "未配對",
                            dotColor = Color(0xFFFFA000),
                            onClick = { onStartPairing(server) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("關閉") }
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