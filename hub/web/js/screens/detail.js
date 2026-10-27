/**
 * js/screens/detail.js — everything this hub knows about one title, and the choice of which copy to play.
 *
 * The hub answers `/app/v1/meta` with the record it has, no more: sometimes a synopsis, sometimes only a
 * poster, a year and a list of copies. This screen therefore renders what arrived and quietly omits what
 * did not, rather than inventing filler or showing an empty box. Three rules follow from that:
 *
 *   · The copy list is the point. The hub scored every source it could find and said why — the quality, the
 *     size, the risk, the seeding — so those go on screen as chosen facts, not as a spinner. Picking one
 *     sends it back through `/app/v1/play` as the copy to open.
 *   · Related titles come from this hub's own shelves. The record has no `similar` field on every build, so
 *     the row is assembled from the home rows already loaded, matched on type and genre. Nothing here
 *     hardcodes a title, a row, or a language.
 *   · The season bar, the episode strip and the cast are drawn from the record's own `episodes` and `cast`.
 *     An episode with no still keeps its slot and shows its number instead of a picture; a person with no
 *     photograph in the payload is a name standing on their initials. The hub sends no roles and no
 *     per-episode length, so the strip does not claim either.
 */

import { el, clear, toast } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { api } from '/ui/js/api.js';
import { session, rememberMeta, metaCache, inMyList } from '/ui/js/state.js';
import { clock, runtime, size, langList, since, episodeLabel, seasonLabel, qualityWord, timeLeft, initials } from '/ui/js/format.js';
import { rowFor, dropMountedRows } from '/ui/js/plugins.js';
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
  root.appendChild(hero);
  root.appendChild(body);
  if (!item) paintLoading();

  /* ------------------------------------------------------------------ skeleton */

  function paintLoading() {
    hero.appendChild(el('div', { class: 'scrim' }));
    body.appendChild(el('h1', { text: 'Loading…' }));
  }

  /* -------------------------------------------------------------------- paint */

  function paint() {
    hero.textContent = '';
    body.textContent = '';
    const bg = el('img', { class: 'bg', src: item.backdrop || item.poster || '', alt: '', decoding: 'async' });
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
    if (item.logo) dh.appendChild(el('img', { class: 'logo', src: item.logo, alt: item.title || '' }));
    else dh.appendChild(el('h1', { text: item.title || '' }));

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
      playBtn.appendChild(el('span', { class: 'tagq', style: 'margin-left:12px', text: `${timeLeft(prog.positionMs, item.durationMs)} left` }));
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

    /* ------------------------------------------------------------------ seasons */
    /* The bar names the seasons the record actually contains and the strip draws the episodes of one of
       them. Whoever is still watching lands on the season they left; everyone else on the newest. */
    const eps = Array.isArray(item.episodes) ? item.episodes : [];
    if (eps.length) {
      const seasons = [...new Set(eps.map((e) => (e.season === undefined ? 1 : e.season)))].sort((a, b) => a - b);
      const count = (s) => eps.filter((e) => (e.season === undefined ? 1 : e.season) === s).length;
      let season = seasons[seasons.length - 1];
      if (prog && prog.episode && seasons.indexOf(prog.episode.season) >= 0) season = prog.episode.season;
      const head = el('h2', { class: 'sect-title' });
      const tabs = el('div', { class: 'seasons' });
      const wrap = el('div', { class: 'episodes h', 'data-nav': 'h' });
      const paintEpisodes = () => {
        const shown = season === null ? eps : eps.filter((e) => (e.season === undefined ? 1 : e.season) === season);
        head.textContent = '';
        head.appendChild(el('span', { text: season === null ? 'All seasons' : seasonLabel(season) }));
        head.appendChild(el('span', { class: 'note', text: `${shown.length} episode${shown.length === 1 ? '' : 's'}` }));
        wrap.textContent = '';
        if (!item.live) wrap.appendChild(trailerTile());
        shown.forEach((e, i) => wrap.appendChild(epRow(e, i)));
        for (const k of tabs.children) k.classList.toggle('on', (k.dataset.season === '' ? null : Number(k.dataset.season)) === season);
        focus.focusDefault(wrap.querySelector('.ep'));
      };
      for (const s of seasons) {
        // The bar is numbers, because the heading above it already says what they are.
        const b = el('button', { class: `chip${s === season ? ' on' : ''}`, type: 'button',
          'data-season': String(s), 'aria-label': seasonLabel(s), text: String(s),
          onclick: () => { season = s; paintEpisodes(); } });
        focus.focusable(b);
        tabs.appendChild(b);
      }
      if (seasons.length > 1) {
        const all = el('button', { class: 'chip all', type: 'button', 'data-season': '', text: 'All seasons',
          onclick: () => { season = null; paintEpisodes(); } });
        focus.focusable(all);
        tabs.appendChild(all);
      }
      body.appendChild(head);
      if (seasons.length > 1) body.appendChild(tabs);
      body.appendChild(wrap);
      paintEpisodes();
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
    /* Names, exactly as the hub flattened them. It sends no photographs and no roles, so there is no
       portrait to fetch and no credit line to make up under the name. */
    const cast = Array.isArray(item.cast) ? item.cast : [];
    const grid = el('div', { class: 'cast' });
    for (const c of cast.slice(0, 18)) {
      const name = typeof c === 'string' ? c : ((c && (c.name || c.person)) || '');
      if (!name) continue;
      grid.appendChild(el('div', { class: 'who' },
        el('div', { class: 'face', text: initials(name) }),
        el('div', { class: 'nm', text: name })));
    }
    if (grid.childNodes.length) {
      main.appendChild(el('h2', { class: 'sect-title' }, el('span', { text: 'Cast' })));
      main.appendChild(grid);
    }

    /* ------------------------------------------------------------------- rating */
    /* A rating this household gives, not one the hub claims: there is no ratings endpoint on this hub, so
       the value is stored on the box the way My list is (js/state.js) and is labelled as the viewer's own. */
    const RK = `scenix.ratings.${(session.profile && session.profile.id) || 'default'}`;
    const myRating = () => {
      try {
        const v = Number(JSON.parse(localStorage.getItem(RK) || '{}')[item.identity]);
        return v > 0 && v <= 5 ? v : 0;
      } catch (e) { return 0; }
    };
    const saveRating = (v) => {
      let m = {};
      try { m = JSON.parse(localStorage.getItem(RK) || '{}') || {}; } catch (e) { m = {}; }
      if (v > 0) m[item.identity] = v; else delete m[item.identity];
      try { localStorage.setItem(RK, JSON.stringify(m)); } catch (e) { /* nowhere to keep it on this box */ }
    };
    const stars = el('div', { class: 'rate-stars' });
    const rateNote = el('span', { class: 'rate-note' });
    const drawStars = () => {
      const v = myRating();
      clear(stars);
      for (let i = 1; i <= 5; i++) {
        const b = el('button', { class: `star-btn${i <= v ? ' on' : ''}`, type: 'button',
          'aria-label': `${i} star${i === 1 ? '' : 's'}`,
          onclick: () => { saveRating(i); drawStars(); } },
        icon('star', { solid: i <= v }));
        focus.focusable(b);
        stars.appendChild(b);
      }
      rateNote.textContent = v ? `You rated this ${v} of 5` : 'Not rated by you';
    };
    drawStars();
    main.appendChild(el('h2', { class: 'sect-title' }, el('span', { text: 'Create a rating' })));
    main.appendChild(el('div', { class: 'rate-box' }, stars, rateNote));

    /* ------------------------------------------------------------- the copies */
    if (sources.length) {
      main.appendChild(el('h2', { class: 'sect-title' }, el('span', { text: 'Where to watch' }),
        el('span', { class: 'note', text: 'Ranked by the hub for this profile' })));
      const list = el('div', { class: 'srcs' });
      for (const s of sources) list.appendChild(srcRow(s));
      main.appendChild(list);
    } else {
      main.appendChild(el('h2', { class: 'sect-title' }, el('span', { text: 'Where to watch' }),
        el('span', { class: 'note', text: 'No addon returned a copy for this title yet' })));
    }

    /* --------------------------------------------------- why the hub surfaced it */
    const why = (sources[0] && Array.isArray(sources[0].why)) ? sources[0].why : (Array.isArray(item.why) ? item.why : []);
    if (why.length) {
      side.appendChild(el('h2', { class: 'sect-title' }, el('span', { text: 'Why this is here' })));
      side.appendChild(el('div', { class: 'why' }, ...why.map((w) => el('i', { text: `— ${w}` }))));
    }
    const facts = [];
    if (Number(item.minAge)) facts.push(`For ${item.minAge} and up`);
    if (item.releasedAt) facts.push(`Released ${new Date(item.releasedAt).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })}`);
    if (item.addedAt) facts.push(`On this hub ${since(item.addedAt)}`);
    if (sources.length) facts.push(`${sources.length} ${sources.length === 1 ? 'copy' : 'copies'} found by your addons`);
    if (item.inCinemas) facts.push('Still in cinemas');
    if (facts.length) side.appendChild(el('p', { class: 'facts', text: facts.join('  ·  ') }));
    if (item.match) {
      side.appendChild(el('p', { class: 'facts', text: `Matched at ${item.match}${item.score !== undefined ? ` of ${item.score}` : ''} by ${item.provider || 'an addon'}.` }));
    }
    if (prog && prog.positionMs > 5000) {
      side.appendChild(el('p', { class: 'facts', text: `Left off at ${clock(prog.positionMs)}.` }));
    }

    related();

    /* The trailer, if the hub can find one, plays in the hero frame after a moment — the same rule the
       home billboard uses, so a title is never a wall of stills. */
    let dwell = null;
    const TRAILER_DWELL = Number(token('preview', 'delayMs', 1500));
    const armTrailer = () => {
      if (dwell || item.live) return;
      dwell = setTimeout(playTrailer, TRAILER_DWELL);
    };
    const cancelTrailer = () => { if (dwell) { clearTimeout(dwell); dwell = null; } };
    focus.onFocus((n) => {
      const inHero = n && n.closest && n.closest('.detail-hero');
      const onTrailer = n && n.classList && n.classList.contains('trailer');
      if (inHero || onTrailer) armTrailer();
      else { cancelTrailer(); stopPreview(); }
    });
  }

  function srcRow(s) {
    // Which icon, from what the copy actually is: a stream this hub serves, a swarm that needs peers, or a file
    // already on the disk.
    const kindIcon = (s.kind === 'hls' || s.kind === 'gateway' || s.kind === 'live') ? 'tv'
      : s.kind === 'torrent' ? 'globe' : 'external';
    const sl = el('div', { class: 'sl' },
      el('b', { text: s.label || qualityWord(s.quality) || s.provider || 'copy' }),
      el('span', { text: s.tagline || [s.provider, s.kind].filter(Boolean).join(' · ') }));
    if (Array.isArray(s.why) && s.why.length) {
      sl.appendChild(el('div', { class: 'why' }, ...s.why.slice(0, 3).map((w) => el('i', { text: `— ${w}` }))));
    }
    const q = el('div', { class: 'q' });
    if (s.quality) q.appendChild(el('span', { class: 'tagq g', text: qualityWord(s.quality) }));
    if (Number(s.sizeBytes) > 0) q.appendChild(el('span', { class: 'tagq', text: size(s.sizeBytes) }));
    if (s.seeders !== undefined && s.seeders !== null) q.appendChild(el('span', { class: 'tagq', text: `${s.seeders} seeds` }));
    if (s.risk) q.appendChild(el('span', { class: `tagq ${s.risk === 'low' ? 'g' : s.risk === 'medium' ? 'y' : 'r'}`, text: `${s.risk} risk` }));
    if (s.status && s.status !== 'available') q.appendChild(el('span', { class: 'tagq r', text: s.status }));
    const b = el('button', { class: 'src', type: 'button', onclick: () => play(s) }, icon(kindIcon, { solid: false }), sl, q);
    focus.focusable(b);
    return b;
  }

  /**
   * One episode in the strip. The hub's own keys: `thumb` for the still, `title` for the name (the raw
   * addon field is `name`, and a hub that has not been mapped yet still sends it), `synopsis` for the
   * summary. It sends no length per episode, so the line under the title is the length only when a hub
   * does have one.
   */
  function epRow(e, i) {
    const seasonNo = e.season !== undefined ? e.season : 1;
    const epNo = e.episode !== undefined ? e.episode : i + 1;
    const label = episodeLabel(seasonNo, epNo);
    const title = e.title || e.name || '';
    const still = e.thumb || e.still || '';
    const no = String(epNo).padStart(2, '0');
    const nxt = nextEpisode(e);
    const row = el('button', { class: 'ep', type: 'button',
      onclick: () => ctx.play(item, {
        title: title || item.title, season: seasonNo, episode: epNo,
        next: nxt ? Object.assign({}, nxt, { identity: item.identity }) : null,
      }) },
    // The number is printed on the still when there is one, and stands in as the picture when there is not.
    still ? el('img', { src: still, alt: '', loading: 'lazy' }) : el('div', { class: 'nostill' }, el('span', { text: no })),
    still ? el('span', { class: 'epn', text: no }) : null,
    el('div', {},
      el('div', { class: 'en', text: title || label }),
      Number(e.durationMs) > 0 ? el('div', { class: 'eq', text: runtime(e.durationMs) }) : null));
    focus.focusable(row);
    return row;
  }

  /** The trailer is an episode of one, filed at the front of the strip. */
  function trailerTile() {
    const b = el('button', { class: 'ep trailer', type: 'button', 'aria-label': 'Play trailer',
      onclick: () => playTrailer() },
    el('div', { class: 'nostill' }, icon('play', { solid: true })),
    el('div', {}, el('div', { class: 'en', text: 'Trailer' })));
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
  function playTrailer() {
    if (trailerTried && hero.querySelector('video')) return;
    getTrailerSafe().then((t) => {
      if (!t) { toast('No trailer for this title on this hub.'); return; }
      startPreview(hero.__previewSlot || hero, t);
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
    if (pool.length < 3) return;
    const synth = { id: `related-${item.identity}`, card: 'poster', title: 'More like this', items: pool.slice(0, 24) };
    const builder = rowFor(synth);
    const node = builder(synth, {
      index: 99,
      play: (it) => ctx.play(it, { title: it.title, type: it.type }),
      open: (it) => ctx.open(it),
      list: (it) => ctx.list(it),
      explore: () => {},
    });
    if (node) root.appendChild(node);
  }

  /* -------------------------------------------------------------------- load */

  const offs = [];
  let handler = null;

  async function load() {
    const pid = session.profile && session.profile.id;
    const r = await api.meta(identity, pid);
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
