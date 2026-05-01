package com.ustc.wifibss.database

import android.content.SharedPreferences
import com.ustc.wifibss.datastore.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 从 SharedPreferences 迁移数据到 Room
 * 只在首次安装/升级时运行一次，运行成功后删除 SharedPreferences 中的数据
 */
object DataMigration {
    private const val KEY_MIGRATED_TO_ROOM = "migrated_to_room"

    suspend fun migrateIfNeeded(
        database: WifiBssDatabase,
        prefs: SharedPreferences,
        settingsDataStore: SettingsDataStore
    ) {
        val alreadyMigrated = prefs.getBoolean(KEY_MIGRATED_TO_ROOM, false)
        if (alreadyMigrated) return

        withContext(Dispatchers.IO) {
            try {
                // 迁移历史记录
                migrateQueryHistory(database, prefs)

                // 迁移本地 BSSMAC
                migrateBssLocal(database, prefs)

                // 迁移设置到 DataStore
                settingsDataStore.migrateFromSharedPreferencesIfNeeded(prefs)

                // 标记迁移完成
                prefs.edit().putBoolean(KEY_MIGRATED_TO_ROOM, true).apply()
            } catch (e: Exception) {
                // 迁移失败不阻塞应用，下次启动会重试
            }
        }
    }

    private suspend fun migrateQueryHistory(
        database: WifiBssDatabase,
        prefs: SharedPreferences
    ) {
        val json = prefs.getString("query_history", "") ?: return
        if (json.isBlank()) return

        runCatching {
            val array = JSONArray(json)
            val entities = (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                QueryHistoryEntity(
                    timestamp = obj.getLong("timestamp"),
                    bssid = obj.getString("bssid"),
                    apName = obj.getString("apName"),
                    building = obj.getString("building")
                )
            }
            if (entities.isNotEmpty()) {
                database.queryHistoryDao().insertAll(entities)
            }
        }
    }

    private suspend fun migrateBssLocal(
        database: WifiBssDatabase,
        prefs: SharedPreferences
    ) {
        val json = prefs.getString("bss_local_data", "") ?: return
        if (json.isBlank()) return

        runCatching {
            val array = JSONArray(json)
            val entities = (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                BssLocalEntity(
                    bssMac = obj.getString("bssMac"),
                    apName = obj.getString("apName"),
                    building = obj.getString("building")
                )
            }
            if (entities.isNotEmpty()) {
                database.bssLocalDao().insertAll(entities)
            }
        }
    }
}
