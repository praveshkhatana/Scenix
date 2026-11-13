'use strict';
/*
 * Offline browser preview is deliberately a different launch lane from a household Hub.  It exists
 * for inspecting the web shell on this machine, not for pairing a TV, mounting add-ons or playing a
 * title.  Prepare it before any core module is required: most cores choose their data file at import
 * time, so changing the environment later would be too late to keep a real household isolated.
 */

const fs = require('fs');
const os = require('os');
const path = require('path');

const LOOPBACK_BINDS = new Set(['127.0.0.1', '::1']);
const TEMP_PREFIX = 'scenix-offline-preview-';

function enabled(env = process.env) {
  return String(env.OFFLINE_PREVIEW || '').trim() === '1';
}

function isLoopbackBind(value) {
  return LOOPBACK_BINDS.has(String(value || '').trim());
}

function requireFixture(file, label) {
  if (!fs.existsSync(file)) throw new Error('offline preview fixture is missing: ' + label);
  return file;
}

function prepare(root, env = process.env) {
  const active = enabled(env);
  const bind = String(env.HUB_BIND || '0.0.0.0').trim() || '0.0.0.0';
  if (!active) return { enabled: false, bind, dataDir: null, fixtureDir: null };

  if (!isLoopbackBind(bind)) {
    throw new Error('OFFLINE_PREVIEW=1 requires HUB_BIND=127.0.0.1 or HUB_BIND=::1');
  }

  const fixtureDir = path.join(root, 'fixtures', 'offline-preview');
  const addonsFixture = requireFixture(path.join(fixtureDir, 'addons.json'), 'addons.json');
  const profilesFixture = requireFixture(path.join(fixtureDir, 'profiles.json'), 'profiles.json');
  const mediaDir = requireFixture(path.join(fixtureDir, 'media'), 'media/');
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), TEMP_PREFIX));

  // A caller's path overrides may name a real household's state.  Preview owns this new directory
  // instead.  Keep the allow-list short and explicit so a new core cannot accidentally inherit state.
  for (const key of Object.keys(env)) {
    if (/(?:_FILE|_DIR)$/.test(key) && key !== 'TMPDIR') delete env[key];
  }
  for (const key of [
    'SEED_ADDON_URLS', 'TLS_CERT', 'TLS_KEY', 'HUB_TOKEN', 'TORR_URL', 'TORR_PUBLIC_URL',
    'PLAYLIST_URL', 'XMLTV_URL', 'DEBRID_TOKEN', 'DEBRID_PROVIDER',
  ]) delete env[key];

  env.DATA_DIR = dataDir;
  env.DEFAULT_ADDONS_FILE = addonsFixture;
  env.PROFILES_SEED_FILE = profilesFixture;
  env.MEDIA_DIR = mediaDir;
  env.ANNOUNCE = '0';
  env.GATEWAY = 'off';
  env.PROBE_ENABLED = '0';
  env.DEBRID_ENABLED = '0';
  env.HUB_ID = 'scenix-offline-preview';

  // The fixtures are read-only source material.  The process sees copies in its fresh data directory,
  // so no preview write can change a tracked file or an owner's configured add-on list.
  fs.copyFileSync(addonsFixture, path.join(dataDir, 'addons.json'));

  let cleaned = false;
  const cleanup = () => {
    if (cleaned) return;
    cleaned = true;
    // This exact directory was made by mkdtemp above; never recurse over a caller-provided path.
    if (path.basename(dataDir).startsWith(TEMP_PREFIX)) {
      try { fs.rmSync(dataDir, { recursive: true, force: true }); } catch (e) { /* exit cleanup */ }
    }
  };
  process.once('exit', cleanup);

  return { enabled: true, bind, dataDir, fixtureDir, profilesSeedFile: profilesFixture };
}

module.exports = { prepare, enabled, isLoopbackBind, TEMP_PREFIX };
