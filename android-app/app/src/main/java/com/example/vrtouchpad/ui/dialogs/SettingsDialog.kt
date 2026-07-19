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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.vrtouchpad.R

@Composable
fun SettingsDialog(
    mouseSpeed: Float,
    scrollSpeed: Float,
    reverseScroll: Boolean,
    onMouseSpeedChange: (Float) -> Unit,
    onScrollSpeedChange: (Float) -> Unit,
    onReverseScrollChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // 使用本地暫存狀態。在拖動滑桿時 UI 會維持極度流暢，按下完成時才一次性寫入儲存空間。
    var localMouseSpeed by remember { mutableStateOf(mouseSpeed) }
    var localScrollSpeed by remember { mutableStateOf(scrollSpeed) }
    var localReverseScroll by remember { mutableStateOf(reverseScroll) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column {
                // 自動將 localMouseSpeed 帶入 strings.xml 中的 %1$.1f 格式化
                Text(stringResource(R.string.settings_mouse_speed, localMouseSpeed))
                Slider(
                    value = localMouseSpeed,
                    onValueChange = { localMouseSpeed = it },
                    valueRange = 0.3f..3f,
                )
                Spacer(Modifier.height(8.dp))

                Text(stringResource(R.string.settings_scroll_speed, localScrollSpeed))
                Slider(
                    value = localScrollSpeed,
                    onValueChange = { localScrollSpeed = it },
                    valueRange = 0.3f..3f,
                )
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.settings_reverse_scroll), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = localReverseScroll,
                        onCheckedChange = { localReverseScroll = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 按下完成時才寫入，確保不會因為频繁 IO 操作導致拖曳滑桿時卡頓
                    onMouseSpeedChange(localMouseSpeed)
                    onScrollSpeedChange(localScrollSpeed)
                    onReverseScrollChange(localReverseScroll)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.settings_done))
            }
        },
    )
}