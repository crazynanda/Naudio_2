#!/usr/bin/env bash
# read-installed-signature.sh — read the package, the version and the signing
# certificate of every Naudio installation on a phone.
#
# Why this script exists:
#
#   The Android installer refuses an update with "App not installed as package
#   conflicts with an existing package" when the signer of the installed app is
#   not the signer of the new APK. The message does not say which signer the
#   installed app carries. This script reads it - off the APK the phone actually
#   has - and prints it beside the signer the new artifacts carry, so the
#   conflict is named instead of guessed at.
#
# Usage:
#   scripts/read-installed-signature.sh [apk-output-directory]
#
# A phone must be connected and adb must see it (adb devices). The app's APK is
# pulled from the phone and read with apksigner; nothing on the phone is
# changed.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

OUT="${1:-$ROOT/build/installed-apks}"
mkdir -p "$OUT"

# --- find adb ------------------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then
    SDK="$LOCALAPPDATA/Android/Sdk"
fi
ADB=""
for candidate in "$SDK/platform-tools/adb.exe" "$SDK/platform-tools/adb"; do
    [[ -f "$candidate" ]] && ADB="$candidate" && break
done
[[ -n "$ADB" ]] || { echo "ERROR: adb was not found under $SDK. Set ANDROID_HOME." >&2; exit 1; }

# --- find apksigner -----------------------------------------------------------------------
APKSIGNER=""
for dir in $(ls -1 "$SDK/build-tools" 2>/dev/null | sort -V -r); do
    for candidate in "$SDK/build-tools/$dir/apksigner.bat" "$SDK/build-tools/$dir/apksigner"; do
        [[ -f "$candidate" ]] && APKSIGNER="$candidate" && break 2
    done
done
[[ -n "$APKSIGNER" ]] || { echo "ERROR: apksigner was not found under $SDK." >&2; exit 1; }

echo "== connected devices =="
"$ADB" devices
echo

echo "== every Naudio (and vivo-clone) package on the phone =="
PACKAGES=$("$ADB" shell pm list packages | tr -d '\r' | sed 's/^package://' | grep -E 'maxrave|com\.spotify\.music' || true)
if [[ -z "$PACKAGES" ]]; then
    echo "  (none found - check the phone of every user: adb shell pm list packages --user 10)"
fi
echo

for pkg in $PACKAGES; do
    echo "=============================================================="
    echo "PACKAGE: $pkg"
    echo "--------------------------------------------------------------"

    echo "-- version (dumpsys) --"
    "$ADB" shell dumpsys package "$pkg" | tr -d '\r' | grep -E 'versionCode|versionName|firstInstallTime|lastUpdateTime' | head -6
    echo

    echo "-- APK path (pm path) --"
    APK_PATH=$("$ADB" shell pm path "$pkg" | tr -d '\r' | sed 's/^package://' | head -1)
    echo "  $APK_PATH"
    echo

    if [[ -n "$APK_PATH" ]]; then
        LOCAL="$OUT/$pkg.apk"
        echo "-- pulling the installed APK (read only) --"
        "$ADB" pull "$APK_PATH" "$LOCAL" && echo "  saved: $LOCAL"
        echo

        echo "-- signer of the INSTALLED app (apksigner) --"
        "$APKSIGNER" verify --print-certs "$LOCAL" 2>/dev/null | grep -iE 'SHA-256 digest|signer'
        echo
    fi
done

echo "=============================================================="
echo "The signer the CI artifacts carry (the shared debug key):"
echo "  6c5113c44b809a7d8a96ee3752df454bdff8283d202cb99565c17bb087052001"
echo
echo "If an installed signer above differs from this one, this exact"
echo "difference is the conflict the installer reports."
