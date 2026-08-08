from __future__ import annotations

import asyncio
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from blemeshctl.client import InitialConnectionError
from blemeshctl.daemon import (
    INTERNAL_DAEMON_ARGUMENT,
    DaemonError,
    KeepaliveDaemon,
    _request,
    _spawn_daemon,
)
from blemeshctl.protocol import AdvertisementInfo


INFO = AdvertisementInfo(
    address="A4:C1:38:93:1B:72",
    name="BleMesh",
    mesh_uuid=0x0211,
    product_uuid=0x0004,
    status=1,
    mesh_address=0x0067,
    rssi=-50,
)


class FakeSession:
    instances: list["FakeSession"] = []

    def __init__(self, **settings: object) -> None:
        self.settings = settings
        self.is_connected = False
        self.info: AdvertisementInfo | None = None
        self.commands: list[tuple[int, bytes]] = []
        self.closed = False
        type(self).instances.append(self)

    async def send(self, opcode: int, parameters: bytes) -> AdvertisementInfo:
        self.is_connected = True
        self.info = INFO
        self.commands.append((opcode, parameters))
        return INFO

    async def close(self) -> None:
        self.is_connected = False
        self.closed = True


class UnavailableSession(FakeSession):
    async def send(self, opcode: int, parameters: bytes) -> AdvertisementInfo:
        raise InitialConnectionError("light is temporarily unavailable")


def command_request(**overrides: object) -> dict[str, object]:
    request: dict[str, object] = {
        "type": "command",
        "opcode": 0xF1,
        "parameters": "6400ff0000000000",
        "address": "A4:C1:38:93:1B:72",
        "mesh_name": "BleMesh",
        "password": "mesh123",
        "scan_timeout": 20,
        "connect_timeout": 20,
        "idle_timeout": 60,
    }
    request.update(overrides)
    return request


class DaemonLaunchTests(unittest.TestCase):
    def test_source_install_launches_daemon_as_a_python_module(self) -> None:
        socket_path = Path("/tmp/blemeshctl-source.sock")
        popen = Mock()
        with (
            patch.object(sys, "frozen", False, create=True),
            patch.object(sys, "executable", "/usr/bin/python3"),
            patch("blemeshctl.daemon.subprocess.Popen", popen),
        ):
            _spawn_daemon(socket_path)

        popen.assert_called_once_with(
            [
                "/usr/bin/python3",
                "-m",
                "blemeshctl.daemon",
                "--socket",
                str(socket_path),
            ],
            close_fds=True,
            start_new_session=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=None,
        )

    def test_frozen_install_relaunches_an_independent_binary(self) -> None:
        socket_path = Path("/tmp/blemeshctl-frozen.sock")
        popen = Mock()
        with (
            patch.dict(os.environ, {"EXISTING": "value"}, clear=True),
            patch.object(sys, "frozen", True, create=True),
            patch.object(sys, "executable", "/tmp/blemeshctl"),
            patch("blemeshctl.daemon.subprocess.Popen", popen),
        ):
            _spawn_daemon(socket_path)

        arguments, options = popen.call_args
        self.assertEqual(
            arguments[0],
            [
                "/tmp/blemeshctl",
                INTERNAL_DAEMON_ARGUMENT,
                "--socket",
                str(socket_path),
            ],
        )
        self.assertEqual(
            options["env"],
            {"EXISTING": "value", "PYINSTALLER_RESET_ENVIRONMENT": "1"},
        )
        self.assertTrue(options["close_fds"])
        self.assertTrue(options["start_new_session"])


