package com.scenix.tv.plugins.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Item
import com.scenix.tv.core.PlayTarget
import com.scenix.tv.core.PlayerPlugin
import com.scenix.tv.core.Source
import androidx.media3.exoplayer.ExoPlayer

/**
 * Torrents: listed, never rendered.
 *
 * This TV has 2 GB of RAM and is 32-bit; pulling a torrent through Media3 would need an on-device
 * engine that this build deliberately does not carry. So a `kind:"torrent"` source stays visible in
 * the hub's ranking (size, quality, risk) and, if the owner taps it, the magnet is handed to a
 * companion engine app through an ordinary `ACTION_VIEW` intent — no package name is hard-coded, the
 * app just checks that *something* can take a magnet.
 *
 * Two things make this one file the whole torrent story:
 *  - if the hub ever answers `POST /app/v1/play` with `mode:"gateway"` (an ordinary seekable HTTP
 *    stream), the player uses the http plugin instead and this plugin never runs;
 *  - if a real on-device engine lands later, it is a second PlayerPlugin for `kind:"torrent"` with
 *    `external() = false`, plus one line in PluginCatalog. Nothing here changes.
 */
object ExternalEnginePlayer : PlayerPlugin {
    override val id get() = "torrent-engine"
    override val title get() = "Torrent hand-off"
    override val order = 40
    override val kind get() = "torrent"

    private const val TAG = "ScenixTorrent"

    /** A magnet is not something Media3 can open, so the warning is printed wherever it appears. */
    override val warning get() = "Torrent \u2014 needs a stream engine on this TV"

    override fun riskLabel(source: Source): String? = "needs a stream engine"

    override fun riskToken(source: Source): String = "warn"

    /**
     * Always listed: the owner wants to see that a better-quality torrent copy exists. Whether it can
     * be opened is decided at tap time by [openExternally], which reports what it found.
     */
    override fun openable(app: AppContext, source: Source): Boolean = true

    override fun external(): Boolean = true

    override fun configure(player: ExoPlayer, app: AppContext, source: Source, target: PlayTarget) {
        // Not used: an external plugin never drives Media3.
    }

    override fun statusLine(source: Source, target: PlayTarget): String =
        listOf("torrent", source.quality, source.risk).filter { it.isNotBlank() }.joinToString(" \u00B7 ")

    override fun openExternally(app: AppContext, item: Item, source: Source, target: PlayTarget?): String {
        val magnet = target?.url?.takeIf { it.isNotBlank() }
            ?: source.label.takeIf { it.startsWith("magnet:") }
            ?: return "The hub returned no torrent address for ${item.title}"

        if (!magnet.startsWith("magnet:") && !magnet.startsWith("torrent:")) {
            return "Not a magnet this TV can hand over (${magnet.take(24)}\u2026)"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(magnet)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            if (intent.resolveActivity(app.app.packageManager) == null) {
                "No torrent engine installed \u2014 listed for reference"
            } else {
                app.app.startActivity(intent)
                "Handed to the torrent engine on this TV"
            }
        } catch (e: ActivityNotFoundException) {
            "No torrent engine installed \u2014 listed for reference"
        } catch (e: SecurityException) {
            Log.w(TAG, "engine refused the magnet", e)
            "The torrent engine refused this title"
        }
    }
}
