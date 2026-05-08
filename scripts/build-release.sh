#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

OUT_DIR="$ROOT/dist"
APK_SRC="$ROOT/app/build/outputs/apk/release/app-release.apk"

echo "==> clean"
./gradlew --no-daemon clean

echo "==> assembleRelease"
./gradlew --no-daemon :app:assembleRelease

if [[ ! -f "$APK_SRC" ]]; then
  echo "ERROR: APK not found at $APK_SRC" >&2
  exit 1
fi

VERSION_NAME=$(grep -E '^\s*versionName\s*=' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
VERSION_CODE=$(grep -E '^\s*versionCode\s*=' app/build.gradle.kts | head -1 | sed -E 's/.*=\s*([0-9]+).*/\1/')
GIT_SHA=$(git rev-parse --short HEAD 2>/dev/null || echo "nogit")
DIRTY=""
if ! git diff --quiet 2>/dev/null || ! git diff --cached --quiet 2>/dev/null; then
  DIRTY="-dirty"
fi

mkdir -p "$OUT_DIR"
DEST="$OUT_DIR/musicPlayer-v${VERSION_NAME}-${VERSION_CODE}-${GIT_SHA}${DIRTY}.apk"
cp "$APK_SRC" "$DEST"
( cd "$OUT_DIR" && sha256sum "$(basename "$DEST")" > "$(basename "$DEST").sha256" )

echo "==> done"
echo "APK:    $DEST"
echo "SHA256: $DEST.sha256"
