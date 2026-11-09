/**
 * js/screens/home.js — the billboard and the shelves.
 *
 * The whole page is a loop over `/app/v1/home`: rows come in the order the hub chose, each one names its
 * own presentation, and js/plugins.js decides what that means. Nothing here mentions a shelf by name, so
 * a new addon that registers a new row changes this page without changing it.
 */

import { api, getTrailer } from '/ui/js/api.js';
import { el, clear, posterImg, toast } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { token } from '/ui/js/theme.js';
import { billboard, rowFor, cardFor, lazyMount, dropMountedRows } from '/ui/js/plugins.js';
import { startPreview, stopPreview } from '/ui/js/video.js';
import { session, rememberFocus } from '/ui/js/state.js';
import { paintAvatar } from '/ui/js/avatars.js';
import { initials } from '/ui/js/format.js';

/*
 * A home pack is a structural contract, not a theme-name switch.  theme.js publishes these
 * values on <html>, but Home owns the DOM it describes: keeping the normalisation here means a
 * future authored pack can select a known composition without adding a new `theme.id` branch.
 * Unknown values deliberately fall back to the durable billboard + stacked shelves rather than
 * claiming a composition this renderer does not understand.
 */
const KNOWN_HOME_HEROES = new Set([
  'shared-slider', 'cinema-billboard', 'focused-hub', 'glass-cards', 'kids-stage',
]);
const KNOWN_HOME_RAILS = new Set(['stacked', 'hub']);
const KNOWN_HOME_NAVIGATION = new Set(['pack', 'top-bar', 'utility', 'none']);

function homeSurfaceContract() {
  const raw = (name, fallback) => String(token('pack', name, fallback) || '').trim();
  const hero = raw('homeHero', '');
  const rails = raw('homeRails', '');
  const navigation = raw('homeNavigation', '');
  return {
    // `homeLayout` is still useful as a descriptive hook for older authored packs; it never decides
    // which renderer is mounted.  The three explicit contracts above are the behavioural boundary.
    layout: raw('homeLayout', 'classic') || 'classic',
    hero: KNOWN_HOME_HEROES.has(hero) ? hero : 'classic-billboard',
    rails: KNOWN_HOME_RAILS.has(rails) ? rails : 'stacked',
    navigation: KNOWN_HOME_NAVIGATION.has(navigation) ? navigation : 'top-bar',
  };
}

