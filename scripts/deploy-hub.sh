#!/usr/bin/env bash
# Deploy the plugin hub to the always-on server and restart it.
#   usage: HUB_HOST=<hub-host> HUB_USER=nagraj ./deploy-hub.sh
# Nothing here edits the hub code: it copies hub/ (plus any new plugin file
# that appears in providers/ rows/ rules/) and restarts the same process.
set -euo pipefail

HOST="${HUB_HOST:?set HUB_HOST}"
USER_="${HUB_USER:?set HUB_USER}"
PORT="${HUB_PORT:-8788}"
SRC="$(cd "$(dirname "$0")/hub" && pwd)"
DEST="tvhub"

echo "== copying $SRC -> $USER_@$HOST:~/$DEST"
# library.json / progress.json / addons.json survive a deploy: they are the server's memory, not code.
rsync -a --delete \
  --exclude 'data/' \
  --exclude 'media/' \
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
setsid nohup env LOG_REQUESTS=1 PORT=$PORT \\
    YT_BIN=\$HOME/ytv/bin/yt-dlp YT_JS=/usr/bin/node \\
    node index.js > hub.log 2>&1 < /dev/null &
sleep 4
curl -s http://127.0.0.1:$PORT/healthz || tail -30 hub.log
EOF

echo
echo "== from off-box"
curl -s "http://$HOST:$PORT/healthz" || true
echo
