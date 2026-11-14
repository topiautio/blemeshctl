from __future__ import annotations

import asyncio
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from typing import Any, Callable
from unittest.mock import AsyncMock, patch

from blemeshctl import config
from blemeshctl.client import DiscoveredLight
from blemeshctl.cli import _format_light, build_parser, main, run
from blemeshctl.config import ConfigError
from blemeshctl.daemon import DaemonError, socket_path_for_address
from blemeshctl.protocol import (
    COMMAND_ON_OFF,
    COMMAND_RGB,
    AdvertisementInfo,
    off_parameters,
    on_parameters,
    rgb_parameters,
)
from blemeshctl.script import ScriptError


DESK = "A4:C1:38:93:1B:72"
SHELF = "00:11:22:33:44:55"
LAMP = "00:11:22:33:44:66"


class FakeDaemons:
    """Record requests per address and fail chosen addresses."""

    def __init__(self, failures: dict[str, Exception] | None = None) -> None:
        self.failures = failures or {}
        self.events: list[tuple[Any, ...]] = []
        self.request = AsyncMock(side_effect=self._request)
        self.sleep = AsyncMock(side_effect=self._sleep)

    async def _request(
        self, payload: dict[str, Any], *, socket_path: Path | None = None, start_if_needed: bool = True
    ) -> dict[str, Any]:
        address = payload["address"]
        self.events.append(("send", address, payload["opcode"], payload["parameters"], socket_path))
        failure = self.failures.get(address)
        if failure is not None:
            raise failure
        return {"address": address, "mesh_address": 0x67, "reused": False, "idle_timeout": 60.0}

    async def _sleep(self, seconds: float) -> None:
        self.events.append(("wait", seconds))

    def sends(self, address: str | None = None) -> list[tuple[Any, ...]]:
        return [event for event in self.events if event[0] == "send" and (address is None or event[1] == address)]


class CliTestCase(unittest.TestCase):
    """Use temporary config and runtime directories and a fake daemon."""

    def setUp(self) -> None:
        super().setUp()
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        environment = patch.dict(
            os.environ, {"XDG_CONFIG_HOME": str(root / "config"), "XDG_RUNTIME_DIR": str(root / "run")}
        )
        environment.start()
        self.addCleanup(environment.stop)
        self.root = root

    def run_cli(
        self, argv: list[str], daemons: FakeDaemons | None = None, stdin: str = ""
    ) -> tuple[int, str, str]:
        daemons = daemons or FakeDaemons()
        arguments = build_parser().parse_args(argv)
        stdout, stderr = io.StringIO(), io.StringIO()
        with (
            patch("blemeshctl.cli.request_daemon", new=daemons.request),
            patch("blemeshctl.cli.asyncio.sleep", new=daemons.sleep),
            patch("sys.stdin", io.StringIO(stdin)),
            redirect_stdout(stdout),
            redirect_stderr(stderr),
        ):
            result = asyncio.run(run(arguments))
        return result, stdout.getvalue(), stderr.getvalue()


