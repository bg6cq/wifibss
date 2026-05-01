package com.ustc.wifibss.data

import android.content.SharedPreferences
import android.content.Context
import com.ustc.wifibss.R
import com.ustc.wifibss.R.string
import kotlinx.coroutines.flow.first

class AppPreferences(private val context: Context,
                     private val prefs: SharedPreferences? = null) {

    companion object {
        const val PREFS_NAME = "app_settings"
        const val DEFAULT_QUERY_URL = "https://linux.ustc.edu.cn/api/bssinfo.php"
        const val DEFAULT_AUTO_REFRESH = 1000
    }

    // 设置存储（DataStore）
    private val store = com.ustc.wifibss.datastore.SettingsDataStore(context)

    // ==================== 查询设置 ====================
    suspend fun getQueryUrl(): String {
        val fromStore = store.getQueryUrl()
        return if (fromStore == DEFAULT_QUERY_URL) {
            // 兼容旧数据
            prefs?.getString("query_url", null)?.takeIf { it != DEFAULT_QUERY_URL } ?: fromStore
        } else {
            fromStore
        }
    }

    suspend fun getQueryKey(): String {
        val fromStore = store.getQueryKey()
        return fromStore.ifBlank { prefs?.getString("query_key", "") ?: "" }
    }

    suspend fun saveSettings(url: String, key: String) {
        store.saveSettings(url, key)
    }

    // ==================== 自动查询 ====================
    suspend fun isAutoQueryEnabled(): Boolean {
        val fromStore = store.autoQueryEnabledFlow.first()
        return if (!fromStore) {
            prefs?.getBoolean("auto_query", false) ?: false
        } else {
            fromStore
        }
    }

    suspend fun saveAutoQuery(enabled: Boolean) {
        store.saveAutoQuery(enabled)
    }

    // ==================== 缓存设置 ====================
    suspend fun isCacheApInfoEnabled(): Boolean {
        val fromStore = store.cacheApInfoEnabledFlow.first()
        return if (!fromStore) {
            prefs?.getBoolean("cache_ap_info", false) ?: false
        } else {
            fromStore
        }
    }

    suspend fun saveCacheApInfo(enabled: Boolean) {
        store.saveCacheApInfo(enabled)
    }

    // ==================== 自动刷新 ====================
    suspend fun getAutoRefreshInterval(): Int {
        val fromStore = store.autoRefreshIntervalFlow.first()
        return if (fromStore == 0) {
            prefs?.getInt("auto_refresh", DEFAULT_AUTO_REFRESH) ?: DEFAULT_AUTO_REFRESH
        } else {
            fromStore
        }
    }

    suspend fun saveAutoRefreshInterval(intervalMs: Int) {
        store.saveAutoRefreshInterval(intervalMs)
    }

    fun getAutoRefreshLabelResId(interval: Int): Int = when (interval) {
        1000 -> string.auto_refresh_1s
        3000 -> string.auto_refresh_3s
        5000 -> string.auto_refresh_5s
        else -> string.auto_refresh_1s
    }

    // ==================== 自动检查更新 ====================
    suspend fun isAutoCheckUpdateEnabled(): Boolean {
        return store.autoCheckUpdateFlow.first()
    }

    suspend fun saveAutoCheckUpdate(enabled: Boolean) {
        store.saveAutoCheckUpdate(enabled)
    }

    // ==================== 历史记录（迁移中，旧方法保留） ====================
    fun getHistoryList(): String = prefs?.getString("query_history", "") ?: ""
    fun saveHistoryList(json: String) = prefs?.edit()?.putString("query_history", json)?.apply()
    fun clearHistory() = prefs?.edit()?.remove("query_history")?.apply()

    // ==================== 本地 BSSMAC（迁移中，旧方法保留） ====================
    fun getBssLocalList(): String = prefs?.getString("bss_local_data", "") ?: ""
    fun saveBssLocalList(json: String) = prefs?.edit()?.putString("bss_local_data", json)?.apply()
}
