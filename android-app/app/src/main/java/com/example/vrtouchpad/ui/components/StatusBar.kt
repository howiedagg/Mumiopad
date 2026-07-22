// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/ui/components/StatusBar.kt

package com.example.vrtouchpad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
            .statusBarsPadding()
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

            Spacer(Modifier.width(2.dp))

            // 💡 替換為官方內建圖示 ArrowDropDown
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(18.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (connState == ConnState.CONNECTED) {
                IconButton(
                    icon = Icons.Default.Keyboard,
                    highlighted = isKeyboardOpen,
                    onClick = onToggleKeyboard,
                )
                Spacer(Modifier.width(4.dp))
            }
            IconButton(
                icon = Icons.Default.Settings,
                onClick = onSettingsClick
            )
        }
    }
}

@Composable
private fun IconButton(
    icon: ImageVector,
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}