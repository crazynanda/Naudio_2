#!/usr/bin/env bash
# verify-debug-signing.sh — check that a debug APK is signed with the shared debug key.
#
# Why this script exists:
#  • Without a shared key, the Android SDK makes a throwaway debug key wherever the build runs,
#    so two debug builds never share a signer and Android refuses to put one over the other
#    (INSTALL_FAILED_UPDATE_INCOMPATIBLE) — which forces an uninstall and loses the data of the
#    app.
#  • The key that signs the debug builds lives in `keystore/naudio-debug.keystore` and is the
#    same on every machine and every runner. This script reads the certificate of a built APK and
#    compares it with the certificate of that key, so a build that drifts away from it is caught
#    before it reaches a phone.
#
# Usage:
#   scripts/verify-debug-signing.sh <path-to.apk> [more.apk ...]
#
# The build tools of the Android SDK are found through ANDROID_HOME / ANDROID_SDK_ROOT, or
# through the newest build-tools directory of the SDK.

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "usage: scripts/verify-debug-signing.sh <path-to.apk> [more.apk ...]" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="$ROOT/keystore/naudio-debug.keystore"
ALIAS="naudio-debug"
STOREPASS="naudio-debug"

if [[ ! -f "$KEYSTORE" ]]; then
    echo "ERROR: the shared debug key is missing: $KEYSTORE" >&2
    exit 1
fi

# --- find apksigner -----------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then
    SDK="$HOME/Library/Android/sdk"
    [[ -d "$SDK" ]] || SDK="$HOME/Android/Sdk"
fi

APKSIGNER=""
if [[ -d "$SDK/build-tools" ]]; then
    # The newest build tools first.
    for dir in $(ls -1 "$SDK/build-tools" 2>/dev/null | sort -V -r); do
        for candidate in "$SDK/build-tools/$dir/apksigner" "$SDK/build-tools/$dir/apksigner.bat"; do
            if [[ -f "$candidate" ]]; then
                APKSIGNER="$candidate"
                break 2
            fi
        done
    done
fi

if [[ -z "$APKSIGNER" ]]; then
    echo "ERROR: apksigner was not found. Set ANDROID_HOME to the Android SDK." >&2
    exit 1
fi

# --- the expected fingerprint --------------------------------------------------------------
KEYTOOL=""
for candidate in "${JDK_HOME:-}/bin/keytool" "${JAVA_HOME:-}/bin/keytool" "$(command -v keytool || true)"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
        KEYTOOL="$candidate"
        break
    fi
done

if [[ -z "$KEYTOOL" ]]; then
    echo "ERROR: keytool was not found. Set JAVA_HOME to a JDK." >&2
    exit 1
fi

read_keystore_fingerprint() {
    "$KEYTOOL" -list -v -keystore "$KEYSTORE" -storepass "$STOREPASS" -alias "$ALIAS" 2>/dev/null \
        | grep -i 'SHA256:' \
        | head -1 \
        | tr -d ' :' \
        | sed 's/.*SHA256//' \
        | tr 'A-Z' 'a-z'
}

read_apk_fingerprint() {
    "$APKSIGNER" verify --print-certs "$1" 2>/dev/null \
        | grep -i 'SHA-256 digest:' \
        | head -1 \
        | tr -d ' :' \
        | sed 's/.*digest//' \
        | tr 'A-Z' 'a-z'
}

EXPECTED="$(read_keystore_fingerprint)"
if [[ -z "$EXPECTED" ]]; then
    echo "ERROR: the fingerprint of the shared debug key could not be read." >&2
    exit 1
fi

echo "The shared debug key: $EXPECTED"

STATUS=0
for apk in "$@"; do
    if [[ ! -f "$apk" ]]; then
        echo "ERROR: there is no such file: $apk" >&2
        STATUS=1
        continue
    fi
    ACTUAL="$(read_apk_fingerprint "$apk")"
    if [[ -z "$ACTUAL" ]]; then
        echo "ERROR: the certificate of $apk could not be read." >&2
        STATUS=1
        continue
    fi
    if [[ "$ACTUAL" != "$EXPECTED" ]]; then
        echo "ERROR: $apk is signed with $ACTUAL, not with the shared debug key." >&2
        STATUS=1
        continue
    fi
    echo "OK: $apk is signed with the shared debug key."
done

exit $STATUS
