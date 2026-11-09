package com.scenix.tv

import android.app.Application
import android.net.http.HttpResponseCache
import android.util.Log
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.Theme
import java.io.File

/**
 * Builds the app context exactly once: theme and prefs. The interface itself is the hub's web UI;
 * this is only what the native launcher needs before it can open the shell.
 */
class ScenixApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var theme: Theme
        private set
    lateinit var ctx: AppContext
        private set

    override fun onCreate() {
        super.onCreate()
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        installImageCache()

        prefs = Prefs(this)
        // Packs come and go between builds. A TV still holding a retired theme id lands on the
        // default look at boot instead of wandering through fallbacks with a stale label.
        // Include validated local reskins here. Checking assets alone discarded a selected local
        // theme on the next process start even though its safe file was still present.
        if (Theme.catalog(this, assets).none { it.id == prefs.theme() }) prefs.setTheme(Theme.DEFAULT_ID)
        theme = Theme.load(this, assets, w, h, Theme.assetFor(this, assets, prefs.theme()), prefs.uiZoom())
        ctx = AppContext(this, theme, prefs)
    }

    /**
     * A disk cache for the artwork. Both CDNs Scenix uses send year-long `Cache-Control` headers, so
     * the HTTP layer can keep every poster/backdrop/still the TV has already seen. Without this,
     * opening Home after a restart — or scrolling back up a shelf — re-downloaded the same 30–90 KB
     * pictures through two workers, which is exactly the second of grey tiles the viewer saw after
     * a fast scroll. The in-memory LRU in [BrowseImages] still serves the current screen; this is
     * what makes the *next* screen, and the next launch, arrive from storage instead of the network.
     */
    private fun installImageCache() {
        try {
            if (HttpResponseCache.getInstalled() != null) return
            val size = 96L * 1024 * 1024
            HttpResponseCache.install(File(cacheDir, "images"), size)
            Log.i(TAG, "image disk cache installed (${size / (1024 * 1024)} MB)")
        } catch (t: Throwable) {
            // A TV with no room left is still a TV: artwork stays memory-only rather than crashing.
            Log.w(TAG, "image disk cache unavailable", t)
        }
    }

    /** Reload the chosen theme in place; screens built by the new activities then read it. */
    fun reloadTheme() {
        val metrics = resources.displayMetrics
        val next = Theme.load(this, assets, metrics.widthPixels, metrics.heightPixels,
            Theme.assetFor(this, assets, prefs.theme()), prefs.uiZoom())
        theme = next
        ctx.theme = next
    }

    private companion object {
        const val TAG = "ScenixApp"
    }
}
