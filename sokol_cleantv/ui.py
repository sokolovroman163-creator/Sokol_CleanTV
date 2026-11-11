from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

from PySide6.QtCore import Qt, QObject, QThread, Signal, Slot, QTimer, QUrl
from PySide6.QtGui import QDesktopServices, QFont, QColor
from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton,
    QLineEdit, QComboBox, QStackedWidget, QFrame, QTableWidget, QTableWidgetItem,
    QHeaderView, QCheckBox, QFileDialog, QMessageBox, QPlainTextEdit, QListWidget,
    QProgressBar, QInputDialog, QAbstractItemView, QSplitter, QScrollArea,
)

from .backend import Workshop, OperationError, catalog, APP_NAMES

STYLE = """
QWidget { font-family: 'Segoe UI'; font-size: 14px; color: #17263b; }
QMainWindow, #canvas { background: #f2f5f9; }
QDialog { background-color: #f8fafc; color: #17263b; }
QDialog QLabel { background-color: transparent; color: #17263b; }
QDialog QPushButton { background-color: #ffffff; color: #17263b; }
QDialog QPushButton:hover { background-color: #edf3fa; }
QDialog QPushButton:disabled { background-color: #e9edf2; color: #64748b; }
QDialog QLineEdit, QDialog QComboBox { background-color: #ffffff; color: #17263b; }
QDialog QAbstractItemView { background-color: #ffffff; color: #17263b; selection-background-color: #2367b2; selection-color: #ffffff; }
QComboBox QAbstractItemView { background-color: #ffffff; color: #17263b; selection-background-color: #2367b2; selection-color: #ffffff; }
#sidebar { background: #10243a; }
#sidebar QLabel { color: #c8d5e3; }
#sidebar #brand { color: white; font-size: 23px; font-weight: 700; }
#sidebar QPushButton { text-align: left; background: transparent; border: 0; color: #c8d5e3; padding: 14px 16px; }
#sidebar QPushButton:checked { background: #244465; color: white; border-left: 3px solid #59b9dd; }
#sidebar QPushButton:hover { background: #1c3650; }
QLabel#title { font-size: 27px; font-weight: 700; color: #10243a; }
QLabel#subtitle { color: #506276; }
QLabel#cardTitle { font-size: 17px; font-weight: 600; }
QLabel#eyebrow { color: #25739a; font-size: 12px; font-weight: 700; }
QFrame#card { background: white; border: 1px solid #dce4ed; border-radius: 10px; }
QLabel#notice { padding: 12px; background: #e5eff8; color: #214766; border-radius: 6px; }
QPushButton { background: white; border: 1px solid #c7d3e0; border-radius: 6px; padding: 10px 15px; font-weight: 600; }
QPushButton:hover { background: #edf3fa; border-color: #658db7; }
QPushButton:focus { border: 2px solid #2575c2; }
QPushButton#primary { background: #2367b2; border-color: #2367b2; color: white; }
QPushButton#primary:hover { background: #184f8e; }
QPushButton:disabled { color: #8b99a8; background: #e9edf2; border-color: #d5dde6; }
QLineEdit, QComboBox { padding: 10px; background: white; border: 1px solid #c7d3e0; border-radius: 5px; min-height: 19px; }
QLineEdit:focus, QComboBox:focus { border: 2px solid #2575c2; }
QCheckBox { spacing: 10px; padding: 8px 0; }
QCheckBox::indicator { width: 19px; height: 19px; }
QTableWidget, QListWidget { background: white; border: 1px solid #dce4ed; border-radius: 6px; gridline-color: #edf1f6; }
QTableWidget::item, QListWidget::item { padding: 7px; }
QHeaderView::section { background: #eaf0f6; padding: 9px; border: 0; font-weight: 600; color: #41566e; }
QPlainTextEdit { background: #10243a; color: #dfebf4; border: 0; border-radius: 6px; padding: 9px; font-family: Consolas; font-size: 12px; }
QProgressBar { background: #dfe8f1; border: 0; height: 4px; border-radius: 2px; }
QProgressBar::chunk { background: #2983b5; }
QScrollArea { border: 0; background: transparent; }
"""


class Worker(QObject):
    done = Signal(object)
    failed = Signal(str)
    log = Signal(str)

    def __init__(self, function):
        super().__init__()
        self.function = function

    def run(self):
        try:
            self.done.emit(self.function())
        except Exception as error:
            self.failed.emit(str(error))


