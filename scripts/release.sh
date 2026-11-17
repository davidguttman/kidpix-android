#!/bin/bash
# Builds a signed release APK and publishes it as a GitHub release for the current versionName.
# Signs with the debug keystore under $ANDROID_USER_HOME (or ~/.android) so sideloaded installs update in place.
# usage: scripts/release.sh            (versionName/versionCode come from app/build.gradle.kts)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)
TAG="v$VERSION"
APK="dist/kidpix-android-$VERSION.apk"

[ -z "$(git status --porcelain)" ] || { echo "working tree not clean"; exit 1; }
git rev-parse "$TAG" >/dev/null 2>&1 && { echo "$TAG already exists; bump versionName/versionCode"; exit 1; }

./gradlew -q clean assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "$APK"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$APK" 2>/dev/null | grep "SHA-256"
sha256sum "$APK" | tee "$APK.sha256"

git tag -a "$TAG" -m "Kid Pix for Android $VERSION"
git push origin main "$TAG"
NOTES="${RELEASE_NOTES:-docs/release-notes/$TAG.md}"
gh release create "$TAG" "$APK" "$APK.sha256" --title "Kid Pix for Android $VERSION" --notes-file "$NOTES"
