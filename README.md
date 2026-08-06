# blemeshctl

`blemeshctl` controls legacy Telink BleMesh lights from a Linux command line.
It was built and verified against a Briloner Control-compatible light advertising
as `BleMesh`.

It supports only normal light controls: discovery, on, off, and solid RGB
colour. It intentionally does not expose provisioning, factory-reset, or OTA
commands.

## Install

Python 3.10+ and a working BlueZ Bluetooth stack are required.

```bash
git clone https://github.com/topiautio/blemeshctl.git
cd blemeshctl
python -m venv .venv
.venv/bin/pip install -e .
. .venv/bin/activate
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

## How it works

This is a legacy proprietary Telink mesh protocol, not Bluetooth SIG Mesh.
The device does not require Bluetooth pairing, but it does require an
application-level AES challenge/response login. `blemeshctl` performs that
login for each control operation, derives a fresh session key, and sends the
encrypted vendor command to the mesh address advertised by the target.

The command encryption is implemented in Python and tested against a known
Telink native-library output vector. No APK or Android shared library is
needed at runtime.

## Test

```bash
python -m unittest discover -s tests -v
```

The unit tests cover advertisement parsing, authentication key derivation,
the command-frame layout, and the Telink command-encryption test vector.

## Safety notes

- A device name such as `BleMesh` is generic. Use `scan`, check the MAC and
  signal level, then prefer `--address` when more than one device is nearby.
- The advertised status byte is reported verbatim. Treat it as a diagnostic
  hint, not a complete colour/state readback.
- This project has not been tested with provisioning or firmware-update
  workflows; it deliberately does not implement them.
