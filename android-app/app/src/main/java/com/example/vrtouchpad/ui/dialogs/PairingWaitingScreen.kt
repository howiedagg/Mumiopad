package com.example.vrtouchpad.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.vrtouchpad.data.DiscoveredServer

/**
 * 等待授權授權畫面。
 * 取代原有需要手動輸入密碼的介面，提供零輸入、直覺化的一鍵體驗。
 */
@Composable
fun PairingWaitingScreen(
    server: DiscoveredServer,
    pairingError: String?,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("配對請求已發送") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "請在電腦「${server.name}」上顯示的視窗中點擊「是」，以允許此裝置連線並控制滑鼠與鍵盤。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
                Spacer(Modifier.height(24.dp))

                if (pairingError == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = pairingError,
                        color = Color(0xFFEF5350),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        },
    )
}