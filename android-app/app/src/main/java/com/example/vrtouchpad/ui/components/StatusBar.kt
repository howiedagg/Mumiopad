package com.example.vrtouchpad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.vrtouchpad.network.ConnState

@Composable
fun StatusBar(
    connState: ConnState,
    isKeyboardOpen: Boolean,
    onSettingsClick: () -> Unit,
    onStatusClick: () -> Unit, // 點擊狀態列膠囊觸發下拉選單
    onToggleKeyboard: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dotColor = when (connState) {
            ConnState.CONNECTED -> Color(0xFF4CAF50)      // 綠燈
            ConnState.CONNECTING, ConnState.PAIRING -> Color(0xFFFFA000) // 橘燈
            ConnState.DISCONNECTED, ConnState.AUTH_FAILED -> Color(0xFF757575) // 灰燈
        }

        val statusText = when (connState) {
            ConnState.DISCONNECTED -> "未連線"
            ConnState.CONNECTING -> "搜尋中"
            ConnState.PAIRING -> "配對中"
            ConnState.CONNECTED -> "已連線"
            ConnState.AUTH_FAILED -> "驗證失敗"
        }

        // --- 膠囊狀態晶片 (Status Pill) ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0xFF2D2D2D))
                .clickable { onStatusClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )

            Spacer(Modifier.width(4.dp))

            Text(
                text = "▾",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        // --- 右側輔助按鈕 ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (connState == ConnState.CONNECTED) {
                TextButton(onClick = onToggleKeyboard) {
                    Text(
                        text = if (isKeyboardOpen) "關閉鍵盤" else "鍵盤輸入",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            TextButton(onClick = onSettingsClick) {
                Text(
                    text = "設定",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}