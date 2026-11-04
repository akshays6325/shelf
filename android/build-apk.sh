#!/usr/bin/env bash
# Builds the signed release APK: dist/shelf.apk
# The signing key is created once in signing/. Keep it: Android only installs
# updates signed with the same key.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Java/jdk-21}"

if [ ! -f signing/release.jks ]; then
  mkdir -p signing
  PASS=$(node -e "console.log(require('crypto').randomBytes(18).toString('base64url'))")
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore signing/release.jks -alias shelf \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=Shelf" >/dev/null 2>&1
  printf 'storeFile=signing/release.jks\nkeyAlias=shelf\npassword=%s\n' "$PASS" > signing/signing.properties
fi

./gradlew :app:assembleRelease --no-daemon -q
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/shelf.apk
echo "Built dist/shelf.apk ($(du -h dist/shelf.apk | cut -f1))"
