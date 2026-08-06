"""Pure-Python implementation of the legacy Telink light wire protocol."""

from __future__ import annotations

from dataclasses import dataclass
from secrets import token_bytes

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

TELINK_COMPANY_ID = 0x0211
DEFAULT_MESH_NAME = "BleMesh"
DEFAULT_PASSWORD = "mesh123"

PAIR_REQUEST_OPCODE = 0x0C
PAIR_RESPONSE_OPCODE = 0x0D
COMMAND_ON_OFF = 0xF0
COMMAND_RGB = 0xF1


class TelinkProtocolError(ValueError):
    """Raised when an advertisement, login response, or command is invalid."""


@dataclass(frozen=True)
class AdvertisementInfo:
    """Fields exposed by a Telink manufacturer advertisement."""

    address: str
    name: str | None
    mesh_uuid: int
    product_uuid: int
    status: int
    mesh_address: int
    rssi: int | None


def _padded_16(value: str) -> bytes:
    encoded = value.encode("utf-8")
    if len(encoded) > 16:
        raise TelinkProtocolError("mesh name and password must be at most 16 UTF-8 bytes")
    return encoded.ljust(16, b"\0")


def _aes_ecb_encrypt(key: bytes, data: bytes) -> bytes:
    if len(key) != 16 or len(data) != 16:
        raise TelinkProtocolError("Telink AES inputs must be exactly 16 bytes")
    return Cipher(algorithms.AES(key), modes.ECB()).encryptor().update(data)


def java_aes_encrypt(key: bytes, data: bytes) -> bytes:
    """Match the two-argument AES helper used by the Android app."""

    return _aes_ecb_encrypt(key[::-1], data[::-1])


def telink_aes_block(key: bytes, data: bytes) -> bytes:
    """Match Telink's little-endian native AES block helper."""

    return java_aes_encrypt(key, data)[::-1]


def credentials(mesh_name: str, password: str) -> bytes:
    """Return the 16-byte XOR credential used by Telink's login handshake."""

    return bytes(left ^ right for left, right in zip(_padded_16(mesh_name), _padded_16(password)))


def build_pair_request(mesh_name: str, password: str, local_random: bytes | None = None) -> tuple[bytes, bytes]:
    """Build a pairing request and return ``(request, local_random)``."""

    local_random = token_bytes(8) if local_random is None else local_random
    if len(local_random) != 8:
        raise TelinkProtocolError("local random value must be 8 bytes")
    credential = credentials(mesh_name, password)
    proof = java_aes_encrypt(local_random.ljust(16, b"\0"), credential)[8:][::-1]
    return bytes((PAIR_REQUEST_OPCODE,)) + local_random + proof, local_random


def derive_session_key(
    mesh_name: str,
    password: str,
    local_random: bytes,
    response: bytes,
) -> bytes:
    """Verify a pairing response and derive the 16-byte command session key."""

    if len(local_random) != 8:
        raise TelinkProtocolError("local random value must be 8 bytes")
    if len(response) != 17 or response[0] != PAIR_RESPONSE_OPCODE:
        raise TelinkProtocolError("unexpected Telink pairing response")

    credential = credentials(mesh_name, password)
    remote_random = response[1:9]
    expected_proof = remote_random + java_aes_encrypt(
        remote_random.ljust(16, b"\0"), credential
    )[8:][::-1]
    if response[1:] != expected_proof:
        raise TelinkProtocolError("Telink pairing proof did not match the supplied credentials")
    return java_aes_encrypt(credential, local_random + remote_random)[::-1]


def mac_to_telink_bytes(address: str) -> bytes:
    """Convert a canonical Bluetooth address to Telink's little-endian bytes."""

    parts = address.split(":")
    if len(parts) != 6 or any(len(part) != 2 for part in parts):
        raise TelinkProtocolError("Bluetooth address must use AA:BB:CC:DD:EE:FF form")
    try:
        return bytes(int(part, 16) for part in parts)[::-1]
    except ValueError as exc:
        raise TelinkProtocolError("Bluetooth address contains non-hexadecimal characters") from exc


