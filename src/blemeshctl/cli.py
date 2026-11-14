"""Command-line interface for ``blemeshctl``."""

from __future__ import annotations

import argparse
import asyncio
import sys
import time
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, NoReturn

from . import config, timers
from .client import (
    BROADCAST_MESH_ADDRESS,
    DEFAULT_CONNECTION_SCAN_TIMEOUT,
    BleMeshError,
    discover_lights,
)
from .config import ConfigError, ResolvedLight
from .daemon import DaemonError, request_daemon, socket_path_for_address
from .protocol import (
    COMMAND_ON_OFF,
    COMMAND_RGB,
    COMMAND_WHITE,
    DEFAULT_MESH_NAME,
    DEFAULT_PASSWORD,
    WHITE_COOLEST_KELVIN,
    WHITE_NEUTRAL_KELVIN,
    WHITE_WARMEST_KELVIN,
    TelinkProtocolError,
    off_parameters,
    on_parameters,
    parse_rgb,
    rgb_parameters,
    white_parameters,
)
from .script import (
    Delay,
    LightCommand,
    ScriptError,
    ScriptStep,
    execute_script,
    load_script,
    parse_script,
)

GROUP_FAILURE_EXIT_CODE = 1

# Sends one command: opcode, parameters, description, and whether to report it.
CommandDispatcher = Callable[[int, bytes, str, bool], Awaitable[None]]


class _WaitForConnectionAction(argparse.BooleanOptionalAction):
    """Record that the user chose --[no-]wait-for-connection explicitly."""

    def __call__(
        self,
        parser: argparse.ArgumentParser,
        namespace: argparse.Namespace,
        values: Any,
        option_string: str | None = None,
    ) -> None:
        super().__call__(parser, namespace, values, option_string)
        namespace.wait_for_connection_explicit = True


def _add_connection_options(
    parser: argparse.ArgumentParser, *, wait_for_connection_default: bool = False
) -> None:
    target = parser.add_mutually_exclusive_group()
    target.add_argument("--address", help="Bluetooth MAC address; required when multiple lights are visible")
    target.add_argument(
        "--light",
        metavar="NAME",
        help="alias, group, or MAC address (see 'alias' and 'group'); a group sends to every member",
    )
    parser.add_argument("--mesh-name", default=DEFAULT_MESH_NAME, help=f"mesh name (default: {DEFAULT_MESH_NAME})")
    parser.add_argument("--password", default=DEFAULT_PASSWORD, help="mesh password")
    parser.add_argument(
        "--scan-timeout",
        type=float,
        default=DEFAULT_CONNECTION_SCAN_TIMEOUT,
        help="maximum advertising scan time in seconds; a group always scans for this long to find its members",
    )
    parser.add_argument("--connect-timeout", type=float, default=20.0, help="GATT connection timeout in seconds")
    parser.add_argument(
        "--wait-for-connection",
        action=_WaitForConnectionAction,
        default=wait_for_connection_default,
        help=(
            "retry a known --address's discovery and initial connection (Ctrl-C stops waiting); "
            "groups wait only when this is given explicitly, and then one unavailable member holds back every step"
        ),
    )
    parser.set_defaults(wait_for_connection_explicit=False)
    parser.add_argument(
        "--keepalive-seconds",
        type=float,
        default=60.0,
        help="keep the authenticated Bluetooth connection open after the command (default: 60)",
    )


def _fade_target(value: str) -> int | None:
    """Parse ``--to``: a brightness from 1 through 100, or ``off`` (returned as None)."""

    if value.lower() == "off":
        return None
    try:
        return int(value)
    except ValueError:
        raise argparse.ArgumentTypeError("--to must be a brightness from 1 through 100, or 'off'") from None


def _add_delay_option(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--in",
        dest="delay_seconds",
        type=timers.duration_argument,
        metavar="DURATION",
        help=(
            "do it later, such as 30s, 20m, 2h, or 1h30m (1s to 24h): a background process of this login "
            "session waits, then sends it; nothing connects before then. List or cancel with 'timers'"
        ),
    )


_CONFIG_LOCATION = "$XDG_CONFIG_HOME/blemeshctl (default ~/.config/blemeshctl)"


def _add_alias_parser(subcommands: argparse._SubParsersAction) -> None:
    alias = subcommands.add_parser(
        "alias",
        help="name lights by MAC address",
        description=(
            "Name lights so --light NAME can replace --address. Stored without mesh credentials in "
            f"lights.json under {_CONFIG_LOCATION}."
        ),
    )
    actions = alias.add_subparsers(dest="alias_action", required=True)
    add = actions.add_parser("add", help="define an alias", description="Define an alias for a MAC address.")
    add.add_argument("name", help="letters, digits, '_' or '-', starting with a letter or digit")
    add.add_argument("mac", help="Bluetooth MAC address such as A4:C1:38:93:1B:72")
    add.add_argument("--force", action="store_true", help="replace the address of an existing alias")
    remove = actions.add_parser(
        "remove", help="remove an alias", description="Remove an alias that no group refers to."
    )
    remove.add_argument("name")
    actions.add_parser("list", help="list aliases")


