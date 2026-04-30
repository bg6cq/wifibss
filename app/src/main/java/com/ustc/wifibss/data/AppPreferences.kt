package com.ustc.wifibss.data

import android.content.SharedPreferences
import com.ustc.wifibss.R
import com.ustc.wifibss.model.BssLocalEntry
import com.ustc.wifibss.model.QueryHistory

class AppPreferences(private val prefs: SharedPreferences) {

    private val autoRefreshResIds = mapOf(
        0 to R.string.auto_refresh_never,
        1000 to R.string.auto_refresh_1s,
        5000 to R.string.auto_refresh_5s,
        10000 to R.string.auto_refresh_10s
    )

    // 查询设置
    fun getQueryUrl(): String = prefs.getString(KEY_QUERY_URL, DEFAULT_QUERY_URL) ?: DEFAULT_QUERY_URL
    fun getQueryKey(): String = prefs.getString(KEY_QUERY_KEY, "") ?: ""
    fun saveSettings(url: String, key: String) {
        prefs.edit()
            .putString(KEY_QUERY_URL, url.takeIf { it.isNotBlank() } ?: DEFAULT_QUERY_URL)
            .putString(KEY_QUERY_KEY, key)
            .apply()
    }

    // 自动查询
    fun isAutoQueryEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_QUERY, false)
    fun saveAutoQuery(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_QUERY, enabled).apply()

    // 缓存设置
    fun isCacheApInfoEnabled(): Boolean = prefs.getBoolean(KEY_CACHE_AP_INFO, false)
    fun saveCacheApInfo(enabled: Boolean) = prefs.edit().putBoolean(KEY_CACHE_AP_INFO, enabled).apply()

    // 自动刷新
    fun getAutoRefreshInterval(): Int = prefs.getInt(KEY_AUTO_REFRESH, DEFAULT_AUTO_REFRESH)
    fun saveAutoRefreshInterval(intervalMs: Int) = prefs.edit().putInt(KEY_AUTO_REFRESH, intervalMs).apply()

    // 历史记录
    fun getHistoryList(): String = prefs.getString(KEY_HISTORY, "") ?: ""
    fun saveHistoryList(json: String) = prefs.edit().putString(KEY_HISTORY, json).apply()
    fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    // 本地 BSSMAC
    fun getBssLocalList(): String = prefs.getString(KEY_BSS_LOCAL, "") ?: ""
    fun saveBssLocalList(json: String) = prefs.edit().putString(KEY_BSS_LOCAL, json).apply()

    fun parseHistoryList(json: String): List<QueryHistory> {
        if (json.isEmpty()) return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                QueryHistory(
                    timestamp = obj.getLong("timestamp"),
                    bssid = obj.getString("bssid"),
                    apName = obj.getString("apName"),
                    building = obj.getString("building")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseBssLocalList(json: String): List<BssLocalEntry> {
        if (json.isEmpty()) return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                BssLocalEntry(
                    bssMac = obj.getString("bssMac"),
                    apName = obj.getString("apName"),
                    building = obj.getString("building")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val PREFS_NAME = "app_settings"
        const val DEFAULT_QUERY_URL = "https://linux.ustc.edu.cn/api/bssinfo.php"
        const val DEFAULT_AUTO_REFRESH = 0
        private const val KEY_QUERY_URL = "query_url"
        private const val KEY_QUERY_KEY = "query_key"
        private const val KEY_AUTO_QUERY = "auto_query"
        private const val KEY_AUTO_REFRESH = "auto_refresh"
        private const val KEY_HISTORY = "query_history"
        private const val KEY_BSS_LOCAL = "bss_local_data"
        private const val KEY_CACHE_AP_INFO = "cache_ap_info"
    }
}
