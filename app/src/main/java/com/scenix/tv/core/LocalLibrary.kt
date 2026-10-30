package com.scenix.tv.core

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

/**
 * The files on this TV, indexed through the Storage Access Framework.
 *
 * A hub scans a directory on its own disk (`providers/local-files.js`). A television does not have a
 * directory it may read: since Android 11 a broad-storage permission is the wrong answer, and a
 * `file://` path another app handed over is not portable across providers. The owner therefore picks
 * a folder in the system picker once; this app keeps that grant, walks the tree, and remembers the
 * files. Nothing outside the picked tree is readable, and no permission beyond that grant is asked for.
 *
 * The index is a cache, not the truth: a rescan is cheap and runs off the request thread, so a folder
 * that gained a file shows it without a reboot. If the grant is revoked, the capability disappears
 * rather than a shelf of tiles that all fail.
 */
class LocalLibrary(private val context: Context, private val indexFile: File) {

    data class Entry(
        val id: String,
        val name: String,
        val path: String,
        val uri: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
        val quality: String,
    )

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()

    private var entries: List<Entry> = emptyList()
    private var tree: String = ""
    private var scannedAt: Long = 0L
    private var lastError: String = ""

    @Volatile
    private var scanning = false

    init {
        load()
    }

    // ---- the owner's folder ---------------------------------------------------------------------

    /** True when the picked folder is still readable by this app after a reboot or a restore. */
    fun hasAccess(treeUri: String): Boolean {
        if (treeUri.isBlank()) return false
        if (treeUri == MEDIASTORE) return mediaPermission()
        return try {
            val uri = Uri.parse(treeUri)
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * The files under the picked folder, from the cache when it is fresh. A stale or changed tree is
     * rescanned in the background, so the caller never waits on a folder with a thousand entries.
     */
    fun entries(treeUri: String): List<Entry> {
        if (treeUri.isBlank()) return emptyList()
        synchronized(lock) {
            if (treeUri != tree) {
                entries = emptyList()
                tree = treeUri
                scannedAt = 0L
            }
        }
        if (entries.isEmpty() || System.currentTimeMillis() - scannedAt > RESCAN_MS) scanAsync(treeUri)
        return synchronized(lock) { entries }
    }

    fun find(treeUri: String, id: String): Entry? = entries(treeUri).firstOrNull { it.id == id }

    /**
     * A seekable read of one indexed file. The provider's descriptor is kept beside the stream: closing
     * only the stream can leave the descriptor owned by this process, and a channel that cannot seek is
     * useless to a player.
     */
    class Stream(val entry: Entry, val input: FileInputStream, private val descriptor: ParcelFileDescriptor) : java.io.Closeable {
        val size: Long get() = if (entry.sizeBytes > 0) entry.sizeBytes else descriptor.statSize
        override fun close() {
            try { input.close() } catch (t: Throwable) { /* already gone */ }
            try { descriptor.close() } catch (t: Throwable) { /* already gone */ }
        }
    }

    fun open(entry: Entry): Stream? {
        val pfd = try {
            context.contentResolver.openFileDescriptor(Uri.parse(entry.uri), "r") ?: return null
        } catch (t: Throwable) {
            Log.w(TAG, "could not open " + entry.path, t)
            return null
        }
        return try {
            Stream(entry, FileInputStream(pfd.fileDescriptor), pfd)
        } catch (t: Throwable) {
            try { pfd.close() } catch (e: Throwable) { /* gone */ }
            Log.w(TAG, "could not read " + entry.path, t)
            null
        }
    }

    /** Kick a scan without waiting; the settings screen calls this after the owner picks a folder. */
    fun refresh(treeUri: String) {
        if (treeUri.isBlank()) return
        synchronized(lock) {
            if (treeUri != tree) {
                entries = emptyList()
                tree = treeUri
                scannedAt = 0L
            }
        }
        scanAsync(treeUri)
    }

    fun status(treeUri: String): JSONObject = synchronized(lock) {
        JSONObject()
            .put("tree", treeUri.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("granted", hasAccess(treeUri))
            .put("files", entries.size)
            .put("scannedAt", if (scannedAt > 0) scannedAt else JSONObject.NULL)
            .put("scanning", scanning)
            .put("error", lastError.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
    }

    // ---- scanning -------------------------------------------------------------------------------

    /**
     * The permissions this Android version asks for to read the TV's shared video and audio. Video is
     * first: a viewer who allows only one collection can still see the shelf, and the audio query is
     * skipped rather than allowed to blank it.
     */
    fun mediaPermissionNames(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun mediaPermissionName(): String = mediaPermissionNames().first()

    fun mediaPermission(): Boolean =
        context.checkSelfPermission(mediaPermissionName()) == PackageManager.PERMISSION_GRANTED

    private fun scanAsync(treeUri: String) {
        if (!hasAccess(treeUri)) {
            synchronized(lock) {
                if (treeUri == tree && entries.isNotEmpty()) entries = emptyList()
                lastError = "the folder grant was lost; pick it again"
            }
            return
        }
        if (!scanning) {
            synchronized(lock) {
                if (scanning) return
                scanning = true
            }
            executor.execute {
                try {
                    val found = if (treeUri == MEDIASTORE) walkMediaStore() else walk(treeUri)
                    synchronized(lock) {
                        entries = found
                        tree = treeUri
                        scannedAt = System.currentTimeMillis()
                        lastError = ""
                    }
                    save()
                } catch (t: Throwable) {
                    synchronized(lock) { lastError = t.message ?: "the folder could not be read" }
                    Log.w(TAG, "local scan failed", t)
                } finally {
                    scanning = false
                }
            }
        }
    }

    private fun walk(treeUri: String): List<Entry> {
        val uri = Uri.parse(treeUri)
        val rootId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (t: Throwable) {
            return emptyList()
        }
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        fun visit(documentId: String, relative: String, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_FILES) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        visit(id, relative + name + "/", depth + 1)
                        continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in EXTENSIONS || !seen.add(id)) continue
                    val doc = DocumentsContract.buildDocumentUriUsingTree(uri, id)
                    out.add(
                        Entry(
                            id = liveId(doc.toString(), relative + name),
                            name = name,
                            path = relative + name,
                            uri = doc.toString(),
                            sizeBytes = c.getLong(3).coerceAtLeast(0L),
                            modifiedAt = c.getLong(4).coerceAtLeast(0L),
                            quality = qualityOf(name),
                        ),
                    )
                }
            }
        }
        visit(rootId, "", 0)
        return out.sortedBy { it.path.lowercase() }
    }

    /** A stable id: the same document keeps the same identity across rescans and restarts. */
    private fun liveId(uri: String, path: String): String {
        val slug = path.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "file" }
        return "local:$slug-" + Integer.toHexString(uri.hashCode())
    }

