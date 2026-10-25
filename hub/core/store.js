'use strict';
/*
 * core/store.js — the hub's only memory across restarts. JSON files under hub/data/.
 *
 *   data/library.json    { "<identity>": <addedAt ms> }   first time the hub ever saw that identity
 *   data/progress.json   { "<profile>": { "<identity>": { positionMs, watchedMs, updatedAt } } }
 *   data/profiles.json   optional runtime override of hub/profiles.json (merged by profile id)
 *
 * Writes are debounced (400 ms) and atomic: write <file>.tmp then rename, so a crash mid-write
 * cannot corrupt the library. Nothing here knows about providers, rows or rules.
 */

const fs = require('fs');
const path = require('path');

const FLUSH_MS = 400;

function readJson(file, fallback) {
  try {
    const raw = fs.readFileSync(file, 'utf8');
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : fallback;
  } catch (e) {
    if (e.code !== 'ENOENT') console.error(new Date().toISOString(), 'store read failed', file, e.message);
    return fallback;
  }
}

function writeJsonAtomic(file, value) {
  const tmp = file + '.tmp';
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(tmp, JSON.stringify(value, null, 1));
  fs.renameSync(tmp, file);
}

class Store {
  constructor(root) {
    this.root = root;
    this.dataDir = path.join(root, 'data');
    this.libraryFile = path.join(this.dataDir, 'library.json');
    this.progressFile = path.join(this.dataDir, 'progress.json');
    this.profilesSeedFile = path.join(root, 'profiles.json');
    this.profilesFile = path.join(this.dataDir, 'profiles.json');
    fs.mkdirSync(this.dataDir, { recursive: true });

    this.library = readJson(this.libraryFile, {}); // identity -> addedAt(ms)
    this.progress = readJson(this.progressFile, {}); // profile -> identity -> progress
    this._dirty = new Set();
    this._timer = null;
    this._profiles = null;
    this.writes = 0;
  }

  // ---- addedAt: first time the hub sees an identity -----------------------------------------------

  /** Record that the hub just saw `identity`; returns its (possibly new) addedAt. */
  touch(identity) {
    if (!identity) return null;
    const key = String(identity);
    if (this.library[key] == null) {
      this.library[key] = Date.now();
      this._markDirty('library');
    }
    return this.library[key];
  }

  /** Touch a batch of items and stamp item.addedAt in place. Returns the count first seen. */
  touchItems(items) {
    let fresh = 0;
    for (const it of items) {
      if (!it || !it.identity) continue;
      const before = this.library[String(it.identity)];
      const at = this.touch(it.identity);
      it.addedAt = at;
      if (before == null) fresh++;
    }
    return fresh;
  }

  addedAt(identity) {
    return this.library[String(identity)];
  }

  librarySize() {
    return Object.keys(this.library).length;
  }

  // ---- progress per profile -----------------------------------------------------------------------

  progressFor(profileId, identity) {
    const p = (this.progress[String(profileId)] || {})[String(identity)];
    return p ? { positionMs: p.positionMs || 0, watchedMs: p.watchedMs || 0 } : { positionMs: 0, watchedMs: 0 };
  }

  setProgress(profileId, identity, patch) {
    if (!profileId || !identity) return null;
    const pkey = String(profileId);
    const ikey = String(identity);
    this.progress[pkey] = this.progress[pkey] || {};
    const cur = this.progress[pkey][ikey] || { positionMs: 0, watchedMs: 0 };
    const next = {
      positionMs: Math.max(0, Number(patch.positionMs) || cur.positionMs || 0),
      watchedMs: Math.max(0, Number(patch.watchedMs) || cur.watchedMs || 0),
      updatedAt: Date.now(),
    };
    this.progress[pkey][ikey] = next;
    this.touch(ikey);
    this._markDirty('progress');
    return next;
  }

