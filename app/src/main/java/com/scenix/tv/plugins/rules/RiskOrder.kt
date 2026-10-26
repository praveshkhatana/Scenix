package com.scenix.tv.plugins.rules

import com.scenix.tv.core.Item
import com.scenix.tv.core.RuleCtx
import com.scenix.tv.core.RulePlugin
import com.scenix.tv.core.Source

/**
 * Source ordering: the client decides which source "Play" picks, so the order has to be a rule and
 * not a detail of the player.
 *
 * Adaptive streams first (HLS/DASH climb and fall back by themselves on a flaky home network), then
 * plain HTTP, then whatever else the hub knows about, then torrents last — a torrent starts from a
 * DHT crawl on a 2 GB TV and stalls under 720p. Quality breaks a tie, best first.
 *
 * This runs before the card renders, so the "Play from" list in Detail and the button on the
 * billboard are already showing the same order the player will use.
 */
object RiskOrder : RulePlugin {
    override val id get() = "source-order"
    override val title get() = "Source order: adaptive first, torrent last"
    override val order = 40

    fun rank(kind: String): Int = when (kind.lowercase()) {
        "hls", "dash", "adaptive", "master" -> 0
        "http", "https", "direct", "file" -> 1
        "torrent", "magnet", "torrentio", "p2p" -> 3
        else -> 2
    }

    fun qualityRank(quality: String): Int = when (quality.lowercase()) {
        "4k", "2160", "2160p", "uhd" -> 5
        "1080", "1080p", "fhd" -> 4
        "720", "720p", "hd" -> 3
        "480", "480p" -> 2
        "360", "360p" -> 1
        else -> 0
    }

    override fun applyItem(item: Item, rctx: RuleCtx): Item {
        if (item.sources.size < 2) return item
        val sorted = item.sources.sortedWith(
            compareBy<Source> { rank(it.kind) }
                .thenByDescending { qualityRank(it.quality) }
                .thenByDescending { it.sizeBytes },
        )
        if (sorted == item.sources) return item
        return item.copy(sources = sorted)
    }
}
