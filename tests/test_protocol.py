from __future__ import annotations

import unittest

from blemeshctl.protocol import (
    COMMAND_ON_OFF,
    build_command_frame,
    build_pair_request,
    credentials,
    derive_session_key,
    encrypt_command_frame,
    java_aes_encrypt,
    parse_advertisement,
    rgb_parameters,
)


class AdvertisementTests(unittest.TestCase):
    def test_parse_real_telink_layout(self) -> None:
        info = parse_advertisement(
            "A4:C1:38:93:1B:72",
            "BleMesh",
            bytes.fromhex("1102721b93380400016700000102030405060708090a0b0c0d0e0f"),
            -46,
        )
        self.assertEqual(info.mesh_uuid, 0x0211)
        self.assertEqual(info.product_uuid, 0x0004)
        self.assertEqual(info.status, 0x01)
        self.assertEqual(info.mesh_address, 0x0067)
        self.assertEqual(info.address, "A4:C1:38:93:1B:72")


class LoginTests(unittest.TestCase):
    def test_pair_request_and_session_key(self) -> None:
        local_random = bytes.fromhex("0102030405060708")
        remote_random = bytes.fromhex("1020304050607080")
        credential = credentials("BleMesh", "mesh123")
        request, actual_random = build_pair_request("BleMesh", "mesh123", local_random)
        self.assertEqual(actual_random, local_random)
        self.assertEqual(request[0], 0x0C)
        self.assertEqual(request[1:9], local_random)
        self.assertEqual(
            request[9:],
            java_aes_encrypt(local_random.ljust(16, b"\0"), credential)[8:][::-1],
        )
        response = b"\x0d" + remote_random + java_aes_encrypt(
            remote_random.ljust(16, b"\0"), credential
        )[8:][::-1]
        self.assertEqual(
            derive_session_key("BleMesh", "mesh123", local_random, response).hex(),
            "749c11ec6ea3d3a0cd7824290eb3bff0",
        )


class CommandTests(unittest.TestCase):
    def test_frame_uses_address_then_opcode_layout(self) -> None:
        frame = build_command_frame(0x010203, 0x0067, COMMAND_ON_OFF, b"\x01\x00\x00")
        self.assertEqual(frame[0:3], bytes.fromhex("030201"))
        self.assertEqual(frame[3:5], b"\0\0")
        self.assertEqual(frame[5:10], bytes.fromhex("6700f01102"))
        self.assertEqual(frame[10:13], b"\x01\0\0")

    def test_telink_encryption_matches_native_reference_vector(self) -> None:
        frame = bytearray(bytes.fromhex("03020100006700f0110201000000000000000000"))
        encrypted = encrypt_command_frame(
            bytes.fromhex("749c11ec6ea3d3a0cd7824290eb3bff0"),
            "A4:C1:38:93:1B:72",
            frame,
        )
        self.assertEqual(encrypted.hex(), "030201f064d3c71b2c56b5591742f6f3fe30dc57")

    def test_rgb_parameters(self) -> None:
        self.assertEqual(rgb_parameters(0, 255, 0, 100), bytes((100, 0, 255, 0, 0, 0, 0, 0)))


if __name__ == "__main__":
    unittest.main()
