package com.scenix.tv.core

import android.util.Log

/**
 * A typed registry: plugins of one kind, keyed by their id, served sorted by `order`.
 *
 * Only screens remain. Keeping the registry (rather than naming the setup screen directly in the
 * activity) is what lets the launcher show a screen the catalog installed without knowing which one
 * it is.
 */
class Registry<T : Plugin>(
    private val kindName: String,
    private val keyOf: (T) -> String,
) {
    private val items = LinkedHashMap<String, T>()

    fun register(plugin: T): Registry<T> {
        val key = keyOf(plugin)
        val prev = items.put(key, plugin)
        if (prev != null) Log.w(TAG, "duplicate $kindName plugin '$key': ${prev.id} replaced by ${plugin.id}")
        return this
    }

    fun get(key: String?): T? = key?.let { items[it] }

    /** Plugins as registered, sorted by `order` — the one setup screen in a finished build. */
    fun all(): List<T> = items.values.sortedWith(compareBy({ it.order }, { it.id }))

    private companion object { const val TAG = "ScenixRegistry" }
}

/** All registries the app owns, filled by [com.scenix.tv.plugins.PluginCatalog]. */
class Registries {
    val screens = Registry<ScreenPlugin>("screen", { it.id })
}