class LightOptionTests(CliTestCase):
    def test_light_and_address_are_mutually_exclusive(self) -> None:
        for command in (["on"], ["color", "00ff00"], ["script", "x.blemesh"], ["scene", "apply", "x"]):
            with self.subTest(command=command), redirect_stderr(io.StringIO()) as stderr:
                with self.assertRaises(SystemExit) as raised:
                    build_parser().parse_args([*command, "--address", DESK, "--light", "desk"])
                self.assertEqual(raised.exception.code, 2)
                self.assertIn("not allowed with argument", stderr.getvalue())

    def test_alias_produces_the_same_request_as_the_address(self) -> None:
        config.add_alias("desk", DESK)
        by_alias, by_address = FakeDaemons(), FakeDaemons()

        alias_result = self.run_cli(["color", "00ff00", "--light", "desk"], by_alias)
        address_result = self.run_cli(["color", "00ff00", "--address", DESK], by_address)

        self.assertEqual(alias_result, address_result)
        self.assertEqual(by_alias.request.await_args_list, by_address.request.await_args_list)
        self.assertEqual(by_alias.request.await_args.kwargs["socket_path"], socket_path_for_address(DESK))

    def test_alias_keeps_the_single_light_wait_default_for_scripts(self) -> None:
        config.add_alias("desk", DESK)
        script = self.root / "scene.blemesh"
        script.write_text("on\n", encoding="utf-8")
        daemons = FakeDaemons(
            {DESK: DaemonError(f"{DESK} was not advertising", retryable=True)}
        )
        calls = 0

        async def request(payload: dict[str, Any], **kwargs: Any) -> dict[str, Any]:
            nonlocal calls
            calls += 1
            if calls == 1:
                return await daemons._request(payload, **kwargs)
            daemons.failures.clear()
            return await daemons._request(payload, **kwargs)

        daemons.request = AsyncMock(side_effect=request)
        result, output, _ = self.run_cli(["script", str(script), "--light", "desk"], daemons)

        self.assertEqual(result, 0)
        self.assertEqual(len(daemons.sends(DESK)), 2)
        self.assertIn(f"Waiting for {DESK}", output)

    def test_raw_mac_light_is_normalised(self) -> None:
        daemons = FakeDaemons()

        result, _, _ = self.run_cli(["on", "--light", SHELF.lower()], daemons)

        self.assertEqual(result, 0)
        self.assertEqual(daemons.sends(), [("send", SHELF, COMMAND_ON_OFF, on_parameters().hex(), socket_path_for_address(SHELF))])

    def test_raw_mac_light_ignores_a_corrupt_config(self) -> None:
        directory = self.root / "config" / "blemeshctl"
        directory.mkdir(parents=True)
        (directory / "lights.json").write_text("{oops", encoding="utf-8")
        daemons = FakeDaemons()

        result, _, _ = self.run_cli(["on", "--light", DESK], daemons)

        self.assertEqual(result, 0)
        self.assertEqual(len(daemons.sends(DESK)), 1)
        with self.assertRaisesRegex(ConfigError, "invalid JSON"):
            self.run_cli(["on", "--light", "desk"], daemons)

    def test_unknown_light_makes_no_daemon_request(self) -> None:
        daemons = FakeDaemons()

        with self.assertRaisesRegex(ConfigError, "unknown light 'desk'"):
            self.run_cli(["on", "--light", "desk"], daemons)
        daemons.request.assert_not_awaited()


MESH = {DESK: 0x0101, SHELF: 0x0102, LAMP: 0x0103}


class Runaway(BaseException):
    """Stop a test whose program would otherwise never end (not an Exception)."""


def advertised(address: str, rssi: int | None, mesh_address: int | None = None) -> DiscoveredLight:
    mesh = MESH[address] if mesh_address is None else mesh_address
    return DiscoveredLight(object(), AdvertisementInfo(address, "BleMesh", 0x0211, 0x0004, 0x01, mesh, rssi))


class FakeMesh:
    """One fake daemon per gateway MAC plus a fake advertising scan.

    ``fail(gateway, target, opcode, count)`` returns an exception to raise for
    a command request, where ``count`` is how many commands that gateway's
    daemon has already accepted.
    """

    def __init__(
        self,
        scans: list[list[DiscoveredLight]],
        fail: Callable[[str, int | None, int, int], BaseException | None] | None = None,
        status: dict[str, dict[str, Any]] | None = None,
    ) -> None:
        self.scans = scans
        self.fail = fail or (lambda gateway, target, opcode, count: None)
        self.status = status or {}
        self.events: list[tuple[Any, ...]] = []
        self.payloads: list[dict[str, Any]] = []
        self.accepted: dict[str, int] = {}
        self.scan_count = 0
        self.request = AsyncMock(side_effect=self._request)
        self.sleep = AsyncMock(side_effect=self._sleep)
        self.discover = AsyncMock(side_effect=self._discover)

    async def _discover(self, timeout: float) -> list[DiscoveredLight]:
        self.events.append(("scan", timeout))
        self.scan_count += 1
        return self.scans[min(self.scan_count, len(self.scans)) - 1]

    async def _request(
        self, payload: dict[str, Any], *, socket_path: Path | None = None, start_if_needed: bool = True
    ) -> dict[str, Any]:
        if payload["type"] == "status":
            for address, response in self.status.items():
                if socket_path == socket_path_for_address(address):
                    return response
            raise DaemonError("no keepalive daemon is running")
        if len(self.payloads) > 200:
            raise Runaway
        gateway = payload["address"]
        target = payload.get("target_mesh_address")
        self.payloads.append(payload)
        self.events.append(("send", gateway, target, payload["opcode"], payload["parameters"]))
        assert socket_path == socket_path_for_address(gateway), "request went to another member's daemon"
        failure = self.fail(gateway, target, payload["opcode"], self.accepted.get(gateway, 0))
        if failure is not None:
            raise failure
        count = self.accepted.get(gateway, 0)
        self.accepted[gateway] = count + 1
        return {
            "address": gateway,
            "mesh_address": MESH[gateway],
            "target_mesh_address": MESH[gateway] if target is None else target,
            "reused": count > 0,
            "idle_timeout": 60.0,
        }

    async def _sleep(self, seconds: float) -> None:
        self.events.append(("wait", seconds))

    def sends(self) -> list[tuple[str, int | None]]:
        return [(event[1], event[2]) for event in self.events if event[0] == "send"]


