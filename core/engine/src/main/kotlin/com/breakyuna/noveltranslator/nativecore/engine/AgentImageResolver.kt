package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.FrozenImageRef

/**
 * Resolves an already frozen image identity to its exact bytes for one dispatch.
 * Implementations own bounded storage access and must not log, cache or mutate the
 * payload. The engine verifies the returned bytes against [FrozenImageRef.sha256]
 * before any request body is built, so a changed source file or a resolver bug
 * fails closed instead of sending a different image.
 *
 * Resolution happens before dispatch. A missing resolver, unreadable attachment,
 * invalid MIME/size or digest mismatch is a local CONTENT failure; no paid model
 * request is started and no image is silently dropped.
 */
interface AgentImageResolver {
    suspend fun resolve(image: FrozenImageRef): ByteArray
}
