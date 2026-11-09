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

/*
 * The browser renderer is deliberately explicit about the curated packs it knows how to draw.  This is
 * not a theme allow-list — a future authored pack can still take the safe generic path — it is a small
 * handshake between the registry gate and the selectors in packs.css.  If a curated asset changes its
 * structural pack without its renderer changing too, `data-web-pack` becomes `generic` rather than
 * quietly claiming the old composition is still present.
 */
export const CURATED_WEB_PACKS = Object.freeze({
  'scenix-dark':    { hero: 'full', rows: 'poster', card: 'overlay', focus: 'ring' },
  'scenix-midnight': { hero: 'centered', rows: 'landscape', card: 'glass', focus: 'glow' },
  'scenix-ember':   { hero: 'split', rows: 'poster', card: 'frame', focus: 'lift' },
  'scenix-apple':   { homeLayout: 'apple', hero: 'full', rows: 'landscape', card: 'below', focus: 'glow', nav: 'pill' },
  'scenix-netflix': { homeLayout: 'netflix', hero: 'full', rows: 'landscape', card: 'overlay', focus: 'ring', nav: 'classic' },
  'scenix-ps5':     { homeLayout: 'ps5', hero: 'rail', rows: 'landscape', card: 'glass', focus: 'glow', nav: 'ps5' },
  'scenix-kids':    { homeLayout: 'kids', hero: 'full', rows: 'poster', card: 'below', focus: 'glow', nav: 'kids' },
  'scenix-glass':   { homeLayout: 'glass', hero: 'full', rows: 'poster', card: 'below', focus: 'glow', nav: 'clean' },
});

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

function cssName(name) {
  return String(name || '')
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/[^a-zA-Z0-9-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase();
}

/**
 * Eight hex digits mean two different colours depending on which side of the house they were written on:
 * `#AARRGGBB` is ten-percent white to a TV, and opaque cyan to a browser — which is how every border, chip and
 * nav label tinted at once. Both theme files now spell transparency `rgba()`, which each client parses
 * identically, and eight-digit hex is banned from token files by `scripts/check-theme-colours.mjs`.
 *
 * This stays on as a tolerance, not a convention: a value pasted out of an Android studio or a design tool is
 * read the way its author meant it rather than silently repainting the interface. Enforcing the ban with a
 * check that fails costs nothing; enforcing it by letting the page go visibly wrong would re-expose every
 * screen to the original failure. Six digits, three digits and `rgba()` pass straight through.
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

/**
 * The pack is the part of a theme that is not a token: which hero shape, rail shape, card treatment,
 * focus language, motion and detail layout this theme uses. The DOM carries it as data attributes so
 * packs.css can write real layout rules instead of pretending every theme is the same grid repainted.
 */
/**
 * `wallBlobs` is the television's wall: `x,y,r:#RRGGBB:alpha` for each blob of light, every number a
 * percentage of the stage. CSS draws the same field as stacked radial gradients, so the page the browser
 * shows and the page the television shows are lit by the same list of numbers.
 */
function wallImage(raw) {
  const specs = String(raw || '').split(';').map((s) => s.trim()).filter(Boolean);
  const layers = [];
  for (const spec of specs) {
    const bits = spec.split(':');
    const at = (bits[0] || '').split(',').map((n) => Number(n));
    const hex = String(bits[1] || '').trim();
    const alpha = Math.max(0, Math.min(1, Number(bits[2])));
    const m = /^#?([0-9a-fA-F]{6})$/.exec(hex);
    if (at.length < 3 || !at.every(Number.isFinite) || !m || !alpha) continue;
    const rgb = [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16)).join(', ');
    const r = Math.max(2, Math.min(100, at[2]));
    layers.push(`radial-gradient(${r}% ${r}% at ${at[0]}% ${at[1]}%, rgba(${rgb}, ${alpha}) 0%, rgba(${rgb}, 0) 100%)`);
  }
  return layers.join(', ');
}

