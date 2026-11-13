# Phone-pairing QR — plan note

TV Settings → This TV → **Connect from phone** (PIN-gated via `requireAdmin()`). Shows a
QR + typed fallback URL. Phone opens web Settings over the same Wi-Fi:

- **Standalone**: QR encodes `http://<tv-lan-ip>:8787` (LocalServer `0.0.0.0:8787`).
  `LoopbackServer.serveWeb()` now serves `assets/web/*` (`/` → `web/index.html`,
  `/ui/*` → `web/*`) alongside `/app/v1` and `/healthz` + `/app/v1/healthz`. Settings
  endpoints already existed in LocalServer (`GET/PUT /app/v1/settings`,
  `POST /app/v1/settings/unlock` → admin session), so the web `adminlock.js` PIN flow
  works unchanged.
- **Hub**: QR encodes the paired hub origin (`:8788` or whatever `Prefs.hubUrl` /
  `HubEndpoint.base` resolves to). No hub code change — `hub/web/js/adminlock.js`
  `ensureSettingsAdmin` already prompts for the household Settings PIN and stores the
  short-lived `X-Scenix-Admin-Session` capability in memory (`api.js`).

QR via ZXing `QRCodeWriter` (byte mode, error-correction M, `com.google.zxing:core:3.5.3`),
rendered to a Bitmap with a white quiet zone. Unsigned? No — the QR encodes only the
public LAN URL; the Settings PIN is still required on the phone web page before any write.

Gates: `scripts/check-phone-pairing.mjs` + updated
`scripts/check-standalone-web-boundary.mjs` (FixtureServer stays API-only). JVM:
`PhonePairingTest` (standalone `:8787` vs hub origin, fallback text).
