from __future__ import annotations

import asyncio
import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import AsyncMock, patch

from blemeshctl.cli import build_parser, run
from blemeshctl.daemon import DaemonError
from blemeshctl.protocol import COMMAND_ON_OFF, COMMAND_RGB, on_parameters, rgb_parameters
from blemeshctl.script import (
    RAINBOW_COLORS,
    Delay,
    LightCommand,
    Loop,
    Rainbow,
    ScriptError,
    execute_script,
    load_script,
    parse_script,
)


ADDRESS = "A4:C1:38:93:1B:72"


class ScriptParserTests(unittest.TestCase):
    def test_shipped_rgb_cycle_is_a_valid_endless_loop(self) -> None:
        path = Path(__file__).parents[1] / "examples" / "rgb-cycle.blemesh"
        program = load_script(path)

        self.assertEqual(len(program), 2)
        self.assertIsInstance(program[0], LightCommand)
        self.assertEqual(program[0].parameters, on_parameters())
        self.assertIsInstance(program[1], Loop)
        loop = program[1]
        self.assertIsNone(loop.count)
        self.assertEqual(
            [step.parameters for step in loop.body if isinstance(step, LightCommand)],
            [
                rgb_parameters(255, 0, 0, 100),
                rgb_parameters(0, 255, 0, 100),
                rgb_parameters(0, 0, 255, 100),
            ],
        )
        self.assertEqual([step.seconds for step in loop.body if isinstance(step, Delay)], [1.0, 1.0, 1.0])

    def test_shipped_rainbow_is_saturated_and_contains_no_white(self) -> None:
        path = Path(__file__).parents[1] / "examples" / "rainbow.blemesh"
        program = load_script(path)

        self.assertEqual(len(program), 2)
        self.assertIsInstance(program[1], Loop)
        self.assertEqual(program[1].body, (Rainbow(10, 0.1),))
        self.assertEqual(len(RAINBOW_COLORS), 1530)
        self.assertEqual(len(set(RAINBOW_COLORS)), 1530)
        self.assertEqual(RAINBOW_COLORS[0], (255, 0, 0))
        self.assertEqual(RAINBOW_COLORS[-1], (255, 0, 1))
        self.assertTrue(all(max(color) == 255 and min(color) == 0 for color in RAINBOW_COLORS))
        self.assertNotIn((255, 255, 255), RAINBOW_COLORS)

    def test_accepts_comments_crlf_hash_colours_and_finite_repeat(self) -> None:
        program = parse_script(
            "# A comment\r\non\r\nrepeat 2:\r\n  color #00ff00 65\r\n  wait 250ms",
            "scene.blemesh",
        )

        self.assertEqual(len(program), 2)
        self.assertIsInstance(program[1], Loop)
        repeat = program[1]
        self.assertEqual(repeat.count, 2)
        self.assertEqual(repeat.body[0].parameters, rgb_parameters(0, 255, 0, 65))
        self.assertEqual(repeat.body[1], Delay(5, 0.25))

    def test_rejects_invalid_scripts_with_a_source_line(self) -> None:
        cases = {
            "color ff0000\nunknown": "unknown instruction",
            "color ff0000\n  wait 1s": "unexpected indentation",
            "loop:\n  color ff0000": "endless loop needs at least one wait",
            "repeat 0:\n  color ff0000": "repeat count must be a positive integer",
            "color gg0000": "only hexadecimal digits",
            "wait 0": "wait must be a positive, finite duration",
            "rainbow 0": "wait must be a positive, finite duration",
        }
        for text, expected in cases.items():
            with self.subTest(text=text), self.assertRaisesRegex(
                ScriptError, rf"bad\.blemesh(?::\d+)?: .*{expected}"
            ):
                parse_script(text, "bad.blemesh")


