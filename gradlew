#!/usr/bin/env sh
# Lightweight Gradle bootstrapper for this source archive.
# The standard wrapper JAR is intentionally not vendored; Gradle 9.6.0 is fetched on first use.
set -eu
VERSION="9.6.0"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
INSTALL_DIR="$GRADLE_USER_HOME/wrapper/dists/lumina-gradle-$VERSION"
GRADLE_BIN="$INSTALL_DIR/gradle-$VERSION/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$INSTALL_DIR"
  ARCHIVE="$INSTALL_DIR/gradle-$VERSION-bin.zip"
  URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  echo "Downloading Gradle $VERSION (first run only)..."
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --retry 3 "$URL" -o "$ARCHIVE"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ARCHIVE" "$URL"
  else
    echo "curl or wget is required to download Gradle." >&2
    exit 1
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$ARCHIVE" -d "$INSTALL_DIR"
  else
    echo "unzip is required to extract Gradle." >&2
    exit 1
  fi
  rm -f "$ARCHIVE"
fi
exec "$GRADLE_BIN" "$@"
