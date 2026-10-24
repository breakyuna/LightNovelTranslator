package com.breakyuna.noveltranslator.nativecore.domain

/** Credential-free rotation facts for one immutable model configuration revision. */
enum class ModelKeyFailure { AUTHENTICATION, RATE_LIMIT, SERVER, NETWORK }

data class ModelKeyState(val enabled: Boolean, val failures: Int, val cooldownUntilMillis: Long,
    val lastFailure: ModelKeyFailure?) {
    init { require(failures >= 0 && cooldownUntilMillis >= 0) }
}

class ModelKeyPoolState(val configuration: ModelConfigurationRef, val nextIndex: Int, keys: List<ModelKeyState>) {
    val keys: List<ModelKeyState> = frozenList(keys)
    init {
        require(this.keys.size <= NativeModelSettings.MAX_KEYS)
        require(if (this.keys.isEmpty()) nextIndex == 0 else nextIndex in this.keys.indices)
    }
}

/** Stores no secret or endpoint. Missing is first use; malformed state must fail closed. */
interface ModelKeyStatePort {
    suspend fun read(configuration: ModelConfigurationRef): ModelKeyPoolState?
    suspend fun write(state: ModelKeyPoolState)
}
