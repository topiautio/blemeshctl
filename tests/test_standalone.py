from __future__ import annotations

import sys
import unittest
from unittest.mock import patch

from blemeshctl import standalone
from blemeshctl.daemon import INTERNAL_DAEMON_ARGUMENT


class StandaloneEntrypointTests(unittest.TestCase):
    def test_normal_arguments_run_the_public_cli(self) -> None:
        with (
            patch.object(sys, "argv", ["blemeshctl", "scan"]),
            patch("blemeshctl.standalone.cli.main") as cli_main,
            patch("blemeshctl.standalone.daemon.main") as daemon_main,
        ):
            standalone.main()

        cli_main.assert_called_once_with()
        daemon_main.assert_not_called()

    def test_internal_argument_runs_the_embedded_daemon(self) -> None:
        arguments = [
            "blemeshctl",
            INTERNAL_DAEMON_ARGUMENT,
            "--socket",
            "/tmp/blemeshctl.sock",
        ]
        with (
            patch.object(sys, "argv", arguments),
            patch("blemeshctl.standalone.cli.main") as cli_main,
            patch("blemeshctl.standalone.daemon.main") as daemon_main,
        ):
            standalone.main()
            self.assertEqual(
                sys.argv,
                ["blemeshctl", "--socket", "/tmp/blemeshctl.sock"],
            )

        daemon_main.assert_called_once_with()
        cli_main.assert_not_called()