function applyPack() {
  const p = T.pack || {};
  const attr = (key, fallback, dataKey = key) => {
    const v = String(p[key] === undefined || p[key] === null ? fallback : p[key]).trim();
    root.setAttribute('data-' + dataKey, v || fallback);
  };
  attr('pack', 'classic');
  attr('hero', 'split');
  attr('heroAlign', 'left');
  attr('rows', 'poster');
  attr('card', 'overlay');
  attr('focus', 'ring');
  attr('motion', 'rise');
  attr('detail', 'backdrop');
  attr('nav', 'classic');
  attr('stage', 'off');
  attr('homeLayout', 'classic', 'home-layout');
  attr('homeHero', 'shared-slider', 'home-hero');
  attr('homeRails', 'stacked', 'home-rails');
  attr('homeNavigation', 'top-bar', 'home-navigation');
  /*
   * The wall packs — Liquid Glass, Kids — are not a colour with cards on top of it. The television paints a
   * slow field of soft light behind the whole page, and the token file carries that field as a list of
   * blobs: `x,y:hex:alpha`. Without it the browser shows a flat rectangle where the television shows a room,
   * which is most of why one pack looked like two different products.
   */
  const wall = wallImage(p.wallBlobs);
  if (wall) {
    root.setAttribute('data-wall', 'pack');
    setVar('--wall', wall);
    setNum('--wall-grain', Math.max(0, Math.min(6, Number(p.wallGrain) || 0)) / 100);
  } else {
    root.removeAttribute('data-wall');
    root.style.removeProperty('--wall');
    root.style.removeProperty('--wall-grain');
  }
  const id = String(T.id || 'scenix-dark');
  const signature = CURATED_WEB_PACKS[id];
  const verified = !!signature && Object.entries(signature).every(([key, value]) => p[key] === value);
  root.setAttribute('data-theme', id);
  root.setAttribute('data-web-pack', verified ? 'curated' : 'generic');
  root.style.setProperty('--focus-color',
    p.focusColor ? cssColor(p.focusColor) : 'var(--focus-ring)');

  // Theme contracts expose more than the shared base set (Glass' wall, Kids' focus band, the console
  // rail).  Publish every portable token under a namespace so a renderer can use it without adding a
  // hard-coded parallel palette.  The named variables above remain the backwards-compatible base API.
  const lengthKey = /(?:height|width|bottom|top|pad|gap|inset|size|radius)$/i;
  for (const [key, value] of Object.entries(p)) {
    const name = `--pack-${cssName(key)}`;
    if (typeof value === 'number' && lengthKey.test(key)) setLen(name, value);
    else if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'string') setVar(name, String(value));
  }
}

/**
 * Ink on a filled brand surface, the way the television computes it (Theme.kt: onBrand =
 * black when luminance(brand) > 0.55, white otherwise). The web used to carry a static
 * --on-brand, which was only right for the Apple pack: under a red brand the white
 * knob/check would have been painted in the page's own dark. Mirroring the one formula
 * keeps filled brand surfaces readable in every curated pack without a second value.
 */
