#!/usr/bin/env bash
# Fails if the APK requests a permission that is not on the allowlist, is debuggable or
# testOnly, or is signed with a different certificate. INTERNET is allowed for one purpose
# only; check-network.sh keeps the networking code in a single file.
set -euo pipefail
APK="$1"
BT="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"

# Permissions the app may request. Each new one needs a reason here.
ALLOWED_PERMISSIONS=(
  # AndroidX: lets dynamically registered receivers stay private to this app.
  com.supun.sweeper.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
  # Usage access: lets StorageStatsManager report how much space each other app uses (read-only).
  android.permission.PACKAGE_USAGE_STATS
  # Android 11+ package visibility: needed to list all installed apps (sideloaded app, not on Play).
  android.permission.QUERY_ALL_PACKAGES
  # "All files access" (Android 11+): needed to find big/old files anywhere on shared storage and move
  # them into the app's own trash folder. Files are never uploaded.
  android.permission.MANAGE_EXTERNAL_STORAGE
  # Android 8-10 equivalent of the above (the manifest limits both to maxSdkVersion 29).
  android.permission.READ_EXTERNAL_STORAGE
  android.permission.WRITE_EXTERNAL_STORAGE
  # Downloads the signed malware signature list from one fixed GitHub address; see .github/scripts/check-network.sh
  android.permission.INTERNET
  # Needed for the Uninstall action of the Malware check (Intent.ACTION_DELETE opens Android's own confirm dialog).
  android.permission.REQUEST_DELETE_PACKAGES
)

fail=0
perms=$("$BT/aapt2" dump permissions "$APK" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p")
echo "Requested permissions:"; echo "${perms:-  (none)}"
for p in $perms; do
  if ! printf '%s\n' "${ALLOWED_PERMISSIONS[@]}" | grep -qx "$p"; then
    echo "::error::Permission $p is not on the allowlist in .github/scripts/check-apk.sh"; fail=1
  fi
done

badging=$("$BT/aapt2" dump badging "$APK")
if grep -q "application-debuggable" <<<"$badging"; then echo "::error::APK is debuggable"; fail=1; fi
if grep -q "testOnly='-1'" <<<"$badging"; then echo "::error::APK is testOnly"; fail=1; fi

certs=$("$BT/apksigner" verify --print-certs "$APK" 2>&1) || { echo "::error::APK signature does not verify: $certs"; exit 1; }
# apksigner labels signers differently across versions ("Signer #1 certificate ...",
# "V2 Signer: certificate ..."), so match the digest itself and require exactly one value.
digests=$(grep -oE 'certificate SHA-256 digest: [0-9a-f]{64}' <<<"$certs" | awk '{print $4}' | sort -u)
want=$(tr -d '[:space:]' < .github/signing-cert-sha256.txt)
if [ "$(grep -c . <<<"$digests")" != 1 ]; then
  echo "::error::Expected exactly one signing certificate, found:"; echo "$certs"; exit 1
fi
got=$(tr 'a-f' 'A-F' <<<"$digests" | sed 's/../&:/g; s/:$//')
echo "Signing certificate: $got"
if [ "$got" != "$want" ]; then echo "::error::Signed with an unexpected certificate (want $want)"; fail=1; fi
exit $fail
