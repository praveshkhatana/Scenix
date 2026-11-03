#!/usr/bin/env bash
# Scan local files with the VirusTotal API v3.
#
# Requires only bash 3.2+, curl, and python3 (no jq).
#
# Usage:
#   VT_API_KEY=... ./scripts/virustotal-scan.sh path/to/app-release.apk [more-files ...]
#
# Environment:
#   VT_API_KEY        required; free key from https://www.virustotal.com/gui/my-apikey
#   VT_BASE_URL       API base, default https://www.virustotal.com
#   VT_GUI_URL        GUI base for the report link, default https://www.virustotal.com
#   VT_POLL_INTERVAL  seconds between analysis polls (default 20)
#   VT_POLL_TIMEOUT   seconds to wait for an analysis to finish (default 900)
#   VT_HTTP_TIMEOUT   seconds for metadata/analysis requests (default 60)
#   VT_UPLOAD_TIMEOUT seconds for uploads (default 900)
#
# Exit codes:
#   0  clean (no malicious or suspicious engines)
#   1  malicious > 0 or suspicious > 0
#   2  usage/configuration error (including a missing VT_API_KEY)
#   3  API, network, parse, or polling timeout error
#
# The key is read from the environment and is never written to disk or printed.

set -euo pipefail

VT_BASE_URL="${VT_BASE_URL:-https://www.virustotal.com}"
VT_BASE_URL="${VT_BASE_URL%/}"
VT_GUI_URL="${VT_GUI_URL:-https://www.virustotal.com}"
VT_GUI_URL="${VT_GUI_URL%/}"
VT_POLL_INTERVAL="${VT_POLL_INTERVAL:-20}"
VT_POLL_TIMEOUT="${VT_POLL_TIMEOUT:-900}"
VT_HTTP_TIMEOUT="${VT_HTTP_TIMEOUT:-60}"
VT_UPLOAD_TIMEOUT="${VT_UPLOAD_TIMEOUT:-900}"

ANALYSIS_ID=""
FINAL_STATS=""

die() {
  printf 'error: %s\n' "$*" >&2
  exit 2
}

api_fail() {
  printf 'error: %s\n' "$*" >&2
  exit 3
}

validate_positive_int() {
  case "${2:-}" in
    ''|*[!0-9]*) die "$1 must be a positive integer (got '${2:-}')" ;;
  esac
  if [ "$2" -le 0 ]; then
    die "$1 must be greater than zero"
  fi
}

# Read JSON from stdin and print the value at a dotted path. Exits 4 when the
# value is absent, so callers can treat a missing analysis id as an API error.
json_get() {
  python3 -c '
import json, sys

path = sys.argv[1].split(".")
try:
    doc = json.load(sys.stdin)
except Exception:
    sys.exit(4)

value = doc
for part in path:
    if isinstance(value, list):
        try:
            value = value[int(part)]
        except (ValueError, IndexError):
            sys.exit(4)
    elif isinstance(value, dict):
        if part not in value:
            sys.exit(4)
        value = value[part]
    else:
        sys.exit(4)

if value is None:
    sys.exit(4)
if isinstance(value, bool):
    print("true" if value else "false")
elif isinstance(value, (dict, list)):
    print(json.dumps(value))
else:
    print(value)
' "$1"
}

# Read an analysis response from stdin and print normalized key=value lines.
# The shell stays bash-3.2 compatible; parsing lives in python3.
parse_analysis() {
  python3 -c '
import json, sys

try:
    doc = json.load(sys.stdin)
except Exception:
    sys.exit(4)

data = doc.get("data") or {}
attrs = data.get("attributes") or {}
stats = attrs.get("stats") or {}
status = attrs.get("status") or "unknown"

print("status=%s" % status)
for key in ("harmless", "malicious", "suspicious", "undetected",
            "timeout", "failure", "type-unsupported", "confirmed-timeout"):
    try:
        value = int(stats.get(key, 0))
    except (TypeError, ValueError):
        value = 0
    print("%s=%d" % (key, value))
'
}

