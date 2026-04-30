package com.ustc.wifibss.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ustc.wifibss.model.QueryHistory

/**
 * 查询历史记录表
 * 迁移自 SharedPreferences JSON 数组
 */
@Entity(
    tableName = "query_history",
    indices = [Index(value = ["bssid"], name = "idx_history_bssid")]
)
data class QueryHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "bssid")
    val bssid: String,

    @ColumnInfo(name = "ap_name")
    val apName: String,

    @ColumnInfo(name = "building")
    val building: String
) {
    fun toQueryHistory() = QueryHistory(
        timestamp = timestamp,
        bssid = bssid,
        apName = apName,
        building = building
    )

    companion object {
        fun fromQueryHistory(qh: QueryHistory) = QueryHistoryEntity(
            timestamp = qh.timestamp,
            bssid = qh.bssid,
            apName = qh.apName,
            building = qh.building
        )
    }
}
