#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$HOME/.gradle"}
DIST="$GRADLE_USER_HOME/wrapper/dists/gradle-8.13-bin/local"
GRADLE="$DIST/gradle-8.13/bin/gradle"
if [ ! -x "$GRADLE" ]; then
  mkdir -p "$DIST"
  ZIP="$DIST/gradle-8.13-bin.zip"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then curl -fL https://services.gradle.org/distributions/gradle-8.13-bin.zip -o "$ZIP"
    else wget -O "$ZIP" https://services.gradle.org/distributions/gradle-8.13-bin.zip
    fi
  fi
  unzip -q -o "$ZIP" -d "$DIST"
fi
exec "$GRADLE" -p "$APP_HOME" "$@"
