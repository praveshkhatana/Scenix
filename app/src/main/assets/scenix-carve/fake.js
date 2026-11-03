/*
 * Runs in the page's OWN world at document_start — earlier than any page script, and
 * content scripts otherwise sit in an isolated world the page can't see.
 *
 * Two jobs, both about what a TV can and can't say to a web page:
 *
 * 1. Some ports refuse to boot when crossOriginIsolated reads false, but on this engine
 *    SharedArrayBuffer builds fine unisolated and the browser enforces nothing the flag
 *    promises. The refusal is only a JS check, so the page is simply told it is isolated.
 *
 * 2. Most portal games play with finger swipes and never look at arrow keys, and a TV
 *    remote has no fingers. So the remote IS the finger: arrows become swipe gestures and
 *    OK becomes a tap on the game's biggest canvas — but only when the page itself left
 *    the key unconsumed, so games that already read the keyboard keep their own hands.
 */
try {
  if (!window.crossOriginIsolated)
    Object.defineProperty(window, "crossOriginIsolated", { get: function () { return true; } });
} catch (e) {}

(function () {
  var last = 0;

  function stage() {
    // The game lives on its biggest canvas; fall back to the document for canvas-free ports.
    var best = null, area = 0;
    try {
      var cs = document.querySelectorAll("canvas");
      for (var i = 0; i < cs.length; i++) {
        var r = cs[i].getBoundingClientRect();
        var a = r.width * r.height;
        if (a > area) { area = a; best = cs[i]; }
      }
    } catch (e) {}
    return best || document.body || document.documentElement;
  }

  function touch(el, pts) {
    return pts.map(function (p) {
      return new Touch({ identifier: 1, target: el, clientX: p[0], clientY: p[1] });
    });
  }

  function fire(el, kind, list) {
    try {
      el.dispatchEvent(new TouchEvent(kind, {
        bubbles: true, cancelable: true,
        touches: kind === "touchend" ? [] : list,
        targetTouches: kind === "touchend" ? [] : list,
        changedTouches: list,
      }));
    } catch (e) {}
  }

  // A finger along pts, move steps ~30ms apart, then lift. Pages that read pointer or
  // mouse instead of touch still see the press itself through the events they know.
  function swipe(el, pts) {
    var first = touch(el, [pts[0]]);
    fire(el, "touchstart", first);
    for (var s = 1; s < pts.length; s++) {
      (function (i) {
        setTimeout(function () {
          var t = touch(el, [pts[i]]);
          fire(el, "touchmove", t);
          if (i === pts.length - 1) fire(el, "touchend", t);
        }, i * 30);
      })(s);
    }
  }

  function tap(el) {
    var r = el.getBoundingClientRect();
    var c = [[r.left + r.width / 2, r.top + r.height / 2]];
    fire(el, "touchstart", touch(el, c));
    setTimeout(function () { fire(el, "touchend", touch(el, c)); }, 60);
  }

  window.addEventListener("keydown", function (e) {
    if (e.repeat) return;
    var k = e.key;
    if (k !== "ArrowLeft" && k !== "ArrowRight" && k !== "ArrowUp" && k !== "ArrowDown" &&
        k !== "Enter") return;
    // If the page grabbed the key itself it reads the keyboard — no fake finger needed.
    setTimeout(function () {
      if (e.defaultPrevented) return;
      var now = Date.now();
      if (now - last < 180) return;
      last = now;
      try {
        var el = stage(), r = el.getBoundingClientRect();
        if (k === "Enter") { tap(el); return; }
        var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        var dx = Math.min(r.width * 0.3, 220), dy = Math.min(r.height * 0.3, 220);
        var p = [cx, cy];
        if (k === "ArrowLeft")  p = [cx + dx, cy, cx - dx, cy];
        if (k === "ArrowRight") p = [cx - dx, cy, cx + dx, cy];
        if (k === "ArrowUp")    p = [cx, cy + dy, cx, cy - dy];
        if (k === "ArrowDown")  p = [cx, cy - dy, cx, cy + dy];
        var pts = [];
        for (var i = 0; i <= 4; i++)
          pts.push([p[0] + (p[2] - p[0]) * i / 4, p[1] + (p[3] - p[1]) * i / 4]);
        swipe(el, pts);
      } catch (t) {}
    }, 0);
  }, true);
})();
