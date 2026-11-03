/**
 * js/video.js — the only two <video> elements in the product.
 *
 *   1. the preview element   — muted, looping, 360p. It is a single element that js moves *into* whatever
 *                              is being previewed: the billboard, or a card the remote has rested on for
 *                              the preview delay. One element rather than one per card is what keeps a
 *                              40-card row cheap on a TV with a real lowmemorykiller.
 *   2. the feature element   — the film. Created here and handed to js/player.js, which drives it. On a
 *                              native shell (hasNativePlayer()) the shell decodes instead and this
 *                              element is never created at all, so a TV page holds exactly one <video>.
 *
 * Nothing else in the product may call document.createElement('video').
 */

import { apiUrl } from '/ui/js/api.js';
import { byId } from '/ui/js/dom.js';
import { hasNativePlayer } from '/ui/js/state.js';

let previewWrap = null;
let previewVideo = null;
let featureDiv = null;
let featureVideo = null;
let live = false;

/* ------------------------------------------------------------------ preview */

function ensurePreview() {
  if (previewVideo) return previewVideo;
  previewWrap = document.createElement('div');
  previewWrap.className = 'preview';
  previewWrap.setAttribute('aria-hidden', 'true');
  previewVideo = document.createElement('video');
  // Muted + loop + playsinline is the whole contract for a background preview: it must never make a TV
  // shout, and it must survive a firmware that refuses an unrequested play.
  // A preview is a trailer, not wallpaper: it carries its own sound. The shell allows autoplay with
  // audio; a desktop browser that blocks it falls back to muted playback in startPreview() and still
  // shows the picture rather than nothing.
  previewVideo.muted = false;
  previewVideo.defaultMuted = false;
  previewVideo.volume = 1;
  previewVideo.loop = true;
  previewVideo.playsInline = true;
  previewVideo.setAttribute('playsinline', '');
  previewVideo.preload = 'none';
  previewVideo.setAttribute('aria-hidden', 'true');
  // A browser paints a flat grey rectangle with a play glyph in it before a video has a frame. That is
  // the "grey play thumbnail" a viewer sees for half a second before every trailer. The still artwork is
  // the poster until the first frame is actually decoded; only then does the picture fade in.
  previewVideo.style.background = 'transparent';
  const ready = () => { if (previewWrap) previewWrap.classList.add('ready'); };
  previewVideo.addEventListener('loadeddata', ready);
  previewVideo.addEventListener('canplay', ready);
  previewVideo.addEventListener('error', () => stopPreview());
  previewWrap.appendChild(previewVideo);
  return previewVideo;
}

/** How many <video> elements exist right now — the invariant, kept checkable. */
export function videoCount() {
  return document.querySelectorAll('video').length;
}

/** Move the one preview element into the slot being previewed (`.billboard .preview`, `.card .art`). */
export function attachPreview(slot) {
  const v = ensurePreview();
  if (!slot) return v;
  if (slot.contains(previewWrap)) { slot.classList.add('on'); previewWrap.classList.add('on'); return v; }
  stopPreview();
  slot.appendChild(previewWrap);
  slot.classList.add('on');
  previewWrap.classList.add('on');
  return v;
}

/**
 * A trailer target from /app/v1/trailer: the hub already rewrote most of them to its own
 * /app/v1/proxy?u=…, which replays the User-Agent the upstream demands. Prefer that — a raw
 * googlevideo link with a browser's UA is a coin toss.
 */
export function previewSrc(target) {
  if (!target) return '';
  // Prefer the addon's own address. On the BRAVIA WebView a 4 KB proxied URL is rejected by the media
  // element as a format error while the 1.5 KB googlevideo URL plays with sound; the proxy stays as the
  // fallback for a target that only has one.
  const p = String(target.url || '');
  if (/^https?:/i.test(p)) return p;
  if (target.proxiedUrl) return apiUrl(target.proxiedUrl);
  return p ? apiUrl(p) : '';
}

