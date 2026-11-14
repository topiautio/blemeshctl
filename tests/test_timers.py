from __future__ import annotations

import argparse
import asyncio
import io
import json
import os
import signal
import stat
import sys
import tempfile
import time
import unittest
from datetime import datetime
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

from blemeshctl import cli, config, timers
from blemeshctl.cli import build_parser, run
from blemeshctl.daemon import DaemonError, socket_path_for_address
from blemeshctl.protocol import COMMAND_ON_OFF, off_parameters
from blemeshctl.script import LightCommand, parse_script

DESK = "A4:C1:38:93:1B:72"
SHELF = "00:11:22:33:44:55"
SECRET_MESH = "secret-mesh-name"
SECRET_PASSWORD = "hunter2-password"
CREDENTIALS = {"mesh_name": SECRET_MESH, "password": SECRET_PASSWORD}


class FakeClock:
    """Deterministic wall clock whose sleeps advance it, optionally with a suspend jump."""

    def __init__(self, now: float = 1000.0) -> None:
        self.now = now
        self.sleeps: list[float] = []
        self.suspend_after_sleep: dict[int, float] = {}

    def time(self) -> float:
        return self.now

    async def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds + self.suspend_after_sleep.get(len(self.sleeps), 0.0)

    def sync_sleep(self, seconds: float) -> None:
        self.now += seconds


class FakeBackend:
    """Record deliveries and fail the calls chosen by 1-based index."""

    def __init__(self, failing: dict[int, Exception] | None = None, *, fail_off: bool = False) -> None:
        self.failing = failing or {}
        self.fail_off = fail_off
        self.calls: list[tuple[int, bytes, str, float]] = []

    async def send(self, opcode: int, parameters: bytes, description: str, keepalive: float) -> None:
        self.calls.append((opcode, parameters, description, keepalive))
        if opcode == COMMAND_ON_OFF and parameters == off_parameters() and self.fail_off:
            raise timers.StepError("off failed")
        failure = self.failing.get(len(self.calls))
        if failure is not None:
            raise failure

    def descriptions(self) -> list[str]:
        return [call[2] for call in self.calls]


class TimerTestCase(unittest.TestCase):
    def setUp(self) -> None:
        super().setUp()
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        environment = patch.dict(
            os.environ,
            {"XDG_CONFIG_HOME": str(self.root / "config"), "XDG_RUNTIME_DIR": str(self.root / "run")},
        )
        environment.start()
        self.addCleanup(environment.stop)

    def make_record(
        self, program: str, *, fire_at: float = 1100.0, state: str = timers.STATE_SCHEDULED, **fields: Any
    ) -> Path:
        record = timers._new_record(
            timer_id="abc123",
            now=1000.0,
            delay_seconds=0,
            description="test",
            target="desk",
            addresses=[DESK],
            is_group=False,
            connection={"scan_timeout": 1.0, "connect_timeout": 1.0},
            program_text=program,
        )
        record.update(fire_at=fire_at, state=state, **fields)
        path = timers.record_path("abc123")
        timers.write_record(path, record)
        return path

    def run_timer(
        self, path: Path, backend: FakeBackend, clock: FakeClock | None = None
    ) -> tuple[str, FakeClock]:
        clock = clock or FakeClock()
        state = asyncio.run(
            timers.run_timer(path, CREDENTIALS, lambda record, credentials: backend, clock=clock.time, sleep=clock.sleep)
        )
        return state, clock


class DurationTests(unittest.TestCase):
    def test_valid_durations(self) -> None:
        for text, seconds in (
            ("30s", 30),
            ("90s", 90),
            ("20m", 1200),
            ("2h", 7200),
            ("1h30m", 5400),
            ("1m30s", 90),
            (" 24h ", 86400),
            ("1S", 1),
        ):
            with self.subTest(text=text):
                self.assertEqual(timers.parse_duration(text), seconds)

    def test_invalid_and_out_of_range_durations(self) -> None:
        for text in ("", "0s", "0", "90", "1.5h", "-5m", "5x", "1m1h", "h", "24h1s", "25h", "1d"):
            with self.subTest(text=text), self.assertRaises(timers.TimerError):
                timers.parse_duration(text)

    def test_error_messages_state_the_bounds(self) -> None:
        with self.assertRaisesRegex(timers.TimerError, "minimum is 1s"):
            timers.parse_duration("0s")
        with self.assertRaisesRegex(timers.TimerError, "maximum is 24h"):
            timers.parse_duration("25h")

    def test_argparse_type_rejects_bad_durations(self) -> None:
        with redirect_stderr(io.StringIO()) as stderr, self.assertRaises(SystemExit):
            build_parser().parse_args(["off", "--in", "soon"])
        self.assertIn("invalid duration", stderr.getvalue())

    def test_format_duration(self) -> None:
        self.assertEqual(timers.format_duration(5400), "1h30m")
        self.assertEqual(timers.format_duration(1200), "20m")
        self.assertEqual(timers.format_duration(45), "45s")
        self.assertEqual(timers.format_duration(89.2), "1m30s")