def _add_group_parser(subcommands: argparse._SubParsersAction) -> None:
    group = subcommands.add_parser(
        "group",
        help="name a set of lights",
        description=(
            "Name a set of lights so --light NAME sends every command to each member. A group "
            "connects to one advertising member and relays to the others by their advertised mesh "
            f"addresses. Stored in lights.json under {_CONFIG_LOCATION}."
        ),
    )
    actions = group.add_subparsers(dest="group_action", required=True)
    add = actions.add_parser(
        "add",
        help="define a group",
        description=(
            "Define a group of MAC addresses and aliases. Groups cannot contain other groups. "
            "Adding an existing group is an error unless --force replaces its members."
        ),
    )
    add.add_argument("name", help="letters, digits, '_' or '-', starting with a letter or digit")
    add.add_argument("members", nargs="+", metavar="MEMBER", help="MAC address or alias")
    add.add_argument("--force", action="store_true", help="replace the members of an existing group")
    remove = actions.add_parser("remove", help="remove a group (its aliases are kept)")
    remove.add_argument("name")
    actions.add_parser("list", help="list groups")


def _add_scene_parser(subcommands: argparse._SubParsersAction) -> None:
    scene = subcommands.add_parser(
        "scene",
        help="save and apply named scenes",
        description=(
            "A scene is a small light-control script saved as scenes/NAME.blemesh under "
            f"{_CONFIG_LOCATION}. It uses the same syntax as 'blemeshctl script'."
        ),
    )
    actions = scene.add_subparsers(dest="scene_action", required=True)

    save = actions.add_parser(
        "save",
        help="save a scene",
        description=(
            "Save a one-instruction scene from --on, --off, --color, or --white, or any script with "
            "--from-file. The text is validated before it is saved. The current state of a light "
            "cannot be read, so a scene cannot be captured from a light."
        ),
    )
    save.add_argument("name", help="letters, digits, '_' or '-', starting with a letter or digit")
    content = save.add_mutually_exclusive_group(required=True)
    content.add_argument("--on", action="store_true", help="turn the light on")
    content.add_argument("--off", action="store_true", help="turn the light off")
    content.add_argument("--color", metavar="RGB", help="solid RGB colour such as 00ff00")
    content.add_argument(
        "--white",
        type=int,
        nargs="?",
        const=WHITE_NEUTRAL_KELVIN,
        metavar="KELVIN",
        help=f"white LEDs at an optional temperature (default {WHITE_NEUTRAL_KELVIN}); give NAME first",
    )
    content.add_argument(
        "--from-file", metavar="PATH", help="script file to validate and save, or '-' for standard input"
    )
    save.add_argument("--brightness", type=int, help="brightness from 1 through 100 for --color or --white")
    save.add_argument("--force", action="store_true", help="replace an existing scene")

    apply = actions.add_parser(
        "apply",
        help="run a saved scene",
        description="Run a saved scene like 'blemeshctl script', on one light or every member of a group.",
    )
    apply.add_argument("name")
    _add_connection_options(apply, wait_for_connection_default=True)
    _add_delay_option(apply)

    actions.add_parser("list", help="list saved scenes")
    show = actions.add_parser("show", help="print a scene's script text")
    show.add_argument("name")
    delete = actions.add_parser("delete", help="delete a saved scene")
    delete.add_argument("name")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="blemeshctl", description="Control legacy Telink BleMesh lights"
    )
    subcommands = parser.add_subparsers(dest="command", required=True)

    scan = subcommands.add_parser("scan", help="list compatible Telink advertisements")
    scan.add_argument("--timeout", type=float, default=8.0, help="scan time in seconds")

    for name, help_text in (("on", "turn a light on"), ("off", "turn a light off")):
        command = subcommands.add_parser(name, help=help_text)
        _add_connection_options(command)
        _add_delay_option(command)

    color = subcommands.add_parser("color", help="set a solid RGB colour")
    color.add_argument("rgb", help="six hexadecimal digits, for example 00ff00")
    color.add_argument("--brightness", type=int, default=100, help="brightness from 1 through 100")
    _add_connection_options(color)
    _add_delay_option(color)

    white = subcommands.add_parser("white", help="switch to the white LEDs instead of an RGB colour")
    white.add_argument(
        "--temperature",
        type=int,
        default=WHITE_NEUTRAL_KELVIN,
        help=(
            f"colour temperature in kelvin from {WHITE_WARMEST_KELVIN} (warm) through "
            f"{WHITE_COOLEST_KELVIN} (cool); the default {WHITE_NEUTRAL_KELVIN} sets both white channels to full"
        ),
    )
    white.add_argument("--brightness", type=int, default=100, help="brightness from 1 through 100")
    _add_connection_options(white)
    _add_delay_option(white)

    script = subcommands.add_parser("script", help="run a light-control script file")
    script.add_argument("script", type=Path, help="path to a script file")
    _add_connection_options(script, wait_for_connection_default=True)
    _add_delay_option(script)

    fade = subcommands.add_parser(
        "fade",
        help="gradually dim a light, optionally turning it off at the end",
        description=(
            "Dim a light from --from to --to over --over by sending a series of colour or white commands "
            "(at most one step per 2 seconds). Lights cannot report their state, so you must say which "
            "colour to dim with --color or --white. No 'on' is sent first; a fade assumes the light is "
            "already on at --from, and --to off (the default) dims to brightness 1 and then sends 'off'. "
            "With --in, a background process runs the fade later (see 'timers')."
        ),
    )
    fade_colour = fade.add_mutually_exclusive_group()
    fade_colour.add_argument("--color", metavar="RGB", help="colour to dim, such as ff8800")
    fade_colour.add_argument(
        "--white",
        type=int,
        nargs="?",
        const=WHITE_NEUTRAL_KELVIN,
        metavar="KELVIN",
        help=f"dim the white LEDs at an optional temperature (default {WHITE_NEUTRAL_KELVIN})",
    )
    fade.add_argument("--from", dest="fade_from", type=int, default=100, metavar="N", help="starting brightness 1-100 (default 100)")
    fade.add_argument(
        "--to", dest="fade_to", type=_fade_target, default=None, metavar="N|off",
        help="final brightness 1-100, or off (default) to dim to 1 and then turn off",
    )
    fade.add_argument("--over", dest="fade_over", type=timers.duration_argument, required=True, metavar="DURATION", help="how long the fade lasts, such as 20m (1s to 24h)")
    _add_connection_options(fade, wait_for_connection_default=True)
    _add_delay_option(fade)
    sunrise = subcommands.add_parser(
        "sunrise",
        help="schedule a gradual wake-up light",
        description=(
            "Schedule a ramp from deep red through orange to a warm white that ends at full brightness. "
            "--at HH:MM is the wake time, when the ramp ends; --in DURATION instead starts the ramp after "
            "that delay. A background process of this login session runs it (see 'timers'; lost on "
            "reboot or logout). The ramp uses RGB colour commands only, which turn the light on by "
            "themselves. The runner holds the light's Bluetooth connection for the whole ramp, so the phone "
            "app cannot control the light until it ends. The light stays on at full brightness afterwards."
        ),
    )
    sunrise_when = sunrise.add_mutually_exclusive_group(required=True)
    sunrise_when.add_argument(
        "--at", dest="sunrise_at", type=timers.time_of_day_argument, metavar="HH:MM",
        help=(
            "wake time in 24-hour local time, such as 07:00: the next occurrence, when the ramp ends. If "
            "the ramp would already have started, it is shortened to the time left (at least 1m)"
        ),
    )
    sunrise_when.add_argument(
        "--in", dest="delay_seconds", type=timers.duration_argument, metavar="DURATION",
        help="start the ramp after this delay, such as 30s, 8h, or 1h30m (1s to 24h)",
    )
    sunrise.add_argument(
        "--over", dest="sunrise_over", type=timers.sunrise_over_argument,
        default=timers.SUNRISE_DEFAULT_SECONDS, metavar="DURATION",
        help="ramp length, 1m to 6h (default 30m)",
    )
    _add_connection_options(sunrise, wait_for_connection_default=True)
    _add_alias_parser(subcommands)
    _add_group_parser(subcommands)
    _add_scene_parser(subcommands)
    timers.add_timers_parser(subcommands)

    daemon = subcommands.add_parser("daemon", help="inspect or stop a local keepalive connection")
    daemon.add_argument("action", choices=("status", "stop"), help="daemon action")
    daemon.add_argument("--address", help="Bluetooth MAC address for the retained connection")
    return parser


