package com.breakyuna.noveltranslator.nativecore.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Public invocation data only. Neither native file paths nor credentials belong here. */
internal object WorkspaceInvocationJson {
    fun encode(request: WorkspaceScriptRequest): String = buildJsonObject {
        put("arguments", JsonObject(request.arguments.mapValues { JsonPrimitive(it.value) }))
        put("materials", JsonArray(request.materials.map { material -> buildJsonObject {
            put("id", material.id.value)
            put("path", material.path.value)
            put("sha256", material.sha256)
            put("sizeBytes", material.sizeBytes)
            put("mediaType", material.mediaType)
            put("ordinal", material.ordinal)
            put("readOnly", true)
        } }))
    }.toString()
}
