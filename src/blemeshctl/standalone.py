"""Entrypoint shared by installed and frozen ``blemeshctl`` executables."""

from __future__ import annotations

import sys

from . import cli, daemon, timers


def main() -> None:
    """Route frozen daemon and timer-runner children internally; otherwise run the public CLI."""

    if sys.argv[1:2] == [daemon.INTERNAL_DAEMON_ARGUMENT]:
        del sys.argv[1]
        daemon.main()
        return
    if sys.argv[1:2] == [timers.INTERNAL_TIMER_ARGUMENT]:
        del sys.argv[1]
        timers.main()
        return
    cli.main()


if __name__ == "__main__":
    main()
