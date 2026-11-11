from __future__ import annotations

import hashlib
import base64
import ipaddress
import json
import os
import re
import shutil
import socket
import subprocess
import tempfile
import uuid
from contextlib import contextmanager
from functools import wraps
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "vendor" / "cleanyourtv"
PYTHON = VENDOR / "tools" / "venv" / "Scripts" / "python.exe"
ADB = ROOT / "vendor" / "platform-tools" / "adb.exe"
PACKAGE = re.compile(r"[A-Za-z_][\w]*(?:\.[A-Za-z0-9_]+)+", re.ASCII)
COMPONENT = re.compile(r"[A-Za-z_][\w.]*/[\w.$]+", re.ASCII)
LAUNCHER = "com.gathin.tvhome"
BYEDPI = "io.github.romanvht.byedpi"
KNOWN_HOMES = {
    "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
    "com.google.android.leanbacklauncher", "com.mitv.tvhome",
}
PROTECTED = {
    LAUNCHER, BYEDPI, "android", "com.android.vending", "com.google.android.gms",
    "com.google.android.gsf", "com.google.android.katniss", "com.google.android.tts",
    "com.google.android.apps.mediashell", "com.google.android.tv.remote.service",
    "com.google.android.tungsten.setupwraith", "com.cltv.mal", *KNOWN_HOMES,
}
OPTIONAL = {
    "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv",
    "com.google.android.youtube.tvmusic", "com.google.android.play.games", "com.google.android.music",
    "com.google.android.videos", "com.google.android.feedback", "com.google.android.tvrecommendations",
    "ru.haier.evo", "com.haier.apps.evoSmart", "com.haier.apps.shahid", "tv.anoki.acr.controller",
}
APP_NAMES = {
    "com.google.android.youtube.tv": "YouTube", "com.vk.tv": "VK Видео",
    "ru.rutube.app": "Rutube", "ru.kinopoisk.tv": "Кинопоиск", "mobi.zona": "Зона",
    "com.netflix.ninja": "Netflix", "com.amazon.amazonvideo.livingroom": "Prime Video",
    "com.google.android.youtube.tvmusic": "YouTube Music", "com.google.android.play.games": "Play Игры",
    "com.google.android.music": "Play Музыка", "com.google.android.videos": "Play Фильмы",
    "com.android.vending": "Google Play", LAUNCHER: "Sokol CleanTV Home", BYEDPI: "ByeByeDPI",
}


class OperationError(RuntimeError):
    pass


def exclusive(function):
    @wraps(function)
    def guarded(self, *args, **kwargs):
        with self.operation_lock():
            return function(self, *args, **kwargs)
    return guarded


def write_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temporary, path)


def normalize_endpoint(value: str) -> str:
    parts = value.strip().split(":")
    if len(parts) > 2:
        raise OperationError("Введите IPv4-адрес телевизора и, при необходимости, порт.")
    try:
        address = ipaddress.IPv4Address(parts[0])
        port = int(parts[1]) if len(parts) == 2 else 5555
    except ValueError as error:
        raise OperationError("Некорректный IP-адрес или порт.") from error
    local_ranges = (ipaddress.ip_network("10.0.0.0/8"), ipaddress.ip_network("172.16.0.0/12"),
                    ipaddress.ip_network("192.168.0.0/16"), ipaddress.ip_network("169.254.0.0/16"))
    if not any(address in network for network in local_ranges) or not 1 <= port <= 65535:
        raise OperationError("Используйте локальный IPv4-адрес телевизора и порт от 1 до 65535.")
    return f"{address}:{port}"


def package_set(text):
    return {line.split(":", 1)[1].rsplit("=", 1)[-1].strip()
            for line in text.splitlines() if line.startswith("package:")}


def properties(text):
    return dict(re.findall(r"^\[([^\]]+)\]: \[(.*)\]$", text, re.MULTILINE))


def home_component(text):
    for line in reversed(text.splitlines()):
        if COMPONENT.fullmatch(line.strip()):
            return line.strip()
    raise OperationError("Телевизор не сообщил основной главный экран. Установка остановлена.")


