/* Scenix Theme Kit · Phase 0 gallery.
   Dependency-free: fetch the manifests, resolve tokens, render a tokenised CSS
   approximation of every component in every declared state. No app code. */

'use strict';

const LEVELS = ['atom', 'molecule', 'organism', 'template'];

const DEVICES = [
  { id: 'tv', name: 'TV', width: 1920, height: 1080 },
  { id: 'ipad', name: 'iPad', width: 1366, height: 1024 },
  { id: 'iphone', name: 'iPhone', width: 393, height: 852 },
  { id: 'desktop', name: 'Desktop', width: 1440, height: 900 }
];

const TILE_VARIANTS = ['poster', 'landscape', 'rank', 'continue'];


const TILE_SAMPLES = {
  poster: { title: 'Arrival', secondary: '2016 · Science fiction', progress: 0, badge: '4k', caption: 'below' },
  landscape: { title: 'The Last of Us', secondary: 'S1 E4 · 52m', progress: 0, badge: 'hd', caption: 'below' },
  rank: { title: 'Severance', secondary: '2022 · Drama', rank: 2, badge: 'new', caption: 'below' },
  continue: { title: 'Dune: Part Two', secondary: '1h 12m left', progress: 0.46, badge: 'none', caption: 'overlay' }
};

const NAV_ITEMS = [
  { label: 'Home', icon: 'home', route: 'home' },
  { label: 'Movies', icon: 'films', route: 'movies' },
  { label: 'Series', icon: 'series', route: 'series' },
  { label: 'Games', icon: 'play', route: 'games' },
  { label: 'Live', icon: 'live', route: 'live' }
];

const LEFT_ITEMS = [
  { label: 'Switch profile', icon: 'profile', route: 'profile' },
  { label: 'Search', icon: 'search', route: 'search' },
  { label: 'Home', icon: 'home', route: 'home' },
  { label: 'Movies', icon: 'films', route: 'movies' },
  { label: 'Series', icon: 'series', route: 'series' },
  { label: 'Games', icon: 'play', route: 'games' },
  { label: 'Live TV', icon: 'live', route: 'live' },
  { label: 'Settings', icon: 'settings', route: 'settings' }
];

const HERO_ITEMS = [
  { title: 'The Last of Us', secondary: 'S1 E4 · 52m', badge: '4k' },
  { title: 'Dune: Part Two', secondary: '2024 · Science fiction', badge: 'hd' }
];

const HERO_CHIPS = ['For you', 'Movies', 'Series', 'Games', 'Live', 'Kids', 'Top 10'];

const RAIL_ITEMS = [
  { title: 'Arrival', secondary: '2016', progress: 0, badge: '4k' },
  { title: 'Severance', secondary: 'S2 E3', progress: 0, badge: 'new' },
  { title: 'The Last of Us', secondary: 'S1 E4', progress: 0, badge: 'hd' },
  { title: 'Dune: Part Two', secondary: '1h 12m left', progress: 0.46, badge: 'none' },
  { title: 'Past Lives', secondary: '2023', progress: 0, badge: 'none' },
  { title: 'Shōgun', secondary: 'S1 E7', progress: 0, badge: 'new' }
];

const HOME_ROWS = [
  { title: 'Continue watching', variant: 'continue', caption: 'overlay', items: RAIL_ITEMS.slice(3) },
  { title: 'New this week', variant: 'poster', caption: 'below', items: RAIL_ITEMS.slice(0, 4) }
];

const SETTINGS_SECTIONS = [
  {
    title: 'Playback',
    rows: [
      { label: 'Quality ceiling', note: 'The highest copy this TV may ask the hub for', value: '4K HDR', control: 'picker', icon: 'quality' },
      { label: 'Preferred audio', note: 'Language order for the mixer', value: 'English', control: 'picker', icon: 'audio' },
      { label: 'Subtitles', note: 'Forced tracks follow the audio choice', value: 'English', control: 'picker', icon: 'subtitles' }
    ]
  },
  {
    title: 'Household',
    rows: [
      { label: 'Profiles', note: 'People, avatars and kids mode', value: '4', control: 'chevron', icon: 'profile' },
      { label: 'Theme pack', note: 'Tokens and composition for every screen', value: 'Glass', control: 'picker', icon: 'theme' },
      { label: 'Auto-update', note: 'Install new builds overnight', value: 'On', control: 'toggle', icon: 'update', toggleOn: true }
    ]
  }
];

const PLAYER_OPTIONS = ['audio', 'subtitles', 'speed', 'quality', 'episodes'];

const VARIANT_COMPONENTS = new Set([
  'molecule.tile',
  'organism.hero.glass',
  'organism.nav.top',
  'organism.rail',
  'template.detail'
]);

const VARIANT_SAMPLES = {
  'organism.hero.glass': {
    'glass-cards': { ratio: '7:10', titleAlign: 'start' },
    'kids-stage': { ratio: '40:60', titleAlign: 'start' },
    'dual-cards': { ratio: 'equal', titleAlign: 'center' }
  },
  'organism.nav.top': {
    pill: { active: 'home' },
    kids: { active: 'movies' },
    classic: { active: 'home' }
  },
  'organism.rail': {
    poster: { caption: 'below' },
    landscape: { caption: 'below' },
    rank: { caption: 'below' },
    continue: { caption: 'overlay' }
  },
  'template.detail': {
    backdrop: { showReactions: true },
    split: { showReactions: false },
    stage: { showReactions: true }
  }
};

const SAMPLES = {
  'atom.icon': { name: 'play', size: 34, tone: 'ink', stroke: 2 },
  'atom.label': { text: 'Continue watching', type: 'rowTitle', tone: 'ink', weight: 'bold', maxLines: 1 },
  'atom.button': { label: 'Play', kind: 'primary', icon: 'play' },
  'atom.chip': { label: 'Films', icon: 'films', selected: true, tone: 'glass', count: 12 },
  'atom.focus-ring': { width: 2, radius: 21, inset: 26, visible: true },
  'atom.surface': { tone: 'glass', radius: 21, elevation: 16, border: true, blur: true },
  'atom.avatar': { name: 'Alex', initials: 'A', size: 96, ring: true, badge: 'kid' },
  'atom.progress': { value: 0.62, buffered: 0.84, kind: 'linear', thickness: 5, tone: 'brand', showValue: true },
  'molecule.tile': { progress: 0, badge: 'none', caption: 'below' },
  'molecule.nav-item': { label: 'Home', icon: 'home', active: true, count: 3, collapsed: false, tone: 'pill' },
  'molecule.settings-row': {
    label: 'Auto-update', note: 'Install new builds overnight', value: 'On',
    chevron: false, control: 'toggle', icon: 'update', toggleOn: true
  },
  'molecule.seek-bar': { position: 0.38, buffered: 0.61, duration: 5400, live: false, knob: true, chapters: true, hint: 'time' },
  'organism.hero.glass': {
    items: HERO_ITEMS, max: 2, ratio: '7:10', variant: 'glass-cards', halo: true,
    chips: HERO_CHIPS, maxChips: 8, titleAlign: 'start'
  },
  'organism.nav.top': {
    items: NAV_ITEMS, variant: 'pill', active: 'home', search: true, live: true,
    wordmark: true, compact: false, profile: { name: 'Alex', initials: 'A' }
  },
  'organism.rail': {
    title: 'Continue watching', items: RAIL_ITEMS, total: 24, variant: 'poster',
    caption: 'below', seeAll: true, maxItems: 18, showCount: true
  },
  'organism.rail.left': {
    items: LEFT_ITEMS, open: true, active: 'home', width: 340, scrim: true,
    profileFirst: true, focusFill: '#FFFFFF', labels: true
  },
  'template.home': {
    title: 'Home', family: 'glass', nav: NAV_ITEMS, hero: HERO_ITEMS, chips: HERO_CHIPS,
    rows: HOME_ROWS, safeArea: 'tv', scroll: true
  },
  'template.detail': {
    title: 'The Last of Us', variant: 'backdrop', art: '', overview:
      'Twenty years after a fungal outbreak, a hardened survivor is hired to smuggle a teenager across a ruined country.',
    meta: '96% match · 2023 · TV-MA · S1 E4 · Drama, Adventure',
    actions: [
      { label: 'Resume', kind: 'primary', icon: 'play' },
      { label: '+ My List', kind: 'secondary' },
      { label: '▶ Trailer', kind: 'ghost' }
    ],
    reactions: ['seen', 'like', 'love', 'dislike'], episodes: RAIL_ITEMS.slice(0, 4),
    related: RAIL_ITEMS.slice(1, 5), progress: 0.46, showReactions: true
  },
  'template.settings': {
    title: 'Settings', sections: SETTINGS_SECTIONS, status: 'All changes saved',
    picker: {
      title: 'Quality ceiling',
      options: [
        { label: 'Auto', value: 'Best available' },
        { label: '4K HDR', value: 'Selected' },
        { label: '1080p', value: 'Safer on Wi-Fi' }
      ]
    },
    wall: 'pack', back: true, pickerOpen: true
  },
  'template.player': {
    title: 'The Last of Us', subtitle: 'S1 E4 · Please Hold to My Hand',
    mode: 'vod', position: 0.38, buffered: 0.61, duration: 3180, playing: true,
    options: PLAYER_OPTIONS, hint: 'Timeline · LEFT / RIGHT to seek',
    skipIntro: true, nextEpisode: true, speed: 1
  }
};

