package com.example.vrtouchpad.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 發現新電腦時的輕量提示。展開顯示數秒後自動收合成小圓點，
 * 不會像原本的固定高度 Row 一樣長期擠壓觸控板可用區域；
 * 不論展開或收合狀態，點擊都會開啟裝置清單畫面，
 * 讓使用者從完整清單中選擇要配對哪一台（解決原本 firstOrNull()
 * 只顯示一台、其餘被吃掉的問題）。
 */
@Composable
fun DiscoveredDeviceSnackbar(
    deviceCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expandedDurationMs: Long = 4000,
) {
    var expanded by remember(deviceCount) { mutableStateOf(true) }

    LaunchedEffect(deviceCount) {
        expanded = true
        delay(expandedDurationMs)
        expanded = false
    }

    Box(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "discovered_snackbar",
        ) { isExpanded ->
            if (isExpanded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF0D47A1))
                        .clickable { onClick() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "發現 $deviceCount 台新電腦，點擊配對",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0D47A1))
                        .clickable { onClick() }
                )
            }
        }
    }
}
