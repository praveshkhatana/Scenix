package com.scenix.tv.core

import android.content.Context
import android.util.Log
import org.libtorrent4j.FileStorage
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Standalone mode's torrent engine.
 *
 * It is a real libtorrent session inside the app, not a link to the hub. The hub remains the
 * preferred shape — one engine serves the whole house and a 2 GB television is a poor place to hash
 * pieces — but a TV on its own can now play a magnet instead of showing a dead source.
 *
 * The engine downloads a torrent sequentially into the app's cache and this object serves the chosen
 * file over the app's loopback HTTP server. The player therefore sees a normal, seekable file with
 * Range support; it never learns there is a swarm underneath. A read waits only for the pieces that
 * cover that byte range, so a seek near the end does not require the whole film first.
 *
 * Every libtorrent call can fail on a build that did not carry this ABI. Nothing here throws into the
 * page: [available] and a null return are the honest answers, and the direct-source path is untouched.
 */
object TorrentEngine {

    private const val TAG = "ScenixTorrent"
    private const val VIDEO = "mkv|mp4|avi|ts|m2ts|mov|webm|mpg|mpeg"
    private const val CHUNK_WAIT_MS = 45_000L
    private const val METADATA_WAIT_MS = 20_000L
    private const val MIN_FREE_BYTES = 400L * 1024 * 1024

    data class Prepared(val hash: String, val fileId: Int, val size: Long, val name: String)
    data class Opened(
        val prepared: Prepared,
        val handle: TorrentHandle,
        val info: TorrentInfo,
        val file: File,
    )

    private val opened = ConcurrentHashMap<String, Opened>()
    private var session: SessionManager? = null
    private var failure: String? = null
    private var saveDir: File? = null

