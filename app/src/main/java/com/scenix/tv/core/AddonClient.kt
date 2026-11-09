package com.scenix.tv.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ThreadFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One addon this TV pulls content from, exactly as the owner configured it: a name to read in
 * Settings and the manifest URL they pasted. Nothing here is a constant — a build ships no addons,
 * so an empty list is a normal state and the UI says so instead of inventing shelves.
 *
 * The addon carries its own configuration in the URL path (that is how every community addon ships
 * it), so the base for every request is the manifest URL with `/manifest.json` removed, never the
 * origin.
 */
data class AddonSpec(
    val id: String,
    val name: String,
    val manifestUrl: String,
    val enabled: Boolean = true,
) {
    /** Everything but `/manifest.json`: the base every catalog/meta/stream path hangs off. */
    fun base(): String {
        // Use the same normalized public address the client validates before every request. A
        // legacy malformed entry therefore cannot turn a later `/catalog` interpolation into a
        // request to a different authority.
        val manifest = AddonClient.canonicalManifestUrl(manifestUrl) ?: return ""
        val uri = try { URI(manifest) } catch (_: Throwable) { return "" }
        val manifestPath = uri.rawPath.orEmpty().trimEnd('/')
        val basePath = if (manifestPath.endsWith("/manifest.json", true)) {
            manifestPath.dropLast("/manifest.json".length).trimEnd('/')
        } else manifestPath
        val host = uri.host?.let { if (it.contains(':')) "[$it]" else it } ?: return ""
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "${uri.scheme}://$host$port$basePath"
    }

    /**
     * A protocol route under this configured manifest. Query configuration remains attached to every
     * community request instead of being accidentally embedded between `manifest.json` and the
     * route path; path-based configuration keeps its existing layout unchanged.
     */
    fun endpoint(route: String): String {
        val manifest = AddonClient.canonicalManifestUrl(manifestUrl) ?: return ""
        val uri = try { URI(manifest) } catch (_: Throwable) { return "" }
        val manifestPath = uri.rawPath.orEmpty().trimEnd('/')
        val basePath = if (manifestPath.endsWith("/manifest.json", true)) {
            manifestPath.dropLast("/manifest.json".length).trimEnd('/')
        } else manifestPath.trimEnd('/')
        val path = (basePath + "/" + route.trimStart('/')).ifBlank { "/" }
        val host = uri.host?.let { if (it.contains(':')) "[$it]" else it } ?: return ""
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return "${uri.scheme}://$host$port$path$query"
    }
}

/**
 * What one add-on said about itself, projected through [AddonManifestDomain].  The legacy JSON
 * accessors stay here for LocalHub callers, while new standalone routes can use the typed request
 * planner.  No caller needs to interpolate a manifest-supplied route segment directly.
 */
class AddonManifest(val spec: AddonSpec, val body: JSONObject) {
    val domain: AddonManifestDomain = AddonManifestDomain.parse(body)

    /** Backward-compatible projection for existing LocalHub code. */
    val resources: Map<String, JSONArray> = LinkedHashMap<String, JSONArray>().apply {
        domain.resources.forEach { resource ->
            put(resource.name, JSONArray().apply { resource.types.forEach { put(it) } })
        }
        // The protocol treats catalog declarations as the capability for catalog/addon_catalog
        // routes. Older standalone code consults this map, so expose those derived capabilities
        // without requiring a redundant resources[] declaration in otherwise valid manifests.
        if (domain.catalogs.isNotEmpty()) putIfAbsent("catalog", JSONArray())
        if (domain.addonCatalogs.isNotEmpty()) putIfAbsent("addon_catalog", JSONArray())
    }

    /** Backward-compatible raw catalogue rows; route planning uses [catalogRequest] instead. */
    val catalogs: List<JSONObject> = domain.catalogs.mapNotNull { it.raw }

    /** Source-directory descriptors remain distinct from content catalogues. */
    val addonCatalogs: List<AddonCatalogSpec> get() = domain.addonCatalogs
    val configuration: AddonConfigurationHints get() = domain.configuration

    fun has(resource: String): Boolean = resources.keys.any { it.equals(resource.trim(), ignoreCase = true) }

