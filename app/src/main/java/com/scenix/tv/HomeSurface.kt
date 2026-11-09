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
    /** The billboard's Play is playback, not a detail page. `onOpen` stays the card/detail route. */
    val onPlay: (Item) -> Unit,
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
    /** Hold OK on a hero card adds that title to (or takes it out of) the profile's list. */
    val onFavourite: (Item) -> Unit = {},
    /** The active section ("" = Home). Section pills paint ONLY the matching segment active, so
     *  Home shows no selection at all (owner, round 7: "when on home screen why is Movies selected?"). */
    val section: String = "",
    /** Draw the Live TV door only when the household's hub carries the live add-on and a playlist. */
    val showsLive: Boolean = true,
    /** Draw the Games door only when the household installed a games add-on. */
    val showsGames: Boolean = true,
)

/**
 * The only place that maps the typed structural theme contract to a visual family.  Adding Roku,
 * Clean or Magnifier means adding a surface here, rather than spreading `theme.id` checks through
 * BrowseActivity's RecyclerView, key routing and top-bar code.
 */
/**
 * Arms the press-and-hold on a hero card. The shelves answer this gesture already; a billboard that
 * ignored it would leave the profile's list one press away on one half of the page and unreachable
 * on the other. The item is read at press time because these cards are re-bound as the hero slides.
 */
internal fun armHeroHold(card: View, itemAt: () -> Item?, onFavourite: (Item) -> Unit) {
    card.isLongClickable = true
    card.setOnLongClickListener { itemAt()?.let(onFavourite); true }
}

internal object HomeSurfaceFactory {
    fun create(context: Context, app: AppContext, callbacks: HomeSurfaceCallbacks): HomeSurface =
        when (app.theme.homeLayout.hero) {
            HomeLayoutSpec.Hero.CINEMA_BILLBOARD -> NetflixSurface(
                NetflixHeroComponent(
                    context,
                    app,
                    NetflixHeroComponent.Callbacks(
                        onPlay = callbacks.onPlay,
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
                        showsLive = callbacks.showsLive,
                        showsGames = callbacks.showsGames,
                    ),
                ),
            )
            HomeLayoutSpec.Hero.GLASS_CARDS -> GlassSurface(
                GlassHomeLayout(
                    context,
                    app,
                    GlassHomeLayout.Callbacks(
                        onOpen = callbacks.onOpen,
                        onFavourite = callbacks.onFavourite,
                        onSection = callbacks.onSection,
                        onProfile = callbacks.onProfile,
                        onSearch = callbacks.onSearch,
                        onSettings = callbacks.onSettings,
                        onShelf = callbacks.onShelf,
                        showsLive = callbacks.showsLive,
                        showsGames = callbacks.showsGames,
                    ),
                ),
            )
            HomeLayoutSpec.Hero.DUAL_CARDS -> CleanSurface(
                CleanHomeLayout(
                    context,
                    app,
                    CleanHomeLayout.Callbacks(
                        onOpen = callbacks.onOpen,
                        onFavourite = callbacks.onFavourite,
                        onSection = callbacks.onSection,
                        onProfile = callbacks.onProfile,
                        onSearch = callbacks.onSearch,
                        onSettings = callbacks.onSettings,
                        onShelf = callbacks.onShelf,
                        showsLive = callbacks.showsLive,
                        showsGames = callbacks.showsGames,
                    ),
                ),
            )
            HomeLayoutSpec.Hero.KIDS_STAGE -> KidsSurface(
                KidsHomeLayout(
                    context,
                    app,
                    KidsHomeLayout.Callbacks(
                        onOpen = callbacks.onOpen,
                        onFavourite = callbacks.onFavourite,
                        onSection = callbacks.onSection,
                        onProfile = callbacks.onProfile,
                        onSearch = callbacks.onSearch,
                        onSettings = callbacks.onSettings,
                        onShelf = callbacks.onShelf,
                        section = callbacks.section,
                        showsLive = callbacks.showsLive,
                        showsGames = callbacks.showsGames,
                    ),
                ),
            )
            else -> AppleSceneSurface(
                HeroComponent(
                    context,
                    app,
                    HeroComponent.Callbacks(
                        onPlay = callbacks.onPlay,
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

    /**
     * The strip is the natural re-entry point when it exists; a hero row with a single slide has
     * no strip at all, and the Play action is then the only stop inside the billboard. Falling
     * back to it is what keeps UP from the first shelf (and after a tab re-entry) alive.
     */
    override fun focusEntry(activeIndex: Int): Boolean {
        // Only fall back when there is genuinely no strip: a strip that exists but is still being
        // laid out retries internally, and moving focus to Play here would race that retry.
        if (view.hasSelector()) return view.focusSelector(activeIndex)
        return view.playControl.requestFocus()
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

private class GlassSurface(private val view: GlassHomeLayout) : HomeSurface {
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
