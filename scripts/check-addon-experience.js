'use strict';
/*
 * Guard the source browser's safety boundary and its TV performance structure. The shipped
 * reviewed registry may be populated or empty; this gate tests the experience shape, not data.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const tv = fs.readFileSync(path.join(root, 'app/src/main/java/com/scenix/tv/AddonsActivity.kt'), 'utf8');
const browseApi = fs.readFileSync(path.join(root, 'app/src/main/java/com/scenix/tv/BrowseApi.kt'), 'utf8');
const web = fs.readFileSync(path.join(root, 'hub/web/js/screens/settings.js'), 'utf8');

// Dense, virtualized TV list: filtering must update models rather than rebuilding a ScrollView.
assert.match(tv, /private lateinit var sourceList: RecyclerView/,
  'TV must render the source browser through RecyclerView');
assert.match(tv, /setHasStableIds\(true\)/,
  'source rows must retain stable identities through filters and lane changes');
assert.match(tv, /AsyncListDiffer/,
  'source updates must diff immutable row models instead of recreating every row');
assert.match(tv, /setBackgroundThreadExecutor\(diffExecutor\)/,
  'directory diff work must stay off the D-pad/main thread');
assert.match(tv, /differ\.submitList\(snapshot\)/,
  'source models must use versioned async list submission');
assert.match(tv, /setItemViewCacheSize\(18\)/,
  'the dense source browser must keep nearby TV rows warm');
assert.match(tv, /itemAnimator = null/,
  'source rows must not animate layout changes while navigating with a remote');
assert.match(tv, /sourceList\.scrollToPosition/,
  'focus recovery must use immediate list positioning, not smooth scrolling');
assert.doesNotMatch(tv, /private lateinit var rows:/,
  'the source browser must not regress to the old stacked rows container');
assert.doesNotMatch(tv, /appendFocusableCard|addLibraryCard|Focus\.focusFirst\(rows\)/,
  'the source browser must not retain old card-stack focus plumbing');
assert.match(tv, /Focus\.attach\([\s\S]*?scale = false,[\s\S]*?animateScale = false,[\s\S]*?elevate = false/,
  'dense source controls must retain theme focus without costly scale or elevation animation');
assert.match(tv, /private data class FocusAnchor/,
  'a filtered or switched lane must preserve its row and nested-tab D-pad target');
assert.match(tv, /fun focusedAnchor\(\): FocusAnchor\?/,
  'source focus recovery must preserve a stable row and chip target');
assert.match(tv, /"source:lanes"/,
  'the lane control must provide a safe fallback focus target');

// One lane at a time makes reviewed discovery, installed sources, and manual manifests distinct.
assert.match(tv, /enum class SourceLane[\s\S]*DISCOVER[\s\S]*INSTALLED[\s\S]*ADVANCED/,
  'the primary source selector must expose Discover, Installed, and Advanced lanes');
assert.match(tv, /private fun selectLane\(lane: SourceLane\)/,
  'TV must provide direct primary lane selection without a modal detour');
assert.match(tv, /private fun createSourceTabChip\(\): SourceTabChip/,
  'the visible source lanes must be real remote-focusable chips');
assert.match(tv, /wireTabFocus\(discover, installed, advanced\)/,
  'Discover, Installed, and Advanced must have explicit left/right remote traversal');
assert.match(tv, /when \(activeLane\)/,
  'only the selected lane should be rendered at a time');
assert.match(tv, /Curated sources and a separate non-endorsed Community directory/,
  'Discover must explain its separate curated and community boundaries');
assert.match(tv, /Manual manifests are unreviewed and remain separate/,
  'manual manifests must remain visibly distinct from reviewed sources');
assert.match(tv, /TextEntryActivity\.EXTRA_TITLE, "Add an advanced source"/,
  'manual source entry must remain a dedicated advanced route');

// Discover controls and detail/consent remain first-class remote actions.
assert.match(tv, /private fun showDirectoryFilters\(\)/,
  'TV must provide a dedicated reviewed-directory filter chooser');
assert.match(tv, /private fun showDirectoryCategories\(\)/,
  'TV must let a remote user choose a reviewed-source category');
assert.match(tv, /private fun showDirectoryTypes\(\)/,
  'TV must let a remote user choose a reviewed-source media type');
assert.match(tv, /directorySources\.filter\(::matchesDirectoryFilter\)/,
  'TV must apply selected category, type, and query before source rows render');
assert.match(tv, /TextEntryActivity\.EXTRA_TITLE, "Search reviewed sources"/,
  'TV search must use the native text-entry surface');
assert.match(tv, /REQ_DIRECTORY_SEARCH/,
  'directory search must return to the library without losing the remote route');
assert.match(tv, /private fun reviewedSourceDetail\(source: JSONObject\): String/,
  'a reviewed source must have a detail/consent view before installation');
assert.match(tv, /The Hub installs this by its reviewed ID and checks it before saving\./,
  'consent copy must explain the reviewed install boundary');
assert.match(tv, /"install" to "Install reviewed source"/,
  'installation must remain an explicit action');

// Community discovery is a separate, non-endorsed catalogue within Discover.
assert.match(tv, /enum class DiscoverCatalog[\s\S]*CURATED[\s\S]*COMMUNITY/,
  'Discover must expose an explicit Curated/Community selector');
assert.match(tv, /private fun selectDiscoverCatalog\(catalog: DiscoverCatalog\)/,
  'Curated and Community selection must be a direct remote-accessible control');
assert.match(tv, /wireTabFocus\(curated, community\)/,
  'Curated and Community must have explicit left/right remote traversal');
assert.match(tv, /when \(activeDiscoverCatalog\)/,
  'Curated and Community entries must never be blended in one result lane');
assert.match(tv, /private fun showCommunityFilters\(\)/,
  'Community type and search filters must have their own control route');
assert.match(tv, /TextEntryActivity\.EXTRA_TITLE, "Search community sources"/,
  'Community search must use the native text-entry surface');
assert.match(tv, /take\(COMMUNITY_QUERY_MAX\)/,
  'Community search must remain capped before it is sent to the Hub');
assert.match(tv, /private fun communityDirectoryContractIsSafe\(\): Boolean/,
  'Community rows must fail closed without their acknowledgement envelope');
assert.match(tv, /acknowledgement\.optBoolean\("required", false\)/,
  'Community acknowledgement must be explicitly required');
assert.match(tv, /acknowledgement\.optString\("field"\) == COMMUNITY_ACK_FIELD/,
  'Community acknowledgement must use the documented field');
assert.match(tv, /private fun communitySourceBadges\(source: JSONObject\): List<String>/,
  'Community rows must render disclosure badges');
assert.match(tv, /badges\.add\("Unendorsed"\)/,
  'Community rows must visibly disclose non-endorsement');
assert.match(tv, /source\.optBoolean\("adult", false\)/,
  'Community rows must visibly disclose adult content');
assert.match(tv, /source\.optBoolean\("p2p", false\)/,
  'Community rows must visibly disclose peer-to-peer delivery');
assert.match(tv, /source\.optBoolean\("configurable", false\)/,
  'Community rows must visibly disclose configurable sources');
assert.match(tv, /private fun communityInstallUnavailable\(source: JSONObject\): Boolean/,
  'Community setup-required entries must remain unavailable to install');
assert.match(tv, /source\.optBoolean\("configurationRequired", false\)/,
  'Community configuration-required state must be enforced');
assert.match(tv, /private fun showCommunityDetail\(summary: JSONObject, opaqueId: String\)/,
  'Community list cards must load opaque detail before consent');
assert.match(tv, /api\.communityAddonDetail\(opaqueId\)/,
  'Community detail must be Hub-owned and opaque-ID-only');
assert.match(tv, /"I understand — continue"/,
  'Community installation must require explicit acknowledgement before the install action');
assert.match(tv, /"Acknowledge Community source"/,
  'Community acknowledgement and final install must be separate actions');
assert.match(tv, /api\.installCommunityAddon\(opaqueId\)/,
  'Community installation must use only the opaque id');
assert.match(tv, /private fun safeCommunityText\(raw: String\?, limit: Int\): String/,
  'Community display copy must redact raw links and sensitive setup text');
assert.doesNotMatch(tv, /source\.optString\("url"\)|source\.optString\("manifest"\)|source\.optString\("config"\)/,
  'Community rendering must not expose source URLs, manifests, or configuration secrets');
assert.match(browseApi, /fun communityAddonDirectory\(/,
  'BrowseApi must own the Community directory request');
assert.match(browseApi, /"type=\$\{enc\(safeType\)\}&q=\$\{enc\(safeQuery\)\}"/,
  'Community list requests must send only the documented type and capped query');
assert.match(browseApi, /val safeQuery = query\.trim\(\)\.take\(80\)/,
  'BrowseApi must independently cap Community queries');
assert.match(browseApi, /fun communityAddonDetail\(/,
  'BrowseApi must expose the opaque Community detail request');
assert.match(browseApi, /fun installCommunityAddon\(/,
  'BrowseApi must expose the acknowledged opaque Community install request');
assert.match(browseApi, /JSONObject\(\)\.put\("nonEndorsementAcknowledged", true\)/,
  'Community install must always send the documented acknowledgement body');

// Optional modern directory metadata is safe to consume, but never required for compatibility.
assert.match(browseApi, /optJSONObject\("directory"\)\?\.optJSONArray\("entries"\)[\s\S]*optJSONArray\("reviewed"\)/,
  'TV must prefer modern directory entries while retaining legacy Hub compatibility');
assert.match(tv, /source\.optBoolean\("installed", false\)/,
  'TV must consume a canonical installed status when supplied');
assert.match(tv, /source\.optJSONObject\("directory"\)/,
  'TV must tolerate optional per-entry directory metadata');
assert.match(tv, /private fun reviewedInstallUnavailable\(source: JSONObject\): Boolean/,
  'TV must consume unavailable-install metadata');
assert.match(tv, /sourceSetupAvailable\(source\) -> options\.add\("setup" to "Finish setup on web"\)[\s\S]*?!reviewedInstallUnavailable\(source\) -> options\.add\("install" to "Install reviewed source"\)/,
  'TV must offer Install only when available, with configuration-required entries routed through web setup');
assert.match(tv, /private fun sourceSetupAvailable\(source: JSONObject\): Boolean/,
  'configuration-required entries must require an explicit safe handoff contract');
assert.match(tv, /setup\.optBoolean\("available", false\) && setup\.optString\("method"\) == "web-code"/,
  'configuration-required entries may only use the opaque web-code handoff');
assert.match(tv, /This source needs setup, but this Hub has not provided a safe browser handoff yet\./,
  'configuration-required entries must clearly remain un-installable without a safe handoff');

// Security: reviewed installs are ID-only, manual URLs are isolated, and every mutation retains PIN recovery.
assert.match(tv, /api\.reviewedAddons \{ choices, (?:_|directoryError) ->/,
  'reviewed discovery must stay Hub-owned');
assert.match(tv, /api\.addReviewedAddon\(id\)/,
  'reviewed installation must send only a registry id to the Hub');
assert.doesNotMatch(tv, /source\.optString\("manifest"\)/,
  'TV must not expose a reviewed source manifest address');
assert.match(tv, /api\.addAddon\(url\)/,
  'manual installation must remain available only through the advanced route');
assert.match(tv, /retryAfterSettingsUnlock\(root, retried\)/,
  'all source mutations must retain the Settings-PIN recovery path');
assert.match(tv, /api\.unlockSettings\(pin\)/,
  'a protected mutation must recover through the existing admin session flow');
assert.match(tv, /const val EXTRA_PROFILE = "profile"/,
  'every entry point must require a selected profile id');
assert.match(tv, /profile == null \|\| profile\.kids/,
  'unknown and child profiles must fail closed before directory rendering');
assert.match(tv, /private fun verifyTrustedAdultProfile\(\)/,
  'the adult-profile gate must run before source data is loaded');

// Source health remains observational; rendering must not probe providers.
assert.match(tv, /private fun sourceState\(ad: JSONObject, detail: String, on: Boolean, native: Boolean\): SourceState/,
  'TV must derive source presentation from cached Hub observations');
assert.match(tv, /Needs attention · /,
  'TV must expose a safe, actionable failed-source state');
assert.match(tv, /last refresh is available/,
  'TV must distinguish an observed source from one awaiting first refresh');
assert.match(tv, /waiting for its first refresh/,
  'TV must avoid representing an unobserved source as ready');
assert.match(tv, /ad\.isNull\("error"\)/,
  'a JSON null error must not be rendered as a failed source');
assert.match(tv, /is String -> lastFetch\.isNotBlank\(\)/,
  'TV must recognise timestamp-string refresh reports');
assert.doesNotMatch(tv, /ad\.optString\("url"\)/,
  'TV must not show a configured source URL in its library');

assert.match(web, /function sourceState\(ad, detail\)/,
  'web must derive source presentation from cached Hub observations');
assert.match(web, /value: 'Attention'/,
  'web must expose a safe, actionable failed-source state');
assert.match(web, /value: 'Ready'/,
  'web must distinguish an observed source from one awaiting first refresh');
assert.match(web, /value: 'Starting'/,
  'web must avoid representing an unobserved source as ready');

console.log('ADD-ON EXPERIENCE GATE OK');
