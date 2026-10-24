package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProtocol
import com.breakyuna.noveltranslator.nativecore.domain.PriceTable
import com.breakyuna.noveltranslator.nativecore.domain.PriceTableCodec
import com.breakyuna.noveltranslator.nativecore.domain.PromptTemplateCodec
import com.breakyuna.noveltranslator.nativecore.domain.PromptTemplateSet
import com.breakyuna.noveltranslator.nativecore.domain.RulePreset
import com.breakyuna.noveltranslator.nativecore.domain.RulePresetCodec
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.storage.NativeModelSettingsStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePriceTableStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePromptTemplateStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeRulePresetStore
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupDataPort
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupEntry
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupEntryKind
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupPaths
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupRestoreSession
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupStagedEntry
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Application-level backup adapter for non-secret settings. It never exports API keys, OAuth
 * tokens or execution approvals. Model preferences are restored without credentials, so the
 * user must re-authenticate after a restore; the UI states this explicitly.
 */
internal class NativeAppBackupDataPort(
    private val models: NativeModelSettingsStore,
    private val prices: NativePriceTableStore,
    private val templates: NativePromptTemplateStore,
    private val presets: NativeRulePresetStore,
) : AppBackupDataPort {
    override suspend fun exportEntries(): List<AppBackupEntry> {
        val result = ArrayList<AppBackupEntry>()
        models.current()?.let { settings ->
            val json = buildJsonObject {
                put("schema", 1)
                put("endpoint", settings.endpoint)
                put("model", settings.model)
                put("protocol", settings.protocol.name)
                put("credentialsExcluded", true)
            }.toString()
            result += AppBackupEntry.bytes(AppBackupPaths.MODEL_SETTINGS, AppBackupEntryKind.MODEL_SETTINGS,
                TextProjectFingerprint.utf8(json))
        }
        result += AppBackupEntry.bytes(AppBackupPaths.PRICE_TABLE, AppBackupEntryKind.PRICE_TABLE,
            TextProjectFingerprint.utf8(prices.exportJson()))
        val library = templates.read()
        result += AppBackupEntry.bytes(AppBackupPaths.PROMPT_TEMPLATES, AppBackupEntryKind.PROMPT_TEMPLATES,
            TextProjectFingerprint.utf8(PromptTemplateCodec.encode(library.templates)))
        presets.list().forEachIndexed { index, summary ->
            val preset = presets.load(summary.id)
            result += AppBackupEntry.bytes(AppBackupPaths.rulePreset("preset-$index.json"), AppBackupEntryKind.RULE_PRESET,
                TextProjectFingerprint.utf8(RulePresetCodec.encode(preset)))
        }
        return result
    }

    override suspend fun beginRestore(entries: List<AppBackupStagedEntry>): AppBackupRestoreSession {
        var model: RestoredModel? = null
        var price: PriceTable? = null
        var restoredTemplates: PromptTemplateSet? = null
        val restoredPresets = ArrayList<RulePreset>()
        val seenPresets = HashSet<String>()
        for (entry in entries) {
            val bytes = entry.open().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= MAX_ENTRY_BYTES) { "Backup entry is too large" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            val text = decodeUtf8(bytes)
            when (entry.kind) {
                AppBackupEntryKind.MODEL_SETTINGS -> {
                    require(model == null) { "Duplicate model settings" }
                    val root = strictJson(text)
                    require(root["credentialsExcluded"]?.jsonPrimitive?.content == "true") {
                        "Backup manifest does not exclude credentials"
                    }
                    require(root["schema"]?.jsonPrimitive?.int == 1) { "Unsupported model settings schema" }
                    val endpoint = root["endpoint"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing endpoint")
                    val modelName = root["model"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing model")
                    val protocol = NativeModelProtocol.entries.firstOrNull {
                        it.name == root["protocol"]?.jsonPrimitive?.content
                    } ?: throw IllegalArgumentException("Unknown model protocol")
                    model = RestoredModel(endpoint, modelName, protocol)
                }
                AppBackupEntryKind.PRICE_TABLE -> {
                    require(price == null) { "Duplicate price table" }
                    price = PriceTableCodec.decode(text)
                }
                AppBackupEntryKind.PROMPT_TEMPLATES -> {
                    require(restoredTemplates == null) { "Duplicate prompt templates" }
                    restoredTemplates = PromptTemplateCodec.decode(text)
                }
                AppBackupEntryKind.RULE_PRESET -> {
                    val preset = RulePresetCodec.decode(text)
                    require(seenPresets.add(preset.id)) { "Duplicate rule preset" }
                    restoredPresets += preset
                }
                AppBackupEntryKind.AGENT_CHAT_HISTORY ->
                    throw IllegalArgumentException("Agent chat restore is not supported by this build")
            }
        }
        val fixedModel = model
        val fixedPrice = price
        val fixedTemplates = restoredTemplates
        return object : AppBackupRestoreSession {
            override suspend fun publish() {
                fixedModel?.let { models.saveProfile(it.endpoint, it.model, it.protocol, emptyList()) }
                fixedPrice?.let { prices.replace(it) }
                fixedTemplates?.let { restored ->
                    val current = this@NativeAppBackupDataPort.templates.read()
                    this@NativeAppBackupDataPort.templates.replace(current.fingerprint, restored)
                }
                for (preset in restoredPresets) {
                    val existing = runCatching { presets.load(preset.id) }.getOrNull()
                    presets.save(preset, existing?.let(RulePresetCodec::fingerprint))
                }
            }
        }
    }

    private fun strictJson(text: String): JsonObject = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (failure: Exception) {
        throw IllegalArgumentException("Invalid backup JSON", failure)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (failure: Exception) {
        throw IllegalArgumentException("Backup entry is not valid UTF-8", failure)
    }

    private class RestoredModel(val endpoint: String, val model: String, val protocol: NativeModelProtocol)

    private companion object {
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    }
}
