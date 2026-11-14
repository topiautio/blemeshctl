"""Local storage for light aliases, light groups, and saved scenes.

Everything lives under ``$XDG_CONFIG_HOME/blemeshctl`` (by default
``~/.config/blemeshctl``). Only Bluetooth MAC addresses and scene text are
stored: mesh names and passwords always stay command-line options.
"""

from __future__ import annotations

import json
import os
import re
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

from .protocol import WHITE_NEUTRAL_KELVIN, parse_rgb
from .script import ScriptStep, parse_script

LIGHTS_FILE = "lights.json"
SCENES_DIRECTORY = "scenes"
SCENE_SUFFIX = ".blemesh"
MAX_NAME_LENGTH = 64

_NAME_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]*")
_MAC_PATTERN = re.compile(r"[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}")


class ConfigError(ValueError):
    """Raised when local aliases, groups, or scenes are invalid or unavailable."""


def config_directory() -> Path:
    """Return the configuration directory without creating it."""

    root = os.environ.get("XDG_CONFIG_HOME")
    # The XDG specification says relative paths must be ignored.
    base = Path(root) if root and Path(root).is_absolute() else Path.home() / ".config"
    return base / "blemeshctl"


def _ensure_directory(directory: Path) -> None:
    """Create ``directory`` (and the private config root) lazily with mode 0700."""

    for path in (config_directory(), directory):
        if path.is_dir():
            continue
        try:
            path.mkdir(mode=0o700, parents=True)
            os.chmod(path, 0o700)
        except FileExistsError:
            if not path.is_dir():
                raise ConfigError(f"{path}: exists but is not a directory") from None
        except OSError as exc:
            raise ConfigError(f"{path}: could not create directory: {exc}") from exc


def _write_atomically(path: Path, text: str) -> None:
    """Replace ``path`` with ``text`` via a private temporary file and rename."""

    _ensure_directory(path.parent)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except BaseException as exc:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        if isinstance(exc, OSError):
            raise ConfigError(f"{path}: could not write: {exc}") from exc
        raise


def is_address(value: str) -> bool:
    """Return whether ``value`` is a colon-separated Bluetooth MAC address."""

    return _MAC_PATTERN.fullmatch(value) is not None


def normalize_address(value: str) -> str:
    """Validate a Bluetooth MAC address and return it in upper case."""

    if not is_address(value):
        raise ConfigError(f"invalid Bluetooth MAC address {value!r}; expected AA:BB:CC:DD:EE:FF")
    return value.upper()


def validate_name(name: str, kind: str = "name") -> str:
    """Return ``name`` if it is a safe alias, group, or scene name."""

    if len(name) > MAX_NAME_LENGTH or _NAME_PATTERN.fullmatch(name) is None:
        raise ConfigError(
            f"invalid {kind} {name!r}; use up to {MAX_NAME_LENGTH} letters, digits, '_' or '-', "
            "starting with a letter or digit"
        )
    return name


@dataclass
class LightRegistry:
    """Named lights (aliases) and named collections of lights (groups)."""

    aliases: dict[str, str] = field(default_factory=dict)
    groups: dict[str, tuple[str, ...]] = field(default_factory=dict)


@dataclass(frozen=True)
class ResolvedLight:
    """The MAC addresses selected by a ``--light`` value."""

    name: str
    addresses: tuple[str, ...]
    is_group: bool


def lights_path() -> Path:
    """Return the path of the alias and group file."""

    return config_directory() / LIGHTS_FILE


