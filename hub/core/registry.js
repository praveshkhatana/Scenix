'use strict';
/*
 * core/registry.js — the hub's plugin loader.
 *
 * Directory scan ONLY. To add a feature you add one file:
 *   hub/providers/<name>.js   content source   (provider contract)
 *   hub/rows/<name>.js        one home row     (row contract)
 *   hub/rules/<name>.js       one filter       (rule contract)
 * This file is never edited to add a plugin, and neither is index.js.
 *
 * A plugin that is broken (bad syntax, missing fields, duplicate id) is logged and skipped:
 * one bad file can never crash the hub. Failures are surfaced through /healthz.
 */

const fs = require('fs');
const path = require('path');

const DIRS = [
  { dir: 'providers', kind: 'provider' },
  { dir: 'rows', kind: 'row' },
  { dir: 'rules', kind: 'rule' },
];

const SCOPES = ['all', 'adult', 'kids'];
const log = (...a) => console.log(new Date().toISOString(), ...a);

const isFn = (o, k) => o && typeof o[k] === 'function';
const isStr = (o, k) => o && typeof o[k] === 'string' && o[k].length > 0;

// ---- validation (shape from design/plugin-architecture.md §2) ------------------------------------

function validateProvider(mod, file) {
  if (!isStr(mod, 'id')) return 'provider needs a string id';
  if (!isStr(mod, 'kind')) return 'provider needs a string kind';
  if (!('priority' in mod)) return 'provider needs a numeric priority';
  if (typeof mod.priority !== 'number') return 'provider priority must be a number';
  const methods = ['catalogs', 'catalog', 'meta', 'play', 'sources', 'subtitles'].filter((m) => isFn(mod, m));
  if (!methods.length) return 'provider must implement at least one of catalogs/catalog/meta/play/sources/subtitles';
  if (mod.legacy !== undefined) {
    if (!mod.legacy || typeof mod.legacy !== 'object') return 'provider.legacy must be an object';
    const ok = ['manifestPart', 'catalog', 'meta', 'stream', 'ensure'].some((m) => isFn(mod.legacy, m));
    if (!ok) return 'provider.legacy must implement manifestPart/catalog/meta/stream';
    if (mod.legacy.idPrefixes && !Array.isArray(mod.legacy.idPrefixes))
      return 'provider.legacy.idPrefixes must be an array';
  }
  return null;
}

function validateRow(mod, file) {
  if (!isStr(mod, 'id')) return 'row needs a string id';
  if (!isStr(mod, 'title')) return 'row needs a string title';
  if (!isStr(mod, 'card')) return 'row needs a string card type';
  if (typeof mod.order !== 'number') return 'row needs a numeric order';
  if (mod.scope !== undefined && !SCOPES.includes(mod.scope))
    return 'row scope must be one of ' + SCOPES.join('|');
  // Optional: how many tiles the row wants in the END. build(ctx) may return more candidates; the
  // hub trims AFTER the rules have run, so a rule that drops an item does not shrink the row.
  if (mod.max !== undefined && !(Number.isInteger(mod.max) && mod.max > 0))
    return 'row max must be a positive integer';
  if (!isFn(mod, 'build')) return 'row needs async build(ctx)';
  return null;
}

function validateRule(mod, file) {
  if (!isStr(mod, 'id')) return 'rule needs a string id';
  if (typeof mod.order !== 'number') return 'rule needs a numeric order';
  if (!isFn(mod, 'apply')) return 'rule needs async apply(item, ctx)';
  return null;
}

const VALIDATORS = { provider: validateProvider, row: validateRow, rule: validateRule };

// ---- scan ---------------------------------------------------------------------------------------

function scanKind(kind, dir, failures) {
  let names = [];
  try {
    names = fs.readdirSync(dir).filter((n) => n.endsWith('.js')).sort();
  } catch (e) {
    if (e.code !== 'ENOENT') failures.push({ kind, file: path.basename(dir), error: e.message });
    return [];
  }
  const out = [];
  for (const name of names) {
    const file = path.join(dir, name);
    let mod;
    try {
      mod = require(file);
    } catch (e) {
      failures.push({ kind, file: name, error: 'require failed: ' + (e.message || e) });
      log('PLUGIN LOAD FAIL', kind, name, e.message);
      continue;
    }
    const problem = mod && typeof mod === 'object' ? VALIDATORS[kind](mod, name) : 'module.exports must be an object';
    if (problem) {
      failures.push({ kind, file: name, error: problem });
      log('PLUGIN INVALID', kind, name, '-', problem);
      continue;
    }
    out.push({
      kind,
      file: name,
      id: mod.id,
      order: typeof mod.order === 'number' ? mod.order : (typeof mod.priority === 'number' ? mod.priority : 100),
      mod,
    });
  }
  return out;
}

/**
 * Load every plugin under <root>/{providers,rows,rules}.
 * Returns { providers, rows, rules, byId, priorities, failures } — sorted, validated, deduped by id.
 */
function load(root) {
  const failures = [];
  const found = { provider: [], row: [], rule: [] };
  for (const { dir, kind } of DIRS) {
    for (const entry of scanKind(kind, path.join(root, dir), failures)) {
      const dup = found[kind].find((x) => x.id === entry.id);
      if (dup) {
        failures.push({ kind, file: entry.file, error: 'duplicate id "' + entry.id + '" (already from ' + dup.file + ')' });
        log('PLUGIN SKIP', kind, entry.file, '- duplicate id', entry.id);
        continue;
      }
      found[kind].push(entry);
    }
  }

  const byId = {};
  const sortEntry = (list) => list.slice().sort((a, b) => a.order - b.order || a.id.localeCompare(b.id));
  const orderOf = (mod) => (typeof mod.priority === 'number' ? mod.priority : 100);

  const providers = sortEntry(found.provider).map((e) => e.mod);
  const rows = sortEntry(found.row).map((e) => e.mod);
  const rules = sortEntry(found.rule).map((e) => e.mod);

  for (const p of providers) byId[p.id] = p;
  for (const r of rows) byId['row:' + r.id] = r;
  for (const r of rules) byId['rule:' + r.id] = r;

  const priorities = {};
  for (const p of providers) priorities[p.id] = p.priority;

  /**
   * Hot-load / hot-remove. core/addons.js installs an installed addon without a restart; the sort and
   * the id index are rebuilt in place, so every reader of `registry.providers` sees it immediately.
   */
  function add(mod) {
    if (!mod || typeof mod.id !== 'string') return 'addon needs a string id';
    if (byId[mod.id]) return 'duplicate id "' + mod.id + '"';
    const problem = validateProvider(mod, mod.id);
    if (problem) return problem;
    providers.push(mod);
    providers.sort((a, b) => orderOf(a) - orderOf(b) || String(a.id).localeCompare(String(b.id)));
    byId[mod.id] = mod;
    priorities[mod.id] = mod.priority;
    return null;
  }

  function remove(id) {
    const at = providers.findIndex((p) => p.id === id);
    if (at < 0) return false;
    providers.splice(at, 1);
    delete byId[id];
    delete priorities[id];
    return true;
  }

  return {
    providers,
    rows,
    rules,
    byId,
    priorities,
    failures,
    add,
    remove,
    stats: {
      providers: providers.map((p) => p.id),
      rows: rows.map((r) => ({ id: r.id, order: r.order, scope: r.scope || 'all' })),
      rules: rules.map((r) => ({ id: r.id, order: r.order })),
    },
  };
}

module.exports = { load, SCOPES };
