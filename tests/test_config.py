from __future__ import annotations

import json
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from blemeshctl import config
from blemeshctl.config import ConfigError
from blemeshctl.protocol import TelinkProtocolError
from blemeshctl.script import LightCommand, ScriptError, parse_script


DESK = "A4:C1:38:93:1B:72"
SHELF = "00:11:22:33:44:55"


class ConfigDirectoryMixin(unittest.TestCase):
    """Point the configuration directory at a fresh temporary directory."""

    def setUp(self) -> None:
        super().setUp()
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.config_home = Path(temporary.name) / "config"
        environment = patch.dict(os.environ, {"XDG_CONFIG_HOME": str(self.config_home)})
        environment.start()
        self.addCleanup(environment.stop)
        self.directory = self.config_home / "blemeshctl"

    def write_lights(self, data: object) -> None:
        self.directory.mkdir(parents=True, exist_ok=True)
        (self.directory / "lights.json").write_text(json.dumps(data), encoding="utf-8")


class LightRegistryTests(ConfigDirectoryMixin):
    def test_missing_config_is_empty_and_creates_nothing(self) -> None:
        registry = config.load_lights()

        self.assertEqual(registry.aliases, {})
        self.assertEqual(registry.groups, {})
        self.assertEqual(config.list_scenes(), [])
        self.assertFalse(self.config_home.exists())

    def test_relative_xdg_config_home_is_ignored(self) -> None:
        with patch.dict(os.environ, {"XDG_CONFIG_HOME": "relative/config"}):
            self.assertEqual(config.config_directory(), Path.home() / ".config" / "blemeshctl")

    def test_alias_add_normalises_mac_and_writes_private_files(self) -> None:
        self.assertEqual(config.add_alias("desk", DESK.lower()), DESK)

        path = self.directory / "lights.json"
        self.assertEqual(stat.S_IMODE(self.directory.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(json.loads(path.read_text()), {"aliases": {"desk": DESK}, "groups": {}})
        self.assertEqual([entry.name for entry in self.directory.iterdir()], ["lights.json"])

    def test_alias_add_rejects_invalid_names_and_addresses(self) -> None:
        for name in ("", "-desk", "desk lamp", "../desk", "desk\n", "a" * 65, "AA:BB:CC:DD:EE:FF"):
            with self.subTest(name=name), self.assertRaisesRegex(ConfigError, "invalid alias name"):
                config.add_alias(name, DESK)
        for address in ("A4:C1:38:93:1B", "A4-C1-38-93-1B-72", "A4:C1:38:93:1B:7G", f"{DESK}\n"):
            with self.subTest(address=address), self.assertRaisesRegex(ConfigError, "invalid Bluetooth MAC"):
                config.add_alias("desk", address)
        self.assertFalse(self.config_home.exists())

    def test_alias_add_refuses_to_replace_without_force(self) -> None:
        config.add_alias("desk", DESK)

        with self.assertRaisesRegex(ConfigError, "already exists; pass --force"):
            config.add_alias("desk", SHELF)
        self.assertEqual(config.load_lights().aliases["desk"], DESK)

        config.add_alias("desk", SHELF, force=True)
        self.assertEqual(config.load_lights().aliases["desk"], SHELF)

    def test_alias_and_group_names_cannot_collide(self) -> None:
        config.add_alias("desk", DESK)
        config.add_group("office", ["desk"])

        with self.assertRaisesRegex(ConfigError, "already a group name"):
            config.add_alias("office", SHELF, force=True)
        with self.assertRaisesRegex(ConfigError, "already an alias name"):
            config.add_group("desk", [SHELF], force=True)

    def test_alias_remove_refuses_an_alias_used_by_a_group(self) -> None:
        config.add_alias("desk", DESK)
        config.add_group("office", ["desk", SHELF])

        with self.assertRaisesRegex(ConfigError, "used by group\\(s\\) office"):
            config.remove_alias("desk")
        config.remove_group("office")
        config.remove_alias("desk")

        self.assertEqual(config.load_lights().aliases, {})
        with self.assertRaisesRegex(ConfigError, "no alias named 'desk'"):
            config.remove_alias("desk")
        with self.assertRaisesRegex(ConfigError, "no group named 'office'"):
            config.remove_group("office")

    def test_group_members_must_be_macs_or_existing_aliases(self) -> None:
        config.add_alias("desk", DESK)
        config.add_group("office", ["desk"])

        with self.assertRaisesRegex(ConfigError, "'shelf' is not a MAC address or a known alias"):
            config.add_group("lounge", ["shelf"])
        with self.assertRaisesRegex(ConfigError, "cannot contain itself"):
            config.add_group("lounge", ["lounge"])
        with self.assertRaisesRegex(ConfigError, "cannot contain another group"):
            config.add_group("lounge", ["office"])
        with self.assertRaisesRegex(ConfigError, "same light more than once"):
            config.add_group("lounge", ["desk", DESK.lower()])
        self.assertNotIn("lounge", config.load_lights().groups)

    def test_group_add_refuses_to_replace_without_force(self) -> None:
        config.add_group("office", [DESK.lower()])

        with self.assertRaisesRegex(ConfigError, "already exists; pass --force"):
            config.add_group("office", [SHELF])
        self.assertEqual(config.load_lights().groups["office"], (DESK,))

        self.assertEqual(config.add_group("office", [SHELF, DESK], force=True), (SHELF, DESK))
        self.assertEqual(config.load_lights().groups["office"], (SHELF, DESK))

    def test_resolve_light_accepts_macs_aliases_and_groups(self) -> None:
        config.add_alias("desk", DESK)
        config.add_group("office", ["desk", SHELF])
        registry = config.load_lights()

        self.assertEqual(
            config.resolve_light(registry, SHELF.lower()),
            config.ResolvedLight(SHELF.lower(), (SHELF,), is_group=False),
        )
        self.assertEqual(
            config.resolve_light(registry, "desk"), config.ResolvedLight("desk", (DESK,), is_group=False)
        )
        self.assertEqual(
            config.resolve_light(registry, "office"),
            config.ResolvedLight("office", (DESK, SHELF), is_group=True),
        )
        with self.assertRaisesRegex(ConfigError, "unknown light 'lounge'"):
            config.resolve_light(registry, "lounge")

    def test_groups_resolve_aliases_at_use_time(self) -> None:
        config.add_alias("desk", DESK)
        config.add_group("office", ["desk"])
        config.add_alias("desk", SHELF, force=True)

        self.assertEqual(config.resolve_light(config.load_lights(), "office").addresses, (SHELF,))

    def test_resolution_rejects_hand_edited_nested_groups_and_duplicates(self) -> None:
        self.write_lights(
            {
                "aliases": {"desk": DESK},
                "groups": {"office": ["desk", DESK, "lounge"], "lounge": ["office"], "loop": ["loop"]},
            }
        )
        registry = config.load_lights()

        with self.assertRaisesRegex(ConfigError, "cannot contain another group \\('lounge'\\)"):
            config.resolve_light(registry, "office")
        with self.assertRaisesRegex(ConfigError, "cannot contain itself"):
            config.resolve_light(registry, "loop")
        self.write_lights({"aliases": {"desk": DESK}, "groups": {"office": ["desk", DESK.lower()]}})
        self.assertEqual(config.resolve_light(config.load_lights(), "office").addresses, (DESK,))

    def test_corrupt_config_raises_a_one_line_config_error(self) -> None:
        cases = [
            ("{not json", "invalid JSON"),
            ("[]", "expected a JSON object"),
            (json.dumps({"aliases": []}), "must be JSON objects"),
            (json.dumps({"aliases": {"desk": "nope"}}), "invalid Bluetooth MAC"),
            (json.dumps({"aliases": {"desk": 1}}), "must map to a MAC address string"),
            (json.dumps({"aliases": {"bad name": DESK}}), "invalid alias name"),
            (json.dumps({"groups": {"office": []}}), "non-empty list of strings"),
            (json.dumps({"aliases": {"desk": DESK}, "groups": {"desk": [DESK]}}), "both an alias and a group"),
        ]
        self.directory.mkdir(parents=True)
        for text, message in cases:
            with self.subTest(text=text):
                (self.directory / "lights.json").write_text(text, encoding="utf-8")
                with self.assertRaisesRegex(ConfigError, message) as raised:
                    config.load_lights()
                self.assertIn("lights.json", str(raised.exception))
                self.assertNotIn("\n", str(raised.exception))

    def test_failed_write_keeps_the_previous_file_and_no_temporary_file(self) -> None:
        config.add_alias("desk", DESK)
        with patch("blemeshctl.config.os.replace", side_effect=OSError("disk full")):
            with self.assertRaisesRegex(ConfigError, "could not write: disk full"):
                config.add_alias("shelf", SHELF)

        self.assertEqual(config.load_lights().aliases, {"desk": DESK})
        self.assertEqual([entry.name for entry in self.directory.iterdir()], ["lights.json"])


class SceneStorageTests(ConfigDirectoryMixin):
    def test_scene_text_uses_the_existing_script_syntax(self) -> None:
        cases = {
            "on\n": config.scene_text(on=True),
            "off\n": config.scene_text(off=True),
            "color 00ff00 100\n": config.scene_text(color="#00FF00"),
            "color ff8800 40\n": config.scene_text(color="ff8800", brightness=40),
            "white 4600K 100\n": config.scene_text(white=4600),
            "white 2700K 60\n": config.scene_text(white=2700, brightness=60),
        }
        for expected, text in cases.items():
            with self.subTest(text=text):
                self.assertEqual(text, expected)
                program = parse_script(text)
                self.assertEqual(len(program), 1)
                self.assertIsInstance(program[0], LightCommand)

    def test_scene_text_rejects_invalid_combinations(self) -> None:
        with self.assertRaisesRegex(ConfigError, "--brightness applies only"):
            config.scene_text(on=True, brightness=50)
        with self.assertRaisesRegex(ConfigError, "exactly one"):
            config.scene_text()
        with self.assertRaises(TelinkProtocolError):
            config.scene_text(color="green")

    def test_save_scene_validates_and_refuses_to_overwrite_without_force(self) -> None:
        path = config.save_scene("evening", "on\ncolor ff8800 40")

        self.assertEqual(path, self.directory / "scenes" / "evening.blemesh")
        self.assertEqual(stat.S_IMODE(path.parent.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(config.read_scene("evening"), "on\ncolor ff8800 40\n")
        with self.assertRaisesRegex(ConfigError, "scene 'evening' already exists; pass --force"):
            config.save_scene("evening", "off\n")
        self.assertEqual(config.read_scene("evening"), "on\ncolor ff8800 40\n")

        config.save_scene("evening", "off\n", force=True)
        self.assertEqual(config.read_scene("evening"), "off\n")

    def test_invalid_scene_text_is_not_saved(self) -> None:
        with self.assertRaisesRegex(ScriptError, "scene evening:2: unknown instruction"):
            config.save_scene("evening", "on\nflash\n")
        with self.assertRaisesRegex(ScriptError, "brightness"):
            config.save_scene("evening", config.scene_text(white=2700, brightness=0))
        self.assertFalse(self.config_home.exists())

    def test_scene_names_cannot_leave_the_scene_directory(self) -> None:
        for name in ("../lights", "a/b", ".hidden", ""):
            with self.subTest(name=name), self.assertRaisesRegex(ConfigError, "invalid scene name"):
                config.save_scene(name, "on\n")

    def test_list_load_and_delete_scenes(self) -> None:
        config.save_scene("night", "off\n")
        config.save_scene("evening", "on\n")
        (self.directory / "scenes" / "notes.txt").write_text("ignored", encoding="utf-8")

        self.assertEqual(config.list_scenes(), ["evening", "night"])
        self.assertEqual(len(config.load_scene("evening")), 1)
        config.delete_scene("night")
        self.assertEqual(config.list_scenes(), ["evening"])
        with self.assertRaisesRegex(ConfigError, "no scene named 'night'"):
            config.delete_scene("night")
        with self.assertRaisesRegex(ConfigError, "no scene named 'night'"):
            config.load_scene("night")

    def test_corrupt_saved_scene_is_reported_against_the_scene_name(self) -> None:
        (self.directory / "scenes").mkdir(parents=True)
        (self.directory / "scenes" / "broken.blemesh").write_text("on\nloop:\n  on\n", encoding="utf-8")

        with self.assertRaisesRegex(ScriptError, "scene broken:2: an endless loop needs"):
            config.load_scene("broken")


if __name__ == "__main__":
    unittest.main()
