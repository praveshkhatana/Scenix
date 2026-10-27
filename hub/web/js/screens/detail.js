/**
 * js/screens/detail.js — everything this hub knows about one title, and the choice of which copy to play.
 *
 * The hub answers `/app/v1/meta` with the record it has, no more: sometimes a synopsis, sometimes only a
 * poster, a year and a list of copies. This screen therefore renders what arrived and quietly omits what
 * did not, rather than inventing filler or showing an empty box. Two rules follow from that:
 *
 *   · The copy list is the point. The hub scored every source it could find and said why — the quality, the
 *     size, the risk, the seeding — so those go on screen as chosen facts, not as a spinner. Picking one
 *     sends it back through `/app/v1/play` as the copy to open.
 *   · Related titles come from this hub's own shelves. The record has no `similar` field on every build, so
 *     the row is assembled from the home rows already loaded, matched on type and genre. Nothing here
 *     hardcodes a title, a row, or a language.
 */

import { el } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { icon } from '/ui/js/icons.js';
import { api } from '/ui/js/api.js';
import { session, rememberMeta, inMyList } from '/ui/js/state.js';
import { clock, runtime, size, langList, since, episodeLabel, qualityWord, timeLeft } from '/ui/js/format.js';
import { cardFor, rowFor, dropMountedRows } from '/ui/js/plugins.js';
import { startPreview, stopPreview } from '/ui/js/video.js';
import { pushHandler, popHandler } from '/ui/js/input.js';
import { token } from '/ui/js/theme.js';

