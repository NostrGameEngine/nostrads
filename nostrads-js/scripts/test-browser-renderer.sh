#!/usr/bin/env bash
set -euo pipefail

module_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
bundle="$module_dir/src/nostrads-client.js"
if [[ ! -s "$bundle" ]]; then
    echo "Built JavaScript client bundle is missing." >&2
    exit 1
fi
if rg -q 'nostrads-worker-session-v1|new SharedWorker|new BroadcastChannel' "$bundle"; then
    echo "Built JavaScript client still contains a shared cross-tab worker transport." >&2
    exit 1
fi
test_tmp=$(mktemp -d)
server_pid=""
cleanup() {
    if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; fi
    rm -r "$test_tmp"
}
trap cleanup EXIT

python3 -m http.server 18732 --bind 127.0.0.1 --directory "$module_dir" >"$test_tmp/server.log" 2>&1 &
server_pid=$!
server_ready=false
for _ in $(seq 1 50); do
    if curl -fsS http://127.0.0.1:18732/browser-tests/renderer.html >/dev/null 2>&1; then
        server_ready=true
        break
    fi
    sleep 0.1
done
if [[ "$server_ready" != true ]]; then
    cat "$test_tmp/server.log"
    exit 1
fi

timeout 45s google-chrome \
    --headless=new \
    --no-sandbox \
    --disable-gpu \
    --disable-dev-shm-usage \
    --disable-software-rasterizer \
    --virtual-time-budget=5000 \
    --user-data-dir="$test_tmp/chrome" \
    --dump-dom \
    http://127.0.0.1:18732/browser-tests/renderer.html >"$test_tmp/dom.html"

if ! rg -q 'data-test-status="passed"' "$test_tmp/dom.html"; then
    sed -n '1,160p' "$test_tmp/dom.html"
    exit 1
fi
echo "Browser renderer security test passed."
