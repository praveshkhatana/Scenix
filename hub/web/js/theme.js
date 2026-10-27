/**
 * js/theme.js — the brand file drives the interface.
 *
 * app.css carries the tokens as its defaults so the page is styled before any script runs; this module
 * then re-applies them from `/ui/assets/theme.json`, the same file the Android shell reads. A colour, a
 * radius or a poster size is therefore one JSON edit for both clients.
 *
 * Every length is a pixel measured on a 1920x1080 TV. `--s` is the scale factor for the screen we are
 * actually on, so the design does not change between a 720p TV and a 1080p one — only its size does.
 */

const TOKEN_PATH = '/ui/assets/theme.json';

let T = {};

/* group + key -> the CSS custom property it drives. Lengths are multiplied by --s. */
const COLOR_VARS = {
  bg: '--bg', surface: '--surface', surface2: '--surface2', surface3: '--surface3', line: '--line',
  border: '--border', ink: '--ink', ink1: '--ink1', ink2: '--ink2', ink3: '--ink3',
  brand: '--brand', brandDeep: '--brand-deep', match: '--match', kids: '--kids', kidsDeep: '--kids-deep',
  live: '--live', warn: '--warn', focusRing: '--focus-ring', track: '--track', chipBg: '--chip-bg',
  key: '--key', modalScrim: '--modal-scrim', panelScrim: '--panel-scrim', matBorder: '--mat-border',
  hdInk: '--hd-ink', numeralStroke: '--numeral-stroke',
};
const SPACE_VARS = {
  gutter: '--gutter', gap: '--gap', rowGap: '--row-gap', captionGap: '--caption-gap',
  metaGap: '--meta-gap', navHeight: '--nav-h', billboardHeight: '--billboard-h', navGap: '--nav-gap',
  chipGap: '--chip-gap', buttonGap: '--button-gap', heroBottom: '--hero-bottom', panelPad: '--panel-pad',
};
const TYPE_VARS = {
  brand: '--t-brand', nav: '--t-nav', nmark: '--t-mark', h1: '--t-h1', h2: '--t-h2', h3: '--t-h3',
  sub: '--t-sub', matLine: '--t-mat', button: '--t-button', chip: '--t-chip', rowTitle: '--t-row-title',
  explore: '--t-explore', caption: '--t-caption', meta: '--t-meta', badge: '--t-badge', li: '--t-li',
  liMeta: '--t-li-meta', readout: '--t-readout', searchField: '--t-field', profileName: '--t-profile',
  avatarText: '--t-avatar', key: '--t-key', panelTitle: '--t-panel-title', panelRow: '--t-panel-row',
  panelNote: '--t-panel-note',
};
const RADIUS_VARS = {
  card: '--r-card', badge: '--r-badge', chip: '--r-chip', button: '--r-button', field: '--r-field',
  key: '--r-key', modal: '--r-modal', avatar: '--r-avatar', panel: '--r-panel', heroButton: '--r-hero-button',
};

const root = document.documentElement;
let scale = 1;

function setVar(name, value) { root.style.setProperty(name, value); }

/**
 * The hub writes an alpha pair the Android way — `#AARRGGBB` — while CSS reads eight hex digits as
 * `#RRGGBBAA`. The same characters mean different colours: `#1AFFFFFF` is ten-percent white to a TV and opaque
 * cyan to a browser, which quietly tints every border in the interface. An 8-digit colour is therefore treated
 * as alpha-first here, so the asset can keep the convention the rest of the TV world uses. Six digits, three
 * digits and `rgba()` pass through untouched — a hub that prefers the web spelling can use either.
 */
