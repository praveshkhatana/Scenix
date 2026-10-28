/**
 * js/screens/detail.js — everything this hub knows about one title, and the choice of which copy to play.
 *
 * The hub answers `/app/v1/meta` with the record it has, no more: sometimes a synopsis, sometimes only a
 * poster, a year and a list of copies. This screen therefore renders what arrived and quietly omits what
 * did not, rather than inventing filler or showing an empty box. Three rules follow from that:
 *
 *   · The copy list lives in the player's Source sheet. The detail page is about the title; one Play button
 *     asks the hub for the best copy and the player can change it without coming back here.
 *   · Related titles come from this hub's own shelves. The record has no `similar` field on every build, so
 *     the row is assembled from the home rows already loaded, matched on type and genre. Nothing here
 *     hardcodes a title, a row, or a language.
 *   · The season bar, the episode strip and the cast are drawn from the record's own `episodes` and `cast`.
 *     An episode with no still keeps its slot and shows its number instead of a picture; a person with no
 *     photograph in the payload is a name standing on their initials. The hub sends no roles and no
 *     per-episode length, so the strip does not claim either.
 */

import { el, clear, toast, imageUrl } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { api } from '/ui/js/api.js';
import { session, rememberMeta, metaCache, inMyList } from '/ui/js/state.js';
import { clock, runtime, langList, since, episodeLabel, seasonLabel, timeLeft, initials } from '/ui/js/format.js';
import { rowFor, lazyMount, dropMountedRows } from '/ui/js/plugins.js';
import { startPreview, stopPreview } from '/ui/js/video.js';
import { pushHandler, popHandler } from '/ui/js/input.js';
import { token } from '/ui/js/theme.js';

