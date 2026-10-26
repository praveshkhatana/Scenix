package com.scenix.tv.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** One hub this TV can talk to, and the name the owner gave it. The name is what a person reads. */
data class HubAddress(val url: String, val name: String) {
    /** What to show when the hub announced no name: its host, which is still recognisable. */
    fun display(): String = name.ifBlank { runCatching { java.net.URI(url).host }.getOrNull() ?: url }
}

/**
 * The client half of pairing (design/deployment-modes.md Rule 4): ask the local network who is a
 * Scenix hub, and list the answers by name.
 *
 * The hub answers an SSDP `M-SEARCH` for [SSDP_TARGET] over UDP 1900 with:
 *   LOCATION:  the address its `/app/v1` contract is served from — `http://<hub-host>:8788/`
 *   X-Scenix-Name: the name the owner typed when they installed the hub (optional)
 * `SERVER:` is used as a last resort so a hub that names itself some other way still shows up as
 * something other than an address.
 *
 * Two things make this work on a TV rather than only on a emulator: the multicast lock (Android
 * drops group traffic on Wi-Fi unless a client holds it) and a second copy of the search sent as a
 * limited broadcast, because some firmwares do not forward the group address to their own clients.
 *
 * Nothing here names a machine: the group address and port are protocol constants, and every
 * address in the answer came from the network, not from the build.
 */
object HubDiscovery {

    private const val TAG = "ScenixDiscover"

    /** The SSDP group address and port — protocol constants, not a household value. */
    private const val GROUP = "239.255.255.250"
    private const val PORT = 1900

    /** Header a hub uses to carry the owner's name for itself. Absent means "no name given". */
    private const val NAME_HEADER = "X-Scenix-Name"

    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())

    /** One search in flight at a time; a second call while scanning is dropped. */
    fun isScanning(): Boolean = running

    /**
     * Send the search, listen for [timeoutMs], answer on the main thread with what answered.
     * An empty list is an honest answer: no hub is announcing itself here.
     */
    fun scan(context: Context, target: String, timeoutMs: Int, onResult: (List<HubAddress>) -> Unit) {
        if (running) {
            onResult(emptyList())
            return
        }
        running = true
        val app = context.applicationContext
        Thread({
            val found = try { search(app, target, timeoutMs) } catch (t: Throwable) {
                Log.w(TAG, "LAN search failed", t); emptyList()
            }
            Log.i(TAG, "LAN search found ${found.size} hub(s)")
            main.post {
                running = false
                onResult(found)
            }
        }, "scenix-discover").apply { isDaemon = true }.start()
    }

    /** Blocking: the search, then the listen window. Runs off the main thread. */
    private fun search(app: Context, target: String, timeoutMs: Int): List<HubAddress> {
        val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("scenix-hub-search")
        lock?.setReferenceCounted(true)
        val out = LinkedHashMap<String, HubAddress>()
        var socket: DatagramSocket? = null
        try {
            lock?.acquire()
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 500
            }
            val group = InetAddress.getByName(GROUP)
            val body = request(target).toByteArray(Charsets.UTF_8)
            var sent = 0
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (sent < 2) {  // two copies, a second apart: one UDP packet on Wi-Fi is not a promise
                    socket.send(DatagramPacket(body, body.size, InetSocketAddress(group, PORT)))
                    socket.send(DatagramPacket(body, body.size, InetSocketAddress(InetAddress.getByName("255.255.255.255"), PORT)))
                    sent++
                }
                val buf = ByteArray(2048)
                val dp = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(dp)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                parse(String(dp.data, 0, dp.length, Charsets.UTF_8))?.let {
                    if (it.url.isNotEmpty()) out.putIfAbsent(it.url, it)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "search aborted: ${t.message}")
        } finally {
            socket?.close()
            try { lock?.release() } catch (t: Throwable) { /* already released */ }
        }
        return out.values.toList()
    }

    private fun request(target: String): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: ").append(GROUP).append(':').append(PORT).append("\r\n")
        append("MAN: \"ssdp:discoverable\"\r\n")
        append("MX: 1\r\n")
        append("ST: ").append(target).append("\r\n")
        append("USER-AGENT: Scenix/0.1 (Android TV)\r\n")
        append("\r\n")
    }

    /** Parse one reply. Any packet carrying a `LOCATION:` counts, so an unsolicited announce works too. */
    private fun parse(text: String): HubAddress? {
        var location: String? = null
        var name: String? = null
        var server: String? = null
        for (line in text.split("\r\n", "\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            if (value.isEmpty()) continue
            when (key) {
                "location" -> if (location == null) location = value
                "x-scenix-name" -> if (name == null) name = value
                "server" -> if (server == null) server = value
            }
        }
        val url = normalise(location ?: return null) ?: return null
        return HubAddress(url, (name ?: server ?: "").take(60))
    }

    /**
     * Accept `http://host:port/`, a bare `host:port`, and never anything but http(s). Public because a
     * typed address goes through the same door as an announced one: someone should be able to write
     * `host:8788` on the remote and get a hub.
     */
    fun normalise(raw: String?): String? {
        var u = raw?.trim()?.trimEnd('/') ?: return null
        if (u.isEmpty()) return null
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        val scheme = if (uri.scheme.equals("https", true)) "https" else "http"
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }
}