# Extract one key from parse_analysis output without sed/head pipelines.
get_stat() {
  local line prefix
  prefix="$2="
  while IFS= read -r line; do
    case "$line" in
      "$prefix"*)
        printf '%s\n' "${line#"$prefix"}"
        return 0
        ;;
    esac
  done <<EOF
$1
EOF
  return 1
}

to_int() {
  case "${1:-}" in
    ''|*[!0-9]*) printf '0\n' ;;
    *) printf '%s\n' "$1" ;;
  esac
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    printf 'unavailable\n'
  fi
}

# Upload a file and leave the analysis id in ANALYSIS_ID.
upload_file() {
  local file size url resp code detail

  file="$1"
  size=$(wc -c < "$file" | tr -d ' ')

  if [ "$size" -le 33554432 ]; then
    url="${VT_BASE_URL}/api/v3/files"
    printf 'uploading directly (%s bytes, <= 32 MiB)\n' "$size" >&2
  else
    if [ "$size" -gt 681574400 ]; then
      api_fail "file is larger than VirusTotal's 650 MiB upload limit"
    fi

    printf 'file is larger than 32 MiB; requesting a large-file upload URL\n' >&2
    if ! resp=$(curl -sS --max-time "$VT_HTTP_TIMEOUT" \
      -H "x-apikey: ${VT_API_KEY}" \
      "${VT_BASE_URL}/api/v3/files/upload_url"); then
      api_fail "could not request a large-file upload URL"
    fi
    if ! url=$(printf '%s' "$resp" | json_get "data"); then
      api_fail "VirusTotal did not return a large-file upload URL"
    fi
    case "$url" in
      https://*) ;;
      *) api_fail "VirusTotal returned an unexpected upload URL" ;;
    esac
  fi

  if ! resp=$(curl -sS --max-time "$VT_UPLOAD_TIMEOUT" \
    -X POST \
    -H "x-apikey: ${VT_API_KEY}" \
    -F "file=@${file}" \
    "$url"); then
    api_fail "upload request failed"
  fi

  if ! ANALYSIS_ID=$(printf '%s' "$resp" | json_get "data.id"); then
    code=""
    detail=""
    code=$(printf '%s' "$resp" | json_get "error.code" 2>/dev/null || true)
    detail=$(printf '%s' "$resp" | json_get "error.message" 2>/dev/null || true)
    if [ -n "$code" ] || [ -n "$detail" ]; then
      api_fail "upload failed: ${code:-error}${detail:+ - $detail}"
    fi
    api_fail "upload failed: VirusTotal returned no analysis id"
  fi
  if [ -z "$ANALYSIS_ID" ]; then
    api_fail "upload failed: VirusTotal returned an empty analysis id"
  fi
}

# Poll an analysis until it completes. On success FINAL_STATS holds its stats.
poll_analysis() {
  local id deadline now resp parsed status
  id="$1"
  deadline=$(( $(date +%s) + VT_POLL_TIMEOUT ))

  while :; do
    if ! resp=$(curl -sS --max-time "$VT_HTTP_TIMEOUT" \
      -H "x-apikey: ${VT_API_KEY}" \
      "${VT_BASE_URL}/api/v3/analyses/${id}"); then
      api_fail "analysis request failed"
    fi

    if ! parsed=$(printf '%s' "$resp" | parse_analysis); then
      api_fail "could not parse the VirusTotal analysis response"
    fi

    status=$(get_stat "$parsed" status || true)
    case "$status" in
      completed)
        FINAL_STATS="$parsed"
        return 0
        ;;
      queued|in-progress)
        ;;
      *)
        api_fail "analysis ${id} returned unexpected status '${status:-empty}'"
        ;;
    esac

    now=$(date +%s)
    if [ "$now" -ge "$deadline" ]; then
      printf 'error: timed out after %ss waiting for analysis %s\n' "$VT_POLL_TIMEOUT" "$id" >&2
      exit 3
    fi

    printf 'analysis %s: %s; polling again in %ss\n' "$id" "$status" "$VT_POLL_INTERVAL" >&2
    sleep "$VT_POLL_INTERVAL"
  done
}

