package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProtocol

/** Explicit domain-to-wire mapping; never guesses a protocol from an endpoint or model name. */
fun NativeModelProtocol.toModelProtocol(): ModelProtocol = when (this) {
    NativeModelProtocol.OPENAI_CHAT_COMPLETIONS -> ModelProtocol.OPENAI_CHAT_COMPLETIONS
    NativeModelProtocol.OPENAI_RESPONSES -> ModelProtocol.OPENAI_RESPONSES
    NativeModelProtocol.GOOGLE_GEMINI -> ModelProtocol.GOOGLE_GEMINI
    NativeModelProtocol.ANTHROPIC_MESSAGES -> ModelProtocol.ANTHROPIC_MESSAGES
    NativeModelProtocol.SAKURA_LLM -> ModelProtocol.SAKURA_LLM
}
