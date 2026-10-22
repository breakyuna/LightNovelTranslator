package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "native_model_settings")
internal data class NativeModelSettingsRow(@PrimaryKey val revision: Long, val endpoint: String, val model: String, val encryptedKey: String) {
    override fun toString() = "NativeModelSettingsRow(redacted)"
}
@Dao
internal interface NativeModelSettingsDao {
    @Query("SELECT * FROM native_model_settings ORDER BY revision DESC LIMIT 1") fun current(): NativeModelSettingsRow?
    @Query("SELECT * FROM native_model_settings WHERE revision=:revision") fun find(revision: Long): NativeModelSettingsRow?
    @Insert fun insert(row: NativeModelSettingsRow)
}
class NativeModelSettingsStore(context: Context, private val runtime: RuntimePort) : NativeModelSettingsPort {
    private val db = NativeAppDatabase.open(context)
    private val id = "native-remote-model"
    private val alias = "lnt_native_credentials_v1"
    private fun snapshot(row: NativeModelSettingsRow) = NativeModelSettings(ModelConfigurationRef(id, row.revision), row.endpoint, row.model)
    override suspend fun current(): NativeModelSettings? = withContext(Dispatchers.IO) { db.models().current()?.let(::snapshot) }
    override suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings = withContext(Dispatchers.IO) {
        require(reference.id == id)
        snapshot(requireNotNull(db.models().find(reference.revision)))
    }
    override suspend fun credential(reference: ModelConfigurationRef): String = withContext(Dispatchers.IO) {
        require(reference.id == id)
        val row = requireNotNull(db.models().find(reference.revision))
        val bytes = Base64.decode(row.encryptedKey, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("$id:${row.revision}".toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    override suspend fun save(endpoint: String, model: String, key: String): NativeModelSettings = withContext(Dispatchers.IO) {
        NativeModelSettingsValidation.validate(endpoint, model, key)
        val write = runtime.beginAuthenticationWrite()
        try {
            val previous = db.models().current()
            val revision = Math.addExact(previous?.revision ?: 0, 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret())
            cipher.updateAAD("$id:$revision".toByteArray(Charsets.UTF_8))
            val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            val row = NativeModelSettingsRow(revision, endpoint.trim().trimEnd('/'), model.trim(), encrypted)
            db.models().insert(row)
            snapshot(row)
        } finally { runtime.endAuthenticationWrite(write) }
    }
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