class ScriptExecutorTests(unittest.TestCase):
    def test_execute_rainbow_visits_every_saturated_hue(self) -> None:
        program = parse_script("rainbow 100ms", "rainbow.blemesh")
        sent: list[LightCommand] = []
        waited: list[Delay] = []

        async def send(command: LightCommand) -> None:
            sent.append(command)

        async def wait(delay: Delay) -> None:
            waited.append(delay)

        asyncio.run(execute_script(program, send_command=send, wait=wait))

        self.assertEqual(len(sent), 1530)
        self.assertEqual([command.parameters for command in sent], [
            rgb_parameters(red, green, blue, 100) for red, green, blue in RAINBOW_COLORS
        ])
        self.assertTrue(sent[0].report)
        self.assertTrue(all(not command.report for command in sent[1:]))
        self.assertEqual(waited, [Delay(1, 0.1, report=False)] * 1530)

    def test_execute_script_runs_a_finite_repeat_in_order(self) -> None:
        program = parse_script(
            "repeat 2:\n  color ff0000\n  wait 0.25\n  color 0000ff",
            "repeat.blemesh",
        )
        sent: list[bytes] = []
        waited: list[float] = []

        async def send(command: LightCommand) -> None:
            sent.append(command.parameters)

        async def wait(delay: Delay) -> None:
            waited.append(delay.seconds)

        asyncio.run(execute_script(program, send_command=send, wait=wait))

        self.assertEqual(
            sent,
            [
                rgb_parameters(255, 0, 0, 100),
                rgb_parameters(0, 0, 255, 100),
                rgb_parameters(255, 0, 0, 100),
                rgb_parameters(0, 0, 255, 100),
            ],
        )
        self.assertEqual(waited, [0.25, 0.25])

    def test_execute_script_does_not_retry_after_a_send_failure(self) -> None:
        program = parse_script("color ff0000\nwait 1s\ncolor 0000ff", "failure.blemesh")
        sent: list[bytes] = []

        async def send(command: LightCommand) -> None:
            sent.append(command.parameters)
            raise RuntimeError("connection dropped")

        async def wait(delay: Delay) -> None:
            self.fail(f"unexpected wait: {delay}")

        with self.assertRaisesRegex(RuntimeError, "connection dropped"):
            asyncio.run(execute_script(program, send_command=send, wait=wait))

        self.assertEqual(sent, [rgb_parameters(255, 0, 0, 100)])