/** Start the muted preview in `slot`. Resolves false when there is nothing to play. */
export async function startPreview(slot, target, poster) {
  const src = previewSrc(target);
  if (!src) return false;
  const v = attachPreview(slot);
  if (poster) v.poster = String(poster);
  if (previewWrap) previewWrap.classList.remove('ready');
  if (v.src !== src) {
    v.src = src;
    v.load();
  }
  try {
    v.muted = false;
    await v.play();
    return true;
  } catch (e) {
    // A browser with an autoplay-with-sound policy: keep the picture, mute the sound, and let the next
    // real keypress be the gesture that turns it back on.
    try {
      v.muted = true;
      await v.play();
      return true;
    } catch (e2) {
      return false;
    }
  }
}

export function stopPreview() {
  if (!previewVideo) return;
  try { previewVideo.pause(); } catch (e) { /* already stopped */ }
  if (previewVideo.getAttribute('src')) {
    previewVideo.removeAttribute('src');
    try { previewVideo.load(); } catch (e) { /* detached mid-teardown */ }
  }
  if (previewWrap && previewWrap.parentNode) previewWrap.parentNode.removeChild(previewWrap);
  document.querySelectorAll('.preview.on').forEach((n) => n.classList.remove('on'));
  if (previewWrap) { previewWrap.classList.remove('on'); previewWrap.classList.remove('ready'); }
}

/** Hide the preview without destroying it — used when a screen is torn down. */
export function parkPreview() {
  if (!previewVideo) return;
  try { previewVideo.pause(); } catch (e) { /* ignore */ }
  document.querySelectorAll('.preview.on').forEach((n) => n.classList.remove('on'));
  if (previewWrap) previewWrap.classList.remove('on');
}

/* ------------------------------------------------------------------ feature */

/**
 * The film surface. js/player.js's VideoBackend adopts these two nodes instead of making its own, which
 * is what keeps the count at two. It is called only on the browser path: a native shell decodes in the
 * shell's own PlayerView and never asks for this.
 */
export function mainSurface() {
  if (featureVideo && featureDiv && featureDiv.isConnected) return { div: featureDiv, video: featureVideo };
  const host = byId('video-host') || document.body;
  featureDiv = document.createElement('div');
  featureDiv.className = 'surface feature';
  featureVideo = document.createElement('video');
  featureVideo.playsInline = true;
  featureVideo.setAttribute('playsinline', '');
  featureVideo.preload = 'auto';
  // No native controls: the chrome in js/screens/player.js is the interface on both backends, and
  // #video-host is pointer-events:none so the page's own buttons always get the press.
  featureDiv.appendChild(featureVideo);
  host.appendChild(featureDiv);
  return { div: featureDiv, video: featureVideo };
}

/** Only the player screen may show the film element, so a poster row is never covered by a stopped frame. */
export function setFeatureVisible(on) {
  if (!featureDiv) return;
  featureDiv.classList.toggle('on', !!on);
}

/**
 * Contract rule (a): while the shell is decoding, the picture lives *behind* this WebView. The page must
 * stop painting an opaque background over it, or the TV shows a black rectangle with a HUD on it.
 */
export function setSurfaceLive(on) {
  live = !!on;
  // The shell owns the picture surface. Tell it when the surface is wanted: a full-screen TextureView
  // behind a browse screen is a composited layer paid for nothing.
  try {
    if (window.Scenix && typeof window.Scenix.surface === 'function') window.Scenix.surface(!!on);
  } catch (e) { /* an older shell simply keeps its surface */ }
  const root = document.documentElement;
  if (live) {
    root.classList.add('native-live');
    document.body.classList.add('native-live');
  } else {
    root.classList.remove('native-live');
    document.body.classList.remove('native-live');
  }
}

export function surfaceLive() { return live; }

/** Called once at boot. Decides — for the whole session — whether the film element exists at all. */
export function initVideo() {
  ensurePreview();
  if (!hasNativePlayer()) mainSurface();
  setFeatureVisible(false);
  return { native: hasNativePlayer(), videos: videoCount() };
}

export function featureVideoEl() { return featureVideo; }
