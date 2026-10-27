/**
 * js/input.js — keys in, intents out.
 *
 * Android TV's WebView delivers the D-pad as ordinary `keydown` events (`Arrow*`, `Enter`), and some
 * firmwares fire one physical press twice. That exact bug made the old native player pause a film the
 * instant it opened, so a repeat of the same key inside ~300 ms is treated as one press here. A physical
 * keyboard auto-repeat (`event.repeat`) is dropped outright, and typing into a field is exempt from the
 * 300 ms rule — otherwise "tt" typed fast loses a letter.
 *
 * Every handled key calls `preventDefault()`: the browser otherwise scrolls a page sideways, and the TV
 * shell otherwise treats Enter as a click on whatever element it last had.
 */

import * as focus from '/ui/js/focus.js';

const REPEAT_WINDOW_MS = 300;

const NAV_KEYS = new Set([
  'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Enter', 'NumpadEnter',
  'Backspace', 'Escape', 'Delete', 'GoBack', 'BrowserBack', 'Back',
]);
const MEDIA_KEYS = new Set([
  'MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaTrackNext', 'MediaTrackPrevious',
]);

/* Some boxes deliver only a keyCode; map the Android TV keycodes onto the names the rest of the code uses. */
const KEYCODES = {
  4: 'GoBack', 8: 'Backspace', 9: 'Tab', 13: 'Enter', 27: 'Escape', 37: 'ArrowLeft', 38: 'ArrowUp',
  39: 'ArrowRight', 40: 'ArrowDown', 46: 'Delete',
  48: '0', 49: '1', 50: '2', 51: '3', 52: '4', 53: '5', 54: '6', 55: '7', 56: '8', 57: '9',
  85: 'MediaPlayPause', 89: 'MediaTrackNext', 90: 'MediaTrackPrevious',
  96: '0', 97: '1', 98: '2', 99: '3', 100: '4', 101: '5', 102: '6', 103: '7', 104: '8', 105: '9',
  160: 'MediaPlayPause', 161: 'MediaPause', 162: 'MediaPlay',
};

function normalize(e) {
  let k = e.key;
  if (!k || k === 'Unidentified') k = KEYCODES[e.keyCode] || KEYCODES[e.which] || '';
  if (k === 'Spacebar') k = ' ';
  if (k === 'Uppercase' || k === 'Lowercase') k = '';
  return k;
}

const stack = [];   // screen handlers, topmost first; return true to consume the key
let typingTarget = null;
let digitSink = null;   // a keypad on screen takes 0-9 from the keyboard and the remote alike
let lastKey = '';
let lastAt = 0;

export function pushHandler(h) { stack.push(h); }
export function popHandler(h) {
  const i = stack.indexOf(h);
  if (i >= 0) stack.splice(i, 1);
}
export function clearHandlers() { stack.length = 0; }

/** A visible keypad registers here so digits typed on a keyboard land in it, not in a search field. */
export function setDigitSink(sink) { digitSink = sink || null; }

/**
 * A screen that is taking text registers a target: { insert(ch), backspace(), clear(), commit() }.
 * Arrows still navigate the interface, so a remote can walk out of the field at any time.
 */
export function setTypingTarget(t) { typingTarget = t || null; }
/** Who currently owns typed characters, so a modal can give the keyboard back to whatever had it. */
export function currentTypingTarget() { return typingTarget; }
export function typingActive() { return !!typingTarget; }

export function initInput({ onBack, onLetterSearch } = {}) {
  document.addEventListener('keydown', (e) => {
    const key = normalize(e);
    if (!key) return;

    const printable = key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey;
    const isTyping = !!typingTarget && (printable || key === 'Backspace');

    // Auto-repeat from a keyboard: never a second press.
    if (e.repeat && !isTyping) { e.preventDefault(); return; }

    // The Sony remote fires one press twice. Ignore the echo.
    const now = Date.now();
    if (!isTyping && key === lastKey && now - lastAt < REPEAT_WINDOW_MS) {
      lastAt = now;
      e.preventDefault();
      return;
    }
    lastKey = key;
    lastAt = now;

    if (NAV_KEYS.has(key) || MEDIA_KEYS.has(key) || key === ' ') e.preventDefault();

    // 1. A screen handler owns its keys while it is on top (the player, a keypad, a menu).
    for (let i = stack.length - 1; i >= 0; i--) {
      if (stack[i](e, key)) return;
    }

    // 2. A keypad on screen takes digits first: the same digits arrive from a keyboard and from the
    //    remote's number buttons, and neither should leak into a search field behind it.
    if (digitSink && /^[0-9]$/.test(key)) {
      if (typingTarget && typingTarget !== digitSink) { /* the keypad still wins while it is open */ }
      e.preventDefault();
      digitSink(key);
      return;
    }

    // 3. Typing.
    if (typingTarget) {
      if (key === 'Backspace') { typingTarget.backspace(); return; }
      if (key === 'Escape') { typingTarget.commit && typingTarget.commit('escape'); if (onBack) onBack(); return; }
      if (key === 'Enter') { typingTarget.commit && typingTarget.commit('enter'); return; }
      if (printable) { typingTarget.insert(key); return; }
    }

    // 3. Navigation — the shared focus engine, so every screen gets the same D-pad behaviour.
    switch (key) {
      case 'ArrowLeft': focus.move('left'); return;
      case 'ArrowRight': focus.move('right'); return;
      case 'ArrowUp': focus.move('up'); return;
      case 'ArrowDown': focus.move('down'); return;
      case 'Enter':
      case 'NumpadEnter':
      case ' ':
        focus.activate();
        return;
      case 'Backspace':
      case 'Escape':
      case 'GoBack':
      case 'BrowserBack':
      case 'Back':
        if (onBack) onBack();
        return;
      default:
        if (printable && onLetterSearch) { onLetterSearch(key); return; }
    }
  }, true);
}
