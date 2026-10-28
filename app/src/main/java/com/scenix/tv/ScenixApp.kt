package com.scenix.tv

import android.app.Application
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.Registries
import com.scenix.tv.core.Theme
import com.scenix.tv.plugins.PluginCatalog

/**
 * Builds the app context exactly once: theme, prefs, the screen registry and the setup screen.
 * The interface itself is the hub's web UI; this is only what the native launcher needs before it
 * can open the shell.
 */
class ScenixApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var theme: Theme
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
        registries = Registries()
        PluginCatalog.install(registries)
        ctx = AppContext(this, theme, prefs, registries)
    }
}