def identity(props, android_id):
    serial = props.get("ro.serialno") or props.get("ro.boot.serialno") or android_id.strip()
    if not serial or serial in {"unknown", "null", "0123456789ABCDEF"}:
        raise OperationError("Не удалось получить устойчивый идентификатор ТВ для безопасного отката.")
    material = "|".join((props.get("ro.product.manufacturer", ""), props.get("ro.product.device", ""), serial))
    return hashlib.sha256(material.encode()).hexdigest()


def package_state(dump):
    match = re.search(r"User 0:.*?installed=(true|false).*?enabled=(\d+)", dump, re.DOTALL)
    if not match or match[1] != "true" or match[2] not in {"0", "1", "2", "3", "4"}:
        raise OperationError("Не удалось прочитать состояние приложения для пользователя 0.")
    return int(match[2])


def preserve_choices_for_theme(saved, current):
    restored = json.loads(json.dumps(saved))
    signature = current.get("launcher_settings", {}).get("theme_signature")
    if signature:
        restored.setdefault("launcher_settings", {})["theme_signature"] = signature
    return restored


def protected_reason(name):
    if name in PROTECTED:
        return "Главный экран или важная функция ТВ"
    if name.startswith(("com.android.", "android.", "com.mediatek.", "com.cvte.", "com.mstar.")):
        return "Системный компонент или оборудование"
    if name.startswith("com.google.") and name not in OPTIONAL:
        return "Служба Google или компонент Android"
    if any(word in name.lower() for word in ("factory", "setup", "bluetooth", "keyboard", "inputmethod", "settings", "ota", "updat")):
        return "Настройка, оборудование или обновление прошивки"
    return ""