    /**
     * The fallback for a television whose build has no folder picker (many Android TV images ship
     * without DocumentsUI). MediaStore is the platform's own index of shared video and audio, and it
     * needs one runtime permission rather than a tree grant. Files a media scanner has not seen do not
     * appear here; that is the honest trade for being able to open anything at all on this panel.
     */
    private fun walkMediaStore(): List<Entry> {
        val out = ArrayList<Entry>()
        val cols = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        // A downloader publishes a file through MediaStore with IS_PENDING=1 while it is still being
        // written. That row is not playable yet: its length keeps changing and the provider may refuse
        // an open. Skipping it means the shelf fills with the file one scan after it is complete
        // instead of offering a tile that fails on the first press. Trashed rows are skipped for the
        // same reason: the platform has already marked them as going away.
        val selection = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                MediaStore.MediaColumns.IS_PENDING + "=0 AND " + MediaStore.MediaColumns.IS_TRASHED + "=0"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                MediaStore.MediaColumns.IS_PENDING + "=0"
            else -> null
        }
        fun read(base: Uri) {
            if (out.size >= MAX_FILES) return
            try {
                context.contentResolver.query(
                    base, cols, selection, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC",
                )?.use { c ->
                    while (c.moveToNext() && out.size < MAX_FILES) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext !in EXTENSIONS) continue
                        val uri = ContentUris.withAppendedId(base, id)
                        out.add(
                            Entry(
                                id = liveId(uri.toString(), name),
                                name = name,
                                path = name,
                                uri = uri.toString(),
                                sizeBytes = c.getLong(2).coerceAtLeast(0L),
                                modifiedAt = c.getLong(3).coerceAtLeast(0L) * 1000L,
                                quality = qualityOf(name),
                            ),
                        )
                    }
                }
            } catch (t: SecurityException) {
                // READ_MEDIA_VIDEO does not cover the audio collection on Android 13+. Videos are
                // still usable, so one refused collection must not blank the whole shelf.
                Log.w(TAG, "media query refused for " + base, t)
            }
        }
        read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        read(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        return out.sortedBy { it.name.lowercase() }
    }

    private fun qualityOf(name: String): String {
        val m = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE).find(name)
        if (m != null) return m.groupValues[1].lowercase() + "p"
        if (Regex("\\b4k\\b|\\buhd\\b|\\b2160\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) return "2160p"
        return ""
    }

    // ---- persistence ----------------------------------------------------------------------------

    private fun load() {
        try {
            if (!indexFile.exists()) return
            val root = JSONObject(indexFile.readText())
            tree = root.optString("tree", "")
            scannedAt = root.optLong("scannedAt", 0L)
            val arr = root.optJSONArray("files") ?: return
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Entry(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        path = o.optString("path"),
                        uri = o.optString("uri"),
                        sizeBytes = o.optLong("sizeBytes", 0L),
                        modifiedAt = o.optLong("modifiedAt", 0L),
                        quality = o.optString("quality"),
                    ),
                )
            }
            entries = out.filter { it.id.isNotBlank() && it.uri.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "local index unreadable", t)
        }
    }

    private fun save() {
        try {
            val (files, at, where) = synchronized(lock) { Triple(entries, scannedAt, tree) }
            val arr = JSONArray()
            for (e in files) {
                arr.put(
                    JSONObject()
                        .put("id", e.id).put("name", e.name).put("path", e.path).put("uri", e.uri)
                        .put("sizeBytes", e.sizeBytes).put("modifiedAt", e.modifiedAt)
                        .put("quality", e.quality),
                )
            }
            val root = JSONObject().put("tree", where).put("scannedAt", at).put("files", arr)
            indexFile.parentFile?.mkdirs()
            val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "local index write failed", t)
        }
    }

    companion object {
        const val MEDIASTORE = "mediastore:"
        private const val TAG = "ScenixLocalFiles"
        private const val MAX_DEPTH = 4
        private const val MAX_FILES = 500
        // A download that just finished should appear while the viewer is still on the shelf. The
        // scan is a handful of MediaStore rows (or a tree walk), so a minute is cheap; ten minutes
        // looked like "the TV never found my file".
        private const val RESCAN_MS = 60_000L
        private val EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "m4v", "mov", "ts", "m2ts", "avi", "mp3", "m4a", "opus", "flac",
        )
    }
}
