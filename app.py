import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))


def main():
    from PySide6.QtWidgets import QApplication
    from PySide6.QtCore import QTimer
    from PySide6.QtGui import QFontDatabase, QFont
    from sokol_cleantv.ui import Window
    app = QApplication(sys.argv)
    fonts = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts"
    for name in ("segoeui.ttf", "segoeuib.ttf", "consola.ttf"):
        if (fonts / name).is_file():
            QFontDatabase.addApplicationFont(str(fonts / name))
    app.setFont(QFont("Segoe UI", 10))
    app.setApplicationName("Sokol_CleanTV")
    app.setOrganizationName("Sokol")
    window = Window()
    window.show()
    if "--screenshot" in sys.argv:
        index = sys.argv.index("--screenshot")
        destination = Path(sys.argv[index + 1]).resolve()
        destination.mkdir(parents=True, exist_ok=True)
        def capture():
            for number, name in enumerate(("connection", "actions", "backups", "catalog")):
                window.navigate(number)
                app.processEvents()
                window.grab().save(str(destination / (name + ".png")))
            app.quit()
        QTimer.singleShot(500, capture)
    return app.exec()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception:
        import traceback
        import ctypes
        target = Path(os.environ.get("SOKOL_CLEANTV_DATA") or Path(os.environ.get("LOCALAPPDATA", ROOT)) / "Sokol_CleanTV")
        target.mkdir(parents=True, exist_ok=True)
        (target / "startup-error.txt").write_text(traceback.format_exc(), encoding="utf-8")
        ctypes.windll.user32.MessageBoxW(None, "Не удалось запустить приложение. Подробности сохранены в " + str(target / "startup-error.txt"), "Sokol_CleanTV", 0x10)
        raise
