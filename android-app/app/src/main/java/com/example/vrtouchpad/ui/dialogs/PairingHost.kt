package com.example.vrtouchpad.ui.dialogs

import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.Composable
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.SavedServer
import com.example.vrtouchpad.ui.ConnectionMode
import com.example.vrtouchpad.ui.PairingError
import com.example.vrtouchpad.ui.PairingNavState

@Composable
fun PairingHost(
    navState: PairingNavState,
    connectionMode: ConnectionMode,
    onModeChange: (ConnectionMode) -> Unit,
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
    // 💡 藍牙對接參數
    btBondedDevices: List<BluetoothDevice>,
    btConnState: com.example.vrtouchpad.network.ConnState,
    onConnectBt: (BluetoothDevice) -> Unit,
    onDisconnectBt: () -> Unit,
    onMakeBtDiscoverable: () -> Unit
) {
    when (navState) {
        is PairingNavState.Hidden -> Unit

        is PairingNavState.DeviceList -> DeviceListScreen(
            connectionMode = connectionMode,
            onModeChange = onModeChange,
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
            btBondedDevices = btBondedDevices,
            btConnState = btConnState,
            onConnectBt = onConnectBt,
            onDisconnectBt = onDisconnectBt,
            onMakeBtDiscoverable = onMakeBtDiscoverable
        )

        is PairingNavState.PairingWaiting -> PairingWaitingScreen(
            server = navState.server,
            pairingError = pairingError,
            onCancel = onBackToList,
        )
    }
}