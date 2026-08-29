package com.breakyuna.noveltranslator.data.db

import androidx.room.*
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiProviderDao {
    @Query("SELECT * FROM api_providers ORDER BY isDefault DESC, createdAt ASC")
    fun getAllProviders(): Flow<List<ApiProviderEntity>>

    @Query("SELECT * FROM api_providers WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultProvider(): ApiProviderEntity?

    @Query("SELECT * FROM api_providers ORDER BY isDefault DESC, createdAt ASC")
    suspend fun getAllProvidersOnce(): List<ApiProviderEntity>

    @Query("SELECT * FROM api_providers WHERE id = :id")
    suspend fun getProviderById(id: Long): ApiProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: ApiProviderEntity): Long

    /** Inserts a provider while keeping exactly one default whenever the table is non-empty. */
    @Transaction
    suspend fun insertProviderAndRepairDefault(provider: ApiProviderEntity): Long {
        val id = insertProvider(provider.copy(isDefault = false))
        val providers = getAllProvidersOnce()
        if (provider.isDefault || providers.count { it.isDefault } != 1) {
            clearDefaultFlags()
            setDefaultProviderFlag(id)
        }
        return id
    }

    @Update
    suspend fun updateProvider(provider: ApiProviderEntity)

    /** Updates a provider without allowing an unchecked box to remove the only default. */
    @Transaction
    suspend fun updateProviderAndRepairDefault(provider: ApiProviderEntity): Boolean {
        if (getProviderById(provider.id) == null) return false
        updateProvider(provider.copy(isDefault = false))
        val providers = getAllProvidersOnce()
        if (providers.isNotEmpty() && (provider.isDefault || providers.count { it.isDefault } != 1)) {
            clearDefaultFlags()
            setDefaultProviderFlag(if (provider.isDefault) provider.id else providers.first().id)
        }
        return true
    }

    @Query("UPDATE api_providers SET isDefault = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE api_providers SET isDefault = 1 WHERE id = :id")
    suspend fun setDefaultProviderFlag(id: Long)

    /** Changes the default only after confirming that the requested provider exists. */
    @Transaction
    suspend fun replaceDefaultProvider(id: Long): Boolean {
        if (getProviderById(id) == null) return false
        clearDefaultFlags()
        setDefaultProviderFlag(id)
        return true
    }

    /** Repairs databases written by an older screen that could leave zero or several defaults. */
    @Transaction
    suspend fun repairDefaultProvider(): Long? {
        val providers = getAllProvidersOnce()
        if (providers.isEmpty()) return null
        val defaults = providers.filter { it.isDefault }
        val selected = defaults.firstOrNull() ?: providers.first()
        if (defaults.size != 1 || defaults.firstOrNull()?.id != selected.id) {
            clearDefaultFlags()
            setDefaultProviderFlag(selected.id)
        }
        return selected.id
    }

    @Query("DELETE FROM api_providers WHERE id = :id")
    suspend fun deleteProviderById(id: Long)

    /** Deletes a provider and repairs the default-provider invariant in one transaction. */
    @Transaction
    suspend fun deleteProviderAndRepairDefault(id: Long): Boolean {
        val deleted = getProviderById(id) ?: return false
        deleteProviderById(id)
        val remaining = getAllProvidersOnce()
        if (remaining.isNotEmpty() && (deleted.isDefault || remaining.count { it.isDefault } != 1)) {
            clearDefaultFlags()
            setDefaultProviderFlag(remaining.first().id)
        }
        return true
    }

    @Query("SELECT COUNT(*) FROM api_providers")
    suspend fun getProviderCount(): Int
}
