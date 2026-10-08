#!/system/bin/sh
# Lightweight Gradle launcher for AndroidIDE when the standard Gradle Wrapper files are absent.
set -e
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
GRADLE_VERSION="8.9"
CACHE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/jarvis-gradle"
DIST_DIR="$CACHE_DIR/gradle-$GRADLE_VERSION"
GRADLE_BIN="$DIST_DIR/bin/gradle"
ZIP="$CACHE_DIR/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$CACHE_DIR"
  URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  echo "Downloading Gradle ${GRADLE_VERSION}..."
  if command -v curl >/dev/null 2>&1; then
    curl -L --fail --retry 3 "$URL" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "ERROR: curl or wget is required to download Gradle." >&2
    exit 1
  fi
  rm -rf "$CACHE_DIR/gradle-$GRADLE_VERSION" "$CACHE_DIR/gradle-$GRADLE_VERSION.tmp"
  mkdir -p "$CACHE_DIR/gradle-$GRADLE_VERSION.tmp"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP" -d "$CACHE_DIR/gradle-$GRADLE_VERSION.tmp"
  else
    echo "ERROR: unzip is required to extract Gradle." >&2
    exit 1
  fi
  mv "$CACHE_DIR/gradle-$GRADLE_VERSION.tmp/gradle-$GRADLE_VERSION" "$DIST_DIR"
  rm -rf "$CACHE_DIR/gradle-$GRADLE_VERSION.tmp"
fi

cd "$ROOT"
exec "$GRADLE_BIN" "$@"
