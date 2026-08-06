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
DEFAULT_CONNECTION_SCAN_TIMEOUT = 20.0


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

    if address:
        normalized = address.upper()
        selected: DiscoveredLight | None = None

        def matches_target(device: object, advertisement: object) -> bool:
            nonlocal selected
            if device.address.upper() != normalized:
                return False
            payload = advertisement.manufacturer_data.get(TELINK_COMPANY_ID)
            if payload is None:
                return False
            try:
                info = parse_advertisement(
                    device.address,
                    advertisement.local_name or device.name,
                    bytes(payload),
                    advertisement.rssi,
                )
            except TelinkProtocolError:
                return False
            selected = DiscoveredLight(device=device, info=info)
            return True

        await BleakScanner.find_device_by_filter(matches_target, timeout=timeout)
        if selected is not None:
            return selected
        raise BleMeshError(f"{normalized} was not advertising as a compatible Telink light")

    lights = await discover_lights(timeout)
    if not lights:
        raise BleMeshError("no compatible Telink lights were advertising")
    if len(lights) > 1:
        choices = ", ".join(light.info.address for light in lights)
        raise BleMeshError(f"more than one compatible light is visible ({choices}); use --address")
    return lights[0]


class BleMeshController:
    """Send a normal light-control command with a short-lived session."""

    def __init__(
        self,
        address: str | None = None,
        mesh_name: str = DEFAULT_MESH_NAME,
        password: str = DEFAULT_PASSWORD,
        scan_timeout: float = DEFAULT_CONNECTION_SCAN_TIMEOUT,
        connect_timeout: float = 20.0,
    ) -> None:
        self.address = address
        self.mesh_name = mesh_name
        self.password = password
        self.scan_timeout = scan_timeout
        self.connect_timeout = connect_timeout

    async def send(self, opcode: int, parameters: bytes) -> AdvertisementInfo:
        """Connect, authenticate, send a command, and disconnect."""

        async with BleMeshSession(
            address=self.address,
            mesh_name=self.mesh_name,
            password=self.password,
            scan_timeout=self.scan_timeout,
            connect_timeout=self.connect_timeout,
        ) as session:
            return await session.send(opcode, parameters)

    async def turn_on(self) -> AdvertisementInfo:
        return await self.send(COMMAND_ON_OFF, on_parameters())

    async def turn_off(self) -> AdvertisementInfo:
        return await self.send(COMMAND_ON_OFF, off_parameters())

    async def set_rgb(self, red: int, green: int, blue: int, brightness: int) -> AdvertisementInfo:
        return await self.send(COMMAND_RGB, rgb_parameters(red, green, blue, brightness))


class BleMeshSession:
    """An authenticated Telink connection which can send multiple commands."""

    def __init__(
        self,
        address: str | None = None,
        mesh_name: str = DEFAULT_MESH_NAME,
        password: str = DEFAULT_PASSWORD,
        scan_timeout: float = DEFAULT_CONNECTION_SCAN_TIMEOUT,
        connect_timeout: float = 20.0,
    ) -> None:
        self.address = address
        self.mesh_name = mesh_name
        self.password = password
        self.scan_timeout = scan_timeout
        self.connect_timeout = connect_timeout
        self._client: BleakClient | None = None
        self._info: AdvertisementInfo | None = None
        self._session_key: bytes | None = None
        self._lock = asyncio.Lock()

    @property
    def info(self) -> AdvertisementInfo | None:
        """The target discovered during the active connection, if any."""

        return self._info

    @property
    def is_connected(self) -> bool:
        """Whether the authenticated GATT connection is currently usable."""

        return (
            self._client is not None
            and self._client.is_connected
            and self._info is not None
            and self._session_key is not None
        )

    async def __aenter__(self) -> "BleMeshSession":
        return self

    async def __aexit__(self, exc_type: object, exc: object, traceback: object) -> bool:
        await self.close()
        return False

    async def close(self) -> None:
        """Disconnect and discard the derived session key."""

        client = self._client
        self._client = None
        self._info = None
        self._session_key = None
        if client is None:
            return
        try:
            await client.disconnect()
        except Exception:
            # A remote disconnect is already sufficient for cleanup.
            pass

    async def _connect(self) -> None:
        target = await select_light(self.address, self.scan_timeout)
        pair_request, local_random = build_pair_request(self.mesh_name, self.password)
        client = BleakClient(target.device, timeout=self.connect_timeout)
        try:
            await client.connect()
            await client.write_gatt_char(PAIR_UUID, pair_request, response=True)
            await asyncio.sleep(0.2)
            pair_response = bytes(await client.read_gatt_char(PAIR_UUID))
            session_key = derive_session_key(
                self.mesh_name, self.password, local_random, pair_response
            )
        except Exception:
            try:
                await client.disconnect()
            except Exception:
                pass
            raise
        self._client = client
        self._info = target.info
        self._session_key = session_key

    async def _send_once(self, opcode: int, parameters: bytes) -> AdvertisementInfo:
        if not self.is_connected:
            await self._connect()
        assert self._client is not None
        assert self._info is not None
        assert self._session_key is not None
        sequence = secrets.randbelow(0xFFFFFE) + 1
        frame = build_command_frame(sequence, self._info.mesh_address, opcode, parameters)
        command = encrypt_command_frame(self._session_key, self._info.address, frame)
        await self._client.write_gatt_char(COMMAND_UUID, command, response=True)
        return self._info

    async def send(self, opcode: int, parameters: bytes) -> AdvertisementInfo:
        """Send one command and invalidate a session if its link is dropped."""

        async with self._lock:
            was_connected = self.is_connected
            try:
                return await self._send_once(opcode, parameters)
            except TelinkProtocolError:
                raise
            except BleMeshError:
                await self.close()
                raise
            except Exception as error:
                await self.close()
                if was_connected:
                    raise BleMeshError(
                        "Bluetooth connection dropped; command outcome is unknown, retry manually"
                    ) from error
                raise BleMeshError(f"Bluetooth command failed: {error}") from error
