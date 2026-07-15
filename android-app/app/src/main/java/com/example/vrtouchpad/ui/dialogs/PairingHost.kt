package com.example.vrtouchpad.ui.dialogs

import androidx.compose.runtime.Composable
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.SavedServer
import com.example.vrtouchpad.ui.PairingNavState

/**
 * 依 PairingNavState 分派到對應畫面，本身不含任何業務邏輯或狀態，
 * 純粹是路由層。之後要新增畫面（例如 QR code 掃描配對）只需要新增一個
 * PairingNavState 分支與對應 Composable，完全不需要動既有畫面的程式碼，
 * 也不需要動 ViewModel 以外的任何檔案。
 */
@Composable
fun PairingHost(
    navState: PairingNavState,
    savedServers: List<SavedServer>,
    unpairedDiscovered: List<DiscoveredServer>,
    isScanning: Boolean,
    pairCode: String,
    isPairingBusy: Boolean,
    pairingError: String?,
    onSelectSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onStartPairing: (DiscoveredServer) -> Unit,
    onPairCodeChange: (String) -> Unit,
    onConfirmPairing: (code: String) -> Unit,
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

        is PairingNavState.EnteringCode -> PairingCodeScreen(
            server = navState.server,
            pairCode = pairCode,
            isPairingBusy = isPairingBusy,
            pairingError = pairingError,
            onPairCodeChange = onPairCodeChange,
            onConfirm = onConfirmPairing,
            onBack = onBackToList,
        )
    }
}
