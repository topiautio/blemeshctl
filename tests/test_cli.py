from __future__ import annotations

import asyncio
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, patch

from blemeshctl import config
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


class GroupFanOutTests(CliTestCase):
    def setUp(self) -> None:
        super().setUp()
        config.add_alias("desk", DESK)
        config.add_group("office", ["desk", SHELF, LAMP])

    def test_group_command_uses_each_members_own_daemon(self) -> None:
        daemons = FakeDaemons()

        result, output, error = self.run_cli(["off", "--light", "office"], daemons)

        self.assertEqual(result, 0)
        self.assertEqual(error, "")
        self.assertEqual(
            daemons.sends(),
            [
                ("send", address, COMMAND_ON_OFF, off_parameters().hex(), socket_path_for_address(address))
                for address in (DESK, SHELF, LAMP)
            ],
        )
        for address in (DESK, SHELF, LAMP):
            self.assertIn(f"off command sent to {address}", output)

    def test_group_script_sends_each_step_to_every_member_before_waiting(self) -> None:
        script = self.root / "scene.blemesh"
        script.write_text("on\nrepeat 2:\n  color ff0000\n  wait 1s\n", encoding="utf-8")
        daemons = FakeDaemons()

        result, _, _ = self.run_cli(["script", str(script), "--light", "office"], daemons)

        self.assertEqual(result, 0)
        steps = [(event[2], event[3]) if event[0] == "send" else event for event in daemons.events]
        on, red = (COMMAND_ON_OFF, on_parameters().hex()), (COMMAND_RGB, rgb_parameters(255, 0, 0, 100).hex())
        self.assertEqual(steps, [on] * 3 + [red] * 3 + [("wait", 1.0)] + [red] * 3 + [("wait", 1.0)])
        for index in range(0, 9, 3):
            self.assertEqual({event[1] for event in daemons.sends()[index : index + 3]}, {DESK, SHELF, LAMP})

    def test_partial_failure_skips_the_failed_member_and_exits_nonzero(self) -> None:
        script = self.root / "scene.blemesh"
        script.write_text("on\nwait 1s\ncolor ff0000\noff\n", encoding="utf-8")
        daemons = FakeDaemons({SHELF: DaemonError("Bluetooth connection dropped; command outcome is unknown")})

        result, output, error = self.run_cli(["script", str(script), "--light", "office"], daemons)

        self.assertEqual(result, 1)
        self.assertEqual(len(daemons.sends(SHELF)), 1)
        self.assertEqual(len(daemons.sends(DESK)), 3)
        self.assertEqual(len(daemons.sends(LAMP)), 3)
        self.assertNotIn(f"sent to {SHELF}", output)
        self.assertEqual(output.count(f"sent to {DESK}"), 3)
        self.assertIn(f"blemeshctl: error: {SHELF}: Bluetooth connection dropped", error)
        self.assertIn(f"1 of 3 lights in group office failed: {SHELF}", error)

    def test_unexpected_member_exceptions_do_not_stop_other_members(self) -> None:
        daemons = FakeDaemons({DESK: TimeoutError()})

        result, output, error = self.run_cli(["on", "--light", "office"], daemons)

        self.assertEqual(result, 1)
        self.assertIn(f"{DESK}: TimeoutError", error)
        self.assertIn(f"on command sent to {SHELF}", output)
        self.assertIn(f"on command sent to {LAMP}", output)

    def test_endless_group_program_stops_once_every_member_failed(self) -> None:
        script = self.root / "loop.blemesh"
        script.write_text("loop:\n  on\n  wait 1s\n", encoding="utf-8")
        failure = DaemonError("not advertising", retryable=True)
        daemons = FakeDaemons({DESK: failure, SHELF: failure, LAMP: failure})

        result, output, error = self.run_cli(["script", str(script), "--light", "office"], daemons)

        self.assertEqual(result, 1)
        self.assertEqual(len(daemons.sends()), 3)
        daemons.sleep.assert_not_awaited()
        self.assertNotIn("sent", output)
        self.assertIn("3 of 3 lights in group office failed", error)

    def test_group_waits_for_connection_only_when_asked_explicitly(self) -> None:
        script = self.root / "scene.blemesh"
        script.write_text("on\n", encoding="utf-8")

        # Scripts normally wait, but a group does not unless asked.
        daemons = FakeDaemons({LAMP: DaemonError("not advertising", retryable=True)})
        result, output, _ = self.run_cli(["script", str(script), "--light", "office"], daemons)
        self.assertEqual(result, 1)
        self.assertEqual(len(daemons.sends(LAMP)), 1)
        daemons.sleep.assert_not_awaited()
        self.assertNotIn("Waiting for", output)

        daemons = FakeDaemons({LAMP: DaemonError("not advertising", retryable=True)})
        original = daemons._request

        async def recover(payload: dict[str, Any], **kwargs: Any) -> dict[str, Any]:
            if len(daemons.sends(LAMP)) == 1 and payload["address"] == LAMP:
                daemons.failures.clear()
            return await original(payload, **kwargs)

        daemons.request = AsyncMock(side_effect=recover)
        result, output, _ = self.run_cli(
            ["script", str(script), "--light", "office", "--wait-for-connection"], daemons
        )
        self.assertEqual(result, 0)
        self.assertEqual(len(daemons.sends(LAMP)), 2)
        self.assertIn(f"Waiting for {LAMP}", output)

    def test_group_with_one_member_still_uses_group_semantics(self) -> None:
        config.add_group("solo", [SHELF])
        daemons = FakeDaemons({SHELF: DaemonError("not advertising", retryable=True)})

        result, _, error = self.run_cli(["on", "--light", "solo"], daemons)

        self.assertEqual(result, 1)
        self.assertIn("1 of 1 lights in group solo failed", error)

    def test_invalid_group_command_makes_no_daemon_request(self) -> None:
        daemons = FakeDaemons()

        with self.assertRaises(ValueError):
            self.run_cli(["color", "00ff00", "--brightness", "0", "--light", "office"], daemons)
        daemons.request.assert_not_awaited()


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

    def test_scene_apply_to_a_group_fans_out(self) -> None:
        config.add_group("office", [DESK, SHELF])
        self.run_cli(["scene", "save", "evening", "--from-file", "-"], stdin="on\nwait 2s\noff\n")
        daemons = FakeDaemons({DESK: DaemonError("not advertising", retryable=True)})

        result, output, error = self.run_cli(["scene", "apply", "evening", "--light", "office"], daemons)

        self.assertEqual(result, 1)
        self.assertEqual(len(daemons.sends(DESK)), 1)
        self.assertEqual(
            [event[3] for event in daemons.sends(SHELF)], [on_parameters().hex(), off_parameters().hex()]
        )
        self.assertIn("scene evening:2: waiting 2s", output)
        self.assertIn(f"scene evening:3: off command sent to {SHELF}", output)
        self.assertIn(f"1 of 2 lights in group office failed: {DESK}", error)


if __name__ == "__main__":
    unittest.main()
