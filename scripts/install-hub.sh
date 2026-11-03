#!/usr/bin/env sh
# One-command first install for the Scenix hub.
#
# The hub has no npm dependencies: it is a Node process using built-ins only. This script checks for
# a usable Node, creates the household's profiles file if it does not exist, and prints the exact
# command and URL for starting it. It never overwrites an existing profiles.json or data directory.
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
HUB="$ROOT/hub"

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js is not installed." >&2
  echo "Install Node.js 18 or newer from https://nodejs.org and run this again." >&2
  exit 1
fi

MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
if [ "$MAJOR" -lt 18 ]; then
  echo "Node.js $MAJOR is too old. Install Node.js 18 or newer from https://nodejs.org." >&2
  exit 1
fi

if [ ! -d "$HUB" ]; then
  echo "The hub directory was not found at $HUB" >&2
  exit 1
fi

mkdir -p "$HUB/data"

if [ ! -f "$HUB/profiles.json" ]; then
  if [ -f "$HUB/profiles.example.json" ]; then
    cp "$HUB/profiles.example.json" "$HUB/profiles.json"
    echo "Created hub/profiles.json from the example."
  else
    echo "hub/profiles.example.json is missing; cannot create the household profiles file." >&2
    exit 1
  fi
else
  echo "Keeping the existing hub/profiles.json."
fi

echo
echo "Hub is ready (Node $(node -p 'process.versions.node'))."
echo
echo "  Start it:  cd \"$HUB\" && node index.js"
echo "  Open:      http://<this-machine>:8788"
echo "  Firewall:  allow TCP 8788 and UDP 1900 (LAN discovery)"
echo
echo "On the TV: choose the hub in setup, or type http://<this-machine>:8788."
