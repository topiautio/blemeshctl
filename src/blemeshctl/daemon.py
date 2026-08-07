"""A short-lived local daemon for reusing a Telink BLE connection."""

from __future__ import annotations

import argparse
import asyncio
import fcntl
import json
import os
import stat
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .client import (
    DEFAULT_CONNECTION_SCAN_TIMEOUT,
    BleMeshError,
    BleMeshSession,
    InitialConnectionError,
)
from .protocol import TelinkProtocolError

DEFAULT_IDLE_TIMEOUT = 60.0
MAX_IDLE_TIMEOUT = 3600.0


class DaemonError(RuntimeError):
    """Raised when the local keepalive daemon cannot process a request."""

    def __init__(self, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.retryable = retryable


@dataclass(frozen=True)
class SessionConfig:
    """Device identity and credentials held by one daemon session."""

    address: str | None
    mesh_name: str
    password: str
    scan_timeout: float = field(compare=False)
    connect_timeout: float = field(compare=False)


def runtime_directory() -> Path:
    """Return a private directory suitable for the local Unix socket."""

    runtime_root = os.environ.get("XDG_RUNTIME_DIR")
    if runtime_root:
        directory = Path(runtime_root) / "blemeshctl"
    else:
        state_root = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state"))
        directory = state_root / "blemeshctl"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    return directory


def socket_path_for_address(address: str | None) -> Path:
    """Return a per-user socket path without putting credentials on disk."""

    name = "default" if address is None else address.replace(":", "").lower()
    if not name.isalnum():
        raise DaemonError("Bluetooth address contains unsupported socket-path characters")
    return runtime_directory() / f"{name}.sock"


def default_socket_path() -> Path:
    """Return the fallback socket path used when no MAC address is supplied."""

    return socket_path_for_address(None)


def _remove_socket(path: Path) -> None:
    """Remove only a stale Unix socket, never an arbitrary file."""

    try:
        mode = path.lstat().st_mode
    except FileNotFoundError:
        return
    if not stat.S_ISSOCK(mode):
        raise DaemonError(f"refusing to remove non-socket path: {path}")
    path.unlink()


def _lock_path(socket_path: Path) -> Path:
    return socket_path.with_name(f"{socket_path.name}.lock")


def _acquire_daemon_lock(socket_path: Path) -> int:
    """Acquire the lifetime lock that protects this socket from replacement."""

    descriptor = os.open(_lock_path(socket_path), os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as exc:
        os.close(descriptor)
        raise DaemonError("another keepalive daemon owns this socket") from exc
    return descriptor


def _remove_stale_socket(path: Path) -> None:
    """Remove a socket only when no daemon owns its corresponding lock."""

    descriptor = _acquire_daemon_lock(path)
    try:
        _remove_socket(path)
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def _response_timeout(payload: dict[str, Any]) -> float:
    """Allow enough time for a cold scan, connection, and login."""

    return max(
        45.0,
        float(payload.get("scan_timeout", DEFAULT_CONNECTION_SCAN_TIMEOUT))
        + float(payload.get("connect_timeout", 20.0))
        + 10.0,
    )


async def _request(socket_path: Path, payload: dict[str, Any]) -> dict[str, Any]:
    reader, writer = await asyncio.open_unix_connection(str(socket_path))
    try:
        writer.write(json.dumps(payload, separators=(",", ":")).encode() + b"\n")
        await writer.drain()
        raw_response = await asyncio.wait_for(reader.readline(), _response_timeout(payload))
    finally:
        writer.close()
        await writer.wait_closed()
    if not raw_response:
        raise DaemonError("keepalive daemon closed the connection without a response")
    try:
        response = json.loads(raw_response)
    except json.JSONDecodeError as exc:
        raise DaemonError("keepalive daemon returned invalid JSON") from exc
    if not isinstance(response, dict):
        raise DaemonError("keepalive daemon returned an invalid response")
    if not response.get("ok"):
        raise DaemonError(
            str(response.get("error", "keepalive daemon rejected the request")),
            retryable=response.get("retryable") is True,
        )
    return response


def _spawn_daemon(socket_path: Path) -> None:
    subprocess.Popen(
        [sys.executable, "-m", "blemeshctl.daemon", "--socket", str(socket_path)],
        close_fds=True,
        start_new_session=True,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


async def request_daemon(
    payload: dict[str, Any],
    *,
    start_if_needed: bool = True,
    socket_path: Path | None = None,
) -> dict[str, Any]:
    """Send one request, starting the per-user daemon when necessary."""

    path = default_socket_path() if socket_path is None else socket_path
    try:
        return await _request(path, payload)
    except (FileNotFoundError, ConnectionRefusedError) as first_error:
        if not start_if_needed:
            raise DaemonError("no keepalive daemon is running") from first_error

    _remove_stale_socket(path)
    _spawn_daemon(path)
    deadline = time.monotonic() + 5.0
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            return await _request(path, payload)
        except (FileNotFoundError, ConnectionRefusedError) as exc:
            last_error = exc
            await asyncio.sleep(0.05)
    raise DaemonError("keepalive daemon did not start") from last_error


def _config_from_request(request: dict[str, Any]) -> SessionConfig:
    address = request.get("address")
    mesh_name = request.get("mesh_name")
    password = request.get("password")
    if address is not None and not isinstance(address, str):
        raise DaemonError("address must be a string or null")
    if not isinstance(mesh_name, str) or not isinstance(password, str):
        raise DaemonError("mesh name and password must be strings")
    try:
        scan_timeout = float(request.get("scan_timeout", DEFAULT_CONNECTION_SCAN_TIMEOUT))
        connect_timeout = float(request.get("connect_timeout", 20.0))
    except (TypeError, ValueError) as exc:
        raise DaemonError("scan and connection timeouts must be numbers") from exc
    if scan_timeout <= 0 or connect_timeout <= 0:
        raise DaemonError("scan and connection timeouts must be positive")
    return SessionConfig(address, mesh_name, password, scan_timeout, connect_timeout)


def _idle_timeout_from_request(request: dict[str, Any]) -> float:
    try:
        timeout = float(request.get("idle_timeout", DEFAULT_IDLE_TIMEOUT))
    except (TypeError, ValueError) as exc:
        raise DaemonError("keepalive timeout must be a number") from exc
    if not 0 <= timeout <= MAX_IDLE_TIMEOUT:
        raise DaemonError(f"keepalive timeout must be from 0 through {MAX_IDLE_TIMEOUT:g} seconds")
    return timeout


class KeepaliveDaemon:
    """Serve normal light commands over a private local Unix socket."""

    def __init__(self, socket_path: Path, idle_timeout: float = DEFAULT_IDLE_TIMEOUT) -> None:
        self.socket_path = socket_path
        self.idle_timeout = idle_timeout
        self._session: BleMeshSession | None = None
        self._config: SessionConfig | None = None
        self._server: asyncio.AbstractServer | None = None
        self._command_lock = asyncio.Lock()
        self._lock_descriptor: int | None = None
        self._activity = asyncio.Event()
        self._shutdown = asyncio.Event()
        self._active_requests = 0
        self._last_command_at = time.monotonic()

    def _touch(self) -> None:
        self._last_command_at = time.monotonic()
        self._activity.set()

    async def execute_command(self, request: dict[str, Any]) -> dict[str, Any]:
        """Authenticate once, then send a command over the retained link."""

        config = _config_from_request(request)
        idle_timeout = _idle_timeout_from_request(request)
        try:
            opcode = int(request["opcode"])
            parameters = bytes.fromhex(str(request.get("parameters", "")))
        except (KeyError, TypeError, ValueError) as exc:
            raise DaemonError("command request has invalid opcode or parameters") from exc
        if not 0 <= opcode <= 0xFF:
            raise DaemonError("command opcode must fit in one byte")
        if len(parameters) > 10:
            raise DaemonError("command parameters may be at most 10 bytes")

        self._active_requests += 1
        try:
            async with self._command_lock:
                if self._session is None:
                    self._session = BleMeshSession(
                        address=config.address,
                        mesh_name=config.mesh_name,
                        password=config.password,
                        scan_timeout=config.scan_timeout,
                        connect_timeout=config.connect_timeout,
                    )
                    self._config = config
                elif config != self._config:
                    raise DaemonError(
                        "keepalive daemon is connected to a different device or mesh; "
                        "run 'blemeshctl daemon stop' first"
                    )
                reused = self._session.is_connected
                info = await self._session.send(opcode, parameters)
                self.idle_timeout = idle_timeout
                self._touch()
        except InitialConnectionError as exc:
            # Retrying with no address could later select a different nearby
            # generic BleMesh device. Only an explicitly selected MAC is safe
            # to wait for indefinitely.
            raise DaemonError(str(exc), retryable=config.address is not None) from exc
        except (BleMeshError, TelinkProtocolError) as exc:
            raise DaemonError(str(exc)) from exc
        finally:
            self._active_requests -= 1
        return {
            "address": info.address,
            "mesh_address": info.mesh_address,
            "reused": reused,
            "idle_timeout": self.idle_timeout,
        }

    async def _handle_client(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        stop_after_reply = False
        try:
            raw_request = await asyncio.wait_for(reader.readline(), timeout=5.0)
            if not raw_request:
                writer.close()
                await writer.wait_closed()
                return
            request = json.loads(raw_request)
            if not isinstance(request, dict):
                raise DaemonError("request must be a JSON object")
            request_type = request.get("type")
            if request_type == "command":
                response: dict[str, Any] = {"ok": True, **(await self.execute_command(request))}
            elif request_type == "status":
                response = {
                    "ok": True,
                    "connected": bool(self._session and self._session.is_connected),
                    "idle_timeout": self.idle_timeout,
                }
                if self._session and self._session.info:
                    response["address"] = self._session.info.address
                    response["mesh_address"] = self._session.info.mesh_address
            elif request_type == "stop":
                response = {"ok": True}
                stop_after_reply = True
            else:
                raise DaemonError("unknown keepalive daemon request")
        except (DaemonError, json.JSONDecodeError) as exc:
            response = {"ok": False, "error": str(exc)}
            if isinstance(exc, DaemonError) and exc.retryable:
                response["retryable"] = True
        except Exception as exc:
            response = {"ok": False, "error": f"daemon request failed: {exc}"}
        try:
            writer.write(json.dumps(response, separators=(",", ":")).encode() + b"\n")
            await writer.drain()
        finally:
            writer.close()
            await writer.wait_closed()
        if stop_after_reply:
            self._shutdown.set()

    async def _expire_when_idle(self) -> None:
        while not self._shutdown.is_set():
            if self._active_requests:
                await asyncio.sleep(0.1)
                continue
            remaining = self.idle_timeout - (time.monotonic() - self._last_command_at)
            if remaining <= 0:
                self._shutdown.set()
                return
            try:
                await asyncio.wait_for(self._activity.wait(), timeout=remaining)
            except TimeoutError:
                continue
            self._activity.clear()

    async def serve(self) -> None:
        """Run until stopped explicitly or no command arrives before the deadline."""

        self.socket_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self._lock_descriptor = _acquire_daemon_lock(self.socket_path)
        try:
            if self.socket_path.exists():
                raise DaemonError(f"keepalive socket already exists: {self.socket_path}")
            self._server = await asyncio.start_unix_server(
                self._handle_client, path=str(self.socket_path)
            )
            os.chmod(self.socket_path, 0o600)
            expiry_task = asyncio.create_task(self._expire_when_idle())
            try:
                await self._shutdown.wait()
            finally:
                expiry_task.cancel()
                try:
                    await expiry_task
                except asyncio.CancelledError:
                    pass
        finally:
            if self._server is not None:
                self._server.close()
                await self._server.wait_closed()
            if self._session is not None:
                await self._session.close()
            _remove_socket(self.socket_path)
            if self._lock_descriptor is not None:
                fcntl.flock(self._lock_descriptor, fcntl.LOCK_UN)
                os.close(self._lock_descriptor)
                self._lock_descriptor = None


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="blemeshctl-daemon")
    parser.add_argument("--socket", type=Path, required=True, help=argparse.SUPPRESS)
    return parser


def main() -> None:
    arguments = build_parser().parse_args()
    try:
        asyncio.run(KeepaliveDaemon(arguments.socket).serve())
    except (DaemonError, KeyboardInterrupt):
        # The CLI receives useful request errors over the Unix socket. The
        # daemon itself deliberately remains quiet when launched in background.
        return


if __name__ == "__main__":
    main()
