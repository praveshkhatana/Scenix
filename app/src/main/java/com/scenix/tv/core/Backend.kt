package com.scenix.tv.core

import android.content.Context

/**
 * Which [HubClient] this TV talks to, decided in one place so every entry point agrees
 * (design/deployment-modes.md Rule 2: one contract, two hosts).
 *
 * Order matters: a pinned development fixture beats everything, because it is the one mode whose
 * whole point is that nothing on the network is consulted. Then the owner's choice — standalone
 * means this app hosts the contract itself. Everything else is a remote hub.
 */
fun clientFor(prefs: Prefs, context: Context): HubClient = when {
    !prefs.fixture().isNullOrBlank() -> FixtureHub(context, prefs.fixture()!!)
    prefs.standalone() -> LocalHub.get(context)
    else -> HttpHub(context, prefs)
}
