#!/bin/zsh
# Title sweep: what /app/v1/play answers for a spread of real titles.
H=${HUB:-http://192.168.0.24:8788}
P=${PROFILE:-1001}
run() {
  local id=$1 type=$2 title=$3 se=$4 ep=$5
  local body
  if [ "$type" = "series" ]; then
    body="{\"identity\":\"$id\",\"type\":\"series\",\"title\":\"$title\",\"season\":$se,\"episode\":$ep}"
  else
    body="{\"identity\":\"$id\",\"type\":\"movie\",\"title\":\"$title\"}"
  fi
  printf '=== %s\n' "$title"
  curl -s -m 180 "$H/app/v1/play?profile=$P" -H 'content-type: application/json' -d "$body" | python3 -c "
import json,sys
d=json.load(sys.stdin)
st = d.get('status') or ('rule:'+str(d.get('droppedByRule'))) or ('err:'+str(d.get('error')))
print('   ', st, '|', str(d.get('detail') or '')[:80])
p = d.get('picked') or {}
if p: print('    picked:', str(p.get('label'))[:58], '| res', p.get('resolution'), '| audio', p.get('audioLanguages'))
for x in (d.get('tried') or [])[:3]: print('     tried:', str(x)[:112])
"
}
run imdb:tt0108778 series "Friends" 1 1
run imdb:tt0903747 series "Breaking Bad" 1 2
run imdb:tt2395586 series "Delhi Crime" 1 1
run imdb:tt10292600 movie "Jawan"
run imdb:tt15337162 movie "Pushpa: The Rule"
run imdb:tt0468944 movie "The Dark Knight"
run imdb:tt0108160 movie "Schindler's List"
run imdb:tt0482571 movie "Pirates of the Caribbean"
run imdb:tt1024014 movie "Goliath"
run imdb:tt32564160 series "Citadel" 1 1
