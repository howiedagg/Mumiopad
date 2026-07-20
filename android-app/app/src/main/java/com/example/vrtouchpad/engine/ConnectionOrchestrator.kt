// D:/howie/Documents/vr-touchpad-app/vr-touchpad-app/android-app/app/src/main/java/com/example/vrtouchpad/engine/ConnectionOrchestrator.kt

package com.example.vrtouchpad.engine

import com.example.vrtouchpad.data.DiscoveredServer
import com.example.vrtouchpad.data.NetworkProfileStore
import com.example.vrtouchpad.data.SavedServer
import com.example.vrtouchpad.data.WifiNetworkIdProvider
import com.example.vrtouchpad.ui.DEFAULT_PORT // 💡 修正：引入全域共享的連線埠
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class ConnectionOrchestrator(
    private val scope: CoroutineScope,
    private val wifiNetworkIdProvider: WifiNetworkIdProvider,
    private val networkProfileStore: NetworkProfileStore,
    private val getSavedServers: () -> List<SavedServer>,
    private val getLastKnownIp: (uuid: String) -> String?,
    private val updateLastKnownIp: (uuid: String, ip: String) -> Unit,
    private val discover: (timeoutMs: Long, onFound: (DiscoveredServer) -> Unit, onFinished: () -> Unit) -> (() -> Unit),
    private val dial: suspend (host: String, port: Int, token: String) -> Boolean,
    private val defaultPort: Int = DEFAULT_PORT // 💡 修正：預設值直接綁定全域常數
) {
    sealed class Phase {
        object Idle : Phase()
        object Connecting : Phase()
        data class Connected(val serverName: String) : Phase()
    }

    private data class Matched(val server: SavedServer, val host: String, val port: Int)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch { runFullFlow() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _phase.value = Phase.Idle
    }

    fun manualConnect(server: SavedServer, host: String, port: Int = defaultPort) {
        job?.cancel()
        job = scope.launch {
            val ok = dialWithWatchdogAndRetry(Matched(server, host, port))
            if (!ok) _phase.value = Phase.Idle
        }
    }

    private suspend fun runFullFlow() {
        val savedServers = getSavedServers()

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

        _phase.value = Phase.Connecting
        val firstFound = scanOnce(COLD_SCAN_MS, getSavedServers())
        if (firstFound != null && dialWithWatchdogAndRetry(firstFound)) return

        val deadline = System.currentTimeMillis() + COOL_RETRY_TOTAL_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            _phase.value = Phase.Connecting
            delay(COOL_RETRY_QUIET_INTERVAL_MS)
            val found = scanOnce(COOL_RETRY_LISTEN_MS, getSavedServers())
            if (found != null && dialWithWatchdogAndRetry(found)) return
        }

        _phase.value = Phase.Idle
    }

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