class FadeTests(unittest.TestCase):
    def levels(self, text: str) -> list[int]:
        return [int(line.split()[-1]) for line in text.splitlines() if line.startswith(("color", "white"))]

    def test_default_fade_dims_to_one_then_off_with_one_step_per_level(self) -> None:
        text = timers.fade_program_text(color="ff8800", white=None, start=100, end=None, over_seconds=1200)
        levels = self.levels(text)
        self.assertEqual(levels, list(range(100, 0, -1)))
        self.assertEqual(text.splitlines()[-1], "off")
        self.assertEqual(text.count("wait 12s"), 100)
        program = parse_script(text, "fade")
        self.assertEqual(sum(isinstance(step, LightCommand) for step in program), 101)

    def test_short_fade_has_at_most_one_step_per_two_seconds(self) -> None:
        text = timers.fade_program_text(color="ff8800", white=None, start=100, end=None, over_seconds=20)
        self.assertEqual(len(self.levels(text)), 10)
        waits = [float(line.split()[1].rstrip("s")) for line in text.splitlines() if line.startswith("wait")]
        self.assertTrue(all(wait >= timers.MIN_FADE_STEP_SECONDS for wait in waits))
        self.assertAlmostEqual(sum(waits), 20.0)

    def test_brightness_is_monotonic_with_exact_endpoints(self) -> None:
        text = timers.fade_program_text(color="00ff00", white=None, start=80, end=20, over_seconds=60)
        levels = self.levels(text)
        self.assertEqual((levels[0], levels[-1]), (80, 20))
        self.assertEqual(levels, sorted(levels, reverse=True))
        self.assertNotIn("off", text.splitlines())
        # Brightening works the same way.
        up = self.levels(timers.fade_program_text(color="00ff00", white=None, start=10, end=90, over_seconds=60))
        self.assertEqual(up, sorted(up))
        self.assertEqual((up[0], up[-1]), (10, 90))

    def test_white_fade_uses_the_white_instruction(self) -> None:
        text = timers.fade_program_text(color=None, white=2700, start=50, end=None, over_seconds=100)
        self.assertIn("white 2700K 50", text)
        self.assertNotIn("color", text.replace("# fade", ""))
        parse_script(text, "fade")

    def test_one_step_edge_cases(self) -> None:
        text = timers.fade_program_text(color="ff0000", white=None, start=100, end=None, over_seconds=1)
        self.assertEqual(text.splitlines()[1:], ["color ff0000 1", "wait 1s", "off"])
        same = timers.fade_program_text(color="ff0000", white=None, start=40, end=40, over_seconds=60)
        self.assertEqual(self.levels(same), [40])
        parse_script(same, "fade")

    def test_missing_colour_explains_that_lights_cannot_report_state(self) -> None:
        with self.assertRaisesRegex(timers.TimerError, "cannot report their current state"):
            timers.fade_program_text(color=None, white=None, start=100, end=None, over_seconds=60)

    def test_invalid_brightness_and_colour_are_rejected(self) -> None:
        for kwargs in ({"start": 0}, {"start": 101}, {"end": 0}, {"end": 101}):
            with self.subTest(kwargs=kwargs), self.assertRaises(timers.TimerError):
                timers.fade_program_text(
                    **{"color": "ff0000", "white": None, "start": 100, "end": None, "over_seconds": 60, **kwargs}
                )
        with self.assertRaises(Exception):
            parse_script(
                timers.fade_program_text(color="zzzzzz", white=None, start=100, end=None, over_seconds=60), "fade"
            )

    def test_description(self) -> None:
        self.assertEqual(
            timers.fade_description(color="FF8800", white=None, start=100, end=None, over_seconds=1200),
            "fade ff8800 100->off over 20m",
        )


class RecordTests(TimerTestCase):
    def test_record_files_are_private_and_hold_no_credentials(self) -> None:
        popen = MagicMock()
        with (
            patch("blemeshctl.timers.subprocess.Popen", return_value=popen) as spawn,
            patch("blemeshctl.timers.RUNNER_START_TIMEOUT", 0.0),
            redirect_stderr(io.StringIO()),
        ):
            record = timers.schedule_timer(
                program_text="off\n",
                description="off",
                target="desk",
                addresses=[DESK],
                is_group=False,
                delay_seconds=60,
                connection={"scan_timeout": 1.0, "connect_timeout": 2.0},
                credentials=CREDENTIALS,
                clock=lambda: 1000.0,
                sleep=lambda seconds: None,
            )
        path = timers.record_path(record["id"])
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(path.parent.stat().st_mode), 0o700)
        text = path.read_text(encoding="utf-8")
        self.assertNotIn(SECRET_MESH, text)
        self.assertNotIn(SECRET_PASSWORD, text)
        saved = json.loads(text)
        self.assertEqual(saved["state"], "scheduled")
        self.assertEqual(saved["program"], "off\n")
        self.assertEqual(saved["fire_at"], 1060.0)
        argv = spawn.call_args.args[0]
        self.assertEqual(argv[-1], str(path))
        self.assertNotIn(SECRET_MESH, " ".join(argv))
        self.assertNotIn(SECRET_PASSWORD, " ".join(argv))
        self.assertTrue(spawn.call_args.kwargs["start_new_session"])
        self.assertEqual(spawn.call_args.kwargs["stdout"], -3)  # subprocess.DEVNULL
        written = popen.stdin.write.call_args.args[0]
        self.assertEqual(json.loads(written), CREDENTIALS)
        popen.stdin.close.assert_called_once_with()

    def test_frozen_runner_uses_the_internal_flag_not_python_dash_m(self) -> None:
        with (
            patch("blemeshctl.timers.subprocess.Popen", return_value=MagicMock()) as spawn,
            patch.object(sys, "frozen", True, create=True),
            patch.object(sys, "executable", "/opt/blemeshctl"),
        ):
            timers.spawn_runner(Path("/x/abc123.json"), CREDENTIALS)
        self.assertEqual(spawn.call_args.args[0], ["/opt/blemeshctl", "--internal-timer", "/x/abc123.json"])
        self.assertEqual(spawn.call_args.kwargs["env"]["PYINSTALLER_RESET_ENVIRONMENT"], "1")

    def test_write_is_atomic_and_leaves_no_temporary_files(self) -> None:
        path = self.make_record("off\n")
        timers.update_record(path, state="running")
        self.assertEqual(timers.read_record(path)["state"], "running")
        self.assertEqual([item.name for item in path.parent.iterdir()], ["abc123.json"])

    def test_ids_cannot_escape_the_directory(self) -> None:
        with self.assertRaises(timers.TimerError):
            timers.record_path("../evil")


