package com.scenix.tv.plugins

import com.scenix.tv.core.Registries

/**
 * The one place every built-in screen is named — design/plugin-architecture.md §3.
 *
 * The product is the hub's web UI in [com.scenix.tv.WebShellActivity]; the only native screen left
 * is the setup screen a fresh TV shows before it has a hub to load. Cards, rows, rules and player
 * plugins went with the old native front end.
 */
object PluginCatalog {

    fun install(reg: Registries) {
        // The front door of a TV that has no backend yet: never in a nav (there is no native nav
        // any more), opened only by MainActivity's launcher once the hub watcher finds nothing paired.
        reg.screens.register(com.scenix.tv.plugins.nav.PairingScreen)
    }
}
