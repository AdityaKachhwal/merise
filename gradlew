#!/bin/sh
# Lightweight Gradle bootstrapper for MineVest.
# Downloads the pinned Gradle distribution; no global Gradle installation required.
set -eu
GRADLE_VERSION="8.14.3"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/minevest-bootstrap/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME_DIR/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$(dirname "$GRADLE_HOME_DIR")"
  ZIP_FILE="$(dirname "$GRADLE_HOME_DIR")/gradle-$GRADLE_VERSION-bin.zip"
  echo "Downloading Gradle $GRADLE_VERSION (first run only)..."
  curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP_FILE"
  unzip -q -o "$ZIP_FILE" -d "$(dirname "$GRADLE_HOME_DIR")"
fi
exec "$GRADLE_BIN" "$@"
