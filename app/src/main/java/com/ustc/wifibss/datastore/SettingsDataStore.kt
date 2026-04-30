package com.ustc.wifibss.datastore

import android.content.Context
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

        const val DEFAULT_QUERY_URL = "https://linux.ustc.edu.cn/api/bssinfo.php"
    }

    val autoRefreshIntervalFlow: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[KEY_AUTO_REFRESH] ?: 0 }

    val autoQueryEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[KEY_AUTO_QUERY] ?: false }

    val cacheApInfoEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[KEY_CACHE_AP_INFO] ?: false }

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
}
