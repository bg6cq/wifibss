package com.ustc.wifibss.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ustc.wifibss.model.BssLocalEntry

/**
 * 本地 BSSMAC 表
 * 迁移自 SharedPreferences JSON 数组
 */
@Entity(
    tableName = "bss_local",
    indices = [Index(value = ["bssMac"], unique = true)]
)
data class BssLocalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "bssMac")
    val bssMac: String,

    @ColumnInfo(name = "apName")
    val apName: String,

    @ColumnInfo(name = "building")
    val building: String
) {
    fun toBssLocalEntry() = BssLocalEntry(
        bssMac = bssMac,
        apName = apName,
        building = building
    )

    companion object {
        fun fromBssLocalEntry(entry: BssLocalEntry) = BssLocalEntity(
            bssMac = entry.bssMac,
            apName = entry.apName,
            building = entry.building
        )
    }
}
