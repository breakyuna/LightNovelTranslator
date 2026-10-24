package com.breakyuna.noveltranslator.nativecore.storage

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.serialization.json.*

/** Explicit allow-list; request bodies, endpoints and credentials have no path into the export. */
internal fun usageLedgerEntryJson(entry: UsageLedgerEntry): JsonObject = buildJsonObject {
    put("type", "request"); put("id", entry.id); put("channel", entry.channel.name)
    put("attempt", entry.attempt.value); put("project", entry.run.projectId.value)
    put("instance", entry.ref?.instanceId?.value?.let(::JsonPrimitive) ?: JsonNull)
    put("generation", entry.ref?.generation?.value?.let(::JsonPrimitive) ?: JsonNull)
    put("chat", entry.run.chatId?.value?.let(::JsonPrimitive) ?: JsonNull)
    put("run", entry.run.runId.value); put("command", entry.run.commandId.value)
    put("configuration", entry.configuration.id); put("configuration_revision", entry.configuration.revision)
    put("model", entry.model?.let(::JsonPrimitive) ?: JsonNull); put("state", entry.state.name)
    put("usage", entry.usage?.let { Json.parseToJsonElement(TranslationUsageCodec.encode(it)) } ?: JsonNull)
    put("stop", entry.stop?.let { JsonPrimitive(it.name) } ?: JsonNull)
    put("failure", entry.failure?.let { JsonPrimitive(it.name) } ?: JsonNull)
}
