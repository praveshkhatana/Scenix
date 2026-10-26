/**
 * js/format.js — turning the hub's numbers into words a person on a sofa can read.
 *
 * Nothing here knows about the API; it only formats values it is handed, so every screen and every card
 * plugin says the same thing about a duration, a file size or a language.
 */

const LANG_NAMES = {
  hin: 'Hindi', eng: 'English', pan: 'Punjabi', urd: 'Urdu', tam: 'Tamil', tel: 'Telugu',
  mal: 'Malayalam', kan: 'Kannada', ben: 'Bengali', mar: 'Marathi', guj: 'Gujarati',
  spa: 'Spanish', fra: 'French', ger: 'German', deu: 'German', ita: 'Italian', por: 'Portuguese',
  ron: 'Romanian', pol: 'Polish', rus: 'Russian', tur: 'Turkish', ara: 'Arabic', kor: 'Korean',
  jpn: 'Japanese', zho: 'Chinese', nld: 'Dutch', swe: 'Swedish', nor: 'Norwegian', dan: 'Danish',
  fin: 'Finnish', ces: 'Czech', ell: 'Greek', heb: 'Hebrew', hrv: 'Croatian', hun: 'Hungarian',
  ind: 'Indonesian', it: 'Italian', 'en': 'English', 'hi': 'Hindi', 'pa': 'Punjabi', 'ur': 'Urdu',
};

export function langName(code) {
  if (!code) return '';
  const c = String(code).toLowerCase();
  return LANG_NAMES[c] || String(code).toUpperCase();
}

export function langList(codes) {
  if (!codes || !codes.length) return '';
  return codes.map(langName).join(' · ');
}

/** mm:ss or h:mm:ss — the clock next to the seek bar. */
export function clock(ms) {
  if (ms === null || ms === undefined || !isFinite(ms) || ms < 0) return '--:--';
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n) => String(n).padStart(2, '0');
  return h ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`;
}

/** "1h 49m" / "34m" — the runtime chip on a card. */
export function runtime(ms) {
  if (!ms || !isFinite(ms)) return '';
  const min = Math.round(ms / 60000);
  if (min < 60) return `${min}m`;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return m ? `${h}h ${m}m` : `${h}h`;
}

/** "4.59 GB" — matching how the hub already spells sizes in its own labels. */
export function size(bytes) {
  if (!bytes || !isFinite(bytes)) return '';
  const gb = bytes / 1e9;
  if (gb >= 1) return `${gb.toFixed(2)} GB`;
  return `${Math.round(bytes / 1e6)} MB`;
}

/** "42m left" on a Continue Watching tile. */
export function timeLeft(positionMs, totalMs) {
  if (!totalMs || !isFinite(totalMs)) return '';
  const left = totalMs - (positionMs || 0);
  if (left <= 0) return 'Almost done';
  return `${Math.ceil(left / 60000)}m left`;
}

export function seasonLabel(season) {
  return `Season ${season}`;
}

export function episodeLabel(season, episode) {
  return `S${String(season).padStart(2, '0')} E${String(episode).padStart(2, '0')}`;
}

/** Quality words the hub uses in source labels, normalised for menu rows. */
export function qualityWord(q) {
  const s = String(q || '').toLowerCase();
  if (!s) return '';
  if (s === '4k' || s === '2160p' || s === 'uhd') return '4K';
  if (s === '1080p' || s === 'fullhd' || s === 'fhd') return '1080p';
  if (s === '720p') return '720p';
  if (s === '480p' || s === 'sd') return 'SD';
  return q;
}

/** "Today", "3 days ago" — for "Just added" style notes. */
export function since(ts) {
  if (!ts) return '';
  const d = Date.now() - Number(ts);
  const day = 86400000;
  if (d < day) return 'today';
  const days = Math.round(d / day);
  if (days < 31) return `${days} day${days === 1 ? '' : 's'} ago`;
  const months = Math.round(days / 30);
  return `${months} month${months === 1 ? '' : 's'} ago`;
}

export function initials(name) {
  const s = String(name || '?').trim();
  if (!s) return '?';
  const parts = s.split(/\s+/);
  return (parts.length > 1 ? parts[0][0] + parts[1][0] : s.slice(0, 1)).toUpperCase();
}
