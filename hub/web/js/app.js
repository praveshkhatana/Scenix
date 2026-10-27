/**
 * js/app.js — boot, the profile gate, and the screen router.
 *
 * Three rules hold this file together:
 *   · The hub decides what exists. Nothing here names a row, a title, or a piece of content.
 *   · One screen is mounted into #stage at a time. Modals, keypads and menus go into #layer, so they are
 *     always above the screen and always torn down with it.
 *   · Every screen module is loaded lazily. A TV with a real low-memory killer should not parse the
 *     settings page to draw the home page, and a route whose module fails must not take the page down.
 */

import { api, apiBase, setApiBase } from '/ui/js/api.js';
import { el, byId, clear, layer, toast } from '/ui/js/dom.js';
import * as focus from '/ui/js/focus.js';
import { initInput, pushHandler, popHandler, setDigitSink,
  setTypingTarget, currentTypingTarget } from '/ui/js/input.js';
import { load as loadTheme, token, px } from '/ui/js/theme.js';
import { initVideo, stopPreview } from '/ui/js/video.js';
import * as state from '/ui/js/state.js';
import { icon } from '/ui/js/icons.js';
import { entryField } from '/ui/js/keyboard.js';
import { initials } from '/ui/js/format.js';

/* Screens are plugins too: the route table is the only place that knows a screen exists. */
const SCREENS = {
  home:     () => import('/ui/js/screens/home.js'),
  detail:   () => import('/ui/js/screens/detail.js'),
  play:     () => import('/ui/js/screens/player.js'),
  search:   () => import('/ui/js/screens/search.js'),
  settings: () => import('/ui/js/screens/settings.js'),
  profiles: () => import('/ui/js/screens/profiles.js'),
  add:      () => import('/ui/js/screens/add.js'),
  manage:   () => import('/ui/js/screens/manage.js'),
};

/*
 * Choosing who is watching is one family: the row of faces, making a person, looking after the people there
 * already are. All three sit *before* the catalogue, so none of them carries the menu bar — a tab to a home
 * page nobody has entered yet is a promise the screen cannot keep — and none of them needs a viewer picked in
 * advance, which is the whole point of the row. BACK unwinds the route stack, so leaving Add goes back to
 * whichever screen asked for it: the picker, or Settings.
 */
const GATE_SCREENS = ['profiles', 'add', 'manage'];

const stage = () => byId('stage');
const bootEl = () => byId('boot');

/**
 * A TV has no devtools. Anything that escapes the try/catch of a screen lands in the page title (and in a
 * toast when the page is up), so a field problem is readable from the launcher or a screenshot.
 */
function surfaceError(where, err) {
  const msg = String((err && err.message) || err || 'unknown error');
  console.error(`[scenix] ${where}`, err);
  document.title = `Scenix — ${where}: ${msg}`;
  try { toast(`${where}: ${msg}`); } catch (e) { /* the layer may not exist yet */ }
}
window.addEventListener('unhandledrejection', (e) => surfaceError('unexpected', e.reason));

let nav = null;
let mounted = null;          // the screen node currently in #stage
let routeStack = [];         // the routes we have pushed, so BACK can unwind them
let renderToken = 0;         // a slow screen module must not overwrite a newer route
let modals = [];             // { node, onClose } stacked in #layer

/* ------------------------------------------------------------------ context */

