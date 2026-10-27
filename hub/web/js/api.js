/**
 * js/api.js — every call to the hub, in one place.
 *
 * Same-origin by default: the hub serves this page and its API from one process. Two overrides exist so
 * the interface can be developed against a remote hub and so a thin shell can point at one:
 *
 *   ?api=http://<hub-host>:8788     on the URL, remembered in localStorage
 *   localStorage['scenix.api']      set from Settings, or by the shell before the page loads
 *
 * The hub sends `Access-Control-Allow-Origin: *`, so a page served anywhere can talk to it.
 */

const LS_KEY = 'scenix.api';

function clean(v) { return String(v || '').trim().replace(/\/+$/, ''); }

function initialBase() {
  let fromUrl = null;
  try {
    const u = new URL(window.location.href);
    fromUrl = clean(u.searchParams.get('api'));
  } catch (e) { /* ignore */ }
  if (fromUrl) {
    try { localStorage.setItem(LS_KEY, fromUrl); } catch (e) { /* private mode */ }
    return fromUrl;
  }
  try { return clean(localStorage.getItem(LS_KEY)); } catch (e) { return ''; }
}

let base = initialBase();

export function apiBase() { return base || window.location.origin; }
export function apiOverrideSet() { return !!base; }

export function setApiBase(value) {
  base = clean(value);
  try {
    if (base) localStorage.setItem(LS_KEY, base);
    else localStorage.removeItem(LS_KEY);
  } catch (e) { /* private mode */ }
  return base;
}

/** Turn a path from the hub (often root-relative, sometimes absolute) into a URL we can open. */
export function apiUrl(pathOrUrl) {
  const p = String(pathOrUrl || '');
  if (/^https?:/i.test(p)) return p;
  return apiBase() + (p.startsWith('/') ? p : '/' + p);
}

