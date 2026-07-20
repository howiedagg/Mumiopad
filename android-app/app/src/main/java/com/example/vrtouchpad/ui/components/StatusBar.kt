// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/ui/components/StatusBar.kt

package com.example.vrtouchpad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding // 💡 引入 statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vrtouchpad.R
import com.example.vrtouchpad.network.ConnState

@Composable
fun StatusBar(
    connState: ConnState,
    connectedServerName: String?,
    isKeyboardOpen: Boolean,
    onSettingsClick: () -> Unit,
    onStatusClick: () -> Unit,
    onToggleKeyboard: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding() // 💡 關鍵修正：當背景融入最上方時，此按鈕欄會自動向下保留與實體瀏海、狀態列等高的安全距離
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dotColor = when (connState) {
            ConnState.CONNECTED -> Color(0xFF4CAF50)
            ConnState.CONNECTING, ConnState.PAIRING -> Color(0xFFFFA000)
            ConnState.DISCONNECTED, ConnState.AUTH_FAILED -> Color(0xFF757575)
        }

        val statusText = when (connState) {
            ConnState.DISCONNECTED -> stringResource(R.string.status_disconnected)
            ConnState.CONNECTING -> stringResource(R.string.status_searching)
            ConnState.PAIRING -> stringResource(R.string.status_pairing)
            ConnState.CONNECTED -> connectedServerName ?: stringResource(R.string.status_connected)
            ConnState.AUTH_FAILED -> stringResource(R.string.status_auth_failed)
        }

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

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (connState == ConnState.CONNECTED) {
                IconGlyphButton(
                    glyph = "⌨",
                    highlighted = isKeyboardOpen,
                    onClick = onToggleKeyboard,
                )
                Spacer(Modifier.width(4.dp))
            }
            IconGlyphButton(glyph = "⚙", onClick = onSettingsClick)
        }
    }
}

@Composable
private fun IconGlyphButton(
    glyph: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (highlighted) Color(0xFF0D47A1) else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, fontSize = 18.sp, color = Color.White)
    }
}