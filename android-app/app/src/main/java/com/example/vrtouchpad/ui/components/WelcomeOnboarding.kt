package com.example.vrtouchpad.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 首次啟動、尚未配對任何電腦時顯示的全螢幕引導畫面。
 * 取代原本只丟一個灰色狀態球讓使用者自己猜下一步該做什麼的作法，
 * 直接告訴使用者現在該做什麼、按哪個按鈕。
 */
@Composable
fun WelcomeOnboarding(
    modifier: Modifier = Modifier,
    onFindComputer: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "尚未連接任何電腦",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "請先在電腦上開啟 VR Touchpad 伺服器，\n並確認手機與電腦在同一個 Wi-Fi 網路下",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onFindComputer) {
            Text("尋找電腦")
        }
    }
}
