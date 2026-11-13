/*
 * Scenix carve — the GeckoView twin of GameActivity.CARVE_JS.
 *
 * GeckoView has no host-side evaluateScript any more, so the sweep lives here as a content
 * script: it runs in every frame (even cross-origin ones WebView's dive could never reach),
 * re-runs on a slow tick for the whole session, and posts each result back over a port so the
 * app can still tell the viewer when a portal never starts its game.
 *
 * If you change the sweep design, change the copy in GameActivity too — both engines ship it.
 */
(function () {
  // Some ports refuse to boot when the JS flag crossOriginIsolated reads false, but on this
  // engine SharedArrayBuffer works unisolated and GeckoView's webRequest can never rewrite
  // headers on the app's own top-level document. Nothing is enforced either way, so the page
  // is simply told it is isolated; the real flag is kept for the app's report.
  var realCoi = false;
  try {
    realCoi = !!window.crossOriginIsolated;
    if (!realCoi)
      Object.defineProperty(window, "crossOriginIsolated", { get: function () { return true; } });
  } catch (e) {}
  var port = null;
  try {
    port = browser.runtime.connect({ name: "carve" });
    try { window.__scenixCarvePortState = "open"; } catch (e) {}
    port.onDisconnect.addListener(function (d) {
      var why = (d && d.error && d.error.message) || "closed";
      try { window.__scenixCarvePortState = "dead: " + why; } catch (e) {}
      console.log("carve port disconnected: " + why);
    });
  } catch (e) {
    try { window.__scenixCarvePortState = "threw: " + e; } catch (x) {}
    console.log("carve connect threw: " + e);
  }
  function say(what, val) {
    var m = { what: what, val: val };
    try { if (port) { port.postMessage(m); return; } } catch (e) {}
    try { browser.runtime.sendMessage(m).catch(function (e) { console.log("carve send rejected: " + e); }); } catch (e) { console.log("carve send threw: " + e); } // content scripts without a background land in the app's onMessage
  }
  // The app's escape hatch: when a portal drops a popup over the game, the remote cannot reach
  // its × (OK taps only screen-center), so BACK asks this frame to click the overlay's own
  // dismissal control — whatever the portal class-names it. Game menus stay untouched: their
  // buttons say Continue/Play, which is not a dismissal word. Twin of CLOSE_JS in GameActivity.
  function closePopup() {
    try {
      var W = window.innerWidth || 1, H = window.innerHeight || 1;
      function vis(e) {
        try {
          var b = e.getBoundingClientRect();
          if (b.width < 10 || b.height < 10) return false;
          var cs = getComputedStyle(e);
          return cs.display !== 'none' && cs.visibility !== 'hidden' &&
            (parseFloat(cs.opacity) || 1) > 0.05 && b.top < H && b.bottom > 0 && b.left < W && b.right > 0;
        } catch (x) { return false; }
      }
      function big(e) { var b = e.getBoundingClientRect(); return b.width >= W * 0.4 && b.height >= H * 0.3; }
      var cands = document.querySelectorAll('button,a,[role="button"],[aria-label],[class*="close"],[class*="skip"],[id*="close"],[id*="skip"]');
      var best = null, bestZ = -1;
      for (var i = 0; i < cands.length; i++) {
        var e = cands[i];
        if (!vis(e)) continue;
        var t = (e.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 40);
        var cls = '';
        try { cls = (typeof e.className === 'string' ? e.className : '') + ' ' + (e.id || '') + ' ' + (e.getAttribute('aria-label') || ''); } catch (x) {}
        var hit = /^(\u00d7|\u2715|x|close|no thanks|maybe later|skip ad|skip|dismiss|got it)$/i.test(t) || /(close|skip|dismiss)/i.test(cls);
        if (!hit) continue;
        var on = false, p = e;
        for (var d = 0; p && d < 6; d++, p = p.parentElement) { if (p.nodeType === 1 && vis(p) && big(p)) { on = true; break; } }
        if (!on && !big(e)) continue;
        var z = 0;
        try { z = parseInt(getComputedStyle(e).zIndex, 10) || 0; } catch (x) {}
        if (z > bestZ) { bestZ = z; best = e; }
      }
      if (best) { best.click(); return true; }
      return false;
    } catch (e) { return false; }
  }
  try {
    if (port) port.onMessage.addListener(function (m) {
      if (!m || m.what !== 'close') return;
      say('closed', closePopup() ? 'yes' : 'no');
    });
  } catch (e) {}
  function isTop() { try { return window.top === window; } catch (e) { return false; } }
  function v() { return window; }
  function area(e, W, H) {
    try {
      var b = e.getBoundingClientRect();
      var x = Math.min(b.right, W) - Math.max(b.left, 0), y = Math.min(b.bottom, H) - Math.max(b.top, 0);
      return x > 0 && y > 0 ? x * y : 0;
    } catch (x) { return 0; }
  }

  // crossOriginIsolated, reported with a canvas flag: a wrapper frame is never isolated and
  // that is normal — only a frame that actually holds the game canvas matters, and the app
  // only falls back when THAT frame lost its isolation.
  var coiSentCanvas = false;
  function reportCoi(force) {
    if (!force && coiSentCanvas) return;
    var has = !!document.querySelector("canvas");
    if (!force && !has) return;
    coiSentCanvas = has ? true : coiSentCanvas;
    var c = realCoi; // the honest flag, captured before we raised it for the page
    // The isolation flag can read false on engines that don't know the portal's COEP variant
    // while SharedArrayBuffer still works — the constructor itself is the honest test.
    var sab = "ok";
    try { new SharedArrayBuffer(8); } catch (e) { sab = "no"; }
    // Some ports say in their own words that their server never sent the headers; that exact
    // phrase is the app's cue to inject them and reload.
    var txt = ""; try { txt = (document.body && document.body.innerText) || ""; } catch (e) {}
    say("coi", { v: c, sab: sab, canvas: has, recomp: /cross-origin isolation|COOP\/COEP/i.test(txt) });
  }
  reportCoi(true);

  // One-later capability probe for the app log: what graphics backends this engine actually
  // hands the page, plus the page's own first words (a port that refuses to boot says why).
  setTimeout(function () {
    try {
      var gpu = "no"; try { gpu = navigator.gpu ? "yes" : "no"; } catch (e) { gpu = "err"; }
      var gl = "no"; try {
        var tc = document.createElement("canvas"); tc.width = tc.height = 8;
        gl = (tc.getContext("webgl2") || tc.getContext("webgl")) ? "yes" : "no";
      } catch (e) { gl = "err"; }
      var txt = ""; try { txt = ((document.body && document.body.innerText) || "").replace(/\s+/g, " ").slice(0, 220); } catch (e) {}
      say("coi", { probe: 1, gpu: gpu, gl: gl, canvas: !!document.querySelector("canvas"), txt: txt });
    } catch (e) {}
  }, 9000);

  // ── the sweep ─────────────────────────────────────────────────────────────────────────────
  function sweep() {
    try {
      var W = v().innerWidth || 1, H = v().innerHeight || 1;
      var hid = window.__scenixHid || (window.__scenixHid = []);
      while (hid.length) { try { hid.pop().style.removeProperty("display"); } catch (x) {} }
      var pin = window.__scenixPin || (window.__scenixPin = []);
      while (pin.length) {
        var p0 = pin.pop();
        try {
          p0.style.removeProperty("position"); p0.style.removeProperty("left");
          p0.style.removeProperty("top"); p0.style.removeProperty("width");
          p0.style.removeProperty("height"); p0.style.removeProperty("z-index");
        } catch (x) {}
      }
      function carveDoc(doc, isMain) {
        var dv = doc.defaultView || window, w = dv.innerWidth || W, h = dv.innerHeight || H;
        var mark = doc.querySelectorAll("[data-scn]");
        for (var r = 0; r < mark.length; r++) mark[r].removeAttribute("data-scn");
        var pick = null, best = 0, ns = doc.querySelectorAll("iframe,canvas,video");
        for (var i = 0; i < ns.length; i++) {
          var n = ns[i];
          if (n.getAttribute && n.getAttribute("aria-hidden") === "true") continue; // decorative art, not a game
          var a = area(n, w, h) * (n.tagName === "IFRAME" ? 2 : 1);
          if (a > best) { best = a; pick = n; }
        }
        if (!pick || area(pick, w, h) < w * h * 0.04) return false;
        var node = pick, na = area(pick, w, h);
        while (node.parentElement && node.parentElement !== doc.body) {
          var pa = area(node.parentElement, w, h);
          if (pa > na * 1.7) break;
          node = node.parentElement; na = pa;
        }
        var chain = [];
        for (var p = node; p; p = p.parentElement) chain.unshift(p);
        doc.documentElement.setAttribute("data-scn", "keep");
        for (var j = 0; j < chain.length; j++) chain[j].setAttribute("data-scn", "keep");
        function hideOff(parent, keep) {
          var s = parent.children;
          for (var k = 0; k < s.length; k++) {
            var c = s[k];
            if (c === keep) continue;
            // The loader's status line ("Downloading core… 45%") is feedback the child needs
            // while a first launch pulls tens of MB — it is not portal chrome.
            var t = "";
            try { t = (c.textContent || "").replace(/\s+/g, " ").trim(); } catch (x) {}
            if (t && t.length < 48 && !c.querySelector("canvas,iframe,video") &&
                /down|loading|extract|install|verif|buffer|memory|insert|pause|started|ready/i.test(t)) continue;
            c.style.setProperty("display", "none", "important"); hid.push(c);
          }
        }
        // Walk the chain pairs: at each level keep only the child that leads to the game.
        // (chain[0] is the root itself — passing it as the keeper under body hid everything,
        // canvas included.)
        for (var l = 0; l < chain.length - 1; l++) hideOff(chain[l], chain[l + 1]);
        if (isMain) {
          doc.documentElement.style.setProperty("background", "#000", "important");
          doc.body.style.setProperty("margin", "0", "important");
          doc.body.style.setProperty("background", "#000", "important");
          node.style.setProperty("position", "fixed", "important");
          node.style.setProperty("left", "0", "important");
          node.style.setProperty("top", "0", "important");
          node.style.setProperty("width", "100vw", "important");
          node.style.setProperty("height", "100vh", "important");
          node.style.setProperty("z-index", "2147483000", "important");
          pin.push(node);
        }
        // Anything that floats OVER the stage without being part of it: popups, promo confetti, ad panels.
        function outsideStage(e) { return e !== node && !node.contains(e) && !e.contains(node); }
        var all = doc.getElementsByTagName("*");
        for (var t = 0; t < all.length; t++) {
          var e = all[t];
          if (!outsideStage(e)) continue;
          try {
            var cs = dv.getComputedStyle(e);
            if ((cs.position === "fixed" || cs.position === "absolute") &&
                (area(e, w, h) > w * h * 0.02 || parseInt(cs.zIndex, 10) >= 9)) {
              e.style.setProperty("display", "none", "important"); hid.push(e);
            }
          } catch (x) {}
        }
        promoPass(doc, node);
        if (!dv.__scenixGuard) {
          dv.__scenixGuard = 1;
          new dv.MutationObserver(function (ms) {
            for (var m = 0; m < ms.length; m++) {
              var add = ms[m].addedNodes || [];
              for (var q = 0; q < add.length; q++) {
                var el = add[q];
                if (el.nodeType !== 1) continue;
                if (el.tagName === "SCRIPT" || el.tagName === "STYLE") continue;
                if (!outsideStage(el)) continue;
                var pos = false;
                try { var c2 = dv.getComputedStyle(el); pos = c2.position === "fixed" || c2.position === "absolute"; } catch (x) {}
                if (pos) { el.style.setProperty("display", "none", "important"); hid.push(el); continue; }
                try { // a tile strip that isn't floating still sells other games
                  if (!el.querySelector || el.querySelector("canvas,video")) continue;
                  if (el.querySelectorAll("a,button").length >= 3 && el.querySelectorAll("img").length >= 2) {
                    el.style.setProperty("display", "none", "important"); hid.push(el);
                  }
                } catch (x) {}
              }
            }
          }).observe(doc.documentElement, { childList: true, subtree: true });
        }
        return true;
      }
      var ok = carveDoc(document, true);
      if (!ok) promoPass(document, null); // no stage yet: promo still must not float
      function dive(doc, d) {
        if (d > 2) return;
        var fs = doc.querySelectorAll("iframe");
        for (var i = 0; i < fs.length; i++) {
          try {
            var cd = fs[i].contentDocument;
            if (cd && cd.body) { if (carveDoc(cd, false)) ok = true; else promoPass(cd, null); dive(cd, d + 1); }
          } catch (x) {}
        }
      }
      dive(document, 0);

      // Ad chrome that no stage rule reaches: these run whether or not a stage exists, because
      // before the game's canvas appears there is no stage and the overlays would survive.
      killAds();
      pressStart();
      relayRefusal();
      reportCoi(false);

      return { res: ok ? "carved" : "none", canvas: !!document.querySelector("canvas") };
    } catch (e) { return { res: "none", canvas: false }; }
  }

  // Promo banners and cross-promo tile rows that the stage rules leave behind — and they must
  // run whether or not a stage exists, because on a page whose game never boots (a port waiting
  // for headers its server forgot) there is no stage at all and every overlay just floats.
  // A strip of game tiles always goes; a small floating notice with a close button of its own
  // goes too — unless it reads like loader feedback, which the child needs to see.
  function promoPass(doc, node) {
    var dv = doc.defaultView || window;
    var W = dv.innerWidth || 1, H = dv.innerHeight || 1;
    var hid = window.__scenixHid || (window.__scenixHid = []);
    var els = doc.querySelectorAll("div,section,aside");
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (node && (e === node || node.contains(e) || e.contains(node))) continue; // never the stage's own subtree
      try {
        if (e.querySelector("canvas,video,iframe")) continue;
        var b = e.getBoundingClientRect();
        if (b.width < 80 || b.height < 24) continue;
        var links = e.querySelectorAll("a,button").length;
        if (links >= 3 && e.querySelectorAll("img").length >= 2) {
          e.style.setProperty("display", "none", "important"); hid.push(e); continue; // tile strip sells other games
        }
        var cs = dv.getComputedStyle(e);
        if (cs.position !== "fixed" && cs.position !== "absolute" && cs.position !== "sticky") continue;
        if (links < 1) continue;
        if (b.height >= H * 0.35 && b.width >= W * 0.6) continue; // big overlays are gates — killAds/pressStart own those
        var t = (e.textContent || "").replace(/\s+/g, " ").trim();
        if (!t || t.length > 220) continue;
        if (/down|loading|extract|install|verif|buffer|memory|insert|pause|started|ready/i.test(t)) continue;
        e.style.setProperty("display", "none", "important"); hid.push(e);
      } catch (x) {}
    }
  }

  // A port that cannot boot on this engine says why in its own words ("This browser has no
  // WebGPU…", "This page needs cross-origin isolation…"). The sweep hides that line with the
  // rest of the portal chrome, leaving a black screen and no reason; relay it to the app first
  // so the viewer sees the honest one-liner instead. Deduped per page so a slow tick can't spam.
  var REFUSE = /(has no WebGPU|no WebGPU|no usable graphics adapter|cross-origin isolation|WebGPU is switched on|WebGL[^.]{0,30}(not|no)|not supported by this browser|requires? (a browser|WebGPU|WebGL2?)|Use a (current|modern)|needs? (a|an) (modern|updated|current))/i;
  function relayRefusal() {
    try {
      var seen = window.__scenixRefused || (window.__scenixRefused = {});
      var els = document.querySelectorAll("div,section,main,output,p");
      for (var i = 0; i < els.length; i++) {
        var e = els[i];
        if (e.querySelector && e.querySelector("canvas,iframe,video")) continue;
        var t = (e.textContent || "").replace(/\s+/g, " ").trim();
        if (!t || t.length < 12 || t.length > 240) continue;
        if (!REFUSE.test(t)) continue;
        if (seen[t]) continue;
        seen[t] = 1;
        say("refuse", { t: t });
      }
    } catch (x) {}
  }

  // A full-frame fixed/absolute panel with text and no canvas is a gate or an upsell ad
  // ("Congratulations! …"); a small box pinned at a high z-index is ad badge chrome.
  function killAds() {
    try {
      var dv = v(), W = dv.innerWidth || 1, H = dv.innerHeight || 1;
      var hid = window.__scenixHid || (window.__scenixHid = []);
      var all = document.querySelectorAll("div,section,a");
      for (var i = 0; i < all.length; i++) {
        var e = all[i];
        var cs; try { cs = dv.getComputedStyle(e); } catch (x) { continue; }
        if (cs.position !== "fixed" && cs.position !== "absolute") continue;
        var b; try { b = e.getBoundingClientRect(); } catch (x) { continue; }
        if (b.width < 8 || b.height < 8) continue;
        if (e.querySelector("canvas")) continue; // never touch the game itself
        var z = parseInt(cs.zIndex, 10); if (isNaN(z)) z = 0;
        var txt = (e.textContent || "").replace(/\s+/g, " ").trim();
        var full = b.width >= W * 0.8 && b.height >= H * 0.8;
        if (full && txt.length > 0) {
          var s = findStart(e);
          e.__scenixTries = (e.__scenixTries || 0) + 1;
          if (s) { try { s.click(); } catch (x) {} if (e.__scenixTries < 3) continue; } // a gate gets a chance first
          e.style.setProperty("display", "none", "important"); hid.push(e);
          continue;
        }
        if (z >= 90 && b.width < W * 0.5 && b.height < H * 0.5) {
          e.style.setProperty("display", "none", "important"); hid.push(e);
        }
      }
    } catch (e) {}
  }

  // Portals gate the game behind a START/PLAY/CONTINUE button; on a TV that press never comes
  // on its own. Click the most plausible one until the game's canvas is actually on screen.
  var START_RE = /^(start|play|start game|press start|press any key|click to (start|play|load|continue)|continue|load(ing)?|enter|ok|resume)$/;
  function findStart(scope) {
    // Text match first — but portals that substitute a private font write the button text as
    // unreadable codepoints, so the ASCII class/id names are the fallback signal. Real controls
    // and the innermost match win: a wrapper carries the same text/class as the button in it,
    // and clicking the wrapper does nothing.
    function score(e) {
      var t = (e.textContent || "").replace(/\s+/g, " ").trim().toLowerCase();
      if (t && t.length <= 24 && START_RE.test(t)) return 1;
      var cls = (e.id || "") + " " + (e.className && e.className.toString ? e.className.toString() : "");
      if (/(^|[\s_-])(start|play)([\s_-]|$)/i.test(cls) || /button/i.test(cls) && /(start|play)/i.test(cls)) return 2;
      return 0;
    }
    var best = null, bs = 0, ba = 0;
    var els = scope.querySelectorAll("button, a, [role=button], div, span");
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      var s = score(e);
      if (!s) continue;
      if (s >= 2 && e.querySelector("button, a, [role=button]")) continue;
      var r; try { r = e.getBoundingClientRect(); } catch (x) { continue; }
      if (r.width < 16 || r.height < 16) continue;
      var a = r.width * r.height;
      if (s > bs || (s === bs && a > ba)) { bs = s; ba = a; best = e; }
    }
    return best;
  }
  function pressStart() {
    try {
      var W = v().innerWidth || 1, H = v().innerHeight || 1;
      var s = findStart(document);
      if (!s) return;
      var t = (s.textContent || "").replace(/\s+/g, " ").trim().toLowerCase();
      var bigCanvas = false;
      var c = document.querySelector("canvas");
      if (c) { try { var r = c.getBoundingClientRect(); bigCanvas = r.width * r.height >= W * H * 0.4; } catch (e) {} }
      // Once the game's picture is on screen only the strict boot phrases get pressed;
      // "continue"/"resume" belong to a pause menu the child may have opened on purpose.
      if (bigCanvas && !/^(start|play|start game|press start|press any key|click to start|click to play|click to load|click to continue)$/.test(t)) return;
      say("press", s.tagName + ":" + t); // diagnostic: prove the press lands
      try { s.click(); } catch (e) {}
    } catch (e) {}
  }

  say("carve", sweep());
  setInterval(function () {
    try { if (document.hidden) return; } catch (e) {}
    say("carve", sweep());
  }, 3000);
})();
