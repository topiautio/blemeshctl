# blemeshctl

`blemeshctl` controls legacy Telink BleMesh lights from a Linux command line.
It was built and verified against a Briloner Control-compatible light advertising
as `BleMesh`.

It supports only normal light controls: discovery, on, off, and solid RGB
colour. It intentionally does not expose provisioning, factory-reset, or OTA
commands.

## Install

Python 3.10+ and a working BlueZ Bluetooth stack are required.

### Recommended: `pipx`

`pipx` keeps Python dependencies isolated but exposes `blemeshctl` directly on
your normal shell `PATH`, so no virtual-environment activation is needed.

On Arch-based distributions such as CachyOS, install it once:

```bash
sudo pacman -S python-pipx
pipx ensurepath
```

Then, from a checkout of this repository, install the CLI:

```bash
pipx install --editable .
blemeshctl scan
```

Open a new shell after `pipx ensurepath` if `blemeshctl` is not immediately
found. To update an editable checkout after changing dependencies, run
`pipx reinstall blemeshctl`.

### Development fallback

```bash
git clone https://github.com/topiautio/blemeshctl.git
cd blemeshctl
python -m venv .venv
.venv/bin/pip install -e .
.venv/bin/blemeshctl scan
```

On Linux, first confirm that Bluetooth is enabled and the target is visible:

```bash
bluetoothctl show
blemeshctl scan
```

## Use

Discovery prints the Bluetooth MAC address, advertised Telink mesh address,
product ID, and raw status byte:

```bash
blemeshctl scan
```

Control an explicitly selected device:

```bash
blemeshctl on --address A4:C1:38:93:1B:72
blemeshctl color 00ff00 --address A4:C1:38:93:1B:72
blemeshctl off --address A4:C1:38:93:1B:72
```

When there is exactly one compatible advertisement, `--address` may be omitted.
The CLI obtains the mesh address from the current advertisement rather than
requiring it to be hard-coded.

For the Briloner-compatible light this project was tested with, the application
credentials were `BleMesh` and `mesh123`; those are the defaults. Override
them for another mesh:

```bash
blemeshctl on \
  --address A4:C1:38:93:1B:72 \
  --mesh-name MyMesh \
  --password MyPassword
```

Set colour brightness independently (1 through 100):

```bash
blemeshctl color '#00ff00' --brightness 65 --address A4:C1:38:93:1B:72
```

## Fast repeated controls

The first control command has to wait for the light to advertise, connect, and
authenticate. After that, `blemeshctl` automatically keeps the authenticated
connection open for 60 seconds after the last command. A new `on`, `off`, or
`color` command for the same address reuses it and resets the one-minute timer:

```bash
blemeshctl color ff0000 --address A4:C1:38:93:1B:72
blemeshctl color 00ff00 --address A4:C1:38:93:1B:72
blemeshctl color 0000ff --address A4:C1:38:93:1B:72
```

The connection runs in a private per-user Unix socket under
`$XDG_RUNTIME_DIR` and closes automatically when idle. Inspect or close it
explicitly when useful:

```bash
blemeshctl daemon status --address A4:C1:38:93:1B:72
blemeshctl daemon stop --address A4:C1:38:93:1B:72
```

Use `--keepalive-seconds 0` to close the retained connection immediately after
one command, or choose another value up to one hour.

## Scripts

For animations or repeatable scenes, put normal light controls in a text file
and run it with `blemeshctl script scriptname`. The shipped
[red, green, blue example](examples/rgb-cycle.blemesh) and
[saturated rainbow example](examples/rainbow.blemesh) both loop until
interrupted:

```bash
blemeshctl script examples/rgb-cycle.blemesh --address A4:C1:38:93:1B:72
blemeshctl script examples/rainbow.blemesh --address A4:C1:38:93:1B:72
```

Use `--address` for scripts whenever possible: `BleMesh` is a generic device
name, and an endless animation should target the intended light explicitly.
The same mesh credentials, scan timeouts, and keepalive settings as regular
commands remain CLI options, so script files are portable and do not contain
passwords.

Scripts with an explicit `--address` wait and retry by default when that light
is temporarily not advertising or cannot complete its initial Bluetooth
connection. Press `Ctrl-C` to stop waiting, or use `--no-wait-for-connection`
for fail-fast behaviour. Normal `on`, `off`, and `color` commands remain
fail-fast by default; give them `--wait-for-connection` with `--address` when
the light may be temporarily unavailable. A link that drops after a command
was sent is not retried automatically, because that command's outcome is
unknown.

Scripts are deliberately a small declarative format, not shell or Python.
The complete file is validated before its first command is sent. Blank lines
and lines beginning with `#` are ignored. Blocks use spaces for indentation:

```text
on
loop:
  color ff0000
  wait 1s
  color 00ff00 65
  wait 250ms
  color 0000ff
  wait 1s
```

Available instructions are `on`, `off`, `color RRGGBB [brightness]`,
`wait duration`, `rainbow duration`, `repeat N:`, and `loop:`. A duration is a
positive number of seconds, or may end in `ms`, `s`, or `m`; for example,
`0.5`, `250ms`, `1s`, or `2m`. `repeat N:` runs its indented block a fixed
number of times, while `loop:` repeats forever. Stop an endless loop with
`Ctrl-C`.

`rainbow 100ms` is a gaming-style sweep through all 1,530 distinct fully
saturated 8-bit RGB hue steps: red through the colour wheel and back to red.
It deliberately excludes white and pastels. The full non-white RGB space has
16,777,215 values and would take about 19 days at 100 ms per colour, so that
is not practical for an animation. One saturated `rainbow 100ms` sweep takes
at least 153 seconds; Bluetooth command time can make it longer. The 100 ms
wait starts after each command finishes, so it is a minimum dwell rather than
a guaranteed hardware update rate.

Each action uses the existing per-address keepalive daemon, so the first one
opens and authenticates the Bluetooth connection and later actions reuse it.
If a `wait` is at least as long as `--keepalive-seconds` (60 seconds by
default), the next action will reconnect naturally; raise that option for
longer pauses.

## How it works

This is a legacy proprietary Telink mesh protocol, not Bluetooth SIG Mesh.
The device does not require Bluetooth pairing, but it does require an
application-level AES challenge/response login. `blemeshctl` performs that
login when it opens a connection, derives a session key, and sends encrypted
vendor commands to the mesh address advertised by the target. The local
keepalive daemon retains that GATT connection only for normal light controls;
it does not expose provisioning, reset, or OTA operations.

The command encryption is implemented in Python and tested against a known
Telink native-library output vector. No APK or Android shared library is
needed at runtime.

## Test

```bash
python -m unittest discover -s tests -v
```

The unit tests cover advertisement parsing, authentication key derivation,
the command-frame layout, the Telink command-encryption test vector, script
parsing and execution, retained GATT sessions, and the daemon's socket and
idle-timeout lifecycle.

## Safety notes

- A device name such as `BleMesh` is generic. Use `scan`, check the MAC and
  signal level, then prefer `--address` when more than one device is nearby.
- The advertised status byte is reported verbatim. Treat it as a diagnostic
  hint, not a complete colour/state readback.
- This project has not been tested with provisioning or firmware-update
  workflows; it deliberately does not implement them.
