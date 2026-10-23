#!/usr/bin/env bash
set -euo pipefail

module_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
test_page=${1:-renderer}
bundle="$module_dir/src/nostrads-client.js"
if [[ ! -s "$bundle" ]]; then
    echo "Built JavaScript client bundle is missing." >&2
    exit 1
fi
if grep -qE 'nostrads-worker-session-v1|new SharedWorker|new BroadcastChannel' "$bundle"; then
    echo "Built JavaScript client still contains a shared cross-tab worker transport." >&2
    exit 1
fi
exec node "$module_dir/scripts/test-browser.mjs" "$test_page"
