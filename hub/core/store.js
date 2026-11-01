'use strict';
/*
 * core/store.js — the hub's only memory across restarts. JSON files under hub/data/.
 *
 *   data/library.json    { "<identity>": <addedAt ms> }   first time the hub ever saw that identity
 *   data/progress.json   { "<profile>": { "<identity>": { positionMs, watchedMs, updatedAt, dismissedAt? } } }
 *   data/list.json       { "<profile>": ["<identity>", …] } that profile's My List, newest first
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
    this.listFile = path.join(this.dataDir, 'list.json');
    this.profilesSeedFile = path.join(root, 'profiles.json');
    this.profilesExampleFile = path.join(root, 'profiles.example.json');
    this.profilesFile = path.join(this.dataDir, 'profiles.json');
    fs.mkdirSync(this.dataDir, { recursive: true });

    this.library = readJson(this.libraryFile, {}); // identity -> addedAt(ms)
    this.progress = readJson(this.progressFile, {}); // profile -> identity -> progress
    this.list = readJson(this.listFile, {}); // profile -> [identity, …] newest first
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
    // `watchedMs` is a running total (both clients send their own max-position), but `watchMs` is an
    // ADDITIVE delta: a live channel has no playhead to carry, so the only honest record of watching
    // is seconds accumulated between heartbeats. Channels therefore reach Continue Watching and the
    // last-watched channel with the same mechanism as a film, instead of never being recorded at all.
    const delta = Math.max(0, Math.min(10 * 60e3, Number(patch.watchMs) || 0));
    const next = {
      positionMs: Math.max(0, Number(patch.positionMs) || cur.positionMs || 0),
      watchedMs: Math.max(0, Number(patch.watchedMs) || cur.watchedMs || 0) + delta,
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
      // A tile the viewer dismissed from Continue watching stays out — but only until they press
      // the title again: setProgress clears dismissedAt, so resuming re-arms the row, position and
      // all. That is the Netflix semantic: "get this off my home", not "erase that I ever watched it".
      .filter(([, v]) => (v.watchedMs > 0 || v.positionMs > 0) && !v.dismissedAt)
      .sort((a, b) => (b[1].updatedAt || 0) - (a[1].updatedAt || 0))
      .slice(0, limit)
      .map(([identity]) => identity);
  }

  /**
   * "Remove from Continue watching": keep the position (they may press the title tomorrow), stop
   * the tile from standing on the home page. Re-arm happens by watching: any real progress post
   * clears the flag, exactly like the shelf a Netflix title hops back onto after one more episode.
   */
  dismissProgress(profileId, identity) {
    const pkey = String(profileId); const ikey = String(identity);
    const cur = (this.progress[pkey] || {})[ikey];
    if (!cur) return false;
    cur.dismissedAt = Date.now();
    this._markDirty('progress');
    return true;
  }

  // ---- My List per profile ------------------------------------------------------------------------
  //
  // On the hub, not on the screen: a list you build in the living room is a lie if the bedroom TV
  // and the browser say it is empty. The array is newest-first so the row reads like the shelf a
  // person just stocked.

  myListIds(profileId, limit = 200) {
    const ids = Array.isArray(this.list[String(profileId)]) ? this.list[String(profileId)] : [];
    return ids.slice(0, limit);
  }

  inList(profileId, identity) {
    const ids = Array.isArray(this.list[String(profileId)]) ? this.list[String(profileId)] : [];
    return ids.includes(String(identity));
  }

  /** Put (or keep) a title in the list. Returns whether this call changed anything. */
  listAdd(profileId, identity) {
    if (!identity) return false;
    const pkey = String(profileId); const ikey = String(identity);
    const ids = Array.isArray(this.list[pkey]) ? this.list[pkey] : (this.list[pkey] = []);
    if (ids.includes(ikey)) return false;
    ids.unshift(ikey);
    if (ids.length > 1000) this.list[String(profileId)] = ids.slice(0, 1000);
    this._markDirty('list');
    return true;
  }

  listRemove(profileId, identity) {
    const pkey = String(profileId);
    const ids = Array.isArray(this.list[pkey]) ? this.list[pkey] : [];
    const at = ids.indexOf(String(identity));
    if (at < 0) return false;
    ids.splice(at, 1);
    this._markDirty('list');
    return true;
  }

  /**
   * Rewind a title to "never started": the Resume button becomes Play again, the row tile's bar
   * empties, and a dismissed tile re-arms if it had been hidden. Watched time for the record is
   * zeroed too — starting over means the record starts over.
   */
  resetProgress(profileId, identity) {
    const pkey = String(profileId); const ikey = String(identity);
    const cur = (this.progress[pkey] || {})[ikey];
    if (!cur) return null;
    this.progress[pkey][ikey] = {
      positionMs: 0, watchedMs: 0, updatedAt: Date.now(), episode: cur.episode || null,
    };
    this._markDirty('progress');
    return this.progress[pkey][ikey];
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
  /**
   * A profile id is a handle, never a label. Slugifying the name put one household's names into every
   * data file on somebody else's box, and it meant that renaming a child orphaned that child's watch
   * history, because the history was filed under the old spelling. So ids are handed out as numbers, in
   * order, and the name stays free to change for the rest of the profile's life.
   */
  nextProfileId(list) {
    let top = 1000; // four digits and up, so an id never reads as an age, a year or a channel number
    for (const p of list || []) {
      const n = Number(String((p && p.id) || ''));
      if (Number.isFinite(n) && n >= top) top = Math.floor(n) + 1;
    }
    return String(top);
  }

  addProfile(spec = {}) {
    const name = String(spec.name || '').trim();
    if (!name) return { error: 'a profile needs a name' };
    const list = this.profiles();
    const id = String(spec.id || '').trim() || this.nextProfileId(list);
    if (list.some((x) => String(x.id) === id)) return { error: 'that profile already exists', id };
    const next = [...list, {
      id,
      name,
      kind: spec.kind === 'kids' ? 'kids' : 'adult',
      pinRequired: Boolean(spec.pinRequired),
      ...(spec.age != null && Number.isFinite(Number(spec.age)) ? { age: Number(spec.age) } : {}),
      ...(Array.isArray(spec.languages) && spec.languages.length ? { languages: spec.languages } : {}),
      ...(String(spec.avatar || '').trim() ? { avatar: String(spec.avatar).trim().slice(0, 400) } : {}),
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
      if (what.has('list')) writeJsonAtomic(this.listFile, this.list);
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