class GroupTestCase(CliTestCase):
    def setUp(self) -> None:
        super().setUp()
        config.add_alias("desk", DESK)
        config.add_group("office", ["desk", SHELF, LAMP])

    def run_group(self, argv: list[str], mesh: FakeMesh) -> tuple[int, str, str]:
        arguments = build_parser().parse_args(argv)
        stdout, stderr = io.StringIO(), io.StringIO()
        with (
            patch("blemeshctl.cli.request_daemon", new=mesh.request),
            patch("blemeshctl.cli.discover_lights", new=mesh.discover),
            patch("blemeshctl.cli.asyncio.sleep", new=mesh.sleep),
            redirect_stdout(stdout),
            redirect_stderr(stderr),
        ):
            result = asyncio.run(run(arguments))
        return result, stdout.getvalue(), stderr.getvalue()

    def script(self, text: str) -> str:
        path = self.root / "scene.blemesh"
        path.write_text(text, encoding="utf-8")
        return str(path)


def all_three(desk: int | None = -70, shelf: int | None = -40, lamp: int | None = -55) -> list[DiscoveredLight]:
    return [advertised(DESK, desk), advertised(SHELF, shelf), advertised(LAMP, lamp)]


class GroupRelayTests(GroupTestCase):
    def test_one_scan_and_one_connection_through_the_strongest_member(self) -> None:
        mesh = FakeMesh([all_three()])

        result, output, error = self.run_group(["off", "--light", "office"], mesh)

        self.assertEqual((result, error), (0, ""))
        mesh.discover.assert_awaited_once_with(20.0)
        # The gateway's own command uses the default destination; the other
        # members are relayed by their advertised mesh addresses, in group order.
        self.assertEqual(mesh.sends(), [(SHELF, None), (SHELF, MESH[DESK]), (SHELF, MESH[LAMP])])
        self.assertNotIn("target_mesh_address", mesh.payloads[0])
        for payload in mesh.payloads:
            self.assertEqual(
                (payload["address"], payload["opcode"], payload["parameters"]),
                (SHELF, COMMAND_ON_OFF, off_parameters().hex()),
            )
        self.assertEqual(
            output,
            f"Group office: connected through {SHELF} (mesh 0x0102; connection opened, kept for 60s after "
            f"the last command); relaying to {DESK} (mesh 0x0101), {LAMP} (mesh 0x0103)\n"
            f"off command sent to 3 lights in group office via {SHELF} "
            "(relayed members are not individually acknowledged)\n",
        )

    def test_status_queries_never_start_a_daemon(self) -> None:
        mesh = FakeMesh([all_three()])

        self.run_group(["on", "--light", "office"], mesh)

        status_calls = [call for call in mesh.request.await_args_list if call.args[0]["type"] == "status"]
        self.assertEqual(len(status_calls), 3)
        self.assertTrue(all(call.kwargs["start_if_needed"] is False for call in status_calls))

    def test_unknown_rssi_ranks_last(self) -> None:
        mesh = FakeMesh([all_three(desk=None, shelf=-90, lamp=None)])

        self.assertEqual(self.run_group(["on", "--light", "office"], mesh)[0], 0)
        self.assertEqual(mesh.sends()[0], (SHELF, None))

    def test_script_steps_reach_every_member_before_the_next_step(self) -> None:
        script = self.script("on\nrepeat 2:\n  color ff0000\n  wait 1s\n")
        mesh = FakeMesh([all_three()])

        result, output, _ = self.run_group(["script", script, "--light", "office"], mesh)

        self.assertEqual(result, 0)
        on, red = (COMMAND_ON_OFF, on_parameters().hex()), (COMMAND_RGB, rgb_parameters(255, 0, 0, 100).hex())
        steps = [(event[3], event[4]) if event[0] == "send" else event for event in mesh.events]
        self.assertEqual(
            steps, [("scan", 20.0)] + [on] * 3 + [red] * 3 + [("wait", 1.0)] + [red] * 3 + [("wait", 1.0)]
        )
        targets = [(SHELF, None), (SHELF, MESH[DESK]), (SHELF, MESH[LAMP])]
        self.assertEqual(mesh.sends(), targets * 3)
        self.assertIn(
            f"scene.blemesh:3: colour rgb #ff0000, brightness 100 command sent to 3 lights in group office "
            f"via {SHELF} (relayed members are not individually acknowledged)",
            output,
        )
        self.assertIn("scene.blemesh:4: waiting 1s", output)

    def test_member_not_advertising_fails_while_the_others_continue(self) -> None:
        script = self.script("on\nwait 1s\noff\n")
        mesh = FakeMesh([[advertised(DESK, -70), advertised(SHELF, -40)]])

        result, output, error = self.run_group(["script", script, "--light", "office"], mesh)

        self.assertEqual(result, 1)
        self.assertEqual(mesh.sends(), [(SHELF, None), (SHELF, MESH[DESK])] * 2)
        self.assertNotIn(LAMP, output)
        self.assertEqual(output.count("command sent to 2 lights in group office"), 2)
        self.assertIn(
            f"blemeshctl: error: {LAMP}: not seen advertising during the 20s scan, so its mesh address is "
            "unknown; no commands will be sent to it",
            error,
        )
        self.assertIn(f"1 of 3 lights in group office failed: {LAMP}", error)

    def test_member_advertising_the_broadcast_address_is_never_addressed(self) -> None:
        mesh = FakeMesh([[advertised(DESK, -70), advertised(SHELF, -40), advertised(LAMP, -30, 0xFFFF)]])

        result, _, error = self.run_group(["on", "--light", "office"], mesh)

        self.assertEqual(result, 1)
        self.assertEqual(mesh.sends(), [(SHELF, None), (SHELF, MESH[DESK])])
        self.assertIn(f"{LAMP}: advertises the broadcast mesh address 0xffff", error)
        self.assertIn(f"1 of 3 lights in group office failed: {LAMP}", error)

    def test_shared_mesh_address_is_warned_about(self) -> None:
        stranger = "00:11:22:33:44:77"
        mesh = FakeMesh([[*all_three(), advertised(stranger, -80, MESH[LAMP])]])

        result, _, error = self.run_group(["on", "--light", "office"], mesh)

        self.assertEqual(result, 0)
        self.assertIn(f"warning: {LAMP} shares mesh 0x0103 with {stranger}", error)

    def test_initial_connection_failure_fails_over_to_the_next_member(self) -> None:
        unavailable = DaemonError(f"{SHELF} was not advertising", retryable=True)
        mesh = FakeMesh(
            [all_three()], fail=lambda gateway, target, opcode, count: unavailable if gateway == SHELF else None
        )

        result, output, error = self.run_group(["on", "--light", "office"], mesh)

        self.assertEqual(result, 0)
        # LAMP has the next strongest signal; SHELF stays a relayed member.
        self.assertEqual(
            mesh.sends(), [(SHELF, None), (LAMP, None), (LAMP, MESH[DESK]), (LAMP, MESH[SHELF])]
        )
        self.assertIn(f"blemeshctl: error: {SHELF}: {SHELF} was not advertising; trying {LAMP} as the gateway", error)
        self.assertIn(f"on command sent to 3 lights in group office via {LAMP}", output)

    def test_group_fails_when_no_member_accepts_a_connection(self) -> None:
        script = self.script("loop:\n  on\n  wait 1s\n")
        mesh = FakeMesh(
            [all_three()],
            fail=lambda gateway, target, opcode, count: DaemonError("not advertising", retryable=True),
        )

        result, output, error = self.run_group(["script", script, "--light", "office"], mesh)

        self.assertEqual(result, 1)
        self.assertEqual(mesh.sends(), [(SHELF, None), (LAMP, None), (DESK, None)])
        mesh.sleep.assert_not_awaited()
        self.assertNotIn("sent", output)
        self.assertIn("no member accepted a Bluetooth connection; nothing was sent", error)
        self.assertIn(f"3 of 3 lights in group office failed: {DESK}, {SHELF}, {LAMP}", error)

    def test_non_retryable_first_failure_stops_without_failover(self) -> None:
        mesh = FakeMesh(
            [all_three()],
            fail=lambda gateway, target, opcode, count: DaemonError(
                "Bluetooth command failed: write rejected"
            ),
        )

        result, output, error = self.run_group(["on", "--light", "office"], mesh)

        self.assertEqual(result, 1)
        self.assertEqual(mesh.sends(), [(SHELF, None)])
        self.assertEqual(output, "")
        self.assertIn(f"outcome unknown for {SHELF}; not sent to {DESK}, {LAMP}", error)

    def test_mid_run_gateway_failure_stops_the_whole_group(self) -> None:
        script = self.script("on\nwait 1s\ncolor ff0000\noff\n")
        dropped = DaemonError("Bluetooth connection dropped; command outcome is unknown, retry manually")
        mesh = FakeMesh(
            [all_three()],
            fail=lambda gateway, target, opcode, count: dropped
            if opcode == COMMAND_RGB and target == MESH[DESK]
            else None,
        )

        result, output, error = self.run_group(["script", script, "--light", "office"], mesh)

        self.assertEqual(result, 1)
        # Nothing follows the failed write: not LAMP's colour, nor any "off".
        self.assertEqual(
            mesh.sends(),
            [(SHELF, None), (SHELF, MESH[DESK]), (SHELF, MESH[LAMP]), (SHELF, None), (SHELF, MESH[DESK])],
        )
        self.assertIn("scene.blemesh:1: on command sent to 3 lights", output)
        self.assertNotIn("colour", output)
        self.assertNotIn("off command", output)
        self.assertIn(f"blemeshctl: error: {DESK} via {SHELF}: Bluetooth connection dropped", error)
        self.assertIn(
            f"group office stopped because the write through {SHELF} failed; no further commands will be sent. "
            f"{script}:3: colour rgb #ff0000, brightness 100: sent to {SHELF}; outcome unknown for {DESK}; "
            f"not sent to {LAMP}; 1 earlier step was sent to every addressed light",
            error,
        )
        self.assertTrue(
            error.endswith(
                "blemeshctl: group office stopped before the program finished, so no light in it received "
                "every step\n"
            )
        )
        self.assertNotIn("lights in group office failed", error)

    def test_reconnect_failure_after_delivery_does_not_fail_over(self) -> None:
        script = self.script("on\nwait 1s\noff\n")
        mesh = FakeMesh(
            [all_three()],
            fail=lambda gateway, target, opcode, count: DaemonError("not advertising", retryable=True)
            if count == 3
            else None,
        )

        result, _, error = self.run_group(["script", script, "--light", "office"], mesh)

        self.assertEqual(result, 1)
        self.assertEqual({gateway for gateway, _ in mesh.sends()}, {SHELF})
        self.assertEqual(len(mesh.sends()), 4)
        self.assertIn(f"outcome unknown for {SHELF}; not sent to {DESK}, {LAMP}", error)

    def test_unexpected_exception_is_an_unknown_outcome(self) -> None:
        mesh = FakeMesh(
            [all_three()],
            fail=lambda gateway, target, opcode, count: TimeoutError() if target == MESH[LAMP] else None,
        )

        result, output, error = self.run_group(["on", "--light", "office"], mesh)

        self.assertEqual(result, 1)
        self.assertNotIn("on command sent", output)
        self.assertIn(f"blemeshctl: error: {LAMP} via {SHELF}: TimeoutError", error)
        self.assertIn(f"sent to {SHELF}, {DESK}; outcome unknown for {LAMP}", error)
        self.assertIn(f"1 of 3 lights in group office failed: {LAMP}", error)

    def test_endless_program_stops_after_a_gateway_failure(self) -> None:
        script = self.script("loop:\n  on\n  wait 1s\n")
        mesh = FakeMesh(
            [all_three()],
            fail=lambda gateway, target, opcode, count: DaemonError("dropped") if count == 6 else None,
        )

        mesh.scans = [[advertised(DESK, -70), advertised(SHELF, -40)]]

        result, _, error = self.run_group(["script", script, "--light", "office"], mesh)

        self.assertEqual(result, 1)
        # Three full rounds of two lights, then the gateway's own write fails.
        self.assertEqual(len(mesh.sends()), 7)
        self.assertIn(f"blemeshctl: error: {SHELF}: dropped", error)
        self.assertIn(
            f"stopped before the program finished, so no light in it received every step; never addressed: {LAMP}",
            error,
        )

    def test_already_connected_member_is_preferred_as_gateway(self) -> None:
        status = {DESK: {"ok": True, "connected": True, "address": DESK, "mesh_address": MESH[DESK]}}
        # A connected light may stop advertising, so DESK is missing from the scan.
        mesh = FakeMesh([[advertised(SHELF, -40), advertised(LAMP, -55)]], status=status)
        mesh.accepted[DESK] = 1

        result, output, error = self.run_group(["on", "--light", "office"], mesh)

        self.assertEqual((result, error), (0, ""))
        self.assertEqual(mesh.sends(), [(DESK, None), (DESK, MESH[SHELF]), (DESK, MESH[LAMP])])
        self.assertIn(f"connected through {DESK} (mesh 0x0101; connection reused", output)

    def test_scan_failure_exits_one_without_commands(self) -> None:
        mesh = FakeMesh([all_three()])
        mesh.discover.side_effect = RuntimeError("No Bluetooth adapters found.")

        result, output, error = self.run_group(["on", "--light", "office"], mesh)

        self.assertEqual((result, output), (1, ""))
        self.assertEqual(mesh.sends(), [])
        self.assertIn("group office: Bluetooth scan failed: No Bluetooth adapters found.", error)

    def test_group_waits_for_members_only_when_asked_explicitly(self) -> None:
        script = self.script("on\n")
        partial = [advertised(DESK, -70), advertised(SHELF, -40)]

        # Scripts normally wait, but a group does not unless asked.
        mesh = FakeMesh([partial, all_three()])
        result, output, _ = self.run_group(["script", script, "--light", "office"], mesh)
        self.assertEqual(result, 1)
        self.assertEqual(mesh.discover.await_count, 1)
        mesh.sleep.assert_not_awaited()
        self.assertNotIn("Waiting for", output)

        mesh = FakeMesh([partial, all_three()])
        result, output, _ = self.run_group(
            ["script", script, "--light", "office", "--wait-for-connection"], mesh
        )
        self.assertEqual(result, 0)
        self.assertEqual(mesh.discover.await_count, 2)
        self.assertIn(f"Waiting for {LAMP} in group office to advertise; press Ctrl-C to stop.", output)
        self.assertEqual(mesh.sends(), [(SHELF, None), (SHELF, MESH[DESK]), (SHELF, MESH[LAMP])])

    def test_explicit_wait_retries_the_gateway_connection(self) -> None:
        mesh = FakeMesh(
            [all_three()],
            fail=lambda gateway, target, opcode, count: DaemonError("busy", retryable=True)
            if len(mesh.payloads) <= 3
            else None,
        )

        result, output, error = self.run_group(["on", "--light", "office", "--wait-for-connection"], mesh)

        self.assertEqual(result, 0)
        self.assertEqual(mesh.sends()[:4], [(SHELF, None), (LAMP, None), (DESK, None), (SHELF, None)])
        self.assertIn("Waiting for a member of group office to accept a Bluetooth connection", output)
        self.assertEqual(error.count("blemeshctl: error:"), 3)

    def test_group_with_one_member_still_uses_group_semantics(self) -> None:
        config.add_group("solo", [SHELF])

        mesh = FakeMesh([[advertised(SHELF, -40)]])
        result, output, _ = self.run_group(["on", "--light", "solo"], mesh)
        self.assertEqual(result, 0)
        self.assertEqual(mesh.sends(), [(SHELF, None)])
        self.assertTrue(output.endswith(f"on command sent to 1 light in group solo via {SHELF}\n"))
        self.assertNotIn("relay", output)

        mesh = FakeMesh([[]])
        result, _, error = self.run_group(["on", "--light", "solo"], mesh)
        self.assertEqual(result, 1)
        self.assertEqual(mesh.sends(), [])
        self.assertIn("1 of 1 lights in group solo failed", error)

    def test_invalid_group_command_makes_no_request_or_scan(self) -> None:
        mesh = FakeMesh([all_three()])

        with self.assertRaises(ValueError):
            self.run_group(["color", "00ff00", "--brightness", "0", "--light", "office"], mesh)
        mesh.request.assert_not_awaited()
        mesh.discover.assert_not_awaited()

    def test_scene_apply_to_a_group(self) -> None:
        config.add_group("pair", [DESK, SHELF])
        self.run_cli(["scene", "save", "evening", "--from-file", "-"], stdin="on\nwait 2s\noff\n")
        mesh = FakeMesh([[advertised(DESK, -40)]])

        result, output, error = self.run_group(["scene", "apply", "evening", "--light", "pair"], mesh)

        self.assertEqual(result, 1)
        self.assertEqual(mesh.sends(), [(DESK, None), (DESK, None)])
        self.assertEqual(
            [payload["parameters"] for payload in mesh.payloads], [on_parameters().hex(), off_parameters().hex()]
        )
        self.assertIn("scene evening:2: waiting 2s", output)
        self.assertIn(f"scene evening:3: off command sent to 1 light in group pair via {DESK}", output)
        self.assertIn(f"1 of 2 lights in group pair failed: {SHELF}", error)

    def test_single_light_paths_never_scan(self) -> None:
        script = self.root / "scene.blemesh"
        script.write_text("on\n", encoding="utf-8")
        discover = AsyncMock()
        with patch("blemeshctl.cli.discover_lights", new=discover):
            self.run_cli(["on", "--light", "desk"])
            self.run_cli(["script", str(script), "--address", SHELF])
        discover.assert_not_awaited()


