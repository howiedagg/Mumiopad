package com.example.vrtouchpad.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.PairingManager
import com.example.vrtouchpad.data.SavedServer
import com.example.vrtouchpad.data.SettingsStore
import com.example.vrtouchpad.network.ConnState
import com.example.vrtouchpad.network.TouchpadWebSocketClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TouchpadViewModel(context: Context) : ViewModel() {
    private val pairingManager = PairingManager(context)
    private val settingsStore = SettingsStore(context)

    private val _connState = MutableStateFlow(ConnState.DISCONNECTED)
    val connState: StateFlow<ConnState> = _connState

    private val _unpairedDiscovered = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val unpairedDiscovered: StateFlow<List<DiscoveredServer>> = _unpairedDiscovered

    private val _savedServers = MutableStateFlow<List<SavedServer>>(emptyList())
    val savedServers: StateFlow<List<SavedServer>> = _savedServers

    private val _showPairDialog = MutableStateFlow(false)
    val showPairDialog: StateFlow<Boolean> = _showPairDialog

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

    // 【新增】：反向滾動狀態流
    private val _reverseScroll = MutableStateFlow(settingsStore.reverseScroll)
    val reverseScroll: StateFlow<Boolean> = _reverseScroll

    private val _pairCodeInput = MutableStateFlow("")
    val pairCodeInput: StateFlow<String> = _pairCodeInput

    private val _targetServerToPair = MutableStateFlow<DiscoveredServer?>(null)
    val targetServerToPair: StateFlow<DiscoveredServer?> = _targetServerToPair

    private var isAppActive = true
    private var supervisorJob: Job? = null

    val wsClient = TouchpadWebSocketClient(
        onStateChange = { _connState.value = it },
        onPairSuccess = { token, pcName ->
            _isPairingBusy.value = false
            _pairingError.value = null

            _targetServerToPair.value?.let { server ->
                pairingManager.saveServer(server.uuid, pcName, token)
                pairingManager.setSelectedServerUuid(server.uuid)
            }

            _targetServerToPair.value = null
            refreshServerLists()
            _connState.value = ConnState.CONNECTED
            _showPairDialog.value = false
            _unpairedDiscovered.value = emptyList()
        },
        onPairFail = { reason ->
            _isPairingBusy.value = false
            _pairingError.value = when (reason) {
                "invalid_code" -> "配對驗證碼錯誤，請重新確認電腦端畫面"
                "network_error" -> "連線失敗，請確認手機與電腦处于相同 Wi-Fi 網路"
                else -> "配對失敗: $reason"
            }
            _connState.value = ConnState.DISCONNECTED
        }
    )

    init {
        refreshServerLists()
        startSupervisorLoop()
    }

    private fun refreshServerLists() {
        _savedServers.value = pairingManager.getSavedServers()
    }

    fun updateAppActive(active: Boolean) {
        isAppActive = active
        if (active) {
            startSupervisorLoop()
        } else {
            stopSupervisorLoop()
            wsClient.close()
            _connState.value = ConnState.DISCONNECTED
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

    // 【新增】：更新反向滾動設定
    fun updateReverseScroll(reverse: Boolean) {
        _reverseScroll.value = reverse
        settingsStore.reverseScroll = reverse
    }

    fun updatePairCode(code: String) {
        _pairCodeInput.value = code
    }

    fun triggerPairing(server: DiscoveredServer) {
        _targetServerToPair.value = server
        _pairingError.value = null
        _showPairDialog.value = true
    }

    fun cancelPairing() {
        _targetServerToPair.value = null
        _pairingError.value = null
        _pairCodeInput.value = ""
    }

    fun selectServer(uuid: String) {
        Log.d("SWITCH_PC", "手動切換至電腦 UUID: $uuid")
        pairingManager.setSelectedServerUuid(uuid)
        wsClient.close()
        _connState.value = ConnState.DISCONNECTED
        _showPairDialog.value = false
        forceReconnect()
    }

    fun deleteServer(uuid: String) {
        val activeUuid = pairingManager.getSelectedServerUuid()
        if (activeUuid == uuid && _connState.value == ConnState.CONNECTED) {
            runCatching {
                wsClient.sendUnpairRequest()
            }
            wsClient.close()
            _connState.value = ConnState.DISCONNECTED
        }
        pairingManager.deleteServer(uuid)
        refreshServerLists()
    }

    fun closeServerSelector() {
        _showPairDialog.value = false
        _targetServerToPair.value = null
    }

    fun startPairing(code: String) {
        val server = _targetServerToPair.value ?: return
        _isPairingBusy.value = true
        _pairingError.value = null
        wsClient.connectForPairing(server.host, server.port, code.trim())
    }

    fun openServerSelector() {
        _pairingError.value = null
        _pairCodeInput.value = ""
        _targetServerToPair.value = null
        refreshServerLists()
        _showPairDialog.value = true
        startPairingScan()
    }

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
        _connState.value = ConnState.DISCONNECTED
        stopSupervisorLoop()
        startSupervisorLoop()
    }

    private fun startSupervisorLoop() {
        if (supervisorJob?.isActive == true) return
        supervisorJob = viewModelScope.launch {
            while (true) {
                if (_connState.value == ConnState.DISCONNECTED && isAppActive && !_showPairDialog.value) {
                    _connState.value = ConnState.CONNECTING
                    Log.d("AUTO_CONNECT", "搜尋可用電腦中...")

                    var scanFinished = false
                    val newUnpairedList = mutableListOf<DiscoveredServer>()
                    val savedServers = pairingManager.getSavedServers()
                    val preferredUuid = pairingManager.getSelectedServerUuid()

                    pairingManager.discover(
                        timeoutMs = 3500,
                        onFound = { server ->
                            val matchedSaved = savedServers.firstOrNull { it.uuid == server.uuid }
                            if (matchedSaved != null) {
                                if (preferredUuid == null || preferredUuid == server.uuid) {
                                    Log.d("AUTO_CONNECT", "匹配成功！自動連線至: ${matchedSaved.name}")
                                    wsClient.connectWithToken(server.host, server.port, matchedSaved.token)
                                    pairingManager.setSelectedServerUuid(server.uuid)
                                    scanFinished = true
                                }
                            } else {
                                if (!newUnpairedList.any { it.uuid == server.uuid }) {
                                    newUnpairedList.add(server)
                                }
                            }
                        },
                        onFinished = {
                            scanFinished = true
                        }
                    )

                    val startWaiting = System.currentTimeMillis()
                    while (!scanFinished && System.currentTimeMillis() - startWaiting < 4000) {
                        delay(200)
                    }

                    if (_connState.value != ConnState.CONNECTED) {
                        _unpairedDiscovered.value = newUnpairedList
                        _connState.value = ConnState.DISCONNECTED
                        delay(4000)
                    } else {
                        _unpairedDiscovered.value = emptyList()
                    }
                } else {
                    delay(1000)
                }
            }
        }
    }

    private fun stopSupervisorLoop() {
        supervisorJob?.cancel()
        supervisorJob = null
    }

    override fun onCleared() {
        super.onCleared()
        wsClient.close()
    }
}