def _parse_registry(data: object, path: Path) -> LightRegistry:
    if not isinstance(data, dict):
        raise ConfigError(f"{path}: expected a JSON object")
    raw_aliases = data.get("aliases", {})
    raw_groups = data.get("groups", {})
    if not isinstance(raw_aliases, dict) or not isinstance(raw_groups, dict):
        raise ConfigError(f"{path}: 'aliases' and 'groups' must be JSON objects")
    registry = LightRegistry()
    try:
        for name, address in raw_aliases.items():
            validate_name(name, "alias name")
            if not isinstance(address, str):
                raise ConfigError(f"alias {name!r} must map to a MAC address string")
            registry.aliases[name] = normalize_address(address)
        for name, members in raw_groups.items():
            validate_name(name, "group name")
            if name in registry.aliases:
                raise ConfigError(f"{name!r} is defined as both an alias and a group")
            if (
                not isinstance(members, list)
                or not members
                or not all(isinstance(member, str) for member in members)
            ):
                raise ConfigError(f"group {name!r} must be a non-empty list of strings")
            registry.groups[name] = tuple(members)
    except ConfigError as exc:
        raise ConfigError(f"{path}: {exc}") from None
    return registry


def load_lights() -> LightRegistry:
    """Read aliases and groups; a missing file is an empty registry."""

    path = lights_path()
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return LightRegistry()
    except (OSError, UnicodeError) as exc:
        raise ConfigError(f"{path}: could not read: {exc}") from exc
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ConfigError(f"{path}: invalid JSON: {exc}") from exc
    return _parse_registry(data, path)


def save_lights(registry: LightRegistry) -> None:
    """Atomically write aliases and groups."""

    data = {
        "aliases": dict(sorted(registry.aliases.items())),
        "groups": {name: list(members) for name, members in sorted(registry.groups.items())},
    }
    _write_atomically(lights_path(), json.dumps(data, indent=2) + "\n")


def _member_address(registry: LightRegistry, group: str, member: str) -> str:
    """Resolve one group member, which must be a MAC address or an alias."""

    if is_address(member):
        return member.upper()
    if member in registry.aliases:
        return registry.aliases[member]
    if member == group:
        raise ConfigError(f"group {group!r} cannot contain itself")
    if member in registry.groups:
        raise ConfigError(f"group {group!r} cannot contain another group ({member!r})")
    raise ConfigError(f"group {group!r} member {member!r} is not a MAC address or a known alias")


def _group_addresses(registry: LightRegistry, group: str, members: tuple[str, ...]) -> tuple[str, ...]:
    addresses: list[str] = []
    for member in members:
        address = _member_address(registry, group, member)
        if address not in addresses:
            addresses.append(address)
    return tuple(addresses)


def resolve_light(registry: LightRegistry, value: str) -> ResolvedLight:
    """Resolve a raw MAC address, an alias, or a group to MAC addresses."""

    if is_address(value):
        return ResolvedLight(value, (value.upper(),), is_group=False)
    if value in registry.aliases:
        return ResolvedLight(value, (registry.aliases[value],), is_group=False)
    if value in registry.groups:
        return ResolvedLight(value, _group_addresses(registry, value, registry.groups[value]), is_group=True)
    raise ConfigError(
        f"unknown light {value!r}; use a MAC address or define it with 'blemeshctl alias add' "
        "or 'blemeshctl group add'"
    )


def add_alias(name: str, address: str, *, force: bool = False) -> str:
    """Define (or with ``force`` redefine) an alias and return its MAC address."""

    validate_name(name, "alias name")
    address = normalize_address(address)
    registry = load_lights()
    if name in registry.groups:
        raise ConfigError(f"{name!r} is already a group name")
    if name in registry.aliases and not force:
        raise ConfigError(f"alias {name!r} already exists; pass --force to replace it")
    registry.aliases[name] = address
    save_lights(registry)
    return address


def remove_alias(name: str) -> None:
    """Remove an alias that no group refers to."""

    registry = load_lights()
    if name not in registry.aliases:
        raise ConfigError(f"no alias named {name!r}")
    users = sorted(group for group, members in registry.groups.items() if name in members)
    if users:
        raise ConfigError(f"alias {name!r} is used by group(s) {', '.join(users)}; update or remove them first")
    del registry.aliases[name]
    save_lights(registry)