def label(text, object_name=None, wrap=True):
    result = QLabel(text)
    result.setWordWrap(wrap)
    if object_name:
        result.setObjectName(object_name)
    return result


class Window(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Sokol_CleanTV")
        self.resize(1240, 850)
        self.setMinimumSize(1000, 700)
        self.service = Workshop()
        self.thread = None
        self.worker = None
        self.busy = False
        self.wallpapers = []
        self.catalog_rows = catalog()
        self.actions = []
        root = QWidget()
        root.setObjectName("canvas")
        self.setCentralWidget(root)
        main = QHBoxLayout(root)
        main.setContentsMargins(0, 0, 0, 0)
        main.setSpacing(0)
        side = QFrame()
        side.setObjectName("sidebar")
        side.setFixedWidth(220)
        nav = QVBoxLayout(side)
        nav.setContentsMargins(18, 26, 18, 22)
        nav.setSpacing(8)
        nav.addWidget(label("Sokol\nCleanTV", "brand"))
        nav.addWidget(label("МАСТЕР НАСТРОЙКИ ТВ"))
        nav.addSpacing(28)
        self.stack = QStackedWidget()
        self.nav_buttons = []
        for number, name in enumerate(("01  Телевизор", "02  Настройка", "03  Резервные копии", "Каталог телевизоров")):
            button = QPushButton(name)
            button.setCheckable(True)
            button.clicked.connect(lambda checked=False, index=number: self.navigate(index))
            nav.addWidget(button)
            self.nav_buttons.append(button)
        nav.addStretch()
        nav.addWidget(label("Android TV · Google TV\nРаботает в локальной сети\nВерсия 0.1.1"))
        main.addWidget(side)
        right = QVBoxLayout()
        right.setContentsMargins(30, 24, 30, 20)
        right.setSpacing(15)
        top = QHBoxLayout()
        top.addWidget(label("SOKOL_CLEANTV  /  WINDOWS", "eyebrow"))
        top.addStretch()
        self.connection_badge = label("Телевизор не подключён", "subtitle")
        top.addWidget(self.connection_badge)
        right.addLayout(top)
        right.addWidget(self.stack, 1)
        self.progress = QProgressBar()
        self.progress.setRange(0, 1)
        self.progress.setValue(0)
        self.progress.setTextVisible(False)
        right.addWidget(self.progress)
        self.status = label("Подключите ноутбук и телевизор к одной локальной сети.", "subtitle")
        right.addWidget(self.status)
        self.log_view = QPlainTextEdit()
        self.log_view.setReadOnly(True)
        self.log_view.setMaximumHeight(105)
        self.log_view.setPlaceholderText("Здесь появится журнал операций.")
        right.addWidget(self.log_view)
        main.addLayout(right, 1)
        self.build_device_page()
        self.build_actions_page()
        self.build_backups_page()
        self.build_catalog_page()
        self.navigate(0)
        self.refresh_backups()
        self.setStyleSheet(STYLE)

    def button(self, title, handler, primary=False):
        result = QPushButton(title)
        if primary:
            result.setObjectName("primary")
        result.clicked.connect(handler)
        self.actions.append(result)
        return result

    def page(self, title, description):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(14)
        layout.addWidget(label(title, "title"))
        layout.addWidget(label(description, "subtitle"))
        self.stack.addWidget(page)
        return layout

    def card(self, parent, title):
        frame = QFrame()
        frame.setObjectName("card")
        layout = QVBoxLayout(frame)
        layout.setContentsMargins(18, 16, 18, 16)
        layout.setSpacing(12)
        layout.addWidget(label(title, "cardTitle"))
        parent.addWidget(frame)
        return layout

    def build_device_page(self):
        page = self.page("Подключите телевизор", "Сначала проверим систему и доступные функции. Изменения запускаются отдельно.")
        box = self.card(page, "Подключение по Wi-Fi или Ethernet")
        box.addWidget(label("На ТВ откройте «Для разработчиков» и включите USB- или сетевую отладку. Разрешите подключение с пульта. Для беспроводной отладки с кодом используйте «Сопряжение».", "subtitle"))
        row = QHBoxLayout()
        self.endpoint = QComboBox()
        self.endpoint.setEditable(True)
        self.endpoint.lineEdit().setPlaceholderText("IP-адрес телевизора, например 192.168.1.50")
        self.endpoint.setAccessibleName("IP-адрес телевизора и порт")
        row.addWidget(self.endpoint, 1)
        row.addWidget(self.button("Найти в сети", self.discover))
        row.addWidget(self.button("Проверить ТВ", self.inspect, True))
        box.addLayout(row)
        row = QHBoxLayout()
        row.addWidget(self.button("Сопряжение по коду", self.pair))
        row.addWidget(self.button("Как включить отладку", self.help_debugging))
        row.addStretch()
        box.addLayout(row)
        self.device_details = label("После подключения здесь появятся модель, версия Android и результат проверки.", "notice")
        box.addWidget(self.device_details)
        limits = self.card(page, "Что проверяет программа")
        limits.addWidget(label("Авторизацию ADB, тип Android TV / Google TV, версию Android, архитектуру, основной Home и доступность списка приложений. Возможность установки и переключения Home дополнительно проверяется при выполнении.", "subtitle"))
        limits.addWidget(label("Каталог содержит отчёты по устройствам. Один бренд может выпускать телевизоры с разными операционными системами и ограничениями.", "subtitle"))
        row = QHBoxLayout()
        row.addWidget(self.button("Открыть настройку", lambda: self.navigate(1), True))
        row.addWidget(self.button("Завершить и закрыть ADB", self.close_debugging))
        row.addStretch()
        page.addLayout(row)
        page.addStretch()

    def build_actions_page(self):
        page = self.page("Выберите изменения", "Перед применением автоматически создаётся новая резервная копия. Отключение приложений сохраняет их данные.")
        row = QHBoxLayout()
        self.install_home = QCheckBox("Установить главный экран")
        self.install_dpi = QCheckBox("Установить ByeByeDPI")
        row.addWidget(self.install_home)
        row.addWidget(self.install_dpi)
        row.addStretch()
        page.addLayout(row)
        page.addWidget(label("ByeByeDPI устанавливается по выбору. При первом запуске на ТВ потребуется подтвердить VPN-разрешение. Подбор стратегии и работа YouTube зависят от сети.", "notice"))
        page.addWidget(label("Приложения для отключения", "cardTitle"))
        self.package_search = QLineEdit()
        self.package_search.setPlaceholderText("Поиск по названию или имени пакета")
        self.package_search.textChanged.connect(self.filter_packages)
        page.addWidget(self.package_search)
        self.packages = QTableWidget(0, 4)
        self.packages.setWordWrap(False)
        self.packages.verticalHeader().setDefaultSectionSize(40)
        self.packages.setHorizontalHeaderLabels(["Отключить", "Приложение", "Пакет", "Статус"])
        self.packages.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.packages.verticalHeader().hide()
        self.packages.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
        self.packages.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
        self.packages.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch)
        self.packages.horizontalHeader().setSectionResizeMode(3, QHeaderView.ResizeMode.Stretch)
        page.addWidget(self.packages, 1)
        wall = QHBoxLayout()
        self.wallpaper_summary = label("Обои не выбраны", "subtitle")
        wall.addWidget(self.wallpaper_summary, 1)
        wall.addWidget(self.button("Добавить обои…", self.add_wallpapers))
        wall.addWidget(self.button("Очистить выбор", self.clear_wallpapers))
        page.addLayout(wall)
        row = QHBoxLayout()
        row.addWidget(self.button("Сохранить копию сейчас", self.backup))
        row.addStretch()
        row.addWidget(self.button("Применить выбранное", self.apply, True))
        page.addLayout(row)

    def build_backups_page(self):
        page = self.page("Резервные копии", "Каждая копия привязана к идентификатору телевизора и хранится на этом компьютере.")
        page.addWidget(label("Копия содержит настройки системы, состояния пакетов, исходный Home и журнал изменений. Откат доступен при работающей Android-системе и ADB. Разделы прошивки и личные данные сторонних приложений сюда не входят.", "notice"))
        self.backups = QListWidget()
        self.backups.currentRowChanged.connect(self.show_backup)
        page.addWidget(self.backups, 1)
        self.backup_details = label("Выберите копию или создайте новую.", "subtitle")
        page.addWidget(self.backup_details)
        row = QHBoxLayout()
        row.addWidget(self.button("Создать копию", self.backup, True))
        row.addWidget(self.button("Обновить список", self.refresh_backups))
        row.addWidget(self.button("Открыть папку копий", self.open_backups))
        page.addLayout(row)
        row = QHBoxLayout()
        row.addWidget(self.button("Восстановить выбранную", self.restore))
        row.addWidget(self.button("Копия из другой папки…", self.restore_external))
        row.addWidget(self.button("Проверить после загрузки", self.verify))
        row.addWidget(self.button("Перезагрузить ТВ", self.reboot))
        page.addLayout(row)

    def build_catalog_page(self):
        page = self.page("Каталог телевизоров", "Отчёты сообщества CleanYourTV. Наличие модели в каталоге помогает подготовиться к работе.")
        counts = Counter(item["brand"] for item in self.catalog_rows)
        page.addWidget(label(f"{len(self.catalog_rows)} отчётов  ·  {len(counts)} брендов  ·  база из локальной копии репозитория", "eyebrow"))
        row = QHBoxLayout()
        self.brand = QComboBox()
        self.brand.addItem("Все бренды", "")
        for brand in sorted(counts, key=str.casefold):
            self.brand.addItem(f"{brand} ({counts[brand]})", brand)
        self.brand.currentIndexChanged.connect(self.filter_catalog)
        self.catalog_search = QLineEdit()
        self.catalog_search.setPlaceholderText("Модель или платформа")
        self.catalog_search.textChanged.connect(self.filter_catalog)
        row.addWidget(self.brand)
        row.addWidget(self.catalog_search, 1)
        page.addLayout(row)
        self.catalog_table = QTableWidget(0, 3)
        self.catalog_table.setHorizontalHeaderLabels(["Бренд", "Телевизор / платформа", "Система"])
        self.catalog_table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.catalog_table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.catalog_table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.catalog_table.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch)
        self.catalog_table.verticalHeader().hide()
        self.catalog_table.itemSelectionChanged.connect(self.show_catalog_notes)
        page.addWidget(self.catalog_table, 1)
        self.catalog_notes = QPlainTextEdit()
        self.catalog_notes.setReadOnly(True)
        self.catalog_notes.setMaximumHeight(120)
        self.catalog_notes.setPlaceholderText("Выберите строку, чтобы прочитать оригинальные заметки автора отчёта.")
        page.addWidget(self.catalog_notes)
        self.filter_catalog()

    def navigate(self, index):
        self.stack.setCurrentIndex(index)
        for number, button in enumerate(self.nav_buttons):
            button.setChecked(number == index)

    def execute(self, title, function, callback=None):
        if self.busy:
            return
        self.busy = True
        for button in self.actions:
            button.setEnabled(False)
        self.packages.setEnabled(False)
        self.install_home.setEnabled(False)
        self.install_dpi.setEnabled(False)
        self.progress.setRange(0, 0)
        self.status.setText(title)
        self.log_view.appendPlainText(title)
        self.thread = QThread(self)
        self.worker = Worker(function)
        self.worker.moveToThread(self.thread)
        self.service.log = self.worker.log.emit
        self.worker.log.connect(self.log_view.appendPlainText)
        self.callback = callback
        self.worker.done.connect(self.finished)
        self.worker.failed.connect(self.failed)
        self.worker.done.connect(self.thread.quit)
        self.worker.failed.connect(self.thread.quit)
        self.thread.finished.connect(self.worker.deleteLater)
        self.thread.finished.connect(self.idle)
        self.thread.finished.connect(self.thread.deleteLater)
        self.thread.started.connect(self.worker.run)
        self.thread.start()

    def idle(self):
        self.busy = False
        self.progress.setRange(0, 1)
        self.progress.setValue(1)
        for button in self.actions:
            button.setEnabled(True)
        self.packages.setEnabled(True)
        self.install_home.setEnabled(True)
        self.install_dpi.setEnabled(True)
        self.service.log = lambda text: None

    @Slot(object)
    def finished(self, result):
        self.status.setText("Готово")
        if self.service.device is None:
            self.connection_badge.setText("Требуется новое подключение")
        if self.callback:
            self.callback(result)
        elif isinstance(result, str):
            self.log_view.appendPlainText(result)
        self.refresh_backups()

    @Slot(str)
    def failed(self, message):
        self.status.setText("Операция остановлена. Подробности в журнале.")
        self.log_view.appendPlainText(message)
        self.refresh_backups()
        QMessageBox.warning(self, "Sokol_CleanTV", message)

    def require_device(self):
        if not self.service.device:
            QMessageBox.information(self, "Подключите ТВ", "Сначала подключитесь и выполните проверку на вкладке «Телевизор».")
            self.navigate(0)
            return False
        return True

    def discover(self):
        def update(items):
            previous = self.endpoint.currentText()
            self.endpoint.clear()
            self.endpoint.addItems(items)
            if not items:
                self.endpoint.setEditText(previous)
                self.status.setText("Объявлений ADB не найдено. Введите IP из настроек сети телевизора.")
        self.execute("Поиск объявлений ADB в локальной сети…", self.service.discover, update)

    def inspect(self):
        endpoint = self.endpoint.currentText()
        def update(device):
            self.connection_badge.setText(device["brand"] + " · " + device["model"])
            verdict = "Предварительные проверки пройдены" if device["can_install"] else "Сценарий установки главного экрана недоступен"
            self.device_details.setText(f"{device['model']}  ·  Android {device['android']}  ·  {device['abi']}\n{verdict}\nГлавный экран: {device['home']}")
            self.populate_packages(device["packages"])
        self.execute("Проверка телевизора…", lambda: self.service.inspect(endpoint), update)

    def populate_packages(self, packages):
        self.packages.setRowCount(len(packages))
        for row, data in enumerate(packages):
            checkbox = QTableWidgetItem()
            checkbox.setFlags(Qt.ItemFlag.ItemIsEnabled | Qt.ItemFlag.ItemIsUserCheckable)
            checkbox.setCheckState(Qt.CheckState.Unchecked)
            checkbox.setData(Qt.ItemDataRole.UserRole, data["package"])
            if data["blocked"] or data["disabled"]:
                checkbox.setFlags(Qt.ItemFlag.NoItemFlags)
            self.packages.setItem(row, 0, checkbox)
            self.packages.setItem(row, 1, QTableWidgetItem(data["label"]))
            self.packages.setItem(row, 2, QTableWidgetItem(data["package"]))
            status = data["blocked"] or ("Уже отключено" if data["disabled"] else "Можно выбрать; роли проверим перед действием")
            self.packages.setItem(row, 3, QTableWidgetItem(status))
            self.packages.item(row, 3).setToolTip(status)
        self.filter_packages()

    def filter_packages(self):
        text = self.package_search.text().casefold()
        for row in range(self.packages.rowCount()):
            combined = self.packages.item(row, 1).text() + " " + self.packages.item(row, 2).text()
            self.packages.setRowHidden(row, text not in combined.casefold())

    def pair(self):
        endpoint, ok = QInputDialog.getText(self, "Сопряжение", "IP и порт из окна «Подключить устройство с помощью кода» на ТВ")
        if not ok:
            return
        code, ok = QInputDialog.getText(self, "Код сопряжения", "Шесть цифр с экрана телевизора", QLineEdit.EchoMode.Password)
        if ok:
            self.execute("Сопряжение с телевизором…", lambda: self.service.pair(endpoint, code))

    def help_debugging(self):
        QMessageBox.information(self, "Отладка на телевизоре", "Откройте настройки телевизора → Об устройстве. Нажмите на «Сборка» 7 раз. В параметрах разработчика включите USB-отладку и сетевую отладку, если она есть.\n\nПодтвердите доступ с пульта. На Google TV с кодом используйте беспроводную отладку и кнопку «Сопряжение». Порт сопряжения отличается от порта подключения.\n\nПосле работы отзовите разрешения отладки, выключите её и проверьте закрытие порта.")

    def add_wallpapers(self):
        files, _ = QFileDialog.getOpenFileNames(self, "Обои для телевизора", "", "Изображения (*.jpg *.jpeg *.png *.webp)")
        if files:
            self.wallpapers = list(dict.fromkeys([*self.wallpapers, *files]))[:12]
            self.wallpaper_summary.setText(f"Выбрано обоев: {len(self.wallpapers)} · до 20 МБ каждый")
            self.wallpaper_summary.setToolTip("\n".join(self.wallpapers))

    def clear_wallpapers(self):
        self.wallpapers = []
        self.wallpaper_summary.setText("Обои не выбраны")

    def backup(self):
        if self.require_device():
            self.execute("Создание резервной копии…", self.service.save_backup)

    def apply(self):
        if not self.require_device():
            return
        packages = [self.packages.item(row, 0).data(Qt.ItemDataRole.UserRole)
                    for row in range(self.packages.rowCount())
                    if self.packages.item(row, 0).checkState() == Qt.CheckState.Checked]
        install_home, install_dpi = self.install_home.isChecked(), self.install_dpi.isChecked()
        wallpapers = list(self.wallpapers)
        if not (packages or install_home or install_dpi or wallpapers):
            QMessageBox.information(self, "Выберите изменения", "Отметьте приложения, установку главного экрана, ByeByeDPI или выберите обои.")
            return
        plan = f"Телевизор: {self.service.device['model']}\nОтключить приложений: {len(packages)}\nУстановить Home: {'да' if install_home else 'нет'}\nУстановить ByeByeDPI: {'да' if install_dpi else 'нет'}\nОбои: {len(wallpapers)}\n\nПеред изменениями будет создана новая копия для отката."
        if QMessageBox.question(self, "Применить изменения", plan) != QMessageBox.StandardButton.Yes:
            return
        self.execute("Применение выбранных изменений…", lambda: self.service.apply(packages, install_home, install_dpi, wallpapers))

    def refresh_backups(self):
        self.backups.clear()
        root = self.service.data_dir / "backups"
        for path in sorted(root.glob("*/manifest.json"), reverse=True):
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except (ValueError, OSError):
                continue
            status = "Готова" if data.get("complete") else "Неполная"
            self.backups.addItem(f"{path.parent.name}   ·   {data.get('device', {}).get('model', 'ТВ')}   ·   {status}")
            self.backups.item(self.backups.count() - 1).setData(Qt.ItemDataRole.UserRole, str(path.parent))

    def selected_backup(self):
        item = self.backups.currentItem()
        if not item:
            QMessageBox.information(self, "Выберите копию", "Выберите запись из списка резервных копий.")
            return None
        return item.data(Qt.ItemDataRole.UserRole)

    def show_backup(self):
        item = self.backups.currentItem()
        if item:
            folder = Path(item.data(Qt.ItemDataRole.UserRole))
            data = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
            self.backup_details.setText(f"Изменённых пакетов в журнале: {len(data.get('changed', {}))}\n{folder}")

    def restore_path(self, folder):
        if self.require_device() and QMessageBox.question(self, "Восстановить состояние", "Будут возвращены исходный Home и состояния изменённых приложений из выбранной копии. Идентификатор телевизора будет проверен перед действием.") == QMessageBox.StandardButton.Yes:
            self.execute("Восстановление исходного состояния…", lambda: self.service.restore(folder))

    def restore(self):
        folder = self.selected_backup()
        if folder:
            self.restore_path(folder)

    def restore_external(self):
        folder = QFileDialog.getExistingDirectory(self, "Папка резервной копии с manifest.json")
        if folder:
            self.restore_path(folder)

    def verify(self):
        folder = self.selected_backup()
        if folder and self.require_device():
            self.execute("Проверка состояния после загрузки…", lambda: self.service.verify(folder))

    def reboot(self):
        folder = self.selected_backup()
        if folder and self.require_device() and QMessageBox.question(self, "Перезагрузка", "Перезагрузить подключённый телевизор? После загрузки может потребоваться снова разрешить отладку.") == QMessageBox.StandardButton.Yes:
            self.execute("Запрос перезагрузки…", lambda: self.service.verify(folder, reboot=True))

    def open_backups(self):
        path = self.service.data_dir / "backups"
        path.mkdir(exist_ok=True)
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(path)))

    def close_debugging(self):
        if self.require_device():
            self.execute("Закрытие отладки и проверка порта…", self.service.close_debugging)

    def filter_catalog(self):
        self.catalog_table.clearSelection()
        brand = self.brand.currentData()
        search = self.catalog_search.text().casefold()
        self.filtered_catalog = [item for item in self.catalog_rows
                                 if (not brand or item["brand"] == brand)
                                 and search in (item["tv"] + " " + item["notes"]).casefold()]
        self.catalog_table.setRowCount(len(self.filtered_catalog))
        for row, item in enumerate(self.filtered_catalog):
            for col, key in enumerate(("brand", "tv", "os")):
                self.catalog_table.setItem(row, col, QTableWidgetItem(item[key]))

    def show_catalog_notes(self):
        rows = self.catalog_table.selectionModel().selectedRows()
        if rows:
            data = self.filtered_catalog[rows[0].row()]
            self.catalog_notes.setPlainText(data["notes"] + "\n\nПрофиль: " + data["profile"])

    def closeEvent(self, event):
        if self.busy:
            QMessageBox.information(self, "Операция выполняется", "Дождитесь завершения операции, чтобы сохранить полный журнал изменений.")
            event.ignore()
        else:
            event.accept()
