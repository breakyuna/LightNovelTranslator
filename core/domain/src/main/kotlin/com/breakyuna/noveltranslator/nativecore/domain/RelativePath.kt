package com.breakyuna.noveltranslator.nativecore.domain

/**
 * Canonical portable path inside a project/archive/workspace. This validates a
 * logical path only; a filesystem adapter must separately refuse symlink escape.
 * URI decoding and platform path conversion happen before this boundary.
 */
@JvmInline
value class RelativePath(val value: String) {
    init {
        require(value.isNotEmpty()) { "Empty relative path" }
        require(!value.startsWith('/') && '\\' !in value) { "Non-relative path" }
        require(!(value.length >= 2 && value[0].isLetter() && value[1] == ':')) {
            "Drive path is not relative"
        }
        require(value.none { it.isISOControl() }) { "Control character in path" }
        require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Non-canonical relative path"
        }
    }
}
