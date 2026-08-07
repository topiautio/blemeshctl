"""Parser and executor for small declarative light-control scripts.

The format intentionally exposes only ordinary on, off, and RGB controls.  It
never evaluates Python, shell commands, raw Telink packets, or other arbitrary
input from a script file.
"""

from __future__ import annotations

import math
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from pathlib import Path

from .protocol import (
    COMMAND_ON_OFF,
    COMMAND_RGB,
    TelinkProtocolError,
    off_parameters,
    on_parameters,
    parse_rgb,
    rgb_parameters,
)


class ScriptError(ValueError):
    """Raised when a light-control script cannot be read or validated."""


@dataclass(frozen=True)
class LightCommand:
    """One validated normal light command with its source line."""

    line: int
    description: str
    opcode: int
    parameters: bytes
    report: bool = True


@dataclass(frozen=True)
class Delay:
    """One positive script delay with its source line."""

    line: int
    seconds: float
    report: bool = True


@dataclass(frozen=True)
class Rainbow:
    """One complete, fully saturated RGB hue sweep with a dwell per colour."""

    line: int
    seconds: float


@dataclass(frozen=True)
class Loop:
    """A finite or endless block of script steps."""

    line: int
    count: int | None
    body: tuple["ScriptStep", ...]


ScriptStep = LightCommand | Delay | Rainbow | Loop
CommandSender = Callable[[LightCommand], Awaitable[None]]
DelayWaiter = Callable[[Delay], Awaitable[None]]


@dataclass(frozen=True)
class _SourceLine:
    number: int
    indentation: int
    text: str


def _build_rainbow_colors() -> tuple[tuple[int, int, int], ...]:
    """Return every distinct fully saturated 8-bit RGB hue in wheel order."""

    return tuple(
        [(255, value, 0) for value in range(255)]
        + [(255 - value, 255, 0) for value in range(255)]
        + [(0, 255, value) for value in range(255)]
        + [(0, 255 - value, 255) for value in range(255)]
        + [(value, 0, 255) for value in range(255)]
        + [(255, 0, 255 - value) for value in range(255)]
    )


RAINBOW_COLORS = _build_rainbow_colors()


def _error(source: str, line: _SourceLine, message: str) -> ScriptError:
    return ScriptError(f"{source}:{line.number}: {message}")


def _source_lines(text: str, source: str) -> tuple[_SourceLine, ...]:
    lines: list[_SourceLine] = []
    for number, raw in enumerate(text.splitlines(), start=1):
        uncommented = raw.lstrip(" \t")
        if not uncommented or uncommented.startswith("#"):
            continue

        indentation_text = raw[: len(raw) - len(uncommented)]
        if "\t" in indentation_text:
            line = _SourceLine(number, 0, uncommented.rstrip())
            raise _error(source, line, "tabs are not supported for indentation")
        lines.append(_SourceLine(number, len(indentation_text), uncommented.rstrip()))
    return tuple(lines)


def _parse_duration(value: str, source: str, line: _SourceLine) -> float:
    lowered = value.lower()
    multiplier = 1.0
    for suffix, suffix_multiplier in (("ms", 0.001), ("s", 1.0), ("m", 60.0)):
        if lowered.endswith(suffix):
            lowered = lowered[: -len(suffix)]
            multiplier = suffix_multiplier
            break
    try:
        seconds = float(lowered) * multiplier
    except ValueError as exc:
        raise _error(source, line, "wait must be a positive duration such as 250ms, 1s, or 2m") from exc
    if not math.isfinite(seconds) or seconds <= 0:
        raise _error(source, line, "wait must be a positive, finite duration")
    return seconds


def _parse_instruction(line: _SourceLine, source: str) -> LightCommand | Delay | Rainbow:
    words = line.text.split()
    instruction = words[0]
    if instruction == "on":
        if len(words) != 1:
            raise _error(source, line, "on does not take arguments")
        return LightCommand(line.number, "on", COMMAND_ON_OFF, on_parameters())
    if instruction == "off":
        if len(words) != 1:
            raise _error(source, line, "off does not take arguments")
        return LightCommand(line.number, "off", COMMAND_ON_OFF, off_parameters())
    if instruction == "color":
        if len(words) not in (2, 3):
            raise _error(source, line, "color requires RRGGBB and optional brightness from 1 through 100")
        try:
            red, green, blue = parse_rgb(words[1])
        except TelinkProtocolError as exc:
            raise _error(source, line, str(exc)) from exc
        brightness = 100
        if len(words) == 3:
            try:
                brightness = int(words[2])
            except ValueError as exc:
                raise _error(source, line, "brightness must be an integer from 1 through 100") from exc
        try:
            parameters = rgb_parameters(red, green, blue, brightness)
        except TelinkProtocolError as exc:
            raise _error(source, line, str(exc)) from exc
        return LightCommand(
            line.number,
            f"colour rgb #{red:02x}{green:02x}{blue:02x}, brightness {brightness}",
            COMMAND_RGB,
            parameters,
        )
    if instruction == "wait":
        if len(words) != 2:
            raise _error(source, line, "wait requires one positive duration")
        return Delay(line.number, _parse_duration(words[1], source, line))
    if instruction == "rainbow":
        if len(words) != 2:
            raise _error(source, line, "rainbow requires one positive duration per colour")
        return Rainbow(line.number, _parse_duration(words[1], source, line))
    if instruction in {"loop", "repeat"}:
        raise _error(source, line, f"{instruction} blocks must end with ':'")
    if line.text.endswith(":"):
        raise _error(source, line, "only 'loop:' and 'repeat N:' can introduce a block")
    raise _error(source, line, f"unknown instruction {instruction!r}")


