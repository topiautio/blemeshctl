"""Command-line interface for ``blemeshctl``."""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

from .client import DEFAULT_CONNECTION_SCAN_TIMEOUT, BleMeshError, discover_lights
from .daemon import DaemonError, request_daemon, socket_path_for_address
from .protocol import (
    COMMAND_ON_OFF,
    COMMAND_RGB,
    DEFAULT_MESH_NAME,
    DEFAULT_PASSWORD,
    TelinkProtocolError,
    off_parameters,
    on_parameters,
    parse_rgb,
    rgb_parameters,
)
from .script import Delay, LightCommand, ScriptError, ScriptStep, execute_script, load_script


def _add_connection_options(
    parser: argparse.ArgumentParser, *, wait_for_connection_default: bool = False
) -> None:
    parser.add_argument("--address", help="Bluetooth MAC address; required when multiple lights are visible")
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
        action=argparse.BooleanOptionalAction,
        default=wait_for_connection_default,
        help="retry a known --address's discovery and initial connection (Ctrl-C stops waiting)",
    )
    parser.add_argument(
        "--keepalive-seconds",
        type=float,
        default=60.0,
        help="keep the authenticated Bluetooth connection open after the command (default: 60)",
    )


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

    script = subcommands.add_parser("script", help="run a light-control script file")
    script.add_argument("script", type=Path, help="path to a script file")
    _add_connection_options(script, wait_for_connection_default=True)

    daemon = subcommands.add_parser("daemon", help="inspect or stop a local keepalive connection")
    daemon.add_argument("action", choices=("status", "stop"), help="daemon action")
    daemon.add_argument("--address", help="Bluetooth MAC address for the retained connection")
    return parser


def _format_light(light: object) -> str:
    info = light.info
    rssi = "?" if info.rssi is None else str(info.rssi)
    return (
        f"{info.address}  name={info.name or '-'}  mesh=0x{info.mesh_address:04x}  "
        f"product=0x{info.product_uuid:04x}  status=0x{info.status:02x}  rssi={rssi}"
    )


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


async def _run_script(
    arguments: argparse.Namespace, socket_path: Path, program: tuple[ScriptStep, ...]
) -> None:
    source = str(arguments.script)

    async def send_command(command: LightCommand) -> None:
        await _send_command(
            arguments,
            socket_path,
            command.opcode,
            command.parameters,
            f"{source}:{command.line}: {command.description}",
            report=command.report,
        )

    async def wait(delay: Delay) -> None:
        if delay.report:
            print(f"{source}:{delay.line}: waiting {delay.seconds:g}s")
        await asyncio.sleep(delay.seconds)

    await execute_script(program, send_command=send_command, wait=wait)


async def run(arguments: argparse.Namespace) -> int:
    if arguments.command == "scan":
        lights = await discover_lights(arguments.timeout)
        if not lights:
            print("No compatible Telink lights found.")
            return 1
        for light in lights:
            print(_format_light(light))
        return 0

    if arguments.command == "script":
        # Compile before locating or starting the daemon, so a bad final line
        # cannot result in earlier valid lines being sent to a light.
        program = load_script(arguments.script)
    else:
        program = None

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

    if arguments.command == "script":
        assert program is not None
        await _run_script(arguments, socket_path, program)
        return 0

    if arguments.command == "on":
        opcode, parameters, description = COMMAND_ON_OFF, on_parameters(), "on"
    elif arguments.command == "off":
        opcode, parameters, description = COMMAND_ON_OFF, off_parameters(), "off"
    else:
        red, green, blue = parse_rgb(arguments.rgb)
        opcode = COMMAND_RGB
        parameters = rgb_parameters(red, green, blue, arguments.brightness)
        description = f"colour rgb #{red:02x}{green:02x}{blue:02x}, brightness {arguments.brightness}"

    await _send_command(arguments, socket_path, opcode, parameters, description)
    return 0


def main() -> None:
    parser = build_parser()
    arguments = parser.parse_args()
    try:
        raise SystemExit(asyncio.run(run(arguments)))
    except (BleMeshError, DaemonError, ScriptError, TelinkProtocolError) as exc:
        parser.exit(2, f"blemeshctl: error: {exc}\n")
    except KeyboardInterrupt:
        parser.exit(130, "blemeshctl: interrupted\n")


if __name__ == "__main__":
    main()
