import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sokol_cleantv.backend import (
    Workshop, OperationError, identity, normalize_endpoint, package_state, protected_reason,
    write_json, KNOWN_HOMES, LAUNCHER, BYEDPI, preserve_choices_for_theme,
)

PROPS = {"ro.product.manufacturer": "Test", "ro.product.device": "testboard",
         "ro.product.model": "Test TV", "ro.serialno": "test-serial-1", "ro.build.fingerprint": "test/build"}


class FakeWorkshop(Workshop):
    def __init__(self, path):
        super().__init__(data_dir=path)
        self.endpoint = "192.168.1.2:5555"
        self.commands = []
        self.fail_on = None
        self.states = {"com.google.android.tvlauncher": 0, "com.netflix.ninja": 0, "android": 0}
        self.home = "com.google.android.tvlauncher/.MainActivity"
        self.props = dict(PROPS)
        self.device = {"identity": identity(PROPS, "android-id"), "model": "Test TV", "brand": "Test",
                       "device": "testboard", "fingerprint": "test/build", "home": self.home,
                       "sdk": 30, "can_install": True, "all_packages": list(self.states), "abi": "armeabi-v7a",
                       "packages": [{"package": "com.netflix.ninja", "blocked": "", "disabled": False}]}

    def shell(self, command, timeout=40):
        self.commands.append(command)
        if command == self.fail_on:
            raise OperationError("Simulated connection failure")
        if command == "getprop":
            return "\n".join(f"[{key}]: [{value}]" for key, value in self.props.items())
        if command == "settings get secure android_id":
            return "android-id"
        if command.startswith("settings list"):
            return "screen_brightness=100\nadb_enabled=1"
        if command.startswith("pm list packages"):
            return "\n".join("package:" + name for name, state in self.states.items()
                             if " -d" not in command or state in {2, 3, 4})
        if command.startswith("dumpsys package "):
            name = command.split()[-1]
            return f"Package [{name}]\n User 0: installed=true hidden=false enabled={self.states[name]}"
        if command.startswith("cmd package resolve-activity"):
            return self.home
        if command.startswith("cmd package query-activities"):
            return "1 activities found:\n" + self.home
        if command == "dumpsys tv_input":
            return "com.test.tv/.InputService"
        if command == "wm size":
            return "Physical size: 1920x1080"
        if command.startswith("pm enable --user 0 "):
            self.states[command.split()[-1]] = 1
            return "new state: enabled"
        if command.startswith("pm ") and "--user 0" in command:
            operation, name = command.split()[1], command.split()[-1]
            self.states[name] = {"default-state": 0, "enable": 1, "disable": 2,
                                 "disable-user": 3, "disable-until-used": 4}[operation]
            return "new state"
        if command.startswith("cmd package set-home-activity --user 0 "):
            self.home = command.split()[-1]
            return "Success"
        if command == "sync":
            return ""
        raise AssertionError("Unexpected fake command: " + command)


class SafetyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.service = FakeWorkshop(Path(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def test_endpoint_rejects_shell_syntax_and_public_hosts(self):
        for value in ("8.8.8.8", "127.0.0.1", "192.168.1.2;reboot", "https://192.168.1.2", "192.168.1.2:0"):
            with self.subTest(value=value), self.assertRaises(OperationError):
                normalize_endpoint(value)
        self.assertEqual(normalize_endpoint("192.168.1.2"), "192.168.1.2:5555")

    def test_core_packages_are_protected(self):
        for name in ("android", "com.android.systemui", "com.mediatek.tvinput", "com.google.android.gms",
                     "com.google.android.tungsten.setupwraith", "com.android.vending"):
            self.assertTrue(protected_reason(name), name)

    def test_partial_backup_never_becomes_complete(self):
        self.service.fail_on = "settings list secure"
        with self.assertRaises(OperationError):
            self.service.save_backup()
        files = list(Path(self.temp.name).glob("backups/*/manifest.json"))
        self.assertEqual(len(files), 1)
        self.assertFalse(json.loads(files[0].read_text())["complete"])
        self.assertFalse(any(command.startswith("pm disable") for command in self.service.commands))

    def test_changed_tv_rejected_before_mutation(self):
        self.service.props["ro.serialno"] = "another-tv"
        with self.assertRaises(OperationError):
            self.service.apply(["com.netflix.ninja"], False, False, [])
        self.assertFalse(any(command.startswith("pm disable") for command in self.service.commands))

    def test_restore_preserves_default_and_prior_disabled_states(self):
        folder = Path(self.service.save_backup())
        data = self.service._manifest(folder)
        data["changed"] = {"com.netflix.ninja": 0, BYEDPI: None, "com.test.disabled": 3}
        write_json(folder / "manifest.json", data)
        self.service.states.update({"com.netflix.ninja": 3, BYEDPI: 0, "com.test.disabled": 1})
        self.service.restore(folder)
        self.assertEqual(self.service.states["com.netflix.ninja"], 0)
        self.assertEqual(self.service.states[BYEDPI], 3)
        self.assertEqual(self.service.states["com.test.disabled"], 3)
        self.assertEqual(self.service.home, "com.google.android.tvlauncher/.MainActivity")

    def test_incomplete_restore_rejected(self):
        folder = Path(self.temp.name) / "incomplete"
        write_json(folder / "manifest.json", {"schema": 1, "complete": False})
        with self.assertRaises(OperationError):
            self.service.restore(folder)
        self.assertEqual(self.service.commands, [])

    def test_backup_for_another_tv_rejected(self):
        folder = Path(self.service.save_backup())
        self.service.props["ro.serialno"] = "second-tv"
        self.service.commands.clear()
        with self.assertRaises(OperationError):
            self.service.restore(folder)
        self.assertFalse(any(command.startswith("pm ") for command in self.service.commands))

    def test_journal_written_before_any_script(self):
        def stop_at_first_script(relative, args, folder, timeout=180):
            data = self.service._manifest(folder)
            self.assertEqual(data["changed"]["com.netflix.ninja"], 0)
            self.assertEqual(data["operations"][0]["state"], "started")
            raise OperationError("Simulated failure before script")
        self.service._script = stop_at_first_script
        with self.assertRaises(OperationError):
            self.service.apply(["com.netflix.ninja"], False, False, [])
        self.assertIsNotNone(self.service.backup_path)

    def test_tv_input_role_blocks_cleanup(self):
        original = self.service.shell
        def guarded(command, timeout=40):
            result = original(command, timeout)
            return result + "\nandroid.permission.TV_INPUT_HARDWARE" if command.startswith("dumpsys package") else result
        self.service.shell = guarded
        with self.assertRaises(OperationError):
            self.service.apply(["com.netflix.ninja"], False, False, [])
        self.assertFalse(any(command.startswith("pm disable") for command in self.service.commands))

    def test_package_state_requires_user_zero(self):
        with self.assertRaises(OperationError):
            package_state("User 10: installed=true enabled=0")

    def test_corrupted_backup_rejected(self):
        folder = Path(self.service.save_backup())
        (folder / "getprop.txt").write_text("changed", encoding="utf-8")
        with self.assertRaises(OperationError):
            self.service.restore(folder)

    def test_concurrent_operation_on_same_tv_rejected(self):
        other = FakeWorkshop(Path(self.temp.name))
        with self.service.operation_lock():
            with self.assertRaises(OperationError):
                other.save_backup()

    def test_wallpaper_choices_survive_a_new_theme_revision(self):
        saved = {"launcher_settings": {"theme_signature": {"type": "String", "value": "old"},
                                      "wallpaper_choice": {"type": "String", "value": "mountains.jpg"}}}
        current = {"launcher_settings": {"theme_signature": {"type": "String", "value": "new"}}}
        merged = preserve_choices_for_theme(saved, current)
        self.assertEqual(merged["launcher_settings"]["theme_signature"]["value"], "new")
        self.assertEqual(merged["launcher_settings"]["wallpaper_choice"]["value"], "mountains.jpg")
        self.assertEqual(saved["launcher_settings"]["theme_signature"]["value"], "old")


if __name__ == "__main__":
    unittest.main()
