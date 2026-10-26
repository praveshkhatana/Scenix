package com.scenix.tv

import android.app.Application
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.ImageLoader
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.Registries
import com.scenix.tv.core.Theme
import com.scenix.tv.plugins.PluginCatalog

/**
 * Builds the app context exactly once: theme, prefs, image cache, the four registries, and every
 * built-in plugin from [PluginCatalog]. Nothing else constructs any of these — plugins receive
 * [AppContext] and reach the rest through it, which is what keeps "add a file, add one line" true.
 */
class ScenixApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var theme: Theme
        private set
    lateinit var images: ImageLoader
        private set
    lateinit var registries: Registries
        private set
    lateinit var ctx: AppContext
        private set

    override fun onCreate() {
        super.onCreate()
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels

        prefs = Prefs(this)
        theme = Theme.load(assets, w, h)
        images = ImageLoader(this)
        registries = Registries()
        PluginCatalog.install(registries)
        ctx = AppContext(this, theme, prefs, images, registries, w, h)
    }

    /** 2 GB of RAM and a TV that keeps this app open for days: drop bitmaps before Android does. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) images.trim()
    }
}
