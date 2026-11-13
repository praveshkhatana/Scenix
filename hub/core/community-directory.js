'use strict';
/*
 * core/community-directory.js — a deliberately separate, consent-gated community source directory.
 *
 * This is not a reviewed registry and not a proxy for arbitrary URLs. The only catalogue request is
 * the fixed official protocol endpoint below, made through the add-on boundary's DNS-pinned transport.
 * Its untrusted descriptors are reduced to a bounded, redacted server-side cache. Clients receive an
 * opaque descriptor id and can ask the Hub to install only that cached descriptor after acknowledging
 * that community publication is not an endorsement.
 */

const crypto = require('crypto');
const addons = require('./addons');

const OFFICIAL_CATALOG_URL = 'https://v3-cinemeta.strem.io/addon_catalog/all/community.json';
const FETCH_TIMEOUT_MS = 8000;
const CACHE_TTL_MS = 10 * 60 * 1000;
const MAX_DOCUMENT_BYTES = 512 * 1024;
const MAX_RAW_ENTRIES = 192;
const MAX_DIRECTORY_ENTRIES = 128;
const MAX_RESOURCES = 8;
const MAX_CONFIG_FIELDS = 64;
const MAX_QUERY_LENGTH = 80;
const ALLOWED_RESOURCE_NAMES = new Set(['catalog', 'meta', 'stream', 'subtitles']);
const ALLOWED_MEDIA_TYPES = new Set(['movie', 'series', 'channel', 'other']);
const DESCRIPTOR_ID_PATTERN = /^community-[a-f0-9]{24}$/;

class CommunityDirectoryError extends Error {
  constructor(message) {
    super(message);
    this.name = 'CommunityDirectoryError';
    this.code = 'COMMUNITY_DIRECTORY_UNAVAILABLE';
  }
}

function isPlainObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value);
}

function plainText(value, max) {
  if (typeof value !== 'string') return '';
  return value
    .replace(/[\u0000-\u001f\u007f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, max);
}

/** Do not let a display label become a second route for an endpoint or an accidental pasted secret. */
function publicText(value, max) {
  const text = plainText(value, Math.max(max * 2, max));
  if (!text) return '';
  return text
    .replace(/\b(?:https?|magnet):[^\s)\]}>,]+/gi, '[link removed]')
    .replace(/\b(?:api[_ -]?key|access[_ -]?token|password|secret)\s*[:=]\s*\S+/gi, '[redacted]')
    .slice(0, max)
    .trim();
}

function safeManifestUrl(value) {
  const raw = plainText(value, 1024);
  if (!raw) return null;
  let parsed;
  try {
    parsed = new URL(raw);
  } catch (error) {
    return null;
  }
  const host = parsed.hostname.replace(/^\[|\]$/g, '').replace(/\.$/, '').toLowerCase();
  if (parsed.protocol !== 'https:' || !host || parsed.username || parsed.password || parsed.search || parsed.hash
      || host === 'localhost' || /\.(?:localhost|local|internal|home|lan)$/.test(host)
      || !/\/manifest\.json$/i.test(parsed.pathname)) return null;
  return parsed.toString();
}

function safeManifestId(value) {
  const id = plainText(value, 160);
  return /^[a-z0-9][a-z0-9._:-]*$/i.test(id) ? id : null;
}

function safeVersion(value) {
  const version = plainText(value, 64);
  return /^[a-z0-9][a-z0-9._+-]*$/i.test(version) ? version : null;
}

function normaliseTypes(value) {
  if (!Array.isArray(value)) return [];
  const types = new Set();
  for (const item of value.slice(0, ALLOWED_MEDIA_TYPES.size + 4)) {
    const type = plainText(item, 16).toLowerCase();
    if (ALLOWED_MEDIA_TYPES.has(type)) types.add(type);
  }
  return [...types].sort();
}

/**
 * Convert the protocol's global-or-per-resource types into the small capability contract the Hub
 * understands. Unknown protocol extensions are intentionally inert rather than being mounted by
 * discovery alone.
 */
