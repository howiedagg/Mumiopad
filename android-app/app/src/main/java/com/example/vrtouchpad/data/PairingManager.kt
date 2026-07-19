// android-app/app/src/main/java/com/example/vrtouchpad/data/PairingManager.kt
package com.example.vrtouchpad.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

private const val SERVICE_TYPE = "_mumiopad._tcp."
private const val PREFS_NAME = "mumiopad_secure_tokens"
private const val KEY_SERVER_LIST = "server_profiles_list"
private const val KEY_SELECTED_UUID = "selected_server_uuid"

data class SavedServer(
    val uuid: String,
    val name: String,
    val token: String,
    val lastKnownIp: String? = null,
)

data class DiscoveredServer(val uuid: String, val host: String, val port: Int, val name: String)

class PairingManager(private val context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            context.getSharedPreferences("vrtouchpad_fallback_tokens", Context.MODE_PRIVATE)
        }
    }

    fun getSavedServers(): List<SavedServer> {
        val jsonStr = prefs.getString(KEY_SERVER_LIST, "[]") ?: "[]"
        val list = mutableListOf<SavedServer>()
        runCatching {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SavedServer(
                        uuid = obj.getString("uuid"),
                        name = obj.getString("name"),
                        token = obj.getString("token"),
                        lastKnownIp = if (obj.has("lastKnownIp") && !obj.isNull("lastKnownIp")) {
                            obj.getString("lastKnownIp")
                        } else null,
                    )
                )
            }
        }
        return list
    }

    fun saveServer(uuid: String, name: String, token: String) {
        val currentList = getSavedServers().toMutableList()
        val index = currentList.indexOfFirst { it.uuid == uuid }
        val newProfile =
            SavedServer(uuid, name, token, lastKnownIp = currentList.getOrNull(index)?.lastKnownIp)
        if (index != -1) {
            currentList[index] = newProfile
        } else {
            currentList.add(newProfile)
        }
        writeListToPrefs(currentList)
    }

    fun updateLastKnownIp(uuid: String, ip: String) {
        val currentList = getSavedServers().toMutableList()
        val index = currentList.indexOfFirst { it.uuid == uuid }
        if (index != -1) {
            currentList[index] = currentList[index].copy(lastKnownIp = ip)
            writeListToPrefs(currentList)
        }
    }

    fun deleteServer(uuid: String) {
        val currentList = getSavedServers().filter { it.uuid != uuid }
        writeListToPrefs(currentList)
        if (getSelectedServerUuid() == uuid) {
            clearSelectedServerUuid()
        }
    }

    fun getSelectedServerUuid(): String? = prefs.getString(KEY_SELECTED_UUID, null)

    fun setSelectedServerUuid(uuid: String) {
        prefs.edit().putString(KEY_SELECTED_UUID, uuid).apply()
    }

    private fun clearSelectedServerUuid() {
        prefs.edit().remove(KEY_SELECTED_UUID).apply()
    }

    private fun writeListToPrefs(list: List<SavedServer>) {
        val array = JSONArray()
        list.forEach { server ->
            val obj = JSONObject().apply {
                put("uuid", server.uuid)
                put("name", server.name)
                put("token", server.token)
                put("lastKnownIp", server.lastKnownIp)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_SERVER_LIST, array.toString()).apply()
    }

    @Suppress("DEPRECATION")
    fun discover(
        timeoutMs: Long = 4000,
        onFound: (DiscoveredServer) -> Unit,
        onFinished: () -> Unit
    ): () -> Unit { // 【修改】：回傳一個取消連線控制函式
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        var finished = false
        val handler = android.os.Handler(context.mainLooper)

        try {
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("vrtouchpad_multicast_lock")
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val safeReleaseLock = {
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (finished) return

                val name = service.serviceName ?: return
                if (!name.startsWith("Mumiopad_")) return
                val uuidPart = name.substringAfter("Mumiopad_").substringBefore(".")

                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (finished) return
                        val address = info.host.hostAddress ?: return

                        if (address.contains(":")) return

                        val pcNameBytes = info.attributes["pc_name"]
                        val pcName = if (pcNameBytes != null) String(
                            pcNameBytes,
                            Charsets.UTF_8
                        ) else ""

                        onFound(DiscoveredServer(uuidPart, address, info.port, pcName))
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                safeReleaseLock()
                if (!finished) {
                    finished = true
                    onFinished()
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        }

        val timeoutRunnable = Runnable {
            if (!finished) {
                finished = true
                safeReleaseLock()
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                onFinished()
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        handler.postDelayed(timeoutRunnable, timeoutMs)

        // 【新增】：回傳手動關閉掃描的閉包，供協程取消時使用
        return {
            if (!finished) {
                finished = true
                handler.removeCallbacks(timeoutRunnable)
                safeReleaseLock()
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            }
        }
    }
}