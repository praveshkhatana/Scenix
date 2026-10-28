package com.scenix.tv

import android.app.Application
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.Theme

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

        prefs = Prefs(this)
        theme = Theme.load(assets, w, h)
        ctx = AppContext(this, theme, prefs)
    }
}
