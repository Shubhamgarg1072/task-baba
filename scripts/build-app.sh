#!/usr/bin/env bash
# Builds "Task Baba.app": Compose distributable + native babakit helper + icon, then ad-hoc signs it.
#
#   scripts/build-app.sh            # build into app/build/compose/binaries/main/app/
#   open "app/build/compose/binaries/main/app/Task Baba.app"
#
# Ad-hoc signing is enough to run locally; it is not notarized, so copies downloaded from elsewhere
# need right-click › Open the first time.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if [[ ! -f app/icons/TaskBaba.icns ]]; then
  echo "==> Generating icon"
  scripts/make-icon.sh
fi

echo "==> Building Compose distributable"
./gradlew --quiet :app:createDistributable

app="$root/app/build/compose/binaries/main/app/Task Baba.app"
if [[ ! -d "$app" ]]; then
  echo "error: expected bundle at $app" >&2
  exit 1
fi

echo "==> Compiling native helper (babakit)"
native/build.sh "$app/Contents/MacOS/babakit" >/dev/null

# A stable identity (scripts/setup-signing.sh) keeps the app's signature the same across rebuilds, so macOS
# remembers notification and Keychain permissions. Without it we fall back to ad-hoc signing.
identity="Task Baba Local Signing"
if security find-identity -p codesigning 2>/dev/null | grep -q "\"$identity\"" \
   && codesign --force -s "$identity" "$app/Contents/MacOS/babakit" 2>/dev/null; then
  echo "==> Signing with \"$identity\""
  codesign --force --deep -s "$identity" "$app"
else
  echo "==> Ad-hoc signing (run scripts/setup-signing.sh for a stable signature that keeps permissions across rebuilds)"
  codesign --force -s - "$app/Contents/MacOS/babakit"
  codesign --force --deep -s - "$app"
fi
codesign --verify --deep --strict "$app" 2>/dev/null || codesign --verify --deep "$app"

echo "==> Checking helper inside bundle"
"$app/Contents/MacOS/babakit" version

echo
echo "Built: $app"
