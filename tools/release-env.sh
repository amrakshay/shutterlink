#!/bin/bash
# Sets up the release signing environment from the macOS Keychain.
#
#   source tools/release-env.sh && ./gradlew :app:assembleRelease
#
# There is deliberately no keystore and no password anywhere in this repository.
# It is public: a signing key committed here would let anyone build an APK that
# Android accepts as an upgrade to this one, and a published key cannot be taken
# back. CI signs releases from GitHub Actions secrets instead, and this script is
# for signing locally.
#
# The Keychain holds both items, so nothing has to be remembered or filed away:
#
#   shutterlink-keystore-password   the store and key password
#   shutterlink-keystore-base64     the keystore itself
#
# Both are under the account "shutterlink" in the login keychain, which syncs
# through iCloud Keychain if that is on — which is also the backup.
#
# CONTRIBUTING.md has the one-time keytool/security commands that create them.
set -euo pipefail

ACCOUNT=shutterlink
KEYSTORE_DIR="$HOME/.shutterlink"
KEYSTORE="$KEYSTORE_DIR/release.keystore"

keychain() {
    security find-generic-password -a "$ACCOUNT" -s "$1" -w 2>/dev/null || true
}

PASSWORD="$(keychain shutterlink-keystore-password)"
if [ -z "$PASSWORD" ]; then
    echo "No signing password in the Keychain." >&2
    echo "Expected account '$ACCOUNT', service 'shutterlink-keystore-password'." >&2
    echo "See the 'Creating the signing key' section of CONTRIBUTING.md." >&2
    return 1 2>/dev/null || exit 1
fi

# Rebuild the keystore from the Keychain when it is not on disk — a new machine,
# or a cleaned home directory. The file is a cache; the Keychain is the source.
if [ ! -f "$KEYSTORE" ]; then
    ENCODED="$(keychain shutterlink-keystore-base64)"
    if [ -z "$ENCODED" ]; then
        echo "No keystore in the Keychain either; it cannot be reconstructed." >&2
        return 1 2>/dev/null || exit 1
    fi
    mkdir -p "$KEYSTORE_DIR"
    chmod 700 "$KEYSTORE_DIR"
    printf '%s' "$ENCODED" | base64 --decode > "$KEYSTORE"
    chmod 600 "$KEYSTORE"
    echo "Restored $KEYSTORE from the Keychain."
fi

export SHUTTERLINK_KEYSTORE="$KEYSTORE"
export SHUTTERLINK_KEYSTORE_PASSWORD="$PASSWORD"
export SHUTTERLINK_KEY_PASSWORD="$PASSWORD"
export SHUTTERLINK_KEY_ALIAS="$ACCOUNT"

echo "Release signing ready (keystore: $SHUTTERLINK_KEYSTORE)"
