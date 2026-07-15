package com.example.vrtouchpad.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.SavedServer

/**
 * 裝置清單畫面：信任裝置與新發現裝置合併呈現在同一個清單中，
 * 用色點區分身分（綠＝已信任、藍＝未配對），而不是像原本 PairDialog
 * 那樣切成兩個各自標題的獨立區塊，減少使用者需要理解的視覺分區。
 */
@Composable
fun DeviceListScreen(
    savedServers: List<SavedServer>,
    unpairedDiscovered: List<DiscoveredServer>,
    isScanning: Boolean,
    onSelectSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onStartPairing: (DiscoveredServer) -> Unit,
    onDismiss: () -> Unit,
) {
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
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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
                    items(savedServers) { server ->
                        DeviceRow(
                            name = server.name,
                            dotColor = Color(0xFF4CAF50),
                            trailingLabel = "刪除",
                            trailingColor = Color(0xFFEF5350),
                            onClick = { onSelectSaved(server.uuid) },
                            onTrailingClick = { onDeleteSaved(server.uuid) },
                        )
                    }
                    items(unpairedDiscovered) { server ->
                        DeviceRow(
                            name = server.name,
                            subtitle = "未配對",
                            dotColor = Color(0xFF42A5F5),
                            trailingLabel = "配對",
                            trailingColor = Color.White,
                            onClick = { onStartPairing(server) },
                            onTrailingClick = { onStartPairing(server) },
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

@Composable
private fun DeviceRow(
    name: String,
    dotColor: Color,
    trailingLabel: String,
    trailingColor: Color,
    onClick: () -> Unit,
    onTrailingClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2D2D2D))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                }
            }
        }
        TextButton(onClick = onTrailingClick) {
            Text(trailingLabel, color = trailingColor)
        }
    }
}
