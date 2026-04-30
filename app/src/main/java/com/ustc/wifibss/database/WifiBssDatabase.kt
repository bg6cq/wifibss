package com.ustc.wifibss.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [QueryHistoryEntity::class, BssLocalEntity::class], version = 1, exportSchema = false)
abstract class WifiBssDatabase : RoomDatabase() {
    abstract fun queryHistoryDao(): QueryHistoryDao
    abstract fun bssLocalDao(): BssLocalDao

    companion object {
        @Volatile private var INSTANCE: WifiBssDatabase? = null

        fun getInstance(context: Context): WifiBssDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WifiBssDatabase::class.java,
                    "wifi_bss_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
