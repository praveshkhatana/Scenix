# Scenix Mac preview

This is a buildable macOS desktop preview for **Hub mode**. It supplies a native SwiftUI connection
screen, validates the Hub's `/healthz` endpoint, remembers the endpoint in `UserDefaults`, and stores an
optional HTTP Basic access token in the macOS Keychain. After pairing, it hosts the Hub's existing web
surface in `WKWebView`, so the catalog, profiles, settings, add-ons, selected theme, and browser playback
behavior remain the real Hub implementation.

It is deliberately not presented as a native media client or a standalone client:

- It does **not** run or install a Hub.
- It does **not** include a native media decoder.
- It does **not** include the Android standalone P2P engine, cache, local library, or standalone mode.
- Playback stays on the Hub's browser-compatible web/remux path.

## Requirements

- macOS 13 or newer
- Xcode command-line tools / Swift 6
- A reachable Hub that you operate

## Build and verify

```sh
cd macos/ScenixMac
./scripts/verify.sh
```

The verification script runs package discovery and `swift build`; it does not start a Hub or contact a
network service. It also builds a release executable and inspects a local, unsigned app bundle at
`build/Scenix.app`; verification never launches that bundle.

To launch the preview after a successful build:

```sh
swift run ScenixMac
```

To create just the local bundle:

```sh
./scripts/package-app.sh
```

This creates `build/Scenix.app` with `Contents/Info.plist` and the release `ScenixMac` executable. It has
no bundled icon, is intentionally unsigned, and is for local developer preview only.

Enter the Hub root address, for example `http://192.168.0.24:8788`. If the Hub has `HUB_TOKEN` enabled,
enter the token in the optional field. The app validates `/healthz` and confirms that the protected web
surface can open before loading the Hub page. The endpoint is saved in `UserDefaults`; the token is saved
only in Keychain after a successful connection.

## Security and scope

For a Hub outside a trusted LAN, use HTTPS and the Hub's access token. Do not expose an unauthenticated
Hub to the public internet. The local preview permits HTTP because many owner-operated LAN Hubs use it;
that is a broad platform transport exception, not a security promise for remote connections. This preview
keeps its embedded browser on the selected Hub origin; a top-level or new-window navigation to another
HTTP(S) origin opens in the system browser instead of replacing the Hub surface. Access tokens are stored
in Keychain under the normalized Hub origin, so a token saved for one Hub is never sent to another address.

The package can create an unsigned local `.app` preview, but it is not signed, notarized, or a public
distribution artifact. Packaging for distribution, notarization, a native player strategy, and a
standalone macOS backend are separate delivery work.
