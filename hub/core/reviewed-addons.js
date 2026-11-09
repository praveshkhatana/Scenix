'use strict';
/*
 * core/reviewed-addons.js — the small, reviewed source catalogue.
 *
 * This is intentionally not a marketplace client. It reads one repository-owned JSON file and rejects
 * every shape that is not an explicit, versioned, HTTPS-only source declaration. A later API route may
 * list the redacted projection and install only by this registry id; it never needs to accept a caller's
 * URL as a substitute for a reviewed entry.
 */

const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const ROOT = path.join(__dirname, '..');
const FILE = process.env.REVIEWED_ADDONS_FILE || path.join(ROOT, 'registry', 'reviewed-addons.json');

const ALLOWED_RESOURCE_NAMES = new Set(['catalog', 'meta', 'stream', 'subtitles']);
// Some official catalogue manifests also advertise `addon_catalog`, a protocol resource for
// discovering third-party source descriptors. The Hub deliberately does not proxy an upstream
// community marketplace into its curated lane: discovery and endorsement are different things.
// Accept that inert, known declaration when validating a reviewed metadata provider, but never
// project it as a Hub capability or use it as an install path.
const TOLERATED_REMOTE_EXTENSION_RESOURCES = new Set(['addon_catalog']);
const ALLOWED_MEDIA_TYPES = new Set(['movie', 'series', 'channel', 'other']);
const ENTRY_KEYS = new Set([
  'id', 'name', 'version', 'manifest', 'manifestId', 'resources',
  // Directory copy is reviewed alongside the manifest contract. It is deliberately small: the
  // browser needs enough information to filter and explain a source, never an upstream URL,
  // logo URL, executable setting, or arbitrary remote payload.
  'description', 'categories', 'tags', 'configurationRequired',
]);
const DIRECTORY_LABEL_PATTERN = /^[a-z][a-z0-9-]*$/;
const DIRECTORY_LABEL_MAX = 32;
const DIRECTORY_TAG_MAX = 8;
const DIRECTORY_SEARCH_MAX = 80;

class RegistryError extends Error {
  constructor(message) {
    super(message);
    this.name = 'RegistryError';
    this.code = 'REVIEWED_REGISTRY_INVALID';
  }
}

function isPlainObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value);
}

function fail(where, message) {
  throw new RegistryError(`${where}: ${message}`);
}

function stringAt(value, where, { min = 1, max = 120, pattern = null } = {}) {
  if (typeof value !== 'string') fail(where, 'must be a string');
  const out = value.trim();
  if (out.length < min || out.length > max) fail(where, `must be ${min} to ${max} characters`);
  if (/[\u0000-\u001f\u007f]/.test(out)) fail(where, 'must not contain control characters');
  if (pattern && !pattern.test(out)) fail(where, 'has an invalid format');
  return out;
}

function exactKeys(value, allowed, where) {
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) fail(where, `contains unsupported field ${key}`);
  }
}

function manifestUrl(value, where) {
  const raw = stringAt(value, where, { min: 16, max: 600 });
  let parsed;
  try {
    parsed = new URL(raw);
  } catch (error) {
    fail(where, 'must be an HTTPS URL');
  }
  if (parsed.protocol !== 'https:' || !parsed.hostname || parsed.username || parsed.password) {
    fail(where, 'must be a credential-free HTTPS URL');
  }
  if (parsed.port && parsed.port !== '443') fail(where, 'must use the default HTTPS port');
  if (parsed.search || parsed.hash) fail(where, 'must not contain a query or fragment');
  if (!/\/manifest\.json$/i.test(parsed.pathname)) fail(where, 'must end in /manifest.json');
  return parsed.toString();
}

function resourcesAt(value, where) {
  if (!Array.isArray(value) || value.length === 0 || value.length > ALLOWED_RESOURCE_NAMES.size) {
    fail(where, 'must contain one to four resource declarations');
  }
  const seen = new Set();
  const out = [];
  for (let i = 0; i < value.length; i++) {
    const item = value[i];
    const itemWhere = `${where}[${i}]`;
    if (!isPlainObject(item)) fail(itemWhere, 'must be an object');
    exactKeys(item, new Set(['name', 'types']), itemWhere);
    const name = stringAt(item.name, `${itemWhere}.name`, {
      min: 3, max: 12, pattern: /^[a-z]+$/,
    });
    if (!ALLOWED_RESOURCE_NAMES.has(name)) fail(`${itemWhere}.name`, 'is not an allowed resource');
    if (seen.has(name)) fail(itemWhere, 'duplicates a resource');
    seen.add(name);
    if (!Array.isArray(item.types) || item.types.length === 0 || item.types.length > ALLOWED_MEDIA_TYPES.size) {
      fail(`${itemWhere}.types`, 'must contain one to four media types');
    }
    const typeSeen = new Set();
    const types = item.types.map((type, typeIndex) => {
      const normalized = stringAt(type, `${itemWhere}.types[${typeIndex}]`, {
        min: 4, max: 8, pattern: /^[a-z]+$/,
      });
      if (!ALLOWED_MEDIA_TYPES.has(normalized)) {
        fail(`${itemWhere}.types[${typeIndex}]`, 'is not an allowed media type');
      }
      if (typeSeen.has(normalized)) fail(`${itemWhere}.types[${typeIndex}]`, 'is duplicated');
      typeSeen.add(normalized);
      return normalized;
    });
    out.push(Object.freeze({ name, types: Object.freeze(types) }));
  }
  return Object.freeze(out);
}