function cssColor(v) {
  const s = String(v || '').trim();
  const m = /^#([0-9a-fA-F]{2})([0-9a-fA-F]{6})$/.exec(s);
  if (!m) return s;
  const a = parseInt(m[1], 16) / 255;
  const r = parseInt(m[2].slice(0, 2), 16);
  const g = parseInt(m[2].slice(2, 4), 16);
  const b = parseInt(m[2].slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${Math.round(a * 1000) / 1000})`;
}

function setLen(name, px) { if (px !== undefined && px !== null) setVar(name, `calc(${px}px * var(--s))`); }
function setNum(name, n) { if (n !== undefined && n !== null) setVar(name, String(n)); }

function applyTokens() {
  const c = T.colors || {};
  for (const [k, v] of Object.entries(COLOR_VARS)) if (c[k]) setVar(v, cssColor(c[k]));

  const sp = T.spacing || {};
  for (const [k, v] of Object.entries(SPACE_VARS)) setLen(v, sp[k]);

  const ty = T.type || {};
  for (const [k, v] of Object.entries(TYPE_VARS)) setLen(v, ty[k]);
  setLen('--modal-w', ty.modalWidth);
  setLen('--key-w', ty.keyWidth);
  setLen('--key-h', ty.keyHeight);
  setLen('--avatar', ty.avatar);

  const r = T.radii || {};
  for (const [k, v] of Object.entries(RADIUS_VARS)) setLen(v, r[k]);

  const f = T.focus || {};
  setLen('--ring-w', f.ringWidth);
  setNum('--focus-scale', f.scale);
  setNum('--focus-ms', f.durationMs !== undefined ? `${f.durationMs}ms` : undefined);

  const a = T.anim || {};
  if (a.cardFadeInMs !== undefined) setNum('--card-in-ms', `${a.cardFadeInMs}ms`);
  if (a.rowScrollMs !== undefined) setNum('--row-scroll-ms', `${a.rowScrollMs}ms`);
  if (a.screenFadeMs !== undefined) setNum('--screen-ms', `${a.screenFadeMs}ms`);
  if (a.fadeUpStagger !== undefined) setNum('--stagger', `${a.fadeUpStagger}ms`);

  const cards = T.cards || {};
  const poster = cards.poster || {};
  if (poster.width) {
    setLen('--card-w', poster.width);
    if (poster.aspect) setLen('--card-h', Math.round(poster.width * poster.aspect));
  }
  // The rank numerals are ratios of the art height, so they scale with whatever card size is configured.
  if (cards.rankWidthRatio) setVar('--rank-w', `calc(var(--card-h) * ${cards.rankWidthRatio})`);
  if (cards.rankOverlapRatio) setVar('--rank-overlap', `calc(var(--card-h) * ${cards.rankOverlapRatio})`);
  if (cards.rankWidthRatio && cards.rankOverlapRatio) {
    setVar('--rank-extra', `calc(var(--card-h) * ${cards.rankWidthRatio - cards.rankOverlapRatio})`);
  }
  setLen('--cards-rank-stroke', cards.rankStrokePx);

  const p = T.panel || {};
  setLen('--panel-w', p.width);
  setLen('--panel-row-h', p.rowHeight);
}

/** min(w/1920, h/1080), floored so a phone browser is still readable and capped for very large panels. */
export function computeScale() {
  const w = window.innerWidth || 1920;
  const h = window.innerHeight || 1080;
  const canvas = T.canvas || { width: 1920, height: 1080 };
  const s = Math.min(w / canvas.width, h / canvas.height);
  scale = Math.max(0.5, Math.min(1.2, s));
  setNum('--s', Number(scale.toFixed(4)));
  return scale;
}

export function currentScale() { return scale; }

/** Pixels on this screen for a token measured on the 1920x1080 TV. */
export function px(tvPixels) { return Math.round(tvPixels * scale); }

/** Read one token the way the shell does: theme.token('preview', 'delayMs', 1500). */
export function token(group, key, fallback) {
  const g = T[group];
  if (!g || g[key] === undefined || g[key] === null) return fallback;
  return g[key];
}

export function tokens() { return T; }

export async function load() {
  try {
    const res = await fetch(TOKEN_PATH, { cache: 'no-cache' });
    if (res.ok) {
      const json = await res.json();
      if (json && typeof json === 'object') T = json;
    }
  } catch (e) {
    /* app.css already carries the defaults; the interface is still correct without the JSON. */
  }
  applyTokens();
  computeScale();
  window.addEventListener('resize', () => { computeScale(); });
  return T;
}
