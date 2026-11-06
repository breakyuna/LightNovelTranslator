package com.breakyuna.noveltranslator.nativecore.workspace

import java.security.MessageDigest
import java.util.Collections

internal fun <T> workspaceFrozenList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal fun <T> workspaceFrozenSet(values: Set<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

internal fun <K, V> workspaceFrozenMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

/** Lowercase hexadecimal SHA-256 used for every workspace fingerprint. */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun requireSha256Hex(value: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Invalid SHA-256"
    }
}

/**
 * Rejects isolated surrogates before hashing or comparison. UTF-8 encoding would silently
 * replace them, so two different Kotlin strings could otherwise share one fingerprint.
 */
internal fun requireWellFormedUnicode(value: String) {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character.isHighSurrogate()) {
            require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                "Unpaired surrogate"
            }
            index += 2
        } else if (character.isLowSurrogate()) {
            throw IllegalArgumentException("Unpaired surrogate")
        } else {
            index += 1
        }
    }
}
