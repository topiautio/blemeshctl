"""Async Bluetooth transport for the legacy Telink light protocol."""

from __future__ import annotations

import asyncio
import secrets
from dataclasses import dataclass

from bleak import BleakClient, BleakScanner

from .protocol import (
    COMMAND_ON_OFF,
    COMMAND_RGB,
    DEFAULT_MESH_NAME,
    DEFAULT_PASSWORD,
    TELINK_COMPANY_ID,
    AdvertisementInfo,
    TelinkProtocolError,
    build_command_frame,
    build_pair_request,
    derive_session_key,
    encrypt_command_frame,
    off_parameters,
    on_parameters,
    parse_advertisement,
    rgb_parameters,
)

SERVICE_UUID = "00010203-0405-0607-0809-0a0b0c0d1910"
PAIR_UUID = "00010203-0405-0607-0809-0a0b0c0d1914"
COMMAND_UUID = "00010203-0405-0607-0809-0a0b0c0d1912"


class BleMeshError(RuntimeError):
    """Raised when discovery or a GATT command cannot be completed."""


@dataclass(frozen=True)
class DiscoveredLight:
    """A compatible BLE device and its Telink advertisement information."""

    device: object
    info: AdvertisementInfo


async def discover_lights(timeout: float = 8.0) -> list[DiscoveredLight]:
    """Discover advertising Telink lights without connecting to them."""

    results = await BleakScanner.discover(timeout=timeout, return_adv=True)
    if not isinstance(results, dict):
        raise BleMeshError("this Bleak version did not return advertisement data")

    lights: list[DiscoveredLight] = []
    for device, advertisement in results.values():
        payload = advertisement.manufacturer_data.get(TELINK_COMPANY_ID)
        if payload is None:
            continue
        try:
            info = parse_advertisement(
                device.address,
                advertisement.local_name or device.name,
                bytes(payload),
                advertisement.rssi,
            )
        except TelinkProtocolError:
            continue
        lights.append(DiscoveredLight(device=device, info=info))
    return sorted(lights, key=lambda light: light.info.address)


async def select_light(address: str | None, timeout: float) -> DiscoveredLight:
    """Find an explicit address, or one unambiguous compatible light."""

    lights = await discover_lights(timeout)
    if address:
        normalized = address.upper()
        for light in lights:
            if light.info.address == normalized:
                return light
        raise BleMeshError(f"{normalized} was not advertising as a compatible Telink light")
    if not lights:
        raise BleMeshError("no compatible Telink lights were advertising")
    if len(lights) > 1:
        choices = ", ".join(light.info.address for light in lights)
        raise BleMeshError(f"more than one compatible light is visible ({choices}); use --address")
    return lights[0]


class BleMeshController:
    """Connect, authenticate, and send normal light-control commands."""

    def __init__(
        self,
        address: str | None = None,
        mesh_name: str = DEFAULT_MESH_NAME,
        password: str = DEFAULT_PASSWORD,
        scan_timeout: float = 8.0,
        connect_timeout: float = 20.0,
    ) -> None:
        self.address = address
        self.mesh_name = mesh_name
        self.password = password
        self.scan_timeout = scan_timeout
        self.connect_timeout = connect_timeout

    async def send(self, opcode: int, parameters: bytes) -> AdvertisementInfo:
        """Authenticate to the selected light and issue a normal vendor command."""

        target = await select_light(self.address, self.scan_timeout)
        pair_request, local_random = build_pair_request(self.mesh_name, self.password)
        try:
            async with BleakClient(target.device, timeout=self.connect_timeout) as client:
                await client.write_gatt_char(PAIR_UUID, pair_request, response=True)
                await asyncio.sleep(0.2)
                pair_response = bytes(await client.read_gatt_char(PAIR_UUID))
                session_key = derive_session_key(
                    self.mesh_name, self.password, local_random, pair_response
                )
                sequence = secrets.randbelow(0xFFFFFE) + 1
                frame = build_command_frame(
                    sequence, target.info.mesh_address, opcode, parameters
                )
                command = encrypt_command_frame(session_key, target.info.address, frame)
                await client.write_gatt_char(COMMAND_UUID, command, response=True)
        except TelinkProtocolError:
            raise
        except Exception as exc:
            raise BleMeshError(f"Bluetooth command failed: {exc}") from exc
        return target.info

    async def turn_on(self) -> AdvertisementInfo:
        return await self.send(COMMAND_ON_OFF, on_parameters())

    async def turn_off(self) -> AdvertisementInfo:
        return await self.send(COMMAND_ON_OFF, off_parameters())

    async def set_rgb(self, red: int, green: int, blue: int, brightness: int) -> AdvertisementInfo:
        return await self.send(COMMAND_RGB, rgb_parameters(red, green, blue, brightness))