export class ApiError extends Error {
  constructor(message, status, body) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

async function request(path, { method = 'GET', body, timeout = 12000, headers = {} } = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  const t0 = Date.now();
  let res;
  try {
    res = await fetch(apiUrl(path), {
      method,
      signal: ctrl.signal,
      headers: body ? Object.assign({ 'Content-Type': 'application/json' }, headers) : headers,
      body: body ? JSON.stringify(body) : undefined,
      // A stale billboard on a TV is worse than one extra round trip; the hub caches everything anyway.
      cache: 'no-store',
    });
  } catch (e) {
    clearTimeout(timer);
    throw new ApiError(e.name === 'AbortError'
      ? `The hub did not answer within ${Math.round(timeout / 1000)}s`
      : `Cannot reach the hub at ${apiBase()}`, 0, null);
  }
  clearTimeout(timer);
  let json = null;
  const text = await res.text();
  try { json = text ? JSON.parse(text) : null; } catch (e) { json = { raw: text }; }
  if (!res.ok) throw new ApiError((json && json.error) || `HTTP ${res.status}`, res.status, json);
  if (json) json.__tookMs = Date.now() - t0;
  return json;
}

/* ---- the endpoints, one per route, in the order the hub documents them ---- */

export const api = {
  manifest: () => request('/app/v1/manifest', { timeout: 8000 }),
  profiles: () => request('/app/v1/profiles', { timeout: 8000 }),
  home: (profile) => request(`/app/v1/home?profile=${encodeURIComponent(profile)}`, { timeout: 10000 }),
  row: (rowId, profile) => request(`/app/v1/row/${encodeURIComponent(rowId)}?profile=${encodeURIComponent(profile)}`, { timeout: 8000 }),
  search: (q, profile) => request(`/app/v1/search?q=${encodeURIComponent(q)}&profile=${encodeURIComponent(profile)}`, { timeout: 8000 }),
  meta: (identity, profile) => request(`/app/v1/meta/${encodeURIComponent(identity)}${profile ? `?profile=${encodeURIComponent(profile)}` : ''}`, { timeout: 8000 }),
  trailer: (identity, profile) => request(`/app/v1/trailer/${encodeURIComponent(identity)}?profile=${encodeURIComponent(profile)}`, { timeout: 8000 }),
  subs: (identity, profile, langs) => request(`/app/v1/subs/${encodeURIComponent(identity)}?profile=${encodeURIComponent(profile)}&langs=${encodeURIComponent(langs || '')}`, { timeout: 8000 }),
  // The hub spends up to ~11s measuring copies before it answers, and says the client should allow 12.
  play: (body) => request('/app/v1/play', { method: 'POST', body, timeout: 14000 }),
  switchSource: (body) => request('/app/v1/switch', { method: 'POST', body, timeout: 14000 }),
  progress: (body) => request('/app/v1/progress', { method: 'POST', body, timeout: 6000 }),
  prefs: (profile) => request(`/app/v1/prefs?profile=${encodeURIComponent(profile)}`, { timeout: 6000 }),
  savePrefs: (profile, prefs) => request(`/app/v1/prefs?profile=${encodeURIComponent(profile)}`, { method: 'POST', body: prefs, timeout: 6000 }),
  health: () => request('/healthz', { timeout: 8000 }),
  /* Settings and administration — the hub owns all of it, and the manifest says which of it exists. */
  settings: () => request('/app/v1/settings', { timeout: 8000 }),
  saveSettings: (body) => request('/app/v1/settings', { method: 'PUT', body, timeout: 8000 }),
  unlockProfile: (id, pin) => request(`/app/v1/profiles/${encodeURIComponent(id)}/unlock`, { method: 'POST', body: { pin }, timeout: 6000 }),
  createProfile: (body) => request('/app/v1/profiles', { method: 'POST', body, timeout: 6000 }),
  updateProfile: (id, body) => request(`/app/v1/profiles/${encodeURIComponent(id)}`, { method: 'PUT', body, timeout: 6000 }),
  deleteProfile: (id) => request(`/app/v1/profiles/${encodeURIComponent(id)}`, { method: 'DELETE', timeout: 6000 }),
  clearCache: () => request('/app/v1/cache/clear', { method: 'POST', body: {}, timeout: 9000 }),
  characters: (seed, limit = 40, refresh = false) => request(
    `/app/v1/avatars/characters?limit=${Number(limit) || 40}&seed=${encodeURIComponent(String(seed || 'scenix'))}${refresh ? '&refresh=1' : ''}`,
    { timeout: 9000 }),
};

/**
 * Trailers are optional by contract: a 404 means "stay a still poster", not an error.
 */
export async function getTrailer(identity, profile) {
  try {
    const t = await api.trailer(identity, profile);
    return t && t.url ? t : null;
  } catch (e) {
    if (e.status === 404) return null;
    return null;
  }
}

/**
 * A stream URL for a <video> element. The hub hands back either an absolute URL or a root-relative
 * `/app/v1/proxy?u=…` that replays the headers the upstream demands (many feeds reject a browser's
 * User-Agent); going through the proxy is what makes signed or header-bound links play at all.
 */
export function streamUrl(target) {
  if (!target) return '';
  // The hub already rewrote `url` to its own proxy for gateway streams; use whatever it decided first.
  if (target.proxied && target.proxiedUrl) return apiUrl(target.proxiedUrl);
  if (target.url && !/^https?:/i.test(target.url)) return apiUrl(target.url);
  // A direct link that needs headers a browser cannot set (a Referer, a specific User-Agent) has to go
  // through the hub's proxy: the hub replays them there, with the `Key:Value||Key:Value` spelling its
  // own /app/v1/proxy parses.
  const headers = target.headers || {};
  if (target.url && Object.keys(headers).length) {
    const h = Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('||');
    return apiUrl(`/app/v1/proxy?u=${encodeURIComponent(target.url)}&h=${encodeURIComponent(h)}`);
  }
  return target.url || '';
}
