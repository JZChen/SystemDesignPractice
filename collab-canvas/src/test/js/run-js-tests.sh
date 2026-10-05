#!/usr/bin/env bash
# Runs the client unit tests in JavaScriptCore (macOS osascript); no node/npm needed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
exec osascript -l JavaScript "$ROOT/src/test/js/runner.js" "$ROOT"