function colorLuminance(value) {
  let rgb = null;
  const hex = /^#([0-9a-f]{6,8})$/i.exec(String(value || '').trim());
  if (hex) {
    const rgbHex = hex[1].slice(-6);
    const n = parseInt(rgbHex, 16);
    rgb = [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  } else {
    const rgba = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)/.exec(String(value || '').trim());
    if (rgba) rgb = [Number(rgba[1]), Number(rgba[2]), Number(rgba[3])];
  }
  if (!rgb) return 0;
  const linear = (channel) => {
    const v = Math.max(0, Math.min(1, channel / 255));
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * linear(rgb[0]) + 0.7152 * linear(rgb[1]) + 0.0722 * linear(rgb[2]);
}

function applyTokens() {
  const c = T.colors || {};
  for (const [k, v] of Object.entries(COLOR_VARS)) if (c[k]) setVar(v, cssColor(c[k]));
  if (c.brand) setVar('--on-brand', colorLuminance(c.brand) > 0.55 ? '#000000' : '#ffffff');
  for (const [key, value] of Object.entries(c)) {
    if (typeof value === 'string') setVar(`--theme-color-${cssName(key)}`, cssColor(value));
  }

  const sp = T.spacing || {};
  for (const [k, v] of Object.entries(SPACE_VARS)) setLen(v, sp[k]);
  for (const [key, value] of Object.entries(sp)) setLen(`--theme-space-${cssName(key)}`, value);

  const ty = T.type || {};
  for (const [k, v] of Object.entries(TYPE_VARS)) setLen(v, ty[k]);
  for (const [key, value] of Object.entries(ty)) setLen(`--theme-type-${cssName(key)}`, value);
  setLen('--modal-w', ty.modalWidth);
  setLen('--key-w', ty.keyWidth);
  setLen('--key-h', ty.keyHeight);
  setLen('--avatar', ty.avatar);

  const r = T.radii || {};
  for (const [k, v] of Object.entries(RADIUS_VARS)) setLen(v, r[k]);
  for (const [key, value] of Object.entries(r)) setLen(`--theme-radius-${cssName(key)}`, value);

  const f = T.focus || {};
  setLen('--ring-w', f.ringWidth);
  setNum('--focus-scale', f.scale);
  setNum('--focus-ms', f.durationMs !== undefined ? `${f.durationMs}ms` : undefined);
  for (const [key, value] of Object.entries(f)) {
    if (typeof value === 'string') setVar(`--theme-focus-${cssName(key)}`, cssColor(value));
    else if (typeof value === 'number') setVar(`--theme-focus-${cssName(key)}`, String(value));
  }

  const a = T.anim || {};
  if (a.cardFadeInMs !== undefined) setNum('--card-in-ms', `${a.cardFadeInMs}ms`);
  if (a.rowScrollMs !== undefined) setNum('--row-scroll-ms', `${a.rowScrollMs}ms`);
  if (a.screenFadeMs !== undefined) setNum('--screen-ms', `${a.screenFadeMs}ms`);
  if (a.fadeUpStagger !== undefined) setNum('--stagger', `${a.fadeUpStagger}ms`);

  // The billboard's rotation window is a token like every other motion: one number in theme.json drives
  // both the art crossfade and the copy column's fade.
  const hero = T.hero || {};
  if (hero.rotateFadeMs !== undefined) setNum('--rotate-fade-ms', `${hero.rotateFadeMs}ms`);

  const cards = T.cards || {};
  const poster = cards.poster || {};
  if (poster.width) {
    setLen('--card-poster-w', poster.width);
    setLen('--card-poster-h', Math.round(poster.width * (poster.aspect || 420 / 280)));
    setLen('--card-w', poster.width);
    if (poster.aspect) setLen('--card-h', Math.round(poster.width * poster.aspect));
  }
  /*
   * A rail's tile is a token, not a CSS constant. The web used to draw 320x180 wide rails and 280x420
   * poster rails whatever the pack said, while the television drew the 480x270 tiles the Scene pack ships —
   * the same pack, two different pages. So the two other rail shapes read their own card block, exactly as
   * the poster shape already did: `cards.continue` for a wide shelf, `cards.channel` for the live wall.
   * The fallback is the shape this client had before, so a hub that ships no such block is drawn as before.
   */
  const ratioOf = (c) => (c && c.aspect) || (poster.aspect || 420 / 280);
  if (cards.continue && cards.continue.width) {
    setLen('--card-cont-w', cards.continue.width);
    setLen('--card-cont-h', Math.round(cards.continue.width * ratioOf(cards.continue)));
  }
  if (cards.channel && cards.channel.width) {
    setLen('--card-chan-w', cards.channel.width);
    setLen('--card-chan-h', Math.round(cards.channel.width * ratioOf(cards.channel)));
  }
  // The rank numerals are ratios of the art height, so they scale with whatever card size is configured.
  // The television spells the same three numbers `numeral*` (its Top 10 numeral is a view, not a card
  // treatment); both spellings are read so one token file drives both clients.
  const rankW = cards.rankWidthRatio !== undefined ? cards.rankWidthRatio : cards.numeralWidthRatio;
  const rankOv = cards.rankOverlapRatio !== undefined ? cards.rankOverlapRatio : cards.numeralOverlapRatio;
  const rankStroke = cards.rankStrokePx !== undefined ? cards.rankStrokePx : cards.numeralStrokePx;
  if (rankW) setVar('--rank-w', `calc(var(--card-h) * ${rankW})`);
  if (rankOv) setVar('--rank-overlap', `calc(var(--card-h) * ${rankOv})`);
  if (rankW && rankOv) {
    setVar('--rank-extra', `calc(var(--card-h) * ${rankW - rankOv})`);
  }
  setLen('--cards-rank-stroke', rankStroke);

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

async function fetchTheme() {
  // The hub knows the selected theme; a standalone/fixture host may not have the endpoint, so the
  // bundled token file remains the fallback and app.css remains the last resort.
  try {
    const res = await fetch('/app/v1/theme', { cache: 'no-cache' });
    if (res.ok) {
      const json = await res.json();
      if (json && json.tokens && typeof json.tokens === 'object') return json.tokens;
    }
  } catch (e) { /* older host: try the static file */ }
  try {
    const res = await fetch(TOKEN_PATH, { cache: 'no-cache' });
    if (res.ok) {
      const json = await res.json();
      if (json && typeof json === 'object') return json;
    }
  } catch (e) { /* app.css already carries the defaults */ }
  return null;
}

export async function load() {
  const json = await fetchTheme();
  if (json) T = json;
  applyTokens();
  applyPack();
  computeScale();
  window.addEventListener('resize', () => { computeScale(); });
  return T;
}
