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
// Kept in memory only: closing/reloading the page ends the browser's administrator session. The
// server binds it to this API origin as well, so an override cannot carry a household capability to
// another hub.
let adminSession = null;

export function apiBase() { return base || window.location.origin; }
export function apiOverrideSet() { return !!base; }

export function setApiBase(value) {
  base = clean(value);
  clearAdminSession();
  try {
    if (base) localStorage.setItem(LS_KEY, base);
    else localStorage.removeItem(LS_KEY);
  } catch (e) { /* private mode */ }
  return base;
}

/** Forget the in-memory capability. It is intentionally never persisted across a page reload. */
export function clearAdminSession() { adminSession = null; }

/**
 * The hub returns its expiry with the opaque capability. Clearing it on this side avoids an avoidable
 * rejected write and lets the UI ask again before an owner reaches a dead-looking row. A legacy hub
 * that did not return an expiry remains safe: its server still rejects an expired capability and the
 * refusal below clears it before the UI retries once.
 */
export function adminSessionActive() {
  if (!adminSession) return false;
  if (adminSession.expiresAt && adminSession.expiresAt <= Date.now()) {
    clearAdminSession();
    return false;
  }
  return true;
}

/** The one refusal shape that means a Settings-PIN capability is absent or has expired. */
export function isSettingsSessionRefusal(error) {
  return !!(error && error.status === 403 && error.body && error.body.sessionRequired === true);
}

