#!/usr/bin/env bash
# Keeps the app's network use in ONE file. Fails if
#  - any source file other than SignatureDownloader.kt contains networking code,
#  - SignatureDownloader.kt contains a plain http address, or any https address other than the one fixed
#    download location,
#  - the network security config or the manifest no longer forbid cleartext traffic / user certificates.
set -euo pipefail
cd "$(dirname "$0")/../.."

DOWNLOADER=app/src/main/java/com/supun/sweeper/net/SignatureDownloader.kt
BASE='https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/'
PATTERN='java\.net\.|javax\.net\.|HttpURLConnection|URLConnection|\bSocket\b|DatagramSocket|okhttp|ktor|WebView|DownloadManager'
SOURCE_DIRS=(app/src/main)
for extra in app/src/debug app/src/release; do
  if [ -d "$extra" ]; then SOURCE_DIRS+=("$extra"); fi
done
fail=0

echo "Checking for networking code in: ${SOURCE_DIRS[*]}"
echo "  pattern: $PATTERN"
echo "  the only file allowed to match: $DOWNLOADER"
if [ ! -f "$DOWNLOADER" ]; then
  echo "::error::$DOWNLOADER is missing"; exit 1
fi
files=$(grep -rIlE -e "$PATTERN" "${SOURCE_DIRS[@]}" | grep -vxF "$DOWNLOADER" || true)
checked=$(find "${SOURCE_DIRS[@]}" -type f | wc -l)
echo "  files scanned: $checked"
if [ -n "$files" ]; then
  while IFS= read -r f; do
    echo "::error file=$f::Networking code outside SignatureDownloader.kt"
    grep -nE -e "$PATTERN" "$f" || true
  done <<<"$files"
  fail=1
fi

echo "Checking the addresses in $DOWNLOADER"
if grep -nF 'http://' "$DOWNLOADER"; then
  echo "::error file=$DOWNLOADER::Plain http address found"; fail=1
fi
while IFS= read -r url; do
  if [ -z "$url" ]; then continue; fi
  if [ "$url" != "$BASE" ]; then
    echo "::error file=$DOWNLOADER::Unexpected address: $url"; fail=1
  fi
done < <(grep -oE 'https://[^"[:space:]]*' "$DOWNLOADER" || true)
base_count=$(grep -oF "$BASE" "$DOWNLOADER" | wc -l)
scheme_count=$(grep -oF 'https:' "$DOWNLOADER" | wc -l)
if [ "$base_count" -ne 1 ]; then
  echo "::error file=$DOWNLOADER::Expected the base address exactly once, found $base_count"; fail=1
fi
if [ "$scheme_count" -ne "$base_count" ]; then
  echo "::error file=$DOWNLOADER::An https: string appears outside the base address"; fail=1
fi
echo "  base address found $base_count time(s); https: strings: $scheme_count"

echo "Checking the network security config"
CONFIG=app/src/main/res/xml/network_security_config.xml
MANIFEST=app/src/main/AndroidManifest.xml
if ! grep -q 'cleartextTrafficPermitted="false"' "$CONFIG"; then
  echo "::error file=$CONFIG::cleartextTrafficPermitted must be false"; fail=1
fi
if grep -qE 'src="user"|<domain-config|cleartextTrafficPermitted="true"|<debug-overrides' "$CONFIG"; then
  echo "::error file=$CONFIG::Only system certificates and no cleartext are allowed"; fail=1
fi
if ! grep -q 'android:networkSecurityConfig="@xml/network_security_config"' "$MANIFEST"; then
  echo "::error file=$MANIFEST::The manifest must reference the network security config"; fail=1
fi
if grep -q 'usesCleartextTraffic="true"' "$MANIFEST"; then
  echo "::error file=$MANIFEST::usesCleartextTraffic must not be true"; fail=1
fi

if [ "$fail" -eq 0 ]; then echo "Network check passed."; else echo "Network check FAILED."; fi
exit $fail