    /** The types this addon answers for one resource, or empty when it does not restrict them. */
    fun types(resource: String): List<String> {
        val declared = domain.types(resource)
        if (declared.isNotEmpty()) return declared
        return when {
            resource.equals("catalog", ignoreCase = true) -> domain.catalogs.map { it.type }.distinct()
            resource.equals("addon_catalog", ignoreCase = true) -> domain.addonCatalogs.map { it.type }.distinct()
            else -> emptyList()
        }
    }

    /** Catalogs that answer a text search, which is what the search screen needs. */
    fun searchableCatalogs(): List<JSONObject> = domain.searchableCatalogs().mapNotNull { it.raw }

    fun catalogRequest(type: String, catalogId: String, legacyExtra: String): AddonRequest? =
        domain.planLegacyCatalog(type, catalogId, legacyExtra)

    fun metaRequest(type: String, remoteId: String): AddonRequest? = domain.planMeta(type, remoteId)

    fun streamRequest(type: String, remoteId: String): AddonRequest? = domain.planStreams(type, remoteId)

    fun subtitleRequest(type: String, remoteId: String, language: String): AddonRequest? =
        domain.planSubtitles(type, remoteId, language)
}

/**
 * The addon protocol over plain HTTP, plus the on-device JSON cache (standalone mode
 * Rule 2: standalone "talks to addons directly, keeps its own cache and DB on device").
 *
 * Same shape as the hub's addon host, deliberately: `manifest`, `catalog`, `meta`, `stream`,
 * `subtitles`. Everything the home screen asks for is cache-first — a fresh-enough answer comes back
 * immediately and a refresh is kicked off in the background, so a slow addon costs a shorter shelf,
 * never a hung TV. Only a detail page or a Play press waits for a live answer.
 *
 * Cache lives under `filesDir`, so it survives a restart, costs nothing to clear, and never touches
 * another app's storage. One file per URL: `{ at, url, body }`.
 */
class AddonClient(context: Context) {

    private val dir = File(context.filesDir, "scenix/addon-cache").apply { mkdirs() }
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(2, ThreadFactory { r ->
        Thread(r, "scenix-addon").apply { isDaemon = true }
    })

    /** Fetch a manifest. `wait` = block this thread for the answer; otherwise cache-only. */
    fun manifest(spec: AddonSpec, wait: Boolean, ttlMs: Long): AddonManifest? {
        val o = json(spec.manifestUrl, wait, ttlMs) ?: return null
        return AddonManifest(spec, o)
    }

    fun catalog(am: AddonManifest, type: String, remoteCatalogId: String, extra: String, wait: Boolean, ttlMs: Long): JSONArray? {
        val request = am.catalogRequest(type, remoteCatalogId, extra) ?: return null
        val url = am.spec.endpoint(request.relativePath)
        val o = json(url, wait, ttlMs) ?: return null
        return o.optJSONArray("metas")
    }

    fun meta(am: AddonManifest, type: String, remoteId: String, wait: Boolean, ttlMs: Long): JSONObject? {
        val request = am.metaRequest(type, remoteId) ?: return null
        val url = am.spec.endpoint(request.relativePath)
        return json(url, wait, ttlMs)?.optJSONObject("meta")
    }

    fun streams(am: AddonManifest, type: String, remoteId: String, wait: Boolean, ttlMs: Long): JSONArray? {
        val request = am.streamRequest(type, remoteId) ?: return null
        val url = am.spec.endpoint(request.relativePath)
        return json(url, wait, ttlMs)?.optJSONArray("streams")
    }

    fun subtitles(am: AddonManifest, type: String, remoteId: String, lang: String, wait: Boolean, ttlMs: Long): JSONArray? {
        val request = am.subtitleRequest(type, remoteId, lang) ?: return null
        val url = am.spec.endpoint(request.relativePath)
        return json(url, wait, ttlMs)?.optJSONArray("subtitles")
    }

    // ---- cache + fetch ---------------------------------------------------------------------

    private fun fileFor(url: String): File = File(dir, sha1(url) + ".json")

