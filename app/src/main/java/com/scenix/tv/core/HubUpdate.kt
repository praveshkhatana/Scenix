package com.scenix.tv.core

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The hub keeps its own build current, so a household that updates its television does not end up
 * with a new app driving an old backend.
 *
 * The hub's checkout is the same repository this app is built from, and the hub publishes the git
 * facts about itself at `GET /app/v1/hub/update` and the clean fast-forward pull at `POST`
 * (the hub's core/update.js). This file is the television's half of that agreement: Settings gives
 * the person an "Update hub" row, and [followIfNeeded] nudges once per app run right after a hub
 * answers — so an APK installed from GitHub Releases today drags its hub forward today, with nobody
 * walking to the machine the hub runs on.
 *
 * The nudge never asks and never nags: when the hub declines (its checkout carries the owner's own
 * edits, it is not a git checkout at all, or it predates this route), that is the hub's business —
 * the Settings row surfaces the same answer on demand, and the hub logs it too.
 */
object HubUpdate {
    private const val TAG = "ScenixHubUpdate"

    /** What the hub says about its own build. */
    data class State(
        val git: Boolean,
        val commit: String?,
        val branch: String?,
        val behind: Int,
        val canUpdate: Boolean,
        val blocked: String?,
    )

    /**
     * Read the hub's build state. Blocks — call off the main thread. Null when this hub predates
     * the route (a 404) or did not answer JSON; that is an old hub, not an error to shout about.
     */
    fun status(base: String, token: String): State? {
        val s = call(base, token, "GET", 15_000) ?: return null
        if (!s.has("git")) return null
        return State(
            git = s.optBoolean("git"),
            commit = s.optString("commit").ifBlank { null },
            branch = s.optString("branch").ifBlank { null },
            behind = s.optInt("behind"),
            canUpdate = s.optBoolean("canUpdate"),
            blocked = s.optString("blocked").ifBlank { null },
        )
    }

    /** Ask the hub to pull and restart. Blocks; the process gap is a second or two. */
    fun updateNow(base: String, token: String): JSONObject? = call(base, token, "POST", 120_000)

    private fun call(base: String, token: String, method: String, timeoutMs: Int): JSONObject? {
        return try {
            val conn = URL("$base/app/v1/hub/update").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = timeoutMs
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) {
                val basic = Base64.encodeToString("scenix:$token".toByteArray(), Base64.NO_WRAP)
                conn.setRequestProperty("Authorization", "Basic $basic")
            }
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write("{}".toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 404 || text.isBlank()) null else JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    @Volatile private var nudged = false

    /** Forget the once-per-run guard: a viewer who just chose a different hub deserves the nudge again. */
    fun reset() { nudged = false }

    /**
     * Once per run, right after a hub answers: if that hub is behind its origin and allowed to
     * fast-forward, pull it now. Silent either way — the hub restarts within a second and
     * [HubWatch]'s probe re-finders it — because this is the household staying coherent, not an
     * event a viewer chose.
     */
    fun followIfNeeded(context: Context, base: String, token: String) {
        if (nudged) return
        nudged = true
        Thread({
            val s = status(base, token) ?: return@Thread
            if (s.behind <= 0) {
                Log.i(TAG, "hub at $base is current (${s.commit})")
                return@Thread
            }
            if (!s.canUpdate) {
                Log.i(TAG, "hub at $base is ${s.behind} commits behind but will not self-pull: ${s.blocked}")
                return@Thread
            }
            val r = updateNow(base, token)
            Log.i(
                TAG,
                if (r?.optBoolean("updated") == true) {
                    "hub followed the app: " + r.optString("from") + " -> " + r.optString("to") + ", hub restarts"
                } else {
                    "hub declined to update: " + (r?.optString("reason") ?: "no answer")
                },
            )
        }, "scenix-hubupdate").apply { isDaemon = true }.start()
    }
}