def _parse_loop_header(line: _SourceLine, source: str) -> tuple[bool, int | None]:
    if line.text == "loop:":
        return True, None
    if line.text == "loop" or line.text.startswith("loop "):
        raise _error(source, line, "loop blocks must be written as 'loop:'")
    if line.text == "repeat:" or line.text.startswith("repeat "):
        if not line.text.endswith(":"):
            raise _error(source, line, "repeat blocks must be written as 'repeat N:'")
        words = line.text[:-1].split()
        if len(words) != 2:
            raise _error(source, line, "repeat requires one positive integer count")
        count_text = words[1]
        if not count_text.isascii() or not count_text.isdigit() or int(count_text) <= 0:
            raise _error(source, line, "repeat count must be a positive integer")
        return True, int(count_text)
    return False, None


def _parse_block(
    lines: Sequence[_SourceLine], source: str, index: int, indentation: int
) -> tuple[tuple[ScriptStep, ...], int]:
    steps: list[ScriptStep] = []
    while index < len(lines):
        line = lines[index]
        if line.indentation < indentation:
            break
        if line.indentation > indentation:
            raise _error(source, line, "unexpected indentation; indent only below a loop or repeat block")

        is_loop, count = _parse_loop_header(line, source)
        if not is_loop:
            steps.append(_parse_instruction(line, source))
            index += 1
            continue

        index += 1
        if index == len(lines) or lines[index].indentation <= indentation:
            raise _error(source, line, "loop or repeat block needs an indented body")
        body_indentation = lines[index].indentation
        body, index = _parse_block(lines, source, index, body_indentation)
        steps.append(Loop(line.number, count, body))
    return tuple(steps), index


def _contains_light_command(steps: Sequence[ScriptStep]) -> bool:
    return any(
        isinstance(step, (LightCommand, Rainbow))
        or (isinstance(step, Loop) and _contains_light_command(step.body))
        for step in steps
    )


def _contains_delay(steps: Sequence[ScriptStep]) -> bool:
    return any(
        isinstance(step, (Delay, Rainbow)) or (isinstance(step, Loop) and _contains_delay(step.body))
        for step in steps
    )


def _validate_loops(steps: Sequence[ScriptStep], source: str) -> None:
    for step in steps:
        if not isinstance(step, Loop):
            continue
        _validate_loops(step.body, source)
        if step.count is None and not _contains_delay(step.body):
            raise ScriptError(f"{source}:{step.line}: an endless loop needs at least one wait command")


def parse_script(text: str, source: str = "<script>") -> tuple[ScriptStep, ...]:
    """Parse and validate an entire script before it can send any command."""

    lines = _source_lines(text, source)
    if not lines:
        raise ScriptError(f"{source}: script contains no instructions")
    if lines[0].indentation:
        raise _error(source, lines[0], "the first instruction must not be indented")
    program, index = _parse_block(lines, source, 0, 0)
    if index != len(lines):
        raise _error(source, lines[index], "unexpected indentation")
    if not _contains_light_command(program):
        raise ScriptError(f"{source}: script contains no light commands")
    _validate_loops(program, source)
    return program


def load_script(path: Path) -> tuple[ScriptStep, ...]:
    """Read UTF-8 script text from ``path`` and return its validated program."""

    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise ScriptError(f"{path}: could not read script: {exc}") from exc
    return parse_script(text, str(path))


async def execute_script(
    program: Sequence[ScriptStep],
    *,
    send_command: CommandSender,
    wait: DelayWaiter,
) -> None:
    """Run a previously validated program in source order.

    Errors from ``send_command`` and cancellation from ``wait`` deliberately
    propagate: a failed Bluetooth write has an unknown outcome, so retrying it
    automatically could make the light do something unexpected.
    """

    async def execute_steps(steps: Sequence[ScriptStep]) -> None:
        for step in steps:
            if isinstance(step, LightCommand):
                await send_command(step)
            elif isinstance(step, Delay):
                await wait(step)
            elif isinstance(step, Rainbow):
                for index, (red, green, blue) in enumerate(RAINBOW_COLORS):
                    await send_command(
                        LightCommand(
                            step.line,
                            f"rainbow colour rgb #{red:02x}{green:02x}{blue:02x}",
                            COMMAND_RGB,
                            rgb_parameters(red, green, blue, 100),
                            report=index == 0,
                        )
                    )
                    await wait(Delay(step.line, step.seconds, report=False))
            elif step.count is None:
                while True:
                    await execute_steps(step.body)
            else:
                for _ in range(step.count):
                    await execute_steps(step.body)

    await execute_steps(program)
