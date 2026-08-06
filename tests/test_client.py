from __future__ import annotations

import asyncio
import unittest
from contextlib import contextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from blemeshctl.client import (
    COMMAND_UUID,
    PAIR_UUID,
    BleMeshController,
    BleMeshSession,
    DiscoveredLight,
    select_light,
)
from blemeshctl.protocol import (
    COMMAND_ON_OFF,
    COMMAND_RGB,
    AdvertisementInfo,
    credentials,
    java_aes_encrypt,
    on_parameters,
    rgb_parameters,
    TELINK_COMPANY_ID,
)


class FakeBleakClient:
    """Minimal async client used to verify the GATT transaction choices."""

    instance: "FakeBleakClient | None" = None
    pair_response = b""

    def __init__(self, device: object, timeout: float) -> None:
        self.device = device
        self.timeout = timeout
        self.is_connected = False
        self.connect_count = 0
        self.disconnect_count = 0
        self.writes: list[tuple[str, bytes, bool]] = []
        type(self).instance = self

    async def connect(self) -> bool:
        self.connect_count += 1
        self.is_connected = True
        return True

    async def disconnect(self) -> bool:
        self.disconnect_count += 1
        self.is_connected = False
        return True

    async def write_gatt_char(self, uuid: str, data: bytes, response: bool) -> None:
        self.writes.append((uuid, bytes(data), response))

    async def read_gatt_char(self, uuid: str) -> bytes:
        if uuid != PAIR_UUID:
            raise AssertionError(f"unexpected characteristic read: {uuid}")
        return self.pair_response


class ControllerTests(unittest.TestCase):
    def setUp(self) -> None:
        FakeBleakClient.instance = None
        local_random = bytes.fromhex("0102030405060708")
        remote_random = bytes.fromhex("1020304050607080")
        credential = credentials("BleMesh", "mesh123")
        FakeBleakClient.pair_response = b"\x0d" + remote_random + java_aes_encrypt(
            remote_random.ljust(16, b"\0"), credential
        )[8:][::-1]
        self.local_random = local_random
        self.target = DiscoveredLight(
            device=object(),
            info=AdvertisementInfo(
                address="A4:C1:38:93:1B:72",
                name="BleMesh",
                mesh_uuid=0x0211,
                product_uuid=0x0004,
                status=1,
                mesh_address=0x0067,
                rssi=-50,
            ),
        )

    @contextmanager
    def _patches(self):
        select_light = AsyncMock(return_value=self.target)
        with (
            patch("blemeshctl.client.BleakClient", FakeBleakClient),
            patch("blemeshctl.client.select_light", new=select_light),
            patch(
                "blemeshctl.client.build_pair_request",
                return_value=(b"\x0c" + self.local_random + b"\0" * 8, self.local_random),
            ),
        ):
            yield select_light

    def test_normal_command_uses_acknowledged_writes(self) -> None:
        with self._patches():
            info = asyncio.run(BleMeshController().turn_on())

        self.assertEqual(info, self.target.info)
        self.assertIsNotNone(FakeBleakClient.instance)
        writes = FakeBleakClient.instance.writes
        self.assertEqual([uuid for uuid, _, _ in writes], [PAIR_UUID, COMMAND_UUID])
        self.assertTrue(all(response for _, _, response in writes))
        self.assertEqual(len(writes[1][1]), 20)
        self.assertEqual(FakeBleakClient.instance.connect_count, 1)
        self.assertEqual(FakeBleakClient.instance.disconnect_count, 1)

    def test_explicit_address_stops_scanning_when_target_appears(self) -> None:
        device = SimpleNamespace(address="A4:C1:38:93:1B:72", name="BleMesh")
        advertisement = SimpleNamespace(
            manufacturer_data={
                TELINK_COMPANY_ID: bytes.fromhex(
                    "1102721b93380400016700000102030405060708090a0b0c0d0e0f"
                )
            },
            local_name="BleMesh",
            rssi=-50,
        )

        async def find_target(matches_target, *, timeout: float):
            self.assertEqual(timeout, 20)
            self.assertTrue(matches_target(device, advertisement))
            return device

        with patch("blemeshctl.client.BleakScanner.find_device_by_filter", new=find_target):
            selected = asyncio.run(select_light("a4:c1:38:93:1b:72", 20))

        self.assertEqual(selected.device, device)
        self.assertEqual(selected.info.mesh_address, 0x0067)

    def test_session_reuses_one_authenticated_connection(self) -> None:
        async def send_twice() -> None:
            async with BleMeshSession() as session:
                first = await session.send(COMMAND_ON_OFF, on_parameters())
                second = await session.send(COMMAND_RGB, rgb_parameters(0, 255, 0, 100))
                self.assertEqual(first, self.target.info)
                self.assertEqual(second, self.target.info)
                self.assertTrue(session.is_connected)

        with self._patches() as select_light:
            asyncio.run(send_twice())

        self.assertEqual(select_light.await_count, 1)
        self.assertIsNotNone(FakeBleakClient.instance)
        writes = FakeBleakClient.instance.writes
        self.assertEqual([uuid for uuid, _, _ in writes], [PAIR_UUID, COMMAND_UUID, COMMAND_UUID])
        self.assertEqual(FakeBleakClient.instance.connect_count, 1)
        self.assertEqual(FakeBleakClient.instance.disconnect_count, 1)


if __name__ == "__main__":
    unittest.main()