def _format_light(light: object, aliases: Sequence[str] = ()) -> str:
    info = light.info
    rssi = "?" if info.rssi is None else str(info.rssi)
    line = (
        f"{info.address}  name={info.name or '-'}  mesh=0x{info.mesh_address:04x}  "
        f"product=0x{info.product_uuid:04x}  status=0x{info.status:02x}  rssi={rssi}"
    )
    if aliases:
        line += f"  alias={','.join(aliases)}"
    return line


def _aliases_by_address() -> dict[str, list[str]]:
    """Return alias names per MAC address; a broken config must not break ``scan``."""

    try:
        registry = config.load_lights()
    except ConfigError as exc:
        print(f"blemeshctl: warning: {exc}", file=sys.stderr)
        return {}
    by_address: dict[str, list[str]] = {}
    for name, address in sorted(registry.aliases.items()):
        by_address.setdefault(address, []).append(name)
    return by_address


def _run_alias_command(arguments: argparse.Namespace) -> int:
    if arguments.alias_action == "add":
        address = config.add_alias(arguments.name, arguments.mac, force=arguments.force)
        print(f"Alias {arguments.name} -> {address}")
    elif arguments.alias_action == "remove":
        config.remove_alias(arguments.name)
        print(f"Alias {arguments.name} removed.")
    else:
        aliases = config.load_lights().aliases
        if not aliases:
            print("No aliases defined.")
        for name, address in sorted(aliases.items()):
            print(f"{name}  {address}")
    return 0


def _run_group_command(arguments: argparse.Namespace) -> int:
    if arguments.group_action == "add":
        addresses = config.add_group(arguments.name, arguments.members, force=arguments.force)
        print(f"Group {arguments.name} -> {', '.join(addresses)}")
    elif arguments.group_action == "remove":
        config.remove_group(arguments.name)
        print(f"Group {arguments.name} removed.")
    else:
        groups = config.load_lights().groups
        if not groups:
            print("No groups defined.")
        for name, members in sorted(groups.items()):
            print(f"{name}  {' '.join(members)}")
    return 0


