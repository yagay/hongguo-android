#!/usr/bin/env bash
# Rebuild the official openhongguo post-v0.4.0 Android SDK with a 16 KiB-aligned
# ARM64 Go JNI library. Do not use or silently fall back to the upstream
# prebuilt Maven AAR: its libgojni.so fails on 16 KiB-page devices.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AAR="$ROOT/app/libs/openhongguo-16kb-recovery.aar"
STAMP="$AAR.buildstamp"
PATCH_ID="v5-upstream-medusa-v041-32d2a8c6-player-empty-strictid"
SOURCE_REPO="https://github.com/beilunyang/openhongguo.git"
SOURCE_TAG="main@32d2a8c6"
SOURCE_SHA="32d2a8c6387d4a63fe9262525531dd9d363f9833"
GOMOBILE_VERSION="v0.0.0-20261011034528-197770444867"

# An ELF-aligned AAR may still contain the obsolete, already-cached v2
# protocol. Reuse it only when the tested patch version also matches.
if [[ -s "$AAR" && -f "$STAMP" ]] &&
   [[ "$(cat "$STAMP")" == "$PATCH_ID" ]] &&
   python3 "$ROOT/scripts/verify-16kb-elf.py" "$AAR"; then
  echo "Using previously verified official 16 KiB SDK: $AAR"
  exit 0
fi

: "${ANDROID_HOME:?ANDROID_HOME must point to Android SDK}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to NDK r28 or newer}"
NDK_VERSION="$(cat "$ANDROID_NDK_HOME/source.properties" | sed -n 's/^Pkg.Revision *= *\([0-9][0-9]*\).*/\1/p' | head -1)"
if [[ -z "$NDK_VERSION" ]] || (( NDK_VERSION < 28 )); then
  echo "NDK r28+ is required, found ${NDK_VERSION:-unknown} at $ANDROID_NDK_HOME" >&2
  exit 1
fi
command -v go >/dev/null
command -v unzip >/dev/null
command -v git >/dev/null

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "Fetching pinned official openhongguo ${SOURCE_TAG} (${SOURCE_SHA})"
git init -q "$TMP/openhongguo"
git -C "$TMP/openhongguo" remote add origin "$SOURCE_REPO"
# Fetch the exact reviewed commit; do not silently consume moving main.
git -C "$TMP/openhongguo" fetch --quiet --depth 1 origin "$SOURCE_SHA"
git -C "$TMP/openhongguo" checkout -q --detach "$SOURCE_SHA"
ACTUAL_SHA="$(git -C "$TMP/openhongguo" rev-parse HEAD)"
if [[ "$ACTUAL_SHA" != "$SOURCE_SHA" ]]; then
  echo "Upstream tag changed; refusing to build unexpected source: $ACTUAL_SHA" >&2
  exit 1
fi

# Playback recovery is scoped to the pinned official Go source tree.
# Unit tests check same-identity signed GET, episode matching and limit handling.
python3 "$ROOT/scripts/apply-openhongguo-recovery.py" "$TMP/openhongguo"
cp "$ROOT/scripts/openhongguo-recovery.test.go" \
  "$TMP/openhongguo/sdk/playback_video_tab_recovery_test.go"
(
  cd "$TMP/openhongguo"
  # Include new v04.10.03 x-medusa/x-gorgon/x-helios signing vectors.
  go test ./sdk ./internal/protocol ./internal/protocol/sign
)

echo "Building ARM64 Go JNI with NDK r28+ and 16 KiB linker alignment"
go install "golang.org/x/mobile/cmd/gomobile@${GOMOBILE_VERSION}"
GOMOBILE="$(go env GOPATH)/bin/gomobile"
"$GOMOBILE" init
mkdir -p "$TMP/output" "$TMP/expanded"
(
  cd "$TMP/openhongguo/bindings/mobile/core"
  "$GOMOBILE" bind -target=android/arm64 -androidapi=26 \
    -javapkg=dev.openhongguo -trimpath \
    -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" \
    -o "$TMP/output/core.aar" .
)
unzip -q "$TMP/output/core.aar" 'classes.jar' 'jni/arm64-v8a/libgojni.so' -d "$TMP/expanded"
python3 "$ROOT/scripts/verify-16kb-elf.py" "$TMP/output/core.aar"

ANDROID_LIB="$TMP/openhongguo/bindings/mobile/android"
mkdir -p "$ANDROID_LIB/libs" "$ANDROID_LIB/src/main/jniLibs/arm64-v8a"
cp "$TMP/expanded/classes.jar" "$ANDROID_LIB/libs/core.jar"
cp "$TMP/expanded/jni/arm64-v8a/libgojni.so" "$ANDROID_LIB/src/main/jniLibs/arm64-v8a/libgojni.so"
(
  cd "$ANDROID_LIB"
  ./gradlew --no-daemon assembleRelease
)
mkdir -p "$(dirname "$AAR")"
cp "$ANDROID_LIB/build/outputs/aar/openhongguo-release.aar" "$AAR"
python3 "$ROOT/scripts/verify-16kb-elf.py" "$AAR"
printf '%s\n' "$PATCH_ID" > "$STAMP"
echo "Created $AAR from pinned official openhongguo $SOURCE_TAG with pinned v0.4.1 signer fixes, tested player/v1, typed empty response, video_tab recovery and 16 KiB ARM64 ELF alignment ($PATCH_ID)."
