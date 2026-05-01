package com.ustc.wifibss.data

import android.content.SharedPreferences
import android.content.Context
import com.ustc.wifibss.R.string
import com.ustc.wifibss.datastore.SettingsDataStore
import kotlinx.coroutines.flow.first

class AppPreferences(private val context: Context,
                     private val prefs: SharedPreferences? = null) {

    companion object {
        const val PREFS_NAME = "app_settings"
        const val DEFAULT_QUERY_URL = "https://linux.ustc.edu.cn/api/bssinfo.php"
        const val DEFAULT_AUTO_REFRESH = 1000
    }

    // 设置存储（DataStore）
    private val _store = SettingsDataStore(context)
    val store: SettingsDataStore get() = _store

    // ==================== 查询设置 ====================
    suspend fun getQueryUrl(): String = store.getQueryUrl()

    suspend fun getQueryKey(): String = store.getQueryKey()

    suspend fun saveSettings(url: String, key: String) {
        store.saveSettings(url, key)
    }

    // ==================== 自动查询 ====================
    suspend fun isAutoQueryEnabled(): Boolean = store.autoQueryEnabledFlow.first()

    suspend fun saveAutoQuery(enabled: Boolean) {
        store.saveAutoQuery(enabled)
    }

    // ==================== 缓存设置 ====================
    suspend fun isCacheApInfoEnabled(): Boolean = store.cacheApInfoEnabledFlow.first()

    suspend fun saveCacheApInfo(enabled: Boolean) {
        store.saveCacheApInfo(enabled)
    }

    // ==================== 自动刷新 ====================
    suspend fun getAutoRefreshInterval(): Int = store.autoRefreshIntervalFlow.first()

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
    suspend fun isAutoCheckUpdateEnabled(): Boolean = store.autoCheckUpdateFlow.first()

    suspend fun saveAutoCheckUpdate(enabled: Boolean) {
        store.saveAutoCheckUpdate(enabled)
    }

    // ==================== 统计 ====================
    suspend fun getStatsApSwitch(): Int = store.getStatsApSwitch()

    suspend fun getStatsQuerySuccess(): Int = store.getStatsQuerySuccess()

    suspend fun getStatsQueryFailure(): Int = store.getStatsQueryFailure()

    suspend fun getStatsCacheHit(): Int = store.getStatsCacheHit()

    suspend fun getStatsLocalHit(): Int = store.getStatsLocalHit()

    suspend fun incrementApSwitch() = store.incrementApSwitch()

    suspend fun incrementQuerySuccess() = store.incrementQuerySuccess()

    suspend fun incrementQueryFailure() = store.incrementQueryFailure()

    suspend fun incrementCacheHit() = store.incrementCacheHit()

    suspend fun incrementLocalHit() = store.incrementLocalHit()

    suspend fun resetAllStats() = store.resetAllStats()
}
