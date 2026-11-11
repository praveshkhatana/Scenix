package com.scenix.tv.core

import android.graphics.Color

/**
 * The console library's own ink and ground.
 *
 * A theme pack owns the browser's wall, but a console library is a separate room: the reference's
 * grammar — black stage, white type, one electric blue — has to hold even when the household has
 * chosen a light pack for everything else. Only scale, spacing and motion are inherited from the
 * pack; these values are the game mode's surface identity.
 */
object Console {
    val bg = Color.parseColor("#07090D")
    val stage = Color.parseColor("#0B0D11")
    val surface = Color.parseColor("#151A22")
    val ink = Color.parseColor("#F3F6FA")
    val ink2 = Color.parseColor("#C2CBD6")
    val ink3 = Color.parseColor("#8A94A2")
    val line = Color.argb(56, 255, 255, 255)
    val lineStrong = Color.parseColor("#DDE4EC")
    val brand = Color.parseColor("#2E7DFF")
}
