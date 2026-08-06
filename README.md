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
the command-frame layout, the Telink command-encryption test vector, retained
GATT sessions, and the daemon's socket and idle-timeout lifecycle.

## Safety notes

- A device name such as `BleMesh` is generic. Use `scan`, check the MAC and
  signal level, then prefer `--address` when more than one device is nearby.
- The advertised status byte is reported verbatim. Treat it as a diagnostic
  hint, not a complete colour/state readback.
- This project has not been tested with provisioning or firmware-update
  workflows; it deliberately does not implement them.