const ART_PALETTES = [
  ['#1d4f7a', '#5f8fbd', '#122a44'],
  ['#5c3b6e', '#b07aa8', '#26162f'],
  ['#7a3b2e', '#d18a63', '#31150f'],
  ['#245c50', '#77b39d', '#0e2c25'],
  ['#4a4d7a', '#9fa4d8', '#1d1e36'],
  ['#735c2e', '#d5b56a', '#2d2310']
];

const ICONS = {
  play: '<path d="M8 5.2v13.6L19 12z" fill="currentColor" stroke="none"/>',
  pause: '<path d="M8 5h3v14H8zM13 5h3v14h-3z" fill="currentColor" stroke="none"/>',
  back: '<path d="M15 5l-7 7 7 7"/>',
  forward: '<path d="M9 5l7 7-7 7"/>',
  rewind: '<path d="M11 6L5 12l6 6"/><path d="M19 6l-6 6 6 6"/>',
  audio: '<path d="M4 9v6h4l5 4V5L8 9H4z"/><path d="M17 9a4 4 0 010 6"/>',
  subtitles: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 14h4M13 14h4"/>',
  speed: '<path d="M12 20a8 8 0 118-8"/><path d="M12 12l4.4-4.4"/><circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none"/>',
  quality: '<rect x="3" y="6" width="18" height="12" rx="2"/><text x="12" y="15.3" text-anchor="middle" font-size="7" font-weight="700" font-family="system-ui" fill="currentColor" stroke="none">4K</text>',
  episodes: '<rect x="4" y="5" width="16" height="14" rx="2"/><path d="M8 9h8M8 13h8M8 17h5"/>',
  search: '<circle cx="11" cy="11" r="6"/><path d="M16 16l4 4"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M12 3v2.5M12 18.5V21M3 12h2.5M18.5 12H21M5.8 5.8l1.8 1.8M16.4 16.4l1.8 1.8M18.2 5.8l-1.8 1.8M7.6 16.4l-1.8 1.8"/>',
  theme: '<circle cx="12" cy="12" r="3"/><path d="M12 3v2.5M12 18.5V21M3 12h2.5M18.5 12H21M5.8 5.8l1.8 1.8M16.4 16.4l1.8 1.8M18.2 5.8l-1.8 1.8M7.6 16.4l-1.8 1.8"/>',
  update: '<path d="M12 3v10M8 9l4 4 4-4"/><path d="M5 17v3h14v-3"/>',
  lock: '<rect x="5" y="10" width="14" height="10" rx="2"/><path d="M8 10V7a4 4 0 018 0v3"/>',
  profile: '<circle cx="12" cy="8" r="3.5"/><path d="M5 20c.7-3.6 3.5-5.5 7-5.5s6.3 1.9 7 5.5"/>',
  chevron: '<path d="M9 5l7 7-7 7"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  check: '<path d="M5 12.5l4.2 4.2L19 7"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  home: '<path d="M4 11l8-7 8 7"/><path d="M6.5 9.5V20h11V9.5"/>',
  films: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 5v14M17 5v14M3 9h4M17 9h4M3 15h4M17 15h4"/>',
  series: '<rect x="3" y="7" width="18" height="12" rx="2"/><path d="M7 3h10"/>',
  kids: '<circle cx="12" cy="12" r="8"/><path d="M9 10h.01M15 10h.01M8.5 14.5c1 1 2 1.5 3.5 1.5s2.5-.5 3.5-1.5"/>',
  live: '<circle cx="12" cy="12" r="2" fill="currentColor" stroke="none"/><path d="M8 8a5.5 5.5 0 000 8M16 8a5.5 5.5 0 010 8M5 5a9.5 9.5 0 000 14M19 5a9.5 9.5 0 010 14"/>'
};

