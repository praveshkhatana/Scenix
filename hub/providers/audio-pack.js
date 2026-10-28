'use strict';
/*
 * providers/audio-pack.js — an owner-configured pack of audio-only sources.
 *
 * The descriptors live in `settings.audio.sources`, not in code: `{ id, name, url, languages,
 * quality, type, headers }`. `url` is a template with the same placeholders the IGC pack uses.
 *
 * An audio source is a donor, not a picture: the play path can mux it with a higher-quality video
 * source when the video's own container does not carry the language the viewer asked for. Putting the
 * donor in its own addon keeps the source list honest and lets the owner switch the pack off without
 * touching the play code.
 */

const settings = require('../core/settings');
const media = require('../core/media');

const PROVIDER_ID = 'audio-pack';

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
  const pack = settings.get().audio || {};
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

function buildSources(item) {
  const out = [];
  for (const desc of descriptors()) {
    if (!matchesType(desc, item)) continue;
    const template = String(desc.url || '');
    if (!template) continue;
    const langs = Array.isArray(desc.languages) ? desc.languages : [];
    const list = langs.length ? langs : [''];
    for (const lang of list) {
      const url = expand(template, item, lang);
      const label = [String(desc.name || desc.id || 'Audio'), nameOf(lang || 'und')]
        .filter(Boolean).join(' \u00b7 ');
      out.push({
        provider: 'audio',
        _addon: PROVIDER_ID,
        label,
        kind: 'audio',
        quality: desc.quality || null,
        sizeBytes: null,
        risk: null,
        audioOnly: true,
        _langs: lang ? [lang] : [],
        audioLangs: lang ? [lang] : [],
        audioTracks: [{
          id: 'a:0',
          language: lang ? media.langOf(lang) : 'und',
          codec: null,
          default: true,
        }],
        _url: url,
        _headers: Object.assign({}, desc.headers || {}),
        _descriptor: desc.id,
      });
    }
  }
  return out;
}

module.exports = {
  id: PROVIDER_ID,
  name: 'Audio-only pack',
  native: true,
  kind: 'audio',
  priority: 70,

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
      kind: kindOf(source._url) === 'hls' ? 'hls' : 'http',
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
    return { id: PROVIDER_ID, name: 'Audio-only pack', native: true, configured: descriptors().length };
  },
};
