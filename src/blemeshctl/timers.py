"""Delayed commands and sleep fades run by a detached background process.

``--in DURATION`` compiles a program, stores a *timer record* under
``$XDG_RUNTIME_DIR/blemeshctl/timers/`` and spawns a runner that sleeps until
the fire time. Nothing connects to a light before the timer fires. Mesh names
and passwords never reach a record or an argument list: they are written to the
runner's standard input once, at spawn.

Failure policy
--------------
An unattended run deliberately differs from ``execute_script``, which stops at
the first error. A step whose write fails has an unknown outcome; it is
recorded as failed and skipped, never retried at its own position, and later
steps are still attempted (a single light reconnects through its daemon; a group
rebuilds its relay by rescanning and choosing a gateway). Only the program's
final ``off`` is retried: up to :data:`OFF_RETRIES` extra attempts spaced
:data:`OFF_RETRY_SPACING_SECONDS` apart, because a fade that fails midway must
still end with the light off. The final state is ``done`` only when every step
was acknowledged (for a group: gateway acknowledged and no member failed),
``partial`` when the run finished but some steps failed or reached only some
members, and ``failed`` when nothing was delivered or the program could not
start. Only the connection to the gateway is acknowledged; relayed group members
are never individually acknowledged.

The runner sleeps in chunks of at most :data:`WAKE_CHUNK_SECONDS` and compares
wall-clock time with the fire time, so a suspended laptop fires within one chunk
of waking instead of being delayed by the length of the suspension.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import re
import secrets
import signal
import subprocess
import sys
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta
from datetime import time as dt_time
from pathlib import Path
from typing import Any, Protocol

from .daemon import MAX_IDLE_TIMEOUT, runtime_directory
from .protocol import COMMAND_ON_OFF, off_parameters, parse_rgb
from .script import (
    RAINBOW_COLORS,
    Delay,
    LightCommand,
    Loop,
    Rainbow,
    ScriptStep,
    execute_script,
    parse_script,
)

INTERNAL_TIMER_ARGUMENT = "--internal-timer"

MIN_DELAY_SECONDS = 1
MAX_DELAY_SECONDS = 24 * 60 * 60
MIN_FADE_STEP_SECONDS = 2
WAKE_CHUNK_SECONDS = 30.0
FINAL_KEEPALIVE_SECONDS = 5.0
MIN_KEEPALIVE_SECONDS = 10.0
# Between steps further apart than this, release the light instead of holding it,
# because a held connection blocks every other device (such as the phone app).
MAX_HOLD_WAIT_SECONDS = 120.0
OFF_RETRIES = 3
OFF_RETRY_SPACING_SECONDS = 5.0
FINISHED_RETENTION_SECONDS = 24 * 60 * 60
RUNNER_START_TIMEOUT = 10.0
UNSTARTED_GRACE_SECONDS = 30.0
LATE_NOTE_SECONDS = 60.0
CANCEL_EXIT_TIMEOUT = 5.0

STATE_SCHEDULED = "scheduled"
STATE_RUNNING = "running"
STATE_DONE = "done"
STATE_PARTIAL = "partial"
STATE_FAILED = "failed"
STATE_CANCELLED = "cancelled"
ACTIVE_STATES = frozenset({STATE_SCHEDULED, STATE_RUNNING})

RECORD_VERSION = 1
_ID_PATTERN = re.compile(r"[0-9a-f]{6}")
_DURATION_PATTERN = re.compile(r"(?:([0-9]+)h)?(?:([0-9]+)m)?(?:([0-9]+)s)?")


class TimerError(ValueError):
    """Raised when a timer or fade request is invalid or cannot be stored."""


class StepError(Exception):
    """A step was not fully acknowledged; ``partial`` means some lights received it."""

    def __init__(self, message: str, *, partial: bool = False) -> None:
        super().__init__(message)
        self.partial = partial


class StepBackend(Protocol):
    """Delivers one command to the timer's target and raises StepError on failure."""

    async def send(self, opcode: int, parameters: bytes, description: str, keepalive: float) -> None: ...


BackendFactory = Callable[[dict[str, Any], dict[str, str]], StepBackend]


# --- durations -------------------------------------------------------------