def _read_scene_input(path: str) -> tuple[str, str]:
    """Return scene text and its source label from ``path`` or standard input."""

    if path == "-":
        return sys.stdin.read(), "<stdin>"
    try:
        return Path(path).read_text(encoding="utf-8"), path
    except (OSError, UnicodeError) as exc:
        raise ScriptError(f"{path}: could not read script: {exc}") from exc


def _run_scene_command(arguments: argparse.Namespace) -> int:
    """Handle every scene action except ``apply``, which needs a light."""

    action = arguments.scene_action
    if action == "save":
        if arguments.from_file is not None:
            if arguments.brightness is not None:
                raise ConfigError("--brightness cannot be combined with --from-file")
            text, source = _read_scene_input(arguments.from_file)
            # Report syntax errors against the input the user actually wrote.
            config.validate_name(arguments.name, "scene name")
            parse_script(text, source)
        else:
            text = config.scene_text(
                on=arguments.on,
                off=arguments.off,
                color=arguments.color,
                white=arguments.white,
                brightness=arguments.brightness,
            )
        path = config.save_scene(arguments.name, text, force=arguments.force)
        print(f"Scene {arguments.name} saved to {path}")
    elif action == "list":
        names = config.list_scenes()
        if not names:
            print("No scenes saved.")
        for name in names:
            print(name)
    elif action == "show":
        sys.stdout.write(config.read_scene(arguments.name))
    elif action == "delete":
        config.delete_scene(arguments.name)
        print(f"Scene {arguments.name} deleted.")
    else:
        raise AssertionError(f"unhandled scene action {action!r}")
    return 0


async def _send_command(
    arguments: argparse.Namespace,
    socket_path: Path,
    opcode: int,
    parameters: bytes,
    description: str,
    *,
    report: bool = True,
) -> None:
    request = {
        "type": "command",
        "opcode": opcode,
        "parameters": parameters.hex(),
        "address": arguments.address,
        "mesh_name": arguments.mesh_name,
        "password": arguments.password,
        "scan_timeout": arguments.scan_timeout,
        "connect_timeout": arguments.connect_timeout,
        "idle_timeout": arguments.keepalive_seconds,
    }
    waiting_reported = False
    while True:
        try:
            response = await request_daemon(request, socket_path=socket_path)
            break
        except DaemonError as exc:
            if not arguments.wait_for_connection or not exc.retryable:
                raise
            if not waiting_reported:
                target = arguments.address or "a compatible Telink light"
                print(f"Waiting for {target} to advertise and accept a Bluetooth connection; press Ctrl-C to stop.")
                waiting_reported = True
            await asyncio.sleep(1.0)

    if report:
        connection = "reused" if response["reused"] else "opened"
        print(
            f"{description} command sent to {response['address']} "
            f"(mesh 0x{response['mesh_address']:04x}; connection {connection}, "
            f"kept for {response['idle_timeout']:g}s after the last command)"
        )


def _describe_error(exc: BaseException) -> str:
    return str(exc) or type(exc).__name__


def _single_light_dispatcher(arguments: argparse.Namespace, socket_path: Path) -> CommandDispatcher:
    async def dispatch(opcode: int, parameters: bytes, description: str, report: bool) -> None:
        await _send_command(arguments, socket_path, opcode, parameters, description, report=report)

    return dispatch


@dataclass(frozen=True)
class _GroupMember:
    """A group member whose mesh address is known for this run."""

    address: str
    mesh_address: int
    rssi: int | None
    connected: bool


@dataclass
class _GroupPlan:
    """Which members can be addressed, and why the others cannot."""

    addressable: dict[str, _GroupMember] = field(default_factory=dict)
    unaddressable: dict[str, str] = field(default_factory=dict)
    permanent: set[str] = field(default_factory=set)


async def _connected_mesh_address(address: str) -> int | None:
    """Return the mesh address of a member its own daemon is connected to.

    A connected light may stop advertising, so a scan alone would miss it.
    """

    try:
        response = await request_daemon(
            {"type": "status"}, start_if_needed=False, socket_path=socket_path_for_address(address)
        )
    except (DaemonError, OSError, asyncio.TimeoutError):
        return None
    mesh_address = response.get("mesh_address")
    if (
        response.get("connected") is not True
        or str(response.get("address", "")).upper() != address
        or isinstance(mesh_address, bool)
        or not isinstance(mesh_address, int)
    ):
        return None
    return mesh_address


async def _plan_group(arguments: argparse.Namespace, light: ResolvedLight) -> _GroupPlan:
    """Find each member's mesh address with one scan plus daemon status queries."""

    connected = {address: await _connected_mesh_address(address) for address in light.addresses}
    try:
        lights = await discover_lights(arguments.scan_timeout)
    except Exception as exc:
        raise _GroupScanFailed(_describe_error(exc)) from exc
    seen = {found.info.address.upper(): found.info for found in lights}

    plan = _GroupPlan()
    for address in light.addresses:
        mesh_address = connected[address]
        if mesh_address is not None:
            member = _GroupMember(address, mesh_address, None, connected=True)
        elif address in seen:
            info = seen[address]
            member = _GroupMember(address, info.mesh_address, info.rssi, connected=False)
        else:
            plan.unaddressable[address] = (
                f"not seen advertising during the {arguments.scan_timeout:g}s scan, "
                "so its mesh address is unknown"
            )
            continue
        if member.mesh_address == BROADCAST_MESH_ADDRESS:
            plan.unaddressable[address] = f"advertises the broadcast mesh address 0x{BROADCAST_MESH_ADDRESS:04x}"
            plan.permanent.add(address)
            continue
        plan.addressable[address] = member

    for address, member in plan.addressable.items():
        sharing = [
            other
            for other, info in seen.items()
            if other != address and info.mesh_address == member.mesh_address
        ]
        if sharing:
            print(
                f"blemeshctl: warning: {address} shares mesh 0x{member.mesh_address:04x} with "
                f"{', '.join(sharing)}; commands to it also reach those lights",
                file=sys.stderr,
            )
    return plan


