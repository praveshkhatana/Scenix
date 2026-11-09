#!/usr/bin/env bash
set -euo pipefail

preview_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_bundle="$preview_root/build/Scenix.app"
info_plist="$preview_root/Resources/Info.plist"

cd "$preview_root"

swift build -c release
bin_dir="$(swift build -c release --show-bin-path)"
executable="$bin_dir/ScenixMac"

if [[ ! -x "$executable" ]]; then
    echo "Release executable was not produced: $executable" >&2
    exit 1
fi
if [[ ! -f "$info_plist" ]]; then
    echo "Bundle metadata is missing: $info_plist" >&2
    exit 1
fi

# This is a generated preview artifact. The exact target is kept inside this package's ignored build/
# directory; no source files, user applications, or household services are removed or touched.
rm -rf "$app_bundle"
mkdir -p "$app_bundle/Contents/MacOS"
cp "$info_plist" "$app_bundle/Contents/Info.plist"
cp "$executable" "$app_bundle/Contents/MacOS/ScenixMac"
chmod u+x "$app_bundle/Contents/MacOS/ScenixMac"

plutil -lint "$app_bundle/Contents/Info.plist" >/dev/null
if [[ ! -x "$app_bundle/Contents/MacOS/ScenixMac" ]]; then
    echo "Bundle executable is not executable" >&2
    exit 1
fi

echo "Packaged unsigned local preview: $app_bundle"