    @Synchronized
    fun init(context: Context) {
        if (session != null || failure != null) return
        saveDir = File(context.cacheDir, "torrents").apply { mkdirs() }
        try {
            val s = SessionManager()
            s.start(SessionParams())
            s.startDht()
            session = s
            Log.i(TAG, "libtorrent session started, save=${saveDir?.absolutePath}")
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "libtorrent unavailable in this build", t)
        }
    }

    @Synchronized
    fun available(): Boolean = session != null || failure == null

    /** Bytes this app may use for a torrent, before the cushion reserved for the rest of the TV. */
    fun freeBytes(context: Context): Long = try {
        android.os.StatFs(File(context.cacheDir, "torrents").apply { mkdirs() }.absolutePath).availableBytes
    } catch (t: Throwable) {
        Long.MAX_VALUE
    }

    fun status(): Map<String, Any?> = mapOf(
        "available" to available(),
        "opened" to opened.size,
        "failure" to failure,
        "save" to saveDir?.absolutePath,
    )

    /**
     * Resolve a magnet (or an info-hash-only entry) to one file. Blocks up to [timeoutMs] for metadata
     * only; bytes are fetched later, when the player actually reads them.
     */
    fun prepare(context: Context, uri: String, season: Int, episode: Int, timeoutMs: Long = METADATA_WAIT_MS): Prepared? {
        init(context)
        val s = session ?: return null
        val hash = hashOf(uri) ?: return null
        opened["$hash:meta"]?.let { return it.prepared }

        val dir = saveDir ?: return null
        val free = try { android.os.StatFs(dir.absolutePath).availableBytes } catch (t: Throwable) { Long.MAX_VALUE }
        if (free < MIN_FREE_BYTES) {
            Log.w(TAG, "not enough free space for a torrent ($free bytes)")
            return null
        }

        var handle: TorrentHandle? = null
        try {
            synchronized(this) {
                if (opened["$hash:meta"] == null) {
                    s.download(uri, dir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not add magnet", t)
            return null
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            handle = s.find(Sha1Hash.parseHex(hash))
            val h = handle
            if (h != null && h.isValid) {
                val st = h.status()
                if (st.hasMetadata()) break
            }
            if (failure != null) return null
            try { Thread.sleep(120) } catch (e: InterruptedException) { return null }
        }
        val h = handle
        if (h == null || !h.isValid || !h.status().hasMetadata()) {
            Log.w(TAG, "metadata did not arrive for ${hash.take(10)}")
            return null
        }

        return try {
            val info = h.torrentFile() ?: return null
            val files = info.files()
            val pick = chooseFile(files, season, episode) ?: return null
            val name = files.fileName(pick)
            val size = files.fileSize(pick)
            if (size <= 0) return null
            if (size > free - MIN_FREE_BYTES) {
                Log.w(TAG, "torrent file $name is larger than free space")
                return null
            }

            // Download only the chosen file. A season pack should not fetch seven other episodes
            // before the one the viewer pressed.
            for (i in 0 until files.numFiles()) {
                h.filePriority(i, if (i == pick) Priority.DEFAULT else Priority.IGNORE)
            }
            val firstPiece = info.mapFile(pick, 0, 1).piece()
            val lastPiece = info.mapFile(pick, size - 1, 1).piece()
            h.setSequentialRange(firstPiece, lastPiece)

            val file = File(files.filePath(pick, dir.absolutePath))
            val prepared = Prepared(hash, pick, size, name)
            opened["$hash:$pick"] = Opened(prepared, h, info, file)
            opened["$hash:meta"] = Opened(prepared, h, info, file)
            Log.i(TAG, "prepared $name ($size bytes) from ${hash.take(10)}")
            prepared
        } catch (t: Throwable) {
            Log.w(TAG, "could not choose a file in this torrent", t)
            null
        }
    }

    /** A prepared file by hash and file index, or null when it was never prepared by this process. */
    fun find(hash: String, fileId: Int): Opened? = opened["$hash:$fileId"]

    /**
     * Block until the pieces covering [offset, offset+length) are present, then read exactly that
     * range. Null means the bytes did not arrive in time; the caller closes the stream rather than
     * serving zeros, which the decoder would report as a corrupt file.
     */
    fun read(target: Opened, offset: Long, length: Int): ByteArray? {
        if (length <= 0) return ByteArray(0)
        val want = length.coerceAtMost((target.prepared.size - offset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (want <= 0) return ByteArray(0)
        val deadline = System.currentTimeMillis() + CHUNK_WAIT_MS
        var firstPiece = 0
        var lastPiece = 0
        try {
            firstPiece = target.info.mapFile(target.prepared.fileId, offset, want).piece()
            lastPiece = target.info.mapFile(target.prepared.fileId, offset + want - 1, 1).piece()
            // A seek is a new sequential window. Without this, libtorrent keeps filling from the old
            // playhead while the player waits at the new one.
            target.handle.setSequentialRange(firstPiece, lastPiece)
        } catch (t: Throwable) {
            return null
        }
        while (System.currentTimeMillis() < deadline) {
            var missing = false
            for (p in firstPiece..lastPiece) {
                if (!target.handle.havePiece(p)) {
                    missing = true
                    try { target.handle.piecePriority(p, Priority.TOP_PRIORITY) } catch (t: Throwable) { }
                    try { target.handle.setPieceDeadline(p, 1) } catch (t: Throwable) { }
                }
            }
            if (!missing) break
            try { Thread.sleep(80) } catch (e: InterruptedException) { return null }
        }
        return try {
            if (!target.file.isFile) return null
            RandomAccessFile(target.file, "r").use { f ->
                f.seek(offset)
                val buf = ByteArray(want)
                f.readFully(buf)
                buf
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read failed at $offset", t)
            null
        }
    }

    private fun chooseFile(files: FileStorage, season: Int, episode: Int): Int? {
        val video = Regex("\\.($VIDEO)$", RegexOption.IGNORE_CASE)
        var best = -1
        var bestSize = 0L
        var exact = -1
        var exactSize = 0L
        for (i in 0 until files.numFiles()) {
            val size = files.fileSize(i)
            if (size <= 10L * 1024 * 1024) continue
            val name = files.fileName(i)
            if (!video.containsMatchIn(name)) continue
            if (season > 0 && episode > 0 && matchesEpisode(name, season, episode) && size > exactSize) {
                exact = i
                exactSize = size
            }
            if (size > bestSize) {
                best = i
                bestSize = size
            }
        }
        return if (exact >= 0) exact else best.takeIf { it >= 0 }
    }

    private fun matchesEpisode(name: String, season: Int, episode: Int): Boolean {
        val n = name.lowercase()
        return Regex("s0?${season}[ ._-]*e0?${episode}").containsMatchIn(n) ||
            Regex("${season}x0?${episode}").containsMatchIn(n)
    }

    fun hashOf(uri: String): String? {
        val m = Regex("btih:([a-fA-F0-9]{40})", RegexOption.IGNORE_CASE).find(uri)
        if (m != null) return m.groupValues[1].lowercase()
        if (Regex("^[a-fA-F0-9]{40}$").matches(uri.trim())) return uri.trim().lowercase()
        return null
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }
}
