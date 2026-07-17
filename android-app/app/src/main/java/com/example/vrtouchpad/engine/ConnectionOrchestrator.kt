package com.example.vrtouchpad.engine

import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.NetworkProfileStore
import com.example.vrtouchpad.data.SavedServer
import com.example.vrtouchpad.data.WifiNetworkIdProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 這個類別畫面上顯示什麼燈號、UI 長怎樣、WebSocket 怎麼實作，一概不知道。
 * 只負責一件事：照著規格跑「該搜尋、該撥號、該等多久」這條時間軸，
 * 並把目前走到哪一步以 [phase] 吐出去讓外面(ViewModel)訂閱。
 *
 * 需要外部注入四個「純資料查詢」與「動作」函式，讓這個類別可以被單元測試：
 *  - getSavedServers：目前所有配對過的電腦
 *  - getLastKnownIp / updateLastKnownIp：某台電腦上次連上時的 IP（熱直連捷徑要用）
 *  - discover：一次 mDNS 掃描（沿用 PairingManager.discover 的簽名，可直接傳進來）
 *  - dial：實際發起一次連線嘗試，回傳是否連線成功（由外面用 WebSocket 實作）
 */
class ConnectionOrchestrator(
    private val scope: CoroutineScope,
    private val wifiNetworkIdProvider: WifiNetworkIdProvider,
    private val networkProfileStore: NetworkProfileStore,
    private val getSavedServers: () -> List<SavedServer>,
    private val getLastKnownIp: (uuid: String) -> String?,
    private val updateLastKnownIp: (uuid: String, ip: String) -> Unit,
    private val discover: (timeoutMs: Long, onFound: (DiscoveredServer) -> Unit, onFinished: () -> Unit) -> (() -> Unit),
    private val dial: suspend (host: String, port: Int, token: String) -> Boolean,
    private val defaultPort: Int = 8765,
) {
    sealed class Phase {
        /** 完全靜止，沒有任何搜尋或連線動作在跑。對應灰燈。 */
        object Idle : Phase()
        /** 正在搜尋或撥號中，不細分給 UI 看。對應橘燈。 */
        object Connecting : Phase()
        /** 已連線成功。對應綠燈 + 電腦名稱。 */
        data class Connected(val serverName: String) : Phase()
    }

    private data class Matched(val server: SavedServer, val host: String, val port: Int)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    private var job: Job? = null

    /** App 進到前景 / 使用者手動觸發重連時呼叫。永遠從頭走一次完整流程。 */
    fun start() {
        job?.cancel()
        job = scope.launch { runFullFlow() }
    }

    /** App 退到背景、螢幕鎖定時呼叫。立刻中止所有搜尋與等待，狀態歸零，不留殘餘進度。 */
    fun stop() {
        job?.cancel()
        job = null
        _phase.value = Phase.Idle
    }

    /** 使用者在清單裡手動點了某台電腦（不論在線/離線）時呼叫，直接跳過搜尋去撥號。 */
    fun manualConnect(server: SavedServer, host: String, port: Int = defaultPort) {
        job?.cancel()
        job = scope.launch {
            val ok = dialWithWatchdogAndRetry(Matched(server, host, port))
            if (!ok) _phase.value = Phase.Idle
        }
    }

    private suspend fun runFullFlow() {
        val savedServers = getSavedServers()

        // 1. 熱直連捷徑：這個地方之前記住的電腦 + 上次已知 IP，直接衝
        val bssid = wifiNetworkIdProvider.getCurrentBssid()
        val defaultUuid = bssid?.let { networkProfileStore.getDefaultServerUuid(it) }
        val defaultServer = savedServers.find { it.uuid == defaultUuid }
        if (defaultServer != null) {
            val lastIp = getLastKnownIp(defaultServer.uuid)
            if (lastIp != null) {
                _phase.value = Phase.Connecting
                val ok = withTimeoutOrNull(WARM_DIRECT_TIMEOUT_MS) {
                    dial(lastIp, defaultPort, defaultServer.token)
                } == true
                if (ok) {
                    onConnected(Matched(defaultServer, lastIp, defaultPort), bssid)
                    return
                }
            }
        }

        // 2. 冷啟動 5 秒衝刺掃描
        _phase.value = Phase.Connecting
        val firstFound = scanOnce(COLD_SCAN_MS, getSavedServers())
        if (firstFound != null && dialWithWatchdogAndRetry(firstFound)) return

        // 3. 冷靜重試，每隔一段時間聽一下，最多 3 分鐘
        val deadline = System.currentTimeMillis() + COOL_RETRY_TOTAL_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            _phase.value = Phase.Connecting
            delay(COOL_RETRY_QUIET_INTERVAL_MS)
            val found = scanOnce(COOL_RETRY_LISTEN_MS, getSavedServers())
            if (found != null && dialWithWatchdogAndRetry(found)) return
        }

        // 3 分鐘到了都沒找到，徹底休息
        _phase.value = Phase.Idle
    }

    /** 8 秒看門狗 → 失敗等 2 秒 → 5 秒暖重試。回傳最終是否連線成功。 */
    private suspend fun dialWithWatchdogAndRetry(match: Matched): Boolean {
        _phase.value = Phase.Connecting
        var ok = withTimeoutOrNull(WATCHDOG_MS) { dial(match.host, match.port, match.server.token) } == true
        if (ok) {
            onConnected(match, wifiNetworkIdProvider.getCurrentBssid())
            return true
        }

        delay(RETRY_WAIT_MS)

        _phase.value = Phase.Connecting
        ok = withTimeoutOrNull(WARM_RETRY_MS) { dial(match.host, match.port, match.server.token) } == true
        if (ok) {
            onConnected(match, wifiNetworkIdProvider.getCurrentBssid())
            return true
        }

        return false
    }

    private fun onConnected(match: Matched, bssid: String?) {
        _phase.value = Phase.Connected(match.server.name)
        updateLastKnownIp(match.server.uuid, match.host)
        // 這次成功連上的電腦，之後就成為「這個地方」的預設電腦
        // （不論是自動找到的，還是使用者手動選的，規則一致）
        bssid?.let { networkProfileStore.setDefaultServerUuid(it, match.server.uuid) }
    }

    private suspend fun scanOnce(timeoutMs: Long, savedServers: List<SavedServer>): Matched? {
        return suspendCancellableCoroutine { cont ->
            var resolved = false
            val cancelDiscover = discover(
                timeoutMs,
                { discovered ->
                    if (resolved) return@discover
                    val matchedSaved = savedServers.find { it.uuid == discovered.uuid }
                    if (matchedSaved != null) {
                        resolved = true
                        if (cont.isActive) cont.resumeWith(
                            Result.success(Matched(matchedSaved, discovered.host, discovered.port))
                        )
                    }
                },
                {
                    if (!resolved) {
                        resolved = true
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                }
            )
            cont.invokeOnCancellation {
                cancelDiscover()
            }
        }
    }

    companion object {
        private const val WARM_DIRECT_TIMEOUT_MS = 1500L
        private const val COLD_SCAN_MS = 5000L
        private const val WATCHDOG_MS = 8000L
        private const val RETRY_WAIT_MS = 2000L
        private const val WARM_RETRY_MS = 5000L
        private const val COOL_RETRY_QUIET_INTERVAL_MS = 10000L
        private const val COOL_RETRY_LISTEN_MS = 2000L
        private const val COOL_RETRY_TOTAL_BUDGET_MS = 180_000L // 3 分鐘
    }
}