function rememberAdminSession(answer) {
  // A lock/PIN change invalidates every old capability on the hub. Mirror that immediately so a
  // browser never keeps replaying a token the hub has already revoked.
  clearAdminSession();
  const token = answer && typeof answer.adminSession === 'string' ? answer.adminSession.trim() : '';
  if (!/^[a-f0-9]{64}$/i.test(token)) return;
  const rawExpiry = Number(answer && answer.adminSessionExpiresAt);
  adminSession = {
    token,
    origin: new URL(apiBase(), window.location.href).origin,
    expiresAt: Number.isFinite(rawExpiry) && rawExpiry > Date.now() ? rawExpiry : null,
  };
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
    const requestHeaders = Object.assign({}, headers);
    const targetOrigin = new URL(apiUrl(path), window.location.href).origin;
    if (adminSessionActive() && adminSession.origin === targetOrigin) {
      requestHeaders['X-Scenix-Admin-Session'] = adminSession.token;
    }
    res = await fetch(apiUrl(path), {
      method,
      signal: ctrl.signal,
      headers: body ? Object.assign({ 'Content-Type': 'application/json' }, requestHeaders) : requestHeaders,
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
  if (!res.ok) {
    const error = new ApiError((json && json.error) || `HTTP ${res.status}`, res.status, json);
    if (isSettingsSessionRefusal(error)) clearAdminSession();
    throw error;
  }
  if (json) json.__tookMs = Date.now() - t0;
  return json;
}

/* ---- the endpoints, one per route, in the order the hub documents them ---- */

export const api = {
  manifest: () => request('/app/v1/manifest', { timeout: 8000 }),
  profiles: () => request('/app/v1/profiles', { timeout: 8000 }),
  // What the navigation may offer, in one small answer. The bar asks this at start-up rather than
  // guessing: a hub without a games add-on (or without a live playlist) must not offer a tab it
  // cannot fill. The native client gates its doors on the same two facts.
  caps: () => request('/app/v1/caps', { timeout: 6000 }),
  // Game mode shelves. A game is not a title — it carries `playUrl`, `fallbackUrl` and a controller
  // flag instead of sources — so this stays its own endpoint, the same one the TV reads.
  games: () => request('/app/v1/games', { timeout: 12000 }),
  home: (profile) => request(`/app/v1/home?profile=${encodeURIComponent(profile)}`, { timeout: 10000 }),
  row: (rowId, profile) => request(`/app/v1/row/${encodeURIComponent(rowId)}?profile=${encodeURIComponent(profile)}`, { timeout: 8000 }),
  search: (q, profile) => request(`/app/v1/search?q=${encodeURIComponent(q)}&profile=${encodeURIComponent(profile)}`, { timeout: 8000 }),
  meta: (identity, profile) => request(`/app/v1/meta/${encodeURIComponent(identity)}${profile ? `?profile=${encodeURIComponent(profile)}` : ''}`, { timeout: 8000 }),
  trailer: (identity, profile) => request(`/app/v1/trailer/${encodeURIComponent(identity)}?profile=${encodeURIComponent(profile)}`, { timeout: 8000 }),
  subs: (identity, profile, langs) => request(`/app/v1/subs/${encodeURIComponent(identity)}?profile=${encodeURIComponent(profile)}&langs=${encodeURIComponent(langs || '')}`, { timeout: 8000 }),
  // The hub now answers a Play press inside ~8 s, but a cold copy can still take a few more seconds to
  // produce its first byte. The old 14 s was below the hub's own worst case, so a working request was
  // aborted by the client and shown as "the hub did not answer".
  play: (body) => request('/app/v1/play', { method: 'POST', body, timeout: 30000 }),
  switchSource: (body) => request('/app/v1/switch', { method: 'POST', body, timeout: 30000 }),
  progress: (body) => request('/app/v1/progress', { method: 'POST', body, timeout: 6000 }),
  // Continue watching management: dismiss hides the tile (the position survives, and pressing the
  // title again brings it back); reset rewinds a title to never-started.
  dismiss: (body) => request('/app/v1/progress/dismiss', { method: 'POST', body, timeout: 6000 }),
  resetProgress: (body) => request('/app/v1/progress/reset', { method: 'POST', body, timeout: 6000 }),
  // My List lives on the hub, so the living room and the browser read the same shelf.
  myList: (profile) => request(`/app/v1/list?profile=${encodeURIComponent(profile)}`, { timeout: 6000 }),
  setMyList: (profile, identity, add) => request('/app/v1/list',
    { method: 'POST', body: { profile, identity, add }, timeout: 6000 }),
  prefs: (profile) => request(`/app/v1/prefs?profile=${encodeURIComponent(profile)}`, { timeout: 6000 }),
  // The hub's POST reads the profile from the body (the native client has always sent it there); the
  // query is for a reader. Sending only the query made every write from this page answer `400 profile
  // required`, so a switch flipped on screen and the value never changed.
  savePrefs: (profile, prefs) => request(`/app/v1/prefs?profile=${encodeURIComponent(profile)}`,
    { method: 'POST', body: Object.assign({}, prefs, { profile }), timeout: 6000 }),
  health: () => request('/healthz', { timeout: 8000 }),

  // Add-on management. The hub keeps the registry, so this is only a view of it: what is installed,
  // whether it is switched on, and what it last failed at.
  addons: () => request('/app/v1/addons', { timeout: 9000 }),
  reviewedAddons: () => request('/app/v1/addon-registry', { timeout: 9000 }),
  installReviewedAddon: (id) => request(`/app/v1/addon-registry/${encodeURIComponent(id)}/install`,
    { method: 'POST', timeout: 15000 }),
  // Config-required sources begin on the TV or web, but complete only in this browser with an
  // expiring opaque code. Neither the directory nor this client receives an upstream manifest URL.
  startReviewedSourceSetup: (id) => request(`/app/v1/addon-registry/${encodeURIComponent(id)}/setup-session`,
    { method: 'POST', timeout: 9000 }),
  // Community entries are a separately labelled, non-endorsed directory.  The browser never receives
  // a transport or manifest address: it can browse a Hub-redacted descriptor and install only its
  // short-lived opaque id after the source screen has recorded an explicit acknowledgement.
  communityAddonDirectory: ({ type = '', q = '' } = {}) => {
    const query = [];
    if (type) query.push(`type=${encodeURIComponent(String(type))}`);
    if (q) query.push(`q=${encodeURIComponent(String(q))}`);
    const suffix = query.length ? `?${query.join('&')}` : '';
    return request(`/app/v1/community-addon-directory${suffix}`, { timeout: 12000 });
  },
  communityAddonEntry: (id) => request(`/app/v1/community-addon-directory/${encodeURIComponent(id)}`,
    { timeout: 12000 }),
  installCommunityAddon: (id, nonEndorsementAcknowledged) => request(
    `/app/v1/community-addon-directory/${encodeURIComponent(id)}/install`,
    { method: 'POST', body: { nonEndorsementAcknowledged: nonEndorsementAcknowledged === true }, timeout: 15000 },
  ),
  startCommunitySourceSetup: (id, nonEndorsementAcknowledged) => request(
    `/app/v1/community-addon-directory/${encodeURIComponent(id)}/setup-session`,
    { method: 'POST', body: { nonEndorsementAcknowledged: nonEndorsementAcknowledged === true }, timeout: 12000 },
  ),
  sourceSetup: (code) => request(`/app/v1/source-setups/${encodeURIComponent(String(code || '').trim())}`,
    { timeout: 9000 }),
  completeSourceSetup: (code, manifestUrl) => request(
    `/app/v1/source-setups/${encodeURIComponent(String(code || '').trim())}/complete`,
    { method: 'POST', body: { manifestUrl: String(manifestUrl || '').trim() }, timeout: 20000 },
  ),
  setAddon: (id, on) => request(`/app/v1/addons/${encodeURIComponent(id)}/${on ? 'enable' : 'disable'}`,
    { method: 'POST', timeout: 9000 }),
  removeAddon: (id) => request(`/app/v1/addons/${encodeURIComponent(id)}`, { method: 'DELETE', timeout: 9000 }),

  // Installing asks the hub to go and read the other side's manifest, which is a network round trip on
  // someone else's schedule, so this gets more time than the calls that only touch local state.
  addAddon: (url) => request('/app/v1/addons', { method: 'POST', body: { url }, timeout: 15000 }),
  /* Settings and administration — the hub owns all of it, and the manifest says which of it exists. */
  settings: () => request('/app/v1/settings', { timeout: 8000 }),
  saveSettings: (body) => request('/app/v1/settings', { method: 'PUT', body, timeout: 8000 }),
  saveSettingsLock: async (body) => {
    const answer = await request('/app/v1/settings/lock', { method: 'POST', body, timeout: 6000 });
    rememberAdminSession(answer);
    return answer;
  },
  // The household's admin PIN is checked by the hub, never compared on this page: `settings.lock.enabled`
  // says a PIN is wanted, and this route is the only one that can say whether the number is right.
  unlockSettings: async (pin) => {
    const answer = await request('/app/v1/settings/unlock', { method: 'POST', body: { pin }, timeout: 6000 });
    rememberAdminSession(answer);
    return answer;
  },
  unlockProfile: (id, pin) => request(`/app/v1/profiles/${encodeURIComponent(id)}/unlock`, { method: 'POST', body: { pin }, timeout: 6000 }),
  createProfile: (body) => request('/app/v1/profiles', { method: 'POST', body, timeout: 6000 }),
  updateProfile: (id, body) => request(`/app/v1/profiles/${encodeURIComponent(id)}`, { method: 'PUT', body, timeout: 6000 }),
  // A DELETE with a body is unusual but legal, and it is the only way a locked profile can be removed by a
  // browser without the number travelling in a URL that gets logged. No body, no PIN: an unlocked profile.
  deleteProfile: (id, body) => request(`/app/v1/profiles/${encodeURIComponent(id)}`, { method: 'DELETE', body, timeout: 6000 }),
  clearCache: () => request('/app/v1/cache/clear', { method: 'POST', body: {}, timeout: 9000 }),
  // The hub's own build: GET states which commit this hub runs, how far behind its origin it is, and
  // why it would not pull if it would not; POST pulls and restarts the hub process. The gap is a
  // second long — the same gap any hub restart makes.
  hubUpdate: () => request('/app/v1/hub/update', { timeout: 20000 }),
  hubUpdateNow: () => request('/app/v1/hub/update', { method: 'POST', body: {}, timeout: 120000 }),
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
