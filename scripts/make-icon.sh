#!/usr/bin/env bash
# Regenerates app/icons/TaskBaba.icns (and a 1024px PNG) from the original artwork in scripts/icon/MonkIcon.swift.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

swiftc -O -swift-version 5 -framework AppKit "$root/scripts/icon/MonkIcon.swift" -o "$work/monkicon"
"$work/monkicon" "$work/icon_1024.png" >/dev/null

iconset="$work/TaskBaba.iconset"
mkdir -p "$iconset"
for s in 16 32 128 256 512; do
  sips -z "$s" "$s" "$work/icon_1024.png" --out "$iconset/icon_${s}x${s}.png" >/dev/null
  d=$((s * 2))
  sips -z "$d" "$d" "$work/icon_1024.png" --out "$iconset/icon_${s}x${s}@2x.png" >/dev/null
done

mkdir -p "$root/app/icons"
iconutil -c icns "$iconset" -o "$root/app/icons/TaskBaba.icns"
cp "$work/icon_1024.png" "$root/app/icons/TaskBaba-1024.png"
echo "$root/app/icons/TaskBaba.icns"
