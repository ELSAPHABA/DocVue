#!/usr/bin/env bash
# Verified distribution bootstrap until the official wrapper binary is generated.
set -euo pipefail
version=8.14.4
sha=f1771298a70f6db5a29daf62378c4e18a17fc33c9ba6b14362e0cdf40610380d
cache="$HOME/.cache/dockvue/gradle"
mkdir -p "$cache"
if [[ ! -x "$cache/gradle-$version/bin/gradle" ]]; then
  temp=$(mktemp -d "$cache/download.XXXXXX")
  trap 'rm -rf -- "$temp"' EXIT
  curl --fail --location --retry 3 --proto '=https' -o "$temp/gradle.zip" "https://services.gradle.org/distributions/gradle-$version-bin.zip"
  printf '%s  %s\n' "$sha" "$temp/gradle.zip" | sha256sum --check
  unzip -q "$temp/gradle.zip" -d "$temp"
  if [[ ! -d "$cache/gradle-$version" ]]; then
    mv "$temp/gradle-$version" "$cache/"
  fi
fi
exec "$cache/gradle-$version/bin/gradle" "$@"
