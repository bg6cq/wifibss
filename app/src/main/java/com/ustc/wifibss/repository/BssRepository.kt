package com.ustc.wifibss.repository

import com.ustc.wifibss.api.BssInfoApiService
import com.ustc.wifibss.data.AppPreferences
import com.ustc.wifibss.model.ApInfo
import com.ustc.wifibss.model.BssLocalEntry
import com.ustc.wifibss.model.QueryHistory
import com.ustc.wifibss.util.WifiUtils
import org.json.JSONArray
import org.json.JSONObject

class BssRepository(
    private val prefs: AppPreferences
) {
    private val apiService = BssInfoApiService(prefs)

    // ==================== 历史记录 ====================

    fun getHistoryList(): List<QueryHistory> = prefs.parseHistoryList(prefs.getHistoryList())

    fun addHistoryRecord(bssid: String, apName: String, building: String) {
        val list = getHistoryList().toMutableList()

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

    fun updateHistoryRecord(bssid: String, apName: String, building: String) {
        val list = getHistoryList().toMutableList()
        for (i in list.size - 1 downTo 0) {
            if (list[i].bssid == bssid && list[i].apName.isEmpty()) {
                list[i] = QueryHistory(list[i].timestamp, bssid, apName, building)
                saveHistoryList(list)
                return
            }
        }
        addHistoryRecord(bssid, apName, building)
    }

    fun clearHistory() = prefs.clearHistory()

    private fun saveHistoryList(list: List<QueryHistory>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("timestamp", item.timestamp)
            obj.put("bssid", item.bssid)
            obj.put("apName", item.apName)
            obj.put("building", item.building)
            array.put(obj)
        }
        prefs.saveHistoryList(array.toString())
    }

    // ==================== 本地 BSSMAC ====================

    fun getBssLocalList(): List<BssLocalEntry> = prefs.parseBssLocalList(prefs.getBssLocalList())

    fun addBssLocal(bssMac: String, apName: String, building: String): Boolean {
        val normalizedMac = WifiUtils.normalizeBssMac(bssMac) ?: return false
        val list = getBssLocalList().toMutableList()

        val existingIndex = list.indexOfFirst { it.bssMac == normalizedMac }
        if (existingIndex >= 0) {
            list[existingIndex] = BssLocalEntry(normalizedMac, apName, building)
        } else {
            list.add(BssLocalEntry(normalizedMac, apName, building))
        }
        saveBssLocalList(list)
        return true
    }

    fun deleteBssLocal(bssMac: String) {
        val normalizedMac = WifiUtils.normalizeBssMac(bssMac) ?: return
        val list = getBssLocalList().toMutableList()
        list.removeAll { it.bssMac == normalizedMac }
        saveBssLocalList(list)
    }

    private fun saveBssLocalList(list: List<BssLocalEntry>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("bssMac", item.bssMac)
            obj.put("apName", item.apName)
            obj.put("building", item.building)
            array.put(obj)
        }
        prefs.saveBssLocalList(array.toString())
    }

    fun exportBssLocalToString(): String {
        return getBssLocalList().joinToString("\n") { entry ->
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