def _gateway_order(plan: _GroupPlan, light: ResolvedLight) -> list[str]:
    """Prefer an already-connected member, then the strongest signal."""

    position = {address: index for index, address in enumerate(light.addresses)}

    def key(member: _GroupMember) -> tuple[int, int, int, int]:
        return (
            0 if member.connected else 1,
            0 if member.rssi is not None else 1,
            -(member.rssi or 0),
            position[member.address],
        )

    return [member.address for member in sorted(plan.addressable.values(), key=key)]


class _GroupScanFailed(Exception):
    """The Bluetooth scan that locates group members failed."""


class _GroupStopped(Exception):
    """The group run cannot continue; the details were already reported."""


class _GroupRelay:
    """Send every step through one gateway connection, relayed by mesh address.

    Only the write to the gateway is acknowledged. A failed write has an
    unknown outcome and loses the connection, so the whole group stops: there
    is no retry and no change of gateway once a command has been delivered.
    """

    def __init__(
        self, arguments: argparse.Namespace, light: ResolvedLight, plan: _GroupPlan, *, wait: bool
    ) -> None:
        self.arguments = arguments
        self.light = light
        self.plan = plan
        self.wait = wait
        self.gateway: str | None = None
        self.steps_completed = 0
        self.stopped = False
        self.failed: set[str] = set(plan.unaddressable)
        self._candidates = _gateway_order(plan, light)

    def _request(self, gateway: str, opcode: int, parameters: bytes, target: int | None) -> dict[str, Any]:
        request: dict[str, Any] = {
            "type": "command",
            "opcode": opcode,
            "parameters": parameters.hex(),
            "address": gateway,
            "mesh_name": self.arguments.mesh_name,
            "password": self.arguments.password,
            "scan_timeout": self.arguments.scan_timeout,
            "connect_timeout": self.arguments.connect_timeout,
            "idle_timeout": self.arguments.keepalive_seconds,
        }
        # The gateway's own command keeps the daemon's default destination.
        if target is not None:
            request["target_mesh_address"] = target
        return request

    def _relayed(self, gateway: str) -> list[_GroupMember]:
        return [member for address, member in self.plan.addressable.items() if address != gateway]

    async def _open_gateway(self, opcode: int, parameters: bytes, description: str) -> None:
        """Deliver the first command to a gateway's own light, failing over before any delivery."""

        waiting_reported = False
        while True:
            for index, candidate in enumerate(self._candidates):
                try:
                    response = await request_daemon(
                        self._request(candidate, opcode, parameters, None),
                        socket_path=socket_path_for_address(candidate),
                    )
                except Exception as exc:
                    if not isinstance(exc, DaemonError) or not exc.retryable:
                        unsent = [member.address for member in self._relayed(candidate)]
                        self._stop(candidate, exc, candidate, description, sent=[], unsent=unsent)
                    # While waiting, report each candidate's error only once.
                    if not waiting_reported:
                        remaining = self._candidates[index + 1 :]
                        hint = f"; trying {remaining[0]} as the gateway" if remaining else ""
                        print(f"blemeshctl: error: {candidate}: {_describe_error(exc)}{hint}", file=sys.stderr)
                    continue
                self.gateway = candidate
                self._report_gateway(response)
                return
            if not self.wait:
                print(
                    f"blemeshctl: group {self.light.name}: no member accepted a Bluetooth connection; "
                    "nothing was sent",
                    file=sys.stderr,
                )
                self.failed.update(self.light.addresses)
                raise _GroupStopped
            if not waiting_reported:
                print(
                    f"Waiting for a member of group {self.light.name} to accept a Bluetooth connection; "
                    "press Ctrl-C to stop."
                )
                waiting_reported = True
            await asyncio.sleep(1.0)

    def _report_gateway(self, response: dict[str, Any]) -> None:
        connection = "reused" if response["reused"] else "opened"
        line = (
            f"Group {self.light.name}: connected through {response['address']} "
            f"(mesh 0x{response['mesh_address']:04x}; connection {connection}, "
            f"kept for {response['idle_timeout']:g}s after the last command)"
        )
        relayed = self._relayed(response["address"])
        if relayed:
            line += "; relaying to " + ", ".join(
                f"{member.address} (mesh 0x{member.mesh_address:04x})" for member in relayed
            )
        print(line)

    def _stop(
        self,
        target: str,
        error: BaseException,
        gateway: str,
        description: str,
        *,
        sent: list[str],
        unsent: list[str],
    ) -> NoReturn:
        """Report a write with an unknown outcome and stop the whole group."""

        # The failure belongs to the gateway link, even when relaying to another light.
        culprit = target if target == gateway else f"{target} via {gateway}"
        print(f"blemeshctl: error: {culprit}: {_describe_error(error)}", file=sys.stderr)
        details = [f"sent to {', '.join(sent)}"] if sent else []
        details.append(f"outcome unknown for {target}")
        if unsent:
            details.append(f"not sent to {', '.join(unsent)}")
        if self.steps_completed:
            steps = "step was" if self.steps_completed == 1 else "steps were"
            details.append(f"{self.steps_completed} earlier {steps} sent to every addressed light")
        print(
            f"blemeshctl: group {self.light.name} stopped because the write through {gateway} failed; "
            f"no further commands will be sent. {description}: {'; '.join(details)}",
            file=sys.stderr,
        )
        self.stopped = True
        self.failed.add(target)
        self.failed.update(unsent)
        raise _GroupStopped from error

    async def send(self, opcode: int, parameters: bytes, description: str, report: bool) -> None:
        """Send one step to every addressable member before the program continues."""

        sent: list[str] = []
        if self.gateway is None:
            await self._open_gateway(opcode, parameters, description)
            assert self.gateway is not None
            sent.append(self.gateway)
        gateway = self.gateway
        relayed = self._relayed(gateway)
        targets: list[tuple[str, int | None]] = [] if sent else [(gateway, None)]
        targets += [(member.address, member.mesh_address) for member in relayed]
        for index, (address, target) in enumerate(targets):
            try:
                await request_daemon(
                    self._request(gateway, opcode, parameters, target),
                    socket_path=socket_path_for_address(gateway),
                )
            except Exception as exc:
                unsent = [later for later, _ in targets[index + 1 :]]
                self._stop(address, exc, gateway, description, sent=sent, unsent=unsent)
            sent.append(address)
        self.steps_completed += 1
        if report:
            lights = "light" if len(sent) == 1 else "lights"
            line = f"{description} command sent to {len(sent)} {lights} in group {self.light.name} via {gateway}"
            if relayed:
                line += " (relayed members are not individually acknowledged)"
            print(line)