const state = {
  components: [],
  themes: [],
  theme: null,
  device: DEVICES[0],
  selectedId: null,
  levelFilter: 'all',
  copyTimer: null
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

function esc(value) {
  return String(value == null ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function iconSvg(name, stroke = 2) {
  const path = ICONS[name] || ICONS.chevron;
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${Number(stroke) || 2}" ` +
    `stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">${path}</svg>`;
}

function formatBytes(bytes) {
  if (!bytes) return 'no images';
  if (bytes < 1024) return `${bytes} B`;
  return `${Math.round(bytes / 1024)} KB`;
}

function pad2(n) {
  return String(Math.floor(n)).padStart(2, '0');
}

function formatTime(seconds) {
  const total = Math.max(0, Math.floor(seconds || 0));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${pad2(m)}:${pad2(s)}` : `${m}:${pad2(s)}`;
}

function initials(title) {
  return String(title || '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0])
    .join('')
    .toUpperCase() || 'S';
}

function artVars(title) {
  let hash = 0;
  for (let i = 0; i < String(title).length; i += 1) {
    hash = (hash * 31 + String(title).charCodeAt(i)) >>> 0;
  }
  const palette = ART_PALETTES[hash % ART_PALETTES.length];
  return `--art-a:${palette[0]};--art-b:${palette[1]};--art-c:${palette[2]}`;
}

/* ---------------- data loading ---------------- */

async function fetchJSON(path) {
  const response = await fetch(path, { cache: 'no-store' });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText} · ${path}`);
  return response.json();
}

function resolveThemes(themes) {
  const byId = new Map(themes.map((theme) => [theme.id, theme]));
  const memo = new Map();

  function mergeTokens(base, override) {
    const out = {};
    for (const group of ['color', 'space', 'radius', 'type', 'focus', 'anim', 'player', 'pack']) {
      out[group] = { ...(base && base[group] ? base[group] : {}), ...(override && override[group] ? override[group] : {}) };
    }
    return out;
  }

  function resolve(theme) {
    if (memo.has(theme.id)) return memo.get(theme.id);
    let tokens = { ...theme.tokens };
    if (theme.basedOn && byId.has(theme.basedOn)) {
      const parent = resolve(byId.get(theme.basedOn));
      tokens = mergeTokens(parent.tokens, theme.tokens);
    }
    const out = { ...theme, tokens };
    memo.set(theme.id, out);
    return out;
  }

  return themes.map(resolve);
}

async function loadData() {
  const [componentIndex, themeIndex] = await Promise.all([
    fetchJSON('../components/index.json'),
    fetchJSON('../themes/index.json')
  ]);

  const [components, rawThemes] = await Promise.all([
    Promise.all(componentIndex.components.map((file) => fetchJSON(`../components/${file}`))),
    Promise.all(themeIndex.themes.map((file) => fetchJSON(`../themes/${file}`)))
  ]);

  state.components = components.sort((a, b) => {
    const level = LEVELS.indexOf(a.level) - LEVELS.indexOf(b.level);
    return level !== 0 ? level : a.id.localeCompare(b.id);
  });
  state.themes = resolveThemes(rawThemes);
}

/* ---------------- theme tokens ---------------- */

function applyTheme() {
  const theme = state.theme;
  const stage = $('#device-stage');
  if (!theme || !stage) return;

  const tokens = theme.tokens || {};
  const colors = tokens.color || {};
  const spaces = tokens.space || {};
  const radii = tokens.radius || {};
  const type = tokens.type || {};
  const focus = tokens.focus || {};
  const anim = tokens.anim || {};
  const pack = tokens.pack || {};

  for (const [key, value] of Object.entries(colors)) stage.style.setProperty(`--color-${key}`, value);
  for (const [key, value] of Object.entries(spaces)) stage.style.setProperty(`--space-${key}`, Number.isFinite(Number(value)) ? `${value}px` : value);
  for (const [key, value] of Object.entries(radii)) stage.style.setProperty(`--radius-${key}`, Number.isFinite(Number(value)) ? `${value}px` : value);
  for (const [key, value] of Object.entries(type)) {
    const size = value && typeof value === 'object' ? value.size : value;
    if (Number.isFinite(Number(size))) stage.style.setProperty(`--type-${key}`, `${size}px`);
  }
  for (const [key, value] of Object.entries(anim)) {
    if (Number.isFinite(Number(value))) stage.style.setProperty(`--anim-${key}`, `${value}ms`);
  }

  stage.dataset.focusStyle = focus.style || 'ring';
  stage.dataset.theme = theme.id;
  stage.style.setProperty('--focus-ring-width', focus.ringWidth != null ? focus.ringWidth : 2);
  stage.style.setProperty('--focus-scale', focus.scale != null ? focus.scale : 1.04);
  stage.style.setProperty('--focus-glow-inset', focus.glowInset != null ? focus.glowInset : 22);
  stage.style.setProperty('--focus-color', pack.focusColor || colors.focusRing || '#FFFFFF');

  let background = colors.bg || '#000000';
  if (colors.stageYellow && colors.stageDeep) {
    background = `linear-gradient(180deg, ${colors.stageYellow}, ${colors.stageDeep})`;
  } else if (colors.wallA && colors.wallB) {
    const scrim = colors.scrim || 'rgba(0,0,0,0.35)';
    background = `linear-gradient(${scrim}, ${scrim}), radial-gradient(120% 95% at 18% 6%, ${colors.wallA} 0%, transparent 58%), linear-gradient(155deg, ${colors.wallA}, ${colors.wallB})`;
  }
  stage.style.background = background;
  stage.style.color = colors.ink || '#FFFFFF';
}

/* ---------------- stage rendering ---------------- */

function cssId(id) {
  return id.replace(/[^a-zA-Z0-9_-]/g, '-');
}

function currentVariant(component) {
  if (!VARIANT_COMPONENTS.has(component.id)) return '';
  const enums = component.props && component.props.variant && component.props.variant.enum;
  return enums && enums.length ? enums[0] : '';
}

function buildCases(component) {
  if (VARIANT_COMPONENTS.has(component.id)) {
    const variants = component.props.variant && component.props.variant.enum ? component.props.variant.enum : [];
    return variants.flatMap((variant) => component.states.map((stateName) => ({
      variant,
      state: stateName,
      label: `${variant} · ${stateName}`
    })));
  }
  return component.states.map((stateName) => ({ variant: '', state: stateName, label: stateName }));
}

function sampleFor(component, variant) {
  const base = { ...(SAMPLES[component.id] || {}) };
  if (component.id === 'molecule.tile') {
    Object.assign(base, TILE_SAMPLES[variant] || TILE_SAMPLES.poster);
  } else if (VARIANT_SAMPLES[component.id] && VARIANT_SAMPLES[component.id][variant]) {
    Object.assign(base, VARIANT_SAMPLES[component.id][variant]);
  }
  if (variant) base.variant = variant;
  if (component.id === 'template.detail') {
    base.kids = variant === 'split';
    base.glass = variant !== 'split';
  }
  return base;
}

function classesFor(component, stateName) {
  const classes = [`state-${stateName}`];
  if (stateName === 'focus' && (component.level === 'atom' || component.level === 'molecule')
    && component.id !== 'atom.focus-ring') classes.push('is-focus');
  return classes.join(' ');
}

function renderCase(component, sample, stateName, variant) {
  const classes = classesFor(component, stateName);
  switch (component.id) {
    case 'atom.icon': return renderIcon(sample, stateName, classes);
    case 'atom.label': return renderLabel(sample, stateName, classes);
    case 'atom.button': return renderButton(sample, stateName, classes);
    case 'atom.chip': return renderChip(sample, stateName, classes);
    case 'atom.focus-ring': return renderFocusRing(sample, stateName, classes);
    case 'atom.surface': return renderSurface(sample, stateName, classes);
    case 'atom.avatar': return renderAvatar(sample, stateName, classes);
    case 'atom.progress': return renderProgress(sample, stateName, classes);
    case 'molecule.tile': return renderTile(sample, stateName, classes, variant);
    case 'molecule.nav-item': return renderNav(sample, stateName, classes);
    case 'molecule.settings-row': return renderSettingsRow(sample, stateName, classes);
    case 'molecule.seek-bar': return renderSeekBar(sample, stateName, classes);
    case 'organism.hero.glass': return renderHeroGlass(sample, stateName, classes);
    case 'organism.nav.top': return renderNavTop(sample, stateName, classes);
    case 'organism.rail': return renderRail(sample, stateName, classes);
    case 'organism.rail.left': return renderRailLeft(sample, stateName, classes);
    case 'template.home': return renderTemplateHome(sample, stateName, classes);
    case 'template.detail': return renderTemplateDetail(sample, stateName, classes, variant);
    case 'template.settings': return renderTemplateSettings(sample, stateName, classes);
    case 'template.player': return renderTemplatePlayer(sample, stateName, classes);
    default: return `<div class="c-surface ${classes}"><span class="surface-sub">${esc(component.id)} has no gallery renderer yet.</span></div>`;
  }
}

function renderIcon(sample, stateName, classes) {
  return `<span class="c-icon ${classes} tone-${esc(sample.tone || 'ink')}" ` +
    `style="--icon-size:${Number(sample.size) || 24}px" role="img" aria-label="${esc(sample.name)} icon">` +
    iconSvg(sample.name, sample.stroke) + '</span>';
}

function renderLabel(sample, stateName, classes) {
  const text = stateName === 'empty' ? 'No text' : sample.text;
  return `<span class="c-label ${classes} type-${esc(sample.type || 'li')} tone-${esc(sample.tone || 'ink')} ` +
    `weight-${esc(sample.weight || 'regular')} align-${esc(sample.align || 'start')} ` +
    `${sample.uppercase ? 'upper' : ''} ${sample.truncate !== false ? 'truncate' : ''}">${esc(text)}</span>`;
}

function renderButton(sample, stateName, classes) {
  const disabled = stateName === 'disabled';
  const icon = sample.icon && sample.icon !== 'none'
    ? `<span class="c-icon">${iconSvg(sample.icon)}</span>` : '';
  const badge = sample.badge ? `<span class="c-btn-badge">${esc(sample.badge)}</span>` : '';
  return `<button type="button" class="c-btn kind-${esc(sample.kind || 'primary')} ${sample.wide ? 'wide' : ''} ${classes}" ` +
    `${disabled ? 'disabled aria-disabled="true"' : ''}>` +
    `${icon}<span class="c-btn-label">${esc(sample.label)}</span>${badge}<span class="c-spinner" aria-hidden="true"></span></button>`;
}

function renderChip(sample, stateName, classes) {
  const selected = sample.selected || stateName === 'selected';
  const icon = sample.icon && sample.icon !== 'none'
    ? `<span class="c-icon">${iconSvg(sample.icon)}</span>` : '';
  const count = sample.count != null ? `<span class="c-chip-count">${esc(sample.count)}</span>` : '';
  return `<span class="c-chip tone-${esc(sample.tone || 'glass')} ${selected ? 'is-selected' : ''} ${classes}">` +
    `${icon}<span>${esc(sample.label)}</span>${count}</span>`;
}

function renderFocusRing(sample, stateName, classes) {
  const focus = (state.theme && state.theme.tokens.focus) || {};
  const pack = (state.theme && state.theme.tokens.pack) || {};
  const style = focus.style || 'ring';
  const width = sample.width != null ? sample.width : (focus.ringWidth || 2);
  const radius = sample.radius != null ? sample.radius : 21;
  const glow = sample.inset != null ? sample.inset : (focus.glowInset || 24);
  return `<div class="c-focus-demo focus-${esc(style)} ${classes}" ` +
    `style="--ring-w:${Number(width) || 2};--ring-glow:${Number(glow) || 24};--ring-radius:${Number(radius) || 14}px">` +
    `<div class="focus-target" style="border-radius:${Number(radius) || 14}px">${esc(pack.name || 'Scene')}</div>` +
    '<span class="focus-outline" aria-hidden="true"></span></div>';
}

function renderSurface(sample, stateName, classes) {
  return `<div class="c-surface tone-${esc(sample.tone || 'card')} ${sample.blur ? 'blur' : ''} ${classes}" ` +
    `style="--surface-radius:${Number(sample.radius) || 14}px;--surface-elev:${Number(sample.elevation) || 0}px">` +
    '<span class="surface-kicker">surface · ' + esc(sample.tone || 'card') + '</span>' +
    '<span class="surface-title">Glass plate</span>' +
    `<span class="surface-sub">radius ${Number(sample.radius) || 14} · ${sample.blur ? 'one blur layer' : 'flat fill'}</span></div>`;
}

function renderAvatar(sample, stateName, classes) {
  const size = Number(sample.size) || 64;
  const special = stateName === 'loading' ? 'avatar-loading'
    : stateName === 'error' ? 'avatar-error'
      : stateName === 'empty' ? 'avatar-empty' : '';
  const badge = sample.badge && sample.badge !== 'none'
    ? `<span class="avatar-badge">${esc(String(sample.badge).toUpperCase())}</span>` : '';
  return `<span class="c-avatar ${special} ${classes}" style="--avatar-size:${size}px" role="img" aria-label="${esc(sample.name)} profile">` +
    `<span class="avatar-disc"><span class="avatar-initial">${esc(sample.initials || initials(sample.name))}</span></span>${badge}</span>`;
}

function renderProgress(sample, stateName, classes) {
  const value = stateName === 'complete' ? 1 : Math.max(0, Math.min(1, Number(sample.value) || 0));
  const buffered = Math.max(0, Math.min(1, Number(sample.buffered) || 0));
  const thickness = Number(sample.thickness) || 4;
  const tone = sample.tone || 'brand';
  const kind = sample.kind || 'linear';
  if (kind === 'ring') {
    return `<div class="c-progress-ring ${classes}" style="--ring-progress:${value * 360}deg;--progress-color:var(--color-${tone === 'ink' ? 'ink' : tone === 'match' ? 'match' : 'brand'},#fff)">` +
      `<span class="progress-readout">${Math.round(value * 100)}%</span></div>`;
  }
  return `<div class="c-progress ${kind === 'indeterminate' ? 'progress-indeterminate' : ''} ${classes}" ` +
    `style="--progress-thickness:${thickness};--progress-color:var(--color-${tone === 'ink' ? 'ink' : tone === 'match' ? 'match' : 'brand'},#fff)">` +
    `<div class="progress-track"><span class="progress-buffer" style="width:${buffered * 100}%"></span>` +
    `<span class="progress-fill${tone === 'ink' ? ' tone-ink' : tone === 'match' ? ' tone-match' : ''}" style="width:${value * 100}%"></span></div>` +
    `${sample.showValue ? `<span class="progress-label">${Math.round(value * 100)}% played · ${Math.round(buffered * 100)}% buffered</span>` : ''}</div>`;
}

function renderTile(sample, stateName, classes, variant) {
  const caption = sample.caption || 'below';
  const progress = stateName === 'complete' ? 1 : Math.max(0, Math.min(1, Number(sample.progress) || 0));
  const badge = sample.badge && sample.badge !== 'none'
    ? `<span class="tile-badge">${esc(sample.badge)}</span>` : '';
  const rank = variant === 'rank' ? `<span class="tile-rank">${esc(sample.rank || 1)}</span>` : '';
  const play = (variant === 'continue' && stateName !== 'empty' && stateName !== 'loading')
    ? `<span class="tile-play"><span class="c-icon">${iconSvg('play')}</span></span>` : '';
  const progressBar = progress > 0
    ? `<span class="tile-progress"><i style="width:${progress * 100}%"></i></span>` : '';
  const selected = stateName === 'selected'
    ? `<span class="tile-selected-mark"><span class="c-icon">${iconSvg('check')}</span></span>` : '';
  const captionHtml = caption === 'none' ? '' :
    `<span class="tile-caption${caption === 'overlay' ? ' overlay' : ''}">` +
    `<span class="tile-title">${esc(sample.title)}</span>` +
    `<span class="tile-sub">${esc(sample.secondary || '')}</span></span>`;

  return `<article class="c-tile variant-${esc(variant)} ${classes}" style="${artVars(sample.title)}">` +
    `<span class="tile-art">` +
    '<i class="art-shape s1"></i><i class="art-shape s2"></i>' +
    `<span class="art-monogram">${esc(initials(sample.title))}</span>` +
    `${badge}${rank}${play}${progressBar}${selected}` +
    (caption === 'overlay' ? captionHtml : '') +
    '</span>' +
    (caption === 'overlay' ? '' : captionHtml) +
    '</article>';
}

function renderNav(sample, stateName, classes) {
  const active = sample.active || stateName === 'selected';
  const icon = sample.icon ? `<span class="c-icon">${iconSvg(sample.icon)}</span>` : '';
  const count = sample.count != null ? `<span class="c-nav-count">${esc(sample.count)}</span>` : '';
  return `<span class="c-nav tone-${esc(sample.tone || 'plain')} ${active ? 'is-active' : ''} ${classes}">` +
    `${icon}<span>${esc(sample.label)}</span>${count}</span>`;
}

function renderSettingsRow(sample, stateName, classes) {
  const disabled = stateName === 'disabled';
  const icon = sample.icon && sample.icon !== 'none'
    ? `<span class="row-icon">${iconSvg(sample.icon)}</span>` : '';
  let control = '';
  if (sample.control === 'toggle') {
    control = `<span class="row-toggle ${sample.toggleOn ? 'on' : ''}"><i></i></span>`;
  } else if (sample.control === 'slider') {
    control = '<span class="row-mini-track"><i></i></span>';
  } else if (sample.control === 'picker') {
    control = `<span class="row-control"><span class="c-icon">${iconSvg('chevron')}</span></span>`;
  } else if (sample.control === 'chevron' || sample.chevron) {
    control = `<span class="row-control"><span class="c-icon">${iconSvg('chevron')}</span></span>`;
  }
  return `<div class="c-row ${classes}" ${disabled ? 'aria-disabled="true"' : ''}>` +
    `${icon}<span class="row-copy"><span class="row-label">${esc(sample.label)}</span>` +
    `<span class="row-note">${esc(sample.note || '')}</span></span>` +
    `<span class="row-value">${esc(sample.value || '')}</span>${control}</div>`;
}

function renderSeekBar(sample, stateName, classes) {
  const position = stateName === 'live' ? 1 : Math.max(0, Math.min(1, Number(sample.position) || 0));
  const buffered = Math.max(position, Math.max(0, Math.min(1, Number(sample.buffered) || 0)));
  const duration = Number(sample.duration) || 0;
  const knob = sample.knob !== false && stateName !== 'live'
    ? `<span class="seek-knob" style="left:${position * 100}%"></span>` : '';
  const chapters = sample.chapters
    ? [0.25, 0.5, 0.75].map((fraction) => `<span class="seek-chapter" style="left:${fraction * 100}%"></span>`).join('') : '';
  const head = stateName === 'live'
    ? `<span class="seek-live">LIVE</span><span>${formatTime(duration)}</span>`
    : `<span>${formatTime(duration * position)}</span><span>${formatTime(duration)}</span>`;
  const hint = stateName === 'buffering' ? 'Buffering…'
    : sample.hint === 'chapter' ? 'Chapter 4 · The Long Way Home'
      : sample.hint === 'time' ? 'LEFT / RIGHT to seek' : '';
  return `<div class="c-seek ${classes}" style="--seek-thickness:7;--seek-knob:9">` +
    `<span class="seek-head">${head}</span>` +
    `<span class="seek-track">` +
    `<span class="seek-buffer" style="width:${buffered * 100}%"></span>` +
    `<span class="seek-played" style="width:${position * 100}%"></span>` +
    `${chapters}${knob}</span>` +
    `${hint ? `<span class="seek-hint">${esc(hint)}</span>` : ''}</div>`;
}

/* ---------------- organisms ---------------- */

function chipMarkup(label, icon, selected, tone = 'glass') {
  const glyph = icon && icon !== 'none' ? `<span class="c-icon">${iconSvg(icon)}</span>` : '';
  return `<span class="c-chip tone-${esc(tone)}${selected ? ' is-selected' : ''}">` +
    `${glyph}<span>${esc(label)}</span></span>`;
}

function navAction(icon, cls = '') {
  return `<span class="nav-action ${cls}" aria-hidden="true">${iconSvg(icon)}</span>`;
}

function renderHeroGlass(sample, stateName, classes) {
  const variant = sample.variant || 'glass-cards';
  const items = (Array.isArray(sample.items) ? sample.items : HERO_ITEMS)
    .slice(0, Math.max(1, Number(sample.max) || 2));
  const chips = (Array.isArray(sample.chips) ? sample.chips : HERO_CHIPS)
    .slice(0, Math.max(0, Number(sample.maxChips) || 8));
  const ratio = String(sample.ratio || '7:10').replace(':', '-');
  const loading = stateName === 'loading';
  const empty = stateName === 'empty';
  const focus = stateName === 'focus';
  const pressed = stateName === 'pressed';

  const plates = items.map((item, index) => {
    const deep = index === 0;
    const art = artVars(item.title || 'Scene');
    if (loading) return `<article class="hero-plate loading ${deep ? 'deep' : ''}" style="${art}"></article>`;
    if (empty) return `<article class="hero-plate empty ${deep ? 'deep' : ''}"><span>No artwork</span></article>`;
    return `<article class="hero-plate ${deep ? 'deep' : ''} ${focus && index === 0 ? 'plate-focus' : ''} ` +
      `${pressed && index === 0 ? 'plate-pressed' : ''}" style="${art}">` +
      '<i class="hero-art-shape s1"></i><i class="hero-art-shape s2"></i>' +
      `<span class="hero-plate-monogram">${esc(initials(item.title))}</span>` +
      '<span class="hero-plate-kicker">Featured</span>' +
      `<span class="hero-plate-title">${esc(item.title)}</span>` +
      `<span class="hero-plate-sub">${esc(item.secondary || '')}</span>` +
      `<span class="hero-play">${iconSvg('play')}</span>` +
      `${item.badge ? `<span class="hero-badge">${esc(item.badge)}</span>` : ''}` +
      '</article>';
  }).join('');

  const copy = empty
    ? '<span class="hero-empty-title">No featured titles</span>'
    : '<span class="hero-eyebrow">Tonight on Scenix</span>' +
      '<h3 class="hero-heading">Pick up where you left off</h3>' +
      '<p class="hero-dek">Two household favourites, one press away.</p>' +
      `<span class="hero-cta">${iconSvg('play')} Play</span>`;
  const chipHtml = empty ? '' : chips.map((chip, index) => chipMarkup(chip, index === 0 ? 'play' : 'none', index === 0)).join('');

  return `<div class="c-heroglass variant-${esc(variant)} ratio-${esc(ratio)} ${classes}">` +
    `<div class="hero-copy ${sample.titleAlign === 'center' ? 'align-center' : ''}">${copy}</div>` +
    `<div class="hero-plates">${plates}</div>` +
    `<div class="hero-chip-band">${chipHtml}</div>` +
    '</div>';
}

function renderNavTop(sample, stateName, classes) {
  const variant = sample.variant || 'pill';
  const active = String(sample.active || 'home').toLowerCase();
  const items = (Array.isArray(sample.items) ? sample.items : NAV_ITEMS).slice(0, 5);
  const focus = stateName === 'focus';
  const stops = items.map((item, index) => {
    const selected = String(item.route || item.label).toLowerCase() === active;
    const isFocus = focus && index === 0;
    return `<span class="nav-stop${selected ? ' is-active' : ''}${isFocus ? ' nav-focus' : ''}">` +
      `${item.icon ? `<span class="c-icon">${iconSvg(item.icon)}</span>` : ''}` +
      `<span>${esc(item.label)}</span></span>`;
  }).join('');
  const profile = `<span class="nav-profile" aria-label="${esc((sample.profile && sample.profile.name) || 'Profile')}">` +
    `${esc((sample.profile && sample.profile.initials) || 'P')}</span>`;
  const search = sample.search ? navAction('search', 'nav-search') : '';
  const gear = navAction('settings');
  const brand = sample.wordmark !== false ? '<span class="nav-wordmark">SCENIX</span>' : '';

  if (variant === 'kids') {
    return `<nav class="c-navtop variant-kids ${classes}">` +
      '<span class="nav-brand-disc">S</span>' +
      `<span class="nav-kids-pill">${stops}${search}</span>` +
      `<span class="nav-household">${profile}${gear}</span></nav>`;
  }
  if (variant === 'classic') {
    return `<nav class="c-navtop variant-classic ${classes}">${brand}` +
      `<span class="nav-classic-links">${stops}</span>` +
      `<span class="nav-household">${profile}${search}${gear}</span></nav>`;
  }
  return `<nav class="c-navtop variant-pill ${classes}">${brand}` +
    `<span class="nav-pill">${profile}${stops}${search}</span>` +
    `<span class="nav-household">${gear}</span></nav>`;
}

function renderRail(sample, stateName, classes) {
  const variant = sample.variant || 'poster';
  const caption = sample.caption || 'below';
  const items = (Array.isArray(sample.items) ? sample.items : RAIL_ITEMS)
    .slice(0, Math.max(1, Number(sample.maxItems) || 18));
  const seeAll = Boolean(sample.seeAll) || (Number(sample.total) || 0) > items.length;
  const count = sample.showCount && sample.total ? `<span class="rail-count">${esc(sample.total)}</span>` : '';
  const head = `<div class="rail-head"><span class="rail-title">${esc(sample.title || 'Shelf')}</span>${count}` +
    `${seeAll ? `<span class="rail-seeall-link">See all <span class="c-icon">${iconSvg('chevron')}</span></span>` : ''}</div>`;

  if (stateName === 'loading') {
    return `<div class="c-rail ${classes}">${head}<div class="rail-track">` +
      Array.from({ length: 4 }).map(() => '<span class="rail-skeleton"></span>').join('') +
      '</div></div>';
  }
  if (stateName === 'empty') {
    return `<div class="c-rail ${classes}">${head}<div class="rail-empty">Nothing on this shelf yet.</div></div>`;
  }
  if (stateName === 'error') {
    return `<div class="c-rail ${classes}">${head}<div class="rail-empty error">This shelf did not load. ` +
      '<span class="rail-retry">Retry</span></div></div>';
  }

  const tiles = items.map((item, index) => {
    const tileState = stateName === 'focus' && index === 0 ? 'focus'
      : stateName === 'pressed' && index === 0 ? 'pressed' : 'default';
    const tileClasses = classesFor({ level: 'molecule', id: 'molecule.tile' }, tileState);
    return renderTile({ ...item, variant, caption }, tileState, tileClasses, variant);
  }).join('');
  const seeAllCard = seeAll
    ? `<article class="rail-seeall"><span class="rail-seeall-disc">${iconSvg('forward')}</span><span>See all</span></article>`
    : '';
  return `<div class="c-rail ${classes}">${head}<div class="rail-track">${tiles}${seeAllCard}</div></div>`;
}

function renderRailLeft(sample, stateName, classes) {
  const items = (Array.isArray(sample.items) ? sample.items : LEFT_ITEMS).slice(0, 8);
  const active = String(sample.active || 'home').toLowerCase();
  const focus = stateName === 'focus';
  const rows = items.map((item, index) => {
    const selected = String(item.route || item.label).toLowerCase() === active;
    const isFocus = focus && index === 0;
    return `<span class="railleft-row${selected ? ' is-selected' : ''}${isFocus ? ' row-focus' : ''}">` +
      `<span class="railleft-icon">${iconSvg(item.icon || 'chevron')}</span>` +
      `<span class="railleft-label">${esc(item.label)}</span>` +
      `${selected ? `<span class="railleft-mark">${iconSvg('check')}</span>` : ''}</span>`;
  }).join('');
  return `<div class="c-railleft ${classes}">` +
    '<span class="railleft-scrim"></span>' +
    `<div class="railleft-panel"><span class="railleft-brand">SCENIX</span>${rows}</div>` +
    '<div class="railleft-page"><span></span><span></span><span></span><span></span></div>' +
    '</div>';
}

/* ---------------- templates ---------------- */

function pageMessage(kind, title, body) {
  return `<div class="c-page page-message ${kind}"><span class="page-message-mark">${iconSvg(kind === 'error' ? 'close' : 'films')}</span>` +
    `<b>${esc(title)}</b><span>${esc(body)}</span></div>`;
}

function renderTemplateHome(sample, stateName, classes) {
  if (stateName === 'loading') {
    return `<div class="c-page c-home loading ${classes}">` +
      '<span class="page-skeleton nav"></span><span class="page-skeleton hero"></span>' +
      '<span class="page-skeleton rail"></span><span class="page-skeleton rail short"></span></div>';
  }
  if (stateName === 'empty') return pageMessage('empty', 'Nothing to watch yet', 'Add a hub or a profile, then this wall fills itself.');
  if (stateName === 'error') return pageMessage('error', 'The hub is not answering', 'Retry from Settings or check the box on the LAN.');

  const rows = Array.isArray(sample.rows) ? sample.rows : HOME_ROWS;
  const focus = stateName === 'focus';
  const nav = renderNavTop({
    items: sample.nav || NAV_ITEMS,
    variant: sample.family === 'kids' ? 'kids' : 'pill',
    active: 'home', search: true,
    profile: { name: 'Alex', initials: 'A' }
  }, 'default', '');
  const hero = renderHeroGlass({
    items: sample.hero || HERO_ITEMS,
    variant: sample.family === 'kids' ? 'kids-stage' : 'glass-cards',
    chips: sample.chips || HERO_CHIPS, max: 2
  }, focus ? 'focus' : 'default', '');
  const rails = rows.slice(0, 2).map((row) => renderRail({
    ...row, seeAll: true, total: row.total || 24, showCount: false
  }, 'default', '')).join('');
  return `<div class="c-page c-home family-${esc(sample.family || 'glass')} ${classes}">` +
    `${nav}${hero}${rails}</div>`;
}

function renderTemplateDetail(sample, stateName, classes, variant) {
  const style = variant || sample.variant || 'backdrop';
  if (stateName === 'loading') {
    return `<div class="c-page c-detail loading ${classes}">` +
      '<span class="page-skeleton detail-hero"></span><span class="page-skeleton rail"></span></div>';
  }
  if (stateName === 'empty') return pageMessage('empty', 'Title not found', 'The hub answered without a record for this id.');
  if (stateName === 'error') return pageMessage('error', 'This title did not load', 'The record came back empty. Retry or open it from the shelf again.');

  const title = sample.title || 'Untitled';
  const actions = (Array.isArray(sample.actions) ? sample.actions : [])
    .map((action, index) => renderButton(action, 'default',
      classesFor({ level: 'atom', id: 'atom.button' }, stateName === 'focus' && index === 0 ? 'focus' : 'default'))).join('');
  const reactionGlyph = { seen: 'check', like: 'play', love: 'play', dislike: 'close' };
  const reactions = (Array.isArray(sample.reactions) ? sample.reactions : [])
    .map((tag, index) => `<span class="detail-reaction${index === 1 ? ' is-on' : ''}">` +
      `${iconSvg(reactionGlyph[tag] || 'play')}<span>${esc(tag)}</span></span>`).join('');
  const people = [
    ['Pedro Pascal', 'PP'], ['Bella Ramsey', 'BR'], ['Anna Torv', 'AT'], ['Murray Bartlett', 'MB']
  ].map(([name, mark]) => `<span class="detail-person"><span class="detail-face">${mark}</span><span>${esc(name)}</span></span>`).join('');
  const episodes = (Array.isArray(sample.episodes) ? sample.episodes : RAIL_ITEMS.slice(0, 4))
    .map((item) => renderTile({ ...item, variant: 'landscape', caption: 'below' }, 'default',
      classesFor({ level: 'molecule', id: 'molecule.tile' }, 'default'), 'landscape')).join('');
  const related = (Array.isArray(sample.related) ? sample.related : RAIL_ITEMS.slice(1, 5))
    .map((item) => renderTile({ ...item, variant: 'poster', caption: 'below' }, 'default',
      classesFor({ level: 'molecule', id: 'molecule.tile' }, 'default'), 'poster')).join('');

  return `<div class="c-page c-detail variant-${esc(style)} ${classes}">` +
    '<div class="detail-hero">' +
      `<div class="detail-art" style="${artVars(title)}"><i class="hero-art-shape s1"></i><i class="hero-art-shape s2"></i>` +
        `<span class="detail-monogram">${esc(initials(title))}</span>` +
        `<span class="detail-art-caption">${esc(sample.meta || '')}</span></div>` +
      '<div class="detail-words"><span class="detail-back">‹</span><span class="detail-kicker">Scenix</span>' +
        `<h3 class="detail-title">${esc(title)}</h3>` +
        `<p class="detail-meta">${esc(sample.meta || '')}</p>` +
        `<p class="detail-overview">${esc(sample.overview || '')}</p>` +
        `<div class="detail-actions">${actions}</div>` +
        `${sample.showReactions === false ? '' : `<div class="detail-reactions">${reactions}</div>`}` +
      '</div>' +
    '</div>' +
    '<div class="detail-content"><span class="detail-section">Cast</span>' +
      `<div class="detail-cast">${people}</div>` +
      '<span class="detail-section">Episodes</span>' +
      `<div class="detail-episodes">${episodes}</div>` +
      '<span class="detail-section">More like this</span>' +
      `<div class="detail-related">${related}</div>` +
    '</div></div>';
}

function renderTemplateSettings(sample, stateName, classes) {
  if (stateName === 'loading') {
    return `<div class="c-page c-settings loading ${classes}">` +
      '<span class="settings-top"><span class="settings-back">‹</span><span class="settings-title">Settings</span></span>' +
      Array.from({ length: 5 }).map(() => '<span class="page-skeleton row"></span>').join('') + '</div>';
  }
  if (stateName === 'empty') return pageMessage('empty', 'No settings for this profile', 'A kids profile hides the admin sections until a PIN unlocks them.');
  if (stateName === 'error') return pageMessage('error', 'Preferences did not load', 'The hub did not answer. Retry from the back control.');

  const sections = Array.isArray(sample.sections) ? sample.sections : SETTINGS_SECTIONS;
  let rowIndex = 0;
  const body = sections.map((section) => `<div class="settings-section">` +
    `<span class="settings-heading">${esc(section.title)}</span>` +
    (section.rows || []).map((row) => {
      const isFocus = stateName === 'focus' && rowIndex === 0;
      rowIndex += 1;
      return renderSettingsRow(row, isFocus ? 'focus' : 'default',
        isFocus ? 'state-focus is-focus' : 'state-default');
    }).join('') + '</div>').join('');

  const picker = sample.pickerOpen && sample.picker
    ? `<div class="settings-picker"><span class="settings-picker-title">${esc(sample.picker.title)}</span>` +
      (sample.picker.options || []).map((option, index) => renderSettingsRow({
        label: option.label, value: option.value, control: 'chevron', chevron: false
      }, index === 1 ? 'selected' : 'default', index === 1 ? 'state-selected is-selected' : 'state-default')).join('') +
      '</div>'
    : '';

  return `<div class="c-page c-settings ${classes}">` +
    `<div class="settings-top"><span class="settings-back">‹</span>` +
      `<span class="settings-title">${esc(sample.title || 'Settings')}</span>` +
      `<span class="settings-status">${esc(sample.status || '')}</span></div>` +
    `<div class="settings-body">${body}</div>${picker}</div>`;
}

function renderTemplatePlayer(sample, stateName, classes) {
  const live = sample.mode === 'live' || stateName === 'live';
  const buffering = stateName === 'buffering';
  const paused = stateName === 'paused' || (sample.playing === false && stateName === 'default');
  const focus = stateName === 'focus';
  const error = stateName === 'error';
  const playing = !paused && !buffering && !error;
  const position = live ? 1 : Math.max(0, Math.min(1, Number(sample.position) || 0));
  const buffered = Math.max(position, Math.min(1, Number(sample.buffered) || 0));
  const duration = Number(sample.duration) || 0;
  const options = (Array.isArray(sample.options) ? sample.options : PLAYER_OPTIONS)
    .map((icon) => `<span class="player-opt">${iconSvg(icon)}</span>`).join('');
  const center = error
    ? `<span class="player-error">This copy did not open. <span class="player-retry">Retry</span></span>`
    : buffering
      ? '<span class="player-buffer"><span class="c-spinner"></span> Buffering…</span>'
      : '';

  return `<div class="c-page c-player ${classes}"><div class="player-frame">` +
    '<div class="player-video"></div>' +
    `<span class="player-frame-title">${esc(sample.title || '')}</span>` +
    center +
    `<div class="player-top"><span class="player-back">‹</span>` +
      `<span class="player-title">${esc(sample.title || '')}</span>` +
      `<span class="player-meta">${esc(sample.subtitle || '')}</span>` +
      `${live ? '<span class="player-live">LIVE</span>' : ''}</div>` +
    '<div class="player-bottom"><div class="player-line">' +
      `<span>${live ? 'LIVE' : formatTime(duration * position)}</span>` +
      `<span class="player-seek${live ? ' is-live' : ''}">` +
        `<i class="buffer" style="width:${buffered * 100}%"></i>` +
        `<i class="played" style="width:${position * 100}%"></i>` +
        `${live ? '' : `<b class="knob" style="left:${position * 100}%"></b>`}</span>` +
      `<span>${formatTime(duration)}</span></div>` +
      `<div class="player-transport">` +
        `<span class="player-btn main${focus ? ' is-focus' : ''}">${iconSvg(playing ? 'pause' : 'play')}</span>` +
        `${sample.nextEpisode ? `<span class="player-btn">${iconSvg('forward')}</span>` : ''}` +
        `<span class="player-btn">${iconSvg('rewind')}</span>` +
        `<span class="player-btn">${iconSvg('forward')}</span>` +
        '<span class="player-spacer"></span>' +
        `<span class="player-options">${options}</span></div>` +
      `<span class="player-hint">${esc(sample.hint || '')}</span></div>` +
    `${sample.skipIntro ? '<span class="player-skip">Skip intro</span>' : ''}` +
    '</div></div>';
}

function componentSection(component) {
  const initialVariant = currentVariant(component);
  const initialState = component.states[0];
  const initialSample = sampleFor(component, initialVariant);
  const cases = buildCases(component);
  const budget = component.budget || {};
  const variantControls = VARIANT_COMPONENTS.has(component.id)
    ? `<div class="control-group"><span class="control-label">Variant</span>${component.props.variant.enum
      .map((variant) => `<button type="button" class="variant-tab${variant === initialVariant ? ' active' : ''}" ` +
        `data-variant-tab="${esc(variant)}">${esc(variant)}</button>`).join('')}</div>`
    : '';
  const stateControls = `<div class="control-group"><span class="control-label">State</span>${component.states
    .map((stateName) => `<button type="button" class="state-tab${stateName === initialState ? ' active' : ''}" ` +
      `data-state-tab="${esc(stateName)}">${esc(stateName)}</button>`).join('')}</div>`;

  const shortName = component.id.split('.').slice(1).join('.');
  return `<section class="component level-${esc(component.level)}" id="c-${cssId(component.id)}" data-id="${esc(component.id)}" ` +
    `data-level="${esc(component.level)}" data-case-state="${esc(initialState)}" data-variant="${esc(initialVariant)}">` +
    '<div class="component-head"><div class="component-name">' +
    `<span class="level-pill">${esc(component.level)}</span>` +
    `<h2>${esc(shortName)}</h2><code>${esc(component.id)} · v${component.version}</code></div>` +
    `<span class="budget">${esc(budget.maxViews)} views\n${esc(budget.maxLayers)} layers\n${esc(formatBytes(budget.maxImageBytes))}</span></div>` +
    `<p class="component-summary">${esc(component.summary || '')}</p>` +
    `<div class="case-controls">${variantControls}${stateControls}</div>` +
    `<div class="demo-meta"><span>Preview</span><span data-demo-label>${esc(initialVariant ? `${initialVariant} · ${initialState}` : initialState)}</span></div>` +
    `<div class="demo-panel" data-demo>${renderCase(component, initialSample, initialState, initialVariant)}</div>` +
    '<div class="state-grid">' +
    cases.map((entry) => {
      const sample = sampleFor(component, entry.variant);
      return `<figure class="state-cell" data-state="${esc(entry.state)}"${entry.variant ? ` data-variant="${esc(entry.variant)}"` : ''}>` +
        `<div class="state-cell-canvas">${renderCase(component, sample, entry.state, entry.variant)}</div>` +
        `<figcaption><b>${esc(entry.variant || entry.state)}</b>${entry.variant ? `<span>${esc(entry.state)}</span>` : ''}</figcaption></figure>`;
    }).join('') +
    '</div></section>';
}

function visibleComponents() {
  if (state.levelFilter === 'all') return state.components;
  return state.components.filter((component) => component.level === state.levelFilter);
}

function renderStage(preserveScroll = false) {
  const stage = $('#device-stage');
  if (!stage || !state.theme) return;
  const scroll = preserveScroll ? stage.scrollTop : 0;
  const theme = state.theme;
  const caps = (theme.requires && theme.requires.caps) || [];
  const targetCount = (theme.targets || []).length;
  const visible = visibleComponents();
  const filterLabel = state.levelFilter === 'all' ? 'all levels' : `${state.levelFilter}s`;

  stage.innerHTML = `<div class="stage-inner">
    <header class="stage-head">
      <span class="stage-kicker">Scenix / ${esc(theme.name)} pack</span>
      <h1>Component gallery</h1>
      <p>Every declared state, painted with resolved tokens. ${esc(visible.length)} ${esc(filterLabel)} · ${esc(targetCount)} targets · ${esc(caps.length)} capabilities. This is a web approximation; Android, iOS and web render the same contract through their registry.</p>
    </header>
    <div id="component-sections">${visible.map(componentSection).join('')}</div>
  </div>`;

  if (preserveScroll) stage.scrollTop = scroll;
  bindStage();
}

function bindStage() {
  const stage = $('#device-stage');
  if (!stage) return;

  $$('.component', stage).forEach((section) => {
    const component = state.components.find((entry) => entry.id === section.dataset.id);
    if (!component) return;

    section.addEventListener('click', (event) => {
      selectComponent(component.id, false);
      const stateTab = event.target.closest('[data-state-tab]');
      if (stateTab) {
        section.dataset.caseState = stateTab.dataset.stateTab;
        paintCase(section, component);
        return;
      }
      const variantTab = event.target.closest('[data-variant-tab]');
      if (variantTab) {
        section.dataset.variant = variantTab.dataset.variantTab;
        paintCase(section, component);
        return;
      }
      const cell = event.target.closest('.state-cell');
      if (cell) {
        section.dataset.caseState = cell.dataset.state;
        if (cell.dataset.variant) section.dataset.variant = cell.dataset.variant;
        paintCase(section, component);
        scrollStageTo(section.querySelector('.demo-panel'), true);
      }
    });
  });
}

function scrollStageTo(target, smooth = true) {
  const stage = $('#device-stage');
  if (!stage || !target) return;
  const stageRect = stage.getBoundingClientRect();
  const targetRect = target.getBoundingClientRect();
  const scale = stage.clientWidth ? stageRect.width / stage.clientWidth : 1;
  const top = stage.scrollTop + (targetRect.top - stageRect.top) / (scale || 1);
  stage.scrollTo({ top: Math.max(0, top - 14), behavior: smooth ? 'smooth' : 'auto' });
}

function paintCase(section, component) {
  const stateName = section.dataset.caseState || component.states[0];
  const variant = section.dataset.variant || '';
  const sample = sampleFor(component, variant);
  const demo = section.querySelector('[data-demo]');
  if (demo) demo.innerHTML = renderCase(component, sample, stateName, variant);
  const label = section.querySelector('[data-demo-label]');
  if (label) label.textContent = variant ? `${variant} · ${stateName}` : stateName;
  $$('[data-state-tab]', section).forEach((button) => button.classList.toggle('active', button.dataset.stateTab === stateName));
  $$('[data-variant-tab]', section).forEach((button) => button.classList.toggle('active', button.dataset.variantTab === variant));
}

/* ---------------- sidebar & selection ---------------- */

function renderSidebar() {
  const list = $('#component-list');
  if (!list) return;
  const visible = visibleComponents();
  list.innerHTML = LEVELS.map((level) => {
    const items = visible.filter((component) => component.level === level);
    if (!items.length) return '';
    return `<div class="level-group"><div class="level-name">${esc(level)}</div>` +
      items.map((component) => `<button type="button" class="comp-link" data-component="${esc(component.id)}">` +
        '<span class="dot"></span>' +
        `<span class="name">${esc(component.id.split('.').slice(1).join('.'))}</span>` +
        `<span class="v">v${component.version}</span></button>`).join('') +
      '</div>';
  }).join('') || '<p class="sidebar-empty">No components at this level.</p>';

  $$('.comp-link', list).forEach((button) => {
    button.addEventListener('click', () => {
      selectComponent(button.dataset.component, true);
    });
  });

  const count = $('#component-count');
  if (count) count.textContent = String(visible.length);
  const levelSelect = $('#level-select');
  if (levelSelect) levelSelect.value = state.levelFilter;
}

function selectComponent(id, scroll) {
  const component = state.components.find((entry) => entry.id === id);
  if (!component) return;
  state.selectedId = id;
  $$('.comp-link').forEach((button) => button.classList.toggle('active', button.dataset.component === id));
  renderTokenPanel();
  if (scroll) {
    const target = document.getElementById(`c-${cssId(id)}`);
    scrollStageTo(target, true);
  }
}

function setLevelFilter(level, updateUrl = true) {
  state.levelFilter = LEVELS.includes(level) ? level : 'all';
  const visible = visibleComponents();
  if (visible.length && !visible.some((component) => component.id === state.selectedId)) {
    state.selectedId = visible[0].id;
  }
  renderSidebar();
  renderStage(false);
  selectComponent(state.selectedId, false);
  if (updateUrl) writeUrl();
}

/* ---------------- token panel ---------------- */

function tokenValue(value, suffix = '') {
  if (value == null) return '—';
  if (value && typeof value === 'object') {
    return `${value.size != null ? value.size : '—'}${suffix}${value.weight ? ` / ${value.weight}` : ''}`;
  }
  return `${value}${suffix}`;
}

function renderTokenPanel() {
  const host = $('#token-content');
  if (!host || !state.theme) return;
  const theme = state.theme;
  const component = state.components.find((entry) => entry.id === state.selectedId)
    || visibleComponents()[0] || state.components[0];
  const tokens = theme.tokens || {};
  const colors = tokens.color || {};
  const spaces = tokens.space || {};
  const radii = tokens.radius || {};
  const type = tokens.type || {};
  const focus = tokens.focus || {};
  const pack = tokens.pack || {};
  const caps = (theme.requires && theme.requires.caps) || [];
  const fallbacks = theme.fallbacks || {};

  const colorRows = Object.entries(colors).map(([name, value]) =>
    `<div class="swatch"><span class="swatch-dot"><i style="background:${esc(value)}"></i></span>` +
    `<span class="swatch-name">${esc(name)}</span><span class="swatch-value">${esc(value)}</span></div>`).join('');

  const spacingRows = Object.entries(spaces).map(([name, value]) =>
    `<div class="metric"><span>${esc(name)}</span><b>${esc(value)} px</b></div>`).join('');

  const radiusRows = Object.entries(radii).map(([name, value]) => {
    const numeric = Number(value);
    const preview = Number.isFinite(numeric) ? Math.min(18, Math.max(0, numeric)) : 0;
    return `<div class="radius-tile"><span class="radius-box" style="border-radius:${preview}px"></span>` +
      `<span>${esc(name)}</span><b>${esc(value)}</b></div>`;
  }).join('');

  const typeRows = Object.entries(type).map(([name, value]) =>
    `<div class="type-row"><span>${esc(name)}</span><b>${esc(tokenValue(value, ' px'))}</b></div>`).join('');

  const capFacts = caps.length
    ? caps.map((cap) => `<span class="fact">${esc(cap)}</span>`).join('')
    : '<span class="fact muted">no extra capabilities</span>';
  const fallbackFacts = Object.entries(fallbacks).map(([from, to]) =>
    `<span class="fact">${esc(from)} → ${esc(to)}</span>`).join('');
  const targetFacts = (theme.targets || []).map((target) => `<span class="fact muted">${esc(target)}</span>`).join('');

  host.innerHTML = `
    <div class="token-block">
      <h3>Pack <span>${esc(theme.id)}</span></h3>
      <div class="theme-facts">
        <span class="fact">${esc(theme.name)} · ${esc(theme.version)}</span>
        ${theme.basedOn ? `<span class="fact">basedOn ${esc(theme.basedOn)}</span>` : ''}
        ${targetFacts}
      </div>
    </div>
    <div class="token-block">
      <h3>Capabilities <span>${caps.length}</span></h3>
      <div class="theme-facts">${capFacts}${fallbackFacts}</div>
    </div>
    <div class="token-block">
      <h3>Colors <span>${Object.keys(colors).length}</span></h3>
      <div class="swatches">${colorRows}</div>
    </div>
    <div class="token-block">
      <h3>Spacing <span>${Object.keys(spaces).length}</span></h3>
      <div class="metric-grid">${spacingRows}</div>
    </div>
    <div class="token-block">
      <h3>Radii <span>${Object.keys(radii).length}</span></h3>
      <div class="radius-grid">${radiusRows}</div>
    </div>
    <div class="token-block">
      <h3>Type <span>${Object.keys(type).length}</span></h3>
      <div class="type-list">${typeRows}</div>
    </div>
    <div class="token-block">
      <h3>Focus &amp; motion <span>resolved</span></h3>
      <div class="theme-facts">
        <span class="fact">focus ${esc(focus.style || 'ring')}</span>
        <span class="fact">ring ${esc(focus.ringWidth != null ? focus.ringWidth : 2)} px</span>
        <span class="fact">scale ${esc(focus.scale != null ? focus.scale : 1.04)}</span>
        <span class="fact">${esc(pack.motion || 'fade')} motion</span>
        <span class="fact">rank ${esc(pack.rank || 'solid')}</span>
        <span class="fact">nav ${esc(pack.nav || 'classic')}</span>
      </div>
    </div>
    <div class="token-block">
      <h3>Component contract <span>${esc(component.id)}</span></h3>
      <div class="contract-head"><code>${esc(component.id)} · v${component.version}</code>` +
        '<button type="button" class="copy-button" id="copy-contract">Copy JSON</button></div>' +
      `<pre id="contract-json">${esc(JSON.stringify(component, null, 2))}</pre>` +
    '</div>';

  const copy = $('#copy-contract');
  if (copy) {
    copy.addEventListener('click', async () => {
      const payload = JSON.stringify(component, null, 2);
      try {
        await navigator.clipboard.writeText(payload);
        copy.textContent = 'Copied';
      } catch (error) {
        copy.textContent = 'Select manually';
      }
      window.clearTimeout(state.copyTimer);
      state.copyTimer = window.setTimeout(() => { copy.textContent = 'Copy JSON'; }, 1600);
    });
  }

  const name = $('#token-theme-name');
  if (name) name.textContent = theme.name;
  const version = $('#token-theme-version');
  if (version) version.textContent = `v${theme.version}`;
}

/* ---------------- chrome ---------------- */

function updateChrome() {
  const theme = state.theme;
  const device = state.device;
  if (!theme || !device) return;

  const themeSelect = $('#theme-select');
  if (themeSelect) themeSelect.value = theme.id;
  const deviceSelect = $('#device-select');
  if (deviceSelect) deviceSelect.value = device.id;

  const caption = $('#theme-caption');
  if (caption) caption.textContent = theme.name;
  const deviceCaption = $('#device-caption');
  if (deviceCaption) deviceCaption.textContent = `${device.name} · ${device.width} × ${device.height}`;
  const title = $('#stage-title');
  if (title) title.textContent = `${theme.name} component gallery`;

  const brief = $('#theme-brief');
  if (brief) {
    const caps = (theme.requires && theme.requires.caps) || [];
    brief.innerHTML = `<span class="pill">${esc(theme.id)}</span>` +
      `<span class="pill">basedOn ${esc(theme.basedOn || '—')}</span>` +
      caps.map((cap) => `<span class="pill">${esc(cap)}</span>`).join('');
  }

  applyTheme();
}

function setTheme(id, updateUrl = true) {
  const theme = state.themes.find((entry) => entry.id === id)
    || state.themes.find((entry) => entry.name.toLowerCase() === String(id).toLowerCase());
  if (!theme) return;
  const stage = $('#device-stage');
  const scroll = stage ? stage.scrollTop : 0;
  state.theme = theme;
  applyTheme();
  renderStage(false);
  if (stage) stage.scrollTop = scroll;
  renderTokenPanel();
  updateChrome();
  if (updateUrl) writeUrl();
}

function setDevice(id, updateUrl = true) {
  const device = DEVICES.find((entry) => entry.id === id) || DEVICES[0];
  state.device = device;
  const frame = $('#device-frame');
  if (frame) {
    frame.style.width = `${device.width}px`;
    frame.style.height = `${device.height}px`;
  }
  fitDevice();
  updateChrome();
  if (updateUrl) writeUrl();
}

function fitDevice() {
  const viewport = $('#device-viewport');
  const holder = $('#device-holder');
  const frame = $('#device-frame');
  const device = state.device;
  if (!viewport || !holder || !frame || !device) return;

  const width = viewport.clientWidth - 30;
  const height = viewport.clientHeight - 30;
  const scale = Math.max(0.12, Math.min(1, width / device.width, height / device.height));
  holder.style.width = `${Math.round(device.width * scale)}px`;
  holder.style.height = `${Math.round(device.height * scale)}px`;
  frame.style.width = `${device.width}px`;
  frame.style.height = `${device.height}px`;
  frame.style.transform = `scale(${scale})`;
  const caption = $('#scale-caption');
  if (caption) caption.textContent = `${Math.round(scale * 100)}% of ${device.width}px`;
}

function writeUrl() {
  if (!state.theme || !state.device) return;
  try {
    const params = new URLSearchParams();
    params.set('theme', state.theme.id);
    params.set('device', state.device.id);
    if (state.levelFilter !== 'all') params.set('level', state.levelFilter);
    if (state.selectedId) params.set('component', state.selectedId);
    history.replaceState(null, '', `${location.pathname}?${params.toString()}`);
  } catch (error) {
    /* file:// or a sandboxed preview: the gallery still works without a shareable URL. */
  }
}

function bootChrome() {
  const themeSelect = $('#theme-select');
  if (themeSelect) {
    themeSelect.innerHTML = state.themes
      .map((theme) => `<option value="${esc(theme.id)}">${esc(theme.name)}</option>`).join('');
    themeSelect.addEventListener('change', (event) => setTheme(event.target.value));
  }

  const deviceSelect = $('#device-select');
  if (deviceSelect) {
    deviceSelect.innerHTML = DEVICES
      .map((device) => `<option value="${device.id}">${device.name} · ${device.width} × ${device.height}</option>`).join('');
    deviceSelect.addEventListener('change', (event) => setDevice(event.target.value));
  }

  const levelSelect = $('#level-select');
  if (levelSelect) {
    levelSelect.innerHTML = '<option value="all">All levels</option>' +
      LEVELS.map((level) => `<option value="${level}">${level[0].toUpperCase()}${level.slice(1)}s</option>`).join('');
    levelSelect.value = state.levelFilter;
    levelSelect.addEventListener('change', (event) => setLevelFilter(event.target.value));
  }

  const reset = $('#reset-view');
  if (reset) {
    reset.addEventListener('click', () => {
      setTheme(state.themes[0].id, false);
      setDevice('tv', false);
      state.levelFilter = 'all';
      state.selectedId = state.components[0].id;
      renderSidebar();
      renderStage(false);
      selectComponent(state.selectedId, false);
      const stage = $('#device-stage');
      if (stage) stage.scrollTop = 0;
      writeUrl();
    });
  }

  if ('ResizeObserver' in window) {
    new ResizeObserver(() => fitDevice()).observe($('#device-viewport'));
  }
  window.addEventListener('resize', fitDevice);
}

function showError(error) {
  const stage = $('#device-stage');
  const message = error && error.message ? error.message : String(error);
  if (!stage) return;
  stage.innerHTML = `<div class="load-error"><strong>Could not load the theme kit.</strong>` +
    `<p>${esc(message)}</p>` +
    `<p>Serve <code>theme-kit/</code> with <code>python3 -m http.server 8000</code> and open ` +
    `<code>http://localhost:8000/gallery/</code>. Opening <code>index.html</code> as a file is blocked by browser fetch rules.</p></div>`;
}

async function boot() {
  try {
    if (location.protocol === 'file:') {
      throw new Error('Browsers refuse fetch() on file:// pages.');
    }
    await loadData();
    if (!state.components.length || !state.themes.length) {
      throw new Error('No components or themes found. Check components/index.json and themes/index.json.');
    }

    const params = new URLSearchParams(location.search);
    const requestedTheme = params.get('theme');
    const requestedDevice = params.get('device');
    const requestedLevel = params.get('level');
    const requestedComponent = params.get('component');

    state.theme = state.themes.find((theme) => theme.id === requestedTheme
      || theme.name.toLowerCase() === String(requestedTheme || '').toLowerCase()) || state.themes[0];
    state.device = DEVICES.find((device) => device.id === requestedDevice) || DEVICES[0];
    state.levelFilter = LEVELS.includes(requestedLevel) ? requestedLevel : 'all';
    const requested = state.components.find((component) => component.id === requestedComponent);
    const visible = visibleComponents();
    state.selectedId = requested && visible.includes(requested)
      ? requested.id
      : (visible[0] || state.components[0]).id;

    renderSidebar();
    bootChrome();
    applyTheme();
    renderStage(false);
    setDevice(state.device.id, false);
    renderTokenPanel();
    updateChrome();
    selectComponent(state.selectedId, false);

    if (requestedComponent) {
      const target = document.getElementById(`c-${cssId(state.selectedId)}`);
      scrollStageTo(target, false);
    }
  } catch (error) {
    showError(error);
  }
}

boot();
