#!/usr/bin/env bash
# Inspect an APK without connecting to a device or attempting an install.
#
# Usage:
#   bash scripts/verify-android-artifact.sh <apk> qa
#   bash scripts/verify-android-artifact.sh <apk> production <installed-version-code>
#
# QA artifacts must use com.scenix.tv.qa. Production candidates must use com.scenix.tv and have a
# versionCode strictly greater than the supplied installed version. The script intentionally prints
# no install command: it is an identity and upgrade-safety gate, not a deployment tool.
set -euo pipefail

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat >&2 <<'EOF'
Usage:
  bash scripts/verify-android-artifact.sh <apk> qa
  bash scripts/verify-android-artifact.sh <apk> production <installed-version-code>

The QA lane accepts only com.scenix.tv.qa, which installs alongside com.scenix.tv.
The production lane accepts only com.scenix.tv and rejects a versionCode that is not newer than
the supplied installed version.
EOF
    exit 64
}

[ "$#" -ge 2 ] || usage

apk=$1
lane=$2
[ -f "$apk" ] || die "APK does not exist: $apk"

case "$lane" in
    qa)
        [ "$#" -eq 2 ] || usage
        expected_package='com.scenix.tv.qa'
        installed_version_code=''
        ;;
    production)
        [ "$#" -eq 3 ] || usage
        expected_package='com.scenix.tv'
        installed_version_code=$3
        case "$installed_version_code" in
            ''|*[!0-9]*) die 'installed-version-code must be a non-negative integer' ;;
        esac
        ;;
    *)
        usage
        ;;
esac

if command -v aapt >/dev/null 2>&1; then
    aapt_bin=$(command -v aapt)
else
    sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    if [ -z "$sdk_root" ]; then
        script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
        local_properties="$script_dir/../app/local.properties"
        if [ -r "$local_properties" ]; then
            sdk_root=$(awk -F= '$1 == "sdk.dir" { print substr($0, index($0, "=") + 1); exit }' "$local_properties")
        fi
    fi
    [ -n "$sdk_root" ] || die 'aapt was not found; set ANDROID_HOME or ANDROID_SDK_ROOT'
    aapt_bin=''
    for candidate in "$sdk_root"/build-tools/*/aapt; do
        [ -x "$candidate" ] || continue
        aapt_bin=$candidate
    done
    [ -n "$aapt_bin" ] || die "aapt was not found under $sdk_root/build-tools"
fi

package_line=$("$aapt_bin" dump badging "$apk" 2>/dev/null | awk '/^package: / { print; exit }')
[ -n "$package_line" ] || die 'APK manifest could not be read'

package_id=$(printf '%s\n' "$package_line" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
version_code=$(printf '%s\n' "$package_line" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")
version_name=$(printf '%s\n' "$package_line" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")

[ -n "$package_id" ] || die 'APK package id is missing'
[ -n "$version_code" ] || die 'APK versionCode is missing'
[ -n "$version_name" ] || die 'APK versionName is missing'
case "$version_code" in
    *[!0-9]*) die "APK versionCode is not a non-negative integer: $version_code" ;;
esac

[ "$package_id" = "$expected_package" ] || die \
    "refusing ${lane} guidance: expected package $expected_package, found $package_id"

if [ "$lane" = 'production' ] && [ "$version_code" -le "$installed_version_code" ]; then
    die "refusing production guidance: artifact versionCode $version_code is not newer than installed $installed_version_code"
fi

if [ "$lane" = 'qa' ]; then
    printf 'Verified QA artifact: %s %s (versionCode %s).\n' "$package_id" "$version_name" "$version_code"
    printf 'It is side-by-side with com.scenix.tv; no device was contacted and no install was attempted.\n'
else
    printf 'Verified production candidate: %s %s (versionCode %s > installed %s).\n' \
        "$package_id" "$version_name" "$version_code" "$installed_version_code"
    printf 'No device was contacted and no install was attempted.\n'
fi