async def _run_program(source: str, program: Sequence[ScriptStep], dispatch: CommandDispatcher) -> None:
    async def send_command(command: LightCommand) -> None:
        await dispatch(
            command.opcode,
            command.parameters,
            f"{source}:{command.line}: {command.description}",
            command.report,
        )

    async def wait(delay: Delay) -> None:
        if delay.report:
            print(f"{source}:{delay.line}: waiting {delay.seconds:g}s")
        await asyncio.sleep(delay.seconds)

    await execute_script(program, send_command=send_command, wait=wait)


def _one_shot_command(arguments: argparse.Namespace) -> tuple[int, bytes, str]:
    """Return the opcode, parameters, and description for on/off/color/white."""

    if arguments.command == "on":
        return COMMAND_ON_OFF, on_parameters(), "on"
    if arguments.command == "off":
        return COMMAND_ON_OFF, off_parameters(), "off"
    if arguments.command == "white":
        parameters = white_parameters(arguments.temperature, arguments.brightness)
        return COMMAND_WHITE, parameters, f"white {arguments.temperature} K, brightness {arguments.brightness}"
    if arguments.command == "color":
        red, green, blue = parse_rgb(arguments.rgb)
        parameters = rgb_parameters(red, green, blue, arguments.brightness)
        return (
            COMMAND_RGB,
            parameters,
            f"colour rgb #{red:02x}{green:02x}{blue:02x}, brightness {arguments.brightness}",
        )
    raise AssertionError(f"unhandled command {arguments.command!r}")


async def _run_group(
    arguments: argparse.Namespace,
    light: ResolvedLight,
    source: str | None,
    program: Sequence[ScriptStep] | None,
) -> int:
    """Run one command or one program on every member through one connection."""

    command = _one_shot_command(arguments) if program is None else None
    # Unlike a single --address, a group waits only when asked explicitly:
    # one unavailable member would otherwise stall every other member.
    wait = arguments.wait_for_connection if arguments.wait_for_connection_explicit else False
    waiting_reported = False
    while True:
        try:
            plan = await _plan_group(arguments, light)
        except _GroupScanFailed as exc:
            print(f"blemeshctl: error: group {light.name}: Bluetooth scan failed: {exc}", file=sys.stderr)
            return GROUP_FAILURE_EXIT_CODE
        missing = [address for address in plan.unaddressable if address not in plan.permanent]
        if not wait or not missing:
            break
        if not waiting_reported:
            print(f"Waiting for {', '.join(missing)} in group {light.name} to advertise; press Ctrl-C to stop.")
            waiting_reported = True
        await asyncio.sleep(1.0)

    for address, reason in plan.unaddressable.items():
        print(f"blemeshctl: error: {address}: {reason}; no commands will be sent to it", file=sys.stderr)
    relay = _GroupRelay(arguments, light, plan, wait=wait)
    if plan.addressable:
        try:
            if command is not None:
                await relay.send(*command, True)
            else:
                assert source is not None and program is not None
                await _run_program(source, program, relay.send)
        except _GroupStopped:
            pass
    if relay.stopped and program is not None:
        # Later steps were never sent, so no member can be reported as complete.
        line = (
            f"blemeshctl: group {light.name} stopped before the program finished, "
            "so no light in it received every step"
        )
        never = [address for address in light.addresses if address in plan.unaddressable]
        if never:
            line += f"; never addressed: {', '.join(never)}"
        print(line, file=sys.stderr)
        return GROUP_FAILURE_EXIT_CODE
    if relay.failed:
        failed = [address for address in light.addresses if address in relay.failed]
        print(
            f"blemeshctl: {len(failed)} of {len(light.addresses)} lights in group "
            f"{light.name} failed: {', '.join(failed)}",
            file=sys.stderr,
        )
        return GROUP_FAILURE_EXIT_CODE
    return 0


