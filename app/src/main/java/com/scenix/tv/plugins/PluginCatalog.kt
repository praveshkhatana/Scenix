package com.scenix.tv.plugins

import com.scenix.tv.core.Registries

/**
 * The one place every built-in plugin is named — design/plugin-architecture.md §3.
 *
 * A new feature is a new file plus one line here. Nothing else in the app knows which plugins
 * exist: screens look plugins up by the id the hub sent (`card`, `kind`, `rowId`), the nav is built
 * from whatever registers a `route`, and the rule pipeline runs whatever is in the list. So adding a
 * plugin cannot change an existing plugin's logic, and a hub that starts sending an unknown
 * `card:"trailer"` logs once and renders a poster instead of crashing.
 *
 * Order comes from each plugin, not from this file — see [Registries].
 */
object PluginCatalog {

    fun install(reg: Registries) {
        // ---- cards: how one item is painted -----------------------------------------------
        reg.cards.register(com.scenix.tv.plugins.cards.PosterCard)
        reg.cards.register(com.scenix.tv.plugins.cards.ChannelCard)
        reg.cards.register(com.scenix.tv.plugins.cards.ContinueCard)
        reg.cards.register(com.scenix.tv.plugins.cards.HeroCard)

        // ---- rows: per-row layout / decoration. Any row with no entry here is drawn by the
        // ---- generic renderer in plugins/rows/RowAdapter.kt with the card it names.
        reg.rows.register(com.scenix.tv.plugins.rows.Top10Row)

        // ---- rules: run in order over every row before it reaches the screen.
        reg.rules.register(com.scenix.tv.plugins.rules.DedupeGuard)
        reg.rules.register(com.scenix.tv.plugins.rules.HideRows)
        reg.rules.register(com.scenix.tv.plugins.rules.KidsGuard)
        reg.rules.register(com.scenix.tv.plugins.rules.RiskOrder)
        reg.rules.register(com.scenix.tv.plugins.rules.ContinueGuard)
        reg.rules.register(com.scenix.tv.plugins.rules.NotInterested)

        // ---- players: this registry IS the list of things this TV can actually do with a source.
        //      "http" is the fallback for any unknown kind, so a new hub kind still streams.
        //      `torrent` registers a hand-off, not a renderer: the source stays visible in the
        //      ranking with its size and risk, a tap passes the magnet to whatever engine the owner
        //      installed (if any), and auto-play skips it because that plugin says external().
        //      Real torrent playback later = one more PlayerPlugin with external() = false, here.
        reg.players.register(com.scenix.tv.plugins.player.HttpPlayer)
        reg.players.register(com.scenix.tv.plugins.player.HlsPlayer)
        reg.players.register(com.scenix.tv.plugins.player.YtPlayer)
        reg.players.register(com.scenix.tv.plugins.player.ExternalEnginePlayer)

        // ---- screens: the nav is this list, filtered by profile scope.
        reg.screens.register(com.scenix.tv.plugins.nav.ProfilesScreen)
        // The front door of a TV that has no backend yet: never in the nav, opened only by
        // MainActivity.startup when the watcher finds nothing paired.
        reg.screens.register(com.scenix.tv.plugins.nav.PairingScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.HomeScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.MoviesScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.SeriesScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.LiveScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.NewScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.MyListScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.KidsScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.SearchScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.RowScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.DetailScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.PlayerScreen)
        reg.screens.register(com.scenix.tv.plugins.nav.SettingsScreen)
    }
}
