package com.scenix.tv.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * The safe, typed part of an add-on manifest used by standalone mode.
 *
 * A manifest is an untrusted network document.  This model intentionally keeps only bounded data
 * that can influence a protocol request, then builds every request from escaped path segments. It
 * does not preserve configuration URLs, tokens, or arbitrary manifest fields for display or logs.
 * The Hub has its own runtime implementation; this is the equivalent local contract for the TV
 * when it is running without one.
 */
data class AddonRequest(
    val resource: String,
    /** Escaped, relative protocol path such as `catalog/movie/top/default.json`. */
    val relativePath: String,
)

data class AddonResourceSpec(
    val name: String,
    /** Empty means that the manifest did not restrict this resource to particular media types. */
    val types: Set<String>,
    /** Empty means that the manifest did not restrict accepted item identities. */
    val idPrefixes: Set<String>,
)

data class AddonExtraSpec(
    val name: String,
    val required: Boolean,
    /** A non-empty set is a closed list supplied by the source; empty means free-form input. */
    val options: Set<String>,
    val optionsLimit: Int?,
)

/** One manifest catalogue, including the legal query controls for that catalogue only. */
data class AddonCatalogSpec(
    val type: String,
    val id: String,
    val name: String,
    val extras: List<AddonExtraSpec>,
    /** Kept only for backward-compatible callers that already render the manifest's own copy. */
    internal val raw: JSONObject? = null,
) {
    val searchable: Boolean get() = extras.any { it.name == "search" }
}

/** Hints are state only. Configuration values and links never enter this object. */
data class AddonConfigurationHints(
    val configurable: Boolean,
    val configurationRequired: Boolean,
    val adult: Boolean,
    val peerToPeer: Boolean,
    val epgProvider: Boolean,
) {
    val needsSecureHandoff: Boolean get() = configurable || configurationRequired
}

/**
 * Parsed protocol metadata and request planner for one add-on manifest.
 *
 * `catalogs` describes content shelves. `addonCatalogs` is deliberately separate: it describes a
 * source-directory feed and must never be silently mixed with content catalogues or a reviewed
 * directory. A future caller can place it behind the product's Community lane and acknowledgement.
 */
