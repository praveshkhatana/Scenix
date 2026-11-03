package com.scenix.tv.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Auto-update from the project's GitHub releases.
 *
 * The build pipeline is deliberately the boring one: every APK the household should get is
 * published as a GitHub Release on github.com/praveshkhatana/Scenix with the APK attached. The app
 * asks `releases/latest`, compares version codes, and offers to download and install. No custom
 * server, no channel JSON, nothing to maintain besides pressing "publish release".
 *
 * The version-code contract (the one rule the release publisher must follow):
 *   · the release carries an .apk asset; and
 *   · either the body contains `versionCode: N`, or the tag's last integer segment IS N —
 *     `v0.2-3` means versionCode 3. The app only offers a release whose N is above the installed
 *     BuildConfig version code, so a mislabelled older release can never downgrade a TV.
 *
 * GitHub's API demands a User-Agent and answers anonymous requests for a public repo, so nothing
 * here needs a token.
 */
object Updates {
    private const val TAG = "ScenixUpdate"
    const val REPO = "praveshkhatana/Scenix"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** What the newest published release amounts to for this device. */
    data class Release(
        val versionCode: Int,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
    )

    /** This install's version code — the number every comparison runs on. */
    @Suppress("DEPRECATION")
    fun currentVersionCode(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else info.versionCode
    } catch (e: Exception) { 0 }

    @Suppress("DEPRECATION")
    fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /**
     * Ask GitHub for the newest release. [done] gets the release only when it is NEWER than what
     * this TV runs; null means "you are up to date" and a non-null error means the network or
     * GitHub did not answer. The call never blocks the caller's thread.
     */
    fun check(context: Context, done: (release: Release?, error: String?) -> Unit) {
        pool.execute {
            try {
                val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 12000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub returns 403 to any request without a User-Agent.
                conn.setRequestProperty("User-Agent", "Scenix-TV")
                if (conn.responseCode !in 200..299) {
                    done(null, "GitHub answered ${conn.responseCode}")
                    return@execute
                }
                val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = root.optString("tag_name", "")
                val body = root.optString("body", "")
                val code = releaseVersionCode(tag, body)
                val assets = root.optJSONArray("assets") ?: org.json.JSONArray()
                var url = ""
                var size = 0L
                var fallback = ""
                var fallbackSize = 0L
                val wantsArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64") }
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "").lowercase()
                    val dl = a.optString("browser_download_url", "")
                    if (!name.endsWith(".apk") || dl.isBlank()) continue
                    // A multi-abi release names the abi in the file; prefer this device's. A
                    // universal APK (the normal case here) names no abi and is always acceptable.
                    val abiMatched = if (wantsArm64) name.contains("arm64") else name.contains("armeabi")
                    if (abiMatched && url.isBlank()) { url = dl; size = a.optLong("size", 0L) }
                    if (fallback.isBlank()) { fallback = dl; fallbackSize = a.optLong("size", 0L) }
                }
                if (url.isBlank()) { url = fallback; size = fallbackSize }
                if (url.isBlank()) {
                    done(null, "the newest release carries no APK")
                    return@execute
                }
                if (code <= currentVersionCode(context)) {
                    done(null, null) // up to date — not an error, nothing to offer
                    return@execute
                }
                done(
                    Release(
                        code,
                        root.optString("name", "").ifBlank { tag },
                        body,
                        url,
                        size,
                    ),
                    null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "update check failed: ${e.message}")
                done(null, e.message ?: "network")
            }
        }
    }

    /**
     * The published version code, per the contract in the header comment: an explicit
     * `versionCode: N` in the body wins; otherwise the tag's last integer segment is the code.
     */
    fun releaseVersionCode(tag: String, body: String): Int {
        Regex("""versionCode[:=\s]+(\d+)""", RegexOption.IGNORE_CASE).find(body)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        val parts = Regex("""\d+""").findAll(tag).mapNotNull { it.value.toIntOrNull() }.toList()
        return parts.lastOrNull() ?: 0
    }

    /**
     * Pull the APK into the app's own cache. [onProgress] gets (downloaded, total) for a progress
     * line; [done] receives the file ready for the installer. The file lands under cache/updates,
     * which the FileProvider in the manifest publishes — the package installer reads it from there.
     */
    fun download(context: Context, release: Release, onProgress: (Long, Long) -> Unit, done: (file: java.io.File?, error: String?) -> Unit) {
        pool.execute {
            try {
                val dir = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
                // Clear the debris of older attempts before pulling a new one.
                dir.listFiles()?.forEach { f -> if (f.name != TMP_NAME && f.name.contains(".apk")) f.delete() }
                val out = java.io.File(dir, TMP_NAME)
                val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) {
                    done(null, "download answered ${conn.responseCode}")
                    return@execute
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                conn.inputStream.use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            got += n
                            if (total > 0) onProgress(got, total)
                        }
                    }
                }
                if (out.length() < 1024 * 100) {
                    out.delete()
                    done(null, "the downloaded APK looks broken")
                    return@execute
                }
                val target = java.io.File(dir, "scenix-${release.versionCode}.apk")
                if (out.renameTo(target)) done(target, null) else done(out, null)
            } catch (e: Exception) {
                Log.w(TAG, "update download failed: ${e.message}")
                done(null, e.message ?: "download")
            }
        }
    }

    /**
     * Hand the downloaded APK to Android's package installer. The TV shows its own confirm screen —
     * updating stays an explicit act of the person holding the remote, never a silent swap.
     */
    fun install(context: Context, file: java.io.File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .setType("application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "install handoff failed: ${e.message}")
        false
    }

    /**
     * The one piece of UI in this file: a plain system dialog, the same confirmation language
     * Android itself uses for installs. Download then hand-off; the TV's installer finishes the job.
     * A second offer while one is in flight is ignored — the boot check and Settings can overlap.
     */
    fun offer(activity: android.app.Activity, release: Release) {
        if (inFlight) return
        inFlight = true
        activity.runOnUiThread {
            val sizeMb = if (release.apkSize > 0) " · ${(release.apkSize / (1024 * 1024)).toInt()} MB" else ""
            val notes = release.notes.trim().take(500)
            android.app.AlertDialog.Builder(activity)
                .setTitle("Update ${release.title.ifBlank { "build " + release.versionCode }}")
                .setMessage(
                    if (notes.isBlank()) "A newer Scenix build is published.$sizeMb Download and install it now?"
                    else "A newer Scenix build is published.$sizeMb\n\n$notes"
                )
                .setPositiveButton("Download & install") { _, _ -> downloadAndInstall(activity, release) }
                .setNegativeButton("Later") { d, _ -> d.cancel() }
                .setOnCancelListener { inFlight = false }
                .show()
        }
    }

    /** Download the release APK and open the installer. Progress stays quiet; the finish is loud. */
    fun downloadAndInstall(activity: android.app.Activity, release: Release) {
        android.widget.Toast.makeText(activity, "Downloading update…", android.widget.Toast.LENGTH_SHORT).show()
        download(activity, release, { _, _ -> }) { file, error ->
            activity.runOnUiThread {
                inFlight = false
                if (file == null) {
                    android.widget.Toast.makeText(
                        activity, "The update download failed (${error ?: "unknown"})",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    return@runOnUiThread
                }
                if (!install(activity, file)) {
                    android.widget.Toast.makeText(
                        activity, "Android would not open the installer on this TV",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scenix-update").apply { isDaemon = true }
    }

    @Volatile
    private var inFlight = false

    private const val TMP_NAME = "download.part.apk"
}