def parse_duration(text: str) -> int:
    """Return whole seconds for ``30s``, ``20m``, ``2h``, ``1h30m`` and similar."""

    cleaned = text.strip().lower()
    match = _DURATION_PATTERN.fullmatch(cleaned)
    if not cleaned or match is None:
        raise TimerError(
            f"invalid duration {text!r}: use a whole number with units such as 30s, 20m, 2h, or 1h30m"
        )
    hours, minutes, seconds = (int(part) if part else 0 for part in match.groups())
    total = hours * 3600 + minutes * 60 + seconds
    if total < MIN_DELAY_SECONDS:
        raise TimerError(f"duration {text!r} is too short; the minimum is 1s")
    if total > MAX_DELAY_SECONDS:
        raise TimerError(f"duration {text!r} is too long; the maximum is 24h")
    return total


def duration_argument(text: str) -> int:
    """argparse ``type=`` wrapper around :func:`parse_duration`."""

    try:
        return parse_duration(text)
    except TimerError as exc:
        raise argparse.ArgumentTypeError(str(exc)) from exc


def format_duration(seconds: float) -> str:
    """Format whole seconds as ``1h30m``, ``20m``, or ``45s``."""

    remaining = max(0, math.ceil(seconds))
    hours, remaining = divmod(remaining, 3600)
    minutes, secs = divmod(remaining, 60)
    parts = [f"{value}{unit}" for value, unit in ((hours, "h"), (minutes, "m"), (secs, "s")) if value]
    return "".join(parts) or "0s"


# --- fades -----------------------------------------------------------------


def _format_wait(seconds: float) -> str:
    return f"{seconds:.3f}".rstrip("0").rstrip(".") + "s"


def _check_brightness(name: str, value: int) -> None:
    if not 1 <= value <= 100:
        raise TimerError(f"{name} brightness must be from 1 through 100")


def fade_description(
    *, color: str | None, white: int | None, start: int, end: int | None, over_seconds: int
) -> str:
    """Return the short description shown by ``timers``."""

    subject = parse_rgb_label(color) if color is not None else f"white {white}K"
    target = "off" if end is None else str(end)
    return f"fade {subject} {start}->{target} over {format_duration(over_seconds)}"


def parse_rgb_label(color: str) -> str:
    red, green, blue = parse_rgb(color)
    return f"{red:02x}{green:02x}{blue:02x}"


