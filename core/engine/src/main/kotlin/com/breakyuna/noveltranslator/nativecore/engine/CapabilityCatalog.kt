package com.breakyuna.noveltranslator.nativecore.engine

import java.util.Collections

/**
 * Capability facts are tri-state. UNKNOWN is not a synonym for UNSUPPORTED and
 * never becomes a silent yes: a required feature with UNKNOWN is rejected before
 * dispatch unless a catalog entry explicitly says SUPPORTED.
 */
enum class CapabilitySupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

enum class CapabilityFeature { TOOLS, VISION, REASONING, CONTEXT_WINDOW, OUTPUT_TOKENS }

enum class CapabilityRejectionReason { UNSUPPORTED, UNKNOWN, LIMIT_TOO_SMALL }

data class ModelCapabilities(
    val tools: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val vision: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val reasoning: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
) {
    init {
        require(contextWindowTokens == null || contextWindowTokens in 1..10_000_000)
        require(maxOutputTokens == null || maxOutputTokens in 1..10_000_000)
        require(contextWindowTokens == null || maxOutputTokens == null || maxOutputTokens <= contextWindowTokens)
    }

    companion object {
        val UNKNOWN = ModelCapabilities()
    }
}

/**
 * Features the caller requires for one dispatch. Defaults describe a plain text
 * request with no tools, vision, explicit reasoning or capacity promise.
 */
data class CapabilityRequirement(
    val tools: Boolean = false,
    val vision: Boolean = false,
    val reasoning: Boolean = false,
    val minimumContextTokens: Int? = null,
    val maximumOutputTokens: Int? = null,
) {
    init {
        require(minimumContextTokens == null || minimumContextTokens in 1..10_000_000)
        require(maximumOutputTokens == null || maximumOutputTokens in 1..10_000_000)
    }

    companion object {
        val NONE = CapabilityRequirement()
    }
}

data class CapabilityModelRef(val protocol: ModelProtocol, val model: String) {
    init {
        require(model.isNotBlank() && model.length <= 200 && model.none(Char::isISOControl))
    }
}

data class CapabilityRejection(val feature: CapabilityFeature, val reason: CapabilityRejectionReason)

sealed interface CapabilityDecision {
    data object Supported : CapabilityDecision

    data class Rejected(val rejection: CapabilityRejection) : CapabilityDecision
}

/** Sanitized failure: it identifies a contract field, never model output or credentials. */
class UnsupportedModelCapability(val feature: CapabilityFeature, val reason: CapabilityRejectionReason) :
    IllegalArgumentException("Model capability rejected: $feature ($reason)")

/**
 * Static, explicitly populated capability catalog. Lookup is exact by protocol and
 * model id; there is no name-prefix guessing. An absent model is all UNKNOWN, not
 * an implicit fallback to another provider or model.
 */
class CapabilityCatalog private constructor(entries: Map<CapabilityModelRef, ModelCapabilities>) {
    private val entries: Map<CapabilityModelRef, ModelCapabilities> =
        Collections.unmodifiableMap(LinkedHashMap(entries))

    fun describe(protocol: ModelProtocol, model: String): ModelCapabilities =
        entries[CapabilityModelRef(protocol, model)] ?: ModelCapabilities.UNKNOWN

    fun describe(ref: CapabilityModelRef): ModelCapabilities = entries[ref] ?: ModelCapabilities.UNKNOWN

    /** First rejection in a fixed order: tools, vision, reasoning, context, output. */
    fun validate(
        protocol: ModelProtocol,
        model: String,
        requirement: CapabilityRequirement = CapabilityRequirement.NONE,
    ): CapabilityDecision {
        val rejection = rejections(protocol, model, requirement).firstOrNull()
        return if (rejection == null) CapabilityDecision.Supported else CapabilityDecision.Rejected(rejection)
    }