class RunnerTests(TimerTestCase):
    PROGRAM = "on\nwait 1s\ncolor ff0000 50\nwait 1s\noff\n"

    def test_waits_in_chunks_then_fires_at_fire_at(self) -> None:
        path = self.make_record("off\n", fire_at=1100.0)
        backend = FakeBackend()
        state, clock = self.run_timer(path, backend)
        self.assertEqual(state, "done")
        self.assertEqual(clock.sleeps, [30.0, 30.0, 30.0, 10.0])
        self.assertEqual(len(backend.calls), 1)
        record = timers.read_record(path)
        self.assertEqual(record["state"], "done")
        self.assertEqual(record["outcome"], "1 of 1 steps acknowledged")
        self.assertEqual(record["steps"]["ok"], 1)

    def test_nothing_is_sent_before_the_fire_time(self) -> None:
        path = self.make_record("off\n", fire_at=1100.0)
        backend = FakeBackend()
        clock = FakeClock()
        original = clock.sleep

        async def checking_sleep(seconds: float) -> None:
            self.assertEqual(backend.calls, [])
            await original(seconds)

        asyncio.run(
            timers.run_timer(path, CREDENTIALS, lambda r, c: backend, clock=clock.time, sleep=checking_sleep)
        )
        self.assertEqual(len(backend.calls), 1)

    def test_suspend_jump_fires_immediately_after_waking(self) -> None:
        path = self.make_record("off\n", fire_at=1000.0 + 3600)
        clock = FakeClock()
        clock.suspend_after_sleep[1] = 4000.0  # laptop slept far past the fire time
        backend = FakeBackend()
        state, clock = self.run_timer(path, backend, clock)
        self.assertEqual(clock.sleeps, [30.0])
        self.assertEqual(state, "done")
        self.assertIn("after the scheduled time", timers.read_record(path)["outcome"])

    def test_failed_step_is_skipped_and_later_steps_and_final_off_are_attempted(self) -> None:
        path = self.make_record(self.PROGRAM, fire_at=1000.0)
        backend = FakeBackend({2: timers.StepError("write failed")})
        state, clock = self.run_timer(path, backend)
        self.assertEqual(state, "partial")
        self.assertEqual(len(backend.calls), 3)
        self.assertEqual(backend.calls[-1][2], "off")
        record = timers.read_record(path)
        self.assertEqual(record["steps"], {"total": 3, "executed": 3, "ok": 2, "partial": 0, "failed": 1})
        self.assertIn("write failed", record["first_error"])
        self.assertIn("2 of 3 steps acknowledged, 1 failed", record["outcome"])
        self.assertEqual(clock.sleeps, [1.0, 1.0])  # the failed step was not retried

    def test_final_off_is_retried_three_times_with_spacing(self) -> None:
        path = self.make_record(self.PROGRAM, fire_at=1000.0)
        backend = FakeBackend(fail_off=True)
        state, clock = self.run_timer(path, backend)
        self.assertEqual(backend.descriptions().count("off"), 1 + timers.OFF_RETRIES)
        self.assertEqual(clock.sleeps, [1.0, 1.0, 5.0, 5.0, 5.0])
        self.assertEqual(state, "partial")
        self.assertIn("off failed", timers.read_record(path)["last_error"])

    def test_final_off_that_succeeds_on_retry_counts_as_acknowledged(self) -> None:
        path = self.make_record("on\noff\n", fire_at=1000.0)

        class Flaky(FakeBackend):
            async def send(self, opcode: int, parameters: bytes, description: str, keepalive: float) -> None:
                await super().send(opcode, parameters, description, keepalive)
                if description == "off" and self.descriptions().count("off") < 3:
                    raise timers.StepError("busy")

        backend = Flaky()
        state, _ = self.run_timer(path, backend)
        self.assertEqual(state, "done")
        self.assertEqual(backend.descriptions().count("off"), 3)

    def test_non_final_steps_are_never_retried(self) -> None:
        path = self.make_record("off\nwait 1s\non\n", fire_at=1000.0)
        backend = FakeBackend(fail_off=True)
        self.run_timer(path, backend)
        self.assertEqual(backend.descriptions(), ["off", "on"])

    def test_nothing_delivered_is_failed(self) -> None:
        path = self.make_record("on\nwait 1s\non\n", fire_at=1000.0)
        backend = FakeBackend({1: timers.StepError("a"), 2: RuntimeError("b")})
        state, _ = self.run_timer(path, backend)
        self.assertEqual(state, "failed")
        record = timers.read_record(path)
        self.assertIn("nothing was delivered", record["outcome"])
        self.assertIn("a", record["first_error"])
        self.assertIn("b", record["last_error"])

    def test_partially_delivered_group_steps_make_the_run_partial_not_done(self) -> None:
        path = self.make_record("on\n", fire_at=1000.0)
        backend = FakeBackend({1: timers.StepError("not delivered to X", partial=True)})
        state, _ = self.run_timer(path, backend)
        self.assertEqual(state, "partial")
        self.assertEqual(timers.read_record(path)["steps"]["partial"], 1)

    def test_all_steps_acknowledged_is_done(self) -> None:
        path = self.make_record(self.PROGRAM, fire_at=1000.0)
        state, _ = self.run_timer(path, FakeBackend())
        self.assertEqual(state, "done")

    def test_keepalive_is_short_for_the_last_request_and_long_for_earlier_ones(self) -> None:
        path = self.make_record("on\nwait 30s\noff\n", fire_at=1000.0)
        backend = FakeBackend()
        self.run_timer(path, backend)
        self.assertEqual([call[3] for call in backend.calls], [60.0, timers.FINAL_KEEPALIVE_SECONDS])
        path = self.make_record("on\nwait 1s\noff\n", fire_at=1000.0)
        backend = FakeBackend()
        self.run_timer(path, backend)
        self.assertEqual(backend.calls[0][3], timers.MIN_KEEPALIVE_SECONDS)

    def test_long_waits_release_the_light_between_steps(self) -> None:
        path = self.make_record("on\nwait 600s\noff\n", fire_at=1000.0)
        backend = FakeBackend()
        self.run_timer(path, backend)
        self.assertEqual(backend.calls[0][3], timers.MIN_KEEPALIVE_SECONDS)

    def test_program_that_cannot_start_is_failed(self) -> None:
        path = self.make_record("bogus\n", fire_at=1000.0)
        state, _ = self.run_timer(path, FakeBackend())
        self.assertEqual(state, "failed")
        self.assertIn("runner error", timers.read_record(path)["outcome"])

    def test_cancel_during_sleep_marks_cancelled_and_sends_nothing(self) -> None:
        path = self.make_record("off\n", fire_at=10**12)
        backend = FakeBackend()

        async def scenario() -> None:
            task = asyncio.create_task(
                timers.run_timer(path, CREDENTIALS, lambda r, c: backend, clock=lambda: 1000.0)
            )
            for _ in range(5):
                await asyncio.sleep(0)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task

        asyncio.run(scenario())
        self.assertEqual(backend.calls, [])
        self.assertEqual(timers.read_record(path)["state"], "cancelled")
        self.assertEqual(timers.read_record(path)["outcome"], "cancelled before it fired")

    def test_cancelled_record_is_not_run(self) -> None:
        path = self.make_record("off\n", fire_at=1000.0, state="cancelled")
        backend = FakeBackend()
        state, _ = self.run_timer(path, backend)
        self.assertEqual(state, "cancelled")
        self.assertEqual(backend.calls, [])

    def test_main_rejects_missing_credentials_without_running(self) -> None:
        path = self.make_record("off\n", fire_at=1000.0)
        with patch.object(sys, "argv", ["blemeshctl", str(path)]), patch.object(sys, "stdin", io.StringIO("")):
            timers.main()
        self.assertEqual(timers.read_record(path)["state"], "failed")

    def test_main_records_its_own_pid_then_runs(self) -> None:
        path = self.make_record("off\n", fire_at=1000.0)
        with (
            patch.object(sys, "argv", ["blemeshctl", str(path)]),
            patch.object(sys, "stdin", io.StringIO(json.dumps(CREDENTIALS))),
            patch("blemeshctl.cli.timer_backend", lambda record, credentials: FakeBackend()),
        ):
            timers.main()
        record = timers.read_record(path)
        self.assertEqual(record["pid"], os.getpid())
        self.assertEqual(record["state"], "done")