class _TimerBackend:
    """Deliver one timer step through the single-light or group send path.

    A group's relay is rebuilt (rescan, new gateway) after any failure, because
    a failed write loses the gateway connection and its outcome is unknown.
    """

    def __init__(self, record: dict[str, Any], credentials: dict[str, str]) -> None:
        connection = record["connection"]
        addresses = tuple(record["addresses"])
        self.light = ResolvedLight(record["target"], addresses, True) if record["is_group"] else None
        self.arguments = argparse.Namespace(
            address=None if self.light or not addresses else addresses[0],
            mesh_name=credentials["mesh_name"],
            password=credentials["password"],
            scan_timeout=connection["scan_timeout"],
            connect_timeout=connection["connect_timeout"],
            keepalive_seconds=60.0,
            # An unattended step must not stall the steps after it.
            wait_for_connection=False,
            wait_for_connection_explicit=False,
        )
        self.relay: _GroupRelay | None = None

    async def send(self, opcode: int, parameters: bytes, description: str, keepalive: float) -> None:
        self.arguments.keepalive_seconds = keepalive
        if self.light is None:
            try:
                await _send_command(
                    self.arguments,
                    socket_path_for_address(self.arguments.address),
                    opcode,
                    parameters,
                    description,
                    report=False,
                )
            except Exception as exc:
                raise timers.StepError(_describe_error(exc)) from exc
            return
        await self._send_group(opcode, parameters, description)

    async def _send_group(self, opcode: int, parameters: bytes, description: str) -> None:
        assert self.light is not None
        if self.relay is None:
            try:
                plan = await _plan_group(self.arguments, self.light)
            except Exception as exc:
                raise timers.StepError(f"group {self.light.name}: Bluetooth scan failed: {_describe_error(exc)}") from exc
            if not plan.addressable:
                raise timers.StepError(f"group {self.light.name}: no member was seen advertising")
            self.relay = _GroupRelay(self.arguments, self.light, plan, wait=False)
        relay = self.relay
        try:
            await relay.send(opcode, parameters, description, False)
        except Exception as exc:
            self.relay = None
            # _GroupStopped only says the details were printed; the cause has them.
            cause = exc.__cause__ if isinstance(exc, _GroupStopped) else exc
            reason = _describe_error(cause) if cause is not None else "no member accepted a Bluetooth connection"
            raise timers.StepError(f"group {self.light.name}: {reason}") from exc
        if relay.failed:
            missing = [address for address in self.light.addresses if address in relay.failed]
            raise timers.StepError(f"not delivered to {', '.join(missing)}", partial=True)


def timer_backend(record: dict[str, Any], credentials: dict[str, str]) -> timers.StepBackend:
    """Backend factory used by the background timer runner."""

    return _TimerBackend(record, credentials)


def _scheduled_program(arguments: argparse.Namespace) -> tuple[str, str, str]:
    """Return program text, a short description, and an error-message source label."""

    command = arguments.command
    if command in ("on", "off"):
        text, description, source = f"{command}\n", command, command
    elif command == "color":
        text = config.scene_text(color=arguments.rgb, brightness=arguments.brightness)
        description, source = text.strip(), "color"
    elif command == "white":
        text = config.scene_text(white=arguments.temperature, brightness=arguments.brightness)
        description, source = text.strip(), "white"
    elif command == "script":
        source = str(arguments.script)
        try:
            text = arguments.script.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            raise ScriptError(f"{source}: could not read script: {exc}") from exc
        description = f"script {arguments.script.name}"
    elif command == "scene":
        text = config.read_scene(arguments.name)
        source, description = config.scene_source(arguments.name), f"scene {arguments.name}"
    elif command == "fade":
        text, description = _fade_text(arguments)
        source = "fade"
    else:
        raise AssertionError(f"unhandled command {command!r}")
    parse_script(text, source)
    return text, description, source


def _fade_text(arguments: argparse.Namespace) -> tuple[str, str]:
    fields = dict(
        color=arguments.color,
        white=arguments.white,
        start=arguments.fade_from,
        end=arguments.fade_to,
        over_seconds=arguments.fade_over,
    )
    return timers.fade_program_text(**fields), timers.fade_description(**fields)


def _now() -> float:
    return time.time()


def _sunrise_plan(arguments: argparse.Namespace) -> tuple[str, str, int, list[str]]:
    """Return program text, description, start delay, and extra output lines for ``sunrise``."""

    now = _now()
    notes: list[str] = []
    over = arguments.sunrise_over
    if arguments.sunrise_at is not None:
        plan = timers.plan_sunrise_at(*arguments.sunrise_at, over, now)
        over, wake_at = plan.over_seconds, plan.wake_at
        delay = max(0, round(plan.start_at - now))
        if plan.compressed:
            notes.append(
                f"The wake time is only {timers.format_duration(over)} away, so the ramp is shortened "
                f"from {timers.format_duration(arguments.sunrise_over)} to {timers.format_duration(over)} "
                "and starts now."
            )
    else:
        delay = arguments.delay_seconds
        wake_at = now + delay + over
    wake_clock = time.strftime("%H:%M", time.localtime(wake_at))
    description = timers.sunrise_description(over, wake_clock)
    text = timers.sunrise_program_text(over, description=description)
    notes.append(f"Wake time: {timers.format_wake_time(wake_at)}")
    return text, description, delay, notes


