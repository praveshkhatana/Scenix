package com.scenix.tv.core

/**
 * The app plugin host. What remains is one kind: a screen. The product itself is the hub's web UI
 * in [com.scenix.tv.WebShellActivity]; the only native screen left is the pairing/setup front door
 * a fresh TV needs before it has a hub to load anything from.
 *
 * A new screen is a new file under `plugins/` plus one line in `PluginCatalog.kt`.
 */

interface Plugin {
    /** Registry key for this kind of plugin. */
    val id: String
    /** Human label. */
    val title: String get() = id
    /** Sort order inside its registry. */
    val order: Int get() = 0
}

// ---------------------------------------------------------------------------------------------
// nav/ — native screens. The setup screen is the only one registered.
// ---------------------------------------------------------------------------------------------
interface ScreenPlugin : Plugin {
    /** Build the screen into `host.container()`. */
    fun render(host: ScreenHost)
}
