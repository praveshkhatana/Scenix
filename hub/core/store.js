'use strict';
/*
 * core/store.js — the hub's only memory across restarts. JSON files under hub/data/.
 *
 *   data/library.json    { "<identity>": <addedAt ms> }   first time the hub ever saw that identity
 *   data/progress.json   { "<profile>": { "<identity>": { positionMs, watchedMs, updatedAt } } }
 *   data/profiles.json   optional runtime override of hub/profiles.json (merged by profile id)
 *   hub/profiles.json    the household itself - NOT in the repo (real names), so hub/profiles.example.json
 *                        (generic ids) ships instead and is the fallback when the real one is absent
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
    this.profilesExampleFile = path.join(root, 'profiles.example.json');
    this.profilesFile = path.join(this.dataDir, 'profiles.json');
    fs.mkdirSync(this.dataDir, { recursive: true });

    this.library = readJson(this.libraryFile, {}); // identity -> addedAt(ms)
    this.progress = readJson(this.progressFile, {}); // profile -> identity -> progress
    this._dirty = new Set();
    this._timer = null;
    this._profiles = null;
    this._seedLogged = false;
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
    return p
      ? { positionMs: p.positionMs || 0, watchedMs: p.watchedMs || 0, episode: p.episode || null }
      : { positionMs: 0, watchedMs: 0, episode: null };
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
      // Which episode of a series this person stopped on, so the next Play press resumes that episode
      // instead of restarting S01E01 three seasons in.
      episode: patch.episode && Number(patch.episode.season) > 0 && Number(patch.episode.episode) > 0
        ? { season: Number(patch.episode.season), episode: Number(patch.episode.episode),
          title: patch.episode.title || null }
        : cur.episode || null,
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

  /**
   * The seed list: hub/profiles.json if the household has one, otherwise hub/profiles.example.json.
   *
   * profiles.json holds real names, so it is not in the repo and deploy-hub.sh never rsyncs it — the
   * file lives only on the server that this household's TV points at. A fresh checkout therefore has
   * just the example (generic ids: adult1, kid1…), which is enough for the hub to boot and for the app
   * to be developed against. One log line says which of the two is in use, because "why is my profile
   * gone" is otherwise a confusing first boot.
   */
  seedProfiles() {
    const real = readJson(this.profilesSeedFile, null);
    const from = real ? this.profilesSeedFile : this.profilesExampleFile;
    const seed = real != null ? real : readJson(this.profilesExampleFile, []);
    if (!this._seedLogged) {
      this._seedLogged = true;
      if (real == null) {
        console.log(new Date().toISOString(), 'PROFILES',
          'no profiles.json next to the hub - using', path.basename(this.profilesExampleFile),
          '(generic ids). Copy it to profiles.json and put this household in it.');
      }
    }
    const list = Array.isArray(seed) ? seed : Array.isArray(seed && seed.profiles) ? seed.profiles : [];
    return { list, file: from };
  }

  /** Seed profiles from hub/profiles.json, with data/profiles.json able to override by id. */
  profiles() {
    if (this._profiles) return this._profiles;
    const list = this.seedProfiles().list;
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
   * Write the whole merged list. This is the only way a profile that came from the seed file can be
   * removed: `data/profiles.json` overrides the seed by id, so deleting a line there would leave the seed
   * putting it back on the next boot.
   */
  setProfiles(list) {
    writeJsonAtomic(this.profilesFile, list);
    this._profiles = null;
    return list;
  }

  /**
   * Create a profile. Shape and defaults live here rather than in a screen, because a profile is a
   * statement about what this backend may show, not about what one client drew. The last profile in a
   * household cannot be removed: with no profile there is no home screen, and a box that boots to a blank
   * page is worse than a box that keeps one adult.
   */
  addProfile(spec = {}) {
    const name = String(spec.name || '').trim();
    if (!name) return { error: 'a profile needs a name' };
    const id = String(spec.id || '').trim()
      || name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'p';
    const list = this.profiles();
    if (list.some((x) => String(x.id) === id)) return { error: 'that profile already exists', id };
    const next = [...list, {
      id,
      name,
      kind: spec.kind === 'kids' ? 'kids' : 'adult',
      pinRequired: Boolean(spec.pinRequired),
      ...(spec.age != null && Number.isFinite(Number(spec.age)) ? { age: Number(spec.age) } : {}),
      ...(Array.isArray(spec.languages) && spec.languages.length ? { languages: spec.languages } : {}),
    }];
    this.setProfiles(next);
    return { profile: this.profile(id) };
  }

  removeProfile(id) {
    const list = this.profiles();
    if (list.length <= 1) return { error: 'the last profile cannot be removed' };
    const next = list.filter((x) => String(x.id) !== String(id));
    if (next.length === list.length) return { error: 'no such profile', id };
    this.setProfiles(next);
    return { removed: String(id) };
  }

  /**
   * Write a per-profile patch (prefs, rules, name…) into data/profiles.json, which is merged over
   * hub/profiles.json by id. This is how the app's Settings screen changes the hub's behaviour with
   * no code and no restart.
   */
  patchProfile(id, patch) {
    if (!id) return null;
    const slist = this.seedProfiles().list;
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
      profilesSeed: path.basename(this.seedProfiles().file), // profiles.json, or the example on a fresh box
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