class ListAndCancelTests(TimerTestCase):
    def test_stale_active_record_becomes_failed(self) -> None:
        path = self.make_record("off\n", state="running", pid=999999, pid_start=5)
        with patch("blemeshctl.timers.process_start_time", return_value=None):
            record = timers.refresh_record(path, timers.read_record(path))
        self.assertEqual(record["state"], "failed")
        self.assertEqual(record["outcome"], "runner exited unexpectedly")

    def test_reused_pid_is_not_mistaken_for_the_runner(self) -> None:
        path = self.make_record("off\n", pid=4242, pid_start=5)
        kill = MagicMock()
        with patch("blemeshctl.timers.process_start_time", return_value=999):
            reason = timers.cancel_timer(path, kill=kill)
        kill.assert_not_called()
        self.assertIn("already finished", reason or "")

    def test_unstarted_record_is_failed_only_after_a_grace_period(self) -> None:
        path = self.make_record("off\n")
        self.assertEqual(timers.refresh_record(path, timers.read_record(path), lambda: 1010.0)["state"], "scheduled")
        record = timers.refresh_record(path, timers.read_record(path), lambda: 1100.0)
        self.assertEqual((record["state"], record["outcome"]), ("failed", "runner never started"))

    def test_cancel_signals_the_verified_runner_and_marks_cancelled(self) -> None:
        path = self.make_record("off\n", pid=4242, pid_start=7)
        alive = {"value": True}

        def kill(pid: int, number: int) -> None:
            self.assertEqual((pid, number), (4242, signal.SIGTERM))
            alive["value"] = False

        with patch("blemeshctl.timers.process_start_time", side_effect=lambda pid: 7 if alive["value"] else None):
            reason = timers.cancel_timer(path, kill=kill, sleep=lambda seconds: None)
        self.assertIsNone(reason)
        record = timers.read_record(path)
        self.assertEqual(record["state"], "cancelled")
        self.assertEqual(record["outcome"], "cancelled before it fired")

    def test_cancelling_a_finished_timer_is_refused(self) -> None:
        path = self.make_record("off\n", state="done")
        self.assertIn("already finished", timers.cancel_timer(path) or "")

    def run_timers(self, argv: list[str], clock: float = 1000.0) -> tuple[int, str, str]:
        arguments = build_parser().parse_args(["timers", *argv])
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            result = timers.run_timers_command(arguments, lambda: clock)
        return result, stdout.getvalue(), stderr.getvalue()

    def test_list_shows_scheduled_running_and_finished_timers(self) -> None:
        self.make_record("off\n", fire_at=1000.0 + 1200, pid=4242, pid_start=7)
        other = timers._new_record(
            timer_id="def456",
            now=900.0,
            delay_seconds=50,
            description="fade ff8800 100->off over 20m",
            target="group-x",
            addresses=[DESK, SHELF],
            is_group=True,
            connection={},
            program_text="off\n",
        )
        other.update(state="done", outcome="3 of 3 steps acknowledged", finished_at=990.0)
        timers.write_record(timers.record_path("def456"), other)
        with patch("blemeshctl.timers.process_start_time", return_value=7):
            for action in ([], ["list"]):
                result, output, _ = self.run_timers(action)
                self.assertEqual(result, 0)
                self.assertIn("abc123  scheduled  in 20m (", output)
                self.assertIn("desk  test", output)
                self.assertIn("def456  done", output)
                self.assertIn("group-x  fade ff8800 100->off over 20m", output)
                self.assertIn("    3 of 3 steps acknowledged", output)

    def test_list_removes_finished_records_older_than_a_day(self) -> None:
        old = self.make_record("off\n", state="done", finished_at=1000.0 - timers.FINISHED_RETENTION_SECONDS - 1)
        result, output, _ = self.run_timers([])
        self.assertFalse(old.exists())
        self.assertIn("No timers.", output)

    def test_clear_removes_only_inactive_records(self) -> None:
        self.make_record("off\n", state="failed", finished_at=999.0, outcome="x")
        active = timers.record_path("aaaaaa")
        record = timers.read_record(timers.record_path("abc123"))
        timers.write_record(active, {**record, "id": "aaaaaa", "state": "scheduled", "pid": 4242, "pid_start": 7})
        with patch("blemeshctl.timers.process_start_time", return_value=7):
            _, output, _ = self.run_timers(["clear"])
        self.assertIn("Cleared 1 finished timer.", output)
        self.assertTrue(active.exists())
        self.assertFalse(timers.record_path("abc123").exists())

    def test_cancel_requires_an_id_or_all(self) -> None:
        for argv in (["cancel"], ["cancel", "abc123", "--all"]):
            with self.subTest(argv=argv), self.assertRaises(timers.TimerError):
                self.run_timers(argv)

    def test_cancel_all_cancels_every_active_timer(self) -> None:
        self.make_record("off\n", pid=4242, pid_start=7)
        alive = {"value": True}

        def kill(pid: int, number: int) -> None:
            alive["value"] = False

        with (
            patch("blemeshctl.timers.process_start_time", side_effect=lambda pid: 7 if alive["value"] else None),
            patch("blemeshctl.timers.os.kill", kill),
            patch("blemeshctl.timers.time.sleep", lambda seconds: None),
        ):
            result, output, _ = self.run_timers(["cancel", "--all"])
        self.assertEqual(result, 0)
        self.assertIn("Cancelled abc123.", output)
        self.assertEqual(timers.read_record(timers.record_path("abc123"))["state"], "cancelled")


