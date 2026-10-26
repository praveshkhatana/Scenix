package com.scenix.tv.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.MessageDigest

/**
 * Local persistence: hub URL, profile choice, per-profile PIN, per-profile hidden rows.
 *
 * Profiles themselves and watch progress live on the hub (kids-app-plan §6b), so a reinstall
 * keeps them; this file only holds what the app must know before it can talk to anything.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("scenix", Context.MODE_PRIVATE)

    // ---- hub -----------------------------------------------------------------------------
    fun hubUrl(fallback: String): String = sp.getString(KEY_HUB, null)?.takeIf { it.isNotBlank() } ?: fallback
    fun setHubUrl(url: String) = sp.edit().putString(KEY_HUB, url.trim()).apply()

    /**
     * Every hub this TV is allowed to talk to, in the order it should try them.
     *
     * A hub is just a Node process the user can run on any machine, so the same TV may face a LAN
     * address today, a Tailscale address tomorrow and a VPS next year. Listing several and
     * remembering which one last answered means the TV keeps working when the hub moves, without
     * anyone re-typing an address on a 10-foot remote.
     */
    fun hubUrlCandidates(defaultCsv: String): List<String> {
        val out = ArrayList<String>()
        fun add(u: String?) {
            val t = u?.trim()?.trimEnd('/') ?: return
            if (t.isNotEmpty() && !out.contains(t)) out.add(t)
        }
        // explicit choice in Settings wins, then wherever the TV last actually got an answer.
        add(sp.getString(KEY_HUB, null))
        add(sp.getString(KEY_HUB_LAST, null))
        defaultCsv.split(',').forEach { add(it) }
        return out
    }

    fun setHubLastWorking(url: String) = sp.edit().putString(KEY_HUB_LAST, url.trim()).apply()
    fun hubLastWorking(): String? = sp.getString(KEY_HUB_LAST, null)?.takeIf { it.isNotBlank() }

    /** `--es fixture <asset-path>`: when set, the app reads rows from assets instead of the network. */
    fun fixture(): String? = sp.getString(KEY_FIXTURE, null)?.takeIf { it.isNotBlank() }
    fun setFixture(path: String?) = sp.edit().putString(KEY_FIXTURE, path ?: "").apply()

    // ---- profile -------------------------------------------------------------------------
    fun currentProfileId(): String? = sp.getString(KEY_PROFILE, null)?.takeIf { it.isNotBlank() }
    fun setCurrentProfileId(id: String) = sp.edit().putString(KEY_PROFILE, id).apply()

    /**
     * PINs are per-profile and app-side (the hub never sees them). Stored as a SHA-256 hash, so a
     * pulled prefs.xml does not reveal the digits; there is no salt because the space is 10^4.
     */
    fun pinHash(profileId: String): String? = sp.getString("$KEY_PIN_PREFIX$profileId", null)
    fun setPin(profileId: String, pin: String?) {
        if (pin.isNullOrEmpty()) sp.edit().remove("$KEY_PIN_PREFIX$profileId").apply()
        else sp.edit().putString("$KEY_PIN_PREFIX$profileId", sha256(pin)).apply()
    }

    fun hasPin(profileId: String): Boolean = pinHash(profileId) != null

    fun checkPin(profileId: String, pin: String): Boolean {
        val want = pinHash(profileId) ?: return true
        return want == sha256(pin)
    }

    // ---- per-profile hidden rows (hide-rows rule reads this) ------------------------------
    fun hiddenRows(profileId: String): List<String> {
        val raw = sp.getString("$KEY_HIDDEN_PREFIX$profileId", "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setHiddenRows(profileId: String, ids: List<String>) =
        sp.edit().putString("$KEY_HIDDEN_PREFIX$profileId", ids.joinToString(",")).apply()

    fun toggleHiddenRow(profileId: String, rowId: String): Boolean {
        val cur = hiddenRows(profileId).toMutableList()
        val hidden = if (cur.contains(rowId)) { cur.remove(rowId); false } else { cur.add(rowId); true }
        setHiddenRows(profileId, cur)
        return hidden
    }

    // ---- My List ---------------------------------------------------------------------------
    // Local for P1: the hub's `mylist` row is another agent's file; when it lands, this becomes a
    // POST and the screen does not change.
    fun myList(profileId: String): List<String> {
        val raw = sp.getString("$KEY_LIST_PREFIX$profileId", "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun toggleMyList(profileId: String, identity: String): Boolean {
        val cur = myList(profileId).toMutableList()
        val added = if (cur.contains(identity)) { cur.remove(identity); false } else { cur.add(identity); true }
        sp.edit().putString("$KEY_LIST_PREFIX$profileId", cur.joinToString(",")).apply()
        return added
    }

    /**
     * "Not interested" — the MENU-key block list, per profile, kept on this TV. The hub has no
     * notion of a viewer saying no to a title, so the app remembers it locally and the
     * `not-interested` rule drops those items from every row it can reach.
     */
    fun notInterested(profileId: String): List<String> =
        (sp.getString("$KEY_NOTINT_PREFIX$profileId", "") ?: "").split(',').filter { it.isNotBlank() }

    /** Returns true when the identity is now blocked, false when the block was lifted. */
    fun toggleNotInterested(profileId: String, identity: String): Boolean {
        val cur = notInterested(profileId).toMutableList()
        val added = if (!cur.remove(identity)) { cur.add(identity); true } else false
        sp.edit().putString("$KEY_NOTINT_PREFIX$profileId", cur.joinToString(",")).apply()
        return added
    }

    private fun sha256(s: String): String = try {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        d.joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "no SHA-256, storing PIN in the clear", t)
        s
    }

    /** Autoplay the next title in the row when this one ends. Per profile: a kid's set behaves differently. */
    fun autoplayNext(profileId: String): Boolean =
        sp.getBoolean(KEY_AUTOPLAY_PREFIX + profileId, true)

    fun setAutoplayNext(profileId: String, on: Boolean) {
        sp.edit().putBoolean(KEY_AUTOPLAY_PREFIX + profileId, on).apply()
    }

    companion object {
        private const val TAG = "ScenixPrefs"
        private const val KEY_AUTOPLAY_PREFIX = "autoplaynext_"
        private const val KEY_HUB = "hub_url"
        private const val KEY_HUB_LAST = "hub_url_last_working"
        private const val KEY_FIXTURE = "fixture"
        private const val KEY_PROFILE = "profile_id"
        private const val KEY_PIN_PREFIX = "pin_"
        private const val KEY_HIDDEN_PREFIX = "hidden_"
        private const val KEY_LIST_PREFIX = "mylist_"
        private const val KEY_NOTINT_PREFIX = "notint_"
    }
}
