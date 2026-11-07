#!/usr/bin/env sh
set -eu
VERSION=9.1.0
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/discord-webview-bootstrap"
GRADLE="$BASE/gradle-$VERSION/bin/gradle"
if [ ! -x "$GRADLE" ]; then
  mkdir -p "$BASE"
  ARCHIVE="$BASE/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ARCHIVE"
  else
    echo "curl is required to bootstrap Gradle $VERSION" >&2
    exit 1
  fi
  unzip -oq "$ARCHIVE" -d "$BASE"
  rm -f "$ARCHIVE"
fi
exec "$GRADLE" "$@"