  /** Identities this profile has started but not finished, most recent first. */
  continueIdentities(profileId, limit = 20) {
    const p = this.progress[String(profileId)] || {};
    return Object.entries(p)
      .filter(([, v]) => (v.watchedMs > 0 || v.positionMs > 0))
      .sort((a, b) => (b[1].updatedAt || 0) - (a[1].updatedAt || 0))
      .slice(0, limit)
      .map(([identity]) => identity);
  }

  // ---- profiles -----------------------------------------------------------------------------------

  /** Seed profiles from hub/profiles.json, with data/profiles.json able to override by id. */
  profiles() {
    if (this._profiles) return this._profiles;
    const seed = readJson(this.profilesSeedFile, []);
    const list = Array.isArray(seed) ? seed : Array.isArray(seed.profiles) ? seed.profiles : [];
    const override = readJson(this.profilesFile, []);
    const olist = Array.isArray(override) ? override : Array.isArray(override.profiles) ? override.profiles : [];
    const merged = new Map();
    for (const p of list) if (p && p.id) merged.set(String(p.id), p);
    for (const p of olist) if (p && p.id) merged.set(String(p.id), { ...merged.get(String(p.id)), ...p });
    this._profiles = [...merged.values()];
    return this._profiles;
  }

  profile(id) {
    const list = this.profiles();
    return list.find((p) => String(p.id) === String(id)) || list[0] || { id: 'default', name: 'Default', kind: 'adult' };
  }

  reloadProfiles() {
    this._profiles = null;
    return this.profiles();
  }

  /**
   * Write a per-profile patch (prefs, rules, name…) into data/profiles.json, which is merged over
   * hub/profiles.json by id. This is how the app's Settings screen changes the hub's behaviour with
   * no code and no restart.
   */
  patchProfile(id, patch) {
    if (!id) return null;
    const seed = readJson(this.profilesSeedFile, []);
    const slist = Array.isArray(seed) ? seed : Array.isArray(seed.profiles) ? seed.profiles : [];
    const current = readJson(this.profilesFile, []);
    const clist = Array.isArray(current) ? current : Array.isArray(current.profiles) ? current.profiles : [];
    const base = slist.find((p) => String(p.id) === String(id)) || clist.find((p) => String(p.id) === String(id)) || { id };
    const entry = clist.find((p) => String(p.id) === String(id)) || {};
    const next = { ...entry, id: base.id, ...patch };
    const out = clist.filter((p) => String(p.id) !== String(id));
    out.push(next);
    try {
      writeJsonAtomic(this.profilesFile, out);
    } catch (e) {
      return { error: e.message };
    }
    this.reloadProfiles();
    return this.profile(id);
  }

  // ---- persistence --------------------------------------------------------------------------------

  _markDirty(what) {
    this._dirty.add(what);
    if (this._timer) return;
    this._timer = setTimeout(() => {
      this._timer = null;
      this.flush();
    }, FLUSH_MS);
    if (this._timer.unref) this._timer.unref();
  }

  flush() {
    if (!this._dirty.size) return;
    const what = new Set(this._dirty);
    this._dirty.clear();
    try {
      if (what.has('library')) writeJsonAtomic(this.libraryFile, this.library);
      if (what.has('progress')) writeJsonAtomic(this.progressFile, this.progress);
      this.writes++;
    } catch (e) {
      console.error(new Date().toISOString(), 'store flush failed:', e.message);
    }
  }

  stats() {
    return {
      library: this.librarySize(),
      profiles: this.profiles().length,
      progressProfiles: Object.keys(this.progress).length,
      writes: this.writes,
      dataDir: this.dataDir,
    };
  }
}

function create(root) {
  const store = new Store(root);
  const bye = () => {
    try {
      store.flush();
    } catch (e) {
      /* exit path */
    }
  };
  process.on('exit', bye);
  for (const sig of ['SIGINT', 'SIGTERM']) {
    process.on(sig, () => {
      bye();
      process.exit(0);
    });
  }
  return store;
}

module.exports = { create, Store };
