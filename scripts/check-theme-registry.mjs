#!/usr/bin/env node
/*
 * The curated registry is the contract between the TV shell and the web shell.
 *
 * Theme manifests deliberately remain next to their consumers so existing asset
 * loaders keep working. This check makes those manifests projections of one
 * registry, verifies the digest of every curated asset, and makes a web theme
 * opt in only after its exact bytes and portable token groups match the TV
 * source. It is intentionally a closed, repository-only catalogue: it does
 * not fetch, install, or otherwise trust arbitrary theme packs.
 *
 *   node scripts/check-theme-registry.mjs
 */
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REGISTRY_FILE = path.join(ROOT, 'design-system', 'themes.json');
const ANDROID_MANIFEST = path.join(ROOT, 'app', 'src', 'main', 'assets', 'themes.json');
const WEB_MANIFEST = path.join(ROOT, 'hub', 'web', 'assets', 'themes.json');
const ANDROID_ASSETS = path.dirname(ANDROID_MANIFEST);
const WEB_ASSETS = path.dirname(WEB_MANIFEST);
const TOKEN_INDEX = path.join(ROOT, 'design-system', 'tokens', 'index.json');
const TOKEN_DIR = path.join(ROOT, 'design-system', 'tokens', 'themes');
const WEB_THEME_MODULE = path.join(ROOT, 'hub', 'web', 'js', 'theme.js');
const WEB_HOME_MODULE = path.join(ROOT, 'hub', 'web', 'js', 'screens', 'home.js');
const WEB_APP_MODULE = path.join(ROOT, 'hub', 'web', 'js', 'app.js');
const WEB_PACK_STYLESHEET = path.join(ROOT, 'hub', 'web', 'css', 'packs.css');

const PLATFORM_TV = 'android-tv';
const PLATFORM_WEB = 'web';
const KNOWN_TARGETS = new Set([PLATFORM_TV, PLATFORM_WEB]);
/*
 * These are the complete curated packs the product releases together.  A
 * theme may reach the browser only when its TV bytes are present, its
 * structure is declared by the renderer, and the CSS owns the pack selector.
 * Keeping this closed prevents a manifest-only toggle from advertising a
 * painted-but-not-implemented theme to a household.
 */
const REQUIRED_WEB_PACKS = new Map([
  ['scenix-dark', { hero: 'full', rows: 'poster', card: 'overlay', focus: 'ring' }],
  ['scenix-midnight', { hero: 'centered', rows: 'landscape', card: 'glass', focus: 'glow' }],
  ['scenix-ember', { hero: 'split', rows: 'poster', card: 'frame', focus: 'lift' }],
  ['scenix-apple', { homeLayout: 'apple', hero: 'full', rows: 'landscape', card: 'below', focus: 'glow', nav: 'pill' }],
  ['scenix-netflix', { homeLayout: 'netflix', hero: 'full', rows: 'landscape', card: 'overlay', focus: 'ring', nav: 'classic' }],
  ['scenix-ps5', { homeLayout: 'ps5', hero: 'rail', rows: 'landscape', card: 'glass', focus: 'glow', nav: 'ps5' }],
  ['scenix-kids', { homeLayout: 'kids', hero: 'full', rows: 'poster', card: 'below', focus: 'glow', nav: 'kids' }],
  ['scenix-glass', { homeLayout: 'glass', hero: 'full', rows: 'poster', card: 'below', focus: 'glow', nav: 'clean' }],
]);
const WEB_PACK_SELECTORS = new Map([
  ['scenix-dark', 'data-hero="full"'],
  ['scenix-midnight', 'data-hero="centered"'],
  ['scenix-ember', 'data-card="frame"'],
  ['scenix-apple', 'data-theme="scenix-apple"'],
  ['scenix-netflix', 'data-hero="full"'],
  ['scenix-ps5', 'data-home-layout="ps5"'],
  ['scenix-kids', 'data-home-layout="kids"'],
  ['scenix-glass', 'data-home-layout="glass"'],
]);
const TOKEN_GROUPS = [
  ['colors', 'color'],
  ['spacing', 'space'],
  ['radii', 'radius'],
  ['type', 'type'],
  ['focus', 'focus'],
  ['anim', 'anim'],
  ['player', 'player'],
  ['pack', 'pack'],
];

const failures = [];