export function screen(ctx, identity) {
  const root = el('div', { class: 'screen detail' });
  const cached = session.metaCache && session.metaCache[identity];
  let item = cached || null;

  const hero = el('div', { class: 'detail-hero' });
  const body = el('div', { class: 'detail-body' });
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

    /* The first line names the shelf and the kind of thing it holds, then the title's own lettering where the
       hub has it. A logotype is artwork; plain text is the fallback rather than the design. */
    const kind = item.type === 'live' || item.live ? 'Live TV' : item.type === 'series' ? 'Series' : 'Movie';
    body.appendChild(el('div', { class: 'kicker' }, el('b', { text: 'Scenix' }), el('span', { text: kind })));
    if (item.logo) body.appendChild(el('img', { class: 'logo', src: item.logo, alt: item.title || '' }));
    else body.appendChild(el('h1', { text: item.title || '' }));

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
    if (mat.childNodes.length) body.appendChild(mat);

    /* "#3 in …" is this hub's own shelf in its own order: the position is read off the row the title arrived on,
       so the line claims nothing the hub did not already say. */
    const spot = shelfSpot();
    if (spot) {
      body.appendChild(el('div', { class: 'trend' },
        el('b', { text: `#${spot.index + 1}` }),
        el('span', { text: spot.title || 'on this hub' })));
    }

    /* Actions. Play, or Resume with the time actually left, plus the list and the trailer. */
    const prog = item.progress || null;
    const playBtn = el('button', { class: 'btn white big', type: 'button', onclick: () => play() },
      icon('play', { solid: true }),
      el('span', { text: prog && prog.positionMs > 5000 ? 'Resume' : (item.live ? 'Watch live' : 'Play') }));
    if (prog && prog.positionMs > 5000 && Number(item.durationMs) > 0) {
      playBtn.appendChild(el('span', { class: 'tagq', style: 'margin-left:12px', text: `${timeLeft(prog.positionMs, item.durationMs)} left` }));
    }
    focus.focusable(playBtn);

    const on = inMyList(item.identity);
    const listBtn = el('button', { class: `round${on ? ' on' : ''}`, type: 'button',
      'aria-label': on ? 'Remove from your list' : 'Add to your list', title: on ? 'In your list' : 'Add to my list',
      onclick: () => { ctx.list(item); listBtn.classList.toggle('on', inMyList(item.identity)); } },
    icon(on ? 'check' : 'plus'));
    focus.focusable(listBtn);

    const trailerBtn = el('button', { class: 'round', type: 'button', 'aria-label': 'Play trailer',
      title: 'Trailer', onclick: () => playTrailer() }, icon('info'));
    focus.focusable(trailerBtn);

    const infoBtn = el('button', { class: 'round', type: 'button', 'aria-label': 'What this hub knows',
      title: 'What this hub knows', onclick: () => showRecord() }, icon('sliders'));
    focus.focusable(infoBtn);

    body.appendChild(el('div', { class: 'detail-actions' }, playBtn, listBtn, trailerBtn, infoBtn));

    /* A synopsis when the hub has one, genres as chips when it does not. Nothing is invented. */
    const blurb = item.overview || item.synopsis || item.tagline || '';
    if (blurb) body.appendChild(el('p', { class: 'blurb', text: blurb }));
    if (Array.isArray(item.genres) && item.genres.length) {
      const chips = el('div', { class: 'chips' });
      for (const g of item.genres) chips.appendChild(el('span', { class: 'chip', text: g }));
      body.appendChild(chips);
    }

    const facts = [];
    if (Number(item.minAge)) facts.push(`For ${item.minAge} and up`);
    if (item.releasedAt) facts.push(`Released ${new Date(item.releasedAt).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })}`);
    if (item.addedAt) facts.push(`On this hub ${since(item.addedAt)}`);
    if (Array.isArray(item.sources) && item.sources.length) {
      facts.push(`${item.sources.length} ${item.sources.length === 1 ? 'copy' : 'copies'} found by your addons`);
    }
    if (item.inCinemas) facts.push('Still in cinemas');
    if (facts.length) body.appendChild(el('p', { class: 'facts', text: facts.join('  ·  ') }));

    /* ------------------------------------------------------------- episodes */
    /* The strip of stills sits directly under the hero with the season named and counted — where the sheets put
       it — rather than as a list at the foot of the page. */
    const eps = Array.isArray(item.episodes) ? item.episodes : [];
    if (eps.length) {
      const seasons = [...new Set(eps.map((e) => (e.season === undefined ? 1 : e.season)))].sort((a, b) => a - b);
      const count = (s) => eps.filter((e) => (e.season === undefined ? 1 : e.season) === s).length;
      // Whoever is still watching should land on the season they left, and everyone else on the newest one.
      let season = seasons[seasons.length - 1];
      if (prog && prog.episode && seasons.indexOf(prog.episode.season) >= 0) season = prog.episode.season;
      const head = el('h2', { class: 'sect-title' });
      const tabs = el('div', { class: 'seasons' });
      const wrap = el('div', { class: 'episodes h', 'data-nav': 'h' });
      const paintEpisodes = () => {
        head.textContent = '';
        head.appendChild(el('span', { text: `Season ${season}` }));
        head.appendChild(el('span', { class: 'note', text: `${count(season)} episode${count(season) > 1 ? 's' : ''}` }));
        wrap.textContent = '';
        eps.filter((e) => (e.season === undefined ? 1 : e.season) === season)
          .forEach((e, i) => wrap.appendChild(epRow(e, i)));
        focus.focusDefault(wrap.querySelector('.ep'));
      };
      for (const s of seasons) {
        const b = el('button', { class: `chip${s === season ? ' on' : ''}`, type: 'button',
          text: `Season ${s}`, onclick: () => {
            season = s;
            for (const k of tabs.children) k.classList.toggle('on', k.textContent === `Season ${s}`);
            paintEpisodes();
          } });
        focus.focusable(b);
        tabs.appendChild(b);
      }
      body.appendChild(head);
      if (seasons.length > 1) body.appendChild(tabs);
      body.appendChild(wrap);
      paintEpisodes();
    }

    /* --------------------------------------------------------------- the copies */
    const sources = Array.isArray(item.sources) ? item.sources : [];
    const cols = el('div', { class: 'detail-cols' });
    const main = el('div');
    const side = el('div');
    cols.appendChild(main);
    cols.appendChild(side);
    body.appendChild(cols);
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
    focus.onFocus((n) => { if (n === trailerBtn || (n && n.closest && n.closest('.detail-hero'))) armTrailer(); else { cancelTrailer(); stopPreview(); } });
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

  function epRow(e, i) {
    const label = episodeLabel(e.season !== undefined ? e.season : 1, e.episode !== undefined ? e.episode : i + 1);
    /* Some hubs name every episode and some name none. The number is the floor, and a code repeated under a
       code is noise — so the second line carries the synopsis, or the number only when the first line is a title. */
    const title = e.title || '';
    const second = e.overview || (title ? label : '');
    const row = el('button', { class: 'ep', type: 'button',
      onclick: () => ctx.play(item, {
        title: title || item.title, season: e.season, episode: e.episode,
        next: nextEpisode(e) ? Object.assign({}, nextEpisode(e), { identity: item.identity }) : null,
      }) },
    e.still || e.thumb ? el('img', { src: e.still || e.thumb, alt: '', loading: 'lazy' }) : null,
    el('div', {},
      el('div', { class: 'en', text: title || label }),
      second ? el('div', { class: 'es', text: second }) : null),
    Number(e.durationMs) > 0 ? el('span', { class: 'eq', text: runtime(e.durationMs) }) : el('span', { class: 'eq', text: label }));
    focus.focusable(row);
    return row;
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
      if (!t) return;
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
    focus.focusDefault(root.querySelector('.btn.white'));
  }

  if (item) {
    paint();
    handler = (e, key) => ((key === 'MediaPlayPause' || key === 'PlayPause') ? (play(), true) : false);
    pushHandler(handler);
    focus.focusDefault(root.querySelector('.btn.white'));
  } else {
    load();
  }

  root.__defaultFocus = () => root.querySelector('.btn.white');
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