/** Everything a screen is allowed to ask the shell for. Screens never touch #layer themselves. */
const ctx = {
  get profile() { return state.session.profile; },
  manifest() { return state.session.manifest; },
  navigate,
  back,
  route: () => routeStack[routeStack.length - 1] || { name: 'home' },
  toast,
  px,
  token,
  open(item) { if (item && item.identity) navigate('detail', item.identity); },
  play(item, opts) {
    if (!item || !item.identity) return;
    state.session.pendingPlay = Object.assign({ identity: item.identity, type: item.type }, opts || {});
    navigate('play', item.identity);
  },
  list(item) {
    if (!item) return;
    const on = state.toggleMyList(item.identity);
    toast(on ? `${item.title} is in your list` : `Removed from your list`);
    document.querySelectorAll(`.card[data-identity="${cssEscape(item.identity)}"]`).forEach((n) => {
      const plus = n.querySelector('.exp .r:nth-child(2) .ico');
      if (plus) plus.replaceWith(icon(on ? 'check' : 'plus'));
    });
  },
  mountModal,
  closeModals,
  askPin,
  /** Ask for a new PIN rather than checking one: resolves the digits, or null when the viewer backs out. */
  enterPin: (profile, reason = 'Choose a PIN') => askPin(profile, { reason, mode: 'set' }),
  enterText: (opts) => askText(opts),
  setProfile,
  switchProfile,
  reload() { const r = routeStack[routeStack.length - 1]; if (r) mount(r, false); },
};

