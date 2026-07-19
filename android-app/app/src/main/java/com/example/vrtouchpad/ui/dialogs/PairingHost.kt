package com.example.vrtouchpad.ui.dialogs

import androidx.compose.runtime.Composable
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.SavedServer
import com.example.vrtouchpad.ui.PairingError
import com.example.vrtouchpad.ui.PairingNavState

@Composable
fun PairingHost(
    navState: PairingNavState,
    savedServers: List<SavedServer>,
    onlineSavedUuids: Set<String>,
    connectedUuid: String?,
    unpairedDiscovered: List<DiscoveredServer>,
    isScanning: Boolean,
    isPairingBusy: Boolean,
    pairingError: PairingError?,
    onSelectSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onDisconnect: () -> Unit,
    onStartPairing: (DiscoveredServer) -> Unit,
    onRescan: () -> Unit,
    onBackToList: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (navState) {
        is PairingNavState.Hidden -> Unit

        is PairingNavState.DeviceList -> DeviceListScreen(
            savedServers = savedServers,
            onlineSavedUuids = onlineSavedUuids,
            connectedUuid = connectedUuid,
            unpairedDiscovered = unpairedDiscovered,
            isScanning = isScanning,
            onSelectSaved = onSelectSaved,
            onDeleteSaved = onDeleteSaved,
            onDisconnect = onDisconnect,
            onStartPairing = onStartPairing,
            onRescan = onRescan,
            onDismiss = onDismiss,
        )

        is PairingNavState.PairingWaiting -> PairingWaitingScreen(
            server = navState.server,
            pairingError = pairingError,
            onCancel = onBackToList,
        )
    }
}