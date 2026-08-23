package com.example.data.db

import androidx.room.*
import com.example.data.model.ApiProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiProviderDao {
    @Query("SELECT * FROM api_providers ORDER BY isDefault DESC, createdAt ASC")
    fun getAllProviders(): Flow<List<ApiProviderEntity>>

    @Query("SELECT * FROM api_providers WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultProvider(): ApiProviderEntity?

    @Query("SELECT * FROM api_providers WHERE id = :id")
    suspend fun getProviderById(id: Long): ApiProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: ApiProviderEntity): Long

    @Update
    suspend fun updateProvider(provider: ApiProviderEntity)

    @Query("UPDATE api_providers SET isDefault = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE api_providers SET isDefault = 1 WHERE id = :id")
    suspend fun setDefaultProvider(id: Long)

    @Query("DELETE FROM api_providers WHERE id = :id")
    suspend fun deleteProviderById(id: Long)

    @Query("SELECT COUNT(*) FROM api_providers")
    suspend fun getProviderCount(): Int
}
