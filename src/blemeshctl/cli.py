"""Command-line interface for ``blemeshctl``."""

from __future__ import annotations

import argparse
import asyncio
import sys

from .client import BleMeshController, BleMeshError, discover_lights
from .protocol import DEFAULT_MESH_NAME, DEFAULT_PASSWORD, TelinkProtocolError


def _add_connection_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--address", help="Bluetooth MAC address; required when multiple lights are visible")
    parser.add_argument("--mesh-name", default=DEFAULT_MESH_NAME, help=f"mesh name (default: {DEFAULT_MESH_NAME})")
    parser.add_argument("--password", default=DEFAULT_PASSWORD, help="mesh password")
    parser.add_argument("--scan-timeout", type=float, default=8.0, help="advertising scan time in seconds")
    parser.add_argument("--connect-timeout", type=float, default=20.0, help="GATT connection timeout in seconds")


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
    return parser


def _parse_rgb(value: str) -> tuple[int, int, int]:
    value = value.removeprefix("#")
    if len(value) != 6:
        raise TelinkProtocolError("RGB colour must contain exactly six hexadecimal digits")
    try:
        return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)
    except ValueError as exc:
        raise TelinkProtocolError("RGB colour must contain only hexadecimal digits") from exc


def _format_light(light: object) -> str:
    info = light.info
    rssi = "?" if info.rssi is None else str(info.rssi)
    return (
        f"{info.address}  name={info.name or '-'}  mesh=0x{info.mesh_address:04x}  "
        f"product=0x{info.product_uuid:04x}  status=0x{info.status:02x}  rssi={rssi}"
    )


async def run(arguments: argparse.Namespace) -> int:
    if arguments.command == "scan":
        lights = await discover_lights(arguments.timeout)
        if not lights:
            print("No compatible Telink lights found.")
            return 1
        for light in lights:
            print(_format_light(light))
        return 0

    controller = BleMeshController(
        address=arguments.address,
        mesh_name=arguments.mesh_name,
        password=arguments.password,
        scan_timeout=arguments.scan_timeout,
        connect_timeout=arguments.connect_timeout,
    )
    if arguments.command == "on":
        info = await controller.turn_on()
        print(f"on command sent to {info.address} (mesh 0x{info.mesh_address:04x})")
    elif arguments.command == "off":
        info = await controller.turn_off()
        print(f"off command sent to {info.address} (mesh 0x{info.mesh_address:04x})")
    else:
        red, green, blue = _parse_rgb(arguments.rgb)
        info = await controller.set_rgb(red, green, blue, arguments.brightness)
        print(
            f"colour command sent to {info.address} (mesh 0x{info.mesh_address:04x}, "
            f"rgb #{red:02x}{green:02x}{blue:02x}, brightness {arguments.brightness})"
        )
    return 0


def main() -> None:
    parser = build_parser()
    arguments = parser.parse_args()
    try:
        raise SystemExit(asyncio.run(run(arguments)))
    except (BleMeshError, TelinkProtocolError) as exc:
        parser.exit(2, f"blemeshctl: error: {exc}\n")
    except KeyboardInterrupt:
        parser.exit(130, "blemeshctl: interrupted\n")


if __name__ == "__main__":
    main()
