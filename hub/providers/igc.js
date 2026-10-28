'use strict';
/*
 * providers/igc.js — an owner-configured IGC source pack.
 *
 * The descriptors live in `settings.igc.sources`, not in this file: `{ id, name, url, languages,
 * quality, type, headers }`. `url` is a template; the addon substitutes `{title}`, `{year}`, `{type}`,
 * `{season}`, `{episode}`, `{identity}` and `{lang}`. That keeps the host, the naming and the language
 * list in the owner's settings, and keeps the addon code free of any hardcoded source.
 *
 * The addon is streams-only: it contributes `sources[]` to titles published by another addon. It does
 * not publish a catalog, so it never invents a shelf it cannot back with a real URL.
 */

const settings = require('../core/settings');

const PROVIDER_ID = 'igc';

const LANG_NAMES = {
  hin: 'Hindi', eng: 'English', pan: 'Punjabi', tam: 'Tamil', tel: 'Telugu', mal: 'Malayalam',
  kan: 'Kannada', ben: 'Bengali', mar: 'Marathi', urd: 'Urdu', guj: 'Gujarati', rus: 'Russian',
  spa: 'Spanish', por: 'Portuguese', fre: 'French', ger: 'German', ita: 'Italian', jpn: 'Japanese',
  kor: 'Korean', chi: 'Chinese', ara: 'Arabic', tur: 'Turkish', per: 'Persian', pol: 'Polish',
  ukr: 'Ukrainian', dut: 'Dutch', swe: 'Swedish', nor: 'Norwegian', dan: 'Danish', fin: 'Finnish',
  cze: 'Czech', hun: 'Hungarian', rum: 'Romanian', bul: 'Bulgarian', srp: 'Serbian',
  hrv: 'Croatian', slo: 'Slovak', slv: 'Slovenian', est: 'Estonian', lav: 'Latvian',
  lit: 'Lithuanian', gre: 'Greek', ind: 'Indonesian', may: 'Malay', tha: 'Thai',
  vie: 'Vietnamese', heb: 'Hebrew',
};

function nameOf(code) {
  return LANG_NAMES[code] || code;
}

function descriptors() {
  const pack = settings.get().igc || {};
  return (Array.isArray(pack.sources) ? pack.sources : []).filter((d) => d && d.enabled !== false);
}

function matchesType(desc, item) {
  const t = String(desc.type || '').toLowerCase();
  if (!t || t === 'any') return true;
  const itemType = item.type === 'series' ? 'series' : String(item.type || '');
  return t === itemType || (t === 'channel' && itemType === 'channel');
}

function streamParts(item) {
  const m = /:(\d+):(\d+)$/.exec(String(item.streamId || ''));
  return { season: m ? Number(m[1]) : null, episode: m ? Number(m[2]) : null };
}

function expand(template, item, lang) {
  const parts = streamParts(item);
  const vars = {
    title: item.title || '',
    year: item.year == null ? '' : item.year,
    type: item.type === 'series' ? 'series' : item.type || '',
    season: parts.season == null ? '' : parts.season,
    episode: parts.episode == null ? '' : parts.episode,
    identity: item.identity || '',
    lang: lang || '',
  };
  return String(template).replace(/\{([a-z]+)\}/gi, (m, key) => {
    const v = vars[key.toLowerCase()];
    return v == null ? '' : encodeURIComponent(String(v));
  });
}

function kindOf(url) {
  if (/\.m3u8([?#]|$)/i.test(url)) return 'hls';
  if (/\.mpd([?#]|$)/i.test(url)) return 'dash';
  return 'http';
}

function sourceLabel(desc, langs) {
  const bits = [String(desc.name || desc.id || 'Source'), String(desc.quality || '')];
  if (langs.length) bits.push(langs.map(nameOf).join('/'));
  return bits.filter(Boolean).join(' \u00b7 ');
}

function buildSources(item) {
  const out = [];
  for (const desc of descriptors()) {
    if (!matchesType(desc, item)) continue;
    const langs = Array.isArray(desc.languages) ? desc.languages : [];
    const template = String(desc.url || '');
    if (!template) continue;
    if (langs.length && /\{lang\}/i.test(template)) {
      for (const lang of langs) {
        const url = expand(template, item, lang);
        out.push({
          provider: 'igc',
          _addon: PROVIDER_ID,
          label: sourceLabel(desc, [lang]),
          kind: kindOf(url),
          quality: desc.quality || null,
          sizeBytes: null,
          risk: null,
          audioOnly: false,
          _langs: [lang],
          audioLangs: [lang],
          _url: url,
          _headers: Object.assign({}, desc.headers || {}),
          _descriptor: desc.id,
          audioTracks: [],
        });
      }
    } else {
      const url = expand(template, item, langs[0] || '');
      out.push({
        provider: 'igc',
        _addon: PROVIDER_ID,
        label: sourceLabel(desc, langs),
        kind: kindOf(url),
        quality: desc.quality || null,
        sizeBytes: null,
        risk: null,
        audioOnly: false,
        _langs: langs,
        audioLangs: langs,
        _url: url,
        _headers: Object.assign({}, desc.headers || {}),
        _descriptor: desc.id,
        audioTracks: [],
      });
    }
  }
  return out;
}

module.exports = {
  id: PROVIDER_ID,
  name: 'IGC source pack',
  native: true,
  kind: 'video',
  priority: 60,

  async catalogs() {
    return [];
  },

  async catalog() {
    return [];
  },

  async sources(item) {
    return buildSources(item);
  },

  async play(identity, source) {
    if (!source || !source._url) return null;
    return {
      url: source._url,
      headers: Object.assign({}, source._headers || {}),
      kind: source.kind === 'hls' ? 'hls' : source.kind === 'dash' ? 'dash' : 'http',
      expiresAt: null,
    };
  },

  async sourceTarget(identity, source) {
    if (!source || !source._url) return null;
    return { url: source._url, headers: Object.assign({}, source._headers || {}) };
  },

  async subtitles() {
    return [];
  },

  stats() {
    return { id: PROVIDER_ID, name: 'IGC source pack', native: true, configured: descriptors().length };
  },
};