class SchedulingTestCase(TimerTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.daemon = AsyncMock(return_value={"address": DESK, "mesh_address": 1, "reused": False, "idle_timeout": 60.0})

        def fake_spawn(path: Path, credentials: dict[str, str]) -> None:
            self.spawned.append((path, credentials))
            timers.update_record(path, pid=4242, pid_start=7)

        self.spawned: list[tuple[Path, dict[str, str]]] = []
        for target, replacement in (
            ("blemeshctl.timers.spawn_runner", fake_spawn),
            ("blemeshctl.cli.request_daemon", self.daemon),
        ):
            patcher = patch(target, replacement)
            patcher.start()
            self.addCleanup(patcher.stop)

    def run_cli(self, argv: list[str]) -> tuple[int, str, str]:
        arguments = build_parser().parse_args(argv)
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            result = asyncio.run(run(arguments))
        return result, stdout.getvalue(), stderr.getvalue()


class CliSchedulingTests(SchedulingTestCase):
    def test_in_schedules_without_connecting_and_prints_the_id(self) -> None:
        result, output, _ = self.run_cli(["off", "--address", DESK, "--in", "20m", "--password", SECRET_PASSWORD])
        self.assertEqual(result, 0)
        self.daemon.assert_not_called()
        [(path, credentials)] = self.spawned
        self.assertRegex(output, rf"^Scheduled {path.stem}: off at \d\d:\d\d:\d\d \(in 20m\)\n$")
        self.assertEqual(credentials["password"], SECRET_PASSWORD)
        record = timers.read_record(path)
        self.assertEqual((record["target"], record["addresses"], record["description"]), (DESK, [DESK], "off"))
        self.assertNotIn(SECRET_PASSWORD, path.read_text(encoding="utf-8"))

    def test_each_command_compiles_to_the_expected_program(self) -> None:
        config.add_alias("desk", DESK)
        script = self.root / "x.blemesh"
        script.write_text("on\nwait 1s\noff\n", encoding="utf-8")
        config.save_scene("night", "off\n")
        cases = {
            "on": (["on"], "on", "on\n"),
            "color": (["color", "FF8800", "--brightness", "40"], "color ff8800 40", "color ff8800 40\n"),
            "white": (["white", "--temperature", "2700"], "white 2700K 100", "white 2700K 100\n"),
            "script": (["script", str(script)], "script x.blemesh", script.read_text()),
            "scene": (["scene", "apply", "night"], "scene night", "off\n"),
        }
        for name, (argv, description, program) in cases.items():
            with self.subTest(name=name):
                result, output, _ = self.run_cli([*argv, "--light", "desk", "--in", "1h30m"])
                self.assertEqual(result, 0)
                record = timers.read_record(self.spawned[-1][0])
                self.assertEqual((record["description"], record["program"]), (description, program))
                self.assertEqual(record["target"], "desk")
                self.assertIn(f"{description} at ", output)
                self.assertIn("(in 1h30m)", output)

    def test_invalid_program_is_rejected_before_anything_is_stored(self) -> None:
        script = self.root / "bad.blemesh"
        script.write_text("on\nbogus\n", encoding="utf-8")
        with self.assertRaises(Exception):
            self.run_cli(["script", str(script), "--address", DESK, "--in", "5m"])
        self.assertEqual(self.spawned, [])
        self.assertEqual(list(timers.timers_directory().glob("*")), [])

    def test_group_timers_store_the_resolved_members(self) -> None:
        config.add_alias("desk", DESK)
        config.add_alias("shelf", SHELF)
        config.add_group("room", ["desk", "shelf"])
        self.run_cli(["off", "--light", "room", "--in", "10s"])
        record = timers.read_record(self.spawned[-1][0])
        self.assertTrue(record["is_group"])
        self.assertEqual(record["addresses"], [DESK, SHELF])

    def test_fade_requires_a_colour_with_an_explanatory_error(self) -> None:
        with self.assertRaisesRegex(timers.TimerError, "cannot report their current state"):
            self.run_cli(["fade", "--over", "10m", "--address", DESK, "--in", "1h"])
        self.assertEqual(self.spawned, [])

    def test_fade_schedules_a_generated_program(self) -> None:
        result, output, _ = self.run_cli(
            ["fade", "--color", "ff8800", "--over", "20m", "--address", DESK, "--in", "2h"]
        )
        self.assertEqual(result, 0)
        self.assertIn("fade ff8800 100->off over 20m at ", output)
        record = timers.read_record(self.spawned[-1][0])
        self.assertTrue(record["program"].endswith("wait 12s\noff\n"))
        self.assertEqual(self.daemon.await_count, 0)

    def test_fade_without_in_runs_now_through_the_normal_script_path(self) -> None:
        sleep = AsyncMock()
        with patch("blemeshctl.cli.asyncio.sleep", sleep):
            result, output, _ = self.run_cli(
                ["fade", "--white", "--from", "50", "--to", "40", "--over", "20s", "--address", DESK]
            )
        self.assertEqual(result, 0)
        self.assertEqual(self.spawned, [])
        self.assertGreaterEqual(self.daemon.await_count, 5)
        self.assertEqual(sleep.await_count, self.daemon.await_count - 1)
        self.assertIn("white 4600 K, brightness 50", output)

    def test_commands_without_in_do_not_touch_timers(self) -> None:
        result, _, _ = self.run_cli(["on", "--address", DESK])
        self.assertEqual(result, 0)
        self.assertEqual(self.spawned, [])
        self.assertEqual(self.daemon.await_count, 1)

    def test_in_option_exists_on_every_supported_command(self) -> None:
        for argv in (
            ["on"], ["off"], ["color", "00ff00"], ["white"], ["script", "x"], ["scene", "apply", "x"],
            ["fade", "--color", "00ff00", "--over", "1m"],
        ):
            with self.subTest(argv=argv):
                self.assertEqual(build_parser().parse_args([*argv, "--in", "90s"]).delay_seconds, 90)
                self.assertIsNone(build_parser().parse_args(argv).delay_seconds)

    def test_out_of_range_in_is_rejected_by_the_parser(self) -> None:
        for value in ("0s", "25h"):
            with self.subTest(value=value), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                build_parser().parse_args(["on", "--in", value])


class SunriseProgramTests(unittest.TestCase):
    def commands(self, text: str) -> list[tuple[str, int]]:
        return [(line.split()[1], int(line.split()[2])) for line in text.splitlines() if line.startswith("color")]

    def test_frame_endpoints_and_keyframes(self) -> None:
        self.assertEqual(timers.sunrise_frame(0.0), ((255, 0, 0), 1))
        self.assertEqual(timers.sunrise_frame(1.0), ((255, 0xB0, 0x60), 100))
        for fraction, color, level in timers.SUNRISE_KEYFRAMES:
            self.assertEqual(timers.sunrise_frame(fraction), (color, level))
        # Halfway between the first two keyframes.
        self.assertEqual(timers.sunrise_frame(0.15), ((255, 30, 0), 11))

    def test_brightness_is_monotonic_and_ends_at_the_final_keyframe(self) -> None:
        for over in (60, 120, 1800, 21600):
            with self.subTest(over=over):
                steps = self.commands(timers.sunrise_program_text(over))
                levels = [level for _, level in steps]
                self.assertEqual(levels, sorted(levels))
                self.assertEqual(steps[0], ("ff0000", 1))
                self.assertEqual(steps[-1], ("ffb060", 100))
                self.assertTrue(all(1 <= level <= 100 for level in levels))
                self.assertEqual(len(set(steps)), len(steps))

    def test_only_rgb_colour_commands_and_waits(self) -> None:
        text = timers.sunrise_program_text(1800)
        for line in text.splitlines()[1:]:
            self.assertRegex(line, r"^(color [0-9a-f]{6} [0-9]{1,3}|wait [0-9.]+s)$")
        self.assertNotIn("white", text)
        self.assertNotIn("on", text.split())
        self.assertIsNone(timers.final_off_line(parse_script(text, "sunrise")))

    def test_step_count_cap_and_total_duration(self) -> None:
        for over in (60, 120, 1800, 3600, 21600):
            with self.subTest(over=over):
                text = timers.sunrise_program_text(over)
                program = parse_script(text, "sunrise")
                self.assertLessEqual(timers.count_commands(program), 301)
                waits = [float(line.split()[1][:-1]) for line in text.splitlines() if line.startswith("wait")]
                self.assertLessEqual(sum(waits), over + 0.01)
                self.assertGreaterEqual(min(waits), 5.0 - 1e-9)
        self.assertEqual(timers.count_commands(parse_script(timers.sunrise_program_text(120), "x")), 25)
        self.assertLessEqual(timers.count_commands(parse_script(timers.sunrise_program_text(21600), "x")), 400)

    def test_duplicate_steps_merge_their_waits(self) -> None:
        text = timers.sunrise_program_text(21600)
        waits = [float(line.split()[1][:-1]) for line in text.splitlines() if line.startswith("wait")]
        self.assertIn(144.0, waits)
        self.assertAlmostEqual(sum(waits), 21600 - 72.0 * 0, delta=72.0)
        self.assertEqual(len(self.commands(text)), len(waits) + 1)

    def test_out_of_range_lengths_are_rejected(self) -> None:
        for over in (59, 21601):
            with self.assertRaises(timers.TimerError):
                timers.sunrise_program_text(over)

    def test_description(self) -> None:
        self.assertEqual(timers.sunrise_description(1800, "07:00"), "sunrise 30m ending 07:00")
        self.assertEqual(timers.sunrise_program_text(120).splitlines()[0], "# sunrise 2m")


class SunrisePlanTests(unittest.TestCase):
    def setUp(self) -> None:
        super().setUp()
        self.old_tz = os.environ.get("TZ")
        os.environ["TZ"] = "Europe/Helsinki"
        time.tzset()

    def tearDown(self) -> None:
        if self.old_tz is None:
            os.environ.pop("TZ", None)
        else:
            os.environ["TZ"] = self.old_tz
        time.tzset()
        super().tearDown()

    @staticmethod
    def stamp(*parts: int) -> float:
        return datetime(*parts).timestamp()

    def test_wake_time_later_today_with_a_full_ramp(self) -> None:
        now = self.stamp(2026, 11, 14, 6, 0)
        plan = timers.plan_sunrise_at(7, 0, 1800, now)
        self.assertEqual(plan.wake_at, self.stamp(2026, 11, 14, 7, 0))
        self.assertEqual(plan.start_at, self.stamp(2026, 11, 14, 6, 30))
        self.assertEqual((plan.over_seconds, plan.compressed), (1800, False))

    def test_wake_time_earlier_today_means_tomorrow(self) -> None:
        now = self.stamp(2026, 11, 14, 21, 20)
        plan = timers.plan_sunrise_at(7, 0, 1800, now)
        self.assertEqual(plan.wake_at, self.stamp(2026, 11, 15, 7, 0))
        self.assertEqual(plan.start_at, self.stamp(2026, 11, 15, 6, 30))
        # Exactly now is not strictly in the future.
        same = timers.plan_sunrise_at(7, 0, 1800, self.stamp(2026, 11, 14, 7, 0))
        self.assertEqual(same.wake_at, self.stamp(2026, 11, 15, 7, 0))

    def test_late_start_is_compressed_to_the_time_that_remains(self) -> None:
        now = self.stamp(2026, 11, 14, 6, 48) + 30.5
        plan = timers.plan_sunrise_at(7, 0, 1800, now)
        self.assertTrue(plan.compressed)
        self.assertEqual((plan.start_at, plan.over_seconds), (now, 689))
        self.assertEqual(plan.wake_at, self.stamp(2026, 11, 14, 7, 0))

    def test_exactly_enough_time_is_not_compressed(self) -> None:
        now = self.stamp(2026, 11, 14, 6, 30)
        self.assertFalse(timers.plan_sunrise_at(7, 0, 1800, now).compressed)

    def test_too_close_to_the_wake_time(self) -> None:
        for now in (self.stamp(2026, 11, 14, 6, 59, 30), self.stamp(2026, 11, 14, 6, 59, 1)):
            with self.subTest(now=now), self.assertRaisesRegex(
                timers.TimerError, "too close to the wake time for a ramp; use a shorter --over or a later time"
            ):
                timers.plan_sunrise_at(7, 0, 1800, now)
        self.assertEqual(timers.plan_sunrise_at(7, 0, 1800, self.stamp(2026, 11, 14, 6, 59)).over_seconds, 60)

    def test_spring_forward_night_is_an_hour_shorter(self) -> None:
        # Helsinki skips 03:00-04:00 on 2026-03-29, so 07:00 is only 6 real hours after 22:00... of the day before.
        now = self.stamp(2026, 3, 28, 23, 0)
        plan = timers.plan_sunrise_at(7, 0, 1800, now)
        self.assertEqual(plan.wake_at - now, 7 * 3600)  # 8 wall hours, 7 real hours
        self.assertEqual(plan.start_at - now, 7 * 3600 - 1800)
        self.assertEqual(datetime.fromtimestamp(plan.wake_at).hour, 7)

    def test_skipped_wall_time_still_resolves_to_a_future_instant(self) -> None:
        now = self.stamp(2026, 3, 29, 1, 0)
        wake = timers.next_local_time(3, 30, now)
        self.assertGreater(wake, now)
        self.assertLess(wake - now, 3 * 3600)

    def test_fall_back_night_is_an_hour_longer(self) -> None:
        # Helsinki repeats 03:00-04:00 on 2026-10-25.
        now = self.stamp(2026, 10, 24, 23, 0)
        plan = timers.plan_sunrise_at(7, 0, 1800, now)
        self.assertEqual(plan.wake_at - now, 9 * 3600)  # 8 wall hours, 9 real hours
        self.assertEqual(datetime.fromtimestamp(plan.wake_at).hour, 7)

    def test_repeated_wall_time_picks_the_first_occurrence_still_ahead(self) -> None:
        first = datetime(2026, 10, 25, 3, 30, fold=0).timestamp()
        second = datetime(2026, 10, 25, 3, 30, fold=1).timestamp()
        self.assertEqual(second - first, 3600)
        self.assertEqual(timers.next_local_time(3, 30, first - 60), first)
        self.assertEqual(timers.next_local_time(3, 30, first + 60), second)

    def test_formatting(self) -> None:
        self.assertEqual(timers.format_wake_time(self.stamp(2026, 11, 15, 7, 0)), "Sun 15 Nov 07:00")
        self.assertEqual(timers.format_wake_time(self.stamp(2026, 11, 5, 7, 5)), "Thu 5 Nov 07:05")

    def test_argument_parsers(self) -> None:
        self.assertEqual(timers.time_of_day_argument("07:00"), (7, 0))
        self.assertEqual(timers.time_of_day_argument("7:05"), (7, 5))
        self.assertEqual(timers.time_of_day_argument("23:59"), (23, 59))
        for bad in ("24:00", "12:60", "7", "0700", "07:0", "ab:cd", "", "7:00pm"):
            with self.subTest(bad=bad), self.assertRaises(argparse.ArgumentTypeError):
                timers.time_of_day_argument(bad)
        self.assertEqual(timers.sunrise_over_argument("1m"), 60)
        self.assertEqual(timers.sunrise_over_argument("6h"), 21600)
        for bad in ("59s", "6h1s", "0s", "soon"):
            with self.subTest(bad=bad), self.assertRaises(argparse.ArgumentTypeError):
                timers.sunrise_over_argument(bad)


class SunriseCliTests(SchedulingTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.old_tz = os.environ.get("TZ")
        os.environ["TZ"] = "Europe/Helsinki"
        time.tzset()
        self.now = datetime(2026, 11, 14, 20, 23, 0).timestamp()
        patcher = patch("blemeshctl.cli._now", lambda: self.now)
        patcher.start()
        self.addCleanup(patcher.stop)

    def tearDown(self) -> None:
        if self.old_tz is None:
            os.environ.pop("TZ", None)
        else:
            os.environ["TZ"] = self.old_tz
        time.tzset()
        super().tearDown()

    def test_at_schedules_the_runner_and_prints_the_id_and_wake_time(self) -> None:
        result, output, _ = self.run_cli(["sunrise", "--at", "07:00", "--address", DESK, "--password", SECRET_PASSWORD])
        self.assertEqual(result, 0)
        self.daemon.assert_not_called()
        [(path, credentials)] = self.spawned
        self.assertEqual(
            output,
            f"Scheduled {path.stem}: sunrise 30m ending 07:00 at 06:30:00 (in 10h7m)\nWake time: Sun 15 Nov 07:00\n",
        )
        self.assertEqual(credentials["password"], SECRET_PASSWORD)
        record = timers.read_record(path)
        self.assertEqual(record["description"], "sunrise 30m ending 07:00")
        self.assertEqual((record["target"], record["addresses"]), (DESK, [DESK]))
        self.assertEqual(record["program"], timers.sunrise_program_text(1800, description="sunrise 30m ending 07:00"))
        self.assertNotIn(SECRET_PASSWORD, path.read_text(encoding="utf-8"))
        self.assertNotIn("white", record["program"])

    def test_timers_listing_shows_the_description(self) -> None:
        self.run_cli(["sunrise", "--in", "1h", "--over", "10m", "--address", DESK])
        with patch("blemeshctl.timers.runner_is_alive", return_value=True):
            _, output, _ = self.run_cli(["timers"])
        self.assertIn("sunrise 10m ending 21:33", output)
        self.assertIn("scheduled", output)

    def test_in_starts_the_ramp_after_the_delay(self) -> None:
        _, output, _ = self.run_cli(["sunrise", "--in", "8h", "--over", "45m", "--address", DESK])
        self.assertRegex(output, r"sunrise 45m ending 05:08 at 04:23:00 \(in 8h\)\nWake time: Sun 15 Nov 05:08\n$")
        record = timers.read_record(self.spawned[-1][0])
        self.assertAlmostEqual(record["fire_at"] - record["created"], 8 * 3600, delta=5)

    def test_compressed_ramp_is_reported(self) -> None:
        self.now = datetime(2026, 11, 15, 6, 48, 0).timestamp()
        result, output, _ = self.run_cli(["sunrise", "--at", "07:00", "--address", DESK])
        self.assertEqual(result, 0)
        self.assertIn("sunrise 12m ending 07:00 at ", output)
        self.assertIn("(now)", output)
        self.assertIn("shortened from 30m to 12m", output)
        self.assertEqual(timers.read_record(self.spawned[-1][0])["description"], "sunrise 12m ending 07:00")

    def test_too_close_to_the_wake_time_stores_nothing(self) -> None:
        self.now = datetime(2026, 11, 15, 6, 59, 30).timestamp()
        with self.assertRaisesRegex(timers.TimerError, "too close to the wake time"):
            self.run_cli(["sunrise", "--at", "07:00", "--address", DESK])
        self.assertEqual(self.spawned, [])
        self.assertEqual(list(timers.timers_directory().glob("*")), [])

    def test_argument_errors(self) -> None:
        cases = (
            ["sunrise", "--address", DESK],
            ["sunrise", "--at", "07:00", "--in", "1h"],
            ["sunrise", "--at", "07:00", "--over", "30s"],
            ["sunrise", "--at", "07:00", "--over", "7h"],
            ["sunrise", "--at", "25:00"],
            ["sunrise", "--at", "7am"],
        )
        for argv in cases:
            with self.subTest(argv=argv), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                build_parser().parse_args(argv)
        self.assertEqual(build_parser().parse_args(["sunrise", "--at", "6:45"]).sunrise_over, 1800)
        self.assertEqual(self.spawned, [])

    def test_group_target_uses_the_same_path_as_fade(self) -> None:
        config.add_alias("desk", DESK)
        config.add_alias("shelf", SHELF)
        config.add_group("room", ["desk", "shelf"])
        self.run_cli(["sunrise", "--at", "07:00", "--light", "room"])
        record = timers.read_record(self.spawned[-1][0])
        self.assertTrue(record["is_group"])
        self.assertEqual((record["target"], record["addresses"]), ("room", [DESK, SHELF]))

    def test_runner_finishes_without_an_off(self) -> None:
        self.run_cli(["sunrise", "--in", "1m", "--over", "1m", "--address", DESK])
        path = self.spawned[-1][0]
        backend = FakeBackend()
        clock = FakeClock(now=timers.read_record(path)["fire_at"])
        state = asyncio.run(
            timers.run_timer(path, CREDENTIALS, lambda record, credentials: backend, clock=clock.time, sleep=clock.sleep)
        )
        self.assertEqual(state, timers.STATE_DONE)
        self.assertEqual(len(backend.calls), 13)
        self.assertEqual(backend.descriptions()[-1], "colour rgb #ffb060, brightness 100")
        self.assertNotIn("off", backend.descriptions())


class ExampleScriptTests(unittest.TestCase):
    def test_every_shipped_example_compiles(self) -> None:
        from blemeshctl.script import load_script

        paths = sorted((Path(__file__).parents[1] / "examples").glob("*.blemesh"))
        self.assertIn("sunrise.blemesh", [path.name for path in paths])
        for path in paths:
            with self.subTest(path=path.name):
                self.assertTrue(load_script(path))

    def test_sunrise_example_matches_the_generator(self) -> None:
        text = (Path(__file__).parents[1] / "examples" / "sunrise.blemesh").read_text(encoding="utf-8")
        body = "\n".join(line for line in text.splitlines() if not line.startswith("#"))
        expected = "\n".join(timers.sunrise_program_text(120).splitlines()[1:])
        self.assertEqual(body.strip(), expected.strip())


class TimerBackendTests(TimerTestCase):
    def setUp(self) -> None:
        super().setUp()
        for stream in (redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO())):
            stream.__enter__()
            self.addCleanup(stream.__exit__, None, None, None)

    def record(self, *, group: bool) -> dict[str, Any]:
        return {
            "target": "room" if group else "desk",
            "addresses": [DESK, SHELF] if group else [DESK],
            "is_group": group,
            "connection": {"scan_timeout": 1.0, "connect_timeout": 2.0},
        }

    def plan(self) -> cli._GroupPlan:
        plan = cli._GroupPlan()
        plan.addressable[DESK] = cli._GroupMember(DESK, 0x67, -40, connected=False)
        plan.addressable[SHELF] = cli._GroupMember(SHELF, 0x68, -50, connected=False)
        return plan

    def test_single_light_sends_through_the_daemon_with_the_requested_keepalive(self) -> None:
        daemon = AsyncMock(return_value={"address": DESK, "mesh_address": 1, "reused": True, "idle_timeout": 5.0})
        backend = cli.timer_backend(self.record(group=False), CREDENTIALS)
        with patch("blemeshctl.cli.request_daemon", daemon):
            asyncio.run(backend.send(COMMAND_ON_OFF, off_parameters(), "off", 5.0))
        request = daemon.await_args.args[0]
        self.assertEqual((request["address"], request["idle_timeout"], request["mesh_name"]), (DESK, 5.0, SECRET_MESH))
        self.assertEqual(daemon.await_args.kwargs["socket_path"], socket_path_for_address(DESK))

    def test_single_light_failures_become_step_errors_without_waiting_forever(self) -> None:
        daemon = AsyncMock(side_effect=DaemonError("not advertising", retryable=True))
        backend = cli.timer_backend(self.record(group=False), CREDENTIALS)
        with patch("blemeshctl.cli.request_daemon", daemon):
            with self.assertRaisesRegex(timers.StepError, "not advertising"):
                asyncio.run(backend.send(COMMAND_ON_OFF, off_parameters(), "off", 5.0))
        self.assertEqual(daemon.await_count, 1)

    def test_group_rebuilds_the_relay_after_a_failure(self) -> None:
        plans = AsyncMock(side_effect=lambda arguments, light: self.plan())
        calls: list[tuple[str, int | None]] = []

        async def request(payload: dict[str, Any], **kwargs: Any) -> dict[str, Any]:
            calls.append((payload["address"], payload.get("target_mesh_address")))
            if len(calls) == 1:
                raise DaemonError("link lost")
            return {"address": payload["address"], "mesh_address": 0x67, "reused": False, "idle_timeout": 5.0}

        backend = cli.timer_backend(self.record(group=True), CREDENTIALS)

        async def scenario() -> list[str]:
            outcomes = []
            for _ in range(2):
                try:
                    await backend.send(COMMAND_ON_OFF, off_parameters(), "off", 5.0)
                    outcomes.append("ok")
                except timers.StepError as exc:
                    outcomes.append(str(exc))
            return outcomes

        with patch("blemeshctl.cli._plan_group", plans), patch("blemeshctl.cli.request_daemon", request):
            outcomes = asyncio.run(scenario())
        self.assertEqual(plans.await_count, 2)  # rescanned after the failure
        self.assertIn("link lost", outcomes[0])
        self.assertEqual(outcomes[1], "ok")
        self.assertEqual(calls[1:], [(DESK, None), (DESK, 0x68)])

    def test_group_keeps_its_relay_between_successful_steps(self) -> None:
        plans = AsyncMock(side_effect=lambda arguments, light: self.plan())
        daemon = AsyncMock(return_value={"address": DESK, "mesh_address": 0x67, "reused": False, "idle_timeout": 5.0})
        backend = cli.timer_backend(self.record(group=True), CREDENTIALS)

        async def scenario() -> None:
            await backend.send(COMMAND_ON_OFF, off_parameters(), "off", 10.0)
            await backend.send(COMMAND_ON_OFF, off_parameters(), "off", 5.0)

        with patch("blemeshctl.cli._plan_group", plans), patch("blemeshctl.cli.request_daemon", daemon):
            asyncio.run(scenario())
        self.assertEqual(plans.await_count, 1)
        self.assertEqual(daemon.await_args.args[0]["idle_timeout"], 5.0)

    def test_group_member_that_was_not_seen_is_a_partial_step(self) -> None:
        plan = self.plan()
        plan.addressable.pop(SHELF)
        plan.unaddressable[SHELF] = "not seen"
        daemon = AsyncMock(return_value={"address": DESK, "mesh_address": 0x67, "reused": False, "idle_timeout": 5.0})
        backend = cli.timer_backend(self.record(group=True), CREDENTIALS)
        with patch("blemeshctl.cli._plan_group", AsyncMock(return_value=plan)), patch("blemeshctl.cli.request_daemon", daemon):
            with self.assertRaises(timers.StepError) as raised:
                asyncio.run(backend.send(COMMAND_ON_OFF, off_parameters(), "off", 5.0))
        self.assertTrue(raised.exception.partial)
        self.assertIn(SHELF, str(raised.exception))
        self.assertEqual(daemon.await_count, 1)

    def test_group_with_no_visible_member_fails_the_step(self) -> None:
        plan = cli._GroupPlan(unaddressable={DESK: "x", SHELF: "y"})
        backend = cli.timer_backend(self.record(group=True), CREDENTIALS)
        with patch("blemeshctl.cli._plan_group", AsyncMock(return_value=plan)):
            with self.assertRaisesRegex(timers.StepError, "no member was seen advertising"):
                asyncio.run(backend.send(COMMAND_ON_OFF, off_parameters(), "off", 5.0))


class ProgramAnalysisTests(unittest.TestCase):
    def test_final_off_detection(self) -> None:
        self.assertEqual(timers.final_off_line(parse_script("on\nwait 1s\noff\n")), 3)
        self.assertEqual(timers.final_off_line(parse_script("off\nwait 1s\n")), 1)
        self.assertIsNone(timers.final_off_line(parse_script("off\nwait 1s\non\n")))
        self.assertIsNone(timers.final_off_line(parse_script("repeat 2:\n  off\n  wait 1s\n")))

    def test_command_counts(self) -> None:
        self.assertEqual(timers.count_commands(parse_script("on\nrepeat 3:\n  off\n  wait 1s\n")), 4)
        self.assertIsNone(timers.count_commands(parse_script("loop:\n  on\n  wait 1s\n")))
        self.assertEqual(timers.longest_wait(parse_script("on\nwait 2m\nrepeat 2:\n  wait 5s\n")), 120.0)


if __name__ == "__main__":
    unittest.main()
