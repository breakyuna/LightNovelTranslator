package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef

internal enum class NativeAssetChange { REMOVE, RESET, MOVE_UP, MOVE_DOWN, ENABLE, DISABLE }

/** A displayed decision is bound to one project generation and revision. */
internal class NativeAssetMutation(
    val ref: LocalProjectRef,
    val revision: Long,
    val assetId: Long,
    val change: NativeAssetChange,
    val command: CommandId,
)