function fail(message) {
  failures.push(message);
  console.error(`FAIL  ${message}`);
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function stableJson(value) {
  if (value === undefined) return 'undefined';
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`;
  if (isObject(value)) {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function sameJson(a, b) {
  return stableJson(a) === stableJson(b);
}

function readJson(file, label) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    fail(`${label} is unreadable JSON (${error.message})`);
    return null;
  }
}

function digest(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function ids(entries) {
  return entries.map((entry) => isObject(entry) && typeof entry.id === 'string' ? entry.id : '<invalid>').join(', ');
}

function validateEntry(entry, index, seenIds) {
  const label = `registry theme #${index + 1}`;
  if (!isObject(entry)) {
    fail(`${label} must be an object`);
    return false;
  }

  let valid = true;
  const requiredStrings = ['id', 'name', 'version', 'asset', 'pack', 'layout', 'sha256'];
  for (const key of requiredStrings) {
    if (typeof entry[key] !== 'string' || !entry[key].trim()) {
      fail(`${label}.${key} must be a non-empty string`);
      valid = false;
    }
  }

  if (typeof entry.id === 'string') {
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(entry.id)) {
      fail(`${label}.id must be a stable lowercase slug`);
      valid = false;
    }
    if (seenIds.has(entry.id)) {
      fail(`${label}.id duplicates ${entry.id}`);
      valid = false;
    }
    seenIds.add(entry.id);
  }
  if (typeof entry.version === 'string' && !/^\d+\.\d+\.\d+$/.test(entry.version)) {
    fail(`${label}.version must use semantic versioning`);
    valid = false;
  }
  if (typeof entry.asset === 'string'
    && (path.basename(entry.asset) !== entry.asset || !entry.asset.endsWith('.json'))) {
    fail(`${label}.asset must be a JSON basename, never a path or URL`);
    valid = false;
  }
  if (typeof entry.sha256 === 'string' && !/^[a-f0-9]{64}$/.test(entry.sha256)) {
    fail(`${label}.sha256 must be a lowercase SHA-256 digest`);
    valid = false;
  }
  if (!Array.isArray(entry.targets) || entry.targets.length === 0) {
    fail(`${label}.targets must be a non-empty target list`);
    valid = false;
  } else {
    const targets = new Set(entry.targets);
    if (targets.size !== entry.targets.length || entry.targets.some((target) => !KNOWN_TARGETS.has(target))) {
      fail(`${label}.targets contains an unknown or duplicate target`);
      valid = false;
    }
  }
  return valid;
}

function checkManifest(manifest, label, target, expected, registryVersion) {
  if (!isObject(manifest)) {
    fail(`${label} manifest must be an object`);
    return;
  }
  if (manifest.version !== registryVersion) fail(`${label} manifest version must equal the registry version`);
  if (manifest.registry !== 'design-system/themes.json') {
    fail(`${label} manifest must name design-system/themes.json as its registry`);
  }
  if (!Array.isArray(manifest.themes)) {
    fail(`${label} manifest must contain a themes array`);
    return;
  }
  if (!sameJson(manifest.themes, expected)) {
    fail(`${label} manifest is not the exact ${target} projection of the curated registry`
      + ` (expected: ${ids(expected)}; found: ${ids(manifest.themes)})`);
  }
}

function checkAsset(entry, directory, target) {
  const file = path.join(directory, entry.asset);
  if (!existsSync(file)) {
    fail(`${target} is missing ${entry.asset} for ${entry.id}`);
    return null;
  }
  let bytes;
  let json;
  try {
    bytes = readFileSync(file);
    json = JSON.parse(bytes.toString('utf8'));
  } catch (error) {
    fail(`${target} asset ${entry.asset} for ${entry.id} is unreadable JSON (${error.message})`);
    return null;
  }
  const actualDigest = digest(bytes);
  if (actualDigest !== entry.sha256) {
    fail(`${target} asset ${entry.asset} for ${entry.id} digest drifted (${actualDigest})`);
  }
  if (!isObject(json) || json.id !== entry.id) {
    fail(`${target} asset ${entry.asset} must declare id ${entry.id}`);
  }
  return { bytes, json };
}

function checkTokens(entries, androidAssets) {
  const index = readJson(TOKEN_INDEX, 'design token index');
  if (!isObject(index) || !Array.isArray(index.themes)) {
    if (index !== null) fail('design token index must contain a themes array');
    return;
  }
  const expectedIndex = entries.map((entry) => ({
    id: entry.id,
    name: entry.name,
    file: `${entry.id}.json`,
  }));
  if (!sameJson(index.themes, expectedIndex)) {
    fail(`design token index is not the curated registry projection (expected: ${ids(expectedIndex)}; found: ${ids(index.themes)})`);
  }

  for (const entry of entries) {
    const tokenFile = path.join(TOKEN_DIR, `${entry.id}.json`);
    const tokens = readJson(tokenFile, `design tokens for ${entry.id}`);
    const source = androidAssets.get(entry.id)?.json;
    if (!isObject(tokens) || !isObject(source)) continue;
    for (const key of ['id', 'name', 'asset', 'pack']) {
      if (tokens[key] !== entry[key]) fail(`design tokens for ${entry.id} drifted in ${key}`);
    }
    if (!isObject(tokens.tokens)) {
      fail(`design tokens for ${entry.id} must contain token groups`);
      continue;
    }
    for (const [assetGroup, tokenGroup] of TOKEN_GROUPS) {
      if (!sameJson(source[assetGroup] || {}, tokens.tokens[tokenGroup] || {})) {
        fail(`design tokens for ${entry.id} drifted in ${tokenGroup}`);
      }
    }
  }
}

