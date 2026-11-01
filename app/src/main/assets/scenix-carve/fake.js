/*
 * Runs in the page's OWN world at document_start — earlier than any page script, and
 * content scripts otherwise sit in an isolated world the page can't see.
 *
 * Some ports refuse to boot when crossOriginIsolated reads false, but on this engine
 * SharedArrayBuffer builds fine unisolated and the browser enforces nothing the flag
 * promises. The refusal is only a JS check, so the page is simply told it is isolated.
 */
try {
  if (!window.crossOriginIsolated)
    Object.defineProperty(window, "crossOriginIsolated", { get: function () { return true; } });
} catch (e) {}
