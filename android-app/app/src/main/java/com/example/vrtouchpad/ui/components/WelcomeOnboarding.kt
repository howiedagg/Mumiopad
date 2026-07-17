// android-app/app/src/main/java/com/example/vrtouchpad/ui/components/WelcomeOnboarding.kt
package com.example.vrtouchpad.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.ui.PairingNavState

@Composable
fun WelcomeOnboarding(
    modifier: Modifier = Modifier,
    unpairedDiscovered: List<DiscoveredServer>,
    isScanning: Boolean,
    isPairingBusy: Boolean,
    pairingError: String?,
    pairingNavState: PairingNavState,
    onStartPairing: (DiscoveredServer) -> Unit,
    onCancelPairing: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = pairingNavState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboarding_flow"
        ) { state ->
            when (state) {
                is PairingNavState.PairingWaiting -> {
                    // 階段三：已向電腦發送連線請求
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "已向電腦發送連線請求",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "請在電腦螢幕上點選「是」以允許連線",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(32.dp))

                        if (pairingError == null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = pairingError,
                                color = Color(0xFFEF5350),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(Modifier.height(40.dp))
                        TextButton(onClick = onCancelPairing) {
                            Text("取消", color = Color.Gray)
                        }
                    }
                }
                else -> {
                    if (unpairedDiscovered.isEmpty()) {
                        // 階段一：自動探索中
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "正在尋找電腦...",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "請確保已在電腦上啟動伺服器\n且處於相同 Wi-Fi 網路",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(32.dp))
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.5.dp,
                                    color = Color.Gray
                                )
                            }
                        }
                    } else {
                        // 階段二：直覺卡片式列表選擇
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "選擇要連線的電腦",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Spacer(Modifier.height(24.dp))

                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(unpairedDiscovered) { server ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF2D2D2D))
                                            .clickable { onStartPairing(server) }
                                            .padding(horizontal = 20.dp, vertical = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF42A5F5))
                                        )
                                        Spacer(Modifier.width(16.dp))
                                        Column {
                                            Text(
                                                text = server.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "點擊配對",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}