package com.example.vrtouchpad.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.SavedServer

@Composable
fun PairDialog(
    pairCode: String,
    savedServers: List<SavedServer> = emptyList(),
    unpairedDiscovered: List<DiscoveredServer> = emptyList(),
    targetServerToPair: DiscoveredServer?,
    isPairingBusy: Boolean = false,
    pairingError: String? = null,
    onSelectServer: (String) -> Unit,
    onDeleteServer: (String) -> Unit,
    onStartPairing: (DiscoveredServer) -> Unit,
    onCancelPairing: () -> Unit,
    onPairCodeChange: (String) -> Unit,
    onConfirm: (code: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isInputValid = pairCode.isNotBlank() && !isPairingBusy

    AlertDialog(
        onDismissRequest = { if (!isPairingBusy) onDismiss() },
        title = { Text(if (targetServerToPair != null) "輸入配對碼" else "選擇連線電腦") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (targetServerToPair != null) {
                    // --- 密碼輸入畫面 ---
                    // 【優化】：移除生硬 IP，在要求密碼提示中直接顯示電腦的實際主機名稱
                    Text(
                        text = "請輸入電腦 (${targetServerToPair.name}) 畫面上顯示的 6 位驗證配對碼：",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = pairCode,
                        onValueChange = onPairCodeChange,
                        label = { Text("6位配對碼") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isPairingBusy
                    )

                    if (isPairingBusy) {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在連線配對中...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    if (pairingError != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = pairingError,
                            color = Color(0xFFEF5350),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                } else {
                    // --- 多主機管理名冊列表 ---

                    // 1. 已綁定的歷史電腦（純名稱，不露 IP）
                    Text("信任的電腦：", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    if (savedServers.isEmpty()) {
                        Text("無歷史綁定紀錄", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
                            items(savedServers) { server ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF2D2D2D))
                                        .clickable { onSelectServer(server.uuid) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = server.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White
                                    )
                                    TextButton(
                                        onClick = { onDeleteServer(server.uuid) },
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
                                    ) {
                                        Text("刪除")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(Modifier.height(16.dp))

                    // 2. 搜尋到的新電腦（【優化】：完全移除 IP，直接渲染優雅的電腦實際名稱）
                    Text("新發現的未配對電腦：", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    if (unpairedDiscovered.isEmpty()) {
                        Text("未發現其他新電腦...", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
                            items(unpairedDiscovered) { server ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF0D47A1))
                                        .clickable { onStartPairing(server) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        // 直接顯示實體電腦名稱（如 Howie-PC）
                                        Text(server.name, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                        Text("未配對", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                                    }
                                    Text("配對", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (targetServerToPair != null) {
                TextButton(
                    onClick = { onConfirm(pairCode.trim()) },
                    enabled = isInputValid
                ) { Text("確認配對") }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (targetServerToPair != null) {
                        onCancelPairing()
                    } else {
                        onDismiss()
                    }
                },
                enabled = !isPairingBusy
            ) {
                Text(if (targetServerToPair != null) "返回" else "關閉")
            }
        },
    )
}