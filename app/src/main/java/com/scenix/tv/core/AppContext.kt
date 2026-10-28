package com.scenix.tv.core

import android.app.Application
import android.widget.FrameLayout

/**
 * Everything the native setup screen may reach: theme, prefs and the hub client.
 * The web shell does not use this object for its interface — it only borrows the same [Prefs] to
 * find the page — so this surface stays deliberately small.
 */
class AppContext(
    val app: Application,
    val theme: Theme,
    val prefs: Prefs,
) {
    /**
     * The watcher that decides whether this TV has a hub to open. Installed once by MainActivity;
     * the pairing screen reaches through it when the viewer picks a backend.
     */
    var watch: HubWatch? = null
}

/**
 * The surface a native screen is given. A screen never reaches into the Activity; it asks to be
 * moved, and the host decides how.
 */
interface ScreenHost {
    val app: AppContext

    /** The container this screen must fill. Already cleared. */
    fun container(): FrameLayout

    /** The id of the screen on top. */
    fun currentScreenId(): String

    /** Re-render the screen that is on top, without pushing it onto a stack. */
    fun refresh() {}

    fun toast(msg: String)

    /** Leave the setup screen and open the product: the hub's web UI over the native player. */
    fun openShell()
}
