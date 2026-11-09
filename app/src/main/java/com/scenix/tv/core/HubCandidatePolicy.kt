package com.scenix.tv.core

/**
 * Orders the origins that may be probed while recovering a Hub connection.
 *
 * An address a person has selected is a trust boundary, not merely the first item in a LAN
 * scan. If that address is unavailable, silently trying every remembered or discovered machine
 * can connect the television to the wrong household (and leaks a health request to those hosts).
 * Recovery candidates are therefore only used before a person has chosen an address, during the
 * deliberate first-run/discovery flow.
 */
object HubCandidatePolicy {

    /**
     * Return one selected origin, or the recovery list when no selection exists.
     *
     * Values are normalised and de-duplicated here so callers cannot accidentally re-introduce an
     * unbounded fan-out by adding the same address through a different source.
     */
    @JvmStatic
    fun candidates(
        selected: String?,
        lastWorking: String?,
        discovered: Iterable<String>,
        defaultsCsv: String,
    ): List<String> {
        val explicit = clean(selected)
        if (explicit.isNotEmpty()) return listOf(explicit)

        val out = LinkedHashSet<String>()
        fun add(value: String?) {
            clean(value).takeIf { it.isNotEmpty() }?.let(out::add)
        }
        add(lastWorking)
        discovered.forEach(::add)
        defaultsCsv.split(',').forEach(::add)
        return out.toList()
    }

    private fun clean(value: String?): String = value?.trim()?.trimEnd('/').orEmpty()
}
