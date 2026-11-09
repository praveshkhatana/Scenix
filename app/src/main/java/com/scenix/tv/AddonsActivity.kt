package com.scenix.tv

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scenix.tv.core.AppContext
import com.scenix.tv.core.Motion
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.Screens
import com.scenix.tv.core.Focus
import com.scenix.tv.core.Views
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** The Hub's reviewed directory plus installed sources; manual manifests stay in an advanced lane. */
class AddonsActivity : Activity() {

    private lateinit var app: AppContext
    private lateinit var profileId: String
    private lateinit var sourceList: RecyclerView
    private lateinit var sourceAdapter: SourceBrowserAdapter
    private lateinit var status: TextView
    private var installed: JSONArray? = null
    private var profileVerified = false
    private var reviewed: JSONArray? = null
    // The reviewed directory is an optional Hub capability. Keep its loading state distinct from an
    // empty result so the first render never claims a directory is unavailable while it is in flight.
    private var reviewedDirectoryLoaded = false
    private var reviewedDirectoryError: String? = null
    // Directory state stays on the TV until this screen closes. The Hub owns the entries; the TV only
    // narrows that safe, already-redacted list, so changing a filter never makes another network call.
    private var directoryCategory: String? = null
    private var directoryType: String? = null
    private var directoryQuery = ""
    // Community discovery is a distinct, explicitly non-endorsed catalogue. Its results are only
    // read after the adult gate and never share a filter or an install path with reviewed sources.
    private var activeDiscoverCatalog = DiscoverCatalog.CURATED
    private var communityDirectory: JSONObject? = null
    private var communityDirectoryLoaded = false
    private var communityDirectoryError: String? = null
    private var communityType = COMMUNITY_DEFAULT_TYPE
    private var communityQuery = ""
    private var communityRequestVersion = 0
    private var activeLane = SourceLane.DISCOVER
    private var picker: View? = null
    // The row that opened the last picker; it takes focus back when the picker
    // closes, so the remote never lands on the page's back control by accident.
    private var pickerReturn: View? = null
    // A picker is an active modal route, not decoration over the list.  Keep the only legal D-pad
    // destinations explicitly so a long source/catalogue list cannot hand focus to a row behind it.
    private var pickerRows: List<View> = emptyList()
    private var renameAddon: String? = null
    private var renameCatalog: String? = null
    // A protected request is retried at most once, and only after the owner explicitly re-enters
    // the household Settings PIN. The capability itself stays in Prefs' process-memory store.
    private var pendingSettingsRetry: (() -> Unit)? = null
    // The first accepted async list submission owns initial focus. A later directory response may
    // supersede the initial one before it reaches the main thread, so focus cannot be tied to one
    // particular network callback.
    private var needsInitialSourceFocus = true
    private val api by lazy { BrowseApi(applicationContext, (application as ScenixApp).prefs) }

    private enum class SourceLane {
        DISCOVER,
        INSTALLED,
        ADVANCED,
    }

