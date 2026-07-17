package com.example.vrtouchpad.data

import android.content.Context
import org.json.JSONObject

private const val PREFS_NAME = "vrtouchpad_network_profiles"
private const val KEY_MAP = "bssid_to_server_uuid"

/**
 * 只做一件事：記住「某個 BSSID(某個地方) 上次自動連的電腦是哪一台 (uuid)」。
 *
 * 完全不知道：
 *  - 這台電腦現在在不在線上
 *  - 怎麼連線、要不要重試
 *  - SavedServer 的完整資料(名稱、token 在哪) —— 這些交給 PairingManager 查
 *
 * 這樣 ConnectionOrchestrator 只需要：
 *   1. 問 WifiNetworkIdProvider 現在在哪個 BSSID
 *   2. 問這個類別「這個地方預設連哪個 uuid」
 *   3. 拿著 uuid 去問 PairingManager 要 token / 最後已知 IP
 * 三個模組互相不需要認識對方的實作細節。
 */
class NetworkProfileStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 取得某個地點（BSSID）目前記住的預設電腦 uuid，沒記錄過就回傳 null。 */
    fun getDefaultServerUuid(bssid: String): String? {
        val map = readMap()
        return if (map.has(bssid)) map.optString(bssid) else null
    }

    /** 記住／覆寫「這個地點下次自動連這台電腦」。使用者手動選了別台時呼叫這個。 */
    fun setDefaultServerUuid(bssid: String, serverUuid: String) {
        val map = readMap()
        map.put(bssid, serverUuid)
        writeMap(map)
    }

    /**
     * 當某台電腦被整個刪除（使用者手動刪除，或被電腦端踢出信任）時呼叫，
     * 把所有地點裡「指向這台電腦」的記錄一併清掉，避免留下指向不存在電腦的殘影。
     */
    fun removeAllReferencesTo(serverUuid: String) {
        val map = readMap()
        val keysToRemove = mutableListOf<String>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val bssid = keys.next()
            if (map.optString(bssid) == serverUuid) {
                keysToRemove.add(bssid)
            }
        }
        keysToRemove.forEach { map.remove(it) }
        writeMap(map)
    }

    private fun readMap(): JSONObject {
        val raw = prefs.getString(KEY_MAP, "{}") ?: "{}"
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun writeMap(map: JSONObject) {
        prefs.edit().putString(KEY_MAP, map.toString()).apply()
    }
}
