package com.ustc.wifibss.database

import androidx.room.*

@Dao
interface QueryHistoryDao {
    @Query("SELECT * FROM query_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<QueryHistoryEntity>

    @Query("SELECT * FROM query_history WHERE bssid = :bssid AND ap_name = '' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getByBssidWithEmptyName(bssid: String): QueryHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: QueryHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<QueryHistoryEntity>)

    @Update
    suspend fun update(entity: QueryHistoryEntity)

    @Query("DELETE FROM query_history WHERE id NOT IN (SELECT id FROM query_history ORDER BY timestamp DESC LIMIT :limit)")
    suspend fun trimExcess(limit: Int)

    @Query("DELETE FROM query_history")
    suspend fun clearAll()
}
