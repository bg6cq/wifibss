package com.ustc.wifibss.database

import androidx.room.*

@Dao
interface BssLocalDao {
    @Query("SELECT * FROM bss_local")
    suspend fun getAll(): List<BssLocalEntity>

    @Query("SELECT * FROM bss_local WHERE bssMac = :bssMac LIMIT 1")
    suspend fun getByBssMac(bssMac: String): BssLocalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BssLocalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<BssLocalEntity>)

    @Delete
    suspend fun delete(entity: BssLocalEntity)

    @Update
    suspend fun update(entity: BssLocalEntity)

    @Query("DELETE FROM bss_local WHERE bssMac = :bssMac")
    suspend fun deleteByBssMac(bssMac: String)

    @Query("DELETE FROM bss_local")
    suspend fun clearAll()
}
