package com.example.vrtouchpad.ui

import android.annotation.SuppressLint
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
import com.example.vrtouchpad.network.WebSocketClient
import com.example.vrtouchpad.network.ConnectionClient
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
import kotlinx.coroutines.delay
import org.json.JSONArray

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
            val modeStr = settingsStore.connectionMode
            if (modeStr == "UNSET") ConnectionMode.WIFI else ConnectionMode.valueOf(modeStr)
        } catch (e: Exception) {
            ConnectionMode.WIFI
        }
    )
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode

    val isFirstLaunch: Boolean get() = settingsStore.connectionMode == "UNSET"

    private val _btBondedDevices = MutableStateFlow<List<android.bluetooth.BluetoothDevice>>(emptyList())
    val btBondedDevices: StateFlow<List<android.bluetooth.BluetoothDevice>> = _btBondedDevices

    private val _savedBtAddresses = MutableStateFlow<Set<String>>(emptySet())
    val savedBtAddresses: StateFlow<Set<String>> = _savedBtAddresses

    private var isAppActive = true
    private var pendingPairServer: DiscoveredServer? = null
    private var lastDialToken: String? = null

    val wsClient = WebSocketClient(
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

    private val activeClient: ConnectionClient
        get() = if (_connectionMode.value == ConnectionMode.BLUETOOTH) btClient else wsClient

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

    val connectedBtAddress: StateFlow<String?> = combine(
        connState,
        _connectionMode
    ) { state, mode ->
        if (state == ConnState.CONNECTED && mode == ConnectionMode.BLUETOOTH) btClient.connectedDevice?.address else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        refreshServerLists()

        // 💡 開機時，發起初始連線嘗試
        startInitialConnection()

        // 💡 註冊各種傳輸協議的狀態監聽器
        observeGeneralConnection()
        observeBluetoothEvents()
        observeWifiEvents()
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

    private fun getSavedBtAddresses(): Set<String> {
        val raw = settingsStore.savedBtDevices
        return runCatching {
            val array = JSONArray(raw)
            val set = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                set.add(array.getString(i))
            }
            set
        }.getOrDefault(emptySet())
    }

    private fun saveBtAddress(address: String) {
        val current = getSavedBtAddresses().toMutableSet()
        if (current.add(address)) {
            settingsStore.savedBtDevices = JSONArray(current).toString()
            refreshBtDevices()
        }
    }

    private fun deleteBtAddress(address: String) {
        val current = getSavedBtAddresses().toMutableSet()
        if (current.remove(address)) {
            settingsStore.savedBtDevices = JSONArray(current).toString()

            if (btClient.connectedDevice?.address == address) {
                Log.d("BT_UNPAIR", "被移除的裝置為目前連線設備，主動中斷藍牙連線")
                btClient.close()
            }

            val bonded = btClient.getBondedDevices()
            val target = bonded.find { it.address == address }
            if (target != null) {
                Log.d("BT_UNPAIR", "向 Android 系統發起解除藍牙配對請求: ${target.address}")
                btClient.removeBond(target)
            }

            if (settingsStore.lastConnectedBtAddress == address) {
                settingsStore.lastConnectedBtAddress = null
            }
            refreshBtDevices()
        }
    }

    fun refreshBtDevices() {
        _savedBtAddresses.value = getSavedBtAddresses()

        val rawDevices = btClient.getBondedDevices()
        val history = _savedBtAddresses.value
        _btBondedDevices.value = rawDevices.sortedByDescending { history.contains(it.address) }
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
                btClient.open()
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
        activeClient.sendEvent(event)
    }

    fun setConnectionMode(mode: ConnectionMode) {
        if (_connectionMode.value == mode) {
            if (mode == ConnectionMode.WIFI && pairingManager.getSavedServers().isEmpty() && !_isScanning.value) {
                Log.d("WIFI_SCAN", "初次配對點選 Wi-Fi，啟動 mDNS 搜尋...")
                startPairingScan()
            }
            return
        }

        _connectionMode.value = mode
        settingsStore.connectionMode = mode.name

        if (mode == ConnectionMode.BLUETOOTH) {
            orchestrator.stop()
            wsClient.close()
            wifiPerformanceManager.release()
            btClient.open()
            refreshBtDevices()
        } else {
            btClient.close()
            _unpairedDiscovered.value = emptyList()
            if (pairingManager.getSavedServers().isNotEmpty()) {
                orchestrator.start()
            } else {
                Log.d("WIFI_SCAN", "手動切換至 Wi-Fi 模式且無歷史紀錄，啟動 mDNS 搜尋...")
                startPairingScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptBluetoothAutoConnect() {
        if (btClient.connState.value == ConnState.CONNECTED || btClient.connState.value == ConnState.CONNECTING) {
            Log.d("BT_AUTO_CONNECT", "自動重連忽略：目前已連線或正在連線中")
            return
        }

        val lastAddress = settingsStore.lastConnectedBtAddress ?: return
        val bonded = btClient.getBondedDevices()
        val target = bonded.find { it.address == lastAddress }
        if (target != null) {
            Log.d("BT_AUTO_CONNECT", "啟動背景自動連線: ${target.name ?: target.address}")
            btClient.connect(target)
        } else {
            Log.d("BT_AUTO_CONNECT", "未發現歷史連線裝置或該裝置已被系統解除配對")
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
        if (_connectionMode.value == ConnectionMode.BLUETOOTH) {
            deleteBtAddress(uuid)
            return
        }

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

    fun sendText(text: String) {
        activeClient.sendText(text)
    }

    fun sendKeypress(key: String) {
        activeClient.sendKeypress(key)
    }

    override fun onCleared() {
        super.onCleared()
        orchestrator.stop()
        wsClient.close()
        btClient.destroy()
        wifiPerformanceManager.release()
    }

    /**
     * 負責開機時的初始連線分流邏輯
     */
    private fun startInitialConnection() {
        if (settingsStore.connectionMode != "UNSET") {
            if (_connectionMode.value == ConnectionMode.WIFI) {
                if (pairingManager.getSavedServers().isEmpty()) {
                    startPairingScan()
                } else {
                    orchestrator.start()
                }
            } else {
                refreshBtDevices()
            }
        }
    }

    /**
     * 監聽全域連線狀態，主要負責在連線時獲取高效能 WiFi 鎖
     */
    private fun observeGeneralConnection() {
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
    }

    /**
     * 負責藍牙相關的背景連線事件、自動重連與歷史紀錄寫入
     */
    private fun observeBluetoothEvents() {
        // 監聽藍牙連線成功，記錄歷史 MAC
        viewModelScope.launch {
            btClient.connState.collect { state ->
                if (_connectionMode.value == ConnectionMode.BLUETOOTH && state == ConnState.CONNECTED) {
                    btClient.connectedDevice?.address?.let { address ->
                        Log.d("BT_AUTO_CONNECT", "藍牙連線成功，記錄歷史 MAC: $address")
                        settingsStore.lastConnectedBtAddress = address
                        saveBtAddress(address)
                    }
                }
            }
        }

        // 監聽藍牙 App 註冊就緒狀態，自動重連
        viewModelScope.launch {
            btClient.isAppRegistered.collect { registered ->
                if (registered && _connectionMode.value == ConnectionMode.BLUETOOTH) {
                    Log.d("BT_AUTO_CONNECT", "檢測到藍牙服務已註冊就緒，延遲 500ms 給予晶片緩衝後發起重連...")
                    delay(500)
                    attemptBluetoothAutoConnect()
                }
            }
        }
    }

    /**
     * 負責 Wi-Fi 相關的連線狀態事件，包括遠端撤銷信任、前景自動重連
     */
    private fun observeWifiEvents() {
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
}