    @Synchronized
    private fun readCache(url: String): Pair<Long, JSONObject?>? {
        val f = fileFor(url)
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            o.optLong("at", 0L) to o.optJSONObject("body")
        } catch (t: Throwable) {
            f.delete()
            null
        }
    }

    @Synchronized
    private fun writeCache(url: String, body: JSONObject) {
        try {
            val tmp = File(dir, sha1(url) + ".tmp")
            tmp.writeText(JSONObject().put("at", System.currentTimeMillis()).put("url", url).put("body", body).toString())
            val target = fileFor(url)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        } catch (t: Throwable) {
            // A configured manifest path may contain source-specific configuration; it is never a
            // useful diagnostic to print it to a shared device log.
            Log.w(TAG, "add-on cache write failed", t)
        }
    }

    /**
     * The whole caching rule in one place: a fresh entry answers at once; a stale one answers and
     * refreshes behind you; nothing cached answers only when the caller is willing to wait.
     */
    private fun json(url: String, wait: Boolean, ttlMs: Long): JSONObject? {
        // A persisted cache is not a trust exception. Validate before reading it so an old or
        // hand-edited settings file cannot make the app reuse data for a loopback/LAN endpoint.
        val safeUrl = publicEndpoint(url)?.uri?.toString() ?: run {
            Log.w(TAG, "blocked non-public add-on endpoint")
            return null
        }
        val hit = readCache(safeUrl)
        if (hit != null) {
            val fresh = System.currentTimeMillis() - hit.first < ttlMs
            if (fresh || !wait) {
                if (!fresh) refresh(safeUrl)   // answer now, be right next time
                return hit.second
            }
        }
        if (!wait) { refresh(safeUrl); return null }
        val body = fetchBounded(safeUrl)
        if (body != null) writeCache(safeUrl, body)
        return body ?: hit?.second
    }

    /**
     * A manifest install may wait on a hostname the TV cannot resolve. `connectTimeout` covers a
     * reachable host that is slow, not a DNS resolver that never answers, and a setup request must not
     * hang the page behind it. Run the fetch on its own daemon thread and stop waiting after the same
     * budget a home shelf would spend.
     */
    private fun fetchBounded(url: String): JSONObject? {
        var result: JSONObject? = null
        val t = Thread({ result = fetch(url) }, "scenix-addon-fetch").apply { isDaemon = true }
        t.start()
        try {
            t.join(ADDON_WAIT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // DNS and old platform HTTP stacks do not always honour an interrupt immediately, but do
        // request one once the caller's budget is spent. The thread is daemonized, so it can never
        // keep the TV process alive after a failed upstream.
        if (t.isAlive) t.interrupt()
        return result
    }

    private fun refresh(url: String) {
        try {
            pool.execute { fetch(url)?.let { writeCache(url, it) } }
        } catch (t: Throwable) {
            Log.w(TAG, "add-on refresh rejected", t)
        }
    }

    /**
     * One deliberately small HTTP boundary for standalone add-ons. The resolved public address is
     * carried through to the actual socket connect: `HttpURLConnection` would resolve the hostname
     * a second time after our check, leaving a DNS-rebinding window. HTTPS keeps the hostname for
     * SNI and certificate verification while its TCP socket remains pinned to that checked address.
     */
    private fun fetch(rawUrl: String): JSONObject? {
        var endpoint = publicEndpoint(rawUrl) ?: return null
        val deadline = System.currentTimeMillis() + ADDON_WAIT_MS
        for (hop in 0..MAX_REDIRECTS) {
            val response = fetchPinned(endpoint, deadline) ?: return null
            if (response.status in 300..399) {
                if (hop >= MAX_REDIRECTS) {
                    Log.w(TAG, "add-on redirect limit reached")
                    return null
                }
                val next = try { response.headers["location"]?.let { endpoint.uri.resolve(it) } }
                    catch (_: Throwable) { null }
                endpoint = next?.let { publicEndpoint(it.toString()) } ?: run {
                    Log.w(TAG, "add-on redirect was not a public HTTP(S) endpoint")
                    return null
                }
                continue
            }
            if (response.status !in 200..299) {
                Log.w(TAG, "add-on returned HTTP ${response.status}")
                return null
            }
            return try {
                JSONObject(String(response.body, Charsets.UTF_8))
            } catch (_: Throwable) {
                Log.w(TAG, "add-on did not return JSON")
                null
            }
        }
        return null
    }

    /** Try the validated DNS answers in resolver order; none is ever re-resolved by a socket. */
    private fun fetchPinned(endpoint: PublicEndpoint, deadline: Long): AddonResponse? {
        for (address in endpoint.addresses) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                Log.w(TAG, "add-on request timed out")
                return null
            }
            val timeout = remaining.coerceIn(250L, CONNECT_TIMEOUT_MS.toLong()).toInt()
            val response = fetchPinnedAddress(endpoint.uri, address, timeout)
            if (response != null) return response
        }
        Log.w(TAG, "add-on request failed")
        return null
    }

    /**
     * Direct HTTP/1.1 GET over a checked address. Add-ons need only JSON GETs, so this narrow reader
     * is intentionally smaller than a browser: no cookies, proxies, auth retries, compression or
     * connection reuse can create a second network path around the checked endpoint.
     */
    private fun fetchPinnedAddress(uri: URI, address: InetAddress, timeout: Int): AddonResponse? {
        val port = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        val rawSocket = Socket()
        var transport: Socket = rawSocket
        try {
            rawSocket.connect(InetSocketAddress(address, port), timeout)
            rawSocket.soTimeout = timeout
            if (uri.scheme.equals("https", true)) {
                val host = uri.host ?: return null
                val tlsFactory = SSLSocketFactory.getDefault() as? SSLSocketFactory ?: return null
                val tlsSocket = (tlsFactory.createSocket(rawSocket, host, port, true) as? SSLSocket) ?: return null
                tlsSocket.soTimeout = timeout
                // API 26 is our floor. Explicit SNI avoids falling back to the numeric pinned address
                // on TLS providers that do not infer it from the factory's host argument.
                if (!isIpLiteral(host)) {
                    try {
                        val params = tlsSocket.sslParameters
                        params.serverNames = listOf(SNIHostName(host))
                        tlsSocket.sslParameters = params
                    } catch (_: Throwable) {
                        // The factory still received `host`; an older provider may set SNI there.
                    }
                }
                tlsSocket.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tlsSocket.session)) {
                    Log.w(TAG, "add-on TLS hostname verification failed")
                    return null
                }
                transport = tlsSocket
            }
            val target = (uri.rawPath?.ifBlank { "/" } ?: "/") +
                (uri.rawQuery?.let { "?$it" } ?: "")
            val request = buildString {
                append("GET ").append(target).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader(uri)).append("\r\n")
                append("Accept: application/json\r\n")
                append("Accept-Encoding: identity\r\n")
                append("User-Agent: Scenix/0.2 (Android TV)\r\n")
                append("Connection: close\r\n\r\n")
            }
            transport.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            return readHttpResponse(BufferedInputStream(transport.getInputStream()))
        } catch (_: Throwable) {
            return null
        } finally {
            try { transport.close() } catch (_: Throwable) { }
            if (transport !== rawSocket) try { rawSocket.close() } catch (_: Throwable) { }
        }
    }

    private data class AddonResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    /** Parse one HTTP/1.1 response with independently bounded headers, chunks and body. */
    private fun readHttpResponse(input: InputStream): AddonResponse? {
        val head = readHeaderBlock(input) ?: return null
        val lines = String(head, Charsets.ISO_8859_1).split("\r\n")
        val status = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})(?:\\s|$)").find(lines.firstOrNull().orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[key] = if (headers[key].isNullOrBlank()) value else headers[key] + "," + value
        }
        // A redirect/error body is never part of the add-on protocol. Neither are HTTP's
        // explicitly body-less 204/205 success responses. Do not wait for a server to close an
        // unframed keep-alive response before following/rejecting its status.
        if (status !in 200..299 || status == 204 || status == 205) {
            return AddonResponse(status, headers, ByteArray(0))
        }
        val encoding = headers["content-encoding"]?.trim()?.lowercase().orEmpty()
        if (encoding.isNotBlank() && encoding != "identity") return null
        val declared = headers["content-length"]?.toLongOrNull()
        if (declared != null && (declared < 0L || declared > MAX_RESPONSE_BYTES)) return null
        val transfer = headers["transfer-encoding"].orEmpty().lowercase()
        val body = when {
            transfer.split(',').any { it.trim() == "chunked" } -> readChunked(input)
            declared != null -> readExactly(input, declared)
            else -> readToEnd(input)
        } ?: return null
        return AddonResponse(status, headers, body)
    }

    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val marker = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var matched = 0
        while (output.size() < MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) return null
            output.write(next)
            if (next == (marker[matched].toInt() and 0xff)) {
                matched++
                if (matched == marker.size) return output.toByteArray()
            } else {
                matched = if (next == '\r'.code) 1 else 0
            }
        }
        return null
    }

    private fun readChunked(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        while (true) {
            val line = readAsciiLine(input) ?: return null
            val chunkBytes = line.substringBefore(';').trim().toLongOrNull(16) ?: return null
            if (chunkBytes < 0L || output.size().toLong() + chunkBytes > MAX_RESPONSE_BYTES) return null
            if (chunkBytes == 0L) {
                // Consume trailer headers, still under the header ceiling, before returning the body.
                var trailerBytes = 0
                while (true) {
                    val trailer = readAsciiLine(input) ?: return null
                    trailerBytes += trailer.length
                    if (trailerBytes > MAX_HEADER_BYTES) return null
                    if (trailer.isBlank()) return output.toByteArray()
                }
            }
            val chunk = readExactly(input, chunkBytes) ?: return null
            output.write(chunk)
            if (input.read() != '\r'.code || input.read() != '\n'.code) return null
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        while (output.size() < MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) {
                val raw = output.toByteArray()
                val length = if (raw.lastOrNull() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, length, Charsets.ISO_8859_1)
            }
            output.write(next)
        }
        return null
    }

    private fun readExactly(input: InputStream, size: Long): ByteArray? {
        if (size < 0L || size > MAX_RESPONSE_BYTES) return null
        val output = ByteArrayOutputStream(size.toInt())
        val buffer = ByteArray(8 * 1024)
        var remaining = size
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return null
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
        return output.toByteArray()
    }

    private fun readToEnd(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size().toLong() + read > MAX_RESPONSE_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun hostHeader(uri: URI): String {
        val host = uri.host.orEmpty()
        val text = if (host.contains(':')) "[$host]" else host
        val defaultPort = if (uri.scheme.equals("https", true)) 443 else 80
        return if (uri.port >= 0 && uri.port != defaultPort) "$text:${uri.port}" else text
    }

    /** Drop every cached answer — what "clear cache" in Settings does — and say how many went. */
    fun clearCache(): Int {
        var cleared = 0
        try { dir.listFiles()?.forEach { if (it.delete()) cleared++ } } catch (t: Throwable) { Log.w(TAG, "cache clear failed", t) }
        return cleared
    }

    private fun sha1(s: String): String = try {
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        Integer.toHexString(s.hashCode())
    }

    companion object {
        private const val TAG = "ScenixAddon"
        /** Freshness rule for the home path: an answer younger than this is used as-is. */
        const val TTL_MS = 6L * 60L * 60L * 1000L
        private const val ADDON_WAIT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val MAX_REDIRECTS = 3
        private const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
        private const val MAX_HEADER_BYTES = 16 * 1024

        /** A URI plus the exact public DNS answers this request is allowed to connect to. */
        private data class PublicEndpoint(val uri: URI, val addresses: List<InetAddress>)

        /**
         * Canonical manifest address for persisted standalone configuration. This is intentionally
         * syntactic: resolving DNS belongs on the background fetch path, never the Settings UI
         * thread. [publicEndpoint] performs the network-side check before any cache or socket use.
         */
        fun canonicalManifestUrl(raw: String): String? {
            val uri = canonicalHttpUri(raw) ?: return null
            val trimmedPath = uri.rawPath.orEmpty().trimEnd('/')
            val path = when {
                trimmedPath.isBlank() -> "/manifest.json"
                trimmedPath.endsWith("/manifest.json", ignoreCase = true) -> trimmedPath
                else -> "$trimmedPath/manifest.json"
            }
            return rebuildUri(uri.scheme, uri.host, uri.port, path, uri.rawQuery)?.toString()
        }

        /** A configuration candidate that currently resolves only to public addresses. */
        fun publicManifestUrl(raw: String): String? {
            val normalized = canonicalManifestUrl(raw) ?: return null
            return publicEndpoint(normalized)?.uri?.toString()
        }

        /** A request endpoint must be HTTP(S), credential-free, and connected only to checked IPs. */
        private fun publicEndpoint(raw: String): PublicEndpoint? {
            val uri = canonicalHttpUri(raw) ?: return null
            val addresses = resolvePublicAddresses(uri) ?: return null
            return PublicEndpoint(uri, addresses)
        }

        /** Normalize without following input fragments or allowing opaque/credential-bearing URLs. */
        private fun canonicalHttpUri(raw: String): URI? {
            val parsed = try { URI(raw.trim()) } catch (_: Throwable) { return null }
            val scheme = parsed.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (parsed.isOpaque || parsed.userInfo != null || parsed.port !in -1..65535) return null
            val host = parsed.host?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return null
            if (host.isBlank() || host.contains('%')) return null
            val path = parsed.rawPath?.ifBlank { "/" } ?: "/"
            if (!path.startsWith('/')) return null
            return rebuildUri(scheme, host, parsed.port, path, parsed.rawQuery)
        }

        /** Rebuild from already parsed components so a fragment never becomes a cache/request key. */
        private fun rebuildUri(scheme: String?, host: String?, port: Int, path: String, query: String?): URI? {
            val safeScheme = scheme?.lowercase() ?: return null
            val safeHost = host?.trim()?.removePrefix("[")?.removeSuffix("]") ?: return null
            if (safeHost.isBlank()) return null
            val authorityHost = if (safeHost.contains(':')) "[$safeHost]" else safeHost
            val authorityPort = if (port >= 0) ":$port" else ""
            val suffix = query?.let { "?$it" } ?: ""
            return try {
                URI("$safeScheme://$authorityHost$authorityPort$path$suffix")
            } catch (_: Throwable) {
                null
            }
        }

        /** Reject loopback, LAN, link-local, documentation, multicast and DNS answers mixed with them. */
        private fun resolvePublicAddresses(uri: URI): List<InetAddress>? {
            val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]")
                ?.trimEnd('.')?.lowercase().orEmpty()
            if (host.isBlank() || host == "localhost" ||
                host.endsWith(".localhost") || host.endsWith(".local") ||
                host.endsWith(".internal") || host.endsWith(".home") || host.endsWith(".lan")
            ) return null
            val answers = try { InetAddress.getAllByName(host) } catch (_: Throwable) { return null }
            return answers.takeIf { it.isNotEmpty() && it.all(::isPublicAddress) }?.toList()
        }

        private fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return false
            val bytes = address.address
            return when (bytes.size) {
                4 -> isPublicIpv4(bytes)
                16 -> isPublicIpv6(bytes)
                else -> false
            }
        }

        private fun isIpLiteral(host: String): Boolean = host.contains(':') ||
            Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$").matches(host)

        private fun isPublicIpv4(bytes: ByteArray): Boolean {
            if (bytes.size != 4) return false
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            // 0/8, private ranges, loopback, link-local, CGNAT, documentation/testing and
            // multicast/reserved ranges never describe an upstream add-on.
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false
            if (a == 100 && b in 64..127) return false
            if (a == 169 && b == 254) return false
            if (a == 172 && b in 16..31) return false
            if (a == 192 && (b == 0 || b == 2 || b == 168)) return false
            if (a == 198 && (b == 18 || b == 19 || b == 51)) return false
            if (a == 203 && b == 0) return false
            return true
        }

        private fun isPublicIpv6(bytes: ByteArray): Boolean {
            if (bytes.size != 16) return false
            val groups = IntArray(8) { i ->
                ((bytes[i * 2].toInt() and 0xff) shl 8) or (bytes[i * 2 + 1].toInt() and 0xff)
            }
            if (groups.all { it == 0 }) return false // ::
            if (groups.take(7).all { it == 0 } && groups[7] == 1) return false // ::1
            val first = groups[0]
            if ((first and 0xfe00) == 0xfc00) return false // fc00::/7 unique local
            if ((first and 0xffc0) == 0xfe80 || (first and 0xffc0) == 0xfec0) return false
            if ((first and 0xff00) == 0xff00) return false // multicast
            if (first == 0x2001 && (groups[1] == 0 || groups[1] == 0x0002 || groups[1] == 0x0db8)) return false
            if (first == 0x2002) return false // 6to4 can conceal a private v4 destination

            val compatible = groups.take(6).all { it == 0 }
            val mapped = groups.take(5).all { it == 0 } && groups[5] == 0xffff
            val translated = groups.take(4).all { it == 0 } && groups[4] == 0xffff && groups[5] == 0
            if (compatible || mapped || translated) return isPublicIpv4(bytes.copyOfRange(12, 16))

            // The well-known NAT64 prefix encodes a v4 destination in the final 32 bits.
            if (first == 0x0064 && groups[1] == 0xff9b && groups.slice(2..5).all { it == 0 }) {
                return isPublicIpv4(bytes.copyOfRange(12, 16))
            }
            return true
        }
    }
}
