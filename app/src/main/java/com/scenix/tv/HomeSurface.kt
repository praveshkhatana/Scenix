package com.scenix.tv

import android.content.Context
import android.view.View
import android.widget.TextView
import android.widget.VideoView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.HomeLayoutSpec
import com.scenix.tv.core.Item

/**
 * The one contract Browse needs from a structural Home composition.
 *
 * It separates the host's durable responsibilities (rows, routes, profile identity and the one
 * active slide index) from each theme family's visual grammar.  Apple, Netflix and PS5 therefore
 * share data and remote semantics without being forced into the same header, hero, selector or
 * shelf hierarchy.
 */
internal interface HomeSurface {
    val root: View
    val primaryControl: View
    /** An internal Home navigation stop for compositions such as the PS5-style context strip. */
    val navigationAnchor: View? get() = null
    /** A rail/selecting control if the composition exposes one; Netflix intentionally returns null. */
    val selectorRoot: View?
    val preferredHeightPx: Int

    /** Apple can offer an optional sampled video; other structural homes intentionally do not. */
    val videoView: VideoView? get() = null
    val subtitleView: TextView? get() = null

    /** Render from Browse's one source of truth for featured items and active index. */
    fun render(slides: List<Item>, activeIndex: Int)
    fun setNextFocusUp(id: Int)
    /** Home's initial/re-entry focus stop for the current title. */
    fun focusEntry(activeIndex: Int): Boolean
    /** Shelf titles a composition may mirror as in-page shortcuts (Clean's chip row). */
    fun setChips(shelves: List<Pair<String, String>>) {}
    /** The household identity a composition shows in its own header (Clean/Kids packs). */
    fun setProfile(name: String, avatarUrl: String?) {}
}

/**
 * Host-owned actions passed into a composition.  `onSelected` is an absolute slide index;
 * `onStep` is a relative arrow intent. Keeping both prevents a direct-arrow cinema billboard from
 * pretending it has an Apple preview tile and preserves a PS5 hub's direct card focus.
 */
internal data class HomeSurfaceCallbacks(
    val onOpen: (Item) -> Unit,
    val onSelected: (Int) -> Unit,
    val onStep: (Int) -> Unit,
    val onHeroFocused: () -> Unit,
    val onSection: (String) -> Unit,
    val onProfile: () -> Unit,
    val onSearch: () -> Unit,
    val onSettings: () -> Unit,
    /** Jump the outer list to the shelf a chip names; surfaces that own their rails ignore it. */
    val onShelf: (String) -> Unit = {},
)

/**
 * The only place that maps the typed structural theme contract to a visual family.  Adding Roku,
 * Clean or Magnifier means adding a surface here, rather than spreading `theme.id` checks through
 * BrowseActivity's RecyclerView, key routing and top-bar code.
 */
internal object HomeSurfaceFactory {
    fun create(context: Context, app: AppContext, callbacks: HomeSurfaceCallbacks): HomeSurface =
        when (app.theme.homeLayout.hero) {
            HomeLayoutSpec.Hero.CINEMA_BILLBOARD -> NetflixSurface(
                NetflixHeroComponent(
                    context,
                    app,
                    NetflixHeroComponent.Callbacks(
                        onPlay = callbacks.onOpen,
                        onMoreInfo = callbacks.onOpen,
                        onSlideRequested = callbacks.onStep,
                        onHeroFocused = callbacks.onHeroFocused,
                    ),
                ),
            )
            HomeLayoutSpec.Hero.FOCUSED_HUB -> Ps5Surface(
                Ps5HomeLayout(
                    context,
                    app,
                    Ps5HomeLayout.Callbacks(
                        onItemFocused = callbacks.onSelected,
                        onItemOpen = callbacks.onOpen,
                        onSection = callbacks.onSection,
                        onProfile = callbacks.onProfile,
                        onSearch = callbacks.onSearch,
                        onSettings = callbacks.onSettings,
                    ),
                ),
            )
            HomeLayoutSpec.Hero.DUAL_CARDS -> CleanSurface(
                CleanHomeLayout(
                    context,
                    app,
                    CleanHomeLayout.Callbacks(
                        onOpen = callbacks.onOpen,
                        onSection = callbacks.onSection,
                        onProfile = callbacks.onProfile,
                        onSearch = callbacks.onSearch,
                        onSettings = callbacks.onSettings,
                        onShelf = callbacks.onShelf,
                    ),
                ),
            )
            HomeLayoutSpec.Hero.KIDS_STAGE -> KidsSurface(
                KidsHomeLayout(
                    context,
                    app,
                    KidsHomeLayout.Callbacks(
                        onOpen = callbacks.onOpen,
                        onSection = callbacks.onSection,
                        onProfile = callbacks.onProfile,
                        onSearch = callbacks.onSearch,
                        onSettings = callbacks.onSettings,
                        onShelf = callbacks.onShelf,
                    ),
                ),
            )
            else -> AppleSceneSurface(
                HeroComponent(
                    context,
                    app,
                    HeroComponent.Callbacks(
                        onPlay = callbacks.onOpen,
                        onSlideSelected = callbacks.onSelected,
                        onPlayFocused = callbacks.onHeroFocused,
                    ),
                ),
            )
        }
}

