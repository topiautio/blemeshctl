# blemeshctl

`blemeshctl` controls legacy Telink BleMesh lights from the Linux command line.
It was built and verified against a Briloner Control-compatible light that
advertises as `BleMesh`.

It supports discovery, on, off, solid RGB colour, and simple animation
scripts. It intentionally does not support provisioning, factory reset, or OTA
firmware updates. An [Android app](#android-app) with the same controls and
script language lives in `android/`.

## Install

Every install method needs a working BlueZ Bluetooth stack and adapter. Check
that Bluetooth is enabled before you start:

```bash
bluetoothctl show
```

### Standalone executable

Each tagged GitHub Release includes a standalone executable for 64-bit Linux.
It bundles Python and the required packages, so it does not need a Python
installation:

```bash
curl --fail --location \
  --output blemeshctl \
  https://github.com/topiautio/blemeshctl/releases/latest/download/blemeshctl-linux-x86_64
chmod +x blemeshctl
./blemeshctl --help
```

It is built and tested on Ubuntu 22.04 and should run on newer glibc-based
x86_64 systems.

### From source

Installing from source needs Python 3.10 or newer. Clone the repository first:

```bash
git clone https://github.com/topiautio/blemeshctl.git
cd blemeshctl
```

The recommended way to install is `pipx`. It isolates the Python dependencies
but puts `blemeshctl` on your normal `PATH`, so you don't need to activate a
virtual environment. Install `pipx` from your distribution; on Arch-based
systems such as CachyOS:

```bash
sudo pacman -S python-pipx
pipx ensurepath
```

Then install the CLI from the checkout:

```bash
pipx install --editable .
blemeshctl scan
```

Open a new shell if `blemeshctl` is not found right after `pipx ensurepath`.
After changing dependencies, run `pipx reinstall blemeshctl`.

To use a plain virtual environment instead:

```bash
python -m venv .venv
.venv/bin/pip install -e .
.venv/bin/blemeshctl scan
```

## Usage

`scan` lists nearby compatible lights with their Bluetooth MAC address,
advertised Telink mesh address, product ID, and raw status byte:

```bash
blemeshctl scan
```

Control a light by its MAC address:

```bash
blemeshctl on --address A4:C1:38:93:1B:72
blemeshctl color 00ff00 --address A4:C1:38:93:1B:72
blemeshctl color '#00ff00' --brightness 65 --address A4:C1:38:93:1B:72
blemeshctl off --address A4:C1:38:93:1B:72
```

`--brightness` takes a value from 1 through 100. If exactly one compatible
light is advertising, `--address` may be omitted. The mesh address is read
from the light's current advertisement, so it doesn't need to be configured.

The default mesh credentials are `BleMesh` and `mesh123`, which the tested
Briloner-compatible light uses. Override them for another mesh:

```bash
blemeshctl on \
  --address A4:C1:38:93:1B:72 \
  --mesh-name MyMesh \
  --password MyPassword
```

### Retained connections

The first command has to wait for the light to advertise, connect, and
authenticate. After that, `blemeshctl` keeps the authenticated connection open
for 60 seconds after the last command. Another `on`, `off`, or `color` command
for the same address reuses it and resets the timer, so repeated commands are
fast:

```bash
blemeshctl color ff0000 --address A4:C1:38:93:1B:72
blemeshctl color 00ff00 --address A4:C1:38:93:1B:72
blemeshctl color 0000ff --address A4:C1:38:93:1B:72
```

The connection is held by a small per-user daemon behind a private Unix socket
under `$XDG_RUNTIME_DIR`. It exits on its own when idle. To inspect or close it
explicitly:

```bash
blemeshctl daemon status --address A4:C1:38:93:1B:72
blemeshctl daemon stop --address A4:C1:38:93:1B:72
```

`--keepalive-seconds` sets the idle time, up to one hour. Use
`--keepalive-seconds 0` to close the connection right after the command.

### Waiting for an unavailable light

By default, `on`, `off`, and `color` fail at once if the light is not
advertising or the initial Bluetooth connection fails. Add
`--wait-for-connection` together with `--address` to keep retrying instead;
press `Ctrl-C` to stop waiting. Scripts wait by default when given an
`--address`; use `--no-wait-for-connection` to make them fail fast.

If the link drops after a command was sent, that command is not retried,
because its outcome is unknown.

## Scripts

For animations or repeatable scenes, put light controls in a text file and run
it with `blemeshctl script`. The bundled
[red, green, blue example](examples/rgb-cycle.blemesh) and
[saturated rainbow example](examples/rainbow.blemesh) both loop until
interrupted with `Ctrl-C`:

```bash
blemeshctl script examples/rgb-cycle.blemesh --address A4:C1:38:93:1B:72
blemeshctl script examples/rainbow.blemesh --address A4:C1:38:93:1B:72
```

Mesh credentials, timeouts, and keepalive settings stay command-line options,
so script files are portable and contain no passwords.

Scripts are a small declarative format, not shell or Python. The whole file is
validated before the first command is sent. Blank lines and lines beginning
with `#` are ignored, and blocks are indented with spaces:

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

| Instruction | Effect |
| --- | --- |
| `on`, `off` | Turn the light on or off |
| `color RRGGBB [brightness]` | Set a solid colour, optionally with brightness 1–100 |
| `wait duration` | Pause |
| `rainbow duration` | Sweep through the saturated colour wheel, holding each step for `duration` |
| `repeat N:` | Run the indented block `N` times |
| `loop:` | Run the indented block forever |

A duration is a positive number of seconds, optionally ending in `ms`, `s`, or
`m`; for example `0.5`, `250ms`, `1s`, or `2m`.

`rainbow` steps through all 1,530 distinct fully saturated 8-bit RGB hues, from
red around the colour wheel and back to red, without white or pastels. One
`rainbow 100ms` sweep takes at least 153 seconds. The wait starts after each
command finishes, so the duration is a minimum dwell time, not a guaranteed
update rate.

Every action reuses the retained connection. If a `wait` is at least as long
as `--keepalive-seconds`, the next action reconnects; raise that option for
longer pauses.

## Android app

The `android/` directory holds a native Android app that uses the same Telink
protocol, `.blemesh` script language, and safety limits as the CLI. It scans
for compatible lights, remembers the last one used and connects to it ahead of
time, and offers on, off, a colour wheel with brightness, and a script editor
with the two bundled examples. Scripts run only while the app is in the
foreground. The interface is in Finnish and follows the system dark theme on
Android 10 and newer; the Appearance (Ulkoasu) option switches the dark theme
to pure black for AMOLED screens.

The app needs Android 8.0 or newer with Bluetooth LE. Android 12 and newer ask
for the Nearby devices permission; older versions ask for location instead,
which Android requires for Bluetooth scanning.

Signed APKs are attached to GitHub Releases whose tags start with `android-v`,
for example `android-v0.4.0`. These releases are not marked as latest, so the
Linux download link above keeps pointing at the CLI. Download the APK on the
phone from the repository's Releases page and open it.

Android only updates an installed app with a build signed by the same key. If
a build signed with a different key is installed, such as a local debug build,
uninstall it first. That also clears its remembered light, mesh credentials,
and script.

## How it works

This is a legacy proprietary Telink mesh protocol, not Bluetooth SIG Mesh. The
light does not need Bluetooth pairing, but it does require an
application-level AES challenge/response login. `blemeshctl` performs that
login when it connects, derives a session key, and sends encrypted vendor
commands to the mesh address the light advertises.

The command encryption is implemented in Python, and again in Kotlin for the
Android app. Both are tested against the same known output vector from
Telink's native library, and neither needs the vendor's APK or Android shared
library at runtime.

## Safety notes

- `BleMesh` is a generic device name. Use `scan`, check the MAC address and
  signal level, and pass `--address` whenever more than one light may be
  nearby. This matters most for endless script animations.
- The advertised status byte is reported verbatim. Treat it as a diagnostic
  hint, not a reliable readback of colour or power state.
- Provisioning and firmware-update workflows are untested and deliberately not
  implemented.

## Development

### Tests

```bash
python -m unittest discover -s tests -v
```

The unit tests cover advertisement parsing, authentication key derivation,
the command-frame layout, the Telink command-encryption test vector, script
parsing and execution, retained GATT sessions, and the daemon's socket and
idle-timeout lifecycle. They need no Bluetooth hardware.

### Standalone executable

Build and smoke-test the same one-file executable that GitHub Actions
produces:

```bash
.venv/bin/pip install -e '.[standalone]'
.venv/bin/python -m PyInstaller --clean --noconfirm blemeshctl.spec
scripts/smoke-standalone.sh dist/blemeshctl
```

### Android app

Building needs JDK 17 or newer and Android SDK platform 37
(`platforms;android-37.0`):

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/`. The unit
tests reuse the Python protocol test vectors and parse the scripts in
`examples/`.

### Releases

See [RELEASING.md](RELEASING.md) for publishing the Linux executable and the
signed Android APK, including the one-time signing-key setup.
