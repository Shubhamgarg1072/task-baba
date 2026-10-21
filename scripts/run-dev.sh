#!/usr/bin/env bash
# Runs Task Baba from Gradle for development.
#
# Outside the app bundle macOS won't grant UserNotifications or launch-at-login, so notifications fall
# back to plain osascript banners (no action buttons). The helper is still used for the Keychain.
# Data goes to a separate dev folder so your real tasks are untouched; override with TASKBABA_HOME.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

helper="$(native/build.sh)"
export TASKBABA_BRIDGE="$helper"
export TASKBABA_HOME="${TASKBABA_HOME:-$HOME/Library/Application Support/TaskBaba-dev}"
echo "Using data folder: $TASKBABA_HOME"
exec ./gradlew :app:run
