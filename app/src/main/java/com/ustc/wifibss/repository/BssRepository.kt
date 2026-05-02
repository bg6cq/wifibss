package com.ustc.wifibss.repository

import com.ustc.wifibss.api.BssInfoApiService
import com.ustc.wifibss.api.BssQueryResult
import com.ustc.wifibss.data.AppPreferences
import com.ustc.wifibss.database.BssLocalEntity
import com.ustc.wifibss.database.QueryHistoryEntity
import com.ustc.wifibss.database.WifiBssDatabase
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
        val lastRecords = historyDao.getLatest(1)
        val lastRecord = lastRecords.firstOrNull()

        if (lastRecord != null && lastRecord.bssid == bssid) {
            // 与最后一条 BSSID 相同：根据是否有名称决定跳过或更新
            when {
                lastRecord.apName.isEmpty() && apName.isNotEmpty() -> {
                    // 最后一条刚记录的 BSSID 有 MAC 但无名称，现在有查询结果了 → 更新它
                    historyDao.update(lastRecord.copy(
                        apName = apName,
                        building = building
                    ))
                }
                lastRecord.apName.isNotEmpty() && apName.isNotEmpty() -> return  // 重复记录，跳过
                lastRecord.apName.isEmpty() && apName.isEmpty() -> return         // 重复记录，跳过
            }
            return
        }

        // BSSID 不同 → 插入新记录
        historyDao.insert(QueryHistoryEntity(
            timestamp = System.currentTimeMillis(),
            bssid = bssid,
            apName = apName,
            building = building
        ))
        // 删除超出上限的旧记录
        historyDao.trimExcess(MAX_HISTORY_COUNT)
    }

    suspend fun updateHistoryRecord(bssid: String, apName: String, building: String) {
        val pending = historyDao.getByBssidWithEmptyName(bssid)
        if (pending != null) {
            historyDao.update(pending.copy(apName = apName, building = building))
        } else {
            addHistoryRecord(bssid, apName, building)
        }
    }

    suspend fun clearHistory() {
        historyDao.clearAll()
    }

    // ==================== 本地 BSSMAC ====================

    suspend fun getBssLocalList(): List<BssLocalEntry> =
        bssLocalDao.getAll().map(BssLocalEntity::toBssLocalEntry)

    suspend fun getBssLocalByMac(bssMac: String): BssLocalEntry? =
        bssLocalDao.getByBssMac(bssMac)?.toBssLocalEntry()

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

    // ==================== BSS 信息查询（统一入口） ====================

    /**
     * 统一查询入口：本地数据库 → 远程 API
     * 不处理统计，由调用方负责
     */
    suspend fun queryBssInfo(bssid: String): BssQueryResult {
        // 1. 查本地数据库
        val local = bssLocalDao.getByBssMac(bssid)
        if (local != null && local.apName.isNotEmpty()) {
            return BssQueryResult(
                rawJson = "来自本地数据库",
                apInfo = com.ustc.wifibss.model.ApInfo(
                    bssMac = local.bssMac,
                    apName = local.apName,
                    apSn = "-",
                    acIp = "-",
                    apIp = "-",
                    building = local.building
                ),
                fromLocal = true
            )
        }

        // 2. 调用远程 API
        return apiService.queryBssInfo(bssid)
    }

    fun isInApiCache(bssid: String): Boolean = apiService.isInCache(bssid)

    fun clearApInfoCache() = apiService.clearCache()

    suspend fun checkVersion(): BssInfoApiService.VersionInfo? = BssInfoApiService.checkVersion(prefs)

    private companion object {
        const val MAX_HISTORY_COUNT = 50
    }
}
