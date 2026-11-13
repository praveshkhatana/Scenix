#!/usr/bin/env bash
set -euo pipefail

preview_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$preview_root"

swift package describe --type json >/dev/null
swift build
swift test
"$preview_root/scripts/package-app.sh"

app_bundle="$preview_root/build/Scenix.app"
info_plist="$app_bundle/Contents/Info.plist"
executable="$app_bundle/Contents/MacOS/ScenixMac"

test -f "$info_plist"
test -x "$executable"
plutil -lint "$info_plist" >/dev/null
test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundlePackageType' "$info_plist")" = "APPL"
test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$info_plist")" = "ScenixMac"
test ! -e "$app_bundle/Contents/Resources/AppIcon.icns"

# The package-level tests exercise normalized origin parsing. Keep the credential store's scope
# obvious to a lightweight release inspection as well: a global account must never reappear.
grep -q 'readToken(for: origin)' "$preview_root/Sources/ScenixMac/HubSession.swift"
grep -q 'saveToken(token, for: origin)' "$preview_root/Sources/ScenixMac/HubSession.swift"
grep -q 'components.user == nil && components.password == nil' "$preview_root/Sources/ScenixMac/HubSession.swift"