    fun rejections(
        protocol: ModelProtocol,
        model: String,
        requirement: CapabilityRequirement = CapabilityRequirement.NONE,
    ): List<CapabilityRejection> {
        val facts = describe(protocol, model)
        val result = mutableListOf<CapabilityRejection>()
        checkFeature(protocol, CapabilityFeature.TOOLS, requirement.tools, facts.tools, result)
        checkFeature(protocol, CapabilityFeature.VISION, requirement.vision, facts.vision, result)
        checkFeature(protocol, CapabilityFeature.REASONING, requirement.reasoning, facts.reasoning, result)
        requirement.minimumContextTokens?.let { minimum ->
            when {
                facts.contextWindowTokens == null ->
                    result.add(CapabilityRejection(CapabilityFeature.CONTEXT_WINDOW, CapabilityRejectionReason.UNKNOWN))
                facts.contextWindowTokens < minimum ->
                    result.add(CapabilityRejection(CapabilityFeature.CONTEXT_WINDOW, CapabilityRejectionReason.LIMIT_TOO_SMALL))
            }
        }
        requirement.maximumOutputTokens?.let { maximum ->
            when {
                facts.maxOutputTokens == null ->
                    result.add(CapabilityRejection(CapabilityFeature.OUTPUT_TOKENS, CapabilityRejectionReason.UNKNOWN))
                facts.maxOutputTokens < maximum ->
                    result.add(CapabilityRejection(CapabilityFeature.OUTPUT_TOKENS, CapabilityRejectionReason.LIMIT_TOO_SMALL))
            }
        }
        return Collections.unmodifiableList(result)
    }

    fun requireSupported(
        protocol: ModelProtocol,
        model: String,
        requirement: CapabilityRequirement = CapabilityRequirement.NONE,
    ) {
        when (val decision = validate(protocol, model, requirement)) {
            CapabilityDecision.Supported -> Unit
            is CapabilityDecision.Rejected ->
                throw UnsupportedModelCapability(decision.rejection.feature, decision.rejection.reason)
        }
    }

    /** Returns a new immutable catalog; the receiver is never mutated. */
    fun with(protocol: ModelProtocol, model: String, capabilities: ModelCapabilities): CapabilityCatalog {
        val ref = CapabilityModelRef(protocol, model)
        require(!entries.containsKey(ref)) { "Duplicate capability entry" }
        return CapabilityCatalog(LinkedHashMap(entries).apply { put(ref, capabilities) })
    }

    private fun checkFeature(
        protocol: ModelProtocol,
        feature: CapabilityFeature,
        required: Boolean,
        support: CapabilitySupport,
        result: MutableList<CapabilityRejection>,
    ) {
        if (!required) return
        // Honest protocol-level limits for the codecs in this slice. A catalog entry
        // cannot claim SakuraLLM tool or image support that the wire adapter lacks.
        if (protocol == ModelProtocol.SAKURA_LLM && (feature == CapabilityFeature.TOOLS || feature == CapabilityFeature.VISION)) {
            result.add(CapabilityRejection(feature, CapabilityRejectionReason.UNSUPPORTED))
            return
        }
        when (support) {
            CapabilitySupport.SUPPORTED -> Unit
            CapabilitySupport.UNSUPPORTED -> result.add(CapabilityRejection(feature, CapabilityRejectionReason.UNSUPPORTED))
            CapabilitySupport.UNKNOWN -> result.add(CapabilityRejection(feature, CapabilityRejectionReason.UNKNOWN))
        }
    }

    override fun toString(): String = "CapabilityCatalog(entries=${entries.size})"

    companion object {
        val EMPTY = CapabilityCatalog(emptyMap())

        fun of(vararg entries: Pair<CapabilityModelRef, ModelCapabilities>): CapabilityCatalog {
            val map = LinkedHashMap<CapabilityModelRef, ModelCapabilities>()
            for ((ref, capabilities) in entries) {
                require(map.put(ref, capabilities) == null) { "Duplicate capability entry" }
            }
            return CapabilityCatalog(map)
        }
    }
}