export function screen(ctx) {
  // On the television this page is the profile gate and the rest of the flow; Home itself is native.
  // A browser has no Scenix bridge and keeps the web shelves. The profile is what the native reader
  // needs, and it is the only thing that crosses the boundary.
  try {
    if (session.profile && window.Scenix && typeof window.Scenix.openNativeBrowse === 'function') {
      window.Scenix.openNativeBrowse(session.profile.id);
    }
  } catch (e) { /* an older shell keeps the web home */ }

  const homeContract = homeSurfaceContract();
  const root = el('div', { class: 'screen home' });
  root.dataset.homeLayout = homeContract.layout;
  root.dataset.homeHero = homeContract.hero;
  root.dataset.homeRails = homeContract.rails;
  root.dataset.homeNavigation = homeContract.navigation;
  const shelves = el('div', {
    class: `rows pulled home-rails home-rails--${homeContract.rails}`,
    dataset: { homeRails: homeContract.rails },
  });
  root.appendChild(shelves);

  /* ---- the Apple stage -------------------------------------------------- */
  // One backdrop behind the shelves, crossfading to the tile under the ring and blurring while the
  // viewer walks. Only the Apple pack opts in; every other pack keeps the opaque page.
  const stagePack = token('pack', 'stage', 'off') === 'focus';
  let homeStage = null;
  let stageFront = null;
  let stageBack = null;
  let stageUrl = '';
  if (stagePack) {
    const host = document.getElementById('stage');
    if (host) {
      homeStage = el('div', { class: 'apple-stage', 'aria-hidden': 'true' });
      stageFront = el('div', { class: 'sg a' });
      stageBack = el('div', { class: 'sg b' });
      homeStage.appendChild(stageFront);
      homeStage.appendChild(stageBack);
      homeStage.appendChild(el('div', { class: 'veil' }));
      host.insertBefore(homeStage, host.firstChild);
    }
  }

  function stageShow(url) {
    if (!homeStage || !url || url === stageUrl) return;
    stageUrl = url;
    const incoming = stageFront && stageFront.classList.contains('on') ? stageBack : stageFront;
    if (!incoming) return;
    const paint = () => {
      if (stageUrl !== url) return;
      incoming.style.backgroundImage = `url("${url}")`;
      incoming.classList.add('on');
      if (incoming === stageFront) stageBack && stageBack.classList.remove('on');
      else { stageFront && stageFront.classList.remove('on'); stageFront = incoming; }
    };
    const img = new Image();
    img.onload = paint;
    img.onerror = () => { if (stageUrl === url) stageUrl = ''; };
    img.src = url;
  }

  function stageRoam(on) {
    if (!homeStage) return;
    document.body.classList.toggle('roaming', !!on);
  }

  function stageHero() {
    const item = bb && bb.__items && bb.__index ? bb.__items[bb.__index()] : null;
    return item ? (item.backdrop || item.poster) : '';
  }

  let rotate = null;
  let bb = null;
  let dwell = null;
  let expand = null;
  let previewFor = null;
  let dead = false;
  let ownedNavigation = null;

  // Theme packs explicitly choose whether moving-preview decode is part of their experience.  The
  // default stays on for legacy packs, but a zero is a hard budget boundary rather than a hint: the
  // native surface treats it the same way, and the browser must not spend decoder/audio resources on
  // a theme that is intentionally artwork-led.
  function previewsAreEnabled() {
    return Number(token('preview', 'enabled', 1)) > 0;
  }

  /* ---- billboard -------------------------------------------------------- */

  /**
   * A billboard is the one tile a viewer presses Play on without opening its page first. The hub can only
   * start a cold copy while it is being looked at, so opening the title in the background is what makes
   * that first press fast: the metadata call resolves the file and fills the first slice of the buffer.
   * Failures are silent — a title with no ready copy is the player's problem to explain, not the shelf's.
   */
  function prewarm(item) {
    if (!item || !item.identity) return;
    api.meta(item.identity, session.profile && session.profile.id).catch(() => {});
  }

  /* ---- Home-owned navigation ------------------------------------------ */

  /**
   * A theme can explicitly own Home navigation.  In that case hiding the host bar is only correct
   * when its replacement carries every real way out: profile, search, settings, list and the
   * sections the current Hub can actually fill.  These are contracts, not theme names — a future
   * pack can select the same grammar without a new branch here.
   */
  function homeSections(home) {
    const manifest = ctx.manifest() || {};
    const manifestRows = Array.isArray(manifest.rows) ? manifest.rows : [];
    const rows = Array.isArray(home && home.rows) ? home.rows : [];
    const hasRow = (id) => rows.some((row) => row && row.id === id)
      || manifestRows.some((row) => row && row.id === id);
    const caps = session.caps;
    // A just-loaded live shelf is stronger evidence than a stale capability cache. This mirrors
    // the host bar's OR rule so owned navigation cannot hide a route the current Home already fills.
    const hasLive = (caps ? !!caps.live : false) || hasRow('live');
    const hasGames = caps ? caps.games !== false : true;
    const isUtility = homeContract.navigation === 'utility';
    const entries = [{
      id: 'home', label: isUtility ? 'All' : 'Home', route: 'home', active: true,
    }];
    if (hasRow('movies')) entries.push({ id: 'movies', label: 'Movies', route: 'browse', arg: 'movies' });
    if (hasRow('series')) entries.push({ id: 'series', label: 'Series', route: 'browse', arg: 'series' });
    if (hasGames) entries.push({ id: 'games', label: 'Games', route: 'games' });
    if (hasLive) entries.push({ id: 'live', label: 'Live', route: 'channels' });
    return entries;
  }

  function navButton(entry) {
    const b = el('button', {
      class: `nav-item home-owned-section${entry.active ? ' on' : ''}`,
      type: 'button', text: entry.label,
      dataset: { tab: entry.id, homeControl: entry.id },
      'aria-current': entry.active ? 'page' : null,
      onclick: () => ctx.navigate(entry.route, entry.arg),
    });
    focus.focusable(b);
    return b;
  }

  function homeUtility(label, iconName, route, extraClass = '') {
    const b = el('button', {
      class: `nav-icon home-owned-action ${extraClass}`.trim(),
      type: 'button', 'aria-label': label, title: label,
      dataset: { homeControl: route },
      onclick: () => ctx.navigate(route),
    }, icon(iconName));
    focus.focusable(b);
    return b;
  }

  function homeProfile() {
    const profile = session.profile || {};
    const b = el('button', {
      class: 'avatar-chip home-owned-profile', type: 'button',
      'aria-label': 'Switch profile', title: 'Switch profile',
      dataset: { homeControl: 'profiles' },
      onclick: () => ctx.navigate('profiles'),
    });
    const face = el('span', { class: 'a' }, el('span', { class: 'ini', text: initials(profile.name) }));
    const index = (session.profiles || []).findIndex((person) => String(person.id) === String(profile.id));
    paintAvatar(face, profile, index >= 0 ? index : 0);
    b.append(face, el('span', { class: 'home-owned-profile-name', text: profile.name || 'Profiles' }));
    focus.focusable(b);
    return b;
  }

  function makeOwnedNavigation(home) {
    const mode = homeContract.navigation;
    if (mode !== 'utility' && mode !== 'none') return null;

    const nav = el('header', {
      class: `nav home-owned-nav home-owned-nav--${mode}`,
      dataset: { homeNavigation: mode },
      // A single-row owned header is a horizontal band. The two-row utility grammar deliberately
      // stays geometric so Right follows its visible top system strip before the lower categories.
      'data-nav': mode === 'none' ? 'h' : null,
      'aria-label': 'Home navigation',
    });
    const brand = el('button', {
      class: 'brand home-owned-brand', type: 'button', 'aria-label': 'Scenix home',
      dataset: { homeControl: 'home' },
      onclick: () => ctx.navigate('home'),
    }, el('span', { class: 'brand-mark' }));
    focus.focusable(brand);

    // The header is one horizontal focus band. Giving its visual subgroups their own bands makes
    // Down walk sideways from sections into utilities instead of leaving the chrome for Home.
    const sections = el('div', { class: 'nav-items home-owned-sections', 'aria-label': 'Browse sections' });
    for (const entry of homeSections(home)) sections.appendChild(navButton(entry));
    // The owned glass/kids grammar keeps search at the end of its central capsule.  The console
    // grammar keeps it beside the profile and settings actions, as one compact system strip.
    if (mode === 'none') sections.appendChild(homeUtility('Search', 'search', 'search', 'home-owned-search'));

    const utilities = el('div', { class: 'home-owned-utilities', 'aria-label': 'Household controls' });
    utilities.appendChild(homeProfile());
    utilities.appendChild(homeUtility('My list', 'star', 'list', 'home-owned-list'));
    if (mode === 'utility') utilities.appendChild(homeUtility('Search', 'search', 'search', 'home-owned-search'));
    utilities.appendChild(homeUtility('Settings', 'gear', 'settings', 'home-owned-settings'));

    nav.append(brand, sections, utilities);
    return nav;
  }

  function mountOwnedNavigation(home) {
    const current = focus.getFocus();
    const restore = ownedNavigation && current && ownedNavigation.contains(current)
      ? current.dataset.homeControl : '';
    if (ownedNavigation) { ownedNavigation.remove(); ownedNavigation = null; }
    ownedNavigation = makeOwnedNavigation(home);
    if (!ownedNavigation) return;
    root.insertBefore(ownedNavigation, shelves);
    // A late capability answer may add/remove one optional door. Preserve a viewer's place in
    // the header if it remains, otherwise land them on Home rather than leaving focus detached.
    if (restore) {
      const target = ownedNavigation.querySelector(`[data-home-control="${restore}"]`)
        || ownedNavigation.querySelector('[data-home-control="home"]');
      // A connected control can still be hidden by a narrow responsive header. Candidates are the
      // focus engine's visibility-tested snapshot, so never restore a ring to a hidden brand.
      if (target && focus.candidates().includes(target)) {
        focus.setFocus(target, { scroll: false, source: 'key' });
        return;
      }
    }
    if (root.isConnected && (!current || current === document.body || !root.contains(current))) {
      // No preferred target: focusDefault filters responsive-hidden controls before choosing the
      // first real stop, which matters when a compact header omits the wordmark.
      focus.focusDefault();
    }
  }

  /**
   * Glass and Kids do not reinterpret the ordinary billboard as a different paint job.  Their contracts
   * call for two simultaneously visible featured titles, so this keeps the shared hero data and play
   * action but gives those packs the composition they actually declare.  Each plate is a single native
   * button: mouse, keyboard and remote all activate the same clear promise — play this title — without
   * putting a smaller button inside a larger one.
   */
  function featureHero(row, kind) {
    const max = Math.min(2, Math.max(1, Number(token('hero', 'maxItems', 2)) || 2));
    const items = (row.items || []).slice(0, max);
    const host = el('section', {
      class: `billboard feature-hero ${kind}-feature-hero`,
      dataset: { row: row.id },
      'data-nav': 'h',
    });
    items.forEach((item, index) => {
      const title = String(item.title || 'Selected title');
      const plate = el('button', {
        class: 'feature-card', type: 'button',
        dataset: { identity: item.identity || '', i: String(index) },
        'aria-label': `Play ${title}`,
        onclick: () => ctx.play(item),
      });
      const art = el('div', { class: 'feature-art' });
      art.appendChild(posterImg(item.backdrop || item.poster, ''));
      const facts = [];
      if (item.match) facts.push(`${item.match}% match`);
      if (item.year) facts.push(String(item.year));
      if (item.maturity) facts.push(String(item.maturity));
      plate.append(
        art,
        el('span', { class: 'feature-wash', 'aria-hidden': 'true' }),
        el('span', { class: 'feature-copy' },
          el('span', { class: 'feature-title', text: title }),
          facts.length ? el('span', { class: 'feature-meta', text: facts.join(' · ') }) : null),
        el('span', { class: 'feature-play', 'aria-hidden': 'true' },
          el('span', { class: 'feature-play-disc' }, icon('play', { solid: true })),
          el('span', { text: 'Play' })));
      plate.addEventListener('focus', () => prewarm(item));
      focus.focusable(plate);
      host.appendChild(plate);
    });
    host.__items = items;
    host.__index = () => 0;
    return host;
  }

  /** Actual shelves become the compact glass/children's-stage shortcuts; no invented categories. */
  function featureChips(rows) {
    const limit = Math.max(0, Number(token('chips', 'max', 6)) || 6);
    const usable = (rows || []).filter((row) => row && row.id && row.title).slice(0, limit);
    if (!usable.length) return null;
    const chips = el('div', { class: 'home-chips', 'data-nav': 'h', 'aria-label': 'Browse shelves' });
    for (const row of usable) {
      const chip = el('button', {
        class: 'home-chip', type: 'button', text: row.title,
        'aria-label': `Open ${row.title}`,
        onclick: () => {
          const target = Array.from(shelves.querySelectorAll('.row'))
            .find((node) => node.dataset.row === String(row.id));
          if (!target) return;
          target.scrollIntoView({ block: 'start', behavior: 'auto' });
          requestAnimationFrame(() => {
            const first = target.querySelector('[data-f]');
            if (first) focus.setFocus(first, { scroll: false, source: 'key' });
          });
        },
      });
      focus.focusable(chip);
      chips.appendChild(chip);
    }
    return chips;
  }

  /**
   * The web counterpart of the native HomeSurface factory.  The data set and actions stay shared;
   * only the composition changes.  In particular, a focused hub never rotates out from under a
   * viewer who is moving between its explicit hero tiles, while a cinema or shared-slider surface
   * keeps the existing bounded cross-fade cadence.
   */
  function mountHomeSurface(hero, rest) {
    if (!hero || !(hero.items || []).length) return;
    const dualFeature = homeContract.hero === 'glass-cards' || homeContract.hero === 'kids-stage';
    if (dualFeature) {
      bb = featureHero(hero, homeContract.hero);
      bb.classList.add('home-surface', `home-surface--${homeContract.hero}`);
      bb.dataset.homeHero = homeContract.hero;
      root.insertBefore(bb, shelves);
      const shortcuts = featureChips(rest);
      if (shortcuts) {
        shortcuts.classList.add('home-surface-shortcuts');
        root.insertBefore(shortcuts, shelves);
      }
      for (const item of hero.items.slice(0, 2)) prewarm(item);
      return;
    }

    bb = billboard(hero, {
      play: (item) => ctx.play(item),
      open: (item) => ctx.open(item),
      list: (item) => ctx.list(item),
    });
    bb.classList.add('home-surface', 'home-billboard', `home-billboard--${homeContract.hero}`);
    bb.dataset.homeHero = homeContract.hero;
    bb.dataset.homeRails = homeContract.rails;
    root.insertBefore(bb, shelves);
    prewarm(hero.items[0]);

    // The focused-hub queue is an explicit selection strip, not a rotating billboard.  Let a
    // viewer's selected tile stay selected; the other known single-hero packs retain the existing
    // timed cross-fade (which already pauses while a viewer is elsewhere on the page).
    if (homeContract.hero !== 'focused-hub') startRotation(bb);
  }

  function startRotation(row) {
    if (rotate) clearInterval(rotate);
    const ms = token('hero', 'rotateMs', 6000);
    if (!row || row.__items.length < 2) return;
    rotate = setInterval(() => {
      if (dead || document.hidden) return;
      // A preview that is running is the viewer paying attention: rotation waits for it.
      if (previewFor) return;
      // And a viewer walking the shelves is not looking at the billboard. Rebuilding its text and
      // thumbnails every six seconds while they browse is pure work for a picture they have left.
      const a = document.activeElement;
      if (a && !a.closest('.billboard') && !a.closest('.nav')) return;
      const next = (row.__index() + 1) % row.__items.length;
      // The timed turn fades (art cross-fade + copy column leave/arrive); a press-driven turn does not.
      row.__setItem(next, true);
      if (focus.getFocus() && focus.getFocus().closest && focus.getFocus().closest('.billboard')) {
        stageShow(row.__items[next] && (row.__items[next].backdrop || row.__items[next].poster));
      }
      prewarm(row.__items[next]);
      for (const b of row.querySelectorAll('.bb-btns .btn, .bb-btns .round')) focus.focusable(b);
    }, ms);
  }

  async function previewBillboard(slot) {
    if (!previewsAreEnabled()) return;
    const item = slot.__items[slot.__index()];
    if (!item || dead) return;
    const trailer = await getTrailer(item.identity, session.profile && session.profile.id);
    if (dead || slot.__items[slot.__index()] !== item) return;
    if (trailer) {
      // `startPreview` is async: a bare truthiness check on its Promise marked the card as playing even
      // when the target had no URL or the decoder refused it.
      const ok = await startPreview(slot.__previewSlot, trailer, item.poster || item.backdrop);
      if (!dead && ok) previewFor = slot;
    }
  }

  /* ---- shelves ---------------------------------------------------------- */

  /**
   * One shelf, on its own terms. A row that fails is replaced by its own retry line; the fourteen rows
   * next to it keep working, which is the difference between a hub that is mid-restart and a broken TV.
   */
  async function buildRow(row, index) {
    const ctxFor = () => ({
      index,
      play: (item) => ctx.play(item),
      open: (item) => { rememberFocus('home', item.identity); ctx.open(item); },
      list: (item) => ctx.list(item),
      explore: (r) => ctx.navigate('search', `row:${r.id}`),
      search: (q) => ctx.navigate('search', q),
    });
    const node = rowFor(row)(row, ctxFor());
    node.style.setProperty('--i', String(index));
    shelves.appendChild(node);

    if ((node.__items || []).length) { lazyMount(node, { root, preload: '700px' }); return node; }

    // The hub answers `/home` fast with some shelves still empty; those fill themselves in when reached.
    const size = row.card === 'poster' ? 'poster' : 'landscape';
    const sk = el('div', { class: `cards skeleton ${size}` });
    for (let i = 0; i < 6; i++) sk.appendChild(el('i'));
    node.querySelector('.cards').replaceWith(sk);
    let filled = false;
    const fill = async () => {
      if (filled || dead) return;
      filled = true;
      try {
        const fresh = await api.row(row.id, session.profile.id);
        const items = (fresh && fresh.items) || [];
        if (!items.length) { node.remove(); return; }
        row.items = items;
        node.__items = items;
        node.__mount = (its) => {
          const list = el('div', { class: `cards ${size}`, 'data-nav': 'h' });
          const render = cardFor(row);
          its.slice(0, 30).forEach((item, i) => list.appendChild(render(item, Object.assign({}, ctxFor(), { index: i, row }))));
          // Replace whichever rail is standing in the shell: the skeleton on the first fill, or the empty
          // `.cards` box the recycler left behind when the row was last unmounted.
          const old = node.querySelector('.cards');
          if (old) old.replaceWith(list); else node.appendChild(list);
          node.__list = list;
          node.__mounted = true;
          return list;
        };
        // From here the row is an ordinary card-bearing row: the recycler mounts it from __items and must
        // not call the (already spent) filler again.
        node.__ensure = null;
        lazyMount(node, { root, preload: '700px' });
      } catch (e) {
        filled = false;
        node.__ensure = null;
        sk.classList.remove('skeleton');
        for (const kid of Array.from(sk.children)) sk.removeChild(kid);
        sk.appendChild(el('div', { class: 'row-err' },
          el('span', { text: `${row.title || 'This shelf'} is not answering right now.` }),
          el('button', {
            class: 'btn ghost', type: 'button',
            onclick: () => { node.remove(); buildRow(row, index); },
          }, 'Try again')));
        focus.focusable(sk.querySelector('.btn'));
      }
    };
    // A shelf the hub left empty still has to be reachable: it joins the same mount queue and fills
    // itself when the viewer walks near it.
    node.__ensure = fill;
    lazyMount(node, { root, preload: '700px' });
    return node;
  }

  /* ---- first run ---------------------------------------------------------
     A hub with nothing installed answers `/home` with no rows at all. On a TV that reads as a broken app, so
     the page says what is missing and puts the remote on the fix. Everything here comes from `capabilities`:
     a hub that manages its own add-ons sends the viewer to Settings, one that does not is told who to ask. */

  function firstRun() {
    const m = ctx.manifest() || {};
    const caps = m.capabilities || {};
    const canFix = !!caps.addonAdmin || !!caps.settingsAdmin;
    const steps = [];
    if (canFix) steps.push(['Add an add-on', 'Add-ons are what bring titles onto the hub. Scenix shows whatever they hand over, in the order the hub chooses.']);
    steps.push(['Choose the languages you watch', 'Audio and subtitles are ranked by what you ask for, so this decides what you are offered.']);
    const box = el('div', { class: 'firstrun' },
      el('h1', { text: 'Nothing on the shelves yet' }),
      el('p', {
        text: canFix
          ? 'The hub is answering and your profile is set. What is missing is a source that is on: a fresh Scenix ships with a legal public-domain film shelf and Local Files, and you can install an add-on for more.'
          : 'The hub is answering and your profile is set, but it has nothing to show yet. Whoever runs this hub adds the content.',
      }),
      el('div', { class: 'fr-steps' },
        ...steps.map((s, i) => el('div', { class: 'fr-step' },
          el('i', { text: String(i + 1) }),
          el('div', {}, el('b', { text: s[0] }), el('span', { text: s[1] })))))
      ,
      el('div', { class: 'bb-btns' },
        canFix ? el('button', { class: 'btn white big', type: 'button', onclick: () => ctx.navigate('settings') }, 'Set up the hub') : null,
        caps.search ? el('button', { class: 'btn ghost big', type: 'button', onclick: () => ctx.navigate('search') }, 'Search anyway') : null));
    for (const b of box.querySelectorAll('.btn')) focus.focusable(b);
    return box;
  }

  const disposers = [];

  // `/caps` resolves independently of the Home payload. A late answer may make Live or Games
  // available after this composition mounted, so an owned header refreshes its real doors while
  // keeping focus on the same control where possible.
  const onCapabilitiesChanged = () => {
    if (dead || !root.isConnected || (homeContract.navigation !== 'utility' && homeContract.navigation !== 'none')) return;
    mountOwnedNavigation(session.home || {});
  };
  window.addEventListener('scenix:caps', onCapabilitiesChanged);
  disposers.push(() => window.removeEventListener('scenix:caps', onCapabilitiesChanged));

  if (stagePack) {
    const offStageFocus = focus.onFocus((node) => {
      if (!node || !homeStage) return;
      const card = node.closest && node.closest('.card');
      if (card) {
        stageShow(card.dataset.bd || card.dataset.p || '');
        stageRoam(true);
        return;
      }
      if (node.closest && (node.closest('.billboard') || node.closest('.nav'))) {
        stageShow(stageHero());
        stageRoam(false);
      }
    });
    disposers.push(offStageFocus);
  }

  /** Stop whatever is moving and give the card back the size it had before. */
  function endPreview() {
    const n = previewFor;
    previewFor = null;
    stopPreview();
    if (n && n.closest) {
      const c = n.closest('.card');
      if (c) c.classList.remove('playing');
    }
  }

  /* ---- load ------------------------------------------------------------- */

  async function load() {
    const profile = session.profile && session.profile.id;
    if (!profile) return;
    // ?empty=1 shows this state against a hub that already has content, so it can be checked on a browser.
    if (new URLSearchParams(location.search).has('empty')) {
      mountOwnedNavigation(session.home || {});
      shelves.appendChild(firstRun());
      return;
    }
    let home = session.home;
    if (!home || home.__profile !== profile) {
      home = await api.home(profile);
      home.__profile = profile;
      session.home = home;
      // The bar's section tabs follow the shelves: the Live TV tab waits until this hub answers with a
      // live wall that has channels in it.
      ctx.syncNav();
    }
    if (dead) return;

    // The replacement chrome is mounted only after the Hub has told us which optional doors are
    // real. That keeps an owned Home from offering an empty Live or Games screen while preserving
    // an immediate, fully reachable setup state for an empty household above.
    mountOwnedNavigation(home);

    const rows = (home.rows || []).filter((r) => r && (r.items || []).length);
    // The hub orders the rows; `order` is authoritative when present, array order otherwise.
    const ordered = rows
      .map((r, i) => ({ r, i, o: r.order == null ? i : Number(r.order) }))
      .sort((a, b) => (a.o - b.o) || (a.i - b.i))
      .map((x) => x.r);

    // No rows at all means a hub with nothing installed, not a screen that failed to paint.
    if (!ordered.length) { shelves.appendChild(firstRun()); return; }

    const hero = ordered.find((r) => r.card === 'hero');
    const rest = ordered.filter((r) => r !== hero);
    const firstStage = (hero && (hero.items || [])[0])
      || (rest[0] && (rest[0].items || [])[0]);
    if (firstStage) stageShow(firstStage.backdrop || firstStage.poster);
    stageRoam(false);

    mountHomeSurface(hero, rest);
    for (let i = 0; i < rest.length; i++) buildRow(rest[i], i);
  }

  /* ---- dwell: 250 ms expands the card, 1000 ms starts its trailer ------- */

  // The expansion is the Netflix signature moment and it belongs to attention, not to the trailer: a
  // tile that has been looked at for `preview.expandMs` widens, its Play / My-List actions become real
  // stops inside it, OK on the expanded tile plays directly, and the shelf dims everything else. The
  // trailer then arrives at `preview.delayMs` if there is one. Both timers are per-focus; leaving the
  // tile collapses it and strips its extra focus stops again.
  let expandedCard = null;
  function collapseExpanded() {
    if (!expandedCard) return;
    const c = expandedCard;
    expandedCard = null;
    c.classList.remove('dwell');
    for (const b of c.querySelectorAll('.exp button')) { b.removeAttribute('data-f'); b.removeAttribute('tabindex'); }
  }

  const onDwell = focus.onFocus((node, source) => {
    if (dwell) { clearTimeout(dwell); dwell = null; }
    if (expand) { clearTimeout(expand); expand = null; }
    const hostCard = node && node.closest ? node.closest('.card') : null;
    if (expandedCard && expandedCard !== hostCard) collapseExpanded();
    if (previewFor && previewFor !== node) endPreview();
    // A feature hero is a navigation destination rather than a shelf tile. It must still clear a
    // previously focused tile first: returning above would leave that tile's preview decoder (and,
    // where permitted, audio) running behind the new hero.
    if (node && node.closest && node.closest('.feature-hero')) return;
    if (!node || node === document.body) return;
    // The expansion is the remote's signature moment: walking the ring and dwelling on a tile. A mouse
    // does not dwell — it sweeps. Growing one card to 460 px while thirty neighbours reflow around it is
    // exactly what reads as "the items are different widths" and "hovering scrolled the row". Pointer
    // focus keeps the light state: scale-up and ring only.
    if (source === 'pointer') return;
    const card = hostCard;
    const inBillboard = node.closest && node.closest('.billboard');
    const delay = token('preview', 'delayMs', 1000);
    const expandMs = token('preview', 'expandMs', 250);
    if (card) {
      expand = setTimeout(() => {
        if (dead || focus.getFocus() !== node) return;
        expandedCard = card;
        card.classList.add('dwell');
        for (const b of card.querySelectorAll('.exp button')) focus.focusable(b);
        // The tile grew to the right: make sure the shelf moved far enough that all of it is seen.
        const row = card.closest('.cards');
        if (row && row.scrollWidth > row.clientWidth + 1) {
          const v = row.getBoundingClientRect();
          const c = card.getBoundingClientRect();
          if (c.right > v.right - 8) {
            const max = row.scrollWidth - row.clientWidth;
            row.scrollLeft = Math.min(max, row.scrollLeft + (c.right - v.right + 8));
          }
        }
      }, expandMs);
      if (previewsAreEnabled()) dwell = setTimeout(async () => {
        if (dead) return;
        const identity = card.dataset.identity;
        const trailer = await getTrailer(identity, session.profile && session.profile.id);
        if (dead || focus.getFocus() !== node) return;
        const art = card.querySelector('.art');
        // A card that is actually moving picture is given room to be one: wider, and at the trailer's ratio.
        if (trailer && art) {
          // The card's own still is the poster to fade out of: this handler was referencing an `item`
          // from an enclosing scope that never existed, so the fallback path threw instead of previewing.
          const img = card.querySelector('img');
          const ok = await startPreview(art, trailer, img ? img.src : null);
          if (!dead && focus.getFocus() === node && ok) { previewFor = node; card.classList.add('playing'); }
        }
      }, delay);
    // Focused-hub uses its direct tile strip as the hero interaction, matching the native console
    // surface. Starting a trailer while that strip is being walked makes the selected tile compete
    // with a second moving surface and needlessly spends decode budget.
    } else if (inBillboard && bb && homeContract.hero !== 'focused-hub' && previewsAreEnabled()) {
      dwell = setTimeout(() => { if (!dead) previewBillboard(bb); }, delay);
    }
    void inBillboard;
  });
  disposers.push(() => { if (typeof onDwell === 'function') onDwell(); });

  root.__dispose = () => {
    dead = true;
    if (rotate) clearInterval(rotate);
    if (dwell) clearTimeout(dwell);
    if (expand) clearTimeout(expand);
    collapseExpanded();
    stopPreview();
    dropMountedRows(root);
    stageRoam(false);
    if (homeStage) { homeStage.remove(); homeStage = null; }
    for (const d of disposers) { try { d(); } catch (e) { /* ignore */ } }
  };

  // Render the owned chrome before the asynchronous Home request. It starts conservative (only
  // proven manifest/capability doors), then `load` and the capability event fill it in without
  // ever leaving the route's focus scope empty.
  mountOwnedNavigation(session.home || {});

  load().catch((e) => {
    if (dead) return;
    console.error('[scenix] home failed', e);
    clear(shelves);
    shelves.appendChild(el('div', { class: 'row-err' },
      el('span', { text: 'The hub did not answer. Scenix will try again.' }),
      el('button', { class: 'btn ghost', type: 'button', onclick: () => ctx.reload() }, 'Try again')));
    toast('Check the hub address in Settings');
  });

  return root;
}
