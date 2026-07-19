package com.example.vrtouchpad.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.vrtouchpad.R
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.ui.PairingError

@Composable
fun PairingWaitingScreen(
    server: DiscoveredServer,
    pairingError: PairingError?,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.pairing_request_sent)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val targetName = server.name.ifBlank { stringResource(R.string.dialog_unpaired_pc_default) }
                Text(
                    text = stringResource(R.string.pairing_waiting_instruction, targetName),
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
                    val errorText = when (pairingError) {
                        PairingError.Denied -> stringResource(R.string.err_denied)
                        PairingError.NetworkError -> stringResource(R.string.err_network)
                        is PairingError.Unknown -> stringResource(R.string.err_fallback, pairingError.reason)
                    }
                    Text(
                        text = errorText,
                        color = Color(0xFFEF5350),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}