def parse_advertisement(
    address: str,
    name: str | None,
    manufacturer_data: bytes,
    rssi: int | None = None,
) -> AdvertisementInfo:
    """Parse BlueZ/Bleak manufacturer data for company ID ``0x0211``.

    Bleak supplies the payload after the two-byte Bluetooth company identifier.
    The Telink payload layout is mesh UUID, four MAC bytes, product UUID,
    status, mesh address, then 16 vendor-defined bytes.
    """

    if len(manufacturer_data) < 11:
        raise TelinkProtocolError("Telink advertisement is shorter than 11 bytes")
    return AdvertisementInfo(
        address=address.upper(),
        name=name,
        mesh_uuid=int.from_bytes(manufacturer_data[0:2], "little"),
        product_uuid=int.from_bytes(manufacturer_data[6:8], "little"),
        status=manufacturer_data[8],
        mesh_address=int.from_bytes(manufacturer_data[9:11], "little"),
        rssi=rssi,
    )


def build_command_frame(
    sequence: int,
    mesh_address: int,
    opcode: int,
    parameters: bytes = b"",
    vendor_id: int = TELINK_COMPANY_ID,
) -> bytearray:
    """Build the unencrypted 20-byte command frame used by the Android app."""

    if not 0 <= sequence <= 0xFFFFFF:
        raise TelinkProtocolError("sequence must fit in 24 bits")
    if not 0 <= mesh_address <= 0xFFFF:
        raise TelinkProtocolError("mesh address must fit in 16 bits")
    if not 0 <= opcode <= 0xFF:
        raise TelinkProtocolError("opcode must fit in one byte")
    if len(parameters) > 10:
        raise TelinkProtocolError("Telink command parameters may be at most 10 bytes")

    frame = bytearray(20)
    frame[0:3] = sequence.to_bytes(3, "little")
    # Telink's packet header stores the destination first, followed by an
    # opcode whose high command bits are set.
    frame[5:7] = mesh_address.to_bytes(2, "little")
    frame[7] = opcode | 0xC0
    frame[8:10] = vendor_id.to_bytes(2, "little")
    frame[10 : 10 + len(parameters)] = parameters
    return frame


def encrypt_command_frame(session_key: bytes, address: str, frame: bytearray) -> bytes:
    """Encrypt a command frame exactly as Telink's native Android routine does."""

    if len(session_key) != 16 or len(frame) != 20:
        raise TelinkProtocolError("session key must be 16 bytes and frame must be 20 bytes")

    sequence = int.from_bytes(frame[0:3], "little")
    mac = mac_to_telink_bytes(address)
    iv = mac[:4] + bytes((1,)) + sequence.to_bytes(3, "little")
    encrypted = bytearray(frame)

    # Calculate the two-byte message integrity code over bytes 5 through 19.
    integrity = bytearray(16)
    integrity[:8] = iv
    integrity[8] = 15
    integrity = bytearray(telink_aes_block(session_key, bytes(integrity)))
    for index in range(15):
        integrity[index % 16] ^= encrypted[5 + index]
        if index % 16 == 15 or index == 14:
            integrity = bytearray(telink_aes_block(session_key, bytes(integrity)))
    encrypted[3:5] = integrity[:2]

    # Encrypt bytes 5 through 19 with the same one-byte-counter stream mode.
    counter = bytearray(16)
    counter[1:9] = iv
    stream = b""
    for index in range(15):
        if index % 16 == 0:
            stream = telink_aes_block(session_key, bytes(counter))
            counter[0] = (counter[0] + 1) & 0xFF
        encrypted[5 + index] ^= stream[index % 16]
    return bytes(encrypted)


def on_parameters() -> bytes:
    return b"\x01\x00\x00"


def off_parameters() -> bytes:
    return b"\x00\x00\x00"


def rgb_parameters(red: int, green: int, blue: int, brightness: int) -> bytes:
    """Build the Briloner RGB parameter payload used by opcode ``0xF1``."""

    if not 1 <= brightness <= 100:
        raise TelinkProtocolError("brightness must be from 1 through 100")
    for channel_name, channel in (("red", red), ("green", green), ("blue", blue)):
        if not 0 <= channel <= 255:
            raise TelinkProtocolError(f"{channel_name} must be from 0 through 255")
    return bytes((brightness, red, green, blue, 0, 0, 0, 0))
