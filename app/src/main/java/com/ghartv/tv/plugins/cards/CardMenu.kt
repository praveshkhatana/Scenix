package com.scenix.tv.plugins.cards

import android.app.AlertDialog
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Format
import com.scenix.tv.core.Item
import com.scenix.tv.core.ScreenHost

/**
 * The MENU key on a focused card — the one key on a TV remote that has never had anything on the
 * other end of it in this app. What a viewer wants here is not a settings screen, it is the two
 * answers Netflix gives: this is finished, and I do not want to see this again.
 *
 * Both are honest about where they live. "Mark as watched" is the hub's own progress endpoint at
 * position 0 (the same thing the player posts when a title ends), and the rules drop finished titles
 * out of Continue watching — so the row is right again without the app keeping a second, private
 * idea of what has been watched. "Not interested" is local to this TV, because the hub has no word
 * for a viewer refusing a title.
 *
 * The dialog is built on the Activity context, not the Application one: a Dialog with an
 * application context has no window token on modern Android.
 */
object CardMenu {

    fun show(ctx: AppContext, host: ScreenHost, item: Item) {
        val profileId = ctx.profile?.id ?: ""
        val blocked = profileId.isNotBlank() && item.identity in ctx.prefs.notInterested(profileId)
        val listed = profileId.isNotBlank() && item.identity in ctx.prefs.myList(profileId)

        val labels = ArrayList<String>(4)
        val actions = ArrayList<() -> Unit>(4)

        if (item.progress == null || item.progress.fraction < 0.999f) {
            labels.add("Mark as watched")
            actions.add {
                // The hub's own "finished" signal: position 0, exactly what the player posts at the end.
                ctx.hub.progress(item.identity, 0L, profileId.ifBlank { null })
                host.toast("Marked as watched")
                host.refresh()
            }
        }
        labels.add(if (blocked) "Undo \u201Cnot interested\u201D" else "Not interested")
        actions.add {
            if (profileId.isBlank()) {
                host.toast("Choose a profile first")
            } else {
                val now = ctx.prefs.toggleNotInterested(profileId, item.identity)
                host.toast(if (now) "Will not show again for ${ctx.profile?.name ?: "this profile"}" else "Showing this again")
                host.refresh()
            }
        }
        if (profileId.isNotBlank()) {
            labels.add(if (listed) "Remove from My List" else "Add to My List")
            actions.add {
                val added = ctx.prefs.toggleMyList(profileId, item.identity)
                host.toast(if (added) "Added to My List" else "Removed from My List")
                host.refresh()
            }
        }

        val subtitle = buildString {
            if (item.year > 0) append(item.year)
            val d = Format.duration(item.durationMs)
            if (d.isNotBlank()) { if (isNotEmpty()) append(" \u00B7 "); append(d) }
            if (item.maturity.isNotBlank()) { if (isNotEmpty()) append(" \u00B7 "); append(item.maturity) }
        }
        AlertDialog.Builder(host.container().context)
            .setTitle(item.title)
            .setMessage(subtitle.ifBlank { null })
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }
}