function cssEscape(s) {
  return (window.CSS && CSS.escape) ? CSS.escape(String(s)) : String(s).replace(/["\\]/g, '\\$&');
}

/* ------------------------------------------------------------------ routing */

function parseHash() {
  const raw = String(location.hash || '').replace(/^#\/?/, '');
  const bits = raw.split('/');
  const name = bits[0] || 'home';
  const arg = bits.length > 1 ? decodeURIComponent(bits.slice(1).join('/')) : null;
  return SCREENS[name] ? { name, arg } : { name: 'home', arg: null };
}

function hashFor(route) {
  return `#/${route.name}${route.arg != null ? `/${encodeURIComponent(route.arg)}` : ''}`;
}

function navigate(name, arg) {
  const route = { name, arg: arg == null ? null : arg };
  const top = routeStack[routeStack.length - 1];
  if (top && top.name === route.name && top.arg === route.arg) return mount(route, false);
  routeStack.push(route);
  const want = hashFor(route);
  // The hash is a record of where we are, not the thing that moves us: hashchange ignores a route that
  // is already on top of the stack, so the mount has to be called here. It is returned so a caller can
  // wait for the new screen to exist — the profile switch measures the nav chip it is flying towards.
  if (location.hash !== want) location.hash = want;
  return mount(route, false);
}

function back() {
  // A modal or keypad owns BACK before the router does.
  if (modals.length) { closeTopModal(); return; }
  if (routeStack.length > 1) {
    routeStack.pop();
    const prev = routeStack[routeStack.length - 1];
    const want = hashFor(prev);
    if (location.hash !== want) location.hash = want;
    mount(prev, false);
    return;
  }
  // At the root: in a native shell BACK is the remote's way out of the app, and the shell has already
  // exhausted web.canGoBack() before it hands us the key.
  const s = state.shell();
  if (s && typeof s.exit === 'function') { try { s.exit(); return; } catch (e) { /* ignore */ } }
  toast('Press BACK again to leave Scenix');
}

window.addEventListener('hashchange', () => {
  const r = parseHash();
  const top = routeStack[routeStack.length - 1];
  if (top && top.name === r.name && top.arg === r.arg) return;
  const under = routeStack[routeStack.length - 2];
  if (under && under.name === r.name && under.arg === r.arg) routeStack.pop();
  else routeStack.push(r);
  mount(r, false);
});

async function mount(route, _pushed) {
  const token = ++renderToken;
  closeModals();
  focus.resetScopes();
  if (route.name !== 'home') stopPreview();

  const loader = SCREENS[route.name] || SCREENS.home;
  let node = null;
  try {
    const mod = await loader();
    const built = await mod.screen(ctx, route.arg, route);
    if (built && built.then) node = await built;      // tolerate an async screen
    node = built;
  } catch (e) {
    console.error('[scenix] screen failed', route, e);
    brokenScreens.add(route.name);
    hideNavItem(route.name);
    node = el('div', { class: 'screen' },
      el('div', { class: 'page-head' },
        el('h1', { text: 'This screen did not load' }),
        el('div', { class: 'sub', text: String(e && e.message || e) })),
      el('div', { class: 'bb-btns', style: 'padding: 0 var(--gutter)' },
        el('button', { class: 'btn white big', type: 'button', onclick: () => navigate('home') }, 'Home')));
  }
  if (token !== renderToken || !node) return;         // a newer route already won

  const s = stage();
  if (mounted && mounted.__dispose) { try { mounted.__dispose(); } catch (e) { /* teardown is best-effort */ } }
  const prev = mounted;
  // A screen that was told to leave mid-transition stays on stage until its exit finishes, un-focusable and
  // out of the pointer path, so the profile grid can fall away while Home arrives behind it.
  const lingering = prev && prev.classList.contains('leaving');
  if (prev && !lingering && prev.parentNode === s) s.removeChild(prev);
  if (nav.parentNode !== s) s.appendChild(nav);
  s.appendChild(node);
  if (lingering) {
    prev.querySelectorAll('[data-f]').forEach((n) => { n.removeAttribute('data-f'); n.removeAttribute('tabindex'); });
    prev.style.pointerEvents = 'none';
    setTimeout(() => { if (prev.parentNode === s) s.removeChild(prev); }, 700);
  }
  mounted = node;
  wireNavScroll(node);

  const chromeless = route.name === 'play' || GATE_SCREENS.indexOf(route.name) >= 0;
  nav.style.display = chromeless ? 'none' : 'flex';
  setNavActive(route.name);
  document.body.classList.toggle('watching', route.name === 'play');

  // The menu bar lives beside the screen in #stage. Scoping the ring to the screen alone made the
  // tabs unreachable on a remote, so a chromeless screen keeps its own scope and everything else
  // can walk up into the bar.
  focus.pushScope(chromeless ? node : stage());
  let target = focusTargetFor(route);
  // A screen may choose where the ring lands: the profile grid puts it on the last person who watched.
  if (!target && node.__defaultFocus) {
    try { target = node.__defaultFocus(); } catch (e) { /* a screen can be wrong about its own focus */ }
  }
  focus.focusDefault(target);
}

/* The ring goes back where the viewer left it, not at the top of the page every time. */
function focusTargetFor(route) {
  if (route.name === 'detail' && route.arg) return stage().querySelector(`.card[data-identity="${cssEscape(route.arg)}"]`);
  if (route.name === 'home') {
    const id = state.focusMemoryFor('home');
    if (id) return stage().querySelector(`.card[data-identity="${cssEscape(id)}"]`);
  }
  return null;
}

/* ----------------------------------------------------------------- chrome */

function buildNav() {
  const item = (name, label, target, arg) => el('button', {
    class: 'nav-item', type: 'button', dataset: { route: target },
    onclick: () => navigate(target, arg),
  }, label);
  nav = el('div', { class: 'nav' },
    el('button', { class: 'brand', type: 'button', 'aria-label': 'Scenix home', onclick: () => navigate('home') },
      el('span', { class: 'brand-mark' })),
    el('div', { class: 'nav-items' },
      item('home', 'Home', 'home'),
      item('search', 'Search', 'search'),
      item('settings', 'Settings', 'settings')),
    el('div', { class: 'nav-right' },
      el('button', {
        class: 'nav-icon', type: 'button', 'aria-label': 'Profiles',
        onclick: () => navigate('profiles'),
      }, icon('user')),
      el('button', {
        class: 'avatar-chip', type: 'button', 'aria-label': 'Choose profile',
        onclick: () => navigate('profiles'),
      }, el('span', { class: 'ini' }))));
  for (const n of nav.querySelectorAll('.nav-item, .nav-icon, .brand, .avatar-chip')) focus.focusable(n);
  nav.addEventListener('scroll', () => { /* the screen scrolls, not the bar */ }, { passive: true });
  stage().addEventListener('scroll', (e) => {
    const top = e.target === stage() ? e.target.scrollTop : 0;
    nav.classList.toggle('solid', top > token('anim', 'navFadeAt', 50));
  }, { passive: true });
  return nav;
}

/* A screen whose module is missing from the bundle has to leave the nav bar: on a remote, a focused item that
   does nothing is a dead key, and a dead key costs the viewer the page they were trying to reach. */
const brokenScreens = new Set();

function hideNavItem(name) {
  if (!nav || name === 'home' || name === 'profiles') return;
  const b = nav.querySelector(`.nav-item[data-route="${name}"]`);
  if (b) b.remove();
}

function setNavActive(name) {
  if (!nav) return;
  for (const n of nav.querySelectorAll('.nav-item')) n.classList.toggle('on', n.dataset.route === name);
  const p = state.session.profile;
  const chip = nav.querySelector('.avatar-chip');
  if (chip && p) {
    chip.textContent = '';
    chip.appendChild(el('span', { class: 'ini', text: initials(p.name) }));
    chip.className = `avatar-chip${state.isKids(p) ? ' kids' : ''}`;
  }
}

/* Scroll state lives on the screen, so the nav listens to whatever is mounted. */
function wireNavScroll(node) {
  node.addEventListener('scroll', () => {
    if (!nav) return;
    nav.classList.toggle('solid', node.scrollTop > token('anim', 'navFadeAt', 50));
  }, { passive: true });
}
export { wireNavScroll };

/* --------------------------------------------------------------- modals */

function mountModal(node, onClose) {
  const wrap = el('div', { class: 'modal' }, node);
  focus.focusable(wrap);
  layer().appendChild(wrap);
  // input.js keeps a stack of handlers, topmost first, each a plain (event, key) => consumed function.
  const handler = (e, key) => {
    if (key === 'Escape' || key === 'Backspace' || key === 'GoBack' || key === 'BrowserBack' || key === 'Back') {
      closeTopModal();
      return true;
    }
    return false;
  };
  const entry = { node: wrap, onClose, handler };
  modals.push(entry);
  pushHandler(handler);
  focus.pushScope(wrap);
  return wrap;
}

function closeTopModal() {
  const entry = modals.pop();
  if (!entry) return;
  popHandler(entry.handler);
  setDigitSink(null);
  focus.popScope();
  if (entry.node.parentNode) entry.node.parentNode.removeChild(entry.node);
  if (entry.onClose) entry.onClose();
}

function closeModals() {
  while (modals.length) closeTopModal();
}

/**
 * The PIN pad has two moods. To open a profile it checks the number against the hub, and `state.isLocked`
 * decides whether the pad is worth showing at all — a pad demanding digits nothing can check is a door with no
 * handle. With `mode: 'set'` it instead collects a new number and hands it back to the caller. The length comes
 * from the theme, so a household that wants six digits changes one token. Digits arrive from the on-screen pad,
 * a keyboard, and the remote's number row alike, through input.js's digit sink.
 *
 * The hub checks the number — a PIN is never sent down to the TV, so nothing on this screen could guess it —
 * and an older hub with no unlock endpoint falls back to whatever the record in hand happens to carry. A wrong
 * PIN shakes the pad instead of closing it. Cancel is a real button rather than only the remote's Back key,
 * because this dialog is also reached with a mouse and with a finger.
 */
/**
 * A modal that asks for one line of text - an add-on address, a playlist URL, anything too long to be a
 * number. It draws the same keyboard the search screen uses, so the remote's number row, a real keyboard and
 * the D-pad all feed one buffer, and it takes the typing target from whatever screen opened it and hands it
 * back on close. Cancel answers `null`, which is a different thing from an empty value: the owner either did
 * not want to type anything, or typed nothing yet.
 */
function askText({ title = '', reason = '', placeholder = '', initial = '', action = 'Save', validate = null } = {}) {
  return new Promise((resolve) => {
    const err = el('div', { class: 'err' });
    let answered = false;
    const entry = entryField({ glyph: 'globe', placeholder, initial });
    let box;
    const answer = (value) => {
      if (answered) return;
      answered = true;
      closeTopModal();          // this dialog only: the screen it was opened over stays where it was
      resolve(value);
    };

    const submit = () => {
      const v = String(entry.value || '').trim();
      const problem = typeof validate === 'function' ? validate(v) : null;
      if (problem) {
        // Refusing in place keeps the half-typed address on screen: making someone re-enter 40 characters of
        // URL because one character was wrong is how a TV form gets abandoned.
        err.textContent = problem;
        box.classList.remove('shake');
        void box.offsetWidth;
        box.classList.add('shake');
        return;
      }
      answer(v);
    };

    box = el('div', { class: 'mbox text' },
      el('button', {
        class: 'mclose', type: 'button', text: '✕', 'aria-label': 'Cancel', title: 'Cancel',
        onclick: () => answer(null),
      }),
      el('h2', { text: title }),
      reason ? el('p', { text: reason }) : null,
      entry.field,
      // Field, keys, then the actions along the bottom — the same order the PIN pad uses, so a remote that
      // already knows one of these dialogs knows both, and focus walks downward into the keys rather than
      // back up over them.
      entry.kbd, err,
      el('div', { class: 'mfoot' },
        el('button', { class: 'btn line', type: 'button', text: 'Cancel', onclick: () => answer(null) }),
        el('button', { class: 'btn white', type: 'button', text: action, onclick: submit })));
    focus.focusable(box.querySelector('.mclose'));
    for (const b of box.querySelectorAll('.mfoot .btn')) focus.focusable(b);

    const previous = currentTypingTarget();
    setTypingTarget({
      insert: (ch) => entry.insert(ch),
      backspace: () => entry.back(false),
      clear: () => entry.back(true),
      commit: submit,
    });
    mountModal(box, () => {
      setTypingTarget(previous);
      if (!answered) resolve(null);
    });
    focus.setFocus(entry.firstKey(), { scroll: false });
  });
}

function askPin(profile, { reason = 'Enter your PIN', mode = 'verify' } = {}) {
  return new Promise((resolve) => {
    const need = Number(token('pin', 'length', 4));
    const expected = String(profile.pin || '');
    let got = '';
    let busy = false;
    const dots = el('div', { class: 'dots' });
    for (let i = 0; i < need; i++) dots.appendChild(el('i'));
    const err = el('div', { class: 'err' });
    const pad = el('div', { class: 'p9' });
    const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'clear', '0', 'ok'];
    for (const k of keys) {
      const btn = el('button', {
        class: `key${k.length > 1 ? ' sm' : ''}`, type: 'button', text: k === 'clear' ? 'Clear' : k === 'ok' ? 'OK' : k,
        onclick: () => press(k),
      });
      focus.focusable(btn);
      pad.appendChild(btn);
    }
    const cancel = el('button', { class: 'btn line', type: 'button', text: 'Cancel', onclick: () => closeTopModal() });
    focus.focusable(cancel);
    const x = el('button', {
      class: 'mclose', type: 'button', text: '✕', 'aria-label': 'Cancel', title: 'Cancel',
      onclick: () => closeModals(),
    });
    focus.focusable(x);
    const box = el('div', { class: 'mbox pin' }, x,
      el('div', { class: 'pmark' }, el('span', { text: 'Scenix' })),
      el('h2', { text: profile.name }),
      el('p', { text: reason }),
      dots, pad, err,
      el('div', { class: 'mfoot' }, cancel));
    const draw = () => { for (let i = 0; i < need; i++) dots.children[i].classList.toggle('on', i < got.length); };
    /* The pad answers once, and it has to claim the answer before it closes: `closeModals()` runs this modal's
       own close callback, whose job is to answer "no" when the viewer walked out of the pad without finishing.
       Answered afterwards, that callback fires first and every PIN a viewer types is thrown away on the way
       out — the pad looks like it accepted the number, and nothing is stored. js/app.js `askText` guards the
       same way with `answered`; this pad had the same shape and not the same guard. */
    let answered = false;
    const finish = (ok) => {
      if (answered) return;
      answered = true;
      // Only this pad, not everything under it: a pad opened over a profile's own sheet answers to that sheet,
      // and dismissing the sheet as well would throw away the row the viewer was standing on.
      closeTopModal();
      resolve(mode === 'set' ? (ok ? got : null) : ok);
    };
    function refuse(message) {
      got = '';
      draw();
      err.textContent = message;
      box.classList.remove('shake');
      void box.offsetWidth;            // the shake is a one-shot animation, so it has to be restarted
      box.classList.add('shake');
      focus.setFocus(pad.querySelector('.key'), { scroll: false });
    }
    function press(k) {
      if (busy) return;
      if (k === 'clear') { got = ''; draw(); err.textContent = ''; return; }
      if (k === 'ok') return check();
      if (got.length >= need) return;
      got += k;
      draw();
      if (got.length === need) check();
    }
    async function check() {
      // Setting a PIN there is nothing to compare against yet: the number only has to be complete.
      if (mode === 'set') return finish(got.length === need);
      if (expected && got === expected) return finish(true);
      if (profile.id) {
        busy = true;
        try {
          const r = await api.unlockProfile(profile.id, got);
          busy = false;
          if (r && (r.unlocked || r.noPin || r.ok)) return finish(true);
          return refuse('That PIN does not match');
        } catch (e) {
          busy = false;
          if (e && (e.status === 401 || e.status === 403)) return refuse('That PIN does not match');
          // No unlock endpoint on this hub: fall back to the record already in front of us.
          if (!expected) return finish(true);
        }
      }
      return refuse('That PIN does not match');
    }
    // input.js routes every digit — keyboard, remote number row, or on-screen pad — to one function.
    setDigitSink(press);
    mountModal(box, () => {
      setDigitSink(null);
      if (answered) return;            // the number was already given; closing the pad must not answer "no" too
      answered = true;
      resolve(false);
    });
    focus.focusDefault(pad.querySelector('.key'));
  });
}

/* ---------------------------------------------------------------- profile */

function setProfile(p) {
  state.setProfile(p);
  document.body.classList.toggle('kids', state.isKids(p));
  state.loadMyList();
  setNavActive(ctx.route().name);
}

/**
 * The profile switch, singled out in our design notes as the one transition in the product that has to be
 * choreographed rather than faded: a route change plus a fade would leave the viewer wondering whether the
 * face they picked actually took.
 *
 * The chosen face is lifted out of the grid as a clone pinned to its exact on-screen box, the grid behind it
 * falls back to 0.9 and fades, Home mounts underneath, and the clone flies to the account chip in the nav
 * bar — the place the same face lives for the rest of the session. Transform and opacity only: moving it
 * with left/top would relayout a 1 GB panel in the middle of a transition, and a relayout there is where a
 * TV drops frames.
 */
/**
 * Lift a node out of the page as a clone pinned to its exact box, so it survives whatever happens to the
 * screen underneath it. Paired with `land`, which flies that clone to another node's box. Two elements
 * crossfading is a page transition; one element travelling between two places is a continuity, and the eye
 * reads the difference immediately.
 */
function lift(node, cls) {
  if (!node || !node.getBoundingClientRect) return null;
  const a = node.getBoundingClientRect();
  if (!a.width || !a.height) return null;
  const ghost = el('div', {
    class: cls,
    style: `left:${a.left}px; top:${a.top}px; width:${a.width}px; height:${a.height}px;`,
  }, node.cloneNode(true));
  layer().appendChild(ghost);
  return { ghost, from: a };
}

function land(pack, target, ms = 800) {
  if (!pack || !pack.ghost) return;
  const b = target && target.getBoundingClientRect ? target.getBoundingClientRect() : null;
  const sc = b && b.width ? b.width / pack.from.width : 0.3;
  const dx = b && b.width ? b.left - pack.from.left : (window.innerWidth * 0.5 - pack.from.left);
  const dy = b && b.width ? b.top - pack.from.top : (60 - pack.from.top);
  requestAnimationFrame(() => {
    pack.ghost.style.transform = `translate(${dx}px, ${dy}px) scale(${sc})`;
    requestAnimationFrame(() => pack.ghost.classList.add('fly'));
  });
  setTimeout(() => { if (pack.ghost.parentNode) pack.ghost.parentNode.removeChild(pack.ghost); }, ms + 140);
}

async function switchProfile(profile, node) {
  if (!profile) return;
  const face = node && node.querySelector ? node.querySelector('.a') : null;
  const wrap = node && node.closest ? node.closest('.pwrap') : null;
  const from = face ? face.getBoundingClientRect() : null;
  let ghost = null;
  if (face && from && from.width > 0) {
    ghost = el('div', {
      class: 'avatar-ghost',
      style: `left:${from.left}px; top:${from.top}px; width:${from.width}px; height:${from.height}px;`,
    }, face.cloneNode(true));
    layer().appendChild(ghost);
  }
  // The wordmark goes the other way from the face: it has been holding the top of the profile screen, and on
  // this beat it steps into the corner of the nav bar, where it lives for the rest of the session. Lifted
  // before Home mounts, because the screen it stands on is about to be torn down.
  const mark = lift(wrap && wrap.querySelector('.pbrand .brand-mark'), 'logo-ghost');
  if (wrap) wrap.classList.add('leaving');
  setProfile(profile);
  await navigate('home');
  if (mark) land(mark, document.querySelector('.nav .brand .brand-mark'));
  if (!ghost) return;
  const chip = document.querySelector('.avatar-chip');
  const to = chip ? chip.getBoundingClientRect() : null;
  const sc = to && to.width > 0 ? to.width / from.width : 0.18;
  const dx = to && to.width > 0 ? to.left - from.left : (window.innerWidth - from.width * 0.18 - 96);
  const dy = to && to.width > 0 ? to.top - from.top : (48 - from.height * 0.18);
  requestAnimationFrame(() => {
    ghost.style.transform = `translate(${dx}px, ${dy}px) scale(${sc})`;
    requestAnimationFrame(() => ghost.classList.add('fly'));
  });
  setTimeout(() => { if (ghost.parentNode) ghost.parentNode.removeChild(ghost); }, 900);
}

async function unlock(p) {
  if (!p) return false;
  if (state.isLocked(p) && !await askPin(p)) return false;
  setProfile(p);
  return true;
}
export { unlock };

/* ------------------------------------------------------------- shell keys */

/**
 * The TV shell delivers remote keys two ways: real keydowns from the WebView, and `scenix:key`
 * CustomEvents for the media keys and for BACK after web.canGoBack() is exhausted. Both end up on the
 * same path — a synthesised keydown — so input.js stays the only key router in the product.
 */
function bridgeShellKeys() {
  document.addEventListener('scenix:key', (e) => {
    const detail = (e && e.detail) || {};
    const key = detail.key || detail.code;
    if (!key) return;
    document.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  });
}

/* ------------------------------------------------------------------- boot */

function bootNote(text, retry) {
  const b = bootEl();
  if (!b) return;
  b.classList.remove('gone');
  const note = b.querySelector('.boot-note');
  if (note) note.textContent = text;
  let btn = b.querySelector('.btn');
  if (retry && !btn) {
    btn = el('button', { class: 'btn white big', type: 'button' }, 'Try again');
    focus.focusable(btn);
    b.appendChild(btn);
  }
  if (btn) {
    btn.textContent = 'Try again';
    btn.onclick = () => boot();
    if (!focus.getFocus()) focus.focusDefault(btn);
  }
}

let splashAt = Date.now();

/**
 * The intro is a beat, not a spinner. It holds for `boot.minMs` (theme.json) and the landing screen mounts
 * *underneath* it, so the two crossfade instead of queueing — a hub that answers in 40 ms would otherwise
 * paint the profile grid in a fallback font and re-lay it out when the real face arrives.
 */
async function hideBoot() {
  const min = Number(token('boot', 'minMs', 1500));
  const left = min - (Date.now() - splashAt);
  if (left > 0) await new Promise((r) => setTimeout(r, left));
  const b = bootEl();
  if (!b) return;
  b.classList.add('gone');
  // The wordmark does not disappear and reappear one screen later — it moves. It flies from the centre of the
  // splash to the head of the profile screen while the cards slide in behind it, so the opening is one
  // continuous gesture rather than three separate ones. `booted` is what starts that card entrance: the
  // entrance is timed off the splash, not off the data, because a fast hub must not spoil the beat.
  const mark = lift(b.querySelector('.boot-logo'), 'logo-ghost');
  const seat = document.querySelector('.pwrap .pbrand .brand-mark') || document.querySelector('.nav .brand .brand-mark');
  land(mark, seat);
  document.body.classList.add('booted');
  setTimeout(() => { if (b.parentNode) b.parentNode.removeChild(b); }, 700);
}

let booted = false;

async function boot() {
  // ?api= lets the page be opened from a file, a laptop, or a different box on the network.
  const q = new URLSearchParams(location.search);
  const apiParam = q.get('api');
  if (apiParam) setApiBase(apiParam);

  await loadTheme();
  initVideo();
  buildNav();
  // Ask now which screens actually exist, rather than finding out when someone presses OK on a dead key.
  // Loading them costs nothing: visiting one screen loads its module anyway.
  for (const name of Object.keys(SCREENS)) {
    if (brokenScreens.has(name)) continue;
    const probe = SCREENS[name]();
    if (probe && probe.catch) probe.catch(() => { brokenScreens.add(name); hideNavItem(name); });
  }
  initInput({
    onBack: () => { back(); return true; },
    onLetterSearch: (ch) => {
      if (ctx.route().name === 'search') return;
      navigate('search', ch);
    },
  });
  bridgeShellKeys();

  // A renderer restart on the panel re-runs boot() on the same page: make it idempotent.
  if (booted) { ctx.reload(); return; }
  booted = true;

  try {
    const m = await api.manifest();
    state.session.manifest = m;
    const p = await api.profiles();
    state.setProfiles(p.profiles);
    state.loadMyList();
  } catch (e) {
    console.error('[scenix] hub unreachable', e);
    bootNote(`The hub at ${apiBase()} is not answering. ${String(e && e.message || e)}`, true);
    return;
  }

  const route = parseHash();
  // ?profile=<id> opens a page as a specific person. The launcher and the native shell hand a deep link
  // over with it, and a PIN-gated profile still has to be unlocked.
  const wanted = q.get('profile');
  const gated = route.name === 'home' || GATE_SCREENS.indexOf(route.name) >= 0;

  // The landing screen mounts *under* the intro rather than after it, so the two crossfade.
  if (gated && !state.session.profile && !wanted) navigate('profiles');

  await hideBoot();

  if (wanted && !state.session.profile) await unlock(state.profileById(wanted));
  if (!gated && !state.session.profile) {
    // A deep link (a shared detail page, a resume) picks the last profile the household used.
    await unlock(state.profileById(state.lastProfileId()) || state.session.profiles[0]);
  }
  if (!state.session.profile) {
    if (route.name !== 'profiles') navigate('profiles');
    return;
  }
  document.body.classList.toggle('kids', state.isKids(state.session.profile));
  navigate(route.name, route.arg);
}

boot().catch((e) => {
  console.error('[scenix] boot failed', e);
  bootNote(`Scenix could not start. ${String(e && e.message || e)}`, true);
});
