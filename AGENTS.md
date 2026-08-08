# Repository Guidelines

## Project Structure & Module Organization

This Python 3.10+ package uses a `src` layout. In `src/blemeshctl/`, `cli.py` parses commands, `client.py` handles BLE sessions, `daemon.py` retains connections, `protocol.py` implements Telink framing and cryptography, `script.py` handles `.blemesh` programs, and `standalone.py` routes the frozen executable. Matching unit tests live in `tests/test_*.py`; samples live in `examples/`. Standalone packaging is defined by `blemeshctl.spec`, `scripts/smoke-standalone.sh`, and `.github/workflows/standalone.yml`.

## Build, Test, and Development Commands

```bash
python -m venv .venv
.venv/bin/pip install -e .
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m blemeshctl --help
.venv/bin/blemeshctl scan
```

The test and help commands require no hardware; `scan` needs Linux, BlueZ, and an adapter. Build the downloadable executable with:

```bash
.venv/bin/pip install -e '.[standalone]'
.venv/bin/python -m PyInstaller --clean --noconfirm blemeshctl.spec
scripts/smoke-standalone.sh dist/blemeshctl
```

## Coding Style & Naming Conventions

Use four-space indentation, type annotations, concise docstrings, and the existing `from __future__ import annotations` pattern. Name functions `snake_case`, classes `PascalCase`, and constants `UPPER_SNAKE_CASE`. Preserve async BLE/socket boundaries. No formatter or linter is configured; match nearby code and group imports as standard library, third-party, then local. Indent `.blemesh` blocks by two spaces.

## Testing Guidelines

Tests use `unittest`: name files `test_<module>.py`, classes `<Area>Tests`, and methods `test_<behavior>`. Mock Bleak and daemon boundaries; tests must be deterministic. Add known vectors for cryptographic changes and failure-path tests where delivery is uncertain. No numeric coverage threshold exists, but behavior changes need focused regression coverage. Packaging changes must pass the standalone smoke test.

## Commit & Pull Request Guidelines

Use short imperative commits, such as `Add programmable light scripts`. Keep commits focused. Pull requests should summarize user-visible behavior, list test results, link issues when applicable, and distinguish mocked checks from real-device testing. Update documentation for CLI, script-language, packaging, or release changes.

## Bluetooth Safety & Configuration

Prefer an explicit `--address`; `BleMesh` is a generic device name. Do not commit private mesh credentials or new device identifiers unintentionally. Keep provisioning, factory reset, and OTA outside project scope, and never claim a physical state change without device-side observation.
