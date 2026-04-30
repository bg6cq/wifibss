package com.ustc.wifibss.repository

import com.ustc.wifibss.api.BssInfoApiService
import com.ustc.wifibss.data.AppPreferences
import com.ustc.wifibss.database.BssLocalEntity
import com.ustc.wifibss.database.QueryHistoryEntity
import com.ustc.wifibss.database.WifiBssDatabase
import com.ustc.wifibss.model.ApInfo
import com.ustc.wifibss.model.BssLocalEntry
import com.ustc.wifibss.model.QueryHistory
import com.ustc.wifibss.util.WifiUtils

class BssRepository(
    private val prefs: AppPreferences,
    private val database: WifiBssDatabase
) {
    private val apiService = BssInfoApiService(prefs)
    private val historyDao = database.queryHistoryDao()
    private val bssLocalDao = database.bssLocalDao()

    // ==================== 历史记录 ====================

    suspend fun getHistoryList(): List<QueryHistory> {
        return historyDao.getLatest(MAX_HISTORY_COUNT)
            .map(QueryHistoryEntity::toQueryHistory)
    }

    suspend fun addHistoryRecord(bssid: String, apName: String, building: String) {
        val list = historyDao.getLatest(MAX_HISTORY_COUNT + 1)
            .map(QueryHistoryEntity::toQueryHistory)
            .toMutableList()

        val lastRecord = list.lastOrNull()
        if (lastRecord != null && lastRecord.bssid == bssid) {
            if (lastRecord.apName.isEmpty() && apName.isNotEmpty()) {
                list.removeAt(list.size - 1)
                list.add(QueryHistory(System.currentTimeMillis(), bssid, apName, building))
                saveHistoryList(list)
                return
            }
            if (lastRecord.apName.isNotEmpty() && apName.isNotEmpty()) return
            if (lastRecord.apName.isEmpty() && apName.isEmpty()) return
        }

        list.add(QueryHistory(System.currentTimeMillis(), bssid, apName, building))
        if (list.size > MAX_HISTORY_COUNT) list.removeAt(0)
        saveHistoryList(list)
    }

    suspend fun updateHistoryRecord(bssid: String, apName: String, building: String) {
        val list = historyDao.getLatest(MAX_HISTORY_COUNT + 1)
            .map(QueryHistoryEntity::toQueryHistory)
            .toMutableList()

        for (i in list.size - 1 downTo 0) {
            if (list[i].bssid == bssid && list[i].apName.isEmpty()) {
                // 找到对应的 entity 并更新
                val entities = historyDao.getLatest(MAX_HISTORY_COUNT)
                for (entity in entities) {
                    if (entity.bssid == bssid && entity.apName.isEmpty()) {
                        val updated = entity.copy(
                            apName = apName,
                            building = building
                        )
                        historyDao.update(updated)
                        return
                    }
                }
                break
            }
        }
        addHistoryRecord(bssid, apName, building)
    }

    suspend fun clearHistory() {
        historyDao.clearAll()
    }

    private suspend fun saveHistoryList(list: List<QueryHistory>) {
        val entities = list.mapIndexed { index, h ->
            QueryHistoryEntity(
                id = index.toLong(),
                timestamp = h.timestamp,
                bssid = h.bssid,
                apName = h.apName,
                building = h.building
            )
        }
        historyDao.clearAll()
        historyDao.insertAll(entities)
    }

    // ==================== 本地 BSSMAC ====================

    suspend fun getBssLocalList(): List<BssLocalEntry> =
        bssLocalDao.getAll().map(BssLocalEntity::toBssLocalEntry)

    suspend fun addBssLocal(bssMac: String, apName: String, building: String): Boolean {
        val normalizedMac = WifiUtils.normalizeBssMac(bssMac) ?: return false
        val existing = bssLocalDao.getByBssMac(normalizedMac)
        if (existing != null) {
            bssLocalDao.update(existing.copy(apName = apName, building = building))
        } else {
            bssLocalDao.insert(BssLocalEntity(bssMac = normalizedMac, apName = apName, building = building))
        }
        return true
    }

    suspend fun deleteBssLocal(bssMac: String) {
        val normalizedMac = WifiUtils.normalizeBssMac(bssMac) ?: return
        bssLocalDao.deleteByBssMac(normalizedMac)
    }

    suspend fun exportBssLocalToString(): String {
        return bssLocalDao.getAll().map(BssLocalEntity::toBssLocalEntry)
            .joinToString("\n") { entry ->
                val parts = mutableListOf(entry.bssMac, entry.apName)
                if (entry.building.isNotEmpty()) parts.add(entry.building)
                parts.joinToString(" ")
            }
    }

    // ==================== BSS 信息查询 ====================

    suspend fun queryBssInfo(bssid: String): ApInfo = apiService.queryBssInfo(bssid)

    suspend fun queryNearbyApName(bssid: String): String? = apiService.queryNearbyApName(bssid)

    fun clearApInfoCache() = apiService.clearCache()

    suspend fun checkVersion(): BssInfoApiService.VersionInfo? = BssInfoApiService.checkVersion(prefs)

    private companion object {
        const val MAX_HISTORY_COUNT = 50
    }
}
