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
    XDG_RUNTIME_DIR="$runtime_directory" XDG_CONFIG_HOME="$runtime_directory/config" \
        "$binary" timers cancel --all >/dev/null 2>&1 || true
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

# Exercise the frozen timer runner: schedule a far-future timer (nothing connects
# before it fires), list it, cancel it, and confirm the runner process exits.
export XDG_RUNTIME_DIR="$runtime_directory" XDG_CONFIG_HOME="$runtime_directory/config"
schedule_output=$("$binary" off --address "$test_address" --in 24h --mesh-name smoke-mesh --password smoke-secret)
grep -Eq "^Scheduled [0-9a-f]{6}: off at [0-9:]{8} \(in 24h\)$" <<<"$schedule_output"
timer_id=$(sed -E 's/^Scheduled ([0-9a-f]{6}):.*/\1/' <<<"$schedule_output")
timer_record="$runtime_directory/blemeshctl/timers/$timer_id.json"
[[ -f "$timer_record" ]]
if grep -Fq -e smoke-mesh -e smoke-secret "$timer_record"; then
    echo "timer record contains credentials" >&2
    exit 1
fi
runner_pid=$(sed -nE 's/^ *"pid": ([0-9]+),?$/\1/p' "$timer_record")
[[ -n "$runner_pid" ]] && kill -0 "$runner_pid"
"$binary" timers | grep -Eq "^$timer_id  scheduled  in 2[34]h"
"$binary" timers cancel "$timer_id" | grep -Fq "Cancelled $timer_id."
for _ in {1..100}; do
    kill -0 "$runner_pid" 2>/dev/null || break
    sleep 0.05
done
if kill -0 "$runner_pid" 2>/dev/null; then
    echo "standalone timer runner did not exit after cancel" >&2
    exit 1
fi
"$binary" timers | grep -Eq "^$timer_id  cancelled"
"$binary" timers clear | grep -Fq "Cleared 1 finished timer."

# Sunrise goes through the same record and runner: schedule far in the future,
# list it, check the record holds no credentials, and cancel it.
sunrise_output=$("$binary" sunrise --in 24h --over 2m --address "$test_address" --mesh-name smoke-mesh --password smoke-secret)
grep -Eq "^Scheduled [0-9a-f]{6}: sunrise 2m ending [0-9:]{5} at [0-9:]{8} \(in 24h\)$" <<<"$(head -n1 <<<"$sunrise_output")"
grep -Eq "^Wake time: [A-Z][a-z]{2} [0-9]{1,2} [A-Z][a-z]{2} [0-9:]{5}$" <<<"$sunrise_output"
sunrise_id=$(sed -nE '1s/^Scheduled ([0-9a-f]{6}):.*/\1/p' <<<"$sunrise_output")
sunrise_record="$runtime_directory/blemeshctl/timers/$sunrise_id.json"
[[ -f "$sunrise_record" ]]
if grep -Fq -e smoke-mesh -e smoke-secret "$sunrise_record"; then
    echo "sunrise timer record contains credentials" >&2
    exit 1
fi
grep -Fq "color ffb060 100" "$sunrise_record"
sunrise_pid=$(sed -nE 's/^ *"pid": ([0-9]+),?$/\1/p' "$sunrise_record")
[[ -n "$sunrise_pid" ]] && kill -0 "$sunrise_pid"
"$binary" timers | grep -Eq "^$sunrise_id  scheduled  in 2[34]h.*sunrise 2m ending"
"$binary" timers cancel "$sunrise_id" | grep -Fq "Cancelled $sunrise_id."
for _ in {1..100}; do
    kill -0 "$sunrise_pid" 2>/dev/null || break
    sleep 0.05
done
if kill -0 "$sunrise_pid" 2>/dev/null; then
    echo "standalone sunrise runner did not exit after cancel" >&2
    exit 1
fi
"$binary" timers clear | grep -Fq "Cleared 1 finished timer."