export function screen(ctx, identity) {
  const root = el('div', { class: 'screen detail' });
  const cached = metaCache.get(identity);
  let item = cached || null;

  const hero = el('div', { class: 'detail-hero' });
  const body = el('div', { class: 'detail-body' });
  // The record modal hands focus back to this button, so it has to outlive the paint that made it.
  let playBtn = null;
  // A detail page that opens from a card already has the tile in `metaCache`; one opened by a deep link
  // does not. Both must show something branded while the hub answers, not a bare "Loading…".
  const loading = el('div', { class: 'detail-loading' },
    el('div', { class: 'dl-mark', text: 'Scenix' }),
    el('div', { class: 'dl-bar' }, el('i')),
    el('div', { class: 'dl-text', text: 'Reading this title…' }));
  root.appendChild(hero);
  root.appendChild(body);
  if (!item) { root.appendChild(loading); ctx.__detailLoading = loading; }

  /* ------------------------------------------------------------------ skeleton */

  function paintLoading() {
    if (!loading.isConnected) root.appendChild(loading);
  }

  /* -------------------------------------------------------------------- paint */

  function paint() {
    loading.remove();
    hero.textContent = '';
    body.textContent = '';
    const bg = el('img', { class: 'bg', src: imageUrl(item.backdrop || item.poster || ''), alt: '', decoding: 'async' });
    hero.appendChild(bg);
    // The trailer plays inside the hero frame, behind the words, exactly as it does on the home billboard.
    const preview = el('div', { class: 'preview' });
    hero.appendChild(preview);
    hero.appendChild(el('div', { class: 'scrim' }));
    hero.__previewSlot = preview;

    /* The words sit on the backdrop, the way the sheets draw them: the shelf line, the title's own
       lettering, the hub's facts about it, and the row that starts playing it. */
    const dh = el('div', { class: 'dh' });
    hero.appendChild(dh);

    const kind = item.type === 'live' || item.live ? 'Live TV' : item.type === 'series' ? 'Series' : 'Movie';
    dh.appendChild(el('div', { class: 'kicker' }, el('b', { text: 'Scenix' }), el('span', { text: kind })));
    // A logotype is artwork; plain text is the fallback rather than the design.
    if (item.logo) {
      const logo = el('img', { class: 'logo', src: item.logo, alt: item.title || '' });
      logo.addEventListener('error', () => logo.replaceWith(el('h1', { text: item.title || '' })));
      dh.appendChild(logo);
    } else dh.appendChild(el('h1', { text: item.title || '' }));

    /* The metadata line: only the parts the hub actually answered for. */
    const mat = el('div', { class: 'mat' });
    if (item.year) mat.appendChild(el('span', { text: String(item.year) }));
    if (Number(item.durationMs) > 0) mat.appendChild(el('span', { text: runtime(item.durationMs) }));
    if (maturityLabel(item)) mat.appendChild(el('span', { class: 'matbox', text: maturityLabel(item) }));
    if (item.live) mat.appendChild(el('span', { class: 'badge live' }, el('i'), el('span', { text: 'Live' })));
    if (Array.isArray(item.languages) && item.languages.length) mat.appendChild(el('span', { text: langList(item.languages) }));
    // A tier that is a word is worth showing; a bare number is the hub's internal ranking band, not a viewer's word.
    if (item.tier && isNaN(Number(item.tier))) mat.appendChild(el('span', { class: 'tagq', text: String(item.tier) }));
    if (Array.isArray(item.sources) && item.sources.some((s) => /1080|2160|4k|uhd/i.test(String(s.quality || '')))) {
      mat.appendChild(el('span', { class: 'hdbadge', text: 'HD' }));
    }
    if (Number(item.match) > 0) mat.appendChild(el('span', { class: 'tagq g', text: `${item.match}% match` }));
    if (mat.childNodes.length) dh.appendChild(mat);

    /* "#3 …" is this hub's own shelf in its own order: the position comes from the board the hub ranked
       the tile onto, or failing that from the row this title actually arrived on. Either way the line
       claims nothing the hub did not already say. */
    const board = item.board && Number.isFinite(Number(item.board.rank)) ? item.board : null;
    const spot = board ? null : shelfSpot();
    if (board || spot) {
      dh.appendChild(el('div', { class: 'trend' },
        el('b', { text: `#${board ? board.rank : spot.index + 1}` }),
        el('span', { text: (board && board.list) || spot.title || 'on this hub' })));
    }

    /* Actions: Play, or Resume with the time actually left; the list; and what the hub really has. */
    const prog = item.progress || null;
    playBtn = el('button', { class: 'btn big red', type: 'button', onclick: () => play() },
      icon('play', { solid: true }),
      el('span', { text: prog && prog.positionMs > 5000 ? 'Resume' : (item.live ? 'Watch live' : 'Play') }));
    if (prog && prog.positionMs > 5000 && Number(item.durationMs) > 0) {
      playBtn.appendChild(el('span', { class: 'tagq', style: 'margin-left:12px', text: timeLeft(prog.positionMs, item.durationMs) }));
    }
    focus.focusable(playBtn);

    const on = inMyList(item.identity);
    const listBtn = el('button', { class: 'btn big ghost', type: 'button',
      'aria-label': on ? 'Remove from your list' : 'Add to your list',
      onclick: () => {
        ctx.list(item);
        const now = inMyList(item.identity);
        clear(listBtn);
        listBtn.appendChild(icon(now ? 'check' : 'plus'));
        listBtn.appendChild(document.createTextNode(now ? 'In My List' : 'My List'));
      } },
    icon(on ? 'check' : 'plus'), on ? 'In My List' : 'My List');
    focus.focusable(listBtn);

    const infoBtn = el('button', { class: 'round', type: 'button', 'aria-label': 'What this hub knows',
      title: 'What this hub knows', onclick: () => showRecord() }, icon('info'));
    focus.focusable(infoBtn);

    dh.appendChild(el('div', { class: 'detail-actions' }, playBtn, listBtn, infoBtn));

    // Netflix shows where you stopped right under the buttons: a thin track with the watched part filled
    // in brand red. The Play button already says "Resume · N left"; this is the wordless version of it.
    if (prog && prog.positionMs > 5000 && Number(item.durationMs) > 0) {
      const pct = Math.max(2, Math.min(100, (prog.positionMs / Number(item.durationMs)) * 100));
      dh.appendChild(el('div', { class: 'resume-line' }, el('i', { style: `width:${pct}%` })));
    }

    /* ------------------------------------------------------------------ seasons */
    /* Whoever is still watching lands on the season they left; everyone else on the newest. */
    const eps = Array.isArray(item.episodes) ? item.episodes : [];
    if (eps.length) {
      const seasons = [...new Set(eps.map((e) => (e.season === undefined ? 1 : e.season)))].sort((a, b) => a - b);
      const count = (s) => eps.filter((e) => (e.season === undefined ? 1 : e.season) === s).length;
      let season = seasons[seasons.length - 1];
      if (prog && prog.episode && seasons.indexOf(prog.episode.season) >= 0) season = prog.episode.season;
      const head = el('h2', { class: 'sect-title' });
      const wrap = el('div', { class: 'episodes v' });
      // On the first paint the screen's own default focus (the Play button, up in the hero) decides where
      // the ring lands; claiming it for the strip as well would scroll the page under the title.
      // Switching a season is a deliberate move, so then the ring does follow the new strip.
      const paintEpisodes = (follow) => {
        const shown = season === null ? eps : eps.filter((e) => (e.season === undefined ? 1 : e.season) === season);
        head.textContent = '';
        head.appendChild(el('span', { text: season === null ? 'All seasons' : seasonLabel(season) }));
        head.appendChild(el('span', { class: 'note', text: `${shown.length} episode${shown.length === 1 ? '' : 's'}` }));
        wrap.textContent = '';
        if (!item.live) wrap.appendChild(trailerTile());
        shown.forEach((e, i) => wrap.appendChild(epRow(e, i)));
        if (follow) focus.focusDefault(wrap.querySelector('.epv'));
      };
      body.appendChild(head);
      if (seasons.length > 1) body.appendChild(seasonPick());
      body.appendChild(wrap);
      paintEpisodes();

      /* A row, not a chip bar — the same shape every other choice on this app has. The row names the
         season and how many episodes it holds; OK opens the dial the remote actually turns, exactly like
         a settings value: the season huge in the middle, Up/Down moving through them, OK refilling the
         list, Back leaving it untouched. Walking past the wrong season costs nothing. */
      function seasonPick() {
        // "All seasons" rides at the top of the dial, past the newest — one value out of a fixed list.
        const values = [...seasons, null];
        const show = (v) => (v === null
          ? `All seasons · ${eps.length} episodes`
          : `${seasonLabel(v)} · ${count(v)} episode${count(v) === 1 ? '' : 's'}`);
        const lv = el('span', { class: 'lv', text: show(season) });
        const b = el('button', { class: 'li act seasonpick', type: 'button', 'data-nav': 'h',
          'aria-label': `Season: ${show(season)}`, onclick: open },
        el('div', { class: 'ltext' },
          el('span', { class: 'lname', text: 'Season' }),
          el('span', { class: 'note', text: `${seasons.length} seasons on this hub` })),
        lv, el('span', { class: 'chev' }, icon('chevRight')));
        focus.focusable(b);
        let i = values.indexOf(season);
        if (i < 0) i = values.length - 2; // default to the newest real season
        function open() {
          const box = el('div', { class: 'mbox wheel' },
            el('h2', { text: 'Season' }),
            el('div', { class: 'wdial' },
              (() => { const u = el('button', { class: 'warrow', type: 'button', 'aria-label': 'Newer' }, icon('chevUp')); focus.focusable(u); u.onclick = () => move(1); return u; })(),
              (() => { const v = el('button', { class: 'wval', type: 'button' }); focus.focusable(v); v.onclick = () => commit(); return v; })(),
              (() => { const d = el('button', { class: 'warrow', type: 'button', 'aria-label': 'Older' }, icon('chevDown')); focus.focusable(d); d.onclick = () => move(-1); return d; })()),
            el('p', { class: 'whint', text: 'Up and Down change it · OK chooses · Back cancels' }));
          const wval = box.querySelector('.wval');
          const paintVal = () => {
            wval.textContent = show(values[i]);
            wval.setAttribute('aria-label', `Season: ${show(values[i])}`);
            // The number re-pops on every step, so turning the dial has a tick you can see.
            wval.classList.remove('pop');
            void wval.offsetWidth;
            wval.classList.add('pop');
          };
          const move = (d) => {
            const j = Math.min(values.length - 1, Math.max(0, i + d));
            if (j === i) return;
            i = j;
            paintVal();
          };
          const commit = () => {
            ctx.closeModals();
            season = values[i];
            lv.textContent = show(season);
            b.setAttribute('aria-label', `Season: ${show(season)}`);
            lv.classList.remove('bump');
            void lv.offsetWidth;
            lv.classList.add('bump');
            paintEpisodes(true);
          };
          // The dial claims the D-pad while it is up: Up/Down move wherever the ring happens to be in
          // the box, OK means "choose", and Escape falls through to the modal's own cancel.
          const lane = (e, key) => {
            if (key === 'ArrowUp') { move(1); return true; }
            if (key === 'ArrowDown') { move(-1); return true; }
            if (key === 'Enter' || key === 'NumpadEnter' || key === ' ') { commit(); return true; }
            return false;
          };
          paintVal();
          ctx.mountModal(box, () => popHandler(lane));
          pushHandler(lane);
          focus.focusDefault(wval);
        }
        return b;
      }
    }

    /* --------------------------------------------------------------- the columns */
    const sources = Array.isArray(item.sources) ? item.sources : [];
    const cols = el('div', { class: 'detail-cols' });
    const main = el('div');
    const side = el('div');
    cols.appendChild(main);
    cols.appendChild(side);
    body.appendChild(cols);

    const blurb = item.overview || item.synopsis || item.tagline || '';
    if (blurb) {
      main.appendChild(el('h2', { class: 'sect-title' }, el('span', { text: 'Overview' })));
      main.appendChild(el('p', { class: 'blurb', text: blurb }));
    }
    if (Array.isArray(item.genres) && item.genres.length) {
      const chips = el('div', { class: 'chips' });
      for (const g of item.genres) chips.appendChild(el('span', { class: 'chip', text: g }));
      main.appendChild(chips);
    }

    /* --------------------------------------------------------------------- cast */
    /* Names and, when the hub's optional TMDB enrichment found them, faces and roles. A missing portrait
       stays a person's initials rather than a generic silhouette. */
    const cast = Array.isArray(item.cast) ? item.cast : [];
    const grid = el('div', { class: 'cast' });
    for (const c of cast.slice(0, 18)) {
      const name = typeof c === 'string' ? c : ((c && (c.name || c.person)) || '');
      if (!name) continue;
      const face = el('div', { class: 'face' });
      if (c && c.profile) face.appendChild(el('img', { src: c.profile, alt: name, loading: 'lazy', decoding: 'async' }));
      else face.textContent = initials(name);
      grid.appendChild(el('div', { class: 'who' }, face,
        el('div', { class: 'nm', text: name }),
        (c && c.character) ? el('div', { class: 'role', text: c.character }) : null));
    }
    if (grid.childNodes.length) {
      main.appendChild(el('h2', { class: 'sect-title' }, el('span', { text: 'Cast' })));
      main.appendChild(grid);
    }
    // What the certificate suggests, in the same words the player shows. It is not a scene guide and the
    // line says where it came from.
    if (Array.isArray(item.advisories) && item.advisories.length) {
      main.appendChild(el('div', { class: 'advisory-line' },
        el('b', { text: 'Content notes' }),
        el('span', { text: 'Based on the certificate: ' + item.advisories.join(' · ') })));
    }

    /* ------------------------------------------------------------------ details */
    /* The copy list lives in the player now: one Play button, and the Source sheet lists every ranked
       copy with its reason. A second picker on the detail page was duplication, and the "why" column
       was hub reasoning the viewer does not need before pressing Play. */
    const facts = el('div', { class: 'detail-facts' });
    const fact = (label, value, cls) => {
      if (value === undefined || value === null || value === '') return;
      facts.appendChild(el('div', { class: 'df' },
        el('span', { class: 'dk', text: label }),
        el('span', { class: 'dv' + (cls ? ' ' + cls : ''), text: String(value) })));
    };
    fact('Rating', maturityLabel(item) || 'Not rated', maturityLabel(item) ? '' : 'muted');
    if (Number(item.minAge)) fact('Age', `${item.minAge}+`);
    if (Array.isArray(item.advisories) && item.advisories.length) fact('Content', item.advisories.join(' · '));
    if (Array.isArray(item.genres) && item.genres.length) fact('Genres', item.genres.join(', '));
    if (item.releasedAt) fact('Released', new Date(item.releasedAt).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }));
    if (item.addedAt) fact('On this hub', since(item.addedAt));
    if (Array.isArray(item.languages) && item.languages.length) fact('Audio', langList(item.languages));
    if (Array.isArray(item.subtitleLanguages) && item.subtitleLanguages.length) fact('Subtitles', langList(item.subtitleLanguages));
    if (prog && prog.positionMs > 5000) fact('Left off', clock(prog.positionMs));
    if (facts.childNodes.length) {
      side.appendChild(el('h2', { class: 'sect-title' }, el('span', { text: 'Details' })));
      side.appendChild(facts);
    }

    related();

    /* The trailer, if the hub can find one, plays in the hero frame after a moment — the same rule the
       home billboard uses, so a title is never a wall of stills. */
    let dwell = null;
    const TRAILER_DWELL = Number(token('preview', 'delayMs', 1500));
    const armTrailer = () => {
      if (dwell || item.live) return;
      dwell = setTimeout(() => playTrailer(true), TRAILER_DWELL);
    };
    const cancelTrailer = () => { if (dwell) { clearTimeout(dwell); dwell = null; } };
    focus.onFocus((n) => {
      const inHero = n && n.closest && n.closest('.detail-hero');
      const onTrailer = n && n.classList && n.classList.contains('trailer');
      if (inHero || onTrailer) armTrailer();
      else { cancelTrailer(); stopPreview(); }
    });
  }

  /**
   * One episode in the list — vertical, like Netflix's, because the thing that decides whether you press
   * play on an episode is its name and its synopsis, and a horizontal still-strip has no room for either.
   * The hub's own keys: `thumb` for the still, `title` for the name (the raw addon field is `name`, and a
   * hub that has not been mapped yet still sends it), `synopsis` for the summary. It sends no length per
   * episode, so the line under the title is the length only when a hub does have one.
   */
  function epRow(e, i) {
    const seasonNo = e.season !== undefined ? e.season : 1;
    const epNo = e.episode !== undefined ? e.episode : i + 1;
    const label = episodeLabel(seasonNo, epNo);
    const title = e.title || e.name || '';
    const still = e.thumb || e.still || '';
    const syn = e.synopsis || e.overview || '';
    const no = String(epNo).padStart(2, '0');
    const nxt = nextEpisode(e);
    const row = el('button', { class: 'epv', type: 'button',
      onclick: () => ctx.play(item, {
        title: title || item.title, season: seasonNo, episode: epNo,
        next: nxt ? Object.assign({}, nxt, { identity: item.identity }) : null,
      }) },
    // The number stands at the left like Netflix's; the still keeps its slot with the number as the
    // picture when the metadata source has no artwork for this episode.
    el('span', { class: 'epnum', text: no }),
    el('div', { class: `epart${still ? '' : ' nostill'}` },
      still ? el('img', { src: still, alt: '', loading: 'lazy' }) : el('span', { text: no })),
    el('div', { class: 'eptxt' },
      el('div', { class: 'en' },
        el('span', { text: title || label }),
        Number(e.durationMs) > 0 ? el('span', { class: 'eq', text: runtime(e.durationMs) }) : null),
      syn ? el('div', { class: 'esy', text: syn }) : null));
    focus.focusable(row);
    return row;
  }

  /** The trailer is an episode of one, filed at the front of the list. */
  function trailerTile() {
    const still = item.backdrop || item.poster;
    const b = el('button', { class: 'epv trailer', type: 'button', 'aria-label': 'Play trailer',
      onclick: () => playTrailer() },
    el('span', { class: 'epnum', text: '' }),
    el('div', { class: 'epart' },
      still ? el('img', { src: imageUrl(still), alt: '', loading: 'lazy' }) : null,
      el('span', { class: 'trailer-play' }, icon('play', { solid: true }))),
    el('div', { class: 'eptxt' }, el('div', { class: 'en', text: 'Trailer' })));
    focus.focusable(b);
    return b;
  }

  function nextEpisode(e) {
    const eps = Array.isArray(item.episodes) ? item.episodes : [];
    const i = eps.indexOf(e);
    return i >= 0 && i + 1 < eps.length ? eps[i + 1] : null;
  }

  /* ------------------------------------------------------------------ actions */

  function play(source) {
    ctx.play(item, {
      title: item.title,
      type: item.type,
      season: item.season,
      episode: item.episode,
      positionMs: progPosition(),
      next: Array.isArray(item.episodes) && item.episodes.length ? Object.assign({}, item.episodes[0], { identity: item.identity }) : null,
      source: source ? { id: source.id, label: source.label, provider: source.provider } : undefined,
    });
  }
  function progPosition() {
    const p = item.progress;
    return p && Number(p.positionMs) > 5000 ? Math.round(p.positionMs) : undefined;
  }

  let trailerTried = false;
  function playTrailer(silent = false) {
    if (trailerTried && hero.querySelector('video')) return;
    getTrailerSafe().then((t) => {
      if (!t) { if (!silent) toast('No trailer for this title on this hub.'); return; }
      startPreview(hero.__previewSlot || hero, t, item.backdrop || item.poster);
    });
  }
  async function getTrailerSafe() {
    if (trailerTried) return null;
    trailerTried = true;
    try {
      const t = await (api.getTrailer
        ? api.getTrailer(item.identity, session.profile && session.profile.id)
        : api.trailer(item.identity));
      return t && (t.url || t.proxiedUrl) ? t : null;
    } catch (e) {
      return null;
    }
  }

  /**
   * What the hub really returned, on request. When a poster is missing or a copy is listed as unavailable,
   * this is the difference between a bug report and a guess.
   */
  function showRecord() {
    const rows = [];
    const add = (k, v) => { if (v !== undefined && v !== null && v !== '') rows.push(el('div', { class: 'li' }, el('span', { text: k }), el('span', { class: 'lv', text: String(v) }))); };
    add('Title', item.title);
    add('Identity', item.identity);
    add('Type', item.type);
    add('Provider', item.provider);
    add('Match', item.match);
    add('Tier', item.tier);
    add('Year', item.year);
    add('Runtime', Number(item.durationMs) > 0 ? runtime(item.durationMs) : '');
    add('Maturity', item.maturity);
    add('Minimum age', item.minAge);
    add('Languages', Array.isArray(item.languages) ? langList(item.languages) : '');
    add('Genres', Array.isArray(item.genres) ? item.genres.join(', ') : '');
    add('Episodes', Array.isArray(item.episodes) ? item.episodes.length : 0);
    add('Cast', Array.isArray(item.cast) ? item.cast.length : 0);
    add('Copies', Array.isArray(item.sources) ? item.sources.length : 0);
    add('Kinds', Array.isArray(item.sources) ? [...new Set(item.sources.map((s) => s.kind).filter(Boolean))].join(', ') : '');
    add('Position', item.progress ? clock(item.progress.positionMs) : '');
    add('Stream id', item.streamId);
    const box = el('div', { class: 'mbox' }, el('h2', { text: 'What this hub knows' }),
      el('p', { text: 'Straight from /app/v1/meta — the record your addons contributed.' }), ...rows);
    ctx.mountModal(box, () => focus.setFocus(playBtn, { scroll: false }));
    focus.focusDefault(box.querySelector('.li'));
  }

  /** Where this title actually sits on this hub's own shelves, for the "#n in <row>" line. */
  function shelfSpot() {
    for (const r of ((session.home && session.home.rows) || [])) {
      if (!r || r.id === 'continue' || r.card === 'hero') continue;
      const i = (r.items || []).findIndex((it) => it && it.identity === item.identity);
      if (i >= 0) return { index: i, title: r.title || null };
    }
    return null;
  }

  /* ------------------------------------------------------------------ related */

  /* Two places quote this hub's own shelves — the shelf line in the hero and the related row below the
     record — and both read the payload the home screen loads. A title opened straight from a URL has never
     asked for it, so ask once here and cache it exactly the way home does. A hub that will not answer about
     shelves simply gets fewer shelves on screen; nothing here invents a "more like this". */
  async function ensureShelves() {
    const pid = session.profile && session.profile.id;
    if (!pid || (session.home && session.home.__profile === pid)) return;
    try {
      const home = await api.home(pid);
      home.__profile = pid;
      session.home = home;
    } catch (e) { /* as just said */ }
  }

  function related() {
    const pool = [];
    const seen = new Set([item.identity]);
    const genres = new Set(Array.isArray(item.genres) ? item.genres : []);
    for (const r of ((session.home && session.home.rows) || [])) {
      if (r.id === 'continue') continue;
      for (const it of (r.items || [])) {
        if (!it || !it.identity || seen.has(it.identity)) continue;
        const sameType = it.type && item.type && it.type === item.type;
        const sharesGenre = Array.isArray(it.genres) && it.genres.some((g) => genres.has(g));
        if (sameType || sharesGenre) { seen.add(it.identity); pool.push(it); }
      }
    }
    if (!pool.length) {
      // An honest empty state instead of silence: this row speaks about this hub's own shelves, so when
      // they hold nothing alike it says so rather than pretending the section never existed. A thin pool
      // still gets the row — one lookalike is still an answer, and hiding it is the lie.
      root.appendChild(el('div', { class: 'sect-empty' },
        el('h2', { class: 'sect-title' }, el('span', { text: 'More like this' })),
        el('p', { text: 'This hub has not found anything similar yet.' })));
      return;
    }
    const synth = { id: `related-${item.identity}`, card: 'poster', title: 'More like this', items: pool.slice(0, 24) };
    const builder = rowFor(synth);
    const node = builder(synth, {
      index: 99,
      play: (it) => ctx.play(it, { title: it.title, type: it.type }),
      open: (it) => ctx.open(it),
      list: (it) => ctx.list(it),
      explore: () => {},
    });
    // The row is a shell until something tells the shared lazy mounter which scroller it lives in — the same
    // contract the home screen uses, so a synthesised row fills itself as it is scrolled into view.
    if (node) { root.appendChild(node); lazyMount(node, { root }); }
  }

  /* -------------------------------------------------------------------- load */

  const offs = [];
  let handler = null;

  async function load() {
    const pid = session.profile && session.profile.id;
    // Asked for beside the record so the first paint can already quote the hub's shelves. Arriving from
    // home, where that payload is cached, this costs nothing extra.
    const [, r] = await Promise.all([ensureShelves(), api.meta(identity, pid)]);
    item = (r && r.item) || r || {};
    if (!item.identity) item.identity = identity;
    rememberMeta(item);
    paint();
    handler = (e, key) => {
      if (key === 'MediaPlayPause' || key === 'PlayPause') { play(); return true; }
      return false;
    };
    pushHandler(handler);
    focus.focusDefault(root.querySelector('.detail-actions .btn'));
  }

  if (item) {
    paint();
    handler = (e, key) => ((key === 'MediaPlayPause' || key === 'PlayPause') ? (play(), true) : false);
    pushHandler(handler);
    focus.focusDefault(root.querySelector('.detail-actions .btn'));
  } else {
    load();
  }

  root.__defaultFocus = () => root.querySelector('.detail-actions .btn');
  root.__dispose = () => {
    stopPreview();
    if (handler) popHandler(handler);
    dropMountedRows(root);
  };
  return root;
}

/** Kept local so the detail screen never needs a maturity table of its own — the hub's word, formatted. */
function maturityLabel(item) {
  return item.maturity || (item.minAge ? `${item.minAge}+` : '');
}