/** Optional, bounded taxonomy used only by the local reviewed directory. */
function labelsAt(value, where) {
  if (value === undefined) return Object.freeze([]);
  if (!Array.isArray(value) || value.length === 0 || value.length > DIRECTORY_TAG_MAX) {
    fail(where, 'must contain one to ' + DIRECTORY_TAG_MAX + ' labels when present');
  }
  const seen = new Set();
  const out = value.map((label, index) => {
    const normalized = stringAt(label, where + '[' + index + ']', {
      min: 1, max: DIRECTORY_LABEL_MAX, pattern: DIRECTORY_LABEL_PATTERN,
    });
    if (seen.has(normalized)) fail(where + '[' + index + ']', 'is duplicated');
    seen.add(normalized);
    return normalized;
  });
  return Object.freeze(out);
}

function descriptionAt(value, where) {
  if (value === undefined) return '';
  const out = stringAt(value, where, { min: 1, max: 320 });
  if (/\b(?:https?|magnet):/i.test(out)) fail(where, 'must not contain a URL');
  return out;
}

function entryAt(value, index, ids) {
  const where = `entries[${index}]`;
  if (!isPlainObject(value)) fail(where, 'must be an object');
  exactKeys(value, ENTRY_KEYS, where);
  const id = stringAt(value.id, `${where}.id`, {
    min: 2, max: 64, pattern: /^[a-z][a-z0-9-]*$/,
  });
  if (ids.has(id)) fail(`${where}.id`, 'is duplicated');
  ids.add(id);
  const name = stringAt(value.name, `${where}.name`, { min: 1, max: 96 });
  const version = stringAt(value.version, `${where}.version`, {
    min: 5, max: 32, pattern: /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/,
  });
  const manifest = manifestUrl(value.manifest, `${where}.manifest`);
  const manifestId = stringAt(value.manifestId, `${where}.manifestId`, {
    min: 2, max: 128, pattern: /^[a-z0-9][a-z0-9._-]*$/,
  });
  const resources = resourcesAt(value.resources, `${where}.resources`);
  const description = descriptionAt(value.description, `${where}.description`);
  const categories = labelsAt(value.categories, `${where}.categories`);
  const tags = labelsAt(value.tags, `${where}.tags`);
  if (value.configurationRequired !== undefined && typeof value.configurationRequired !== 'boolean') {
    fail(`${where}.configurationRequired`, 'must be a boolean when present');
  }
  const configurationRequired = value.configurationRequired === true;
  return Object.freeze({
    id, name, version, manifest, manifestId, resources,
    description, categories, tags, configurationRequired,
  });
}

/** Parse and validate an in-memory registry; exposed for a deterministic no-network verification. */
function parseDocument(raw, source = 'registry') {
  let document;
  try {
    document = typeof raw === 'string' ? JSON.parse(raw) : raw;
  } catch (error) {
    fail(source, 'is not valid JSON');
  }
  if (!isPlainObject(document)) fail(source, 'must be an object');
  exactKeys(document, new Set(['version', 'entries']), source);
  if (document.version !== 1) fail(`${source}.version`, 'must be 1');
  if (!Array.isArray(document.entries)) fail(`${source}.entries`, 'must be an array');
  if (document.entries.length > 128) fail(`${source}.entries`, 'may not exceed 128 entries');
  const ids = new Set();
  const entries = document.entries.map((entry, index) => entryAt(entry, index, ids));
  return Object.freeze({ version: 1, entries: Object.freeze(entries) });
}

function load(file = FILE) {
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (error) {
    throw new RegistryError(`reviewed registry is unreadable: ${error.message}`);
  }
  return parseDocument(raw, path.basename(file));
}