function resourceContract(manifest) {
  if (!isPlainObject(manifest) || !Array.isArray(manifest.resources)) return [];
  const globalTypes = normaliseTypes(manifest.types);
  const merged = new Map();
  for (const raw of manifest.resources.slice(0, MAX_RESOURCES)) {
    const name = typeof raw === 'string' ? plainText(raw, 20).toLowerCase()
      : plainText(raw && raw.name, 20).toLowerCase();
    if (!ALLOWED_RESOURCE_NAMES.has(name)) continue;
    const localTypes = typeof raw === 'string' ? globalTypes : normaliseTypes(raw && raw.types);
    const types = localTypes.length ? localTypes : globalTypes;
    if (!types.length) continue;
    if (!merged.has(name)) merged.set(name, new Set());
    for (const type of types) merged.get(name).add(type);
  }
  return [...merged.entries()]
    .map(([name, types]) => ({ name, types: [...types].sort() }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

function sameResources(left, right) {
  if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length) return false;
  return left.every((resource, index) => resource && right[index]
    && resource.name === right[index].name
    && resource.types.length === right[index].types.length
    && resource.types.every((type, typeIndex) => type === right[index].types[typeIndex]));
}

function configurationRequired(manifest) {
  const hints = isPlainObject(manifest && manifest.behaviorHints) ? manifest.behaviorHints : {};
  if (hints.configurationRequired === true) return true;
  // Some community manifests omit the hint while declaring a required field. We never surface that
  // field, but must still fail closed instead of persisting a source that cannot work without it.
  return Array.isArray(manifest && manifest.config)
    && manifest.config.slice(0, MAX_CONFIG_FIELDS).some((field) => isPlainObject(field) && field.required === true);
}

function behaviour(manifest) {
  const hints = isPlainObject(manifest && manifest.behaviorHints) ? manifest.behaviorHints : {};
  return {
    adult: hints.adult === true,
    p2p: hints.p2p === true,
    configurable: hints.configurable === true || (Array.isArray(manifest && manifest.config) && manifest.config.length > 0),
    configurationRequired: configurationRequired(manifest),
  };
}

function descriptorId(manifestUrl, manifestId, version) {
  const digest = crypto.createHash('sha256')
    .update('community-directory-v1\u0000' + manifestUrl + '\u0000' + manifestId + '\u0000' + version)
    .digest('hex')
    .slice(0, 24);
  return 'community-' + digest;
}

function descriptorFromRaw(raw) {
  if (!isPlainObject(raw) || !isPlainObject(raw.manifest)) return null;
  const manifestUrl = safeManifestUrl(raw.transportUrl);
  const manifest = raw.manifest;
  const manifestId = safeManifestId(manifest.id);
  const version = safeVersion(manifest.version);
  const name = publicText(manifest.name, 96);
  const resources = resourceContract(manifest);
  if (!manifestUrl || !manifestId || !version || !name || name === '[link removed]' || !resources.length) return null;
  const types = [...new Set(resources.flatMap((resource) => resource.types))].sort();
  if (!types.length) return null;
  const flags = behaviour(manifest);
  return Object.freeze({
    id: descriptorId(manifestUrl, manifestId, version),
    // This is intentionally server-only. It is never included in publicEntry() or directory output.
    manifestUrl,
    manifestId,
    name,
    version,
    description: publicText(manifest.description, 320),
    resources: Object.freeze(resources.map((resource) => Object.freeze({
      name: resource.name,
      types: Object.freeze(resource.types.slice()),
    }))),
    types: Object.freeze(types),
    adult: flags.adult,
    p2p: flags.p2p,
    configurable: flags.configurable,
    configurationRequired: flags.configurationRequired,
  });
}

/** Parse the remote document without retaining its raw manifests or config declarations. */
function parseDocument(raw) {
  const text = Buffer.isBuffer(raw) ? raw.toString('utf8') : typeof raw === 'string' ? raw : '';
  if (!text) throw new CommunityDirectoryError('community catalog is empty');
  if (Buffer.byteLength(text, 'utf8') > MAX_DOCUMENT_BYTES) {
    throw new CommunityDirectoryError('community catalog exceeds its size limit');
  }
  let document;
  try {
    document = JSON.parse(text);
  } catch (error) {
    throw new CommunityDirectoryError('community catalog is not valid JSON');
  }
  if (!isPlainObject(document) || !Array.isArray(document.addons)) {
    throw new CommunityDirectoryError('community catalog has an unsupported shape');
  }
  const byId = new Map();
  for (const rawEntry of document.addons.slice(0, MAX_RAW_ENTRIES)) {
    const entry = descriptorFromRaw(rawEntry);
    // Duplicate/colliding descriptors are not merged. The first canonical descriptor remains a complete
    // install contract; accepting a later fragment could let unreviewed fields alter it.
    if (entry && !byId.has(entry.id) && byId.size < MAX_DIRECTORY_ENTRIES) byId.set(entry.id, entry);
  }
  const entries = [...byId.values()].sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
  return Object.freeze({ entries: Object.freeze(entries), byId });
}

function normaliseType(value) {
  const type = plainText(value, 16).toLowerCase();
  return ALLOWED_MEDIA_TYPES.has(type) ? type : null;
}

function normaliseQuery(value) {
  if (value === undefined || value === null) return null;
  const query = plainText(String(value), MAX_QUERY_LENGTH + 1);
  if (!query || query.length > MAX_QUERY_LENGTH || /\b(?:https?|magnet):/i.test(query)) return null;
  return query.toLocaleLowerCase();
}

function installedDirectoryIds(value) {
  if (!(value instanceof Set) && !Array.isArray(value)) return new Set();
  const ids = new Set();
  for (const candidate of [...value].slice(0, MAX_DIRECTORY_ENTRIES * 2)) {
    const id = String(candidate || '').trim();
    if (DESCRIPTOR_ID_PATTERN.test(id)) ids.add(id);
  }
  return ids;
}

function installContract(entry) {
  const base = {
    method: 'community-id',
    requiresAdmin: true,
    requiresAcknowledgement: true,
    acknowledgement: 'community-not-endorsed',
  };
  if (entry.configurationRequired) {
    return {
      ...base,
      available: false,
      reason: 'configuration-required',
      // The same opaque handoff as reviewed entries, with the Community acknowledgement still
      // required before a session is minted.  The server-only descriptor remains off the wire.
      setup: { available: true, method: 'web-code', expiresInSeconds: 600 },
    };
  }
  return { ...base, available: true };
}

function publicEntryFromDescriptor(entry, installedIds) {
  if (!entry) return null;
  return {
    id: entry.id,
    name: entry.name,
    version: entry.version,
    description: entry.description,
    types: entry.types.slice(),
    resources: entry.resources.map((resource) => ({ name: resource.name, types: resource.types.slice() })),
    trust: 'community',
    nonEndorsed: true,
    adult: entry.adult,
    p2p: entry.p2p,
    configurable: entry.configurable,
    configurationRequired: entry.configurationRequired,
    installed: installedIds.has(entry.id),
    install: installContract(entry),
  };
}

function browseOptions(options) {
  const input = isPlainObject(options) ? options : {};
  return {
    type: normaliseType(input.type),
    q: normaliseQuery(input.q === undefined ? input.query : input.q),
    installedIds: installedDirectoryIds(input.installedIds),
  };
}

function publicDirectoryFromSnapshot(snapshot, options = {}) {
  const browse = browseOptions(options);
  const types = new Set();
  const entries = [];
  for (const entry of snapshot.entries) {
    for (const type of entry.types) types.add(type);
    const searchable = [entry.name, entry.description, ...entry.types].join(' ').toLocaleLowerCase();
    if (browse.type && !entry.types.includes(browse.type)) continue;
    if (browse.q && !searchable.includes(browse.q)) continue;
    entries.push(publicEntryFromDescriptor(entry, browse.installedIds));
  }
  return {
    version: 1,
    trust: 'community',
    nonEndorsed: true,
    notice: 'Community sources are not reviewed or endorsed. Review privacy, legality, and availability before installation.',
    acknowledgement: { required: true, field: 'nonEndorsementAcknowledged' },
    filters: { types: [...types].sort() },
    query: { type: browse.type, q: browse.q },
    entries,
  };
}

function validDescriptorId(value) {
  const id = String(value || '').trim();
  return DESCRIPTOR_ID_PATTERN.test(id) ? id : null;
}

/** The explicit action signal; generic form submits cannot accidentally consent to this install lane. */
function hasNonEndorsementAcknowledgement(body) {
  return isPlainObject(body) ? body.nonEndorsementAcknowledged === true : false;
}

/** Ensure the downloaded manifest still means exactly what the cached descriptor showed the user. */
function validateRemoteManifest(entry, manifest) {
  if (!entry || !isPlainObject(entry) || !isPlainObject(manifest)) {
    return { ok: false, error: 'community source contract is unavailable' };
  }
  if (manifest.id !== entry.manifestId) return { ok: false, error: 'community manifest id changed' };
  if (manifest.version !== entry.version) return { ok: false, error: 'community manifest version changed' };
  if (!sameResources(entry.resources, resourceContract(manifest))) {
    return { ok: false, error: 'community manifest capabilities changed' };
  }
  const flags = behaviour(manifest);
  if (flags.configurationRequired) return { ok: false, error: 'community manifest now requires configuration' };
  if (flags.adult !== entry.adult || flags.p2p !== entry.p2p) {
    return { ok: false, error: 'community manifest safety indicators changed' };
  }
  return { ok: true };
}

/**
 * A completed configuration is allowed to clear the required-config flag, but it must not change
 * the cached Community descriptor's identity, capabilities, or adult/P2P disclosures.  This is a
 * separate validator so the ordinary direct-install path remains fail-closed for required setup.
 */
function validateConfiguredManifest(entry, manifest) {
  if (!entry || !isPlainObject(entry) || !isPlainObject(manifest)) {
    return { ok: false, error: 'community source contract is unavailable' };
  }
  if (manifest.id !== entry.manifestId) return { ok: false, error: 'community manifest id changed' };
  if (manifest.version !== entry.version) return { ok: false, error: 'community manifest version changed' };
  if (!sameResources(entry.resources, resourceContract(manifest))) {
    return { ok: false, error: 'community manifest capabilities changed' };
  }
  const flags = behaviour(manifest);
  if (flags.configurationRequired) return { ok: false, error: 'community manifest still requires configuration' };
  if (flags.adult !== entry.adult || flags.p2p !== entry.p2p) {
    return { ok: false, error: 'community manifest safety indicators changed' };
  }
  return { ok: true };
}

/**
 * An injectable service lets the self-test exercise every parser/cache path without a network request.
 * Production has no URL override: regardless of callers, only OFFICIAL_CATALOG_URL is ever fetched.
 */
function createDirectory(options = {}) {
  const input = isPlainObject(options) ? options : {};
  const now = typeof input.now === 'function' ? input.now : () => Date.now();
  const ttl = Number.isFinite(input.cacheTtlMs)
    ? Math.max(1000, Math.min(CACHE_TTL_MS, Math.floor(input.cacheTtlMs))) : CACHE_TTL_MS;
  const fetchCatalog = typeof input.fetchCatalog === 'function'
    ? input.fetchCatalog
    : () => addons.util.get(OFFICIAL_CATALOG_URL, FETCH_TIMEOUT_MS, false, { redirectPolicy: 'same-origin' });
  let cache = null;
  let inflight = null;

  async function snapshot() {
    const current = now();
    if (cache && current - cache.fetchedAt >= 0 && current - cache.fetchedAt < ttl) return cache;
    if (inflight) return inflight;
    const request = Promise.resolve()
      .then(() => fetchCatalog(OFFICIAL_CATALOG_URL))
      .then((raw) => {
        const parsed = parseDocument(raw);
        // No raw catalogue body or manifest object is retained after parsing.
        cache = Object.freeze({ ...parsed, fetchedAt: now() });
        return cache;
      });
    inflight = request;
    try {
      return await request;
    } finally {
      if (inflight === request) inflight = null;
    }
  }

  return Object.freeze({
    async directory(options) {
      return publicDirectoryFromSnapshot(await snapshot(), options);
    },
    async publicEntry(id, options) {
      const descriptor = (await snapshot()).byId.get(validDescriptorId(id));
      return publicEntryFromDescriptor(descriptor, browseOptions(options).installedIds);
    },
    // Internal-only lookup for the install route. It accepts the opaque cache id, never a URL.
    async find(id) {
      return (await snapshot()).byId.get(validDescriptorId(id)) || null;
    },
  });
}

const defaultDirectory = createDirectory();

module.exports = {
  OFFICIAL_CATALOG_URL,
  CACHE_TTL_MS,
  MAX_DIRECTORY_ENTRIES,
  CommunityDirectoryError,
  parseDocument,
  createDirectory,
  publicDirectory: (options) => defaultDirectory.directory(options),
  publicEntry: (id, options) => defaultDirectory.publicEntry(id, options),
  find: (id) => defaultDirectory.find(id),
  hasNonEndorsementAcknowledgement,
  installContract,
  validateRemoteManifest,
  validateConfiguredManifest,
};
