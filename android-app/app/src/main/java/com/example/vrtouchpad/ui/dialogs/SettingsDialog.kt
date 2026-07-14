package com.example.vrtouchpad.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsDialog(
    mouseSpeed: Float,
    scrollSpeed: Float,
    reverseScroll: Boolean, // 【新增】
    onMouseSpeedChange: (Float) -> Unit,
    onScrollSpeedChange: (Float) -> Unit,
    onReverseScrollChange: (Boolean) -> Unit, // 【新增】
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("觸控板設定") },
        text = {
            Column {
                Text("滑鼠移動速度: ${"%.1f".format(mouseSpeed)}x")
                Slider(
                    value = mouseSpeed,
                    onValueChange = onMouseSpeedChange,
                    valueRange = 0.3f..3f,
                )
                Spacer(Modifier.height(8.dp))

                Text("雙指滾動速度: ${"%.1f".format(scrollSpeed)}x")
                Slider(
                    value = scrollSpeed,
                    onValueChange = onScrollSpeedChange,
                    valueRange = 0.3f..3f,
                )
                Spacer(Modifier.height(16.dp))

                // 【新增】：反向滾動開關
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("反向滾動 (自然滾動)", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = reverseScroll,
                        onCheckedChange = onReverseScrollChange
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}