class KeepaliveDaemonTests(unittest.TestCase):
    def setUp(self) -> None:
        FakeSession.instances = []

    def test_reuses_session_when_only_timeouts_change(self) -> None:
        async def scenario() -> tuple[dict[str, object], dict[str, object]]:
            daemon = KeepaliveDaemon(Path("/tmp/blemeshctl-test.sock"))
            first = await daemon.execute_command(command_request(scan_timeout=25))
            second = await daemon.execute_command(command_request(connect_timeout=30))
            return first, second

        with patch("blemeshctl.daemon.BleMeshSession", FakeSession):
            first, second = asyncio.run(scenario())

        self.assertFalse(first["reused"])
        self.assertTrue(second["reused"])
        self.assertEqual(len(FakeSession.instances), 1)
        self.assertEqual(len(FakeSession.instances[0].commands), 2)

    def test_rejects_different_mesh_credentials(self) -> None:
        async def scenario() -> None:
            daemon = KeepaliveDaemon(Path("/tmp/blemeshctl-test.sock"))
            await daemon.execute_command(command_request())
            with self.assertRaises(DaemonError):
                await daemon.execute_command(command_request(password="different"))

        with patch("blemeshctl.daemon.BleMeshSession", FakeSession):
            asyncio.run(scenario())

    def test_marks_an_initial_connection_failure_retryable(self) -> None:
        async def scenario() -> DaemonError:
            daemon = KeepaliveDaemon(Path("/tmp/blemeshctl-test.sock"))
            try:
                await daemon.execute_command(command_request())
            except DaemonError as exc:
                return exc
            self.fail("expected the unavailable session to fail")

        with patch("blemeshctl.daemon.BleMeshSession", UnavailableSession):
            error = asyncio.run(scenario())

        self.assertTrue(error.retryable)
        self.assertEqual(str(error), "light is temporarily unavailable")

    def test_does_not_retry_an_unaddressed_initial_connection_failure(self) -> None:
        async def scenario() -> DaemonError:
            daemon = KeepaliveDaemon(Path("/tmp/blemeshctl-test.sock"))
            try:
                await daemon.execute_command(command_request(address=None))
            except DaemonError as exc:
                return exc
            self.fail("expected the unavailable session to fail")

        with patch("blemeshctl.daemon.BleMeshSession", UnavailableSession):
            error = asyncio.run(scenario())

        self.assertFalse(error.retryable)

    def test_socket_error_preserves_the_retryable_marker(self) -> None:
        async def scenario(socket_path: Path) -> DaemonError:
            async def handler(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
                await reader.readline()
                writer.write(b'{"ok":false,"error":"temporarily unavailable","retryable":true}\n')
                await writer.drain()
                writer.close()
                await writer.wait_closed()

            server = await asyncio.start_unix_server(handler, path=str(socket_path))
            try:
                with self.assertRaises(DaemonError) as caught:
                    await _request(socket_path, {"type": "command"})
                return caught.exception
            finally:
                server.close()
                await server.wait_closed()

        with tempfile.TemporaryDirectory() as temporary_directory:
            error = asyncio.run(scenario(Path(temporary_directory) / "control.sock"))

        self.assertTrue(error.retryable)
        self.assertEqual(str(error), "temporarily unavailable")

    def test_unix_socket_reuses_connection_and_stops_cleanly(self) -> None:
        async def scenario(socket_path: Path) -> tuple[dict[str, object], dict[str, object]]:
            daemon = KeepaliveDaemon(socket_path)
            task = asyncio.create_task(daemon.serve())
            for _ in range(100):
                if socket_path.exists():
                    break
                await asyncio.sleep(0.01)
            else:
                self.fail("daemon did not create its socket")
            first = await _request(socket_path, command_request())
            second = await _request(socket_path, command_request(parameters="640000ff00000000"))
            await _request(socket_path, {"type": "stop"})
            await task
            return first, second

        with tempfile.TemporaryDirectory() as temporary_directory:
            socket_path = Path(temporary_directory) / "control.sock"
            with patch("blemeshctl.daemon.BleMeshSession", FakeSession):
                first, second = asyncio.run(scenario(socket_path))

        self.assertFalse(first["reused"])
        self.assertTrue(second["reused"])
        self.assertEqual(len(FakeSession.instances), 1)
        self.assertTrue(FakeSession.instances[0].closed)

    def test_idle_timeout_closes_the_retained_connection(self) -> None:
        async def scenario(socket_path: Path) -> dict[str, object]:
            daemon = KeepaliveDaemon(socket_path)
            task = asyncio.create_task(daemon.serve())
            for _ in range(100):
                if socket_path.exists():
                    break
                await asyncio.sleep(0.01)
            else:
                self.fail("daemon did not create its socket")
            response = await _request(socket_path, command_request(idle_timeout=0.05))
            await asyncio.wait_for(task, timeout=1.0)
            self.assertFalse(socket_path.exists())
            return response

        with tempfile.TemporaryDirectory() as temporary_directory:
            socket_path = Path(temporary_directory) / "control.sock"
            with patch("blemeshctl.daemon.BleMeshSession", FakeSession):
                response = asyncio.run(scenario(socket_path))

        self.assertEqual(response["idle_timeout"], 0.05)
        self.assertEqual(len(FakeSession.instances), 1)
        self.assertTrue(FakeSession.instances[0].closed)


if __name__ == "__main__":
    unittest.main()
