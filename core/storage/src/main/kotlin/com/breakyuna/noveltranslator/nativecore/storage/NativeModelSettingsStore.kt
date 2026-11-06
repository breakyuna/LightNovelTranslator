package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.security.KeyStore
import java.util.concurrent.Callable
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

@Entity(tableName = "native_model_settings")
internal data class NativeModelSettingsRow(
    @PrimaryKey val revision: Long,
    @ColumnInfo(defaultValue = "'native-remote-model'") val profileId: String = NativeModelSettingsStore.DEFAULT_PROFILE_ID,
    val endpoint: String,
    val model: String,
    val encryptedKey: String,
    @ColumnInfo(defaultValue = "'OPENAI_CHAT_COMPLETIONS'") val protocol: String = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS.name,
    @ColumnInfo(defaultValue = "''") val encryptedKeys: String = "",
    @ColumnInfo(defaultValue = "0") val keyCount: Int = 0,
) {
    override fun toString() = "NativeModelSettingsRow(redacted)"
}

@Entity(tableName = "native_model_profiles")
internal data class NativeModelProfileRow(
    @PrimaryKey val profileId: String,
    val name: String,
    val kind: String,
    val purpose: String,
    val groupName: String,
    val orderIndex: Int,
    val enabled: Boolean,
    val concurrency: Int?,
    val requestsPerMinute: Int?,
    val tools: Boolean?,
    val vision: Boolean?,
    val reasoning: Boolean?,
    val contextWindowTokens: Int?,
    val maxOutputTokens: Int?,
    val temperature: Double?,
    val topP: Double?,
    val generationMaxOutputTokens: Int?,
    val reasoningEffort: String?,
    val serviceTier: String?,
    @ColumnInfo(defaultValue = "'{}'") val extraHeaders: String = "{}",
    val extraBody: String?,
    @ColumnInfo(defaultValue = "0") val selectedTranslation: Boolean = false,
    @ColumnInfo(defaultValue = "0") val selectedAgent: Boolean = false,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
)

@Dao
internal interface NativeModelSettingsDao {
    @Query("SELECT * FROM native_model_settings WHERE profileId=:profileId ORDER BY revision DESC LIMIT 1")
    fun current(profileId: String): NativeModelSettingsRow?

    @Query("SELECT s.* FROM native_model_settings s JOIN (SELECT profileId AS pid, MAX(revision) AS maxRevision FROM native_model_settings GROUP BY profileId) latest ON s.profileId=latest.pid AND s.revision=latest.maxRevision")
    fun latestPerProfile(): List<NativeModelSettingsRow>

    @Query("SELECT * FROM native_model_settings WHERE revision=:revision") fun find(revision: Long): NativeModelSettingsRow?
    @Query("SELECT COALESCE(MAX(revision), 0) FROM native_model_settings") fun maxRevision(): Long
    @Query("DELETE FROM native_model_settings WHERE profileId=:profileId")
    fun deleteForProfile(profileId: String)
    @Insert fun insert(row: NativeModelSettingsRow)
}

@Dao
internal interface NativeModelProfileDao {
    @Query("SELECT * FROM native_model_profiles ORDER BY orderIndex, profileId") fun list(): List<NativeModelProfileRow>
    @Query("SELECT * FROM native_model_profiles WHERE profileId=:profileId") fun find(profileId: String): NativeModelProfileRow?
    @Insert fun insert(row: NativeModelProfileRow)
    @Update fun update(row: NativeModelProfileRow)
    @Query("DELETE FROM native_model_profiles WHERE profileId=:profileId") fun delete(profileId: String)
}