private class AppleSceneSurface(private val view: HeroComponent) : HomeSurface {
    override val root: View get() = view.root
    override val primaryControl: View get() = view.playControl
    override val selectorRoot: View get() = view.selectorRoot
    override val preferredHeightPx: Int get() = view.preferredHeightPx
    override val videoView: VideoView get() = view.videoView
    override val subtitleView: TextView get() = view.subtitleView

    override fun render(slides: List<Item>, activeIndex: Int) {
        slides.getOrNull(activeIndex)?.let { view.render(it, slides, activeIndex) }
    }

    override fun setNextFocusUp(id: Int) = view.setNextFocusUp(id)

    override fun focusEntry(activeIndex: Int): Boolean {
        view.focusSelector(activeIndex)
        return true
    }
}

private class NetflixSurface(private val view: NetflixHeroComponent) : HomeSurface {
    override val root: View get() = view.root
    override val primaryControl: View get() = view.primaryControl
    override val selectorRoot: View? get() = null
    override val preferredHeightPx: Int get() = view.preferredHeightPx

    override fun render(slides: List<Item>, activeIndex: Int) {
        slides.getOrNull(activeIndex)?.let { view.render(it, activeIndex, slides.size) }
    }

    override fun setNextFocusUp(id: Int) = view.setNextFocusUp(id)
    override fun focusEntry(activeIndex: Int): Boolean = view.focusPrimary()
}

private class Ps5Surface(private val view: Ps5HomeLayout) : HomeSurface {
    override val root: View get() = view.root
    override val primaryControl: View get() = view.detailsControl
    override val navigationAnchor: View? get() = view.navigationAnchor
    override val selectorRoot: View get() = view.tileStrip
    override val preferredHeightPx: Int get() = view.preferredHeightPx

    override fun render(slides: List<Item>, activeIndex: Int) = view.render(slides, activeIndex)
    override fun setNextFocusUp(id: Int) = view.setNextFocusUp(id)

    override fun focusEntry(activeIndex: Int): Boolean {
        view.focusTile(activeIndex)
        return true
    }
}

private class CleanSurface(private val view: CleanHomeLayout) : HomeSurface {
    override val root: View get() = view.root
    override val primaryControl: View get() = view.primaryControl
    override val navigationAnchor: View? get() = view.navigationAnchor
    override val selectorRoot: View? get() = null
    override val preferredHeightPx: Int get() = view.preferredHeightPx

    override fun render(slides: List<Item>, activeIndex: Int) = view.render(slides, activeIndex)
    override fun setNextFocusUp(id: Int) = view.setNextFocusUp(id)
    override fun setChips(shelves: List<Pair<String, String>>) = view.setChips(shelves)
    override fun setProfile(name: String, avatarUrl: String?) = view.setProfile(name, avatarUrl)

    override fun focusEntry(activeIndex: Int): Boolean = view.focusEntry(activeIndex)
}

private class KidsSurface(private val view: KidsHomeLayout) : HomeSurface {
    override val root: View get() = view.root
    override val primaryControl: View get() = view.primaryControl
    override val navigationAnchor: View? get() = view.navigationAnchor
    override val selectorRoot: View? get() = null
    override val preferredHeightPx: Int get() = view.preferredHeightPx

    override fun render(slides: List<Item>, activeIndex: Int) = view.render(slides, activeIndex)
    override fun setNextFocusUp(id: Int) = view.setNextFocusUp(id)
    override fun setChips(shelves: List<Pair<String, String>>) = view.setChips(shelves)
    override fun setProfile(name: String, avatarUrl: String?) = view.setProfile(name, avatarUrl)

    override fun focusEntry(activeIndex: Int): Boolean = view.focusEntry(activeIndex)
}
