"""Command-line interface for ``blemeshctl``."""

from __future__ import annotations

import argparse
import asyncio
import sys
from collections.abc import Awaitable, Callable, Sequence
from pathlib import Path
from typing import Any

from . import config
from .client import DEFAULT_CONNECTION_SCAN_TIMEOUT, BleMeshError, discover_lights
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


class _NoReachableMembers(Exception):
    """Every member of a group has failed, so the program cannot continue."""


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
        help="maximum advertising scan time in seconds",
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
            "Name a set of lights so --light NAME sends every command to each member. "
            f"Stored in lights.json under {_CONFIG_LOCATION}."
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


class _GroupSender:
    """Send each command to every group member that has not failed yet.

    A failed write has an unknown outcome, so a member that fails once is never
    retried or sent later steps; the remaining members carry on in step.
    """

    def __init__(self, arguments: argparse.Namespace, light: ResolvedLight) -> None:
        # Unlike a single --address, a group waits only when asked explicitly:
        # one unavailable member would otherwise stall every other member.
        wait = arguments.wait_for_connection if arguments.wait_for_connection_explicit else False
        self.light = light
        self.failed: dict[str, Exception] = {}
        self._members = [
            (
                address,
                argparse.Namespace(**{**vars(arguments), "address": address, "wait_for_connection": wait}),
                socket_path_for_address(address),
            )
            for address in light.addresses
        ]

    async def _send_one(
        self,
        address: str,
        arguments: argparse.Namespace,
        socket_path: Path,
        opcode: int,
        parameters: bytes,
        description: str,
        report: bool,
    ) -> None:
        try:
            await _send_command(arguments, socket_path, opcode, parameters, description, report=report)
        except Exception as exc:
            self.failed[address] = exc
            print(
                f"blemeshctl: error: {address}: {_describe_error(exc)}; "
                "no further commands will be sent to it",
                file=sys.stderr,
            )

    async def send(self, opcode: int, parameters: bytes, description: str, report: bool) -> None:
        """Send one step to all reachable members before the program continues."""

        await asyncio.gather(
            *(
                self._send_one(address, member_arguments, socket_path, opcode, parameters, description, report)
                for address, member_arguments, socket_path in self._members
                if address not in self.failed
            )
        )
        if len(self.failed) == len(self._members):
            raise _NoReachableMembers


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
    """Fan one command or one program run out to every member of a group."""

    command = _one_shot_command(arguments) if program is None else None
    sender = _GroupSender(arguments, light)
    try:
        if command is not None:
            await sender.send(*command, True)
        else:
            assert source is not None and program is not None
            await _run_program(source, program, sender.send)
    except _NoReachableMembers:
        pass
    if sender.failed:
        print(
            f"blemeshctl: {len(sender.failed)} of {len(light.addresses)} lights in group "
            f"{light.name} failed: {', '.join(sender.failed)}",
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