def add_group(name: str, members: list[str], *, force: bool = False) -> tuple[str, ...]:
    """Define (or with ``force`` replace) a group and return its MAC addresses."""

    validate_name(name, "group name")
    if not members:
        raise ConfigError(f"group {name!r} needs at least one member")
    registry = load_lights()
    if name in registry.aliases:
        raise ConfigError(f"{name!r} is already an alias name")
    if name in registry.groups and not force:
        raise ConfigError(f"group {name!r} already exists; pass --force to replace its members")
    stored = tuple(member.upper() if is_address(member) else member for member in members)
    addresses = [_member_address(registry, name, member) for member in stored]
    if len(set(addresses)) != len(addresses):
        raise ConfigError(f"group {name!r} lists the same light more than once")
    registry.groups[name] = stored
    save_lights(registry)
    return tuple(addresses)


def remove_group(name: str) -> None:
    """Remove a group; its member aliases are kept."""

    registry = load_lights()
    if name not in registry.groups:
        raise ConfigError(f"no group named {name!r}")
    del registry.groups[name]
    save_lights(registry)


def scenes_directory() -> Path:
    """Return the directory that holds saved scene scripts."""

    return config_directory() / SCENES_DIRECTORY


def scene_path(name: str) -> Path:
    """Return the file for scene ``name``; the name check prevents path traversal."""

    return scenes_directory() / f"{validate_name(name, 'scene name')}{SCENE_SUFFIX}"


def scene_source(name: str) -> str:
    """Return the label used in scene error messages and command reports."""

    return f"scene {name}"


def list_scenes() -> list[str]:
    """Return saved scene names in sorted order."""

    directory = scenes_directory()
    try:
        paths = list(directory.iterdir())
    except FileNotFoundError:
        return []
    except OSError as exc:
        raise ConfigError(f"{directory}: could not list scenes: {exc}") from exc
    names = [
        path.name[: -len(SCENE_SUFFIX)]
        for path in paths
        if path.name.endswith(SCENE_SUFFIX) and path.is_file()
    ]
    return sorted(name for name in names if _NAME_PATTERN.fullmatch(name) and len(name) <= MAX_NAME_LENGTH)


def read_scene(name: str) -> str:
    """Return the saved text of scene ``name``."""

    path = scene_path(name)
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise ConfigError(f"no scene named {name!r}") from None
    except (OSError, UnicodeError) as exc:
        raise ConfigError(f"{path}: could not read scene: {exc}") from exc


def load_scene(name: str) -> tuple[ScriptStep, ...]:
    """Read and compile scene ``name`` before any command can be sent."""

    return parse_script(read_scene(name), scene_source(name))


def save_scene(name: str, text: str, *, force: bool = False) -> Path:
    """Validate ``text`` with the script parser, then save it as scene ``name``."""

    path = scene_path(name)
    parse_script(text, scene_source(name))
    if path.exists() and not force:
        raise ConfigError(f"scene {name!r} already exists; pass --force to replace it")
    _write_atomically(path, text if text.endswith("\n") else text + "\n")
    return path


def delete_scene(name: str) -> None:
    """Delete saved scene ``name``."""

    path = scene_path(name)
    try:
        path.unlink()
    except FileNotFoundError:
        raise ConfigError(f"no scene named {name!r}") from None
    except OSError as exc:
        raise ConfigError(f"{path}: could not delete scene: {exc}") from exc


def scene_text(
    *,
    on: bool = False,
    off: bool = False,
    color: str | None = None,
    white: int | None = None,
    brightness: int | None = None,
) -> str:
    """Return one-instruction scene text in the existing script syntax."""

    if sum((on, off, color is not None, white is not None)) != 1:
        raise ConfigError("choose exactly one of --on, --off, --color, or --white")
    if on or off:
        if brightness is not None:
            raise ConfigError("--brightness applies only to --color and --white")
        return "on\n" if on else "off\n"
    level = 100 if brightness is None else brightness
    if color is not None:
        red, green, blue = parse_rgb(color)
        return f"color {red:02x}{green:02x}{blue:02x} {level}\n"
    kelvin = WHITE_NEUTRAL_KELVIN if white is None else white
    return f"white {kelvin}K {level}\n"