function copyEntry(entry) {
  return {
    id: entry.id,
    name: entry.name,
    version: entry.version,
    manifest: entry.manifest,
    manifestId: entry.manifestId,
    resources: entry.resources.map((resource) => ({ name: resource.name, types: resource.types.slice() })),
    description: entry.description,
    categories: entry.categories.slice(),
    tags: entry.tags.slice(),
    configurationRequired: entry.configurationRequired,
  };
}

function remoteTypes(value, where) {
  if (!Array.isArray(value) || value.length === 0) return { error: `${where} has no media types` };
  const seen = new Set();
  const out = [];
  for (const type of value) {
    if (typeof type !== 'string' || !ALLOWED_MEDIA_TYPES.has(type) || seen.has(type)) {
      return { error: `${where} has an invalid media type` };
    }
    seen.add(type);
    out.push(type);
  }
  return { value: out.sort() };
}

/**
 * Verify the manifest read during a reviewed install. A later route can use this before persistence
 * to quarantine a source whose id or capability surface drifted after review.
 */
function validateRemoteManifest(entry, manifest) {
  if (!entry || !isPlainObject(entry)) return { ok: false, error: 'reviewed entry is missing' };
  if (!isPlainObject(manifest)) return { ok: false, error: 'reviewed manifest is not an object' };
  if (manifest.id !== entry.manifestId) return { ok: false, error: 'reviewed manifest id changed' };
  if (manifest.version !== entry.version) return { ok: false, error: 'reviewed manifest version changed' };
  if (!Array.isArray(manifest.resources)) return { ok: false, error: 'reviewed manifest has no resources' };
  const globalTypes = remoteTypes(manifest.types, 'manifest');
  const actual = new Map();
  const remoteNames = new Set();
  for (const resource of manifest.resources) {
    const name = typeof resource === 'string' ? resource : resource && resource.name;
    if (typeof name !== 'string' || remoteNames.has(name)) {
      return { ok: false, error: 'reviewed manifest has an unsupported resource' };
    }
    remoteNames.add(name);
    // `addon_catalog` is intentionally not mounted through this Hub. It is a known, inert
    // extension on official metadata manifests and is tolerated only so its presence cannot force
    // an otherwise reviewed source into a stale, less exact registry declaration.
    if (TOLERATED_REMOTE_EXTENSION_RESOURCES.has(name)) continue;
    if (!ALLOWED_RESOURCE_NAMES.has(name)) {
      return { ok: false, error: 'reviewed manifest has an unsupported resource' };
    }
    const types = remoteTypes(
      typeof resource === 'string' ? manifest.types : (resource.types || manifest.types),
      `manifest resource ${name}`,
    );
    if (types.error) return { ok: false, error: types.error };
    actual.set(name, types.value);
  }
  // A manifest-wide types list is only meaningful for string resources; its own validation above
  // may have failed when every resource supplies local types, which is an allowed protocol shape.
  if (globalTypes.error && manifest.resources.some((resource) =>
    typeof resource === 'string' && !TOLERATED_REMOTE_EXTENSION_RESOURCES.has(resource)
  )) {
    return { ok: false, error: globalTypes.error };
  }
  const expected = new Map(entry.resources.map((resource) => [resource.name, resource.types.slice().sort()]));
  if (actual.size !== expected.size) return { ok: false, error: 'reviewed manifest resources changed' };
  for (const [name, types] of expected) {
    const received = actual.get(name);
    if (!received || received.length !== types.length || received.some((type, index) => type !== types[index])) {
      return { ok: false, error: 'reviewed manifest resource types changed' };
    }
  }
  return { ok: true };
}

/** Internal lookup for an install route. The caller receives a copy, not mutable registry state. */
function find(id, file = FILE) {
  const wanted = String(id || '').trim();
  if (!wanted) return null;
  const entry = load(file).entries.find((candidate) => candidate.id === wanted);
  return entry ? copyEntry(entry) : null;
}

/** Safe shape for clients: source identity/capabilities, never its manifest URL or expected remote id. */
function publicEntries(file = FILE) {
  return load(file).entries.map((entry) => ({
    id: entry.id,
    name: entry.name,
    version: entry.version,
    resources: entry.resources.map((resource) => ({ name: resource.name, types: resource.types.slice() })),
  }));
}

function normaliseDirectoryLabel(value) {
  if (value === undefined || value === null) return null;
  const normalized = String(value).trim().toLowerCase();
  if (!normalized || normalized.length > DIRECTORY_LABEL_MAX || !DIRECTORY_LABEL_PATTERN.test(normalized)) return null;
  return normalized;
}

