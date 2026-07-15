package com.example.vrtouchpad.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.vrtouchpad.data.DiscoveredServer

/**
 * 輸入配對碼畫面，只負責這一件事：向使用者要 6 位數配對碼並回報配對結果。
 * 與裝置清單畫面（DeviceListScreen）完全解耦，由 PairingHost 依
 * PairingNavState 決定何時顯示，彼此互不依賴。
 */
@Composable
fun PairingCodeScreen(
    server: DiscoveredServer,
    pairCode: String,
    isPairingBusy: Boolean,
    pairingError: String?,
    onPairCodeChange: (String) -> Unit,
    onConfirm: (code: String) -> Unit,
    onBack: () -> Unit,
) {
    val isInputValid = pairCode.isNotBlank() && !isPairingBusy

    AlertDialog(
        onDismissRequest = { if (!isPairingBusy) onBack() },
        title = { Text("輸入配對碼") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "請輸入電腦 (${server.name}) 畫面上顯示的 6 位驗證配對碼：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = pairCode,
                    onValueChange = onPairCodeChange,
                    label = { Text("6位配對碼") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isPairingBusy,
                )

                if (isPairingBusy) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "正在連線配對中...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (pairingError != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = pairingError,
                        color = Color(0xFFEF5350),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pairCode.trim()) }, enabled = isInputValid) {
                Text("確認配對")
            }
        },
        dismissButton = {
            TextButton(onClick = onBack, enabled = !isPairingBusy) { Text("返回") }
        },
    )
}