#!/usr/bin/env bash
set -euo pipefail

binary=${1:-dist/blemeshctl}
binary=$(realpath "$binary")

if [[ ! -x "$binary" ]]; then
    echo "standalone executable not found: $binary" >&2
    exit 1
fi

"$binary" --help >/dev/null

runtime_directory=$(mktemp -d)
daemon_pid=""
test_address="00:00:00:00:00:00"

cleanup() {
    XDG_RUNTIME_DIR="$runtime_directory" \
        "$binary" daemon stop --address "$test_address" >/dev/null 2>&1 || true
    XDG_RUNTIME_DIR="$runtime_directory" "$binary" daemon stop >/dev/null 2>&1 || true
    if [[ -n "$daemon_pid" ]]; then
        wait "$daemon_pid" 2>/dev/null || true
    fi
    rm -rf -- "$runtime_directory"
}
trap cleanup EXIT

socket_path="$runtime_directory/blemeshctl/default.sock"
XDG_RUNTIME_DIR="$runtime_directory" \
    "$binary" --internal-daemon --socket "$socket_path" &
daemon_pid=$!

for _ in {1..200}; do
    if [[ -S "$socket_path" ]]; then
        break
    fi
    if ! kill -0 "$daemon_pid" 2>/dev/null; then
        wait "$daemon_pid" || true
        echo "standalone daemon exited before creating its socket" >&2
        exit 1
    fi
    sleep 0.05
done

if [[ ! -S "$socket_path" ]]; then
    echo "standalone daemon did not create its socket" >&2
    exit 1
fi

status_output=$(XDG_RUNTIME_DIR="$runtime_directory" "$binary" daemon status)
grep -Fq "Keepalive daemon is starting a Bluetooth connection." <<<"$status_output"
XDG_RUNTIME_DIR="$runtime_directory" "$binary" daemon stop >/dev/null
wait "$daemon_pid"
daemon_pid=""

# Exercise the real frozen self-spawn path without requiring BLE hardware. The
# deliberately nonexistent address makes the command fail after discovery, but
# its independently spawned daemon must remain reachable until explicitly stopped.
if XDG_RUNTIME_DIR="$runtime_directory" "$binary" on \
    --address "$test_address" \
    --scan-timeout 0.05 \
    --connect-timeout 0.05 \
    >"$runtime_directory/control.out" 2>&1; then
    echo "standalone control unexpectedly found the smoke-test address" >&2
    exit 1
fi

status_output=$(
    XDG_RUNTIME_DIR="$runtime_directory" \
        "$binary" daemon status --address "$test_address"
)
grep -Fq "Keepalive daemon is starting a Bluetooth connection." <<<"$status_output"
XDG_RUNTIME_DIR="$runtime_directory" \
    "$binary" daemon stop --address "$test_address" >/dev/null