scan_one() {
  local file size sha stats analysis_id
  local malicious suspicious harmless undetected timeout failure unsupported confirmed

  file="$1"
  size=$(wc -c < "$file" | tr -d ' ')
  sha=$(sha256_of "$file")

  printf '\n== VirusTotal: %s (%s bytes)\n' "$file" "$size"

  ANALYSIS_ID=""
  upload_file "$file"
  analysis_id="$ANALYSIS_ID"
  printf 'analysis id: %s\n' "$analysis_id"

  FINAL_STATS=""
  poll_analysis "$analysis_id"
  stats="$FINAL_STATS"

  malicious=$(to_int "$(get_stat "$stats" malicious || true)")
  suspicious=$(to_int "$(get_stat "$stats" suspicious || true)")
  harmless=$(to_int "$(get_stat "$stats" harmless || true)")
  undetected=$(to_int "$(get_stat "$stats" undetected || true)")
  timeout=$(to_int "$(get_stat "$stats" timeout || true)")
  failure=$(to_int "$(get_stat "$stats" failure || true)")
  unsupported=$(to_int "$(get_stat "$stats" type-unsupported || true)")
  confirmed=$(to_int "$(get_stat "$stats" confirmed-timeout || true)")

  printf '\nVirusTotal summary\n'
  printf '  file:              %s\n' "$file"
  printf '  size:              %s bytes\n' "$size"
  printf '  sha256:            %s\n' "$sha"
  printf '  analysis id:       %s\n' "$analysis_id"
  if [ "$sha" != "unavailable" ]; then
    printf '  report:            %s/gui/file/%s\n' "$VT_GUI_URL" "$sha"
  fi
  printf '  malicious:         %s\n' "$malicious"
  printf '  suspicious:        %s\n' "$suspicious"
  printf '  harmless:          %s\n' "$harmless"
  printf '  undetected:        %s\n' "$undetected"
  printf '  timeout:           %s\n' "$timeout"
  printf '  confirmed-timeout: %s\n' "$confirmed"
  printf '  failure:           %s\n' "$failure"
  printf '  type-unsupported:  %s\n' "$unsupported"

  if [ "$malicious" -gt 0 ] || [ "$suspicious" -gt 0 ]; then
    printf 'RESULT: FAIL - %s malicious and %s suspicious engine(s)\n' "$malicious" "$suspicious"
    return 1
  fi

  printf 'RESULT: CLEAN - no malicious or suspicious engines\n'
  return 0
}

main() {
  command -v curl >/dev/null 2>&1 || die "curl is required"
  command -v python3 >/dev/null 2>&1 || die "python3 is required"

  if [ "${VT_API_KEY:-}" = "" ]; then
    die "VT_API_KEY is not set. Export a VirusTotal API key, e.g. 'VT_API_KEY=... $0 FILE'."
  fi
  if [ "$#" -lt 1 ]; then
    die "usage: VT_API_KEY=... $0 FILE [FILE ...]"
  fi

  validate_positive_int "VT_POLL_INTERVAL" "$VT_POLL_INTERVAL"
  validate_positive_int "VT_POLL_TIMEOUT" "$VT_POLL_TIMEOUT"
  validate_positive_int "VT_HTTP_TIMEOUT" "$VT_HTTP_TIMEOUT"
  validate_positive_int "VT_UPLOAD_TIMEOUT" "$VT_UPLOAD_TIMEOUT"

  local overall file rc
  overall=0

  for file in "$@"; do
    [ -f "$file" ] || die "not a file: $file"
    [ -r "$file" ] || die "not readable: $file"
    if scan_one "$file"; then
      :
    else
      rc=$?
      if [ "$overall" -eq 0 ]; then
        overall=$rc
      fi
    fi
  done

  if [ "$overall" -ne 0 ]; then
    if [ "$overall" -eq 1 ]; then
      printf '\nOne or more files were flagged by VirusTotal.\n' >&2
    fi
    exit "$overall"
  fi

  printf '\nAll %s file(s) clean.\n' "$#"
}

main "$@"
