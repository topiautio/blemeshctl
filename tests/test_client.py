from __future__ import annotations

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from blemeshctl.client import (
    COMMAND_UUID,
    PAIR_UUID,
    BleMeshController,
    DiscoveredLight,
)
from blemeshctl.protocol import AdvertisementInfo, credentials, java_aes_encrypt


class FakeBleakClient:
    """Minimal async client used to verify the GATT transaction choices."""

    instance: "FakeBleakClient | None" = None
    pair_response = b""

    def __init__(self, device: object, timeout: float) -> None:
        self.device = device
        self.timeout = timeout
        self.writes: list[tuple[str, bytes, bool]] = []
        type(self).instance = self

    async def __aenter__(self) -> "FakeBleakClient":
        return self

    async def __aexit__(self, exc_type: object, exc: object, traceback: object) -> bool:
        return False

    async def write_gatt_char(self, uuid: str, data: bytes, response: bool) -> None:
        self.writes.append((uuid, bytes(data), response))

    async def read_gatt_char(self, uuid: str) -> bytes:
        if uuid != PAIR_UUID:
            raise AssertionError(f"unexpected characteristic read: {uuid}")
        return self.pair_response


class ControllerTests(unittest.TestCase):
    def test_normal_command_uses_acknowledged_writes(self) -> None:
        local_random = bytes.fromhex("0102030405060708")
        remote_random = bytes.fromhex("1020304050607080")
        credential = credentials("BleMesh", "mesh123")
        FakeBleakClient.pair_response = b"\x0d" + remote_random + java_aes_encrypt(
            remote_random.ljust(16, b"\0"), credential
        )[8:][::-1]
        target = DiscoveredLight(
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

        with (
            patch("blemeshctl.client.BleakClient", FakeBleakClient),
            patch("blemeshctl.client.select_light", new=AsyncMock(return_value=target)),
            patch(
                "blemeshctl.client.build_pair_request",
                return_value=(b"\x0c" + local_random + b"\0" * 8, local_random),
            ),
        ):
            info = asyncio.run(BleMeshController().turn_on())

        self.assertEqual(info, target.info)
        self.assertIsNotNone(FakeBleakClient.instance)
        writes = FakeBleakClient.instance.writes
        self.assertEqual([uuid for uuid, _, _ in writes], [PAIR_UUID, COMMAND_UUID])
        self.assertTrue(all(response for _, _, response in writes))
        self.assertEqual(len(writes[1][1]), 20)


if __name__ == "__main__":
    unittest.main()
