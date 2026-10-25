package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class PdfPageMode(val code: Char) { KEEP('k'), OMIT('o'), TRANSLATE('t') }

/** On-demand text only; implementations must check revision and frozen source identity atomically. */
interface PdfPageReadPort {
    suspend fun pdfPage(ref: LocalProjectRef, revision: Long, asset: ProjectAssetSummary, page: Int): TextProjectItem
}

/** Decodes the imported page decision from one item's validated PDF binding. */
object PdfPageDecisionBinding {
    fun mode(page: Int, tag: String, extraField: String): PdfPageMode {
        require(page >= 1 && tag == "page:$page")
        val binding = JsonObjectValue.parse(extraField, JsonInputLimits(65_536, 8)).json
        require(binding["v"] == JsonPrimitive(1) && binding["page"] == JsonPrimitive(page))
        val decision = binding["decision"] as? JsonPrimitive ?: throw IllegalArgumentException("PDF binding")
        require(decision.isString)
        return when (decision.content) {
            "keep" -> PdfPageMode.KEEP
            "omit" -> PdfPageMode.OMIT
            "translate" -> PdfPageMode.TRANSLATE
            else -> throw IllegalArgumentException("PDF binding")
        }
    }
    fun mode(item: TextProjectItem): PdfPageMode = mode(item.row, item.metadata.tag, item.metadata.extraField)
}

/** One character per original page. '-' retains the imported decision. Never mutates item metadata. */
class PdfAssetPagePolicy(val assetId: Long, val sha256: String, val decisions: String) {
    init {
        require(assetId > 0)
        require(sha256.length == 64 && sha256.all { it in "0123456789abcdef" })
        require(decisions.length in 1..5_000 && decisions.all { it in "-kot" })
    }
    fun matches(asset: ProjectAssetSummary): Boolean = asset.id == assetId && asset.sha256 == sha256 &&
        asset.format == ProjectAssetFormat.PDF && asset.itemCount == decisions.length
    fun mode(page: Int): PdfPageMode? {
        require(page in 1..decisions.length)
        return PdfPageMode.entries.firstOrNull { it.code == decisions[page - 1] }
    }
    fun effectiveMode(item: TextProjectItem): PdfPageMode {
        require(item.assetId == assetId && item.row in 1..decisions.length)
        return mode(item.row) ?: PdfPageDecisionBinding.mode(item)
    }
    override fun toString() = "PdfAssetPagePolicy(redacted)"

    companion object {
        internal fun decode(root: JsonObject): PdfAssetPagePolicy {
            require(root.keys == setOf("asset", "sha256", "decisions"))
            val id = root["asset"] as? JsonPrimitive ?: throw IllegalArgumentException("settings")
            require(!id.isString && id.content.matches(Regex("[1-9][0-9]*")))
            fun text(key: String): String {
                val value = root[key] as? JsonPrimitive ?: throw IllegalArgumentException("settings")
                require(value.isString); return value.content
            }
            return PdfAssetPagePolicy(requireNotNull(id.longOrNull), text("sha256"), text("decisions"))
        }
    }
}

/** Shared by target freezing, explicit retry and the execution planner. Removed assets are inert. */
class ProjectPageTranslationPolicy(settings: ProjectSettings, assets: List<ProjectAssetSummary>) {
    private val pdfAssets = assets.filter { it.format == ProjectAssetFormat.PDF }.associateBy { it.id }
    private val policies = settings.pdfPages.mapNotNull { policy ->
        assets.firstOrNull { it.id == policy.assetId }?.let { asset ->
            require(policy.matches(asset))
            asset.id to policy
        }
    }.toMap()

    fun accepts(item: TextProjectItem): Boolean {
        val asset = pdfAssets[item.assetId] ?: return true
        require(item.row in 1..asset.itemCount && item.metadata.tag == "page:${item.row}")
        val mode = policies[item.assetId]?.effectiveMode(item) ?: PdfPageDecisionBinding.mode(item)
        return mode == PdfPageMode.TRANSLATE
    }
}
