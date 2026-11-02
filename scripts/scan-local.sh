#!/usr/bin/env bash
# Run the security scanners that are installed on this machine.
#
# This is the local counterpart to .github/workflows/security.yml. It never
# installs anything: tools that are absent are reported as SKIP and the script
# continues. It exits non-zero if a tool that did run reported findings.
#
# Tools:
#   gitleaks          full Git history (or use a PR range in CI)
#   semgrep           p/security-audit + p/owasp-top-ten + p/secrets
#   npm audit         only when hub/package.json and a lockfile exist
#
# Usage: ./scripts/scan-local.sh
#
# Requires bash 3.2+ (the default /bin/bash on macOS).

set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
cd "$ROOT" || exit 1

ran=0
skipped=0
failed=0

record_pass() {
  ran=$((ran + 1))
  printf 'PASS  %s\n' "$1"
}

record_fail() {
  ran=$((ran + 1))
  failed=$((failed + 1))
  printf 'FAIL  %s\n' "$1"
}

record_skip() {
  skipped=$((skipped + 1))
  printf 'SKIP  %s (%s)\n' "$1" "$2"
}

printf 'Scenix local security scan\n'
printf 'Repository: %s\n' "$ROOT"

# ---- gitleaks -----------------------------------------------------------------------------------
printf '\n== gitleaks ==\n'
if ! command -v gitleaks >/dev/null 2>&1; then
  record_skip "gitleaks" "not installed"
elif ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  record_skip "gitleaks" "not a Git checkout"
else
  if gitleaks git --help >/dev/null 2>&1; then
    if gitleaks git --redact --verbose --no-banner .; then
      record_pass "gitleaks (Git history)"
    else
      record_fail "gitleaks (Git history)"
    fi
  else
    if gitleaks detect --source . --redact --verbose --no-banner; then
      record_pass "gitleaks (Git history)"
    else
      record_fail "gitleaks (Git history)"
    fi
  fi
fi

# ---- semgrep ------------------------------------------------------------------------------------
printf '\n== semgrep ==\n'
if ! command -v semgrep >/dev/null 2>&1; then
  record_skip "semgrep" "not installed"
else
  if semgrep scan \
    --config p/security-audit \
    --config p/owasp-top-ten \
    --config p/secrets \
    --severity ERROR \
    --error \
    --metrics=off \
    --exclude app/build \
    --exclude app/.gradle \
    --exclude hub/node_modules \
    --exclude hub/data \
    --exclude hub/media \
    --exclude shots \
    --exclude refs \
    --exclude qa \
    --exclude design \
    --exclude design-contracts \
    --exclude "*.apk"; then
    record_pass "semgrep"
  else
    record_fail "semgrep"
  fi
fi

# ---- npm audit ----------------------------------------------------------------------------------
printf '\n== npm audit ==\n'
if [ ! -f "$ROOT/hub/package.json" ]; then
  record_skip "npm audit" "no hub/package.json"
elif ! command -v npm >/dev/null 2>&1; then
  record_skip "npm audit" "npm not installed"
elif [ ! -f "$ROOT/hub/package-lock.json" ]; then
  record_skip "npm audit" "no hub/package-lock.json"
else
  if (cd "$ROOT/hub" && npm audit --audit-level=high); then
    record_pass "npm audit (hub)"
  else
    record_fail "npm audit (hub)"
  fi
fi

# ---- summary ------------------------------------------------------------------------------------
printf '\n== summary ==\n'
printf 'ran: %d  skipped: %d  failed: %d\n' "$ran" "$skipped" "$failed"

if [ "$failed" -ne 0 ]; then
  printf 'Result: FAIL\n'
  exit 1
fi

if [ "$ran" -eq 0 ]; then
  printf 'Result: no scanners installed; nothing to check.\n'
  exit 0
fi

printf 'Result: PASS\n'
exit 0
