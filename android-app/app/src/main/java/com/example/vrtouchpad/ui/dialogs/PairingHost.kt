package com.example.vrtouchpad.ui.dialogs

import androidx.compose.runtime.Composable
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.SavedServer
import com.example.vrtouchpad.ui.PairingNavState

@Composable
fun PairingHost(
    navState: PairingNavState,
    savedServers: List<SavedServer>,
    unpairedDiscovered: List<DiscoveredServer>,
    isScanning: Boolean,
    isPairingBusy: Boolean,
    pairingError: String?,
    onSelectSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onStartPairing: (DiscoveredServer) -> Unit,
    onBackToList: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (navState) {
        is PairingNavState.Hidden -> Unit

        is PairingNavState.DeviceList -> DeviceListScreen(
            savedServers = savedServers,
            unpairedDiscovered = unpairedDiscovered,
            isScanning = isScanning,
            onSelectSaved = onSelectSaved,
            onDeleteSaved = onDeleteSaved,
            onStartPairing = onStartPairing,
            onDismiss = onDismiss,
        )

        is PairingNavState.PairingWaiting -> PairingWaitingScreen(
            server = navState.server,
            pairingError = pairingError,
            onCancel = onBackToList,
        )
    }
}