def _schedule_command(arguments: argparse.Namespace) -> int:
    """Compile the command, store a timer record, and start its background runner."""

    notes: list[str] = []
    if arguments.command == "sunrise":
        text, description, delay_seconds, notes = _sunrise_plan(arguments)
        parse_script(text, "sunrise")
    else:
        text, description, _ = _scheduled_program(arguments)
        delay_seconds = arguments.delay_seconds
    light_name = getattr(arguments, "light", None)
    if light_name is not None:
        registry = config.LightRegistry() if config.is_address(light_name) else config.load_lights()
        light = config.resolve_light(registry, light_name)
        target, addresses, is_group = light_name, list(light.addresses), light.is_group
    elif arguments.address is not None:
        target, addresses, is_group = arguments.address, [arguments.address], False
    else:
        target, addresses, is_group = "(any light)", [], False
    record = timers.schedule_timer(
        program_text=text,
        description=description,
        target=target,
        addresses=addresses,
        is_group=is_group,
        delay_seconds=delay_seconds,
        connection={"scan_timeout": arguments.scan_timeout, "connect_timeout": arguments.connect_timeout},
        credentials={"mesh_name": arguments.mesh_name, "password": arguments.password},
        clock=_now,
    )
    fires = time.strftime("%H:%M:%S", time.localtime(record["fire_at"]))
    when = f"in {timers.format_duration(delay_seconds)}" if delay_seconds else "now"
    print(f"Scheduled {record['id']}: {description} at {fires} ({when})")
    for note in notes:
        print(note)
    return 0


async def run(arguments: argparse.Namespace) -> int:
    if arguments.command == "scan":
        lights = await discover_lights(arguments.timeout)
        if not lights:
            print("No compatible Telink lights found.")
            return 1
        aliases = _aliases_by_address()
        for light in lights:
            print(_format_light(light, aliases.get(light.info.address.upper(), ())))
        return 0
    if arguments.command == "alias":
        return _run_alias_command(arguments)
    if arguments.command == "group":
        return _run_group_command(arguments)
    if arguments.command == "scene" and arguments.scene_action != "apply":
        return _run_scene_command(arguments)
    if arguments.command == "timers":
        return timers.run_timers_command(arguments)
    if arguments.command == "sunrise" or getattr(arguments, "delay_seconds", None) is not None:
        return _schedule_command(arguments)

    # Compile before locating or starting the daemon, so a bad final line
    # cannot result in earlier valid lines being sent to a light.
    if arguments.command == "script":
        source: str | None = str(arguments.script)
        program = load_script(arguments.script)
    elif arguments.command == "scene":
        source = config.scene_source(arguments.name)
        program = config.load_scene(arguments.name)
    elif arguments.command == "fade":
        source = "fade"
        program = parse_script(_fade_text(arguments)[0], source)
    else:
        source, program = None, None

    # Resolve --light to MAC addresses before any socket path or request
    # address is derived from it. Each MAC has its own daemon socket.
    light_name = getattr(arguments, "light", None)
    if light_name is not None:
        # A raw MAC needs no stored names, so a broken lights.json cannot block it.
        registry = config.LightRegistry() if config.is_address(light_name) else config.load_lights()
        light = config.resolve_light(registry, light_name)
        if light.is_group:
            return await _run_group(arguments, light, source, program)
        arguments.address = light.addresses[0]

    socket_path = socket_path_for_address(arguments.address)
    if arguments.command == "daemon":
        try:
            response = await request_daemon(
                {"type": arguments.action},
                start_if_needed=False,
                socket_path=socket_path,
            )
        except DaemonError:
            if arguments.action == "status":
                print("No keepalive daemon is running.")
                return 1
            print("No keepalive daemon was running.")
            return 0
        if arguments.action == "stop":
            print("Keepalive daemon stopped.")
        elif response["connected"]:
            print(
                f"Keepalive daemon connected to {response['address']} "
                f"(mesh 0x{response['mesh_address']:04x}; "
                f"expires after {response['idle_timeout']:g}s of inactivity)."
            )
        else:
            print("Keepalive daemon is starting a Bluetooth connection.")
        return 0

    if program is not None:
        assert source is not None
        await _run_program(source, program, _single_light_dispatcher(arguments, socket_path))
        return 0

    opcode, parameters, description = _one_shot_command(arguments)
    await _send_command(arguments, socket_path, opcode, parameters, description)
    return 0


def main() -> None:
    parser = build_parser()
    arguments = parser.parse_args()
    try:
        raise SystemExit(asyncio.run(run(arguments)))
    except (BleMeshError, ConfigError, DaemonError, ScriptError, TelinkProtocolError, timers.TimerError) as exc:
        parser.exit(2, f"blemeshctl: error: {exc}\n")
    except KeyboardInterrupt:
        parser.exit(130, "blemeshctl: interrupted\n")


if __name__ == "__main__":
    main()