function normaliseDirectoryQuery(value) {
  if (value === undefined || value === null) return null;
  const raw = String(value).trim().replace(/\s+/g, ' ');
  if (!raw || raw.length > DIRECTORY_SEARCH_MAX || /[\u0000-\u001f\u007f]/.test(raw)
    || /\b(?:https?|magnet):/i.test(raw)) return null;
  return raw.toLocaleLowerCase();
}

function installedRegistryIds(value) {
  if (!(value instanceof Set) && !Array.isArray(value)) return new Set();
  const ids = new Set();
  for (const id of value) {
    const normalized = normaliseDirectoryLabel(id);
    if (normalized) ids.add(normalized);
  }
  return ids;
}

function directoryEntry(entry, installedIds) {
  const types = new Set();
  for (const resource of entry.resources) {
    for (const type of resource.types) types.add(type);
  }
  const install = entry.configurationRequired
    ? {
      available: false,
      method: 'registry-id',
      requiresAdmin: true,
      reason: 'configuration-required',
      // A TV receives only this capability flag. It starts an opaque, expiring web-code handoff;
      // neither the source manifest nor a configured address belongs in a directory response.
      setup: { available: true, method: 'web-code', expiresInSeconds: 600 },
    }
    : { available: true, method: 'registry-id', requiresAdmin: true };
  return {
    id: entry.id,
    name: entry.name,
    version: entry.version,
    description: entry.description,
    categories: entry.categories.slice(),
    tags: entry.tags.slice(),
    types: [...types].sort(),
    resources: entry.resources.map((resource) => ({ name: resource.name, types: resource.types.slice() })),
    trust: 'reviewed',
    configurationRequired: entry.configurationRequired,
    installed: installedIds.has(entry.id),
    // Clients must use this action contract rather than attempting a remote manifest URL.
    install,
  };
}

function directoryOptions(options) {
  const input = isPlainObject(options) ? options : {};
  return {
    category: normaliseDirectoryLabel(input.category),
    type: normaliseDirectoryLabel(input.type),
    q: normaliseDirectoryQuery(input.q === undefined ? input.query : input.q),
    installedIds: installedRegistryIds(input.installedIds),
  };
}

function matchesDirectoryQuery(entry, query) {
  if (!query) return true;
  const searchable = [entry.name, entry.description, ...entry.categories, ...entry.tags]
    .join(' ')
    .toLocaleLowerCase();
  return searchable.includes(query);
}

/**
 * Safe, deterministic browse projection for the reviewed source directory. Type is always derived
 * from the reviewed manifest surface; categories/tags are repository-owned labels, never remote data.
 */
function directoryFromEntries(entries, options = {}) {
  const browse = directoryOptions(options);
  const categories = new Set();
  const types = new Set();
  const rows = [];
  for (const entry of entries) {
    for (const category of entry.categories) categories.add(category);
    const entryTypes = new Set();
    for (const resource of entry.resources) {
      for (const type of resource.types) {
        types.add(type);
        entryTypes.add(type);
      }
    }
    const matchesCategory = !browse.category || entry.categories.includes(browse.category);
    const matchesType = !browse.type || entryTypes.has(browse.type);
    if (matchesCategory && matchesType && matchesDirectoryQuery(entry, browse.q)) {
      rows.push(directoryEntry(entry, browse.installedIds));
    }
  }
  rows.sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  return {
    version: 1,
    filters: {
      categories: [...categories].sort(),
      types: [...types].sort(),
    },
    query: { category: browse.category, type: browse.type, q: browse.q },
    entries: rows,
  };
}

/** A complete safe browse response; no network is contacted while constructing it. */
function publicDirectory(options = {}, file = FILE) {
  return directoryFromEntries(load(file).entries, options);
}

/** Safe detail lookup for a single reviewed source. Returns null for an unknown or malformed id. */
function publicEntry(id, options = {}, file = FILE) {
  const wanted = normaliseDirectoryLabel(id);
  if (!wanted) return null;
  const entry = load(file).entries.find((candidate) => candidate.id === wanted);
  return entry ? directoryEntry(entry, directoryOptions(options).installedIds) : null;
}

module.exports = {
  FILE,
  RegistryError,
  ALLOWED_RESOURCE_NAMES: Object.freeze([...ALLOWED_RESOURCE_NAMES]),
  TOLERATED_REMOTE_EXTENSION_RESOURCES: Object.freeze([...TOLERATED_REMOTE_EXTENSION_RESOURCES]),
  ALLOWED_MEDIA_TYPES: Object.freeze([...ALLOWED_MEDIA_TYPES]),
  parseDocument,
  load,
  find,
  publicEntries,
  directoryFromEntries,
  publicDirectory,
  publicEntry,
  validateRemoteManifest,
};