class NativeModelSettingsStore(context: Context, private val runtime: RuntimePort) :
    NativeModelSettingsPort, NativeModelProfilePort {
    private val db = NativeAppDatabase.open(context)
    private val alias = "lnt_native_credentials_v1"
    private val profileMutex = Mutex()

    private fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })

    private fun snapshot(row: NativeModelSettingsRow) = NativeModelSettings(
        ModelConfigurationRef(row.profileId, row.revision),
        row.endpoint, row.model, row.keyCount > 0, protocolOf(row), row.keyCount)

    private fun protocolOf(row: NativeModelSettingsRow): NativeModelProtocol =
        NativeModelProtocol.entries.firstOrNull { it.name == row.protocol }
            ?: throw IllegalStateException("Unknown model protocol")

    override suspend fun current(): NativeModelSettings? = withContext(Dispatchers.IO) {
        profileMutex.withLock {
            ensureProfilesLocked()
            selectedLocked(ModelProfilePurpose.AGENT)?.settings
        }
    }

    override suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings = withContext(Dispatchers.IO) {
        val row = requireNotNull(db.models().find(reference.revision)) { "Unknown model revision" }
        require(row.profileId == reference.id) { "Model revision belongs to another profile" }
        snapshot(row)
    }

    override suspend fun credential(reference: ModelConfigurationRef): String =
        credentials(reference).firstOrNull().orEmpty()

    override suspend fun credentials(reference: ModelConfigurationRef): List<String> = withContext(Dispatchers.IO) {
        val row = requireNotNull(db.models().find(reference.revision)) { "Unknown model revision" }
        require(row.profileId == reference.id) { "Model revision belongs to another profile" }
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
                decrypt(value, row.profileId, row.revision, index)
            }
        }
        if (row.keyCount == 0) return emptyList()
        require(row.keyCount == 1 && row.encryptedKey.isNotEmpty())
        return listOf(decrypt(row.encryptedKey, row.profileId, row.revision, null))
    }

    private fun decrypt(value: String, profileId: String, revision: Long, index: Int?): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad(profileId, revision, index))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun encrypt(value: String, profileId: String, revision: Long, index: Int?): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        cipher.updateAAD(aad(profileId, revision, index))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun aad(profileId: String, revision: Long, index: Int?): ByteArray =
        (if (index == null) "$profileId:$revision" else "$profileId:$revision:$index").toByteArray(Charsets.UTF_8)

    override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings =
        withContext(Dispatchers.IO) {
            NativeModelSettingsValidation.validate(endpoint, model, key ?: "")
            val base = NativeModelEndpoint.normalize(endpoint)
            val write = runtime.beginAuthenticationWrite()
            try {
                val previous = db.models().current(DEFAULT_PROFILE_ID)
                val protocol = previous?.let(::protocolOf) ?: NativeModelProtocol.OPENAI_CHAT_COMPLETIONS
                val keys = when {
                    key != null -> if (key.isEmpty()) emptyList() else listOf(key)
                    previous != null -> {
                        if (NativeModelEndpoint.base(previous.endpoint) != base) throw InvalidModelSettings()
                        credentials(previous)
                    }
                    else -> throw InvalidModelSettings()
                }
                persist(DEFAULT_PROFILE_ID, base, model.trim(), protocol, keys)
            } finally { runtime.endAuthenticationWrite(write) }
        }

    override suspend fun saveProfile(endpoint: String, model: String, protocol: NativeModelProtocol,
        keys: List<String>): NativeModelSettings = saveProfile(DEFAULT_PROFILE_ID, endpoint, model, protocol, keys)

    /** Saves one explicit profile revision; the profile row is created lazily for migrated data. */
    suspend fun saveProfile(profileId: String, endpoint: String, model: String, protocol: NativeModelProtocol,
        keys: List<String>): NativeModelSettings = withContext(Dispatchers.IO) {
        NativeModelSettingsValidation.validateProfile(endpoint, model, keys)
        val base = NativeModelEndpoint.normalize(endpoint)
        val normalized = keys.filter { it.isNotEmpty() }.distinct()
        if (normalized.size != keys.size) throw InvalidModelSettings()
        val write = runtime.beginAuthenticationWrite()
        try {
            profileMutex.withLock { ensureProfileLocked(profileId) }
            persist(profileId, base, model.trim(), protocol, normalized)
        } finally { runtime.endAuthenticationWrite(write) }
    }

    /** Must run under one authentication write; [previous] is read inside the same budget. */
    private fun persist(profileId: String, base: String, model: String, protocol: NativeModelProtocol,
        keys: List<String>): NativeModelSettings {
        require(keys.size <= NativeModelSettings.MAX_KEYS)
        keys.forEach(NativeModelSettingsValidation::validateKey)
        val revision = Math.addExact(db.models().maxRevision(), 1)
        val encryptedKeys = if (keys.isEmpty()) "" else buildJsonArray {
            keys.forEachIndexed { index, value -> add(JsonPrimitive(encrypt(value, profileId, revision, index))) }
        }.toString()
        // Keep the v1..v4 single-key column readable with its original AAD shape.
        val legacy = if (keys.size == 1) encrypt(keys.single(), profileId, revision, null) else ""
        val row = NativeModelSettingsRow(revision, profileId, base, model, legacy, protocol.name, encryptedKeys, keys.size)
        db.models().insert(row)
        return snapshot(row)
    }

    override suspend fun list(): List<NativeModelProfile> = withContext(Dispatchers.IO) {
        profileMutex.withLock {
            ensureProfilesLocked()
            profileRowsLocked()
        }
    }

    override suspend fun selected(purpose: ModelProfilePurpose): NativeModelProfile? = withContext(Dispatchers.IO) {
        profileMutex.withLock {
            ensureProfilesLocked()
            selectedLocked(purpose)
        }
    }

    override suspend fun find(id: String): NativeModelProfile? = withContext(Dispatchers.IO) {
        profileMutex.withLock {
            ensureProfilesLocked()
            profileRowsLocked().firstOrNull { it.id == id }
        }
    }

    override suspend fun resolveProfile(reference: ModelConfigurationRef): NativeModelProfile? = withContext(Dispatchers.IO) {
        profileMutex.withLock {
            ensureProfilesLocked()
            val settings = db.models().find(reference.revision) ?: return@withLock null
            if (settings.profileId != reference.id) return@withLock null
            val row = db.profiles().find(reference.id) ?: return@withLock null
            profile(row, settings)
        }
    }

    private fun selectedLocked(purpose: ModelProfilePurpose): NativeModelProfile? =
        profileRowsLocked().firstOrNull { purpose in it.selectedFor && it.enabled }

    override suspend fun create(profile: NewModelProfile): NativeModelProfile = withContext(Dispatchers.IO) {
        NativeModelProfileValidation.validate(profile)
        val base = NativeModelEndpoint.normalize(profile.endpoint)
        val normalized = profile.keys.filter { it.isNotEmpty() }.distinct()
        if (normalized.size != profile.keys.size) throw InvalidModelProfile()
        val write = runtime.beginAuthenticationWrite()
        try {
            profileMutex.withLock {
                ensureProfilesLocked()
                val rows = db.profiles().list()
                require(rows.size < MAX_PROFILES)
                val id = UUID.randomUUID().toString()
                val order = (rows.maxOfOrNull { it.orderIndex } ?: -1) + 1
                val purpose = profile.purpose
                val selected = NativeModelProfile.PURPOSES_FOR_SELECTION.filter { candidate ->
                    purpose.serves(candidate) && rows.none { candidate in selectedPurposes(it) }
                }.toSet()
                transaction {
                    persist(id, base, profile.model.trim(), profile.protocol, normalized)
                    db.profiles().insert(profileRow(id, profile, order, selected))
                }
                requireNotNull(profileRowsLocked().firstOrNull { it.id == id })
            }
        } finally { runtime.endAuthenticationWrite(write) }
    }

    override suspend fun edit(id: String, edit: ModelProfileEdit): NativeModelProfile = withContext(Dispatchers.IO) {
        NativeModelProfileValidation.validate(edit)
        profileMutex.withLock {
            ensureProfilesLocked()
            val row = requireNotNull(db.profiles().find(id)) { "Unknown model profile" }
            val selected = if (edit.enabled) selectedPurposes(row).filter { edit.purpose.serves(it) }.toSet()
            else emptySet()
            val updated = row.copy(
                name = edit.name,
                kind = edit.kind.name,
                purpose = edit.purpose.name,
                groupName = edit.group,
                enabled = edit.enabled,
                concurrency = edit.limits?.concurrency,
                requestsPerMinute = edit.limits?.requestsPerMinute,
                tools = edit.capabilities.tools,
                vision = edit.capabilities.vision,
                reasoning = edit.capabilities.reasoning,
                contextWindowTokens = edit.capabilities.contextWindowTokens,
                maxOutputTokens = edit.capabilities.maxOutputTokens,
                temperature = edit.generation.temperature,
                topP = edit.generation.topP,
                generationMaxOutputTokens = edit.generation.maxOutputTokens,
                reasoningEffort = edit.generation.reasoningEffort,
                serviceTier = edit.generation.serviceTier,
                extraHeaders = encodeHeaders(edit.extraHeaders),
                extraBody = edit.extraBody,
                selectedTranslation = ModelProfilePurpose.TRANSLATION in selected,
                selectedAgent = ModelProfilePurpose.AGENT in selected,
                revision = row.revision + 1,
            )
            db.profiles().update(updated)
            requireNotNull(profileRowsLocked().firstOrNull { it.id == id })
        }
    }

    override suspend fun saveConfiguration(id: String, endpoint: String, model: String,
        protocol: NativeModelProtocol, keys: List<String>?): NativeModelProfile = withContext(Dispatchers.IO) {
        NativeModelSettingsValidation.validateProfile(endpoint, model, keys ?: emptyList())
        val base = NativeModelEndpoint.normalize(endpoint)
        val write = runtime.beginAuthenticationWrite()
        try {
            profileMutex.withLock {
                ensureProfilesLocked()
                requireNotNull(db.profiles().find(id)) { "Unknown model profile" }
                val currentSettings = db.models().current(id) ?: throw InvalidModelSettings()
                val resolved = keys?.filter { it.isNotEmpty() }?.distinct() ?: run {
                    if (NativeModelEndpoint.base(currentSettings.endpoint) != base) throw InvalidModelSettings()
                    credentials(currentSettings)
                }
                if (keys != null && resolved.size != keys.size) throw InvalidModelSettings()
                persist(id, base, model.trim(), protocol, resolved)
                requireNotNull(profileRowsLocked().firstOrNull { it.id == id })
            }
        } finally { runtime.endAuthenticationWrite(write) }
    }

    override suspend fun copy(id: String, name: String): NativeModelProfile = withContext(Dispatchers.IO) {
        NativeModelProfileValidation.validateName(name)
        val write = runtime.beginAuthenticationWrite()
        try {
            profileMutex.withLock {
                ensureProfilesLocked()
                val source = requireNotNull(db.profiles().find(id)) { "Unknown model profile" }
                val sourceSettings = db.models().current(id) ?: throw InvalidModelSettings()
                val keys = credentials(sourceSettings)
                val rows = db.profiles().list()
                require(rows.size < MAX_PROFILES)
                val copyId = UUID.randomUUID().toString()
                val order = (rows.maxOfOrNull { it.orderIndex } ?: -1) + 1
                transaction {
                    persist(copyId, sourceSettings.endpoint, sourceSettings.model, protocolOf(sourceSettings), keys)
                    db.profiles().insert(source.copy(
                        profileId = copyId,
                        name = name,
                        kind = ModelProfileKind.CUSTOM.name,
                        orderIndex = order,
                        selectedTranslation = false,
                        selectedAgent = false,
                        revision = 1,
                    ))
                }
                requireNotNull(profileRowsLocked().firstOrNull { it.id == copyId })
            }
        } finally { runtime.endAuthenticationWrite(write) }
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        profileMutex.withLock {
            ensureProfilesLocked()
            val row = requireNotNull(db.profiles().find(id)) { "Unknown model profile" }
            transaction {
                db.profiles().delete(id)
                db.models().deleteForProfile(id)
            }
            val remaining = db.profiles().list()
            if (row.selectedTranslation && remaining.none { it.selectedTranslation && it.enabled }) {
                remaining.firstOrNull { it.enabled && selectedPurposes(it).contains(ModelProfilePurpose.TRANSLATION) }
                    ?.let { db.profiles().update(it.copy(selectedTranslation = true)) }
            }
            if (row.selectedAgent && remaining.none { it.selectedAgent && it.enabled }) {
                remaining.firstOrNull { it.enabled && selectedPurposes(it).contains(ModelProfilePurpose.AGENT) }
                    ?.let { db.profiles().update(it.copy(selectedAgent = true)) }
            }
        }
    }

    override suspend fun move(id: String, offset: Int): List<NativeModelProfile> = withContext(Dispatchers.IO) {
        require(offset in setOf(-1, 1))
        profileMutex.withLock {
            ensureProfilesLocked()
            val rows = db.profiles().list().toMutableList()
            val index = rows.indexOfFirst { it.profileId == id }
            require(index >= 0) { "Unknown model profile" }
            val target = index + offset
            if (target in rows.indices) {
                val moved = rows.removeAt(index)
                rows.add(target, moved)
                transaction {
                    rows.forEachIndexed { order, row -> db.profiles().update(row.copy(orderIndex = order)) }
                }
            }
            profileRowsLocked()
        }
    }

    override suspend fun select(id: String, purpose: ModelProfilePurpose): NativeModelProfile = withContext(Dispatchers.IO) {
        require(purpose != ModelProfilePurpose.BOTH)
        profileMutex.withLock {
            ensureProfilesLocked()
            val target = requireNotNull(db.profiles().find(id)) { "Unknown model profile" }
            if (!target.enabled || !ModelProfilePurpose.valueOf(target.purpose).serves(purpose))
                throw InvalidModelProfile()
            val rows = db.profiles().list()
            transaction {
                rows.forEach { row ->
                    val next = when (purpose) {
                        ModelProfilePurpose.TRANSLATION -> row.copy(selectedTranslation = row.profileId == id)
                        ModelProfilePurpose.AGENT -> row.copy(selectedAgent = row.profileId == id)
                        ModelProfilePurpose.BOTH -> row
                    }
                    if (next != row) db.profiles().update(next)
                }
            }
            requireNotNull(profileRowsLocked().firstOrNull { it.id == id })
        }
    }

    /** Opaque CAS over profile metadata and immutable configuration revisions; no secret leaves storage. */
    private fun reviewedCatalogFingerprintLocked(): String = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(
        buildJsonArray { db.profiles().list().forEach { row -> add(buildJsonObject {
            put("profileId", JsonPrimitive(row.profileId))
            put("name", JsonPrimitive(row.name))
            put("kind", JsonPrimitive(row.kind))
            put("purpose", JsonPrimitive(row.purpose))
            put("groupName", JsonPrimitive(row.groupName))
            put("orderIndex", JsonPrimitive(row.orderIndex))
            put("enabled", JsonPrimitive(row.enabled))
            put("concurrency", JsonPrimitive(row.concurrency))
            put("requestsPerMinute", JsonPrimitive(row.requestsPerMinute))
            put("tools", JsonPrimitive(row.tools))
            put("vision", JsonPrimitive(row.vision))
            put("reasoning", JsonPrimitive(row.reasoning))
            put("contextWindowTokens", JsonPrimitive(row.contextWindowTokens))
            put("maxOutputTokens", JsonPrimitive(row.maxOutputTokens))
            put("temperature", JsonPrimitive(row.temperature))
            put("topP", JsonPrimitive(row.topP))
            put("generationMaxOutputTokens", JsonPrimitive(row.generationMaxOutputTokens))
            put("reasoningEffort", JsonPrimitive(row.reasoningEffort))
            put("serviceTier", JsonPrimitive(row.serviceTier))
            put("extraHeaders", JsonPrimitive(row.extraHeaders))
            put("extraBody", JsonPrimitive(row.extraBody))
            put("selectedTranslation", JsonPrimitive(row.selectedTranslation))
            put("selectedAgent", JsonPrimitive(row.selectedAgent))
            put("revision", JsonPrimitive(row.revision))
            put("configurationRevision", JsonPrimitive(db.models().current(row.profileId)?.revision))
        }) } }.toString()))

    suspend fun reviewedProfileSnapshot(): JsonObject = withContext(Dispatchers.IO) {
        profileMutex.withLock {
            ensureProfilesLocked()
            buildJsonObject {
                put("fingerprint", JsonPrimitive(reviewedCatalogFingerprintLocked()))
                put("profiles", buildJsonArray { db.profiles().list().forEach { row -> add(buildJsonObject {
                    put("id", JsonPrimitive(row.profileId)); put("name", JsonPrimitive(row.name))
                    put("kind", JsonPrimitive(row.kind)); put("purpose", JsonPrimitive(row.purpose))
                    put("group", JsonPrimitive(row.groupName)); put("order", JsonPrimitive(row.orderIndex))
                    put("enabled", JsonPrimitive(row.enabled))
                    put("selected_translation", JsonPrimitive(row.selectedTranslation)); put("selected_agent", JsonPrimitive(row.selectedAgent))
                    put("configuration_revision", JsonPrimitive(requireNotNull(db.models().current(row.profileId)).revision))
                }) } })
            }
        }
    }

    suspend fun saveReviewedProfile(expected: String, id: String?, draft: NewModelProfile,
        retainCredentials: Boolean): String = withContext(Dispatchers.IO) {
        NativeModelProfileValidation.validate(draft)
        require(draft.keys.isEmpty()) { "Reviewed profile data cannot carry credentials" }
        val base = NativeModelEndpoint.normalize(draft.endpoint)
        val write = runtime.beginAuthenticationWrite()
        try {
            profileMutex.withLock {
                ensureProfilesLocked()
                require(reviewedCatalogFingerprintLocked() == expected) { "Model catalog changed" }
                val rows = db.profiles().list()
                val old = id?.let { requireNotNull(db.profiles().find(it)) }
                require(old != null || rows.size < MAX_PROFILES)
                val target = id ?: UUID.randomUUID().toString()
                val keys = if (retainCredentials) {
                    val settings = requireNotNull(old?.let { db.models().current(it.profileId) })
                    require(NativeModelEndpoint.base(settings.endpoint) == base) { "Cannot move credentials to another endpoint" }
                    credentials(settings)
                } else emptyList()
                val selected = if (old == null) NativeModelProfile.PURPOSES_FOR_SELECTION.filter { purpose ->
                    draft.enabled && draft.purpose.serves(purpose) && rows.none { purpose in selectedPurposes(it) }
                }.toSet() else selectedPurposes(old).filter { draft.enabled && draft.purpose.serves(it) }.toSet()
                val order = old?.orderIndex ?: ((rows.maxOfOrNull { it.orderIndex } ?: -1) + 1)
                // Opaque extras may contain user-supplied authentication material. Never
                // carry them to another endpoint through an Agent standard-field edit.
                val retainExtras = old?.let { db.models().current(it.profileId) }
                    ?.let { NativeModelEndpoint.base(it.endpoint) == base } == true
                val record = profileRow(target, draft, order, selected).copy(revision = Math.addExact(old?.revision ?: 0L, 1L),
                    extraHeaders = if (retainExtras) old?.extraHeaders ?: "{}" else "{}",
                    extraBody = if (retainExtras) old?.extraBody else null)
                transaction {
                    persist(target, base, draft.model.trim(), draft.protocol, keys)
                    if (old == null) db.profiles().insert(record) else db.profiles().update(record)
                }
                target
            }
        } finally { runtime.endAuthenticationWrite(write) }
    }

    /** Atomic approval CAS; existing manual mutations share profileMutex. Credentials stay inside this store. */
    suspend fun applyReviewedProfileMutation(expected: String, id: String, operation: String, name: String?): String = withContext(Dispatchers.IO) {
        val write = runtime.beginAuthenticationWrite()
        try {
            profileMutex.withLock {
                ensureProfilesLocked()
                require(reviewedCatalogFingerprintLocked() == expected) { "Model catalog changed" }
                val rows = db.profiles().list()
                val row = requireNotNull(rows.singleOrNull { it.profileId == id })
                when (operation) {
                    "rename" -> {
                        val label = requireNotNull(name)
                        NativeModelProfileValidation.validateName(label)
                        db.profiles().update(row.copy(name = label, revision = Math.addExact(row.revision, 1L)))
                        id
                    }
                    "enable", "disable" -> {
                        val enabled = operation == "enable"
                        db.profiles().update(row.copy(enabled = enabled, revision = Math.addExact(row.revision, 1L),
                            selectedTranslation = enabled && row.selectedTranslation, selectedAgent = enabled && row.selectedAgent))
                        id
                    }
                    "move_up", "move_down" -> {
                        val ordered = rows.toMutableList()
                        val index = ordered.indexOfFirst { it.profileId == id }
                        val to = index + if (operation == "move_up") -1 else 1
                        require(to in ordered.indices)
                        java.util.Collections.swap(ordered, index, to)
                        transaction { ordered.forEachIndexed { order, next -> db.profiles().update(next.copy(orderIndex = order)) } }
                        id
                    }
                    "select_translation", "select_agent" -> {
                        val purpose = if (operation == "select_translation") ModelProfilePurpose.TRANSLATION else ModelProfilePurpose.AGENT
                        require(row.enabled && ModelProfilePurpose.valueOf(row.purpose).serves(purpose))
                        transaction { rows.forEach { next -> db.profiles().update(
                            if (purpose == ModelProfilePurpose.TRANSLATION) next.copy(selectedTranslation = next.profileId == id)
                            else next.copy(selectedAgent = next.profileId == id)) } }
                        id
                    }
                    "delete" -> {
                        transaction { db.profiles().delete(id); db.models().deleteForProfile(id) }
                        id
                    }
                    "copy" -> {
                        val label = requireNotNull(name)
                        NativeModelProfileValidation.validateName(label)
                        require(rows.size < MAX_PROFILES)
                        val settings = requireNotNull(db.models().current(id))
                        val keys = credentials(settings)
                        val created = UUID.randomUUID().toString()
                        transaction {
                            persist(created, settings.endpoint, settings.model, protocolOf(settings), keys)
                            db.profiles().insert(row.copy(profileId = created, name = label, kind = ModelProfileKind.CUSTOM.name,
                                orderIndex = (rows.maxOfOrNull { it.orderIndex } ?: -1) + 1,
                                selectedTranslation = false, selectedAgent = false, revision = 1))
                        }
                        created
                    }
                    else -> error("Unknown reviewed model action")
                }
            }
        } finally { runtime.endAuthenticationWrite(write) }
    }

    /** Creates a legacy row when a pre-profile database only has settings revisions. */
    private fun ensureProfilesLocked() {
        val existing = db.profiles().list()
        if (existing.isNotEmpty()) {
            // Repair a migrated database that lost its profile row but kept settings revisions.
            val profiles = existing.mapTo(mutableSetOf()) { it.profileId }
            db.models().latestPerProfile().filter { it.profileId !in profiles }.forEach { settings ->
                db.profiles().insert(legacyRow(settings.profileId, settings))
            }
            return
        }
        val latest = db.models().latestPerProfile()
        if (latest.isEmpty()) return
        transaction {
            latest.forEachIndexed { index, settings ->
                db.profiles().insert(legacyRow(settings.profileId, settings).copy(
                    selectedTranslation = index == 0,
                    selectedAgent = index == 0,
                ))
            }
        }
    }

    private fun ensureProfileLocked(profileId: String) {
        if (db.profiles().find(profileId) != null) return
        val settings = db.models().current(profileId) ?: return
        db.profiles().insert(legacyRow(profileId, settings))
    }

    private fun legacyRow(profileId: String, settings: NativeModelSettingsRow): NativeModelProfileRow =
        NativeModelProfileRow(profileId, if (profileId == DEFAULT_PROFILE_ID) "Default" else profileId,
            ModelProfileKind.CUSTOM.name, ModelProfilePurpose.BOTH.name, "", 0, true,
            null, null,
            null, null, null, null, null, null, null, null, null, null, "{}", null,
            selectedTranslation = false, selectedAgent = false, revision = 1)

    private fun profileRowsLocked(): List<NativeModelProfile> {
        val settings = db.models().latestPerProfile().associateBy { it.profileId }
        return db.profiles().list().mapNotNull { row ->
            val latest = settings[row.profileId] ?: return@mapNotNull null
            profile(row, latest)
        }
    }

    private fun profile(row: NativeModelProfileRow, settings: NativeModelSettingsRow): NativeModelProfile =
        NativeModelProfile(
            id = row.profileId,
            name = row.name,
            kind = profileKind(row.kind),
            purpose = profilePurpose(row.purpose),
            group = row.groupName,
            order = row.orderIndex,
            enabled = row.enabled,
            settings = snapshot(settings),
            limits = if (row.concurrency != null && row.requestsPerMinute != null)
                ModelProfileLimits(row.concurrency, row.requestsPerMinute) else null,
            capabilities = ModelProfileCapabilities(row.tools, row.vision, row.reasoning,
                row.contextWindowTokens, row.maxOutputTokens),
            generation = ModelGenerationSettings(row.temperature, row.topP,
                row.generationMaxOutputTokens, row.reasoningEffort, row.serviceTier),
            extraHeaders = decodeHeaders(row.extraHeaders),
            extraBody = row.extraBody,
            selectedFor = selectedPurposes(row),
        )

    private fun profileRow(id: String, profile: NewModelProfile, order: Int,
        selected: Set<ModelProfilePurpose>): NativeModelProfileRow = NativeModelProfileRow(
        profileId = id, name = profile.name, kind = profile.kind.name, purpose = profile.purpose.name,
        groupName = profile.group, orderIndex = order, enabled = profile.enabled,
        concurrency = profile.limits?.concurrency, requestsPerMinute = profile.limits?.requestsPerMinute,
        tools = profile.capabilities.tools, vision = profile.capabilities.vision,
        reasoning = profile.capabilities.reasoning, contextWindowTokens = profile.capabilities.contextWindowTokens,
        maxOutputTokens = profile.capabilities.maxOutputTokens, temperature = profile.generation.temperature,
        topP = profile.generation.topP, generationMaxOutputTokens = profile.generation.maxOutputTokens,
        reasoningEffort = profile.generation.reasoningEffort, serviceTier = profile.generation.serviceTier,
        extraHeaders = encodeHeaders(profile.extraHeaders), extraBody = profile.extraBody,
        selectedTranslation = ModelProfilePurpose.TRANSLATION in selected,
        selectedAgent = ModelProfilePurpose.AGENT in selected,
    )

    private fun profileKind(value: String): ModelProfileKind =
        ModelProfileKind.entries.firstOrNull { it.name == value }
            ?: throw IllegalStateException("Unknown model profile kind")

    private fun profilePurpose(value: String): ModelProfilePurpose =
        ModelProfilePurpose.entries.firstOrNull { it.name == value }
            ?: throw IllegalStateException("Unknown model profile purpose")

    private fun selectedPurposes(row: NativeModelProfileRow): Set<ModelProfilePurpose> = buildSet {
        if (row.selectedTranslation) add(ModelProfilePurpose.TRANSLATION)
        if (row.selectedAgent) add(ModelProfilePurpose.AGENT)
    }

    private fun encodeHeaders(headers: Map<String, String>): String =
        buildJsonObject { headers.forEach { (name, value) -> put(name, JsonPrimitive(value)) } }.toString()

    private fun decodeHeaders(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val objectValue = try { Json.parseToJsonElement(text) as JsonObject }
        catch (_: Exception) { throw IllegalStateException("Damaged model profile headers") }
        return objectValue.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalStateException("Damaged model profile headers")
        }
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

    companion object {
        const val DEFAULT_PROFILE_ID = "native-remote-model"
        const val MAX_PROFILES = 64
    }
}
