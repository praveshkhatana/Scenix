package com.scenix.tv.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.scenix.tv.BrowseApi

/**
 * What holding OK on a card does, identically on every pack and every screen.
 *
 * Two actions exist in the reference app and both are profile state, not theme decoration: hold a
 * poster to put it into (or take it out of) the list a profile keeps, and hold a Continue-watching
 * tile to take that title off the row. Neither belongs to a layout, so the action, the wording and
 * the optimistic write live here; a screen only supplies its own row refresh.
 *
 * The write is optimistic because the card must answer the instant the button comes up: the device's
 * mirror flips first, the hub answers, and a disagreement puts the mirror back and re-runs the
 * screen's repaint. That is the same bargain the detail page makes with its My List button.
 */
object CardActions {

    /** Hub callbacks arrive on the client's pool; row edits and toasts belong on the screen's thread. */
    private val main = Handler(Looper.getMainLooper())

    /** The profile's own list as this device knows it right now: the hub's stamp, then the mirror. */
    fun inList(app: AppContext, profileId: String, item: Item): Boolean =
        item.inList || app.prefs.myList(profileId).contains(item.identity)

    /**
     * Add or remove one title from My List. [settled] always runs on the screen's thread with the
     * state the hub confirmed, which is what lets a row drop a tile or repaint its mark.
     */
    fun toggleMyList(
        ctx: Context,
        app: AppContext,
        api: BrowseApi,
        profileId: String,
        item: Item,
        settled: (Boolean) -> Unit = {},
    ) {
        val was = inList(app, profileId, item)
        val now = !was
        writeLocal(app, profileId, item.identity, now)
        speak(ctx, if (now) "Added to My List · ${item.title}" else "Removed from My List · ${item.title}")
        api.setMyList(profileId, item.identity, now) { confirmed ->
            if (confirmed != now) {
                // The hub owns the list; the mirror follows it back and the card says what is true.
                writeLocal(app, profileId, item.identity, confirmed)
                main.post {
                    speak(ctx, if (confirmed) "In My List · ${item.title}" else "Not in My List · ${item.title}")
                }
            }
            main.post { settled(confirmed) }
        }
    }

    /**
     * Take a title off Continue watching. The watch position stays on the hub, so pressing the title
     * again re-arms the row — this is a dismissal, not a deletion.
     */
    fun dismissContinue(ctx: Context, api: BrowseApi, profileId: String, item: Item) {
        api.dismiss(profileId, item.identity) { }
        speak(ctx, "Removed from Continue watching · ${item.title}")
    }

    /**
     * How a card announces itself, naming the gesture the screen has armed on it. Live channels and
     * the genre tiles answer a hold with something else (or nothing), so they are left alone; a
     * Continue tile names its own action, which is leaving the row rather than the list.
     */
    fun announcement(
        app: AppContext,
        profileId: String,
        item: Item,
        base: String,
        holdHint: String? = null,
    ): String = when {
        item.isLive() || item.type == "genre" -> base
        holdHint != null -> "$base · $holdHint"
        else -> base + (if (inList(app, profileId, item)) " · holding removes it from My List"
            else " · holding adds it to My List")
    }

    private fun writeLocal(app: AppContext, profileId: String, identity: String, on: Boolean) {
        val ids = app.prefs.myList(profileId)
        if (on) { if (!ids.contains(identity)) ids.add(identity) } else ids.remove(identity)
        app.prefs.setMyList(profileId, ids)
    }

    private fun speak(ctx: Context, message: String) {
        main.post { Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show() }
    }
}
