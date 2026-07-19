// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/ui/dialogs/PairingHost.kt

package com.example.vrtouchpad.ui.dialogs

import androidx.compose.runtime.Composable
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.SavedServer
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
    pairingError: String?,
    onSelectSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onDisconnect: () -> Unit,
    onStartPairing: (DiscoveredServer) -> Unit,
    onRescan: () -> Unit, // 【新增】
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
            onRescan = onRescan, // 【新增】
            onDismiss = onDismiss,
        )

        is PairingNavState.PairingWaiting -> PairingWaitingScreen(
            server = navState.server,
            pairingError = pairingError,
            onCancel = onBackToList,
        )
    }
}