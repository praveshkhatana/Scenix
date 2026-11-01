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
  function openAppPort() {
    try {
      appPort = browser.runtime.connectNative("carve");
      appPort.onDisconnect.addListener(function (d) {
        lastWhy = (d && d.error && d.error.message) || "closed";
        appPort = null;
      });
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
