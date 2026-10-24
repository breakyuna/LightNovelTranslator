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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

@Entity(tableName = "native_model_settings")
internal data class NativeModelSettingsRow(
    @PrimaryKey val revision: Long,
    val endpoint: String,
    val model: String,
    val encryptedKey: String,
    @ColumnInfo(defaultValue = "'OPENAI_CHAT_COMPLETIONS'") val protocol: String = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS.name,
    @ColumnInfo(defaultValue = "''") val encryptedKeys: String = "",
    @ColumnInfo(defaultValue = "0") val keyCount: Int = 0,
) {
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

    private fun snapshot(row: NativeModelSettingsRow) = NativeModelSettings(ModelConfigurationRef(id, row.revision),
        row.endpoint, row.model, row.keyCount > 0, protocolOf(row), row.keyCount)

    private fun protocolOf(row: NativeModelSettingsRow): NativeModelProtocol =
        NativeModelProtocol.entries.firstOrNull { it.name == row.protocol }
            ?: throw IllegalStateException("Unknown model protocol")

    override suspend fun current(): NativeModelSettings? = withContext(Dispatchers.IO) { db.models().current()?.let(::snapshot) }

    override suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings = withContext(Dispatchers.IO) {
        require(reference.id == id)
        snapshot(requireNotNull(db.models().find(reference.revision)))
    }

    override suspend fun credential(reference: ModelConfigurationRef): String = credentials(reference).firstOrNull().orEmpty()

    override suspend fun credentials(reference: ModelConfigurationRef): List<String> = withContext(Dispatchers.IO) {
        require(reference.id == id)
        val row = requireNotNull(db.models().find(reference.revision))
        credentials(row)
    }

    private fun credentials(row: NativeModelSettingsRow): List<String> {
        if (row.encryptedKeys.isNotEmpty()) {
            val encoded = try {
                Json.parseToJsonElement(row.encryptedKeys) as JsonArray
            } catch (_: Exception) { throw IllegalStateException("Damaged credential envelope") }
            require(encoded.size == row.keyCount && encoded.size <= NativeModelSettings.MAX_KEYS)
            return encoded.mapIndexed { index, element ->
                val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalStateException("Damaged credential envelope")
                decrypt(value, row.revision, index)
            }
        }
        if (row.keyCount == 0) return emptyList()
        require(row.keyCount == 1 && row.encryptedKey.isNotEmpty())
        return listOf(decrypt(row.encryptedKey, row.revision, null))
    }

    private fun decrypt(value: String, revision: Long, index: Int?): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad(revision, index))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun encrypt(value: String, revision: Long, index: Int?): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        cipher.updateAAD(aad(revision, index))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun aad(revision: Long, index: Int?): ByteArray =
        (if (index == null) "$id:$revision" else "$id:$revision:$index").toByteArray(Charsets.UTF_8)

    override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings =
        withContext(Dispatchers.IO) {
            NativeModelSettingsValidation.validate(endpoint, model, key ?: "")
            val base = NativeModelEndpoint.normalize(endpoint)
            val write = runtime.beginAuthenticationWrite()
            try {
                val previous = db.models().current()
                val protocol = previous?.let(::protocolOf) ?: NativeModelProtocol.OPENAI_CHAT_COMPLETIONS
                val keys = when {
                    key != null -> if (key.isEmpty()) emptyList() else listOf(key)
                    previous != null -> {
                        if (NativeModelEndpoint.base(previous.endpoint) != base) throw InvalidModelSettings()
                        credentials(previous)
                    }
                    else -> throw InvalidModelSettings()
                }
                persist(base, model.trim(), protocol, keys)
            } finally { runtime.endAuthenticationWrite(write) }
        }

    override suspend fun saveProfile(endpoint: String, model: String, protocol: NativeModelProtocol,
        keys: List<String>): NativeModelSettings = withContext(Dispatchers.IO) {
        NativeModelSettingsValidation.validateProfile(endpoint, model, keys)
        val base = NativeModelEndpoint.normalize(endpoint)
        val normalized = keys.filter { it.isNotEmpty() }.distinct()
        if (normalized.size != keys.size) throw InvalidModelSettings()
        val write = runtime.beginAuthenticationWrite()
        try {
            persist(base, model.trim(), protocol, normalized)
        } finally { runtime.endAuthenticationWrite(write) }
    }

    /** Must run under one authentication write; [previous] is read inside the same budget. */
    private fun persist(base: String, model: String, protocol: NativeModelProtocol, keys: List<String>): NativeModelSettings {
        require(keys.size <= NativeModelSettings.MAX_KEYS)
        keys.forEach(NativeModelSettingsValidation::validateKey)
        val previous = db.models().current()
        val revision = Math.addExact(previous?.revision ?: 0, 1)
        val encryptedKeys = if (keys.isEmpty()) "" else buildJsonArray {
            keys.forEachIndexed { index, value -> add(JsonPrimitive(encrypt(value, revision, index))) }
        }.toString()
        // Keep the v1..v4 single-key column readable with its original AAD shape.
        val legacy = if (keys.size == 1) encrypt(keys.single(), revision, null) else ""
        val row = NativeModelSettingsRow(revision, base, model, legacy, protocol.name, encryptedKeys, keys.size)
        db.models().insert(row)
        return snapshot(row)
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    suspend fun close(): Unit = withContext(Dispatchers.IO) { db.close() }
}