class ConfigCommandTests(CliTestCase):
    def test_alias_and_group_commands(self) -> None:
        self.assertEqual(self.run_cli(["alias", "list"])[1], "No aliases defined.\n")
        self.assertEqual(self.run_cli(["alias", "add", "desk", DESK.lower()])[1], f"Alias desk -> {DESK}\n")
        self.assertEqual(
            self.run_cli(["group", "add", "office", "desk", SHELF.lower()])[1], f"Group office -> {DESK}, {SHELF}\n"
        )
        self.assertEqual(self.run_cli(["alias", "list"])[1], f"desk  {DESK}\n")
        self.assertEqual(self.run_cli(["group", "list"])[1], f"office  desk {SHELF}\n")
        with self.assertRaisesRegex(ConfigError, "already exists"):
            self.run_cli(["group", "add", "office", SHELF])
        self.run_cli(["group", "add", "office", SHELF, "--force"])
        self.assertEqual(self.run_cli(["group", "remove", "office"])[1], "Group office removed.\n")
        self.assertEqual(self.run_cli(["alias", "remove", "desk"])[1], "Alias desk removed.\n")
        self.assertEqual(self.run_cli(["group", "list"])[1], "No groups defined.\n")

    def test_main_reports_corrupt_config_on_one_line(self) -> None:
        directory = self.root / "config" / "blemeshctl"
        directory.mkdir(parents=True)
        (directory / "lights.json").write_text("{oops", encoding="utf-8")
        request = AsyncMock()
        with (
            patch("sys.argv", ["blemeshctl", "on", "--light", "desk"]),
            patch("blemeshctl.cli.request_daemon", new=request),
            redirect_stderr(io.StringIO()) as stderr,
            self.assertRaises(SystemExit) as raised,
        ):
            main()

        self.assertEqual(raised.exception.code, 2)
        self.assertRegex(stderr.getvalue(), r"^blemeshctl: error: .*lights\.json: invalid JSON: [^\n]*\n$")
        request.assert_not_awaited()

    def test_scan_shows_aliases_and_tolerates_a_corrupt_config(self) -> None:
        light = type("Light", (), {"info": AdvertisementInfo(DESK, "BleMesh", 0x1111, 0x1234, 0x01, 0x67, -60)})()
        self.assertTrue(_format_light(light, ["desk", "lamp"]).endswith("rssi=-60  alias=desk,lamp"))
        self.assertNotIn("alias=", _format_light(light))

        config.add_alias("desk", DESK)
        with patch("blemeshctl.cli.discover_lights", new=AsyncMock(return_value=[light])):
            self.assertIn("alias=desk", self.run_cli(["scan"])[1])
            (self.root / "config" / "blemeshctl" / "lights.json").write_text("{oops", encoding="utf-8")
            result, output, error = self.run_cli(["scan"])
        self.assertEqual(result, 0)
        self.assertNotIn("alias=", output)
        self.assertIn("warning", error)


