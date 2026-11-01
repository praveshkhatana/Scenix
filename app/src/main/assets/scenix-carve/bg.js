/*
 * The bridge to the embedder.
 *
 * A content script's port can never reach the app: GeckoView only relays a NATIVE
 * port to the embedder, and runtime.connect from a content script is just a
 * content→background port — with no background page it dies with "Receiving end
 * does not exist" before the app is ever involved. So the background opens the
 * native "carve" port once and relays every message from the content ports.
 */
(function () {
  var seq = 0;
  var appPort = null;
  var lastWhy = null;

  // recomp-style ports ("This page needs cross-origin isolation") sit on hosts too lazy to send
  // COOP/COEP, so their multithreaded builds refuse to run. The app names those hosts over the
  // native port and we answer the page with our own headers — worth trying because require-corp
  // is harmless there: the game's assets are same-origin, and anything cross-origin that dies is
  // analytics or promo art anyway.
  var coiHosts = {};
  function addCoiHosts(list) {
    try { (list || []).forEach(function (h) { if (h) coiHosts[String(h).toLowerCase()] = 1; }); } catch (e) {}
  }

  // Network-level ad kill, twin of GameActivity's shouldInterceptRequest: mirror promo scripts
  // ("related games" trays injected into the game frame), the prebid auction bundle, and the
  // counter/analytics hosts. ETP catches most trackers; these are portal-side extras ETP lets pass.
  var BLOCK_PATH = [/\/files\/scripts\/scr\.js(\?|$)/i, /\/prebid/i];
  var BLOCK_HOST = ["mc.yandex.ru", "mc.yandex.net", "an.yandex.ru", "ssl.google-analytics.com"];
  var bgok = { req1: "off", req2: "off" };
  try {
    browser.webRequest.onBeforeRequest.addListener(function (d) {
      try {
        for (var i = 0; i < BLOCK_PATH.length; i++) if (BLOCK_PATH[i].test(d.url)) return { cancel: true };
        var h = new URL(d.url).hostname.toLowerCase();
        for (var j = 0; j < BLOCK_HOST.length; j++) if (h === BLOCK_HOST[j] || h.endsWith("." + BLOCK_HOST[j])) return { cancel: true };
      } catch (e) {}
      return {};
    }, { urls: ["<all_urls>"] }, ["blocking"]);
    bgok.req1 = "on";
  } catch (e) { bgok.req1 = "err:" + e; }
  try {
    browser.webRequest.onHeadersReceived.addListener(function (d) {
      try {
        var host = new URL(d.url).hostname.toLowerCase();
        if (!coiHosts[host]) return {};
        var hs = d.responseHeaders || [];
        for (var i = 0; i < hs.length; i++) {
          var n = (hs[i].name || "").toLowerCase();
          if (n === "cross-origin-opener-policy" || n === "cross-origin-embedder-policy") return {}; // server fixed it itself
        }
        hs.push({ name: "Cross-Origin-Opener-Policy", value: "same-origin" });
        hs.push({ name: "Cross-Origin-Embedder-Policy", value: "require-corp" });
        relay({ what: "inject", val: d.url });
        return { responseHeaders: hs };
      } catch (e) { return {}; }
    }, { urls: ["<all_urls>"] }, ["blocking", "responseHeaders"]);
    bgok.req2 = "on";
  } catch (e) { bgok.req2 = "err:" + e; }

  function openAppPort() {
    try {
      appPort = browser.runtime.connectNative("carve");
      appPort.onMessage.addListener(function (m) {
        if (m && m.what === "coiHosts") addCoiHosts(m.val);
      });
      appPort.onDisconnect.addListener(function (d) {
        lastWhy = (d && d.error && d.error.message) || "closed";
        appPort = null;
      });
      appPort.postMessage({ what: "bgok", val: "v" + browser.runtime.getManifest().version + " req1=" + bgok.req1 + " req2=" + bgok.req2 });
    } catch (e) {
      appPort = null;
      lastWhy = "threw: " + e;
    }
  }
  openAppPort();
  function relay(m) {
    if (!appPort) openAppPort();
    try {
      if (appPort) {
        m.seq = ++seq;
        appPort.postMessage(m);
      }
    } catch (e) {}
  }
  browser.runtime.onConnect.addListener(function (port) {
    if (port.name !== "carve") return;
    port.onMessage.addListener(relay);
  });
  // The content script's fallback is an unnamed sendMessage; relay it too so a first
  // connectNative that raced engine startup still gets another chance per message.
  browser.runtime.onMessage.addListener(function (m) {
    if (m && typeof m === "object" && m.what) relay(m);
  });
})();
