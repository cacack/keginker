#!/usr/bin/env bash
# CONSTITUTION.md C4. iOS permissions are usage descriptions in Info.plist
# plus entitlements; the photo picker needs neither. Signing adds identity
# keys to a signed app's entitlements, which grant nothing.
# Usage: check-ios-permissions.sh <app-dir> <entitlements-plist>
set -euo pipefail

app=$1
entitlements=$2

keys() { plutil -convert json -o - "$1" | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin)))'; }

# Read each file first so a broken plist fails here instead of passing as empty.
info_keys=$(keys "$app/Info.plist")
entitlement_keys=$(keys "$entitlements")

extra=$( { grep 'UsageDescription$' <<< "$info_keys" || true; echo "$entitlement_keys"; } \
  | grep -v -x \
    -e '' \
    -e NFCReaderUsageDescription \
    -e com.apple.developer.nfc.readersession.formats \
    -e application-identifier \
    -e com.apple.developer.team-identifier \
    -e get-task-allow \
    -e beta-reports-active \
    -e keychain-access-groups \
  || true)
if [ -n "$extra" ]; then
  echo "::error::C4: the iOS app requests permissions beyond NFC: $extra"
  exit 1
fi
echo "C4: the iOS app requests only NFC"
