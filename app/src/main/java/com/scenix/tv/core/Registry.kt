package com.scenix.tv.core

import android.util.Log

/**
 * A typed registry: plugins of one kind, keyed by their hub-facing string, served sorted by
 * `order`. This is the whole "typed map" half of plugin-architecture.md §3.
 *
 * `resolve()` is the forward-compatibility seam: an unknown key falls back to a named default and
 * logs, so a hub that starts sending `card: "documentary"` renders as a poster instead of
 * crashing, and the log tells us exactly which renderer to add.
 */
class Registry<T : Plugin>(
    private val kindName: String,
    private val keyOf: (T) -> String,
    private val fallbackKey: String? = null,
) {
    private val items = LinkedHashMap<String, T>()
    private val warned = HashSet<String>()

    val size get() = items.size

    fun register(plugin: T): Registry<T> {
        val key = keyOf(plugin)
        val prev = items.put(key, plugin)
        if (prev != null) Log.w(TAG, "duplicate $kindName plugin '$key': ${prev.id} replaced by ${plugin.id}")
        return this
    }

    fun registerAll(plugins: List<T>): Registry<T> {
        for (p in plugins) register(p)
        return this
    }

    fun get(key: String?): T? = key?.let { items[it] }

    /** Keys as registered, sorted by `order` — what /app/v1/manifest mirrors, read-only. */
    fun all(): List<T> = items.values.sortedWith(compareBy({ it.order }, { it.id }))

    /** Keys only (for the Settings manifest dump). */
    fun keys(): List<String> = all().map { keyOf(it) }

    fun resolve(key: String?): T? {
        val direct = get(key)
        if (direct != null) return direct
        val fb = fallbackKey?.let { items[it] } ?: return null
        val missing = key ?: "<null>"
        if (warned.add(missing)) {
            Log.w(TAG, "unknown $kindName '$missing' — falling back to '$fallbackKey' (add a plugin for it)")
        }
        return fb
    }

    private companion object { const val TAG = "ScenixRegistry" }
}

/**
 * All registries the app owns, in one place, filled by `PluginCatalog.registerAll()`.
 * Adding a feature never touches anything except `PluginCatalog.kt`.
 */
class Registries {
    val cards = Registry<CardPlugin>("card", { it.cardType }, "poster")
    val rows = Registry<RowPlugin>("row", { it.rowId })
    val rules = Registry<RulePlugin>("rule", { it.id })
    val players = Registry<PlayerPlugin>("player", { it.kind }, "http")
    val screens = Registry<ScreenPlugin>("screen", { it.id })

    fun cardFor(cardType: String?): CardPlugin? = cards.resolve(cardType)
    fun playerFor(kind: String?): PlayerPlugin? = players.resolve(kind)
    fun rowFor(rowId: String?): RowPlugin? = rows.get(rowId)

    /** Screens the top nav should render for this profile — nav is registry-driven, not hardcoded. */
    fun navFor(profile: Profile?): List<ScreenPlugin> = screens.all().filter { s ->
        if (!s.showInNav) return@filter false
        when (profile?.isKids) {
            true -> s.scope == "all" || s.scope == "kids"
            else -> s.scope == "all" || s.scope == "adult"
        }
    }
}