class Workshop:
    def __init__(self, log=None, data_dir=None):
        self.log = log or (lambda text: None)
        self.data_dir = Path(data_dir or os.environ.get("SOKOL_CLEANTV_DATA") or Path(os.environ.get("LOCALAPPDATA", ROOT)) / "Sokol_CleanTV")
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.endpoint = ""
        self.device = None
        self.backup_path = None
        self._lock_depth = 0

    @contextmanager
    def operation_lock(self):
        if self._lock_depth:
            yield
            return
        if not self.device:
            raise OperationError("Сначала проверьте подключённый телевизор.")
        folder = self.data_dir / "locks"
        folder.mkdir(exist_ok=True)
        path = folder / (self.device["identity"] + ".lock")
        with path.open("a+b") as handle:
            if path.stat().st_size == 0:
                handle.write(b"0")
                handle.flush()
            handle.seek(0)
            try:
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as error:
                raise OperationError("Другой экземпляр программы уже работает с этим телевизором.") from error
            self._lock_depth = 1
            try:
                yield
            finally:
                self._lock_depth = 0
                handle.seek(0)
                if os.name == "nt":
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)

    def run(self, args, timeout=40, env=None, cwd=None, check=True, stdin=None):
        try:
            result = subprocess.run([str(arg) for arg in args], input=stdin, capture_output=True,
                                    text=True, encoding="utf-8", errors="replace", timeout=timeout,
                                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), env=env, cwd=cwd)
        except subprocess.TimeoutExpired as error:
            raise OperationError("Операция превысила время ожидания. Проверьте ТВ и сеть. Результат мог примениться частично.") from error
        except OSError as error:
            raise OperationError(f"Не удалось запустить компонент программы. {error}") from error
        output = (result.stdout + "\n" + result.stderr).strip()
        if check and result.returncode:
            raise OperationError(output[-5000:] or f"Команда завершилась с кодом {result.returncode}.")
        return output

    def adb(self, *args, timeout=40):
        if not self.endpoint:
            raise OperationError("Сначала подключите телевизор.")
        return self.run([ADB, "-s", self.endpoint, *args], timeout=timeout)

    def shell(self, command, timeout=40):
        return self.adb("shell", command, timeout=timeout)

    def discover(self):
        text = self.run([ADB, "mdns", "services"], timeout=12)
        found = []
        for line in text.splitlines():
            if "_adb._tcp" in line or "_adb-tls-connect._tcp" in line:
                candidate = line.split()[-1]
                try:
                    found.append(normalize_endpoint(candidate))
                except OperationError:
                    continue
        return sorted(set(found))

    def pair(self, endpoint, code):
        target = normalize_endpoint(endpoint)
        if not re.fullmatch(r"\d{6}", code.strip()):
            raise OperationError("Код сопряжения должен содержать 6 цифр.")
        output = self.run([ADB, "pair", target], stdin=code.strip() + "\n", timeout=25)
        if "Successfully paired" not in output:
            raise OperationError("Сопряжение не подтверждено. Проверьте порт и новый код на ТВ.")
        return "Сопряжение выполнено. Подключитесь к порту подключения с основного экрана беспроводной отладки."

    def inspect(self, endpoint):
        self.device = None
        self.backup_path = None
        self.endpoint = normalize_endpoint(endpoint)
        self.log("Подключение по ADB…")
        output = self.run([ADB, "connect", self.endpoint], timeout=15)
        state = self.adb("get-state", timeout=10)
        if state.strip() != "device":
            raise OperationError("Подтвердите запрос отладки на телевизоре и повторите подключение.\n" + output)
        self.log("Чтение модели, возможностей системы и списка приложений…")
        raw_props = self.shell("getprop")
        props = properties(raw_props)
        try:
            sdk = int(props.get("ro.build.version.sdk", "0"))
        except ValueError:
            sdk = 0
        if not sdk:
            raise OperationError("Устройство не определено как Android. Поддерживаются Android TV и Google TV.")
        features = self.shell("pm list features")
        tv = "android.software.leanback" in features or "android.hardware.type.television" in features
        all_packages = package_set(self.shell("pm list packages --user 0"))
        if not all_packages or "android" not in all_packages:
            raise OperationError("Не удалось прочитать пакеты пользователя 0.")
        disabled = package_set(self.shell("pm list packages -d --user 0"))
        third = package_set(self.shell("pm list packages -3 --user 0"))
        leanback = self.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER")
        visible = {match[0] for match in re.findall(r"([\w.]+)/(\.?[\w.$]+)", leanback)}
        home = home_component(self.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"))
        device_id = identity(props, self.shell("settings get secure android_id"))
        packages = []
        for name in sorted(visible | (all_packages & OPTIONAL)):
            if not PACKAGE.fullmatch(name):
                continue
            reason = protected_reason(name)
            if not reason and name not in OPTIONAL and name not in third:
                reason = "Неизвестное предустановленное приложение"
            packages.append({"package": name, "label": APP_NAMES.get(name, name), "blocked": reason,
                             "disabled": name in disabled, "third_party": name in third})
        self.device = {
            "identity": device_id, "model": props.get("ro.product.model", "Android TV"),
            "brand": props.get("ro.product.manufacturer", ""), "device": props.get("ro.product.device", ""),
            "android": props.get("ro.build.version.release", ""), "sdk": sdk,
            "abi": props.get("ro.product.cpu.abi", ""), "fingerprint": props.get("ro.build.fingerprint", ""),
            "home": home, "television": tv, "can_install": tv and sdk >= 26 and home.split("/")[0] != "android",
            "packages": packages, "all_packages": sorted(all_packages), "props": props,
        }
        self.log("Проверка завершена. Возможность установки APK подтверждается при самой установке.")
        return self.device

    def assert_identity(self, expected=None):
        target = expected or self.device
        if not target:
            raise OperationError("Сначала выполните проверку телевизора.")
        props = properties(self.shell("getprop"))
        actual = identity(props, self.shell("settings get secure android_id"))
        if actual != target["identity"]:
            raise OperationError("Подключён другой телевизор. Изменения и восстановление остановлены.")
        if props.get("ro.build.fingerprint", "") != target["fingerprint"]:
            raise OperationError("Прошивка изменилась после проверки. Подключите телевизор заново.")

    @exclusive
    def save_backup(self):
        self.assert_identity()
        folder = self.data_dir / "backups" / (datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6])
        folder.mkdir(parents=True)
        manifest = {"schema": 1, "complete": False, "device": {key: self.device[key] for key in
                    ("identity", "model", "brand", "device", "fingerprint", "home")},
                    "created": datetime.now(timezone.utc).isoformat(), "changed": {}, "operations": []}
        write_json(folder / "manifest.json", manifest)
        queries = {
            "packages.txt": "pm list packages -f --user 0", "disabled.txt": "pm list packages -d --user 0",
            "getprop.txt": "getprop", "settings-system.txt": "settings list system",
            "settings-secure.txt": "settings list secure", "settings-global.txt": "settings list global",
            "home.txt": "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
            "home-candidates.txt": "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
            "inputs.txt": "dumpsys tv_input", "display.txt": "wm size",
        }
        for filename, command in queries.items():
            self.log("Сохранение «" + filename + "»…")
            text = self.shell(command)
            if (not text.strip() and filename != "disabled.txt") or re.search(r"(?i)permission denial|unknown command|error:|dump timeout|can't find service", text):
                raise OperationError(f"Резервная копия неполная. Не удалось сохранить {filename}. Изменения заблокированы.")
            (folder / filename).write_text(text, encoding="utf-8")
        if home_component((folder / "home.txt").read_text(encoding="utf-8")) != self.device["home"]:
            raise OperationError("Главный экран изменился во время копирования. Повторите проверку ТВ.")
        self.assert_identity()
        if self.device["home"].startswith(LAUNCHER + "/"):
            self.snapshot_launcher(folder)
        manifest["complete"] = True
        manifest["files"] = {path.relative_to(folder).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                             for path in folder.rglob("*") if path.is_file() and path.name != "manifest.json"}
        write_json(folder / "manifest.json", manifest)
        self.backup_path = folder
        self.log("Резервная копия сохранена. Личные данные приложений и разделы прошивки в неё не входят.")
        return str(folder)

    def _manifest(self, folder):
        path = Path(folder) / "manifest.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("schema") != 1 or data.get("complete") is not True:
            raise OperationError("Эта копия не завершена или имеет неподдерживаемый формат.")
        root = Path(folder).resolve()
        for name, expected in data.get("files", {}).items():
            file = (root / name).resolve()
            if not file.is_relative_to(root) or not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != expected:
                raise OperationError("Файлы копии повреждены или отсутствуют. " + name)
        return data

    def record_before(self, folder, packages):
        manifest = self._manifest(folder)
        for name in packages:
            if not PACKAGE.fullmatch(name):
                raise OperationError("Некорректное имя пакета.")
            if name in manifest["changed"]:
                continue
            installed = package_set(self.shell("pm list packages --user 0"))
            manifest["changed"][name] = package_state(self.shell("dumpsys package " + name)) if name in installed else None
            write_json(Path(folder) / "manifest.json", manifest)
        return manifest

    def _script(self, relative, args, folder, timeout=180):
        environment = os.environ.copy()
        environment.update({"TV_HOST": self.endpoint, "TV_ADB_BINARY": str(ADB), "USERPROFILE": str(folder),
                            "PYTHONIOENCODING": "utf-8", "SKIP_BUILD": "1"})
        for key in ("FORCE_HOME", "FORCE_SETUPWRAITH", "TVINPUT_ALLOW", "HANDLERS_ALLOW"):
            environment.pop(key, None)
        output = self.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                           VENDOR / relative, *args], timeout=timeout, env=environment, cwd=VENDOR)
        self.log(output)
        return output

    def preflight_disable(self, names):
        available = {item["package"]: item for item in self.device["packages"]}
        for name in names:
            item = available.get(name)
            if not item or item["blocked"] or protected_reason(name):
                raise OperationError("Пакет защищён или отсутствует в проверенном списке. " + name)
            dump = self.shell("dumpsys package " + name)
            package_state(dump)
            if re.search(r"TV_INPUT_HARDWARE|android\.media\.tv\.TvInputService|android\.intent\.category\.HOME|android\.net\.VpnService", dump):
                raise OperationError("У приложения есть роль ТВ-входа, главного экрана или VPN. Автоматическое отключение остановлено. " + name)

    @exclusive
    def apply(self, names, install_launcher, install_byedpi, wallpaper_paths):
        self.assert_identity()
        if not self.device["can_install"]:
            raise OperationError("Для этого сценария нужен Android TV / Google TV версии 8.0 или новее.")
        if not (names or install_launcher or install_byedpi or wallpaper_paths):
            raise OperationError("Выберите хотя бы одно действие.")
        if wallpaper_paths and not install_launcher and LAUNCHER not in self.device["all_packages"]:
            raise OperationError("Для обоев сначала установите главный экран Sokol CleanTV Home.")
        self.preflight_disable(names)
        folder = Path(self.save_backup())
        # Record every possible change before the first mutation, including a partial installer.
        tracked = list(names)
        if install_launcher:
            tracked += [LAUNCHER, *KNOWN_HOMES]
        if install_byedpi:
            tracked.append(BYEDPI)
        self.record_before(folder, tracked)
        result = self._manifest(folder)
        result["operations"].append({"state": "started", "packages": names,
                                     "launcher": install_launcher, "byedpi": install_byedpi})
        write_json(folder / "manifest.json", result)
        if install_launcher:
            self.assert_identity()
            self.log("Установка главного экрана. Проверки и откат выполняет установщик CleanYourTV…")
            args = ["-TvHost", self.endpoint]
            if any(name.startswith("com.cvte.") for name in self.device["all_packages"]):
                args += ["-StockLauncher", "none"]
            self._script("launcher/build-and-install.ps1", args, folder, timeout=300)
            self.shell("input keyevent KEYCODE_HOME")
            focus = self.shell("dumpsys activity activities")
            if not re.search(r"(?:mFocusedApp|mResumedActivity|topResumedActivity):?[^\n]*com\.gathin\.tvhome/", focus):
                raise OperationError("Не подтверждено отображение нового Home. Используйте восстановление из созданной копии.")
        if install_byedpi:
            self.assert_identity()
            self.log("Установка подписанного ByeByeDPI…")
            assets = json.loads((ROOT / "resources" / "assets.json").read_text(encoding="utf-8"))
            abi = self.device["abi"]
            apk_info = assets["byedpi"].get(abi)
            if not apk_info:
                raise OperationError("В этой сборке нет ByeByeDPI для архитектуры " + abi)
            apk = ROOT / "resources" / apk_info["file"]
            if hashlib.sha256(apk.read_bytes()).hexdigest() != apk_info["sha256"]:
                raise OperationError("Контрольная сумма APK ByeByeDPI не совпала.")
            output = self.adb("install", "-r", "--user", "0", apk, timeout=150)
            if "Success" not in output:
                raise OperationError("Телевизор не подтвердил установку ByeByeDPI. " + output)
        for index in range(0, len(names), 4):
            batch = names[index:index + 4]
            self.assert_identity()
            list_path = folder / f"disable-{index // 4 + 1}.txt"
            list_path.write_text("\n".join(batch) + "\n", encoding="ascii")
            self.log("Отключение выбранной группы приложений…")
            self._script("tools/disable.ps1", ["-TvHost", self.endpoint, "-List", str(list_path)], folder)
            disabled = package_set(self.shell("pm list packages -d --user 0"))
            if not set(batch) <= disabled:
                raise OperationError("Не все выбранные приложения отключены. Сверьте журнал и используйте откат.")
            self.screenshot(folder / f"after-{index // 4 + 1}.png")
        installed = package_set(self.shell("pm list packages --user 0"))
        if LAUNCHER in installed and (install_launcher or wallpaper_paths or install_byedpi):
            self.log("Применение оформления и обоев…")
            self._apply_theme(folder, wallpaper_paths, BYEDPI in installed)
        self.assert_identity()
        self.shell("sync")
        result = self._manifest(folder)
        result["operations"].append({"state": "applied", "time": datetime.now(timezone.utc).isoformat()})
        write_json(folder / "manifest.json", result)
        self.log("Изменения применены. Проверьте физические кнопки пульта, HDMI и воспроизведение, затем выполните проверку после перезагрузки.")
        return str(folder)

    def _apply_theme(self, folder, wallpapers, byedpi):
        theme = Path(folder) / "theme"
        theme.mkdir(exist_ok=True)
        previous_theme = Path(folder) / "launcher-files"
        if previous_theme.is_dir():
            shutil.copytree(previous_theme, theme, dirs_exist_ok=True)
        images = theme / "wallpapers"
        images.mkdir(exist_ok=True)
        old_config = theme / "config.json"
        config = json.loads(old_config.read_text(encoding="utf-8")) if old_config.is_file() else {"wallpaper": False}
        config.update({"app_library": True, "app_library_selection": True,
                       "library_button": True, "vpn_app": BYEDPI if byedpi else "", "vpn_pill": byedpi})
        for number, source in enumerate(wallpapers, 1):
            file = Path(source)
            if file.suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp"} or not file.is_file():
                raise OperationError("Поддерживаются JPG, PNG и WebP.")
            if file.stat().st_size > 20 * 1024 * 1024:
                raise OperationError("Обои должны быть меньше 20 МБ каждый.")
            digest = hashlib.sha256(file.read_bytes()).hexdigest()[:12]
            shutil.copy2(file, images / f"wallpaper-{digest}{file.suffix.lower()}")
        write_json(theme / "config.json", config)
        self._script("tools/theme.ps1", ["-TvHost", self.endpoint, "-Theme", str(theme)], folder)
        if (Path(folder) / "launcher-preferences.b64").is_file():
            self.restore_launcher_preferences(Path(folder))

    def restore_launcher_preferences(self, folder):
        saved = json.loads(base64.b64decode((folder / "launcher-preferences.b64").read_text(encoding="ascii"), validate=True))
        snapshot = self.shell(f"content call --uri content://{LAUNCHER}.theme --method sokol_snapshot")
        match = re.search(r"snapshot=([A-Za-z0-9+/=]+)", snapshot)
        if not match:
            raise OperationError("Не удалось проверить оформление перед возвратом настроек лаунчера.")
        current = json.loads(base64.b64decode(match[1], validate=True))
        restored = preserve_choices_for_theme(saved, current)
        temporary = folder / "preferences-to-restore.b64"
        temporary.write_text(base64.b64encode(json.dumps(restored, ensure_ascii=False).encode()).decode(), encoding="ascii")
        self.adb("push", temporary, "/data/local/tmp/sokol-preferences.b64")
        result = self.shell(f"content call --uri content://{LAUNCHER}.theme --method sokol_restore --arg $(cat /data/local/tmp/sokol-preferences.b64)")
        self.shell("rm -f /data/local/tmp/sokol-preferences.b64")
        if "restored=true" not in result:
            raise OperationError("Не подтверждено восстановление настроек лаунчера.")
        self.shell("am force-stop " + LAUNCHER)
        self.shell("input keyevent KEYCODE_HOME")

    def snapshot_launcher(self, folder):
        output = self.shell(f"content call --uri content://{LAUNCHER}.theme --method sokol_snapshot")
        match = re.search(r"snapshot=([A-Za-z0-9+/=]+)", output)
        if not match:
            raise OperationError("На ТВ уже установлен другой вариант CleanYourTV без экспорта настроек. Эта версия программы остановилась, чтобы сохранить его оформление и порядок приложений. Для штатного Home это ограничение не применяется.")
        payload = base64.b64decode(match[1], validate=True)
        json.loads(payload)
        (folder / "launcher-preferences.b64").write_text(match[1], encoding="ascii")
        directory = f"/sdcard/Android/data/{LAUNCHER}/files"
        exists = self.shell(f"if [ -d {directory} ]; then echo PRESENT; else echo EMPTY; fi")
        if exists.strip() == "PRESENT":
            self.adb("pull", directory, folder / "launcher-files", timeout=90)

    def screenshot(self, path):
        output = self.shell("screencap -p /data/local/tmp/sokol-cleantv.png")
        if "Permission" in output:
            raise OperationError("Телевизор запретил снимок экрана.")
        self.adb("pull", "/data/local/tmp/sokol-cleantv.png", path)
        self.shell("rm -f /data/local/tmp/sokol-cleantv.png")

    @exclusive
    def restore(self, folder):
        folder = Path(folder)
        manifest = self._manifest(folder)
        self.assert_identity(manifest["device"])
        original_home = manifest["device"]["home"]
        if not COMPONENT.fullmatch(original_home):
            raise OperationError("В копии нет допустимого исходного главного экрана.")
        if not all(PACKAGE.fullmatch(name) and state in {None, 0, 1, 2, 3, 4}
                   for name, state in manifest["changed"].items()):
            raise OperationError("В записи отката недопустимые состояния пакетов.")
        home_package = original_home.split("/")[0]
        current = package_set(self.shell("pm list packages --user 0"))
        if home_package not in current:
            raise OperationError("Исходный главный экран отсутствует. Автоматическое восстановление остановлено.")
        # First make the old Home usable. Any later failure leaves an accessible home screen.
        enabled = self.shell("pm enable --user 0 " + home_package)
        if "enabled" not in enabled:
            raise OperationError("Не удалось включить исходный главный экран.")
        for name, state in manifest["changed"].items():
            if name not in current:
                if state is not None:
                    raise OperationError("Приложение из копии удалено. Для восстановления нужен его APK. " + name)
                continue
            if state is None and name not in {LAUNCHER, BYEDPI}:
                continue
            action = {None: "disable-user", 0: "default-state", 1: "enable", 2: "disable",
                      3: "disable-user", 4: "disable-until-used"}[state]
            self.log("Восстановление состояния " + APP_NAMES.get(name, name))
            self.shell(f"pm {action} --user 0 {name}")
            actual = package_state(self.shell("dumpsys package " + name))
            expected = 3 if state is None else state
            if actual != expected:
                raise OperationError("Состояние приложения не восстановлено. " + name)
        self.shell("cmd package set-home-activity --user 0 " + original_home)
        actual_home = home_component(self.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"))
        if actual_home != original_home:
            raise OperationError("Исходный главный экран не стал основным. Проверьте журнал.")
        preferences = folder / "launcher-preferences.b64"
        if preferences.is_file():
            # Restore an existing launcher's theme and preferences from before this operation.
            self.shell("am force-stop " + LAUNCHER)
            old_theme = folder / "launcher-files" / "config.json"
            if old_theme.is_file():
                self._script("tools/theme.ps1", ["-TvHost", self.endpoint, "-Theme", str(old_theme.parent)], folder)
            else:
                self._script("tools/theme.ps1", ["-TvHost", self.endpoint, "-Reset"], folder)
            self.restore_launcher_preferences(folder)
        self.shell("sync")
        manifest["operations"].append({"state": "restored", "time": datetime.now(timezone.utc).isoformat()})
        write_json(folder / "manifest.json", manifest)
        self.log("Исходный Home и состояния изменённых приложений восстановлены. Новые APK сохранены на ТВ в отключённом состоянии.")
        return "Восстановление завершено"

    def verify(self, folder, reboot=False):
        manifest = self._manifest(Path(folder))
        self.assert_identity(manifest["device"])
        if reboot:
            self.adb("reboot")
            self.device = None
            return "Телевизор перезагружается. После загрузки подключитесь заново и проверьте результат."
        state = self.shell("getprop sys.boot_completed").strip()
        if state != "1":
            raise OperationError("Телевизор ещё не завершил загрузку.")
        disabled = package_set(self.shell("pm list packages -d --user 0"))
        expected = {name for entry in manifest["operations"] if entry.get("state") == "started" for name in entry.get("packages", [])}
        returned = sorted(expected - disabled)
        home = home_component(self.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"))
        if returned:
            raise OperationError("После загрузки включились обратно следующие приложения. " + ", ".join(returned))
        if any(entry.get("launcher") for entry in manifest["operations"]) and home.split("/")[0] != LAUNCHER:
            raise OperationError("После загрузки основной главный экран изменился.")
        return "Home и состояния выбранных приложений проверены. Физические кнопки пульта и воспроизведение проверяются на ТВ."

    def close_debugging(self):
        host, port = self.endpoint.rsplit(":", 1)
        try:
            self.shell("settings put global adb_enabled 0")
        except OperationError:
            pass
        try:
            with socket.create_connection((host, int(port)), timeout=3):
                raise OperationError("Порт ещё открыт. Выключите также сетевую и беспроводную отладку на ТВ, затем повторите проверку.")
        except ConnectionRefusedError:
            self.device = None
            return "Порт ADB закрыт. Отзовите разрешения отладки в меню телевизора."
        except (TimeoutError, OSError) as error:
            raise OperationError("Телевизор не ответил. Закрытие порта пока не подтверждено.") from error


def catalog():
    items = []
    for path in sorted((VENDOR / "examples").glob("*/tv.json")):
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        items.append({"brand": data.get("brand", "Другие"), "tv": data.get("tv", path.parent.name),
                      "os": data.get("os", ""), "notes": data.get("notes", ""), "profile": path.parent.name})
    return items
