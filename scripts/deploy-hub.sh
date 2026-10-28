#!/usr/bin/env bash
# Deploy the plugin hub to the always-on server and restart it.
#   usage: HUB_HOST=<hub-host> HUB_USER=<hub-user> ./deploy-hub.sh
# Nothing here edits the hub code: it copies hub/ (plus any new plugin file
# that appears in providers/ rows/ rules/) and restarts the same process.
set -euo pipefail

HOST="${HUB_HOST:?set HUB_HOST}"
USER_="${HUB_USER:?set HUB_USER}"
PORT="${HUB_PORT:-8788}"
SCHEME="${HUB_SCHEME:-http}"
GATEWAY="${GATEWAY:-http}"
TORR_URL="${TORR_URL:-http://127.0.0.1:8090}"
SRC="$(cd "$(dirname "$0")/../hub" && pwd)"
DEST="tvhub"

# TLS: pass the two remote paths through only when both are set, so a plain-HTTP install never gets
# an empty TLS_CERT that the hub would refuse as a half-configuration. HUB_INSECURE=1 adds -k for a
# self-signed certificate during setup.
TLS_ENV=""
if [ -n "${TLS_CERT:-}" ] && [ -n "${TLS_KEY:-}" ]; then
    TLS_ENV="TLS_CERT=$TLS_CERT TLS_KEY=$TLS_KEY"
fi
AUTH_ENV=""
[ -n "${HUB_TOKEN:-}" ] && AUTH_ENV="HUB_TOKEN=$(printf %q "$HUB_TOKEN")"
CURL="curl -s"
[ "${HUB_INSECURE:-0}" = "1" ] && CURL="curl -sk"

echo "== copying $SRC -> $USER_@$HOST:~/$DEST"
# library.json / progress.json / addons.json survive a deploy: they are the server's memory, not code.
rsync -a --delete \
  --exclude 'data/' \
  --exclude 'media/' \
  --exclude 'profiles.json' \
  --exclude 'node_modules/' \
  --exclude '*.log' \
  "$SRC/" "$USER_@$HOST:~/$DEST/"

echo "== restarting"
ssh "$USER_@$HOST" bash -s <<EOF
cd ~/$DEST
# Kill only OUR hub: the process whose working directory is ~/$DEST. This box runs other services and a
# bare 'pkill -f node' would take them down too.
for pid in \$(pgrep -f "node index.js" 2>/dev/null || true); do
  if [ "\$(readlink -f /proc/\$pid/cwd 2>/dev/null)" = "\$HOME/$DEST" ]; then
    echo "  stopping old hub pid \$pid"; kill \$pid
  fi
done
sleep 1
setsid nohup env LOG_REQUESTS=1 PORT=$PORT GATEWAY=$GATEWAY TORR_URL=$TORR_URL \\
    $TLS_ENV $AUTH_ENV YT_BIN=\$HOME/ytv/bin/yt-dlp YT_JS=/usr/bin/node \\
    node index.js > hub.log 2>&1 < /dev/null &
sleep 4
$CURL "$SCHEME://127.0.0.1:$PORT/healthz" || tail -30 hub.log
EOF

echo
echo "== from off-box"
$CURL "$SCHEME://$HOST:$PORT/healthz" || true
echo
