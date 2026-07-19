package com.example.vrtouchpad.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.NetworkProfileStore
import com.example.vrtouchpad.data.PairingManager
import com.example.vrtouchpad.data.SavedServer
import com.example.vrtouchpad.data.SettingsStore
import com.example.vrtouchpad.data.WifiNetworkIdProvider
import com.example.vrtouchpad.data.WifiPerformanceManager
import com.example.vrtouchpad.engine.ConnectionOrchestrator
import com.example.vrtouchpad.network.ConnState
import com.example.vrtouchpad.network.TouchpadWebSocketClient
import com.example.vrtouchpad.network.BluetoothHidTouchpadClient
import com.example.vrtouchpad.engine.TouchOutEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class ConnectionMode { WIFI, BLUETOOTH }

class TouchpadViewModel(
    private val pairingManager: PairingManager,
    private val settingsStore: SettingsStore,
    private val wifiPerformanceManager: WifiPerformanceManager,
    private val wifiNetworkIdProvider: WifiNetworkIdProvider,
    private val networkProfileStore: NetworkProfileStore,
    private val context: android.content.Context
) : ViewModel() {

    private val _unpairedDiscovered = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val unpairedDiscovered: StateFlow<List<DiscoveredServer>> = _unpairedDiscovered

    private val _savedServers = MutableStateFlow<List<SavedServer>>(emptyList())
    val savedServers: StateFlow<List<SavedServer>> = _savedServers

    private val _pairingNavState = MutableStateFlow<PairingNavState>(PairingNavState.Hidden)
    val pairingNavState: StateFlow<PairingNavState> = _pairingNavState

    private val _isPairingBusy = MutableStateFlow(false)
    val isPairingBusy: StateFlow<Boolean> = _isPairingBusy

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _pairingError = MutableStateFlow<PairingError?>(null)
    val pairingError: StateFlow<PairingError?> = _pairingError

    private val _isKeyboardActive = MutableStateFlow(false)
    val isKeyboardActive: StateFlow<Boolean> = _isKeyboardActive

    private val _mouseSpeed = MutableStateFlow(settingsStore.mouseSpeed)
    val mouseSpeed: StateFlow<Float> = _mouseSpeed

    private val _scrollSpeed = MutableStateFlow(settingsStore.scrollSpeed)
    val scrollSpeed: StateFlow<Float> = _scrollSpeed

    private val _reverseScroll = MutableStateFlow(settingsStore.reverseScroll)
    val reverseScroll: StateFlow<Boolean> = _reverseScroll

    private val _onlineSavedUuids = MutableStateFlow<Set<String>>(emptySet())
    val onlineSavedUuids: StateFlow<Set<String>> = _onlineSavedUuids

    private val _userWantsOffline = MutableStateFlow(false)
    val userWantsOffline: StateFlow<Boolean> = _userWantsOffline

    private val _connectionMode = MutableStateFlow(
        try {
            ConnectionMode.valueOf(settingsStore.connectionMode)
        } catch (e: Exception) {
            ConnectionMode.WIFI
        }
    )
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode

    private val _btBondedDevices = MutableStateFlow<List<android.bluetooth.BluetoothDevice>>(emptyList())
    val btBondedDevices: StateFlow<List<android.bluetooth.BluetoothDevice>> = _btBondedDevices

    private var isAppActive = true
    private var pendingPairServer: DiscoveredServer? = null
    private var lastDialToken: String? = null

    val wsClient = TouchpadWebSocketClient(
        onPairSuccess = { token, pcName ->
            _isPairingBusy.value = false
            _pairingError.value = null

            pendingPairServer?.let { server ->
                pairingManager.saveServer(server.uuid, pcName, token)
                pairingManager.setSelectedServerUuid(server.uuid)
                wifiNetworkIdProvider.getCurrentBssid()?.let { bssid ->
                    networkProfileStore.setDefaultServerUuid(bssid, server.uuid)
                }
            }
            pendingPairServer = null

            refreshServerLists()
            _pairingNavState.value = PairingNavState.Hidden
            _unpairedDiscovered.value = emptyList()
        },
        onPairFail = { reason ->
            _isPairingBusy.value = false
            _pairingError.value = when (reason) {
                "denied" -> PairingError.Denied
                "network_error" -> PairingError.NetworkError
                else -> PairingError.Unknown(reason)
            }
        }
    )

    val btClient = BluetoothHidTouchpadClient(context)

    private val orchestrator = ConnectionOrchestrator(
        scope = viewModelScope,
        wifiNetworkIdProvider = wifiNetworkIdProvider,
        networkProfileStore = networkProfileStore,
        getSavedServers = { pairingManager.getSavedServers() },
        getLastKnownIp = { uuid -> pairingManager.getSavedServers().find { it.uuid == uuid }?.lastKnownIp },
        updateLastKnownIp = { uuid, ip -> pairingManager.updateLastKnownIp(uuid, ip) },
        discover = { timeoutMs, onFound, onFinished ->
            pairingManager.discover(
                timeoutMs = timeoutMs,
                onFound = { server ->
                    val savedList = pairingManager.getSavedServers()
                    if (savedList.any { it.uuid == server.uuid }) {
                        _onlineSavedUuids.value = _onlineSavedUuids.value + server.uuid
                    } else {
                        val currentList = _unpairedDiscovered.value.toMutableList()
                        if (!currentList.any { it.uuid == server.uuid }) {
                            currentList.add(server)
                            _unpairedDiscovered.value = currentList
                        }
                    }
                    onFound(server)
                },
                onFinished = onFinished
            )
        },
        dial = ::dialAdapter,
    )

    val connState: StateFlow<ConnState> = combine(
        _connectionMode,
        orchestrator.phase,
        wsClient.connState,
        btClient.connState
    ) { mode, orthoPhase, wsState, btState ->
        if (mode == ConnectionMode.BLUETOOTH) {
            btState
        } else {
            when {
                wsState == ConnState.CONNECTED -> ConnState.CONNECTED
                wsState == ConnState.AUTH_FAILED -> ConnState.AUTH_FAILED
                wsState == ConnState.PAIRING -> ConnState.PAIRING
                orthoPhase is ConnectionOrchestrator.Phase.Connecting || wsState == ConnState.CONNECTING -> ConnState.CONNECTING
                else -> ConnState.DISCONNECTED
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConnState.DISCONNECTED
    )

    val SkinnerUuid: StateFlow<String?> = combine(
        connState,
        _savedServers
    ) { state, _ ->
        if (state == ConnState.CONNECTED && _connectionMode.value == ConnectionMode.WIFI) pairingManager.getSelectedServerUuid() else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val connectedServerName: StateFlow<String?> = combine(
        _connectionMode,
        orchestrator.phase,
        wsClient.connState,
        _savedServers
    ) { mode, orthoPhase, wsState, savedList ->
        if (mode == ConnectionMode.BLUETOOTH) {
            // 💡 修正：直接返回 null，最外層 UI (StatusBar) 偵測到 null，會自動回退顯示 localized 的「已連線/Connected」
            null
        } else {
            if (wsState == ConnState.CONNECTED) {
                when (orthoPhase) {
                    is ConnectionOrchestrator.Phase.Connected -> orthoPhase.serverName
                    else -> {
                        val activeUuid = pairingManager.getSelectedServerUuid()
                        savedList.find { it.uuid == activeUuid }?.name
                    }
                }
            } else {
                null
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val connectedServerUuid: StateFlow<String?> = combine(
        connState,
        _savedServers
    ) { state, _ ->
        if (state == ConnState.CONNECTED && _connectionMode.value == ConnectionMode.WIFI) pairingManager.getSelectedServerUuid() else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        refreshServerLists()

        if (_connectionMode.value == ConnectionMode.WIFI) {
            if (pairingManager.getSavedServers().isEmpty()) {
                startPairingScan()
            } else {
                orchestrator.start()
            }
        } else {
            refreshBtDevices()
        }

        viewModelScope.launch {
            connState.collect { state ->
                if (state == ConnState.CONNECTED) {
                    _unpairedDiscovered.value = emptyList()
                    if (_connectionMode.value == ConnectionMode.WIFI) {
                        wifiPerformanceManager.acquire()
                    }
                } else {
                    wifiPerformanceManager.release()
                }
            }
        }

        viewModelScope.launch {
            wsClient.connState.collect { state ->
                if (_connectionMode.value != ConnectionMode.WIFI) return@collect

                if (state == ConnState.AUTH_FAILED) {
                    val failedUuid = lastDialToken?.let { token ->
                        pairingManager.getSavedServers().find { it.token == token }?.uuid
                    }
                    if (failedUuid != null) {
                        Log.d("AUTH_FAILED", "電腦端已解除信任，自動清除本機紀錄: $failedUuid")
                        pairingManager.deleteServer(failedUuid)
                        networkProfileStore.removeAllReferencesTo(failedUuid)
                        refreshServerLists()
                    }
                } else if (state == ConnState.DISCONNECTED) {
                    if (isAppActive && !_userWantsOffline.value && pairingManager.getSavedServers().isNotEmpty() && _pairingNavState.value == PairingNavState.Hidden) {
                        Log.d("RECONNECT", "Socket 斷開且處於前景，重新喚醒自動連線協調器")
                        orchestrator.start()
                    }
                }
            }
        }
    }

    private suspend fun dialAdapter(host: String, port: Int, token: String): Boolean {
        lastDialToken = token
        var result = false
        val job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val finalState = wsClient.connState
                .drop(1)
                .first { it == ConnState.CONNECTED || it == ConnState.DISCONNECTED || it == ConnState.AUTH_FAILED }
            result = finalState == ConnState.CONNECTED
        }
        wsClient.connectWithToken(host, port, token)
        job.join()
        return result
    }

    private fun refreshServerLists() {
        _savedServers.value = pairingManager.getSavedServers()
    }

    fun refreshBtDevices() {
        _btBondedDevices.value = btClient.getBondedDevices()
    }

    fun connectBluetooth(device: android.bluetooth.BluetoothDevice) {
        btClient.connect(device)
    }

    fun disconnectBluetooth() {
        btClient.disconnectHost()
    }

    fun updateAppActive(active: Boolean) {
        isAppActive = active
        if (active) {
            _userWantsOffline.value = false
            if (_connectionMode.value == ConnectionMode.WIFI && pairingManager.getSavedServers().isNotEmpty()) {
                orchestrator.start()
            } else if (_connectionMode.value == ConnectionMode.BLUETOOTH) {
                refreshBtDevices()
            }
        } else {
            orchestrator.stop()
            wsClient.close()
            btClient.close()
            wifiPerformanceManager.release()
        }
    }

    fun sendEvent(event: TouchOutEvent) {
        if (_connectionMode.value == ConnectionMode.BLUETOOTH) {
            btClient.sendEvent(event)
        } else {
            wsClient.sendEvent(event)
        }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        if (_connectionMode.value == mode) return
        _connectionMode.value = mode
        settingsStore.connectionMode = mode.name

        if (mode == ConnectionMode.BLUETOOTH) {
            orchestrator.stop()
            wsClient.close()
            wifiPerformanceManager.release()
            refreshBtDevices()
        } else {
            btClient.close()
            _unpairedDiscovered.value = emptyList()
            if (pairingManager.getSavedServers().isNotEmpty()) {
                orchestrator.start()
            }
        }
    }

    fun toggleKeyboard() {
        _isKeyboardActive.value = !_isKeyboardActive.value
    }

    fun setKeyboardActive(active: Boolean) {
        _isKeyboardActive.value = active
    }

    fun updateMouseSpeed(speed: Float) {
        _mouseSpeed.value = speed
        settingsStore.mouseSpeed = speed
    }

    fun updateScrollSpeed(speed: Float) {
        _scrollSpeed.value = speed
        settingsStore.scrollSpeed = speed
    }

    fun updateReverseScroll(reverse: Boolean) {
        _reverseScroll.value = reverse
        settingsStore.reverseScroll = reverse
    }

    fun triggerPairing(server: DiscoveredServer) {
        orchestrator.stop()
        _userWantsOffline.value = false
        pendingPairServer = server
        _pairingError.value = null
        _pairingNavState.value = PairingNavState.PairingWaiting(server)
        _isPairingBusy.value = true
        wsClient.connectForPairing(server.host, server.port)
    }

    fun cancelPairing() {
        wsClient.close()
        pendingPairServer = null
        _pairingError.value = null
        _isPairingBusy.value = false
        _pairingNavState.value = PairingNavState.DeviceList

        if (pairingManager.getSavedServers().isEmpty()) {
            startPairingScan()
        } else {
            orchestrator.start()
        }
    }

    fun selectServer(uuid: String) {
        Log.d("SWITCH_PC", "手動切換至電腦 UUID: $uuid")
        _userWantsOffline.value = false
        pairingManager.setSelectedServerUuid(uuid)
        wifiNetworkIdProvider.getCurrentBssid()?.let { bssid ->
            networkProfileStore.setDefaultServerUuid(bssid, uuid)
        }
        wsClient.close()
        _pairingNavState.value = PairingNavState.Hidden

        val targetServer = pairingManager.getSavedServers().find { it.uuid == uuid }
        if (targetServer != null) {
            val ip = targetServer.lastKnownIp
            if (ip != null) {
                Log.d("SWITCH_PC", "啟動手動指定直連: ${targetServer.name} ($ip)")
                orchestrator.manualConnect(targetServer, ip)
            } else {
                Log.d("SWITCH_PC", "無已知 IP 紀錄，啟動自動連線協調器")
                orchestrator.start()
            }
        } else {
            orchestrator.start()
        }
    }

    fun disconnect() {
        Log.d("DISCONNECT", "使用者手動中斷連線")
        _userWantsOffline.value = true
        orchestrator.stop()
        wsClient.close()
        btClient.close()
        _pairingNavState.value = PairingNavState.Hidden
    }

    fun deleteServer(uuid: String) {
        val activeUuid = pairingManager.getSelectedServerUuid()
        val serverToBeDeletedInfo = pairingManager.getSavedServers().find { it.uuid == uuid }

        if (activeUuid == uuid && connState.value == ConnState.CONNECTED && _connectionMode.value == ConnectionMode.WIFI) {
            runCatching { wsClient.sendUnpairRequest() }
            wsClient.close()
        }
        pairingManager.deleteServer(uuid)
        networkProfileStore.removeAllReferencesTo(uuid)
        refreshServerLists()

        if (pairingManager.getSavedServers().isEmpty()) {
            _pairingNavState.value = PairingNavState.Hidden

            if (serverToBeDeletedInfo != null) {
                val ip = serverToBeDeletedInfo.lastKnownIp ?: "127.0.0.1"
                val convertedUnpaired = DiscoveredServer(
                    uuid = serverToBeDeletedInfo.uuid,
                    host = ip,
                    port = 8765,
                    name = serverToBeDeletedInfo.name
                )
                _unpairedDiscovered.value = listOf(convertedUnpaired)
            }

            if (_connectionMode.value == ConnectionMode.WIFI) {
                startPairingScan(clearExisting = false)
            }
        } else {
            if (_connectionMode.value == ConnectionMode.WIFI) {
                orchestrator.start()
                startPairingScan(clearExisting = true)
            }
        }
    }

    fun closeServerSelector() {
        pendingPairServer = null
        _pairingNavState.value = PairingNavState.Hidden

        if (!_userWantsOffline.value && connState.value != ConnState.CONNECTED && pairingManager.getSavedServers().isNotEmpty() && _connectionMode.value == ConnectionMode.WIFI) {
            orchestrator.start()
        }
    }

    fun openServerSelector() {
        orchestrator.stop()
        _pairingError.value = null
        pendingPairServer = null
        refreshServerLists()
        _pairingNavState.value = PairingNavState.DeviceList
        if (_connectionMode.value == ConnectionMode.WIFI) {
            startPairingScan()
        }
    }

    fun startPairingScan(clearExisting: Boolean = true) {
        if (clearExisting) {
            _unpairedDiscovered.value = emptyList()
        }
        _onlineSavedUuids.value = emptySet()
        _isScanning.value = true
        pairingManager.discover(
            timeoutMs = 5000,
            onFound = { server ->
                val savedList = pairingManager.getSavedServers()
                if (savedList.any { it.uuid == server.uuid }) {
                    _onlineSavedUuids.value = _onlineSavedUuids.value + server.uuid
                } else {
                    val currentList = _unpairedDiscovered.value.toMutableList()
                    if (!currentList.any { it.uuid == server.uuid }) {
                        currentList.add(server)
                        _unpairedDiscovered.value = currentList
                    }
                }
            },
            // 💡 修正：移除 PairingManager.discover 並不存在的 getSavedServers 參數，避免編譯失敗！
            onFinished = {
                _isScanning.value = false
            }
        )
    }

    fun forceReconnect() {
        if (pairingManager.getSavedServers().isNotEmpty() && _connectionMode.value == ConnectionMode.WIFI) {
            orchestrator.start()
        } else if (_connectionMode.value == ConnectionMode.BLUETOOTH) {
            refreshBtDevices()
        }
    }

    override fun onCleared() {
        super.onCleared()
        orchestrator.stop()
        wsClient.close()
        btClient.close()
        wifiPerformanceManager.release()
    }
}