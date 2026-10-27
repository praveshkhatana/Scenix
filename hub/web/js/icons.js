/**
 * js/icons.js — every glyph in the interface, inline.
 *
 * There are no emoji anywhere in Scenix and no icon font to download: each icon is a small SVG path
 * drawn with currentColor, so it inherits the theme token it sits in and scales with the TV like text
 * does. A plugin that needs a new glyph adds one entry to ICONS; nothing else changes.
 */

const ICONS = {
  play: '<path d="M7 4.5v15l13-7.5-13-7.5z" fill="currentColor" stroke="none"/>',
  pause: '<path d="M8 4.5v15M16 4.5v15" stroke-width="3"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.6h.01"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M16.2 16.2 21 21"/>',
  gear: '<circle cx="12" cy="12" r="3.2"/><path d="M19.4 15a1.6 1.6 0 0 0 .32 1.77l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.6 1.6 0 0 0-1.77-.32 1.6 1.6 0 0 0-.97 1.47V21a2 2 0 1 1-4 0v-.11a1.6 1.6 0 0 0-1.05-1.46 1.6 1.6 0 0 0-1.77.32l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.6 1.6 0 0 0 .32-1.77 1.6 1.6 0 0 0-1.47-.97H3a2 2 0 1 1 0-4h.11a1.6 1.6 0 0 0 1.46-1.05 1.6 1.6 0 0 0-.32-1.77l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.6 1.6 0 0 0 1.77.32H9a1.6 1.6 0 0 0 .97-1.47V3a2 2 0 1 1 4 0v.11a1.6 1.6 0 0 0 .97 1.47 1.6 1.6 0 0 0 1.77-.32l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.6 1.6 0 0 0-.32 1.77V9a1.6 1.6 0 0 0 1.47.97H21a2 2 0 1 1 0 4h-.11a1.6 1.6 0 0 0-1.49.97z"/>',
  cc: '<rect x="2.5" y="5.5" width="19" height="13" rx="3"/><path d="M10 10.2a2.6 2.6 0 1 0 0 3.6M17.5 10.2a2.6 2.6 0 1 0 0 3.6"/>',
  audio: '<path d="M4 10v4M8 7v10M12 4.5v15M16 8v8M20 11v2"/>',
  back: '<path d="M15 5 8 12l7 7"/>',
  chevRight: '<path d="M9 5l7 7-7 7"/>',
  chevUp: '<path d="M5 15l7-7 7 7"/>',
  chevDown: '<path d="M5 9l7 7 7-7"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  check: '<path d="M4.5 12.5 9.5 18 20 6.5"/>',
  minus: '<path d="M5 12h14"/>',
  rew: '<path d="M11 6 4 12l7 6z" fill="currentColor" stroke="none"/><path d="M20 6l-7 6 7 6z" fill="currentColor" stroke="none"/>',
  fwd: '<path d="M4 6l7 6-7 6z" fill="currentColor" stroke="none"/><path d="M13 6l7 6-7 6z" fill="currentColor" stroke="none"/>',
  next: '<path d="M5 5l9 7-9 7z" fill="currentColor" stroke="none"/><path d="M18 5v14"/>',
  speed: '<path d="M12 20a8 8 0 1 1 8-8"/><path d="M12 12l4.5-2.5"/>',
  sliders: '<path d="M4 8h9M17 8h3M4 16h3M11 16h9"/><circle cx="15" cy="8" r="2"/><circle cx="9" cy="16" r="2"/>',
  lock: '<rect x="4.5" y="10.5" width="15" height="9.5" rx="2.2"/><path d="M8 10.5V7a4 4 0 0 1 8 0v3.5"/>',
  kids: '<circle cx="12" cy="8.5" r="4.2"/><path d="M4.5 20a7.5 7.5 0 0 1 15 0"/>',
  refresh: '<path d="M20 11a8 8 0 1 0-2.3 6.3"/><path d="M20 4.5V11h-6.2"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  list: '<path d="M4 7h16M4 12h16M4 17h10"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3.5 9h17M3.5 15h17M12 3c2.6 3 2.6 15 0 18-2.6-3-2.6-15 0-18z"/>',
  external: '<path d="M14 4h6v6M20 4l-9 9"/><path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4"/>',
  warning: '<path d="M12 4 2.8 20h18.4z"/><path d="M12 10v4.5M12 17.4h.01"/>',
  volume: '<path d="M4 9.5h3.5L12 5.5v13L7.5 14.5H4z"/><path d="M16 9.2a4 4 0 0 1 0 5.6"/>',
  mute: '<path d="M4 9.5h3.5L12 5.5v13L7.5 14.5H4z"/><path d="M16.5 9.5 21 14M21 9.5 16.5 14"/>',
  tv: '<rect x="2.5" y="5.5" width="19" height="12.5" rx="2.5"/><path d="M8 21h8"/>',
  home: '<path d="M4 11 12 4l8 7v8a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19z"/>',
  star: '<path d="m12 4 2.4 5 5.4.7-3.9 3.7.9 5.4-4.8-2.6-4.8 2.6.9-5.4L4.2 9.7 9.6 9z"/>',
  trash: '<path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 20.5a8 8 0 0 1 16 0"/>',
  wifi: '<path d="M4.5 10.5a10 10 0 0 1 15 0M8 14a5.5 5.5 0 0 1 8 0"/><path d="M12 18.5h.01"/>',
  cast: '<path d="M3 18.5a2.5 2.5 0 0 1 2.5 2.5M3 14a7 7 0 0 1 7 7M3 9.5A11.5 11.5 0 0 1 14.5 21"/><path d="M21 21V6a2 2 0 0 0-2-2H9"/>',
};

/** Build one icon as a real element (never innerHTML for text the hub sent us). */
export function icon(name, opts = {}) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', `ico${opts.solid ? ' solid' : ''}${opts.cls ? ' ' + opts.cls : ''}`);
  const inner = ICONS[name];
  if (!inner) {
    svg.innerHTML = '';
    return svg;
  }
  // The paths are ours (the table above), not data from the network, so innerHTML is safe here.
  svg.innerHTML = inner;
  return svg;
}

export function iconHtml(name) {
  const svg = icon(name);
  return svg.outerHTML;
}

export function hasIcon(name) {
  return !!ICONS[name];
}