def fade_program_text(
    *, color: str | None, white: int | None, start: int, end: int | None, over_seconds: int
) -> str:
    """Generate a fade as ``.blemesh`` text using only existing instructions.

    ``end`` of ``None`` dims to brightness 1 and then sends ``off`` when the
    fade ends. There is at most one step per MIN_FADE_STEP_SECONDS, one per
    brightness level, and at least one step. The colour is required because
    lights cannot report their current state.
    """

    if color is None and white is None:
        raise TimerError(
            "fade needs --color RGB or --white [KELVIN]: lights cannot report their current "
            "state (issue #21), so blemeshctl cannot know which colour to dim"
        )
    if color is not None and white is not None:
        raise TimerError("choose either --color or --white for a fade, not both")
    _check_brightness("--from", start)
    if end is not None:
        _check_brightness("--to", end)
    if over_seconds < 1:
        raise TimerError("--over must be at least 1s")
    target = 1 if end is None else end
    steps = max(1, min(abs(start - target) + 1, over_seconds // MIN_FADE_STEP_SECONDS))
    if steps == 1:
        levels = [start if start == target else target]
    else:
        levels = [int(start + (target - start) * index / (steps - 1) + 0.5) for index in range(steps)]

    subject = f"color {parse_rgb_label(color)}" if color is not None else f"white {white}K"
    description = fade_description(
        color=color, white=white, start=start, end=end, over_seconds=over_seconds
    )
    lines = [f"# {description}"]
    for index, level in enumerate(levels):
        lines.append(f"{subject} {level}")
        if end is None:
            lines.append(f"wait {_format_wait(over_seconds / steps)}")
        elif index < steps - 1:
            lines.append(f"wait {_format_wait(over_seconds / (steps - 1))}")
    if end is None:
        lines.append("off")
    return "\n".join(lines) + "\n"


# --- sunrise ---------------------------------------------------------------

SUNRISE_DEFAULT_SECONDS = 30 * 60
SUNRISE_MIN_SECONDS = 60
SUNRISE_MAX_SECONDS = 6 * 60 * 60
SUNRISE_MIN_STEP_SECONDS = 5.0
SUNRISE_MAX_INTERVALS = 300
# (fraction of the ramp, RGB colour, brightness): deep red, orange, warm white.
# The final colour was picked on the tested strip: the RGB white ffd6aa looked bluish, so the
# ramp ends at the warmer ffb060.
SUNRISE_KEYFRAMES: tuple[tuple[float, tuple[int, int, int], int], ...] = (
    (0.00, (0xFF, 0x00, 0x00), 1),
    (0.30, (0xFF, 0x3C, 0x00), 20),
    (0.60, (0xFF, 0x7A, 0x14), 55),
    (0.85, (0xFF, 0xA6, 0x48), 85),
    (1.00, (0xFF, 0xB0, 0x60), 100),
)
_TIME_OF_DAY_PATTERN = re.compile(r"([0-9]{1,2}):([0-9]{2})")


def sunrise_over_argument(text: str) -> int:
    """argparse ``type=`` for ``sunrise --over``: 1 minute through 6 hours."""

    seconds = duration_argument(text)
    if not SUNRISE_MIN_SECONDS <= seconds <= SUNRISE_MAX_SECONDS:
        raise argparse.ArgumentTypeError(
            f"--over {text!r} is out of range: a sunrise lasts from 1m to 6h"
        )
    return seconds


def time_of_day_argument(text: str) -> tuple[int, int]:
    """argparse ``type=`` for ``--at``: 24-hour ``HH:MM`` as (hour, minute)."""

    match = _TIME_OF_DAY_PATTERN.fullmatch(text.strip())
    if match is None or int(match.group(1)) > 23 or int(match.group(2)) > 59:
        raise argparse.ArgumentTypeError(
            f"invalid time {text!r}: use 24-hour HH:MM, such as 07:00"
        )
    return int(match.group(1)), int(match.group(2))


def sunrise_frame(fraction: float) -> tuple[tuple[int, int, int], int]:
    """Return the interpolated RGB colour and brightness at ``fraction`` of the ramp."""

    fraction = min(1.0, max(0.0, fraction))
    for (start, color_a, level_a), (end, color_b, level_b) in zip(SUNRISE_KEYFRAMES, SUNRISE_KEYFRAMES[1:]):
        if fraction <= end:
            weight = (fraction - start) / (end - start)
            color = tuple(
                min(255, max(0, int(a + (b - a) * weight + 0.5))) for a, b in zip(color_a, color_b)
            )
            level = min(100, max(1, int(level_a + (level_b - level_a) * weight + 0.5)))
            return (color[0], color[1], color[2]), level
    raise AssertionError("keyframes cover the whole ramp")


def sunrise_description(over_seconds: int, wake_clock: str) -> str:
    """Return the short description shown by ``timers``."""

    return f"sunrise {format_duration(over_seconds)} ending {wake_clock}"


def sunrise_program_text(over_seconds: int, *, description: str | None = None) -> str:
    """Generate a sunrise ramp as ``.blemesh`` text using only existing instructions.

    Only RGB ``color`` commands are used (a ``color`` turns a light that is off on
    by itself, and the white channel's temperature is not trustworthy). The ramp
    has at most :data:`SUNRISE_MAX_INTERVALS` + 1 steps, at least
    :data:`SUNRISE_MIN_STEP_SECONDS` apart, and the last step is the final
    keyframe at the end of the ramp. A step identical to its predecessor is
    dropped and its wait merged into the previous one. There is no final ``off``.
    """

    if not SUNRISE_MIN_SECONDS <= over_seconds <= SUNRISE_MAX_SECONDS:
        raise TimerError("a sunrise lasts from 1m to 6h")
    intervals = max(1, int(over_seconds // max(SUNRISE_MIN_STEP_SECONDS, over_seconds / SUNRISE_MAX_INTERVALS)))
    spacing = over_seconds / intervals
    steps: list[tuple[str, float]] = []  # (command, wait after it)
    for index in range(intervals + 1):
        (red, green, blue), level = sunrise_frame(index / intervals)
        command = f"color {red:02x}{green:02x}{blue:02x} {level}"
        if steps and steps[-1][0] == command:
            steps[-1] = (command, steps[-1][1] + spacing)
        else:
            steps.append((command, spacing))
    lines = [f"# {description or 'sunrise ' + format_duration(over_seconds)}"]
    for index, (command, wait) in enumerate(steps):
        lines.append(command)
        if index < len(steps) - 1:
            lines.append(f"wait {_format_wait(wait)}")
    return "\n".join(lines) + "\n"


@dataclass(frozen=True)
class SunrisePlan:
    """When a ``sunrise --at`` ramp starts and ends."""

    start_at: float
    wake_at: float
    over_seconds: int
    compressed: bool


def next_local_time(hour: int, minute: int, now: float) -> float:
    """Return the first instant after ``now`` at which the local clock reads hour:minute.

    The wall time is converted as a naive local datetime, so the C library
    applies DST. A repeated time (autumn) resolves to the first occurrence still
    ahead; a skipped time (spring) resolves to the instant the library gives it.
    """

    today = datetime.fromtimestamp(now).date()
    for offset in range(3):
        day = today + timedelta(days=offset)
        candidates = []
        for fold in (0, 1):
            naive = datetime.combine(day, dt_time(hour, minute, fold=fold))
            stamp = naive.timestamp()
            seen = datetime.fromtimestamp(stamp)
            if (seen.hour, seen.minute) == (hour, minute):
                candidates.append(stamp)
        if not candidates:  # a time skipped by the spring clock change
            candidates.append(datetime.combine(day, dt_time(hour, minute)).timestamp())
        future = [stamp for stamp in candidates if stamp > now]
        if future:
            return min(future)
    raise TimerError(f"could not find the next {hour:02d}:{minute:02d}")


def plan_sunrise_at(hour: int, minute: int, over_seconds: int, now: float) -> SunrisePlan:
    """Plan a ramp that ends at the next ``hour:minute``, compressing it if it must start late."""

    wake_at = next_local_time(hour, minute, now)
    start_at = wake_at - over_seconds
    if start_at >= now:
        return SunrisePlan(start_at, wake_at, over_seconds, False)
    remaining = int(wake_at - now)
    if remaining < SUNRISE_MIN_SECONDS:
        raise TimerError(
            "too close to the wake time for a ramp; use a shorter --over or a later time"
        )
    return SunrisePlan(now, wake_at, remaining, True)


def format_wake_time(timestamp: float) -> str:
    """Format a wake time as ``Sun 15 Nov 07:00``."""

    moment = datetime.fromtimestamp(timestamp)
    return f"{moment:%a} {moment.day} {moment:%b %H:%M}"


# --- program analysis ------------------------------------------------------


def count_commands(program: Sequence[ScriptStep]) -> int | None:
    """Return how many commands a program sends, or None when it never ends."""

    total = 0
    for step in program:
        if isinstance(step, LightCommand):
            total += 1
        elif isinstance(step, Rainbow):
            total += len(RAINBOW_COLORS)
        elif isinstance(step, Loop):
            if step.count is None:
                return None
            inner = count_commands(step.body)
            if inner is None:
                return None
            total += step.count * inner
    return total


def longest_wait(program: Sequence[ScriptStep]) -> float:
    longest = 0.0
    for step in program:
        if isinstance(step, (Delay, Rainbow)):
            longest = max(longest, step.seconds)
        elif isinstance(step, Loop):
            longest = max(longest, longest_wait(step.body))
    return longest


def final_off_line(program: Sequence[ScriptStep]) -> int | None:
    """Return the source line of a top-level ``off`` that ends the program."""

    for step in reversed(program):
        if isinstance(step, Delay):
            continue
        if (
            isinstance(step, LightCommand)
            and step.opcode == COMMAND_ON_OFF
            and step.parameters == off_parameters()
        ):
            return step.line
        return None
    return None


# --- records ---------------------------------------------------------------


def timers_directory() -> Path:
    directory = runtime_directory() / "timers"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    return directory


def record_path(timer_id: str) -> Path:
    if not _ID_PATTERN.fullmatch(timer_id):
        raise TimerError(f"invalid timer id {timer_id!r}")
    return timers_directory() / f"{timer_id}.json"


def write_record(path: Path, record: dict[str, Any]) -> None:
    """Atomically replace ``path`` with a private (0600) JSON file."""

    temporary = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(record, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
        raise


def read_record(path: Path) -> dict[str, Any]:
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise TimerError(f"no timer {path.stem!r}") from None
    except (OSError, ValueError) as exc:
        raise TimerError(f"{path}: could not read timer record: {exc}") from exc
    if not isinstance(record, dict) or not isinstance(record.get("id"), str):
        raise TimerError(f"{path}: not a timer record")
    return record


def update_record(path: Path, **fields: Any) -> dict[str, Any]:
    record = read_record(path)
    record.update(fields)
    write_record(path, record)
    return record


def load_records() -> list[tuple[Path, dict[str, Any]]]:
    """Return every readable record by fire time; unreadable ones are reported."""

    found: list[tuple[Path, dict[str, Any]]] = []
    for path in sorted(timers_directory().glob("*.json")):
        try:
            found.append((path, read_record(path)))
        except TimerError as exc:
            print(f"blemeshctl: warning: {exc}", file=sys.stderr)
    found.sort(key=lambda item: (item[1].get("fire_at", 0), item[1]["id"]))
    return found


def _new_record(
    *,
    timer_id: str,
    now: float,
    delay_seconds: int,
    description: str,
    target: str,
    addresses: Sequence[str],
    is_group: bool,
    connection: dict[str, Any],
    program_text: str,
) -> dict[str, Any]:
    return {
        "version": RECORD_VERSION,
        "id": timer_id,
        "state": STATE_SCHEDULED,
        "pid": None,
        "pid_start": None,
        "created": now,
        "fire_at": now + delay_seconds,
        "started_at": None,
        "finished_at": None,
        "description": description,
        "target": target,
        "addresses": list(addresses),
        "is_group": is_group,
        "connection": connection,
        "steps": {"total": None, "executed": 0, "ok": 0, "partial": 0, "failed": 0},
        "first_error": None,
        "last_error": None,
        "outcome": None,
        "program": program_text,
    }


# --- processes -------------------------------------------------------------


def process_start_time(pid: int) -> int | None:
    """Return the Linux start time (clock ticks since boot) of a live process."""

    try:
        text = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
        fields = text[text.rindex(")") + 2 :].split()
        if fields[0] == "Z":
            return None
        return int(fields[19])
    except (OSError, ValueError, IndexError):
        return None


def runner_is_alive(record: dict[str, Any]) -> bool:
    """True only when the recorded pid is the same process that started the timer."""

    pid = record.get("pid")
    if not isinstance(pid, int):
        return False
    started = process_start_time(pid)
    return started is not None and record.get("pid_start") in (None, started)


def spawn_runner(path: Path, credentials: dict[str, str]) -> None:
    """Start the detached runner; credentials go over stdin, never argv or disk."""

    if getattr(sys, "frozen", False):
        command = [sys.executable, INTERNAL_TIMER_ARGUMENT, str(path)]
        environment: dict[str, str] | None = os.environ.copy()
        assert environment is not None
        # The runner outlives the CLI, so a one-file PyInstaller child needs its
        # own extraction directory, exactly like the keepalive daemon.
        environment["PYINSTALLER_RESET_ENVIRONMENT"] = "1"
    else:
        command = [sys.executable, "-m", "blemeshctl.timers", str(path)]
        environment = None
    process = subprocess.Popen(
        command,
        close_fds=True,
        start_new_session=True,
        stdin=subprocess.PIPE,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        env=environment,
    )
    assert process.stdin is not None
    try:
        process.stdin.write(json.dumps(credentials).encode())
    finally:
        process.stdin.close()


def schedule_timer(
    *,
    program_text: str,
    description: str,
    target: str,
    addresses: Sequence[str],
    is_group: bool,
    delay_seconds: int,
    connection: dict[str, Any],
    credentials: dict[str, str],
    clock: Callable[[], float] = time.time,
    sleep: Callable[[float], None] = time.sleep,
) -> dict[str, Any]:
    """Store a record and start its runner. The program must already be validated."""

    now = clock()
    directory = timers_directory()
    timer_id = secrets.token_hex(3)
    while (directory / f"{timer_id}.json").exists():
        timer_id = secrets.token_hex(3)
    record = _new_record(
        timer_id=timer_id,
        now=now,
        delay_seconds=delay_seconds,
        description=description,
        target=target,
        addresses=addresses,
        is_group=is_group,
        connection=connection,
        program_text=program_text,
    )
    path = record_path(timer_id)
    write_record(path, record)
    try:
        spawn_runner(path, credentials)
    except OSError as exc:
        path.unlink(missing_ok=True)
        raise TimerError(f"could not start the timer runner: {exc}") from exc

    # The runner records its own pid (inside a frozen one-file build it is not
    # the process we spawned), so wait briefly for that hand-over.
    deadline = time.monotonic() + RUNNER_START_TIMEOUT
    while time.monotonic() < deadline:
        record = read_record(path)
        if record.get("pid") is not None:
            break
        sleep(0.05)
    else:
        print(
            f"blemeshctl: warning: timer {timer_id}: the runner has not reported yet; "
            "check 'blemeshctl timers'",
            file=sys.stderr,
        )
    return record


# --- runner ----------------------------------------------------------------


def _finish(path: Path, state: str, outcome: str, clock: Callable[[], float], **fields: Any) -> None:
    update_record(path, state=state, outcome=outcome, finished_at=clock(), **fields)


async def run_timer(
    path: Path,
    credentials: dict[str, str],
    backend_factory: BackendFactory,
    *,
    clock: Callable[[], float] = time.time,
    sleep: Callable[[float], Any] = asyncio.sleep,
) -> str:
    """Wait for the fire time, run the stored program, and record the outcome."""

    record = read_record(path)
    if record.get("state") != STATE_SCHEDULED:
        return str(record.get("state"))
    steps = dict(record["steps"])
    errors: dict[str, str | None] = {"first": None, "last": None}

    def save_progress() -> None:
        update_record(path, steps=steps, first_error=errors["first"], last_error=errors["last"])

    fired = False
    try:
        program = parse_script(record["program"], f"timer {record['id']}")
        fire_at = float(record["fire_at"])
        while True:
            remaining = fire_at - clock()
            if remaining <= 0:
                break
            await sleep(min(remaining, WAKE_CHUNK_SECONDS))
        if read_record(path).get("state") != STATE_SCHEDULED:
            return str(read_record(path).get("state"))
        late = clock() - fire_at
        steps["total"] = count_commands(program)
        update_record(path, state=STATE_RUNNING, started_at=clock(), steps=steps)
        fired = True

        backend = backend_factory(record, credentials)
        total = steps["total"]
        widest_gap = longest_wait(program)
        if widest_gap > MAX_HOLD_WAIT_SECONDS:
            keepalive_middle = MIN_KEEPALIVE_SECONDS
        else:
            keepalive_middle = min(MAX_IDLE_TIMEOUT, max(MIN_KEEPALIVE_SECONDS, 2 * widest_gap))
        off_line = final_off_line(program)

        async def send_command(command: LightCommand) -> None:
            steps["executed"] += 1
            index = steps["executed"]
            keepalive = FINAL_KEEPALIVE_SECONDS if total is not None and index == total else keepalive_middle
            attempts = 1 + (OFF_RETRIES if command.line == off_line else 0)
            result = "failed"
            for attempt in range(attempts):
                try:
                    await backend.send(command.opcode, command.parameters, command.description, keepalive)
                    result = "ok"
                    break
                except Exception as exc:
                    partial = isinstance(exc, StepError) and exc.partial
                    result = "partial" if partial else "failed"
                    message = f"step {index} ({command.description}): {str(exc) or type(exc).__name__}"
                    errors["first"] = errors["first"] or message
                    errors["last"] = message
                if attempt + 1 < attempts:
                    await sleep(OFF_RETRY_SPACING_SECONDS)
            steps[result] += 1
            save_progress()

        async def wait(delay: Delay) -> None:
            await sleep(delay.seconds)

        await execute_script(program, send_command=send_command, wait=wait)
    except asyncio.CancelledError:
        executed = steps["executed"]
        if fired:
            outcome = f"cancelled after {executed} steps; the light keeps its last state"
        else:
            outcome = "cancelled before it fired"
        _finish(path, STATE_CANCELLED, outcome, clock, steps=steps)
        raise
    except Exception as exc:
        _finish(
            path,
            STATE_FAILED,
            f"runner error: {str(exc) or type(exc).__name__}",
            clock,
            steps=steps,
            first_error=errors["first"],
            last_error=errors["last"],
        )
        return STATE_FAILED

    state, outcome = summarize(steps, errors)
    if late >= LATE_NOTE_SECONDS:
        outcome += f"; fired {format_duration(late)} after the scheduled time"
    _finish(path, state, outcome, clock, steps=steps, first_error=errors["first"], last_error=errors["last"])
    return state


def summarize(steps: dict[str, Any], errors: dict[str, str | None]) -> tuple[str, str]:
    """Return the final state and outcome message for a finished run."""

    executed, ok, partial, failed = steps["executed"], steps["ok"], steps["partial"], steps["failed"]
    if ok == executed and executed:
        return STATE_DONE, f"{ok} of {executed} steps acknowledged"
    detail = f"; first error: {errors['first']}"
    if errors["last"] != errors["first"]:
        detail += f"; last error: {errors['last']}"
    if ok == 0 and partial == 0:
        return STATE_FAILED, f"nothing was delivered ({failed} of {executed} steps failed){detail}"
    parts = [f"{ok} of {executed} steps acknowledged"]
    if partial:
        parts.append(f"{partial} reached only some lights")
    if failed:
        parts.append(f"{failed} failed")
    return STATE_PARTIAL, ", ".join(parts) + detail


async def _run_with_signals(
    path: Path, credentials: dict[str, str], backend_factory: BackendFactory
) -> None:
    task = asyncio.current_task()
    assert task is not None
    loop = asyncio.get_running_loop()
    for number in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(number, task.cancel)
    try:
        await run_timer(path, credentials, backend_factory)
    except asyncio.CancelledError:
        pass


def main() -> None:
    """Runner entry point: ``blemeshctl --internal-timer RECORD`` with credentials on stdin."""

    parser = argparse.ArgumentParser(prog="blemeshctl-timer")
    parser.add_argument("record", type=Path, help=argparse.SUPPRESS)
    arguments = parser.parse_args()
    path: Path = arguments.record
    try:
        credentials = json.loads(sys.stdin.read())
        if not (
            isinstance(credentials, dict)
            and isinstance(credentials.get("mesh_name"), str)
            and isinstance(credentials.get("password"), str)
        ):
            raise ValueError("mesh name and password are required")
    except ValueError as exc:
        try:
            _finish(path, STATE_FAILED, f"runner did not receive credentials: {exc}", time.time)
        except (TimerError, OSError):
            pass
        return
    try:
        record = update_record(
            path, pid=os.getpid(), pid_start=process_start_time(os.getpid())
        )
        if record.get("state") != STATE_SCHEDULED:
            return
        from . import cli

        asyncio.run(_run_with_signals(path, credentials, cli.timer_backend))
    except (TimerError, OSError):
        return


# --- user-facing commands --------------------------------------------------


def refresh_record(path: Path, record: dict[str, Any], clock: Callable[[], float] = time.time) -> dict[str, Any]:
    """Mark an active record whose runner vanished as failed."""

    if record.get("state") not in ACTIVE_STATES:
        return record
    if record.get("pid") is None:
        if clock() - float(record.get("created", 0)) <= UNSTARTED_GRACE_SECONDS:
            return record
        message = "runner never started"
    elif runner_is_alive(record):
        return record
    else:
        message = "runner exited unexpectedly"
    current = read_record(path)
    if current.get("state") not in ACTIVE_STATES:
        return current
    return update_record(path, state=STATE_FAILED, outcome=message, finished_at=clock())


def cancel_timer(
    path: Path,
    *,
    clock: Callable[[], float] = time.time,
    sleep: Callable[[float], None] | None = None,
    kill: Callable[[int, int], None] | None = None,
) -> str | None:
    """Stop a timer's runner. Returns None when cancelled, else why it was not."""

    sleep = sleep or time.sleep
    kill = kill or os.kill
    record = refresh_record(path, read_record(path), clock)
    state = record["state"]
    if state not in ACTIVE_STATES:
        return f"timer {record['id']} already finished ({state})"
    if runner_is_alive(record):
        try:
            kill(record["pid"], signal.SIGTERM)
        except ProcessLookupError:
            pass
        deadline = time.monotonic() + CANCEL_EXIT_TIMEOUT
        while runner_is_alive(record) and time.monotonic() < deadline:
            sleep(0.05)
        if runner_is_alive(record):
            try:
                kill(record["pid"], signal.SIGKILL)
            except ProcessLookupError:
                pass
    current = read_record(path)
    if current["state"] in ACTIVE_STATES:
        outcome = (
            "cancelled before it fired"
            if current["state"] == STATE_SCHEDULED
            else "cancelled while running; the light keeps its last state"
        )
        update_record(path, state=STATE_CANCELLED, outcome=outcome, finished_at=clock())
    return None


def _clock_text(timestamp: float, now: float) -> str:
    moment = datetime.fromtimestamp(timestamp)
    if moment.date() == datetime.fromtimestamp(now).date():
        return moment.strftime("%H:%M:%S")
    return moment.strftime("%Y-%m-%d %H:%M:%S")


def format_timer(record: dict[str, Any], now: float) -> list[str]:
    """Return the ``timers`` lines for one record."""

    fire_at = float(record["fire_at"])
    clock_text = _clock_text(fire_at, now)
    if record["state"] == STATE_SCHEDULED:
        remaining = fire_at - now
        when = f"in {format_duration(remaining)} ({clock_text})" if remaining > 0 else f"due now ({clock_text})"
    else:
        when = f"at {clock_text}"
    lines = [
        f"{record['id']}  {record['state']:<9}  {when}  {record['target']}  {record['description']}"
    ]
    steps = record.get("steps") or {}
    if record["state"] == STATE_RUNNING:
        total = steps.get("total")
        of = f" of {total}" if total is not None else ""
        lines.append(f"    progress: {steps.get('executed', 0)}{of} steps")
    elif record.get("outcome"):
        lines.append(f"    {record['outcome']}")
    return lines


def _prune_finished(clock: Callable[[], float]) -> None:
    now = clock()
    for path, record in load_records():
        finished = record.get("finished_at")
        if record.get("state") not in ACTIVE_STATES and finished and now - float(finished) > FINISHED_RETENTION_SECONDS:
            path.unlink(missing_ok=True)


def run_timers_command(arguments: argparse.Namespace, clock: Callable[[], float] = time.time) -> int:
    """Handle ``timers``, ``timers list``, ``timers cancel``, and ``timers clear``."""

    action = getattr(arguments, "timers_action", None) or "list"
    if action == "cancel":
        if arguments.all == (arguments.id is not None):
            raise TimerError("give a timer id or --all (but not both)")
        if arguments.id is not None:
            targets = [record_path(arguments.id)]
        else:
            targets = [
                path
                for path, record in load_records()
                if refresh_record(path, record, clock)["state"] in ACTIVE_STATES
            ]
            if not targets:
                print("No active timers.")
                return 0
        status = 0
        for path in targets:
            reason = cancel_timer(path, clock=clock)
            if reason is None:
                print(f"Cancelled {path.stem}.")
            else:
                print(f"blemeshctl: {reason}", file=sys.stderr)
                status = 1
        return status

    _prune_finished(clock)
    records = [(path, refresh_record(path, record, clock)) for path, record in load_records()]
    if action == "clear":
        removed = 0
        for path, record in records:
            if record["state"] not in ACTIVE_STATES:
                path.unlink(missing_ok=True)
                removed += 1
        print(f"Cleared {removed} finished timer{'s' if removed != 1 else ''}.")
        return 0
    if not records:
        print("No timers.")
    now = clock()
    for _, record in records:
        for line in format_timer(record, now):
            print(line)
    return 0


def add_timers_parser(subcommands: argparse._SubParsersAction) -> None:
    timers = subcommands.add_parser(
        "timers",
        help="list, cancel, or clear delayed commands",
        description=(
            "Delayed commands (--in) and fades are run by a background process of this login session. "
            "They are lost on reboot or logout. With no action, list them."
        ),
    )
    actions = timers.add_subparsers(dest="timers_action")
    actions.add_parser("list", help="list timers (the default)")
    cancel = actions.add_parser("cancel", help="stop a timer's background runner")
    cancel.add_argument("id", nargs="?", help="timer id from 'timers'")
    cancel.add_argument("--all", action="store_true", help="cancel every scheduled or running timer")
    actions.add_parser("clear", help="remove finished, failed, and cancelled timer records")


if __name__ == "__main__":
    # Re-import so exception classes match the ones cli.py sees under ``-m``.
    from blemeshctl.timers import main as _main

    _main()