class AddonManifestDomain private constructor(
    val id: String,
    val name: String,
    val version: String,
    val resources: List<AddonResourceSpec>,
    val catalogs: List<AddonCatalogSpec>,
    val addonCatalogs: List<AddonCatalogSpec>,
    val configuration: AddonConfigurationHints,
) {
    private val resourcesByName = resources.associateBy { it.name }

    fun has(resource: String): Boolean = resourcesByName.containsKey(normalizeKey(resource))

    fun types(resource: String): List<String> =
        resourcesByName[normalizeKey(resource)]?.types?.toList().orEmpty()

    fun searchableCatalogs(): List<AddonCatalogSpec> = catalogs.filter { it.searchable }

    /** Plan a content catalogue request from typed controls. Unknown/invalid input gets no route. */
    fun planCatalog(type: String, catalogId: String, values: Map<String, String> = emptyMap()): AddonRequest? {
        val safeType = normalizeKey(type)
        val safeId = boundedText(catalogId, MAX_CATALOG_ID) ?: return null
        val catalog = catalogs.firstOrNull { it.type == safeType && it.id == safeId } ?: return null
        // Catalogs are their own manifest declaration in the protocol. They do not rely on a
        // duplicate `catalog` entry in resources, which matches existing protocol clients.
        val extra = planExtras(catalog, values) ?: return null
        return catalogRequest("catalog", safeType, safeId, extra)
    }

    /** Compatibility adapter for existing standalone callers that still hand over `search=…` text. */
    fun planLegacyCatalog(type: String, catalogId: String, legacyExtra: String): AddonRequest? {
        val safeType = normalizeKey(type)
        val safeId = boundedText(catalogId, MAX_CATALOG_ID) ?: return null
        val catalog = catalogs.firstOrNull { it.type == safeType && it.id == safeId } ?: return null
        val text = legacyExtra.trim()
        val values = parseLegacyExtra(text) ?: return null
        val extra = planExtras(catalog, values) ?: return null
        // The existing standalone backend has always asked no-filter catalogues through a legacy
        // `/default.json` path. Keep that compatibility while new typed callers use the canonical
        // three-segment path when there are no declared values.
        return catalogRequest("catalog", safeType, safeId, extra, includeLegacyDefault = text == "default")
    }

    fun planMeta(type: String, remoteId: String): AddonRequest? =
        planItemResource("meta", type, remoteId)

    fun planStreams(type: String, remoteId: String): AddonRequest? =
        planItemResource("stream", type, remoteId)

    fun planSubtitles(type: String, remoteId: String, language: String): AddonRequest? {
        val safeType = normalizeKey(type)
        val safeId = boundedText(remoteId, MAX_REMOTE_ID) ?: return null
        val declared = resourcesByName["subtitles"] ?: return null
        if (!supports("subtitles", safeType)) return null
        if (declared.idPrefixes.isNotEmpty() && declared.idPrefixes.none { safeId.startsWith(it) }) return null
        val safeLanguage = normalizeLanguage(language) ?: return null
        return request("subtitles", listOf(safeType, safeId, safeLanguage))
    }

    /**
     * Plan a source-directory request. The resource route uses a directory scope such as `all`,
     * not a media type, so its resource declaration is checked for presence but not media types.
     * The caller must still surface the result as Community discovery rather than as reviewed.
     */
    fun planAddonCatalog(scope: String, catalogId: String, values: Map<String, String> = emptyMap()): AddonRequest? {
        val safeScope = normalizeKey(scope)
        val safeId = boundedText(catalogId, MAX_CATALOG_ID) ?: return null
        val catalog = addonCatalogs.firstOrNull { it.type == safeScope && it.id == safeId } ?: return null
        val extra = planExtras(catalog, values) ?: return null
        // As with content catalogues, addonCatalogs is the capability declaration. It remains a
        // separate source-directory lane; merely parsing it does not promote any descriptor.
        return catalogRequest("addon_catalog", safeScope, safeId, extra)
    }

    private fun planItemResource(resource: String, type: String, remoteId: String): AddonRequest? {
        val safeType = normalizeKey(type)
        val safeId = boundedText(remoteId, MAX_REMOTE_ID) ?: return null
        val declared = resourcesByName[resource] ?: return null
        if (!supports(resource, safeType)) return null
        if (declared.idPrefixes.isNotEmpty() && declared.idPrefixes.none { safeId.startsWith(it) }) return null
        return request(resource, listOf(safeType, safeId))
    }

    private fun supports(resource: String, type: String): Boolean {
        val declared = resourcesByName[resource] ?: return false
        return declared.types.isEmpty() || type in declared.types
    }

    /**
     * Build the catalogue's third path segment from declared extras only. The full text is escaped
     * as one segment afterwards, so a search term can never introduce a slash, query string, or a
     * second route. Required extras are rejected rather than silently sent as `default`.
     */
    private fun planExtras(catalog: AddonCatalogSpec, values: Map<String, String>): List<Pair<String, String>>? {
        val declared = catalog.extras.associateBy { it.name }
        val normalized = LinkedHashMap<String, String>()
        for ((rawName, rawValue) in values) {
            val name = normalizeKey(rawName)
            val spec = declared[name] ?: return null
            val value = boundedText(rawValue, MAX_EXTRA_VALUE) ?: return null
            if (spec.options.isNotEmpty() && value !in spec.options) return null
            normalized[name] = value
        }
        for (spec in catalog.extras) {
            if (spec.required && normalized[spec.name].isNullOrBlank()) return null
        }
        return catalog.extras.mapNotNull { spec ->
            normalized[spec.name]?.let { value -> spec.name to value }
        }
    }

    /** Canonical protocol paths have no fourth segment until the request has actual extra values. */
    private fun catalogRequest(
        resource: String,
        type: String,
        catalogId: String,
        extras: List<Pair<String, String>>,
        includeLegacyDefault: Boolean = false,
    ): AddonRequest? {
        val root = request(resource, listOf(type, catalogId)) ?: return null
        val extraPath = when {
            extras.isNotEmpty() -> extras.joinToString("&") { (name, value) ->
                "${encodeSegment(name)}=${encodeSegment(value)}"
            }
            includeLegacyDefault -> "default"
            else -> return root
        }
        return AddonRequest(resource, root.relativePath.removeSuffix(".json") + "/$extraPath.json")
    }

    private fun request(resource: String, segments: List<String>): AddonRequest? {
        val safeResource = normalizeKey(resource)
        if (safeResource.isBlank() || segments.isEmpty() || segments.any { boundedText(it, MAX_ROUTE_SEGMENT) == null }) return null
        val encoded = listOf(encodeSegment(safeResource)) + segments.map(::encodeSegment)
        return AddonRequest(resource, encoded.joinToString("/") + ".json")
    }

    private fun parseLegacyExtra(raw: String): Map<String, String>? {
        val text = raw.trim()
        if (text == "default") return emptyMap()
        if (text.isBlank() || text.length > MAX_LEGACY_EXTRA || hasControl(text)) return null
        val out = LinkedHashMap<String, String>()
        for (part in text.split('&')) {
            val at = part.indexOf('=')
            if (at <= 0 || at == part.lastIndex) return null
            val key = decodeComponent(part.substring(0, at)) ?: return null
            val value = decodeComponent(part.substring(at + 1)) ?: return null
            val normalized = normalizeKey(key)
            if (normalized in out) return null
            out[normalized] = value
        }
        return out
    }

    companion object {
        private const val MAX_RESOURCES = 32
        private const val MAX_CATALOGS = 128
        private const val MAX_EXTRAS = 24
        private const val MAX_OPTIONS = 256
        private const val MAX_TOKEN = 64
        private const val MAX_PREFIX = 160
        private const val MAX_CATALOG_ID = 160
        private const val MAX_REMOTE_ID = 512
        private const val MAX_EXTRA_VALUE = 512
        private const val MAX_ROUTE_SEGMENT = 1536
        private const val MAX_LEGACY_EXTRA = 1024

        fun parse(body: JSONObject): AddonManifestDomain {
            val globalTypes = tokens(body.optJSONArray("types"), MAX_RESOURCES)
            val globalPrefixes = values(body.optJSONArray("idPrefixes"), MAX_RESOURCES, MAX_PREFIX)
            val resources = parseResources(body.optJSONArray("resources"), globalTypes, globalPrefixes)
            val globalSearch = body.optBoolean("search", false)
            val catalogs = parseCatalogs(body.optJSONArray("catalogs"), globalSearch)
            val addonCatalogs = parseCatalogs(body.optJSONArray("addonCatalogs"), false)
            val hints = body.optJSONObject("behaviorHints")
            val configuration = AddonConfigurationHints(
                configurable = hints?.optBoolean("configurable", body.optBoolean("configurable", false))
                    ?: body.optBoolean("configurable", false),
                configurationRequired = hints?.optBoolean(
                    "configurationRequired",
                    body.optBoolean("configurationRequired", false),
                ) ?: body.optBoolean("configurationRequired", false),
                adult = hints?.optBoolean("adult", false) ?: false,
                peerToPeer = hints?.optBoolean("p2p", false) ?: false,
                epgProvider = hints?.optBoolean("epgProvider", false) ?: false,
            )
            return AddonManifestDomain(
                id = boundedText(body.optString("id", ""), 160).orEmpty(),
                name = boundedText(body.optString("name", ""), 160).orEmpty(),
                version = boundedText(body.optString("version", ""), 64).orEmpty(),
                resources = resources,
                catalogs = catalogs,
                addonCatalogs = addonCatalogs,
                configuration = configuration,
            )
        }

        /**
         * Build a planner from an already validated projection.  Production manifest fetches use
         * [parse]; this factory keeps the request-policy half independently testable on the JVM
         * without Android's framework JSON stubs.
         */
        fun fromParts(
            resources: List<AddonResourceSpec>,
            catalogs: List<AddonCatalogSpec>,
            addonCatalogs: List<AddonCatalogSpec> = emptyList(),
            configuration: AddonConfigurationHints = AddonConfigurationHints(
                configurable = false,
                configurationRequired = false,
                adult = false,
                peerToPeer = false,
                epgProvider = false,
            ),
        ): AddonManifestDomain = AddonManifestDomain(
            id = "",
            name = "",
            version = "",
            resources = resources.take(MAX_RESOURCES),
            catalogs = catalogs.take(MAX_CATALOGS),
            addonCatalogs = addonCatalogs.take(MAX_CATALOGS),
            configuration = configuration,
        )

        private fun parseResources(
            source: JSONArray?,
            globalTypes: Set<String>,
            globalPrefixes: Set<String>,
        ): List<AddonResourceSpec> {
            val merged = LinkedHashMap<String, AddonResourceSpec>()
            if (source == null) return emptyList()
            for (index in 0 until minOf(source.length(), MAX_RESOURCES)) {
                val raw = source.opt(index)
                val objectValue = raw as? JSONObject
                val name = when (raw) {
                    is String -> normalizeKey(raw)
                    is JSONObject -> normalizeKey(raw.optString("name", ""))
                    else -> ""
                }
                if (name.isBlank()) continue
                val localTypes = objectValue?.optJSONArray("types")?.let { tokens(it, MAX_RESOURCES) }
                val localPrefixes = objectValue?.optJSONArray("idPrefixes")?.let { values(it, MAX_RESOURCES, MAX_PREFIX) }
                val next = AddonResourceSpec(
                    name = name,
                    types = localTypes ?: globalTypes,
                    idPrefixes = localPrefixes ?: globalPrefixes,
                )
                val old = merged[name]
                merged[name] = if (old == null) next else AddonResourceSpec(
                    name = name,
                    types = old.types + next.types,
                    idPrefixes = old.idPrefixes + next.idPrefixes,
                )
            }
            return merged.values.toList()
        }

        private fun parseCatalogs(source: JSONArray?, addImplicitSearch: Boolean): List<AddonCatalogSpec> {
            if (source == null) return emptyList()
            val out = ArrayList<AddonCatalogSpec>()
            val seen = HashSet<String>()
            for (index in 0 until minOf(source.length(), MAX_CATALOGS)) {
                val raw = source.optJSONObject(index) ?: continue
                val type = normalizeKey(raw.optString("type", ""))
                val id = boundedText(raw.optString("id", ""), MAX_CATALOG_ID) ?: continue
                if (type.isBlank() || id.isBlank() || !seen.add("$type\u0000$id")) continue
                val name = boundedText(raw.optString("name", ""), 160).orEmpty()
                out.add(AddonCatalogSpec(
                    type = type,
                    id = id,
                    name = name,
                    extras = parseExtras(raw, addImplicitSearch),
                    raw = raw,
                ))
            }
            return out
        }

        private fun parseExtras(catalog: JSONObject, addImplicitSearch: Boolean): List<AddonExtraSpec> {
            val out = LinkedHashMap<String, AddonExtraSpec>()
            val required = tokens(catalog.optJSONArray("extraRequired"), MAX_EXTRAS)
            fun put(name: String, isRequired: Boolean, options: Set<String>, optionsLimit: Int?) {
                if (name.isBlank() || out.size >= MAX_EXTRAS) return
                val old = out[name]
                out[name] = if (old == null) AddonExtraSpec(name, isRequired, options, optionsLimit)
                else AddonExtraSpec(
                    name = name,
                    required = old.required || isRequired,
                    options = old.options + options,
                    optionsLimit = old.optionsLimit ?: optionsLimit,
                )
            }
            val rawExtras = catalog.optJSONArray("extra")
            if (rawExtras != null) for (index in 0 until minOf(rawExtras.length(), MAX_EXTRAS)) {
                when (val raw = rawExtras.opt(index)) {
                    is String -> {
                        val name = normalizeKey(raw)
                        put(name, name in required, emptySet(), null)
                    }
                    is JSONObject -> {
                        val name = normalizeKey(raw.optString("name", ""))
                        val limit = raw.optInt("optionsLimit", 0).takeIf { it in 1..MAX_OPTIONS }
                        put(name, raw.optBoolean("isRequired", false) || name in required,
                            values(raw.optJSONArray("options"), MAX_OPTIONS, MAX_EXTRA_VALUE), limit)
                    }
                }
            }
            for (name in tokens(catalog.optJSONArray("extraSupported"), MAX_EXTRAS)) {
                put(name, name in required, emptySet(), null)
            }
            for (name in required) put(name, true, emptySet(), null)
            if (addImplicitSearch) put("search", false, emptySet(), null)
            return out.values.toList()
        }

        private fun tokens(array: JSONArray?, limit: Int): Set<String> {
            val out = LinkedHashSet<String>()
            if (array == null) return out
            for (index in 0 until minOf(array.length(), limit)) {
                val item = normalizeKey(array.optString(index, ""))
                if (item.isNotBlank()) out.add(item)
            }
            return out
        }

        private fun values(array: JSONArray?, limit: Int, max: Int): Set<String> {
            val out = LinkedHashSet<String>()
            if (array == null) return out
            for (index in 0 until minOf(array.length(), limit)) {
                boundedText(array.optString(index, ""), max)?.let(out::add)
            }
            return out
        }

        private fun normalizeKey(value: String): String {
            val text = value.trim().lowercase(Locale.ROOT)
            return if (text.length in 1..MAX_TOKEN && KEY.matches(text)) text else ""
        }

        private fun normalizeLanguage(value: String): String? {
            val text = value.trim().lowercase(Locale.ROOT)
            return if (text.length in 2..24 && LANGUAGE.matches(text)) text else null
        }

        private fun boundedText(value: String, max: Int): String? {
            val text = value.trim()
            return if (text.isBlank() || text.length > max || hasControl(text)) null else text
        }

        private fun hasControl(value: String): Boolean = value.any { it.code < 0x20 || it.code == 0x7f }

        private fun encodeSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private fun decodeComponent(value: String): String? = try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name()).takeIf { it.length <= MAX_EXTRA_VALUE && !hasControl(it) }
        } catch (_: Throwable) {
            null
        }

        private val KEY = Regex("^[a-z][a-z0-9_-]*$")
        private val LANGUAGE = Regex("^[a-z]{2,3}(?:-[a-z0-9]{2,8})?$")
    }
}
