package com.example.vrtouchpad.ui

import android.content.Context
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TouchpadViewModel(context: Context) : ViewModel() {
    private val pairingManager = PairingManager(context)
    private val settingsStore = SettingsStore(context)
    private val wifiPerformanceManager = WifiPerformanceManager(context)

    // --- 新增：地點記憶相關的兩個獨立模組 ---
    private val wifiNetworkIdProvider = WifiNetworkIdProvider(context)
    private val networkProfileStore = NetworkProfileStore(context)

    private val _connState = MutableStateFlow(ConnState.DISCONNECTED)
    val connState: StateFlow<ConnState> = _connState

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

    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError

    private val _isKeyboardActive = MutableStateFlow(false)
    val isKeyboardActive: StateFlow<Boolean> = _isKeyboardActive

    private val _mouseSpeed = MutableStateFlow(settingsStore.mouseSpeed)
    val mouseSpeed: StateFlow<Float> = _mouseSpeed

    private val _scrollSpeed = MutableStateFlow(settingsStore.scrollSpeed)
    val scrollSpeed: StateFlow<Float> = _scrollSpeed

    private val _reverseScroll = MutableStateFlow(settingsStore.reverseScroll)
    val reverseScroll: StateFlow<Boolean> = _reverseScroll

    private var isAppActive = true
    private var pendingPairServer: DiscoveredServer? = null

    // dialAdapter 呼叫前記一下用的是哪個 token，AUTH_FAILED 發生時才知道要清掉哪台
    private var lastDialToken: String? = null

    val wsClient = TouchpadWebSocketClient(
        onPairSuccess = { token, pcName ->
            _isPairingBusy.value = false
            _pairingError.value = null

            pendingPairServer?.let { server ->
                pairingManager.saveServer(server.uuid, pcName, token)
                pairingManager.setSelectedServerUuid(server.uuid)
                // 新配對成功的電腦，順便成為「這個地方」的預設電腦
                wifiNetworkIdProvider.getCurrentBssid()?.let { bssid ->
                    networkProfileStore.setDefaultServerUuid(bssid, server.uuid)
                }
            }
            pendingPairServer = null

            refreshServerLists()
            _connState.value = ConnState.CONNECTED
            wifiPerformanceManager.acquire()
            _pairingNavState.value = PairingNavState.Hidden
            _unpairedDiscovered.value = emptyList()
        },
        onPairFail = { reason ->
            _isPairingBusy.value = false
            _pairingError.value = when (reason) {
                "denied" -> "連線被拒絕。請在電腦上點選「是」"
                "network_error" -> "連線失敗，請確認手機與電腦處於相同 Wi-Fi 網路"
                else -> "配對失敗: $reason"
            }
            _connState.value = ConnState.DISCONNECTED
        }
    )

    // --- 核心連線狀態機：搜尋 / 直連捷徑 / 看門狗 / 冷靜重試，全部封裝在這裡 ---
    private val orchestrator = ConnectionOrchestrator(
        scope = viewModelScope,
        wifiNetworkIdProvider = wifiNetworkIdProvider,
        networkProfileStore = networkProfileStore,
        getSavedServers = { pairingManager.getSavedServers() },
        getLastKnownIp = { uuid -> pairingManager.getSavedServers().find { it.uuid == uuid }?.lastKnownIp },
        updateLastKnownIp = { uuid, ip -> pairingManager.updateLastKnownIp(uuid, ip) },
        discover = { timeoutMs, onFound, onFinished ->
            pairingManager.discover(timeoutMs = timeoutMs, onFound = onFound, onFinished = onFinished)
        },
        dial = ::dialAdapter,
    )

    init {
        refreshServerLists()

        // orchestrator 的階段 -> 換成畫面看得懂的 ConnState。
        // 配對畫面開著的時候不要覆寫，那段時間的狀態完全交給 wsClient.connState 那邊。
        viewModelScope.launch {
            orchestrator.phase.collect { phase ->
                if (_pairingNavState.value != PairingNavState.Hidden) return@collect
                _connState.value = when (phase) {
                    is ConnectionOrchestrator.Phase.Idle -> ConnState.DISCONNECTED
                    is ConnectionOrchestrator.Phase.Connecting -> ConnState.CONNECTING
                    is ConnectionOrchestrator.Phase.Connected -> ConnState.CONNECTED
                }
                if (phase is ConnectionOrchestrator.Phase.Connected) {
                    wifiPerformanceManager.acquire()
                } else {
                    wifiPerformanceManager.release()
                }
            }
        }

        // 專門盯著「被電腦端踢出信任名單」這件事，跟 orchestrator 的一般失敗重試邏輯分開處理
        viewModelScope.launch {
            wsClient.connState.collect { state ->
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
                }
            }
        }

        orchestrator.start()
    }

    /** 提供給 ConnectionOrchestrator 呼叫的「撥一次號，等結果」轉接頭。 */
    private suspend fun dialAdapter(host: String, port: Int, token: String): Boolean {
        lastDialToken = token
        var result = false
        val job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // drop(1)：忽略呼叫前就已經存在的舊狀態，只看這次撥號之後真正發生的變化
            // first{}：找到第一個「終局狀態」就結束，不用手動管理 cancel
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

    fun updateAppActive(active: Boolean) {
        isAppActive = active
        if (active) {
            orchestrator.start()
        } else {
            orchestrator.stop()
            wsClient.close()
            _connState.value = ConnState.DISCONNECTED
            wifiPerformanceManager.release()
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

    // 點擊未配對電腦時：直接發起一鍵連線，並顯示等待頁面。
    // 這段完全繞過 orchestrator（配對新設備跟「找回已配對電腦」是兩件事），
    // 所以連線嘗試前先暫停 orchestrator，避免兩邊搶著用同一個 wsClient。
    fun triggerPairing(server: DiscoveredServer) {
        orchestrator.stop()
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
        orchestrator.start()
    }

    // TODO: 待清單面板三色分區 UI 完成後，這裡要改成優先用背景掃描已經拿到的
    // host/port 直接呼叫 orchestrator.manualConnect(server, host)，
    // 而不是整個重跑一次完整流程。
    fun selectServer(uuid: String) {
        Log.d("SWITCH_PC", "手動切換至電腦 UUID: $uuid")
        pairingManager.setSelectedServerUuid(uuid)
        wifiNetworkIdProvider.getCurrentBssid()?.let { bssid ->
            networkProfileStore.setDefaultServerUuid(bssid, uuid)
        }
        wsClient.close()
        _connState.value = ConnState.DISCONNECTED
        _pairingNavState.value = PairingNavState.Hidden
        orchestrator.start()
    }

    fun deleteServer(uuid: String) {
        val activeUuid = pairingManager.getSelectedServerUuid()
        if (activeUuid == uuid && _connState.value == ConnState.CONNECTED) {
            runCatching { wsClient.sendUnpairRequest() }
            wsClient.close()
            _connState.value = ConnState.DISCONNECTED
        }
        pairingManager.deleteServer(uuid)
        networkProfileStore.removeAllReferencesTo(uuid)
        refreshServerLists()
    }

    fun closeServerSelector() {
        pendingPairServer = null
        _pairingNavState.value = PairingNavState.Hidden
    }

    fun openServerSelector() {
        _pairingError.value = null
        pendingPairServer = null
        refreshServerLists()
        _pairingNavState.value = PairingNavState.DeviceList
        startPairingScan()
    }

    // 只是為了刷新清單畫面用的「唯讀」掃描，不影響、不取消 orchestrator 背景正在跑的流程
    private fun startPairingScan() {
        _unpairedDiscovered.value = emptyList()
        _isScanning.value = true
        pairingManager.discover(
            timeoutMs = 5000,
            onFound = { server ->
                val savedList = pairingManager.getSavedServers()
                if (!savedList.any { it.uuid == server.uuid }) {
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
        orchestrator.start()
    }

    override fun onCleared() {
        super.onCleared()
        orchestrator.stop()
        wsClient.close()
        wifiPerformanceManager.release()
    }
}