#!/usr/bin/env bash
# Compiles the babakit helper with the Command Line Tools (no Xcode required).
# Usage: native/build.sh [output-path]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/build/babakit}"
mkdir -p "$(dirname "$out")"
swiftc -O -swift-version 5 -target arm64-apple-macos13.0 \
  -framework UserNotifications -framework ServiceManagement -framework Security \
  "$here"/babakit/*.swift -o "$out"
echo "$out"