    private enum class DiscoverCatalog {
        CURATED,
        COMMUNITY,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app = (application as ScenixApp).ctx
        profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty().trim()
        // This page is also reachable from the Browse rail. Never infer an adult identity from a
        // missing extra: a kids/unknown route must not render the household source directory at all.
        if (profileId.isBlank()) {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val wall = Screens.wall(app)
            if (wall != null) background = wall else setBackgroundColor(app.theme.bg)
        }
        val (bar, barStatus) = Screens.topBar(this, app, "Source library") { finish() }
        status = barStatus
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.theme.px(88f)))

        // A directory may contain dozens of sources. Keep only visible dense rows mounted instead
        // of asking a ScrollView to measure and draw every source after a filter changes.
        sourceList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AddonsActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(18)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(app.theme.gutter, app.theme.px(14f), app.theme.gutter, app.theme.px(40f))
        }
        sourceAdapter = SourceBrowserAdapter()
        sourceList.adapter = sourceAdapter
        root.addView(sourceList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Motion.enter(app.theme, root)
        verifyTrustedAdultProfile()
    }

    override fun onDestroy() {
        if (::sourceAdapter.isInitialized) sourceAdapter.dispose()
        super.onDestroy()
    }

    /** The client gate only controls visibility; the Hub still enforces the admin session on writes. */
    private fun verifyTrustedAdultProfile() {
        status.text = "Checking profile…"
        api.profiles { profiles, _ ->
            if (isFinishing || isDestroyed) return@profiles
            val profile = profiles?.firstOrNull { it.id == profileId }
            if (profile == null || profile.kids) {
                // Fail closed for a stale, fabricated, or child profile id. Do not leave an empty
                // directory that could be mistaken for a permitted source-management screen.
                finish()
                return@profiles
            }
            profileVerified = true
            load()
        }
    }

    private fun load() {
        if (!profileVerified) return
        status.text = "Reading sources…"
        api.addons { list, error ->
            if (isFinishing || isDestroyed) return@addons
            if (list == null) {
                status.text = error ?: "The hub did not send its add-ons"
                return@addons
            }
            installed = list
            status.text = ""
            paint()
            // Directory discovery is optional so an older Hub never makes installed sources wait.
            // A reviewed source carries no upstream URL to the TV; the install call names only its id.
            api.reviewedAddons { choices, directoryError ->
                if (isFinishing || isDestroyed) return@reviewedAddons
                reviewed = choices
                reviewedDirectoryLoaded = true
                reviewedDirectoryError = directoryError
                paint()
            }
        }
    }

    /**
     * Build a cheap immutable presentation model on the main thread; the adapter calculates and
     * applies its diff away from the D-pad path. A caller changing a tab may name the exact chip
     * that should retain focus after the model changes.
     */
    private fun paint(preferredFocus: FocusAnchor? = null) {
        val installedSources = installed ?: return
        // RecyclerView owns row lifecycle. Rebuild only this cheap immutable model, then let DiffUtil
        // preserve/recycle visible holders rather than recreating the entire source screen.
        val restoreAnchor = preferredFocus ?: sourceAdapter.focusedAnchor()
        val items = ArrayList<BrowserRow>()

        val curated = reviewed
        val directorySources = reviewedSources()
        items.add(LaneControlRow(
            active = activeLane,
            discoverCount = directorySources.size,
            installedCount = installedSources.length(),
        ))
        when (activeLane) {
            SourceLane.DISCOVER -> {
                items.add(SectionRow("discover", "Discover", "Curated sources and a separate non-endorsed Community directory"))
                items.add(DiscoverControlRow(
                    active = activeDiscoverCatalog,
                    curatedCount = directorySources.size,
                    communityCount = if (communityDirectoryLoaded) communitySources().size else null,
                ))
                when (activeDiscoverCatalog) {
                    DiscoverCatalog.CURATED -> {
                        if (curated != null && directorySources.isNotEmpty()) {
                            normalizeDirectoryFilters(directorySources)
                            val matchingSources = directorySources.filter(::matchesDirectoryFilter)
                            items.add(DirectoryControlRow(
                                key = "curated:filters",
                                title = "Curated filters",
                                summary = directoryFilterSummary(directorySources),
                                visible = matchingSources.size,
                                total = directorySources.size,
                                filtered = hasDirectoryFilters(),
                                actionDescription = "Press Select to filter curated sources",
                                action = ::showDirectoryFilters,
                            ))
                            if (matchingSources.isEmpty()) {
                                items.add(SourceRow(
                                    key = "directory:empty",
                                    title = "No matching sources",
                                    kind = "Curated",
                                    badges = listOf("Empty"),
                                    detail = "Clear filters or try a different search.",
                                    capabilities = "",
                                    actionLabel = "Reset",
                                    action = {
                                        clearDirectoryFilters()
                                        paint()
                                    },
                                ))
                            } else {
                                for (source in matchingSources) {
                                    val id = source.optString("id").trim()
                                    if (id.isBlank()) continue
                                    val alreadyInstalled = source.optBoolean("installed", false)
                                    val installUnavailable = reviewedInstallUnavailable(source)
                                    items.add(SourceRow(
                                        key = "reviewed:$id",
                                        title = source.optString("name", id),
                                        kind = "Hub reviewed",
                                        badges = listOf(
                                            when {
                                                alreadyInstalled -> "Installed"
                                                installUnavailable -> "Setup"
                                                else -> "Reviewed"
                                            },
                                        ),
                                        detail = reviewedCardDetail(source),
                                        capabilities = reviewedDirectoryCapabilities(source),
                                        actionLabel = if (alreadyInstalled || installUnavailable) "Details" else "View details",
                                        action = { showReviewedInstall(source, id, source.optString("name", id)) },
                                    ))
                                }
                            }
                        } else if (!reviewedDirectoryLoaded) {
                            items.add(NoticeRow(
                                key = "directory:pending",
                                title = "Curated directory",
                                message = "Checking the curated directory supplied by this Hub…",
                            ))
                        } else {
                            val message = if (reviewedDirectoryError.isNullOrBlank()) {
                                "No curated sources are included in this build yet. Community and manual sources remain separate."
                            } else {
                                "This Hub does not currently provide a curated directory. Community and manual sources remain separate."
                            }
                            items.add(NoticeRow(
                                key = "directory:empty-state",
                                title = "Curated directory",
                                message = message,
                            ))
                        }
                    }
                    DiscoverCatalog.COMMUNITY -> {
                        val communitySources = communitySources()
                        if (!communityDirectoryLoaded) {
                            items.add(NoticeRow(
                                key = "community:pending",
                                title = "Community directory",
                                message = "Loading community sources. They are not reviewed or endorsed by this Hub.",
                            ))
                        } else if (!communityDirectoryContractIsSafe()) {
                            items.add(NoticeRow(
                                key = "community:unavailable",
                                title = "Community directory unavailable",
                                message = safeCommunityText(
                                    communityDirectoryError ?: "This Hub did not provide the required community acknowledgement.",
                                    180,
                                ),
                            ))
                        } else {
                            communityNotice()?.let { notice ->
                                items.add(NoticeRow(
                                    key = "community:notice",
                                    title = "Community notice",
                                    message = notice,
                                ))
                            }
                            items.add(DirectoryControlRow(
                                key = "community:filters",
                                title = "Community filters",
                                summary = communityFilterSummary(),
                                visible = communitySources.size,
                                total = communitySources.size,
                                filtered = communityQuery.isNotBlank(),
                                actionDescription = "Press Select to change community type or search",
                                action = ::showCommunityFilters,
                            ))
                            if (communitySources.isEmpty()) {
                                items.add(NoticeRow(
                                    key = "community:empty",
                                    title = "No community sources",
                                    message = "No community sources match ${directoryTokenLabel(communityType)}. Change the type or search.",
                                ))
                            } else {
                                for (source in communitySources) {
                                    val id = source.optString("id").trim()
                                    items.add(SourceRow(
                                        key = "community:$id",
                                        title = communityDisplayName(source),
                                        kind = "Community source",
                                        badges = communitySourceBadges(source),
                                        detail = communityCardDetail(source),
                                        capabilities = communityCapabilities(source),
                                        actionLabel = "View details",
                                        action = { showCommunityDetail(source, id) },
                                    ))
                                }
                            }
                        }
                    }
                }
            }
            SourceLane.INSTALLED -> {
                items.add(SectionRow("installed", "Installed", libraryHealth(installedSources).summary))
                if (installedSources.length() == 0) {
                    items.add(NoticeRow(
                        key = "installed:empty",
                        title = "No installed sources",
                        message = "Use Discover for a reviewed source, or Advanced for a public manifest you trust.",
                    ))
                } else {
                    for (i in 0 until installedSources.length()) {
                        val addon = installedSources.optJSONObject(i) ?: continue
                        val name = addon.optString("name").ifBlank { addon.optString("id") }
                        val on = addon.optBoolean("enabled", true)
                        val native = addon.optBoolean("native", false)
                        val kind = when {
                            native -> "Built in"
                            addon.optString("provenance") == "reviewed" -> "Reviewed source"
                            addon.optString("provenance") == "community" -> "Community source"
                            else -> "Advanced manual source"
                        }
                        val state = sourceState(addon, kind, on, native)
                        items.add(SourceRow(
                            key = "installed:" + addon.optString("id"),
                            title = name,
                            kind = kind,
                            badges = listOf(state.value),
                            detail = state.note,
                            capabilities = capabilitySummary(addon),
                            actionLabel = "Manage",
                            action = { showActions(addon, name, on, native) },
                        ))
                    }
                }
            }
            SourceLane.ADVANCED -> {
                items.add(SectionRow("advanced", "Advanced", "Manual manifests are unreviewed and remain separate"))
                items.add(SourceRow(
                    key = "advanced:add",
                    title = "Add a source manually",
                    kind = "Manual manifest",
                    badges = listOf("Manual"),
                    detail = "Use only a public manifest address you trust.",
                    capabilities = "",
                    actionLabel = "Add source",
                    action = {
                        startActivityForResult(
                            Intent(this, TextEntryActivity::class.java)
                                .putExtra(TextEntryActivity.EXTRA_TITLE, "Add an advanced source")
                                .putExtra(TextEntryActivity.EXTRA_HINT, "https://…/manifest.json (public HTTP(S))"),
                            REQ_URL,
                        )
                    },
                ))
            }
        }
        sourceAdapter.submit(items, restoreAnchor, explicitFocus = preferredFocus != null)
    }

    private data class LibraryHealth(val summary: String)

    /** Uses cached Hub state only; drawing the library never contacts an upstream source. */
    private fun libraryHealth(list: JSONArray): LibraryHealth {
        var total = 0
        var ready = 0
        var paused = 0
        var attention = 0
        var starting = 0
        for (i in 0 until list.length()) {
            val source = list.optJSONObject(i) ?: continue
            total += 1
            val native = source.optBoolean("native", false)
            val detail = when {
                native -> "Built in"
                source.optString("provenance") == "reviewed" -> "Reviewed source"
                source.optString("provenance") == "community" -> "Community source"
                else -> "Advanced manual source"
            }
            when (sourceState(source, detail, source.optBoolean("enabled", true), native).value) {
                "Off" -> paused += 1
                "Attention" -> attention += 1
                "Starting" -> starting += 1
                else -> ready += 1
            }
        }
        val pieces = ArrayList<String>()
        pieces.add(if (total == 1) "1 source installed" else "$total sources installed")
        if (ready > 0) pieces.add(if (ready == 1) "1 available" else "$ready available")
        if (paused > 0) pieces.add(if (paused == 1) "1 paused" else "$paused paused")
        if (attention > 0) pieces.add(if (attention == 1) "1 needs attention" else "$attention need attention")
        if (starting > 0) pieces.add(if (starting == 1) "1 setting up" else "$starting setting up")
        return LibraryHealth(pieces.joinToString(" · "))
    }

    private sealed class BrowserRow(
        val key: String,
        val viewType: Int,
        val renderKey: String,
    )

    private class SectionRow(
        id: String,
        val title: String,
        val note: String,
    ) : BrowserRow("section:$id", ROW_SECTION, "$title\u0001$note")

    private class LaneControlRow(
        val active: SourceLane,
        val discoverCount: Int,
        val installedCount: Int,
    ) : BrowserRow(
        "source:lanes",
        ROW_LANE_CONTROL,
        "$active\u0001$discoverCount\u0001$installedCount",
    )

    private class DiscoverControlRow(
        val active: DiscoverCatalog,
        val curatedCount: Int,
        val communityCount: Int?,
    ) : BrowserRow(
        "discover:catalog",
        ROW_DISCOVER_CONTROL,
        "$active\u0001$curatedCount\u0001${communityCount ?: -1}",
    )

    private class DirectoryControlRow(
        key: String,
        val title: String,
        val summary: String,
        val visible: Int,
        val total: Int,
        val filtered: Boolean,
        val actionDescription: String,
        val action: () -> Unit,
    ) : BrowserRow(
        key,
        ROW_DIRECTORY_CONTROL,
        "$title\u0001$summary\u0001$visible\u0001$total\u0001$filtered\u0001$actionDescription",
    )

    private class SourceRow(
        key: String,
        val title: String,
        val kind: String,
        val badges: List<String>,
        val detail: String,
        val capabilities: String,
        val actionLabel: String,
        val action: () -> Unit,
    ) : BrowserRow(
        key,
        ROW_SOURCE,
        listOf(title, kind, badges.joinToString("\u0002"), detail, capabilities, actionLabel).joinToString("\u0001"),
    )

    private class NoticeRow(
        key: String,
        val title: String,
        val message: String,
    ) : BrowserRow(key, ROW_NOTICE, "$title\u0001$message")

    /** One source-list row plus, where relevant, the precise chip inside that row. */
    private data class FocusAnchor(val rowKey: String, val option: String? = null)

    /** A chip tag cannot collide with the holder's String row tag during focus recovery. */
    private data class TabFocusMarker(val rowKey: String, val option: String)

    /**
     * A visual tab and a remote stop are the same thing. The focus ring belongs to the FrameLayout
     * foreground so updating the label's active fill never erases it.
     */
    private class SourceTabChip(
        val root: FrameLayout,
        val label: TextView,
    ) {
        var action: (() -> Unit)? = null
    }

    /**
     * The directory uses the same native recycler pattern as Home and Live TV. Diffing data models
     * means a filter replaces only the changed visible holders; a 50-source directory never recreates
     * every source row or asks a ScrollView to relayout them all.
     */
    private inner class SourceBrowserAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        // Directory responses arrive on BrowseApi's main callback. DiffUtil is quadratic in the
        // worst case, so it must never calculate there: one large Community result must not eat a
        // remote frame. AsyncListDiffer also versions submissions; an older network result can never
        // overwrite a newer filter or steal its focus when it finishes late.
        private val diffExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "scenix-source-diff").apply { isDaemon = true }
        }
        private val differ = AsyncListDiffer(
            AdapterListUpdateCallback(this),
            AsyncDifferConfig.Builder(object : DiffUtil.ItemCallback<BrowserRow>() {
                override fun areItemsTheSame(oldItem: BrowserRow, newItem: BrowserRow): Boolean =
                    oldItem.key == newItem.key

                override fun areContentsTheSame(oldItem: BrowserRow, newItem: BrowserRow): Boolean =
                    oldItem.renderKey == newItem.renderKey
            }).setBackgroundThreadExecutor(diffExecutor).build(),
        )
        private var submissionVersion = 0L
        private var disposed = false
        private val items: List<BrowserRow>
            get() = differ.currentList

        init {
            setHasStableIds(true)
        }

        override fun getItemCount(): Int = items.size
        override fun getItemViewType(position: Int): Int = items[position].viewType
        override fun getItemId(position: Int): Long = stableRowId(items[position].key)

        fun focusedAnchor(): FocusAnchor? {
            var view = currentFocus ?: return null
            var tab: TabFocusMarker? = null
            while (true) {
                (view.tag as? TabFocusMarker)?.let { tab = it }
                val parent = view.parent
                if (parent === sourceList) {
                    val rowKey = view.tag as? String ?: tab?.rowKey ?: return null
                    return FocusAnchor(rowKey, tab?.option)
                }
                view = parent as? View ?: return null
            }
        }

        fun firstActionablePosition(): Int = items.indexOfFirst {
            it is LaneControlRow || it is DiscoverControlRow ||
                it is DirectoryControlRow || it is SourceRow
        }

        fun submit(next: List<BrowserRow>, restoreAnchor: FocusAnchor?, explicitFocus: Boolean) {
            if (disposed) return
            val version = ++submissionVersion
            // Never let a caller mutate the list while AsyncListDiffer owns it.
            val snapshot = next.toList()
            differ.submitList(snapshot) {
                if (disposed || version != submissionVersion || isFinishing || isDestroyed) return@submitList
                // A direct tab choice names its intended chip explicitly. Ordinary list updates
                // preserve the viewer's live destination instead, so a slow filter response cannot
                // pull the ring back to an old row.
                val candidate = if (explicitFocus) restoreAnchor else focusedAnchor() ?: restoreAnchor
                val wanted = resolveFocus(candidate)
                wanted?.let { restoreFocus(it, version) }
                if (needsInitialSourceFocus) {
                    sourceList.post {
                        if (!disposed && version == submissionVersion && needsInitialSourceFocus &&
                            !isFinishing && !isDestroyed
                        ) focusFirstSourceControl()
                    }
                }
            }
        }

        fun dispose() {
            disposed = true
            submissionVersion++
            diffExecutor.shutdownNow()
        }

        private fun resolveFocus(anchor: FocusAnchor?): FocusAnchor? {
            if (anchor == null) return null
            return when {
                items.any { it.key == anchor.rowKey } -> anchor
                items.any { it is DirectoryControlRow } -> FocusAnchor(
                    items.first { it is DirectoryControlRow }.key,
                )
                items.any { it.key == "discover:catalog" } -> FocusAnchor("discover:catalog")
                else -> FocusAnchor("source:lanes")
            }
        }

        private fun restoreFocus(anchor: FocusAnchor, version: Long) {
            val position = items.indexOfFirst { it.key == anchor.rowKey }
            if (position < 0) return
            sourceList.post {
                if (disposed || version != submissionVersion || isFinishing || isDestroyed) return@post
                if (!focusAt(position, anchor.option)) {
                    // Immediate positioning, never smooth scrolling: the next D-pad press owns motion.
                    sourceList.scrollToPosition(position)
                    sourceList.post {
                        if (!disposed && version == submissionVersion && !isFinishing && !isDestroyed) {
                            focusAt(position, anchor.option)
                        }
                    }
                }
            }
        }

        fun focusAt(position: Int, option: String? = null): Boolean {
            val holder = sourceList.findViewHolderForAdapterPosition(position) ?: return false
            val target = when (holder) {
                is LaneControlHolder -> holder.focusTarget(option)
                is DiscoverControlHolder -> holder.focusTarget(option)
                else -> holder.itemView.takeIf { it.isFocusable }
            }
            return target?.requestFocus() == true
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            ROW_LANE_CONTROL -> createLaneControlHolder()
            ROW_DISCOVER_CONTROL -> createDiscoverControlHolder()
            ROW_SECTION -> createSectionHolder()
            ROW_DIRECTORY_CONTROL -> createDirectoryControlHolder()
            ROW_SOURCE -> createSourceHolder()
            else -> createNoticeHolder()
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is LaneControlHolder -> bindLaneControl(holder, items[position] as LaneControlRow)
                is DiscoverControlHolder -> bindDiscoverControl(holder, items[position] as DiscoverControlRow)
                is SectionHolder -> bindSection(holder, items[position] as SectionRow)
                is DirectoryControlHolder -> bindDirectoryControl(holder, items[position] as DirectoryControlRow)
                is SourceHolder -> bindSource(holder, items[position] as SourceRow)
                is NoticeHolder -> bindNotice(holder, items[position] as NoticeRow)
            }
        }

        private fun createSectionHolder(): SectionHolder {
            val box = LinearLayout(this@AddonsActivity).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = false
                isClickable = false
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val title = Views.text(app, "rowTitle", 17f, app.theme.ink2, "", bold = true)
            title.setLetterSpacing(0.14f)
            box.addView(title)
            val note = Views.text(app, "meta", 15f, app.theme.ink3, "", maxLines = 1)
            box.addView(note, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = app.theme.px(5f) })
            return SectionHolder(box, title, note)
        }

        private fun bindSection(holder: SectionHolder, row: SectionRow) {
            holder.itemView.tag = row.key
            holder.title.text = row.title.uppercase()
            holder.note.text = row.note
            (holder.itemView.layoutParams as RecyclerView.LayoutParams).apply {
                topMargin = app.theme.px(if (row.key == "section:discover") 4f else 18f)
                bottomMargin = app.theme.px(8f)
            }
        }

        /**
         * The row's painted pills are real focus targets rather than one large button that opens a
         * second chooser. The ring rides this container's foreground; the inner label owns its
         * selected fill, so changing a tab never erases focus feedback.
         */
        private fun createSourceTabChip(): SourceTabChip {
            val radius = app.theme.radius("button", 20f)
            val root = FrameLayout(this@AddonsActivity).apply {
                minimumHeight = app.theme.px(44f)
                id = View.generateViewId()
                clipChildren = false
                clipToPadding = false
            }
            val label = Views.text(
                app,
                "liMeta",
                14f,
                app.theme.ink2,
                "",
                bold = true,
                gravity = Gravity.CENTER,
                maxLines = 1,
            ).apply {
                setPadding(app.theme.px(12f), app.theme.px(8f), app.theme.px(12f), app.theme.px(8f))
                isFocusable = false
                isClickable = false
                background = Views.rounded(app.theme.colorOr("chipBg", app.theme.surface2), radius)
            }
            root.addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            val chip = SourceTabChip(root, label)
            Focus.attach(
                root, root, app, radius,
                scale = false, animateScale = false, elevate = false,
                glowInset = app.theme.focusGlowInsetSm, control = true,
                onClick = { chip.action?.invoke() },
            )
            return chip
        }

        private fun wireTabFocus(vararg chips: SourceTabChip) {
            for (index in chips.indices) {
                chips[index].root.nextFocusLeftId = chips[(index - 1).coerceAtLeast(0)].root.id
                chips[index].root.nextFocusRightId = chips[(index + 1).coerceAtMost(chips.lastIndex)].root.id
            }
        }

        private fun bindTab(
            chip: SourceTabChip,
            rowKey: String,
            option: String,
            text: String,
            selected: Boolean,
            description: String,
            action: () -> Unit,
        ) {
            chip.root.tag = TabFocusMarker(rowKey, option)
            chip.root.contentDescription = if (selected) "$description, selected" else "$description. Press Select to open."
            chip.label.text = text
            chip.label.setTextColor(if (selected) app.theme.onBrand else app.theme.ink2)
            chip.label.background = Views.rounded(
                if (selected) app.theme.brand else app.theme.colorOr("chipBg", app.theme.surface2),
                app.theme.radius("button", 20f),
            )
            chip.action = action
        }

        private fun createLaneControlHolder(): LaneControlHolder {
            val root = LinearLayout(this@AddonsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = app.theme.space("listItem", 74f)
                background = libraryFill(emphasized = true)
                setPadding(app.theme.px(12f), app.theme.px(10f), app.theme.px(14f), app.theme.px(10f))
                clipChildren = false
                clipToPadding = false
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = app.theme.space("listGap", 10f) }
            }
            val lanes = LinearLayout(this@AddonsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            val discover = createSourceTabChip()
            val installed = createSourceTabChip()
            val advanced = createSourceTabChip()
            val chips = listOf(discover, installed, advanced)
            for ((index, chip) in chips.withIndex()) {
                lanes.addView(chip.root, LinearLayout.LayoutParams(0, app.theme.px(44f), 1f).apply {
                    if (index > 0) leftMargin = app.theme.px(6f)
                })
            }
            wireTabFocus(discover, installed, advanced)
            root.addView(lanes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return LaneControlHolder(root, discover, installed, advanced)
        }

        private fun bindLaneControl(holder: LaneControlHolder, row: LaneControlRow) {
            holder.itemView.tag = row.key
            bindTab(
                holder.discover,
                row.key,
                "discover",
                "DISCOVER · ${row.discoverCount}",
                row.active == SourceLane.DISCOVER,
                "Discover ${sourceCountLabel(row.discoverCount)}",
            ) { selectLane(SourceLane.DISCOVER) }
            bindTab(
                holder.installed,
                row.key,
                "installed",
                "INSTALLED · ${row.installedCount}",
                row.active == SourceLane.INSTALLED,
                "Installed ${sourceCountLabel(row.installedCount)}",
            ) { selectLane(SourceLane.INSTALLED) }
            bindTab(
                holder.advanced,
                row.key,
                "advanced",
                "ADVANCED",
                row.active == SourceLane.ADVANCED,
                "Advanced manual sources",
            ) { selectLane(SourceLane.ADVANCED) }
        }

        private fun createDiscoverControlHolder(): DiscoverControlHolder {
            val root = LinearLayout(this@AddonsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = app.theme.space("listItem", 68f)
                background = libraryFill()
                setPadding(app.theme.px(12f), app.theme.px(9f), app.theme.px(14f), app.theme.px(9f))
                clipChildren = false
                clipToPadding = false
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = app.theme.space("listGap", 8f) }
            }
            val catalogs = LinearLayout(this@AddonsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            val curated = createSourceTabChip()
            val community = createSourceTabChip()
            catalogs.addView(curated.root, LinearLayout.LayoutParams(0, app.theme.px(44f), 1f).apply {
                rightMargin = app.theme.px(6f)
            })
            catalogs.addView(community.root, LinearLayout.LayoutParams(0, app.theme.px(44f), 1f))
            wireTabFocus(curated, community)
            root.addView(catalogs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return DiscoverControlHolder(root, curated, community)
        }

        private fun bindDiscoverControl(holder: DiscoverControlHolder, row: DiscoverControlRow) {
            holder.itemView.tag = row.key
            bindTab(
                holder.curated,
                row.key,
                "curated",
                "CURATED · ${row.curatedCount}",
                row.active == DiscoverCatalog.CURATED,
                "Curated ${sourceCountLabel(row.curatedCount)}",
            ) { selectDiscoverCatalog(DiscoverCatalog.CURATED) }
            val communityLabel = row.communityCount?.let { "COMMUNITY · $it" } ?: "COMMUNITY"
            bindTab(
                holder.community,
                row.key,
                "community",
                communityLabel,
                row.active == DiscoverCatalog.COMMUNITY,
                "Community sources, not endorsed",
            ) { selectDiscoverCatalog(DiscoverCatalog.COMMUNITY) }
        }

        private fun createDirectoryControlHolder(): DirectoryControlHolder {
            val radius = app.theme.radius("listItem", 18f)
            val strip = LinearLayout(this@AddonsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = app.theme.space("listItem", 88f)
                background = libraryFill()
                setPadding(app.theme.px(24f), app.theme.px(10f), app.theme.px(22f), app.theme.px(10f))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = app.theme.space("listGap", 8f) }
                id = View.generateViewId()
            }
            val copy = LinearLayout(this@AddonsActivity).apply { orientation = LinearLayout.VERTICAL }
            val title = Views.text(app, "li", 21f, app.theme.ink, "", bold = true, maxLines = 1)
            val summary = Views.text(app, "meta", 15f, app.theme.ink2, "", maxLines = 1)
            copy.addView(title)
            copy.addView(summary, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = app.theme.px(4f) })
            strip.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val status = LinearLayout(this@AddonsActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            val count = Views.text(app, "liMeta", 18f, app.theme.ink, "", bold = true, gravity = Gravity.END)
            val action = Views.text(app, "meta", 13f, app.theme.ink3, "FILTER", bold = true, gravity = Gravity.END)
            status.addView(count)
            status.addView(action, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = app.theme.px(3f) })
            strip.addView(status, LinearLayout.LayoutParams(app.theme.px(118f), ViewGroup.LayoutParams.WRAP_CONTENT))
            val holder = DirectoryControlHolder(strip, title, summary, count, action)
            Focus.attach(
                strip, strip, app, radius,
                scale = false, animateScale = false, elevate = false,
                glowInset = app.theme.focusGlowInsetSm, control = true,
                onClick = { holder.action?.invoke() },
            )
            return holder
        }

        private fun bindDirectoryControl(holder: DirectoryControlHolder, row: DirectoryControlRow) {
            holder.itemView.tag = row.key
            holder.itemView.background = libraryFill(emphasized = row.filtered)
            holder.itemView.contentDescription = listOf(
                row.title, row.summary, "${row.visible} of ${sourceCountLabel(row.total)} shown",
                row.actionDescription,
            ).joinToString(". ")
            holder.title.text = row.title
            holder.summary.text = row.summary
            holder.count.text = "${row.visible}/${row.total}"
            holder.actionLabel.text = if (row.filtered) "FILTERED" else "FILTER"
            holder.action = row.action
        }

        private fun createSourceHolder(): SourceHolder {
            val radius = app.theme.radius("listItem", 18f)
            val root = LinearLayout(this@AddonsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = app.theme.space("listItem", 88f)
                background = libraryFill()
                setPadding(app.theme.px(24f), app.theme.px(11f), app.theme.px(22f), app.theme.px(11f))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = app.theme.space("listGap", 8f) }
                id = View.generateViewId()
            }
            val copy = LinearLayout(this@AddonsActivity).apply { orientation = LinearLayout.VERTICAL }
            val title = Views.text(app, "li", 22f, app.theme.ink, "", bold = true, maxLines = 1)
            val metadata = Views.text(app, "meta", 15f, app.theme.ink2, "", maxLines = 2)
            copy.addView(title)
            copy.addView(metadata, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = app.theme.px(4f) })
            root.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val controls = LinearLayout(this@AddonsActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                minimumWidth = app.theme.px(144f)
            }
            val badges = ArrayList<TextView>()
            repeat(3) {
                val badge = Views.text(app, "liMeta", 12f, app.theme.ink, "", bold = true, gravity = Gravity.CENTER, maxLines = 1)
                badge.setPadding(app.theme.px(10f), app.theme.px(4f), app.theme.px(10f), app.theme.px(4f))
                badge.visibility = View.GONE
                controls.addView(badge, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.END
                    if (badges.isNotEmpty()) topMargin = app.theme.px(3f)
                })
                badges.add(badge)
            }
            val action = Views.text(app, "meta", 13f, app.theme.ink3, "", bold = true, gravity = Gravity.END)
            controls.addView(action, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = app.theme.px(5f) })
            root.addView(controls, LinearLayout.LayoutParams(
                app.theme.px(150f),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = app.theme.px(16f) })
            val holder = SourceHolder(root, title, metadata, badges, action)
            Focus.attach(
                root, root, app, radius,
                scale = false, animateScale = false, elevate = false,
                glowInset = app.theme.focusGlowInsetSm, control = true,
                onClick = { holder.action?.invoke() },
            )
            return holder
        }

        private fun bindSource(holder: SourceHolder, row: SourceRow) {
            holder.itemView.tag = row.key
            holder.itemView.contentDescription = listOf(
                row.title, row.kind, row.badges.joinToString(", "), row.detail, row.capabilities,
                "Press Select to ${row.actionLabel}",
            ).filter { it.isNotBlank() }.joinToString(". ")
            holder.title.text = row.title
            holder.metadata.text = listOf(row.kind, row.detail, row.capabilities)
                .filter { it.isNotBlank() }.joinToString(" · ")
            for ((index, view) in holder.badges.withIndex()) {
                val badge = row.badges.getOrNull(index)
                if (badge == null) {
                    view.visibility = View.GONE
                } else {
                    view.visibility = View.VISIBLE
                    view.text = badge.uppercase()
                    view.setTextColor(badgeTextColor(badge))
                    view.background = badgeFill(badge)
                }
            }
            holder.actionLabel.text = row.actionLabel.uppercase()
            holder.action = row.action
        }

        private fun createNoticeHolder(): NoticeHolder {
            val box = LinearLayout(this@AddonsActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = libraryFill()
                setPadding(app.theme.px(24f), app.theme.px(12f), app.theme.px(24f), app.theme.px(12f))
                isFocusable = false
                isClickable = false
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = app.theme.space("listGap", 8f) }
            }
            val title = Views.text(app, "li", 19f, app.theme.ink2, "", bold = true, maxLines = 1)
            val message = Views.text(app, "meta", 15f, app.theme.ink3, "", maxLines = 3)
            box.addView(title)
            box.addView(message, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = app.theme.px(4f) })
            return NoticeHolder(box, title, message)
        }

        private fun bindNotice(holder: NoticeHolder, row: NoticeRow) {
            holder.itemView.tag = row.key
            holder.itemView.contentDescription = "$row.title. ${row.message}"
            holder.title.text = row.title
            holder.message.text = row.message
        }
    }

    private class SectionHolder(view: View, val title: TextView, val note: TextView) : RecyclerView.ViewHolder(view)
    private class LaneControlHolder(
        view: View,
        val discover: SourceTabChip,
        val installed: SourceTabChip,
        val advanced: SourceTabChip,
    ) : RecyclerView.ViewHolder(view) {
        fun focusTarget(option: String?): View = when (option) {
            "installed" -> installed.root
            "advanced" -> advanced.root
            else -> discover.root
        }
    }
    private class DiscoverControlHolder(
        view: View,
        val curated: SourceTabChip,
        val community: SourceTabChip,
    ) : RecyclerView.ViewHolder(view) {
        fun focusTarget(option: String?): View = when (option) {
            "community" -> community.root
            else -> curated.root
        }
    }
    private class DirectoryControlHolder(
        view: View,
        val title: TextView,
        val summary: TextView,
        val count: TextView,
        val actionLabel: TextView,
    ) : RecyclerView.ViewHolder(view) {
        var action: (() -> Unit)? = null
    }
    private class SourceHolder(
        view: View,
        val title: TextView,
        val metadata: TextView,
        val badges: List<TextView>,
        val actionLabel: TextView,
    ) : RecyclerView.ViewHolder(view) {
        var action: (() -> Unit)? = null
    }
    private class NoticeHolder(view: View, val title: TextView, val message: TextView) : RecyclerView.ViewHolder(view)

    private fun stableRowId(key: String): Long {
        var value = -3750763034362895579L
        for (character in key) value = (value xor character.code.toLong()) * 1099511628211L
        return value
    }

    private fun focusFirstSourceControl() {
        if (sourceAdapter.focusedAnchor() != null) {
            needsInitialSourceFocus = false
            return
        }
        val position = sourceAdapter.firstActionablePosition()
        if (position < 0) return
        sourceList.scrollToPosition(position)
        sourceList.post {
            if (!isFinishing && !isDestroyed && sourceAdapter.focusAt(position)) {
                needsInitialSourceFocus = false
            }
        }
    }

    private data class DirectoryChoice(val key: String, val label: String, val count: Int)

    /**
     * `reviewed` is the legacy projection from the Hub. Newer Hubs may enrich each safe entry with
     * directory metadata, but an entry with no metadata is still a legitimate reviewed source.
     * A non-reviewed trust declaration is never promoted just because it arrived in this array.
     */
    private fun reviewedSources(): List<JSONObject> {
        val choices = reviewed ?: return emptyList()
        val sources = ArrayList<JSONObject>()
        for (i in 0 until choices.length()) {
            val source = choices.optJSONObject(i) ?: continue
            val id = source.optString("id").trim()
            val trust = source.optString("trust").trim()
            if (id.isNotBlank() && (trust.isBlank() || trust.equals("reviewed", ignoreCase = true))) {
                sources.add(source)
            }
        }
        return sources
    }

    /** Reset only selections the newly supplied directory can no longer satisfy. */
    private fun normalizeDirectoryFilters(sources: List<JSONObject>) {
        val categories = directoryChoices(sources, ::reviewedCategories).map { it.key }.toSet()
        val types = directoryChoices(sources, ::reviewedTypes).map { it.key }.toSet()
        if (directoryCategory?.let { it !in categories } == true) directoryCategory = null
        if (directoryType?.let { it !in types } == true) directoryType = null
    }

    /** The Community contract is fail-closed: no acknowledgement envelope means no browse/install. */
    private fun communityDirectoryContractIsSafe(): Boolean {
        val directory = communityDirectory ?: return false
        val acknowledgement = directory.optJSONObject("acknowledgement") ?: return false
        return directory.optString("trust").equals("community", ignoreCase = true) &&
            directory.optBoolean("nonEndorsed", false) &&
            acknowledgement.optBoolean("required", false) &&
            acknowledgement.optString("field") == COMMUNITY_ACK_FIELD
    }

    /** Only opaque, explicitly non-endorsed Community rows from the Hub's envelope may render. */
    private fun communitySources(): List<JSONObject> {
        if (!communityDirectoryContractIsSafe()) return emptyList()
        val entries = communityDirectory?.optJSONArray("entries") ?: return emptyList()
        val sources = ArrayList<JSONObject>()
        for (i in 0 until entries.length()) {
            val source = entries.optJSONObject(i) ?: continue
            val id = source.optString("id").trim()
            if (
                id.isNotBlank() &&
                source.optString("trust").equals("community", ignoreCase = true) &&
                source.optBoolean("nonEndorsed", false)
            ) {
                sources.add(source)
            }
        }
        return sources
    }

    /** Hub-provided display copy is redacted defensively so a directory cannot surface an endpoint or secret. */
    private fun safeCommunityText(raw: String?, limit: Int): String {
        val compact = raw.orEmpty()
            .replace(Regex("(?i)\\b(?:https?|rtsp|smb|ftp)://\\S+"), "link omitted")
            .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b"), "endpoint omitted")
            .replace(Regex("(?i)\\b(?:api[_ -]?key|token|password|secret)\\s*[:=]\\s*\\S+"), "sensitive setup omitted")
            .replace(Regex("\\s+"), " ")
            .trim()
        return compact.take(limit)
    }

    private fun communityNotice(): String? = safeCommunityText(
        communityDirectory?.optString("notice"),
        COMMUNITY_NOTICE_MAX,
    ).takeIf { it.isNotBlank() }

    private fun communityTypeChoices(): List<String> {
        val values = LinkedHashSet<String>()
        val types = communityDirectory?.optJSONObject("filters")?.optJSONArray("types")
        if (types != null) {
            for (i in 0 until types.length()) {
                communityTypeKey(types.optString(i))?.let(values::add)
            }
        }
        if (values.isEmpty()) values.addAll(COMMUNITY_TYPES)
        return values.toList()
    }

    private fun communityTypeKey(value: String): String? = when (value.trim().lowercase()) {
        "movie", "series", "channel", "other" -> value.trim().lowercase()
        else -> null
    }

    private fun loadCommunityDirectory(preferredFocus: FocusAnchor? = null) {
        communityType = communityTypeKey(communityType) ?: COMMUNITY_DEFAULT_TYPE
        communityQuery = communityQuery.trim().take(COMMUNITY_QUERY_MAX)
        val requestType = communityType
        val requestQuery = communityQuery
        val requestVersion = ++communityRequestVersion
        communityDirectory = null
        communityDirectoryLoaded = false
        communityDirectoryError = null
        paint(preferredFocus)
        api.communityAddonDirectory(requestType, requestQuery) { directory, error ->
            if (isFinishing || isDestroyed || requestVersion != communityRequestVersion) return@communityAddonDirectory
            communityDirectory = directory
            communityDirectoryLoaded = true
            communityDirectoryError = error
            paint()
        }
    }

    private fun communityFilterSummary(): String = listOf(
        "Type: ${directoryTokenLabel(communityType)}",
        if (communityQuery.isBlank()) "Search: none" else "Search: \"${communityQuery.take(36)}\"",
    ).joinToString(" · ")

    /** Curated and Community are deliberately peer tabs inside Discover, never blended in a result list. */
    private fun selectDiscoverCatalog(catalog: DiscoverCatalog) {
        if (activeDiscoverCatalog == catalog) return
        activeDiscoverCatalog = catalog
        val focus = FocusAnchor("discover:catalog", catalog.name.lowercase())
        if (catalog == DiscoverCatalog.COMMUNITY && !communityDirectoryLoaded) {
            loadCommunityDirectory(focus)
        } else {
            paint(focus)
        }
    }

    private fun showCommunityFilters() {
        if (!communityDirectoryContractIsSafe()) return
        val options = ArrayList<Pair<String, String>>()
        options.add("type" to "Type · ${directoryTokenLabel(communityType)}")
        options.add(
            "search" to if (communityQuery.isBlank()) "Search community sources"
            else "Search · \"${communityQuery.take(48)}\"",
        )
        if (communityQuery.isNotBlank()) options.add("clear" to "Clear community search")
        options.add("back" to "Back")
        showPicker(
            "Browse community sources",
            options,
            "Community sources are not reviewed or endorsed by this Hub. ${communityNotice().orEmpty()}".trim(),
        ) { action ->
            when (action) {
                "type" -> showCommunityTypes()
                "search" -> startCommunitySearch()
                "clear" -> {
                    communityQuery = ""
                    loadCommunityDirectory()
                }
            }
        }
    }

    private fun showCommunityTypes() {
        val choices = communityTypeChoices()
        val options = ArrayList<Pair<String, String>>()
        for (type in choices) {
            options.add(
                "type:$type" to directoryPickerLabel(
                    directoryTokenLabel(type),
                    communityType == type,
                    null,
                ),
            )
        }
        options.add("back" to "Back")
        showPicker(
            "Choose a community type",
            options,
            "The Hub filters Community sources by one media type at a time.",
        ) { action ->
            if (!action.startsWith("type:")) return@showPicker
            communityType = communityTypeKey(action.removePrefix("type:")) ?: return@showPicker
            loadCommunityDirectory()
        }
    }

    private fun startCommunitySearch() {
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Search community sources")
                .putExtra(TextEntryActivity.EXTRA_VALUE, communityQuery)
                .putExtra(TextEntryActivity.EXTRA_HINT, "Name or capability — empty clears the search")
                .putExtra(TextEntryActivity.EXTRA_PLAIN_TEXT, true),
            REQ_COMMUNITY_SEARCH,
        )
    }

    /** A single lane is mounted at a time, avoiding the old stacked directory/library/manual page. */
    private fun selectLane(lane: SourceLane) {
        if (activeLane == lane) return
        activeLane = lane
        paint(FocusAnchor("source:lanes", lane.name.lowercase()))
    }

    /** The root chooser keeps category, type, and search as separate, discoverable remote actions. */
    private fun showDirectoryFilters() {
        val sources = reviewedSources()
        if (sources.isEmpty()) return
        normalizeDirectoryFilters(sources)
        val categoryLabel = directorySelectionLabel(
            directoryCategory,
            directoryChoices(sources, ::reviewedCategories),
            "All categories",
        )
        val typeLabel = directorySelectionLabel(
            directoryType,
            directoryChoices(sources, ::reviewedTypes),
            "All media types",
        )
        val options = ArrayList<Pair<String, String>>()
        options.add("category" to "Category · $categoryLabel")
        options.add("type" to "Media type · $typeLabel")
        options.add(
            "search" to if (directoryQuery.isBlank()) "Search reviewed sources"
            else "Search · \"${directoryQuery.take(48)}\"",
        )
        if (hasDirectoryFilters()) options.add("clear" to "Clear filters and search")
        options.add("back" to "Back")
        showPicker(
            "Browse reviewed sources",
            options,
            "Only sources reviewed by this Hub appear here. Choose a category or media type, then search by name or capability.",
        ) { action ->
            when (action) {
                "category" -> showDirectoryCategories()
                "type" -> showDirectoryTypes()
                "search" -> startDirectorySearch()
                "clear" -> {
                    clearDirectoryFilters()
                    paint()
                }
            }
        }
    }

    private fun showDirectoryCategories() {
        val choices = directoryChoices(reviewedSources(), ::reviewedCategories)
        val options = ArrayList<Pair<String, String>>()
        options.add("all" to directoryPickerLabel("All categories", directoryCategory == null, null))
        for (choice in choices) {
            options.add(
                "category:${choice.key}" to directoryPickerLabel(
                    choice.label,
                    directoryCategory == choice.key,
                    choice.count,
                ),
            )
        }
        options.add("back" to "Back")
        showPicker(
            "Choose a category",
            options,
            "Categories are supplied by this Hub with each reviewed source.",
        ) { action ->
            when {
                action == "all" -> {
                    directoryCategory = null
                    paint()
                }
                action.startsWith("category:") -> {
                    directoryCategory = action.removePrefix("category:")
                    paint()
                }
            }
        }
    }

    private fun showDirectoryTypes() {
        val choices = directoryChoices(reviewedSources(), ::reviewedTypes)
        val options = ArrayList<Pair<String, String>>()
        options.add("all" to directoryPickerLabel("All media types", directoryType == null, null))
        for (choice in choices) {
            options.add(
                "type:${choice.key}" to directoryPickerLabel(
                    choice.label,
                    directoryType == choice.key,
                    choice.count,
                ),
            )
        }
        options.add("back" to "Back")
        showPicker(
            "Choose a media type",
            options,
            "Media types come from a source's declared reviewed capabilities.",
        ) { action ->
            when {
                action == "all" -> {
                    directoryType = null
                    paint()
                }
                action.startsWith("type:") -> {
                    directoryType = action.removePrefix("type:")
                    paint()
                }
            }
        }
    }

    private fun startDirectorySearch() {
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Search reviewed sources")
                .putExtra(TextEntryActivity.EXTRA_VALUE, directoryQuery)
                .putExtra(TextEntryActivity.EXTRA_HINT, "Name, category, or capability — empty clears the search")
                .putExtra(TextEntryActivity.EXTRA_PLAIN_TEXT, true),
            REQ_DIRECTORY_SEARCH,
        )
    }

    private fun clearDirectoryFilters() {
        directoryCategory = null
        directoryType = null
        directoryQuery = ""
    }

    private fun hasDirectoryFilters(): Boolean =
        directoryCategory != null || directoryType != null || directoryQuery.isNotBlank()

    private fun matchesDirectoryFilter(source: JSONObject): Boolean {
        if (directoryCategory != null && reviewedCategories(source).none {
                directoryKey(it) == directoryCategory
            }) return false
        if (directoryType != null && reviewedTypes(source).none {
                directoryKey(it) == directoryType
            }) return false
        val query = directoryQuery.trim()
        return query.isBlank() || directorySearchText(source).contains(query, ignoreCase = true)
    }

    private fun directoryFilterSummary(sources: List<JSONObject>): String {
        val categories = directoryChoices(sources, ::reviewedCategories)
        val types = directoryChoices(sources, ::reviewedTypes)
        val parts = ArrayList<String>()
        parts.add("Category: " + directorySelectionLabel(directoryCategory, categories, "All"))
        parts.add("Type: " + directorySelectionLabel(directoryType, types, "All"))
        parts.add(if (directoryQuery.isBlank()) "Search: none" else "Search: \"${directoryQuery.take(36)}\"")
        return parts.joinToString(" · ")
    }

    private fun directorySelectionLabel(
        key: String?,
        choices: List<DirectoryChoice>,
        allLabel: String,
    ): String = if (key == null) {
        allLabel
    } else {
        choices.firstOrNull { it.key == key }?.label ?: directoryTokenLabel(key)
    }

    private fun directoryPickerLabel(label: String, selected: Boolean, count: Int?): String {
        val pieces = ArrayList<String>()
        pieces.add(label)
        count?.let { pieces.add(sourceCountLabel(it)) }
        if (selected) pieces.add("Current")
        return pieces.joinToString(" · ")
    }

    private fun sourceCountLabel(count: Int): String = if (count == 1) "1 source" else "$count sources"

    private fun directoryChoices(
        sources: List<JSONObject>,
        values: (JSONObject) -> List<String>,
    ): List<DirectoryChoice> {
        val labels = LinkedHashMap<String, String>()
        val counts = LinkedHashMap<String, Int>()
        for (source in sources) {
            val seen = HashSet<String>()
            for (value in values(source)) {
                val key = directoryKey(value)
                if (key.isBlank() || !seen.add(key)) continue
                if (!labels.containsKey(key)) labels[key] = directoryTokenLabel(value)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return labels.map { (key, label) -> DirectoryChoice(key, label, counts[key] ?: 0) }
            .sortedBy { it.label.lowercase() }
    }

    /** Reads optional modern metadata without making it mandatory for older registry rows. */
    private fun reviewedCategories(source: JSONObject): List<String> {
        val values = LinkedHashSet<String>()
        addDirectoryMetadataValues(source, arrayOf("categories", "category"), values)
        return values.toList()
    }

    private fun reviewedTypes(source: JSONObject): List<String> {
        val values = LinkedHashSet<String>()
        addDirectoryMetadataValues(source, arrayOf("types", "type"), values)
        val resources = source.optJSONArray("resources")
        if (resources != null) {
            for (i in 0 until resources.length()) {
                val resource = resources.optJSONObject(i) ?: continue
                addDirectoryValue(resource.opt("types"), values)
            }
        }
        return values.toList()
    }

    private fun addDirectoryMetadataValues(source: JSONObject, names: Array<String>, into: MutableSet<String>) {
        fun read(container: JSONObject) {
            for (name in names) addDirectoryValue(container.opt(name), into)
        }
        read(source)
        // The `directory` object is optional future metadata; unknown fields are deliberately ignored.
        source.optJSONObject("directory")?.let(::read)
    }

    private fun addDirectoryValue(value: Any?, into: MutableSet<String>) {
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) addDirectoryValue(value.opt(i), into)
            is String -> value.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { into.add(it) }
        }
    }

    private fun directorySearchText(source: JSONObject): String {
        val pieces = ArrayList<String>()
        pieces.add(source.optString("name"))
        pieces.add(source.optString("id"))
        pieces.add(source.optString("description"))
        pieces.add(source.optString("version"))
        pieces.addAll(reviewedCategories(source))
        pieces.addAll(reviewedTypes(source))
        val tags = LinkedHashSet<String>()
        addDirectoryMetadataValues(source, arrayOf("tags", "tag"), tags)
        pieces.addAll(tags)
        val resources = source.optJSONArray("resources")
        if (resources != null) {
            for (i in 0 until resources.length()) {
                resources.optJSONObject(i)?.optString("name")?.let { pieces.add(it) }
            }
        }
        return pieces.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun directoryKey(value: String): String = value.trim().lowercase()

    private fun directoryTokenLabel(value: String): String = when (directoryKey(value)) {
        "movie" -> "Movies"
        "series" -> "Series"
        "channel" -> "Channels"
        "other" -> "Other"
        else -> value.trim().ifBlank { "Other" }
    }

    private fun reviewedCardDetail(source: JSONObject): String = when {
        reviewedInstallUnavailable(source) -> reviewedInstallUnavailableLabel(source)
        else -> source.optString("description").trim().ifBlank { reviewedResourceSummary(source) }
    }

    private fun reviewedDirectoryCapabilities(source: JSONObject): String {
        val parts = ArrayList<String>()
        val categories = reviewedCategories(source)
        if (categories.isNotEmpty()) parts.add("Categories: " + categories.take(3).joinToString(", ") { directoryTokenLabel(it) })
        val types = reviewedTypes(source)
        if (types.isNotEmpty()) parts.add("Types: " + types.take(4).joinToString(", ") { directoryTokenLabel(it) })
        val resources = reviewedResourceSummary(source)
        if (resources != "Reviewed source") parts.add(resources)
        when {
            reviewedInstallUnavailable(source) -> parts.add(reviewedInstallUnavailableLabel(source))
            source.optBoolean("configurationRequired", false) -> parts.add("May need setup after install")
        }
        return parts.joinToString(" · ")
    }

    private fun reviewedSourceDetail(source: JSONObject): String {
        val details = ArrayList<String>()
        val version = source.optString("version").trim()
        details.add("Reviewed by this Hub" + if (version.isBlank()) "" else " · v$version")
        if (reviewedInstallUnavailable(source)) {
            details.add(reviewedInstallUnavailableDetail(source))
        } else {
            details.add("The Hub installs this by its reviewed ID and checks it before saving.")
        }
        source.optString("description").trim().takeIf { it.isNotBlank() }?.let { details.add(it) }
        val categories = reviewedCategories(source)
        if (categories.isNotEmpty()) details.add("Categories: " + categories.joinToString(", ") { directoryTokenLabel(it) })
        val types = reviewedTypes(source)
        if (types.isNotEmpty()) details.add("Media types: " + types.joinToString(", ") { directoryTokenLabel(it) })
        if (source.optBoolean("configurationRequired", false) && !reviewedInstallUnavailable(source)) {
            details.add("This source may need setup after installation.")
        }
        return details.joinToString("\n")
    }

    /** A configuration-required row stays non-installable; only the Hub's opaque web-code handoff opens it. */
    private fun reviewedInstallUnavailable(source: JSONObject): Boolean =
        source.optJSONObject("install")?.optBoolean("available", true) == false

    private fun sourceSetupAvailable(source: JSONObject): Boolean {
        val install = source.optJSONObject("install") ?: return false
        val setup = install.optJSONObject("setup") ?: return false
        return install.optString("reason") == "configuration-required" &&
            setup.optBoolean("available", false) && setup.optString("method") == "web-code"
    }

    private fun reviewedInstallUnavailableLabel(source: JSONObject): String = when (
        source.optJSONObject("install")?.optString("reason").orEmpty()
    ) {
        "configuration-required" -> if (sourceSetupAvailable(source)) {
            "Setup required · finish on web"
        } else {
            "Setup required · install unavailable"
        }
        else -> "Install unavailable on this TV"
    }

    private fun reviewedInstallUnavailableDetail(source: JSONObject): String = when (
        source.optJSONObject("install")?.optString("reason").orEmpty()
    ) {
        "configuration-required" -> if (sourceSetupAvailable(source)) {
            "This source needs browser setup. The TV can show an expiring code without exposing configuration details."
        } else {
            "This source needs setup, but this Hub has not provided a safe browser handoff yet."
        }
        else -> "This reviewed source is not available to install on this TV right now."
    }

    private fun communityDisplayName(source: JSONObject): String = safeCommunityText(
        source.optString("name"),
        96,
    ).ifBlank { "Community source" }

    /** These are disclosure badges, not quality markers: Community entries remain unendorsed. */
    private fun communitySourceBadges(source: JSONObject): List<String> {
        val badges = ArrayList<String>()
        badges.add("Unendorsed")
        val delivery = ArrayList<String>()
        if (source.optBoolean("adult", false)) delivery.add("Adult")
        if (source.optBoolean("p2p", false)) delivery.add("P2P")
        if (delivery.isNotEmpty()) badges.add(delivery.joinToString(" · "))
        when {
            source.optBoolean("configurationRequired", false) -> badges.add("Setup required")
            source.optBoolean("configurable", false) -> badges.add("Configurable")
            communityInstallUnavailable(source) -> badges.add("Unavailable")
        }
        return badges
    }

    private fun communityCardDetail(source: JSONObject): String = when {
        communityInstallUnavailable(source) -> communityInstallUnavailableLabel(source)
        else -> safeCommunityText(source.optString("description"), 150)
            .ifBlank { sourceResourceSummary(source, "Community source") }
    }

    private fun communityTypes(source: JSONObject): List<String> {
        val values = LinkedHashSet<String>()
        addDirectoryValue(source.opt("types"), values)
        return values.mapNotNull(::communityTypeKey)
    }

    private fun communityCapabilities(source: JSONObject): String {
        val parts = ArrayList<String>()
        val types = communityTypes(source)
        if (types.isNotEmpty()) parts.add("Types: " + types.joinToString(", ") { directoryTokenLabel(it) })
        val resources = sourceResourceSummary(source, "Community source")
        if (resources != "Community source") parts.add(resources)
        if (source.optBoolean("adult", false)) parts.add("Adult material")
        if (source.optBoolean("p2p", false)) parts.add("P2P")
        when {
            source.optBoolean("configurationRequired", false) -> parts.add("Setup required on another device")
            source.optBoolean("configurable", false) -> parts.add("May be configurable after install")
        }
        return parts.joinToString(" · ")
    }

    private fun communitySourceDetail(source: JSONObject): String {
        val details = ArrayList<String>()
        val version = source.optString("version").trim()
        details.add("Community source · not reviewed or endorsed by this Hub" + if (version.isBlank()) "" else " · v$version")
        communityNotice()?.let { details.add(it) }
        safeCommunityText(source.optString("description"), 360).takeIf { it.isNotBlank() }?.let(details::add)
        val types = communityTypes(source)
        if (types.isNotEmpty()) details.add("Media types: " + types.joinToString(", ") { directoryTokenLabel(it) })
        val resources = sourceResourceSummary(source, "Community source")
        if (resources != "Community source") details.add(resources)
        if (source.optBoolean("adult", false)) details.add("This source may expose adult material.")
        if (source.optBoolean("p2p", false)) details.add("This source may use peer-to-peer delivery.")
        if (communityInstallUnavailable(source)) {
            details.add(communityInstallUnavailableDetail(source))
        } else {
            details.add("Continue only if you understand this Community source is not endorsed by this Hub.")
        }
        return details.joinToString("\n")
    }

    /** Community setup remains blocked until both the non-endorsement contract and web-code handoff exist. */
    private fun communityInstallUnavailable(source: JSONObject): Boolean =
        !communityDirectoryContractIsSafe() ||
            source.optBoolean("configurationRequired", false) ||
            source.optJSONObject("install")?.optBoolean("available", true) == false

    private fun communityInstallUnavailableLabel(source: JSONObject): String = when {
        !communityDirectoryContractIsSafe() -> "Acknowledgement unavailable"
        source.optBoolean("configurationRequired", false) ||
            source.optJSONObject("install")?.optString("reason").orEmpty() == "configuration-required" ->
            if (sourceSetupAvailable(source)) "Setup required · finish on web"
            else "Setup required · install unavailable"
        else -> "Install unavailable on this TV"
    }

    private fun communityInstallUnavailableDetail(source: JSONObject): String = when {
        !communityDirectoryContractIsSafe() ->
            "This Hub did not provide the required Community acknowledgement, so installation is unavailable."
        source.optBoolean("configurationRequired", false) ||
            source.optJSONObject("install")?.optString("reason").orEmpty() == "configuration-required" ->
            if (sourceSetupAvailable(source)) {
                "This Community source needs browser setup. Its configuration stays off the TV and it remains unendorsed."
            } else {
                "This Community source needs setup, but this Hub has not provided a safe browser handoff yet."
            }
        else -> "This Community source is not available to install on this TV right now."
    }

    /** Detail answers must still name the same opaque Community id and carry the unendorsed trust. */
    private fun communityDetailEntry(root: JSONObject?, opaqueId: String): JSONObject? {
        val source = root?.optJSONObject("entry") ?: root ?: return null
        return source.takeIf {
            it.optString("id").trim() == opaqueId &&
                it.optString("trust").equals("community", ignoreCase = true) &&
                it.optBoolean("nonEndorsed", false)
        }
    }

    private fun libraryFill(emphasized: Boolean = false): GradientDrawable {
        // A summary card must keep the same readable surface in every pack. In translucent packs,
        // `surface2` may be intentionally light, which would make the normal light ink unreadable.
        val fill = app.theme.colorOr("listFill", app.theme.surface)
        val stroke = if (emphasized) app.theme.focusRingColor else app.theme.colorOr("listStroke", app.theme.line)
        return Views.rounded(
            fill,
            app.theme.radius("listItem", 18f),
            maxOf(1, app.theme.px(1f)),
            stroke,
        )
    }

    private fun badgeFill(badge: String): GradientDrawable {
        val attention = badge == "Attention"
        val fill = if (attention) app.theme.brand else app.theme.colorOr("chipFill", app.theme.surface)
        val stroke = if (attention) app.theme.brand else app.theme.colorOr("listStroke", app.theme.line)
        return Views.rounded(fill, app.theme.radius("button", 20f), maxOf(1, app.theme.px(1f)), stroke)
    }

    private fun badgeTextColor(badge: String): Int =
        if (badge == "Attention") app.theme.onBrand else app.theme.ink

    private fun capabilitySummary(ad: JSONObject): String {
        val capabilities = ArrayList<String>()
        val resources = ad.optJSONArray("resources")
        if (resources != null) {
            for (i in 0 until resources.length()) {
                val resource = when (val item = resources.opt(i)) {
                    is JSONObject -> item.optString("name")
                    else -> item?.toString().orEmpty()
                }
                resourceLabel(resource)?.let { capabilities.add(it) }
            }
        }
        val catalogCount = when (val catalogs = ad.opt("catalogs")) {
            is JSONArray -> catalogs.length()
            is Number -> catalogs.toInt()
            else -> 0
        }
        if (catalogCount > 0) {
            capabilities.add(if (catalogCount == 1) "1 catalogue" else "$catalogCount catalogues")
        }
        return capabilities.distinct().take(4).joinToString(" · ")
            .ifBlank { "Capabilities are awaiting their first Hub refresh" }
    }

    private fun resourceLabel(value: String): String? = when (value.trim().lowercase()) {
        "catalog", "catalogue", "catalogs" -> "Catalogues"
        "meta", "metadata" -> "Details"
        "stream", "streams" -> "Playback"
        "subtitles", "subtitle" -> "Subtitles"
        else -> null
    }

    /**
     * Source state is intentionally based only on what the Hub has already observed.  It never
     * reaches out to a provider while this page is drawn: an owner opening Settings must not cause
     * every source to wake up, and an upstream endpoint must not become a timing oracle for the TV.
     */
    private data class SourceState(val note: String, val value: String)

    private fun sourceState(ad: JSONObject, detail: String, on: Boolean, native: Boolean): SourceState {
        if (!on) return SourceState("$detail · kept here but not used", "Off")
        val error = if (ad.isNull("error")) "" else ad.optString("error").trim()
        if (error.isNotBlank()) {
            return SourceState("Needs attention · " + error.take(50), "Attention")
        }
        if (native) return SourceState(detail, "On")
        val lastFetch = ad.optJSONObject("counts")?.opt("lastFetch")
        val refreshed = when (lastFetch) {
            is Number -> lastFetch.toLong() > 0L
            is String -> lastFetch.isNotBlank() && !lastFetch.equals("null", ignoreCase = true)
            else -> false
        }
        return if (refreshed) {
            SourceState("$detail · last refresh is available", "Ready")
        } else {
            SourceState("$detail · waiting for its first refresh", "Starting")
        }
    }

    private fun sourceResourceSummary(source: JSONObject, fallback: String): String {
        val names = ArrayList<String>()
        val resources = source.optJSONArray("resources")
        if (resources != null) {
            for (i in 0 until resources.length()) {
                val resource = when (val item = resources.opt(i)) {
                    is JSONObject -> item.optString("name")
                    else -> item?.toString().orEmpty()
                }
                resourceLabel(resource)?.let { names.add(it) }
            }
        }
        return names.distinct().joinToString(" · ").ifBlank { fallback }
    }

    private fun reviewedResourceSummary(source: JSONObject): String = sourceResourceSummary(source, "Reviewed source")

    /** The Hub's explicit body flag is the only signal that opens a Settings-PIN recovery prompt. */
    private fun retryAfterSettingsUnlock(root: JSONObject?, retried: Boolean, retry: () -> Unit): Boolean {
        if (retried || root?.optBoolean("sessionRequired", false) != true) return false
        pendingSettingsRetry = retry
        status.text = "Settings PIN required"
        startActivityForResult(
            Intent(this, TextEntryActivity::class.java)
                .putExtra(TextEntryActivity.EXTRA_TITLE, "Settings PIN required")
                .putExtra(TextEntryActivity.EXTRA_HINT, "Enter the 4 to 8 digit household Settings PIN")
                .putExtra(TextEntryActivity.EXTRA_NUMERIC_PIN, true),
            REQ_SETTINGS_PIN,
        )
        return true
    }

    /** Every source mutation has the same one-visible-unlock, one-retry rule; no failure is replayed silently. */
    private fun installReviewed(id: String, retried: Boolean = false) {
        status.text = "Installing reviewed source…"
        api.addReviewedAddon(id) { root, error ->
            if (isFinishing || isDestroyed) return@addReviewedAddon
            if (error != null) {
                if (retryAfterSettingsUnlock(root, retried) { installReviewed(id, retried = true) }) return@addReviewedAddon
                status.text = error
                return@addReviewedAddon
            }
            installed = root?.optJSONArray("addons") ?: installed
            load()
        }
    }

    /** Called only after the two-step Community acknowledgement picker has been completed. */
    private fun installCommunity(opaqueId: String, retried: Boolean = false) {
        status.text = "Installing community source…"
        api.installCommunityAddon(opaqueId) { root, error ->
            if (isFinishing || isDestroyed) return@installCommunityAddon
            if (error != null) {
                if (retryAfterSettingsUnlock(root, retried) { installCommunity(opaqueId, retried = true) }) {
                    return@installCommunityAddon
                }
                status.text = safeCommunityText(error, 180).ifBlank { "The Community source could not be installed" }
                return@installCommunityAddon
            }
            communityDirectory?.optJSONArray("entries")?.let { entries ->
                for (i in 0 until entries.length()) {
                    entries.optJSONObject(i)?.takeIf { it.optString("id").trim() == opaqueId }
                        ?.put("installed", true)
                }
            }
            installed = root?.optJSONArray("addons") ?: installed
            load()
        }
    }

    /** Starts a one-time web code; source configuration never crosses this TV activity. */
    private fun startReviewedSetup(id: String, retried: Boolean = false) {
        status.text = "Preparing secure setup…"
        api.startReviewedSourceSetup(id) { root, error ->
            if (isFinishing || isDestroyed) return@startReviewedSourceSetup
            if (error != null) {
                if (retryAfterSettingsUnlock(root, retried) { startReviewedSetup(id, retried = true) }) return@startReviewedSourceSetup
                status.text = error
                return@startReviewedSourceSetup
            }
            val setup = safeSetupHandoff(root)
            if (setup == null) {
                status.text = "The Hub did not return a safe setup handoff"
                return@startReviewedSourceSetup
            }
            status.text = ""
            showSetupHandoff(setup)
        }
    }

    /** Community setup comes only after the separate acknowledgement path has reached this call. */
    private fun startCommunitySetup(opaqueId: String, retried: Boolean = false) {
        status.text = "Preparing secure Community setup…"
        api.startCommunitySourceSetup(opaqueId) { root, error ->
            if (isFinishing || isDestroyed) return@startCommunitySourceSetup
            if (error != null) {
                if (retryAfterSettingsUnlock(root, retried) { startCommunitySetup(opaqueId, retried = true) }) {
                    return@startCommunitySourceSetup
                }
                status.text = safeCommunityText(error, 180).ifBlank { "The Community setup could not be started" }
                return@startCommunitySourceSetup
            }
            val setup = safeSetupHandoff(root)
            if (setup == null) {
                status.text = "The Hub did not return a safe Community setup handoff"
                return@startCommunitySourceSetup
            }
            status.text = ""
            showSetupHandoff(setup)
        }
    }

    /** No raw configuration URL, manifest id, config label, or value is accepted from the handoff body. */
    private fun safeSetupHandoff(root: JSONObject?): JSONObject? {
        val setup = root?.optJSONObject("setup") ?: return null
        val code = setup.optString("code").trim()
        val source = setup.optJSONObject("source") ?: return null
        val trust = source.optString("trust").trim().lowercase()
        val name = safeCommunityText(source.optString("name"), 96)
        val state = setup.optString("state", "pending").trim().lowercase()
        if (!SETUP_CODE.matches(code) || name.isBlank() || trust !in setOf("reviewed", "community") ||
            state !in setOf("pending", "completed")) return null
        return setup
    }

    /** The receipt is status-only: it tells the couch whether the web completed, never what was entered there. */
    private fun showSetupHandoff(setup: JSONObject) {
        val code = setup.optString("code").trim()
        val source = setup.optJSONObject("source")
        val name = safeCommunityText(source?.optString("name"), 96).ifBlank { "source" }
        val trust = source?.optString("trust").orEmpty().lowercase()
        val acknowledgement = if (trust == "community") {
            " Community sources remain unreviewed and unendorsed."
        } else {
            ""
        }
        showPicker(
            "Finish setup on web",
            listOf("check" to "Check setup status", "back" to "Back"),
            "Code: $code\nOn a phone or computer: Settings → Access & safety → Sources → Finish a source setup code. Enter this code, complete setup, then choose Check setup status. The TV never receives configuration details.$acknowledgement",
        ) { action ->
            if (action == "check") checkSourceSetup(code, name)
        }
    }

    private fun checkSourceSetup(code: String, name: String) {
        status.text = "Checking web setup…"
        api.sourceSetupStatus(code) { root, error ->
            if (isFinishing || isDestroyed) return@sourceSetupStatus
            val setup = safeSetupHandoff(root)
            if (error != null || setup == null) {
                status.text = safeCommunityText(error, 180).ifBlank { "This setup code is unavailable or expired" }
                return@sourceSetupStatus
            }
            if (setup.optString("state").equals("completed", ignoreCase = true)) {
                status.text = "$name is configured on this Hub"
                load()
            } else {
                status.text = "Setup is still waiting on the web"
                showSetupHandoff(setup)
            }
        }
    }

    private fun setAddonEnabled(id: String, on: Boolean, retried: Boolean = false) {
        status.text = "Saving…"
        api.setAddon(id, on) { root, error ->
            if (isFinishing || isDestroyed) return@setAddon
            if (error != null) {
                if (retryAfterSettingsUnlock(root, retried) { setAddonEnabled(id, on, retried = true) }) return@setAddon
                status.text = error
                return@setAddon
            }
            load()
        }
    }

    private fun removeAddon(id: String, retried: Boolean = false) {
        status.text = "Removing…"
        api.removeAddon(id) response@{ root, error ->
            if (isFinishing || isDestroyed) return@response
            if (error != null) {
                if (retryAfterSettingsUnlock(root, retried) { removeAddon(id, retried = true) }) return@response
                status.text = error
                return@response
            }
            installed = root?.optJSONArray("addons") ?: installed
            load()
        }
    }

    private fun saveCatalogName(names: JSONObject, retried: Boolean = false) {
        status.text = "Saving…"
        api.saveSettings(JSONObject().put("settings", JSONObject().put("addonCatalogNames", names))) { root, error ->
            if (isFinishing || isDestroyed) return@saveSettings
            if (error != null) {
                if (retryAfterSettingsUnlock(root, retried) { saveCatalogName(names, retried = true) }) return@saveSettings
                status.text = error
                return@saveSettings
            }
            status.text = "Renamed. It shows on Home next time."
        }
    }

    private fun addAdvancedSource(url: String, retried: Boolean = false) {
        status.text = "Adding…"
        api.addAddon(url) { root, error ->
            if (isFinishing || isDestroyed) return@addAddon
            if (error != null) {
                if (retryAfterSettingsUnlock(root, retried) { addAdvancedSource(url, retried = true) }) return@addAddon
                status.text = error
                return@addAddon
            }
            installed = root?.optJSONArray("addons") ?: installed
            load()
        }
    }

    private fun showReviewedInstall(source: JSONObject, id: String, name: String) {
        val options = ArrayList<Pair<String, String>>()
        if (!source.optBoolean("installed", false)) {
            when {
                sourceSetupAvailable(source) -> options.add("setup" to "Finish setup on web")
                !reviewedInstallUnavailable(source) -> options.add("install" to "Install reviewed source")
            }
        }
        options.add("back" to "Back")
        showPicker(name, options, reviewedSourceDetail(source)) { action ->
            when (action) {
                "install" -> installReviewed(id)
                "setup" -> startReviewedSetup(id)
            }
        }
    }

    /** A Community list card is never installable directly; load its opaque detail before consent. */
    private fun showCommunityDetail(summary: JSONObject, opaqueId: String) {
        status.text = "Reading community source…"
        api.communityAddonDetail(opaqueId) { root, error ->
            if (isFinishing || isDestroyed) return@communityAddonDetail
            val source = communityDetailEntry(root, opaqueId)
            if (error != null || source == null) {
                status.text = safeCommunityText(error, 180).ifBlank {
                    "That Community source is unavailable"
                }
                return@communityAddonDetail
            }
            status.text = ""
            showCommunityAcknowledgement(
                source,
                opaqueId,
                communityDisplayName(source).ifBlank { communityDisplayName(summary) },
            )
        }
    }

    /** The acknowledgement is a separate remote action from the final opaque-ID installation. */
    private fun showCommunityAcknowledgement(source: JSONObject, opaqueId: String, name: String) {
        val canInstall = !source.optBoolean("installed", false) && !communityInstallUnavailable(source)
        val canSetup = !source.optBoolean("installed", false) &&
            communityDirectoryContractIsSafe() && sourceSetupAvailable(source)
        val options = ArrayList<Pair<String, String>>()
        if (canInstall || canSetup) options.add("acknowledge" to "I understand — continue")
        options.add("back" to "Back")
        showPicker(name, options, communitySourceDetail(source)) { action ->
            if (action != "acknowledge") return@showPicker
            showPicker(
                "Acknowledge Community source",
                listOf(
                    if (canSetup) "setup" to "Finish setup on web" else "install" to "Install community source",
                    "back" to "Back",
                ),
                if (canSetup) {
                    "This source is from the Community directory and is not reviewed or endorsed by this Hub. Starting browser setup confirms that you understand; configuration details stay off the TV."
                } else {
                    "This source is from the Community directory and is not reviewed or endorsed by this Hub. Selecting Install confirms that you understand."
                },
            ) { confirmation ->
                when (confirmation) {
                    "install" -> installCommunity(opaqueId)
                    "setup" -> startCommunitySetup(opaqueId)
                }
            }
        }
    }

    private fun showActions(ad: JSONObject, name: String, on: Boolean, native: Boolean) {
        val options = ArrayList<Pair<String, String>>()
        options.add("toggle" to (if (on) "Turn $name off" else "Turn $name on"))
        if (!native) {
            val cats = ad.optJSONArray("catalogs")
            if (cats != null && cats.length() > 0) options.add("rename" to "Rename catalogue tiles")
            options.add("remove" to "Remove $name")
        }
        options.add("back" to "Back")
        showPicker(name, options) { action ->
            when (action) {
                "toggle" -> {
                    val id = ad.optString("id")
                    setAddonEnabled(id, !on)
                }
                "rename" -> {
                    val cats = ad.optJSONArray("catalogs") ?: return@showPicker
                    val choices = ArrayList<Pair<String, String>>()
                    for (i in 0 until cats.length()) {
                        val c = cats.optJSONObject(i) ?: continue
                        val id = c.optString("id")
                        if (id.isBlank()) continue
                        choices.add(id to c.optString("name", id))
                    }
                    if (choices.isEmpty()) return@showPicker
                    showPicker("Rename which shelf?", choices) { catalogId ->
                        val current = choices.firstOrNull { it.first == catalogId }?.second.orEmpty()
                        renameAddon = ad.optString("id")
                        renameCatalog = catalogId
                        startActivityForResult(
                            Intent(this, TextEntryActivity::class.java)
                                .putExtra(TextEntryActivity.EXTRA_TITLE, "Rename " + current)
                                .putExtra(TextEntryActivity.EXTRA_VALUE, current)
                                .putExtra(TextEntryActivity.EXTRA_HINT, "The name this shelf wears on Home"),
                            REQ_CATALOG_NAME,
                        )
                    }
                }
                "remove" -> {
                    val id = ad.optString("id")
                    removeAddon(id)
                }
            }
        }
    }

    private fun showPicker(
        title: String,
        options: List<Pair<String, String>>,
        detail: String = "",
        onPick: (String) -> Unit,
    ) {
        if (picker == null) pickerReturn = currentFocus
        closePicker()
        pickerReturn = currentFocus ?: pickerReturn
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Screens.dimColor(app))
            // Absorb touch/clicks on the veil as well as owning the focused picker descendants.
            // There is intentionally no click-to-dismiss: Back has one predictable meaning on TV.
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.theme.px(26f), app.theme.px(20f), app.theme.px(26f), app.theme.px(20f))
            background = Screens.modalFill(app)
        }
        panel.addView(Views.text(app, "li", 25f, app.theme.ink, title, bold = true, maxLines = 2))
        if (detail.isNotBlank()) {
            panel.addView(
                Views.text(app, "meta", 17f, app.theme.ink2, detail, maxLines = 5),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.theme.px(8f)
                },
            )
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pickerItems = ArrayList<View>()
        for ((value, label) in options) {
            val row = Screens.pickerRow(this, app, label, selected = false) {
                closePicker()
                onPick(value)
            }
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                app.theme.px(58f),
            ).apply { topMargin = app.theme.px(4f) })
            pickerItems.add(row)
        }
        // A source may expose far more catalogues than fit on a 1080p panel.  The ScrollView itself
        // never owns focus; a focused option asks it to scroll into view while the explicit D-pad
        // route below keeps the ring inside the modal.
        val maxListHeight = if (detail.isBlank()) MAX_PICKER_LIST_HEIGHT else MAX_PICKER_LIST_HEIGHT_WITH_DETAIL
        val listHeight = pickerItems.size * app.theme.px(62f)
        if (listHeight > app.theme.px(maxListHeight)) {
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isFocusable = false
                isFocusableInTouchMode = false
            }
            scroll.addView(list)
            panel.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                app.theme.px(maxListHeight),
            ).apply { topMargin = app.theme.px(8f) })
        } else {
            panel.addView(list, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = app.theme.px(8f) })
        }
        layer.addView(panel, FrameLayout.LayoutParams(app.theme.px(640f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        addContentView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        picker = layer
        pickerRows = pickerItems
        pickerItems.firstOrNull()?.let { first ->
            first.post { first.requestFocus() }
        }
    }

    private fun closePicker() {
        picker?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val wasOpen = picker != null
        picker = null
        if (wasOpen) {
            pickerReturn?.takeIf { it.isAttachedToWindow }?.requestFocus()
            pickerReturn = null
        }
        pickerRows = emptyList()
    }

    /** Keep D-pad focus in the modal. The outer list must never become an accidental choice. */
    private fun movePicker(delta: Int): Boolean {
        if (pickerRows.isEmpty()) return false
        val at = pickerRows.indexOfFirst { it.isFocused }
        val next = if (at < 0) 0 else (at + delta).coerceIn(0, pickerRows.lastIndex)
        if (next == at) return true
        pickerRows[next].requestFocus()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (picker != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    closePicker()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> return movePicker(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> return movePicker(1)
                // Picker options are a one-column list.  Consuming lateral movement prevents a
                // FocusFinder escape to the background when an option is near a page edge.
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // Held-key autorepeat must not click a picker option again (a toggle row would
                    // flip back and forth under a held button).
                    if (event.repeatCount > 0) return true
                    pickerRows.firstOrNull { it.isFocused }?.let {
                        it.performClick()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SETTINGS_PIN) {
            val retry = pendingSettingsRetry
            pendingSettingsRetry = null
            if (resultCode != RESULT_OK || retry == null) {
                status.text = "Settings PIN is needed to change sources"
                return
            }
            val pin = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
            if (!pin.matches(Regex("^\\d{4,8}$"))) {
                status.text = "Use 4 to 8 digits for the Settings PIN"
                // The entry was invalid locally, so nothing has been sent and no source action is retried.
                pendingSettingsRetry = retry
                retryAfterSettingsUnlock(JSONObject().put("sessionRequired", true), false, retry)
                return
            }
            status.text = "Checking Settings PIN…"
            api.unlockSettings(pin) { ok, error ->
                if (isFinishing || isDestroyed) return@unlockSettings
                if (ok) {
                    status.text = ""
                    retry()
                    return@unlockSettings
                }
                status.text = error ?: "That Settings PIN is not right"
                // A wrong number gets a clear retry path. The hub's rate-limit response stops here,
                // so the screen never loops a person straight back into an unavailable prompt.
                if (error?.contains("too many", ignoreCase = true) != true) {
                    pendingSettingsRetry = retry
                    retryAfterSettingsUnlock(JSONObject().put("sessionRequired", true), false, retry)
                }
            }
            return
        }
        if (requestCode == REQ_CATALOG_NAME) {
            if (resultCode != RESULT_OK) return
            val name = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
            val addon = renameAddon ?: return
            val catalog = renameCatalog ?: return
            if (name.isBlank()) return
            status.text = "Saving…"
            val names = JSONObject().put("$addon:$catalog", name.take(60))
            saveCatalogName(names)
            return
        }
        if (requestCode == REQ_DIRECTORY_SEARCH) {
            if (resultCode != RESULT_OK) return
            directoryQuery = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim().take(96)
            paint()
            return
        }
        if (requestCode == REQ_COMMUNITY_SEARCH) {
            if (resultCode != RESULT_OK) return
            communityQuery = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim().take(COMMUNITY_QUERY_MAX)
            loadCommunityDirectory()
            return
        }
        if (requestCode != REQ_URL || resultCode != RESULT_OK) return
        val url = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT).orEmpty().trim()
        if (url.isBlank()) return
        addAdvancedSource(url)
    }

    override fun onBackPressed() {
        if (picker != null) { closePicker(); return }
        super.onBackPressed()
    }

    companion object {
        /** Required trusted-adult identity for every source-directory entry point. */
        const val EXTRA_PROFILE = "profile"
        private const val ROW_LANE_CONTROL = 1
        private const val ROW_SECTION = 2
        private const val ROW_DIRECTORY_CONTROL = 3
        private const val ROW_SOURCE = 4
        private const val ROW_NOTICE = 5
        private const val ROW_DISCOVER_CONTROL = 6
        private const val REQ_URL = 41
        private const val REQ_CATALOG_NAME = 42
        private const val REQ_SETTINGS_PIN = 43
        private const val REQ_DIRECTORY_SEARCH = 44
        private const val REQ_COMMUNITY_SEARCH = 45
        private const val COMMUNITY_DEFAULT_TYPE = "movie"
        private const val COMMUNITY_QUERY_MAX = 80
        private const val COMMUNITY_NOTICE_MAX = 280
        private const val COMMUNITY_ACK_FIELD = "nonEndorsementAcknowledged"
        /** Twelve base32 characters = a short couch-readable code while remaining one-time and expiring. */
        private val SETUP_CODE = Regex("^SCX-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$")
        private val COMMUNITY_TYPES = listOf("movie", "series", "channel", "other")
        /** Scaled logical pixels; leaves room for title and padding on every supported TV canvas. */
        private const val MAX_PICKER_LIST_HEIGHT = 560f
        /** Detail/consent copy gets its own vertical budget before a long picker starts scrolling. */
        private const val MAX_PICKER_LIST_HEIGHT_WITH_DETAIL = 430f
    }
}