function readSource(file, label) {
  try {
    return readFileSync(file, 'utf8');
  } catch (error) {
    fail(`${label} is unreadable (${error.message})`);
    return '';
  }
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * `CURATED_WEB_PACKS` is intentionally a literal object: it doubles as the
 * runtime handshake and makes this shallow, no-browser static proof possible.
 * This check is deliberately strict about every structural field instead of
 * merely grepping for the theme id somewhere in the source.
 */
function checkWebRenderers(webThemes, androidAssets) {
  const requiredIds = [...REQUIRED_WEB_PACKS.keys()];
  const advertisedIds = webThemes.map((entry) => entry.id);
  if (!sameJson(advertisedIds, requiredIds)) {
    fail(`web must advertise exactly ${requiredIds.length} curated themes (expected: ${requiredIds.join(', ')}; found: ${advertisedIds.join(', ')})`);
  }

  const moduleSource = readSource(WEB_THEME_MODULE, 'web theme registry');
  const cssSource = readSource(WEB_PACK_STYLESHEET, 'web pack stylesheet');
  if (!moduleSource || !cssSource) return;
  if (!moduleSource.includes("root.setAttribute('data-web-pack'")) {
    fail('web theme registry must mark a structurally verified curated pack');
  }

  for (const [id, signature] of REQUIRED_WEB_PACKS) {
    const entry = webThemes.find((theme) => theme.id === id);
    if (!entry) continue;
    if (!sameJson(entry.targets, [PLATFORM_TV, PLATFORM_WEB])) {
      fail(`${id} must explicitly target both ${PLATFORM_TV} and ${PLATFORM_WEB}`);
    }

    const sourceAsset = androidAssets.get(id)?.json;
    if (sourceAsset && isObject(sourceAsset.pack)) {
      for (const [key, value] of Object.entries(signature)) {
        if (sourceAsset.pack[key] !== value) {
          fail(`${id} asset pack.${key} must be ${JSON.stringify(value)} for its web renderer`);
        }
      }
    }

    const block = new RegExp(`["']${escapeRegExp(id)}["']\\s*:\\s*\\{([\\s\\S]*?)\\},`, 'm').exec(moduleSource);
    if (!block) {
      fail(`web theme registry is missing the ${id} curated pack signature`);
      continue;
    }
    for (const [key, value] of Object.entries(signature)) {
      const field = new RegExp(`\\b${escapeRegExp(key)}\\s*:\\s*["']${escapeRegExp(value)}["']`);
      if (!field.test(block[1])) fail(`web theme registry ${id} is missing ${key}: ${JSON.stringify(value)}`);
    }

    const selector = WEB_PACK_SELECTORS.get(id);
    if (!selector || !cssSource.includes(selector)) {
      fail(`web pack stylesheet is missing the ${selector || 'required'} renderer selector for ${id}`);
    }
  }
}

/*
 * Token parity alone is not visual parity.  The structural home values are authored in the shared
 * pack and need a real consumer on the browser side.  This intentionally proves the small stable
 * vocabulary rather than theme ids: a future registered visual reskin can inherit a verified Home
 * surface without adding another branch to the page.
 */
function checkHomeSurfaceContract(androidAssets) {
  const homeSource = readSource(WEB_HOME_MODULE, 'web Home surface');
  const appSource = readSource(WEB_APP_MODULE, 'web application shell');
  const cssSource = readSource(WEB_PACK_STYLESHEET, 'web pack stylesheet');
  if (!homeSource || !appSource || !cssSource) return;
  if (!homeSource.includes('function homeSurfaceContract')) {
    fail('web Home must normalize the shared structural home contract');
  }
  for (const key of ['homeHero', 'homeRails', 'homeNavigation']) {
    if (!homeSource.includes(`raw('${key}'`)) {
      fail(`web Home must consume pack.${key}`);
    }
  }
  for (const hero of ['shared-slider', 'cinema-billboard', 'focused-hub', 'glass-cards', 'kids-stage']) {
    if (!homeSource.includes(`'${hero}'`)) {
      fail(`web Home is missing the ${hero} structural composition`);
    }
  }
  for (const hero of ['shared-slider', 'cinema-billboard', 'focused-hub']) {
    if (!cssSource.includes(`data-home-hero="${hero}"`)) {
      fail(`web pack stylesheet is missing the ${hero} semantic home selector`);
    }
  }

  // `homeNavigation` cannot be an inert data attribute. Utility and none own actual focusable
  // Home chrome, remove the duplicate host bar only on Home, and refresh optional doors when the
  // parallel capability answer arrives. These signatures keep a token-only repaint from quietly
  // regressing browser/TV structural parity.
  for (const signature of ['function makeOwnedNavigation', 'home-owned-nav', "window.addEventListener('scenix:caps'"]) {
    if (!homeSource.includes(signature)) fail(`web Home must implement owned navigation (${signature})`);
  }
  for (const signature of ['const homeOwnsNavigation', "window.dispatchEvent(new Event('scenix:caps'))"]) {
    if (!appSource.includes(signature)) fail(`web shell must apply Home navigation ownership (${signature})`);
  }
  for (const selector of ['data-home-navigation="none"', 'data-home-navigation="utility"', '.home-owned-nav']) {
    if (!cssSource.includes(selector)) fail(`web pack stylesheet is missing owned Home navigation selector ${selector}`);
  }

  for (const id of ['scenix-apple', 'scenix-netflix', 'scenix-ps5', 'scenix-glass', 'scenix-kids']) {
    const hero = androidAssets.get(id)?.json?.pack?.homeHero;
    if (typeof hero !== 'string' || !hero) {
      fail(`${id} must declare a structural pack.homeHero for the shared Home renderer`);
    }
  }
}

const registry = readJson(REGISTRY_FILE, 'curated theme registry');
const androidManifest = readJson(ANDROID_MANIFEST, 'Android theme manifest');
const webManifest = readJson(WEB_MANIFEST, 'web theme manifest');

if (isObject(registry) && Array.isArray(registry.themes)) {
  if (registry.version !== 1) fail('curated theme registry version must be 1');
  const seenIds = new Set();
  const themes = registry.themes.filter((entry, index) => validateEntry(entry, index, seenIds));
  if (themes.length !== registry.themes.length) {
    fail('curated theme registry has invalid entries');
  }

  const androidThemes = themes.filter((entry) => entry.targets.includes(PLATFORM_TV));
  const webThemes = themes.filter((entry) => entry.targets.includes(PLATFORM_WEB));
  checkManifest(androidManifest, 'Android', PLATFORM_TV, androidThemes, registry.version);
  checkManifest(webManifest, 'web', PLATFORM_WEB, webThemes, registry.version);

  const androidAssets = new Map();
  for (const entry of androidThemes) {
    const asset = checkAsset(entry, ANDROID_ASSETS, 'Android');
    if (asset) androidAssets.set(entry.id, asset);
  }
  for (const entry of webThemes) {
    const webAsset = checkAsset(entry, WEB_ASSETS, 'web');
    const androidAsset = androidAssets.get(entry.id);
    if (!webAsset || !androidAsset) continue;
    if (!webAsset.bytes.equals(androidAsset.bytes)) {
      fail(`shared asset ${entry.asset} for ${entry.id} is not byte-identical across TV and web`);
    }
    for (const [assetGroup, tokenGroup] of TOKEN_GROUPS) {
      if (!sameJson(androidAsset.json[assetGroup] || {}, webAsset.json[assetGroup] || {})) {
        fail(`shared tokens for ${entry.id} drifted in ${tokenGroup}`);
      }
    }
  }

  checkWebRenderers(webThemes, androidAssets);
  checkHomeSurfaceContract(androidAssets);

  checkTokens(androidThemes, androidAssets);

  if (failures.length === 0) {
    console.log(`theme registry parity ok: ${themes.length} curated themes; ${webThemes.length} shared across TV and web`);
    console.log(`byte parity ok: ${webThemes.length} shared assets match their declared SHA-256 digests`);
    console.log(`token parity ok: ${androidThemes.length} curated token sets match the design system`);
    console.log(`web renderer parity ok: ${REQUIRED_WEB_PACKS.size} exact assets have verified pack signatures and selectors`);
  }
} else if (registry !== null) {
  fail('curated theme registry must contain a themes array');
}

if (failures.length) {
  console.error(`\n${failures.length} theme registry violation${failures.length === 1 ? '' : 's'}.`);
  process.exit(1);
}