class ScriptCliTests(unittest.TestCase):
    def test_scripts_wait_by_default_but_regular_commands_are_fail_fast(self) -> None:
        script_arguments = build_parser().parse_args(["script", "scene.blemesh"])
        command_arguments = build_parser().parse_args(["on"])

        self.assertTrue(script_arguments.wait_for_connection)
        self.assertFalse(command_arguments.wait_for_connection)

    def test_wait_for_connection_retries_a_missing_light(self) -> None:
        arguments = build_parser().parse_args(
            ["color", "00ff00", "--address", ADDRESS, "--wait-for-connection"]
        )
        socket_path = Path("/tmp/blemeshctl-wait-test.sock")
        request = AsyncMock(
            side_effect=[
                DaemonError(
                    f"{ADDRESS} was not advertising as a compatible Telink light", retryable=True
                ),
                {"address": ADDRESS, "mesh_address": 0x67, "reused": False, "idle_timeout": 60.0},
            ]
        )
        sleep = AsyncMock()
        output = io.StringIO()
        with (
            patch("blemeshctl.cli.socket_path_for_address", return_value=socket_path),
            patch("blemeshctl.cli.request_daemon", new=request),
            patch("blemeshctl.cli.asyncio.sleep", new=sleep),
            redirect_stdout(output),
        ):
            result = asyncio.run(run(arguments))

        self.assertEqual(result, 0)
        self.assertEqual(request.await_count, 2)
        sleep.assert_awaited_once_with(1.0)
        self.assertIn("Waiting for A4:C1:38:93:1B:72", output.getvalue())

    def test_wait_for_connection_does_not_repeat_unknown_command_outcome(self) -> None:
        arguments = build_parser().parse_args(
            ["color", "00ff00", "--address", ADDRESS, "--wait-for-connection"]
        )
        request = AsyncMock(
            side_effect=DaemonError("Bluetooth connection dropped; command outcome is unknown, retry manually")
        )
        sleep = AsyncMock()
        with (
            patch("blemeshctl.cli.request_daemon", new=request),
            patch("blemeshctl.cli.asyncio.sleep", new=sleep),
        ):
            with self.assertRaisesRegex(DaemonError, "command outcome is unknown"):
                asyncio.run(run(arguments))

        request.assert_awaited_once()
        sleep.assert_not_awaited()

    def test_regular_color_command_keeps_accepting_hash_rgb(self) -> None:
        arguments = build_parser().parse_args(["color", "#00ff00", "--address", ADDRESS])
        socket_path = Path("/tmp/blemeshctl-color-test.sock")
        request = AsyncMock(
            return_value={"address": ADDRESS, "mesh_address": 0x67, "reused": False, "idle_timeout": 60.0}
        )
        with (
            patch("blemeshctl.cli.socket_path_for_address", return_value=socket_path),
            patch("blemeshctl.cli.request_daemon", new=request),
            redirect_stdout(io.StringIO()),
        ):
            result = asyncio.run(run(arguments))

        self.assertEqual(result, 0)
        self.assertEqual(request.await_args.args[0]["opcode"], COMMAND_RGB)
        self.assertEqual(
            request.await_args.args[0]["parameters"], rgb_parameters(0, 255, 0, 100).hex()
        )

    def test_script_sends_commands_sequentially_to_the_same_daemon(self) -> None:
        content = """on
repeat 1:
  color ff0000
  wait 0.25
  color 00ff00
  wait 0.25
  color 0000ff
"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "scene.blemesh"
            path.write_text(content, encoding="utf-8")
            arguments = build_parser().parse_args(["script", str(path), "--address", ADDRESS])
            socket_path = Path("/tmp/blemeshctl-script-test.sock")
            request = AsyncMock(
                side_effect=[
                    {"address": ADDRESS, "mesh_address": 0x67, "reused": False, "idle_timeout": 60.0},
                    {"address": ADDRESS, "mesh_address": 0x67, "reused": True, "idle_timeout": 60.0},
                    {"address": ADDRESS, "mesh_address": 0x67, "reused": True, "idle_timeout": 60.0},
                    {"address": ADDRESS, "mesh_address": 0x67, "reused": True, "idle_timeout": 60.0},
                ]
            )
            sleep = AsyncMock()
            with (
                patch("blemeshctl.cli.socket_path_for_address", return_value=socket_path),
                patch("blemeshctl.cli.request_daemon", new=request),
                patch("blemeshctl.cli.asyncio.sleep", new=sleep),
                redirect_stdout(io.StringIO()),
            ):
                result = asyncio.run(run(arguments))

        self.assertEqual(result, 0)
        self.assertEqual(
            [call.args[0]["opcode"] for call in request.await_args_list],
            [COMMAND_ON_OFF, COMMAND_RGB, COMMAND_RGB, COMMAND_RGB],
        )
        self.assertEqual(
            [call.args[0]["parameters"] for call in request.await_args_list],
            [
                on_parameters().hex(),
                rgb_parameters(255, 0, 0, 100).hex(),
                rgb_parameters(0, 255, 0, 100).hex(),
                rgb_parameters(0, 0, 255, 100).hex(),
            ],
        )
        for call in request.await_args_list:
            payload = call.args[0]
            self.assertEqual(payload["address"], ADDRESS)
            self.assertEqual(payload["mesh_name"], "BleMesh")
            self.assertEqual(payload["password"], "mesh123")
            self.assertEqual(call.kwargs["socket_path"], socket_path)
        self.assertEqual([call.args[0] for call in sleep.await_args_list], [0.25, 0.25])

    def test_invalid_script_makes_no_daemon_request(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "broken.blemesh"
            path.write_text("color ff0000\nnot-a-command\n", encoding="utf-8")
            arguments = build_parser().parse_args(["script", str(path), "--address", ADDRESS])
            request = AsyncMock()
            with patch("blemeshctl.cli.request_daemon", new=request):
                with self.assertRaisesRegex(ScriptError, r"broken\.blemesh:2: unknown instruction"):
                    asyncio.run(run(arguments))

        request.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
