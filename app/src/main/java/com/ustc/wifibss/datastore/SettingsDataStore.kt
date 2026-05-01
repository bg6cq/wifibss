package com.ustc.wifibss.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "app_settings")

class SettingsDataStore(private val context: Context) {
    companion object {
        private val KEY_QUERY_URL = stringPreferencesKey("query_url")
        private val KEY_QUERY_KEY = stringPreferencesKey("query_key")
        private val KEY_AUTO_QUERY = booleanPreferencesKey("auto_query")
        private val KEY_AUTO_REFRESH = intPreferencesKey("auto_refresh")
        private val KEY_CACHE_AP_INFO = booleanPreferencesKey("cache_ap_info")
        private val KEY_AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
        private val KEY_SP_MIGRATED = booleanPreferencesKey("sp_migrated")

        const val DEFAULT_QUERY_URL = "https://linux.ustc.edu.cn/api/bssinfo.php"
    }

    /**
     * 一次性从 SharedPreferences 迁移设置到 DataStore
     */
    suspend fun migrateFromSharedPreferencesIfNeeded(prefs: SharedPreferences?) {
        if (prefs == null) return
        val alreadyMigrated = context.dataStore.data.map { it[KEY_SP_MIGRATED] ?: false }.first()
        if (alreadyMigrated) return

        context.dataStore.edit { store ->
            store[KEY_SP_MIGRATED] = true
            prefs.getString("query_url", null)?.let { if (it.isNotBlank()) store[KEY_QUERY_URL] = it }
            prefs.getString("query_key", null)?.let { if (it.isNotBlank()) store[KEY_QUERY_KEY] = it }
            if (prefs.contains("auto_query")) store[KEY_AUTO_QUERY] = prefs.getBoolean("auto_query", false)
            if (prefs.contains("cache_ap_info")) store[KEY_CACHE_AP_INFO] = prefs.getBoolean("cache_ap_info", false)
            if (prefs.contains("auto_refresh")) store[KEY_AUTO_REFRESH] = prefs.getInt("auto_refresh", 1000)
        }
    }

    val autoRefreshIntervalFlow: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[KEY_AUTO_REFRESH] ?: 1000 }

    val autoQueryEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[KEY_AUTO_QUERY] ?: false }

    val cacheApInfoEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[KEY_CACHE_AP_INFO] ?: true }

    val autoCheckUpdateFlow: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[KEY_AUTO_CHECK_UPDATE] ?: true }

    suspend fun getQueryUrl(): String {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_QUERY_URL] ?: DEFAULT_QUERY_URL
        }.first()
    }

    suspend fun getQueryKey(): String {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_QUERY_KEY] ?: ""
        }.first()
    }

    suspend fun saveSettings(url: String, key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_QUERY_URL] = url.ifBlank { DEFAULT_QUERY_URL }
            prefs[KEY_QUERY_KEY] = key
        }
    }

    suspend fun saveAutoQuery(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_QUERY] = enabled }
    }

    suspend fun saveCacheApInfo(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_CACHE_AP_INFO] = enabled }
    }

    suspend fun saveAutoRefreshInterval(intervalMs: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_REFRESH] = intervalMs }
    }

    suspend fun saveAutoCheckUpdate(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_CHECK_UPDATE] = enabled }
    }
}
