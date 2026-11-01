package com.scenix.tv.core

import org.json.JSONObject
import java.util.Locale

/**
 * Structural Home contract for a theme pack.
 *
 * A palette is deliberately not a Home layout.  This small, validated contract lets a pack choose
 * the composition that Browse hosts: an Apple scene with preview selectors, a Netflix cinema
 * billboard, a PS5-style focused hub, or a future Roku/Clean/Magnifier surface.  Views consume
 * these enums rather than asking for a theme id, so a new pack cannot accidentally inherit an
 * unrelated family's navigation just because it shares a blue colour token.
 *
 * JSON lives in the theme's `pack` block:
 *
 * ```json
 * {
 *   "homeLayout": "ps5",
 *   "homeNavigation": "utility",
 *   "homeHero": "focused-hub",
 *   "homeRails": "hub"
 * }
 * ```
 *
 * Unknown values intentionally fall back to `LEGACY`/`PACK`/`SHARED_SLIDER`/`STACKED`, preserving
 * a usable Home screen for third-party packs while making the unsupported value harmless.
 */
data class HomeLayoutSpec(
    val family: Family,
    val navigation: Navigation,
    val hero: Hero,
    val rails: Rails,
) {
    /** Families are composition families, not brand names or palettes. */
    enum class Family {
        LEGACY,
        APPLE_SCENE,
        NETFLIX_CINEMA,
        PS5_HUB,
        ROKU_GRID,
        CLEAN,
        KIDS,
        MAGNIFIER,
    }

    /** The global Home route is intentionally separate from the visible control treatment. */
    enum class Navigation {
        /** Use the existing pack's nav grammar for backward-compatible/legacy packs. */
        PACK,
        /** Top textual navigation, as used by a cinema browsing surface. */
        TOP_BAR,
        /** Small PS5-inspired utility controls; never an Apple pill. */
        UTILITY,
        /** The Home composition owns all navigation/focus stops itself. */
        NONE,
    }

    /** The visual and interaction relationship between the active title and its recommendations. */
    enum class Hero {
        /** Existing HeroComponent with an embedded preview selector. */
        SHARED_SLIDER,
        /** Wide billboard directly advanced from its CTA edges; no duplicate preview rail. */
        CINEMA_BILLBOARD,
        /** Focused content hub: the selected large tile is the stage and the rail is the navigator. */
        FOCUSED_HUB,
        /** Two glass feature cards side by side above a chip row; a light, calm composition. */
        DUAL_CARDS,
        /** A bright stage with black pill navigation and two feature cards for young households. */
        KIDS_STAGE,
        /** No billboard: content begins with the primary browse surface. */
        NONE,
    }

    /** How the rest of Home occupies the screen after the lead composition. */
    enum class Rails {
        /** Conventional vertically stacked shelves. */
        STACKED,
        /** Rails are an integral part of the focused hub rather than a detached page below it. */
        HUB,
        /** Grid-first browsing (Roku-like). */
        GRID,
        /** Large, low-density rails for accessibility/Magnifier packs. */
        LARGE,
    }

    /** A separate host/component is required; using the Apple holder would be a visual bug. */
    val ownsComposition: Boolean
        get() = family != Family.LEGACY && family != Family.APPLE_SCENE

    companion object {
        fun from(pack: JSONObject): HomeLayoutSpec {
            val family = family(pack.optString("homeLayout", "legacy"))
            return HomeLayoutSpec(
                family = family,
                navigation = navigation(pack.optString("homeNavigation", defaultNavigation(family))),
                hero = hero(pack.optString("homeHero", defaultHero(family))),
                rails = rails(pack.optString("homeRails", defaultRails(family))),
            )
        }

        private fun normalized(value: String) = value.trim().lowercase(Locale.US)

        private fun family(value: String) = when (normalized(value)) {
            "apple", "apple-scene", "scene" -> Family.APPLE_SCENE
            "netflix", "netflix-cinema", "cinema" -> Family.NETFLIX_CINEMA
            "ps5", "ps5-hub", "console", "console-hub" -> Family.PS5_HUB
            "roku", "roku-grid", "grid" -> Family.ROKU_GRID
            "clean", "minimal" -> Family.CLEAN
            "kids" -> Family.KIDS
            "magnifier", "accessible", "large" -> Family.MAGNIFIER
            else -> Family.LEGACY
        }

        private fun navigation(value: String) = when (normalized(value)) {
            "top", "top-bar", "classic" -> Navigation.TOP_BAR
            "utility", "ps5-utility", "minimal-utility" -> Navigation.UTILITY
            "none", "owned" -> Navigation.NONE
            else -> Navigation.PACK
        }

        private fun hero(value: String) = when (normalized(value)) {
            "cinema", "cinema-billboard", "billboard" -> Hero.CINEMA_BILLBOARD
            "hub", "focused-hub", "focus-hub" -> Hero.FOCUSED_HUB
            "dual", "dual-cards", "clean-cards" -> Hero.DUAL_CARDS
            "kids", "kids-stage" -> Hero.KIDS_STAGE
            "none", "off", "grid" -> Hero.NONE
            else -> Hero.SHARED_SLIDER
        }

        private fun rails(value: String) = when (normalized(value)) {
            "hub", "integrated", "focus-hub" -> Rails.HUB
            "grid", "grid-first" -> Rails.GRID
            "large", "magnifier", "accessible" -> Rails.LARGE
            else -> Rails.STACKED
        }

        private fun defaultNavigation(family: Family) = when (family) {
            Family.NETFLIX_CINEMA -> "top-bar"
            Family.PS5_HUB -> "utility"
            Family.ROKU_GRID -> "top-bar"
            Family.CLEAN, Family.KIDS, Family.MAGNIFIER -> "none"
            else -> "pack"
        }

        private fun defaultHero(family: Family) = when (family) {
            Family.NETFLIX_CINEMA -> "cinema-billboard"
            Family.PS5_HUB -> "focused-hub"
            Family.CLEAN -> "dual-cards"
            Family.KIDS -> "kids-stage"
            Family.ROKU_GRID -> "none"
            else -> "shared-slider"
        }

        private fun defaultRails(family: Family) = when (family) {
            Family.PS5_HUB -> "hub"
            Family.ROKU_GRID -> "grid"
            Family.MAGNIFIER -> "large"
            else -> "stacked"
        }
    }
}
