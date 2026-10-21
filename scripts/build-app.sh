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

echo "==> Ad-hoc signing"
codesign --force -s - "$app/Contents/MacOS/babakit"
codesign --force --deep -s - "$app"
codesign --verify --deep --strict "$app"

echo "==> Checking helper inside bundle"
"$app/Contents/MacOS/babakit" version

echo
echo "Built: $app"
