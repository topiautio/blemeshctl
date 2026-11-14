"""Command-line interface for ``blemeshctl``."""

from __future__ import annotations

import argparse
import asyncio
import sys
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, NoReturn

from . import config
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

    color = subcommands.add_parser("color", help="set a solid RGB colour")
    color.add_argument("rgb", help="six hexadecimal digits, for example 00ff00")
    color.add_argument("--brightness", type=int, default=100, help="brightness from 1 through 100")
    _add_connection_options(color)

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

    script = subcommands.add_parser("script", help="run a light-control script file")
    script.add_argument("script", type=Path, help="path to a script file")
    _add_connection_options(script, wait_for_connection_default=True)

    _add_alias_parser(subcommands)
    _add_group_parser(subcommands)
    _add_scene_parser(subcommands)

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

    # Compile before locating or starting the daemon, so a bad final line
    # cannot result in earlier valid lines being sent to a light.
    if arguments.command == "script":
        source: str | None = str(arguments.script)
        program = load_script(arguments.script)
    elif arguments.command == "scene":
        source = config.scene_source(arguments.name)
        program = config.load_scene(arguments.name)
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
    except (BleMeshError, ConfigError, DaemonError, ScriptError, TelinkProtocolError) as exc:
        parser.exit(2, f"blemeshctl: error: {exc}\n")
    except KeyboardInterrupt:
        parser.exit(130, "blemeshctl: interrupted\n")


if __name__ == "__main__":
    main()
