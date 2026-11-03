#!/usr/bin/env bash
# Capture the design-system catalogue from the connected television.
#   scripts/studio-capture.sh --write      refresh every golden
#   scripts/studio-capture.sh              capture and diff
#   scripts/studio-capture.sh --strict     fail on any missing or changed golden
set -euo pipefail
cd "$(dirname "$0")/.."
exec node design-system/tools/capture.mjs "$@"
