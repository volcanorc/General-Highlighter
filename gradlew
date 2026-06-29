#!/usr/bin/env sh
set -eu

GRADLE_VERSION=8.11.1
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_HOME="$ROOT_DIR/.gradle-launcher/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"
GRADLE_ZIP="$ROOT_DIR/.gradle-launcher/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$ROOT_DIR/.gradle-launcher"
  if [ ! -f "$GRADLE_ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -L "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$GRADLE_ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -O "$GRADLE_ZIP"
    else
      echo "curl or wget is required to download Gradle." >&2
      exit 1
    fi
  fi
  unzip -q -o "$GRADLE_ZIP" -d "$ROOT_DIR/.gradle-launcher"
fi

exec "$GRADLE_BIN" "$@"