class SceneCommandTests(CliTestCase):
    def test_scene_save_from_flags_then_apply_to_one_light(self) -> None:
        result, output, _ = self.run_cli(["scene", "save", "evening", "--color", "ff8800", "--brightness", "40"])
        self.assertEqual(result, 0)
        self.assertIn("Scene evening saved to", output)
        self.assertEqual(self.run_cli(["scene", "show", "evening"])[1], "color ff8800 40\n")

        daemons = FakeDaemons()
        result, output, _ = self.run_cli(["scene", "apply", "evening", "--address", DESK], daemons)

        self.assertEqual(result, 0)
        self.assertEqual(
            daemons.sends(),
            [("send", DESK, COMMAND_RGB, rgb_parameters(255, 136, 0, 40).hex(), socket_path_for_address(DESK))],
        )
        self.assertIn(f"scene evening:1: colour rgb #ff8800, brightness 40 command sent to {DESK}", output)

    def test_scene_apply_waits_like_a_script(self) -> None:
        arguments = build_parser().parse_args(["scene", "apply", "evening"])
        self.assertTrue(arguments.wait_for_connection)
        self.assertFalse(arguments.wait_for_connection_explicit)

    def test_scene_save_white_and_on_off(self) -> None:
        self.run_cli(["scene", "save", "bright", "--white"])
        self.run_cli(["scene", "save", "warm", "--white", "2700", "--brightness", "60"])
        self.run_cli(["scene", "save", "dark", "--off"])

        self.assertEqual(config.read_scene("bright"), "white 4600K 100\n")
        self.assertEqual(config.read_scene("warm"), "white 2700K 60\n")
        self.assertEqual(config.read_scene("dark"), "off\n")
        with self.assertRaisesRegex(ConfigError, "--brightness applies only"):
            self.run_cli(["scene", "save", "lit", "--on", "--brightness", "5"])

    def test_scene_save_refuses_to_overwrite_without_force(self) -> None:
        self.run_cli(["scene", "save", "evening", "--on"])

        with self.assertRaisesRegex(ConfigError, "already exists; pass --force"):
            self.run_cli(["scene", "save", "evening", "--off"])
        self.assertEqual(config.read_scene("evening"), "on\n")
        self.run_cli(["scene", "save", "evening", "--off", "--force"])
        self.assertEqual(config.read_scene("evening"), "off\n")

    def test_scene_save_from_file_and_stdin_validates_first(self) -> None:
        source = self.root / "party.blemesh"
        source.write_text("on\nloop:\n  rainbow 100ms\n", encoding="utf-8")
        self.run_cli(["scene", "save", "party", "--from-file", str(source)])
        self.assertEqual(config.read_scene("party"), "on\nloop:\n  rainbow 100ms\n")

        self.run_cli(["scene", "save", "pulse", "--from-file", "-"], stdin="repeat 2:\n  on\n  off\n")
        self.assertEqual(config.read_scene("pulse"), "repeat 2:\n  on\n  off\n")

        with self.assertRaisesRegex(ScriptError, r"<stdin>:2: unknown instruction 'flash'"):
            self.run_cli(["scene", "save", "broken", "--from-file", "-"], stdin="on\nflash\n")
        broken = self.root / "broken.blemesh"
        broken.write_text("loop:\n  on\n", encoding="utf-8")
        with self.assertRaisesRegex(ScriptError, r"broken\.blemesh:1: an endless loop needs"):
            self.run_cli(["scene", "save", "broken", "--from-file", str(broken)])
        with self.assertRaisesRegex(ScriptError, "could not read script"):
            self.run_cli(["scene", "save", "broken", "--from-file", str(self.root / "missing")])
        self.assertEqual(config.list_scenes(), ["party", "pulse"])

    def test_scene_list_show_and_delete(self) -> None:
        self.assertEqual(self.run_cli(["scene", "list"])[1], "No scenes saved.\n")
        self.run_cli(["scene", "save", "night", "--off"])
        self.run_cli(["scene", "save", "evening", "--on"])

        self.assertEqual(self.run_cli(["scene", "list"])[1], "evening\nnight\n")
        self.assertEqual(self.run_cli(["scene", "delete", "night"])[1], "Scene night deleted.\n")
        self.assertEqual(self.run_cli(["scene", "list"])[1], "evening\n")
        with self.assertRaisesRegex(ConfigError, "no scene named 'night'"):
            self.run_cli(["scene", "show", "night"])

    def test_missing_or_corrupt_scene_makes_no_daemon_request(self) -> None:
        daemons = FakeDaemons()
        with self.assertRaisesRegex(ConfigError, "no scene named 'evening'"):
            self.run_cli(["scene", "apply", "evening", "--address", DESK], daemons)

        scenes = self.root / "config" / "blemeshctl" / "scenes"
        scenes.mkdir(parents=True)
        (scenes / "evening.blemesh").write_text("on\ncolor ff0000\nnope\n", encoding="utf-8")
        with self.assertRaisesRegex(ScriptError, "scene evening:3: unknown instruction"):
            self.run_cli(["scene", "apply", "evening", "--address", DESK], daemons)
        daemons.request.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
