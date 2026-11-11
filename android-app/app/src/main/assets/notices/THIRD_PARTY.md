# Компоненты Android-версии

- CleanYourTV / TV Home — исходники в соседней папке `launcher`, MIT, исходный проект https://github.com/Gathin23/cleanyourtv. Закреплённая ревизия и лицензии указаны в корневом `THIRD_PARTY.md` и `bootstrap.ps1`.
- LibADB Android 3.1.1 — https://github.com/MuntashirAkon/libadb-android/tree/3.1.1, GPL-3.0-or-later **OR** Apache-2.0. Для приложения выбрана Apache-2.0. `app/src/main/java/io/github/muntashirakon/adb/SokolPairingConnection.java` — адаптация PairingConnectionCtx из этого тега: добавлены тайм-ауты TCP/TLS и закрытие сокетов при ошибке. Содержит код ADB client и SPAKE2 из перечисленных в upstream LICENSES источников.
- SPAKE2 Android 2.2.1 — https://github.com/MuntashirAkon/spake2-java, Apache-2.0 / BSD-3-Clause согласно upstream. Транзитивная зависимость LibADB.
- Conscrypt Android 2.8.0 — https://github.com/google/conscrypt/releases/tag/2.8.0, Apache-2.0; содержит BoringSSL и собственные third-party notices.
- Bouncy Castle 1.81 (`bcprov`, `bcpkix`, `bcutil` jdk15to18) — https://www.bouncycastle.org/licence.html, Bouncy Castle Licence (MIT-style).
- AndroidX Annotation — Apache-2.0.
- Desugar JDK Libraries 2.1.4 — https://github.com/google/desugar_jdk_libs, GPL-2.0 with Classpath Exception и third-party notices.
- ByeByeDPI 1.8.2 — https://github.com/romanvht/ByeByeDPI/releases/tag/v.1.8.2, GPL-3.0. Приложение передаёт официальный отдельно подписанный APK как независимую необязательную программу, не связывается с его кодом. Полный соответствующий исходный код доступен в указанном теге проекта; исходники его компонентов и инструкции — в upstream. SHA-256 и сертификат закреплены в родительском `resources/assets.json`.
- JUnit 4.13.2 — EPL-1.0, только тесты. JSON-java 20240303 — Public Domain, только тесты.

Дополнительные сведения о лицензиях в `app/src/main/assets/notices/` и исходных репозиториях. Телефонный APK включает этот документ и лицензионные тексты в assets/notices.
