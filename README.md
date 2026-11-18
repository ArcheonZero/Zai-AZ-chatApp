# AZ chat — Android-обёртка для chat.z.ai

Нативное Android-приложение, которое открывает **[chat.z.ai](https://chat.z.ai)** в WebView без браузерной обвязки: свой экран, свои жесты, свои кнопки. Kotlin, Material Design 3, AMOLED-тема, minSdk 24 (Android 7.0+).

Это форк проекта [SHSLabs/Zai-app](https://github.com/SHSLabs/Zai-app) (MIT, © The-JDdev), переработанный под собственный бренд и доведённый до версии 2.0.0. См. раздел «Что изменено».

---

## Что это

Zhipu AI не выпускает официального Android-клиента для chat.z.ai, поэтому веб-версия заворачивается в нативное приложение: скрывается хром сайта, камера и выбор файлов работают через системные диалоги, есть pull-to-refresh, аппаратная кнопка «Назад» с корректным поведением и диалог выхода.

Вся обработка запросов идёт на серверах Z.ai — приложение только показывает страницу и добавляет нативную обвязку.

## Что изменено относительно исходного Zai-app

Ниже — все отличия от базового проекта, сгруппированные по темам: что было в исходном приложении и что сделано здесь.

### Бренд и сборка

- Собственный пакет `ru.archeonzero.azchat` вместо `com.zai.wrapper`, своё имя приложения «AZ chat», своя тема `Theme.AZChat` и свой набор иконок (адаптивная иконка, все плотности, версия 512×512 для магазина).
- `versionCode 20` / `versionName 2.0.0`.
- Скрипт `build-debug.ps1` больше не привязан к одной машине: `JAVA_HOME` берётся из окружения, путь к SDK — из `local.properties`, APK складывается в `dist/`.
- **Release-подпись вынесена из репозитория.** `keystore.properties` лежит рядом с ключом (`../keys/`), путь к `.jks` считается относительно него, так что буква диска не важна. Нет файла — release собирается неподписанным, debug это не затрагивает. Пример — `keystore.properties.example`.
- **`app/proguard-rules.pro`** сохраняет JS-мосты: без `-keepattributes JavascriptInterface` R8 в release вырезает методы, и приложение собирается, но «Назад», счётчик превью и скачивание `blob:` молча перестают работать.
- `.gitignore` закрывает `local.properties`, `*.jks`, `*.keystore`, `keystore.properties` и `keys/`; `.gitattributes` фиксирует LF для `gradlew`, CRLF для `.bat` и binary для png/apk/jks.

### Вход через Google

- **Было:** `window.open` игнорировался, Google видел «webview»-UA и показывал веб-форму ввода логина и пароля.
- **Стало:** `setSupportMultipleWindows(true)`, подмена User-Agent на Chrome Android, `onCreateWindow(...)` создаёт popup-WebView с общими cookies, «Назад» сначала закрывает popup, `accounts.google.*` не уходит в системный браузер, работает `login_hint` и autofill.
- **Ограничение:** Google в WebView всё равно просит пароль. Бесшовный вход возможен только через native Google Sign-In с поддержкой на стороне chat.z.ai.

### Внешние ссылки

- **Было:** всё грузилось в том же WebView — сторонние сайты и китайские версии открывались внутри приложения, выйти назад было нельзя.
- **Стало:** внешние `http(s)`-ссылки уходят в системный браузер, `z.ai` и авторизация Google остаются в приложении. Проверка хоста — по `z.ai` / `*.z.ai`, а не по подстроке.

### Кнопка «Домой»

- **Было:** правый нижний угол — перекрывала кнопку «Отправить»; тап уводил «назад по истории».
- **Стало:** компактная кнопка в правом верхнем углу; тап всегда возвращает на `chat.z.ai` (если уже там — перезагружает).

### Прокрутка и жесты

- **Было:** `SwipeRefreshLayout` не знал о внутреннем скролле SPA — прокрутка истории чата вызывала перезагрузку.
- **Стало:** `ZaiSwipeRefresh` + JS-мост `ZaiScrollBridge`: страница сообщает позицию внутреннего контейнера, обновление разрешено только на самом верху.
- Скрытие элементов сайта (шапка, подвал) повторно применяется после смены маршрута — SPA не возвращает их обратно.

### Вложения и всплывающие окна

Главная переработка. Сайт при входе в чат сам открывает панель вложений — это мешает, но превью, открытое человеком, закрывать нельзя. Логика разделена на два уровня:

- **Автооткрытие** (панель появилась сама после перехода в чат) скрывается: переход на `/c/<uuid>` сбрасывает окно доверия, жест входа в чат осознанным не считается.
- **Осознанное действие** — любой тап внутри чата: по миниатюре, документу, превью, кнопке Share, ссылке. Такое окно остаётся; окно доверия — 4 секунды.
- Спрятанные окна запоминаются: если у панели нет кнопки закрытия, она прячется через `display` и при следующем осознанном тапе это скрытие снимается, чтобы сайт мог переиспользовать узел под превью.
- Поиск панелей учитывает `drawer`/`sheet`, `attachment`/`file-view`, `preview`/`lightbox`/`gallery`, `role=dialog`; скрывается `backdrop`, изменения ловит `MutationObserver`.
- Аппаратная кнопка «Назад» и `Esc` сначала закрывают превью и галереи, и только потом листают историю.

### Скачивание файлов

- **Было:** обработчика скачиваний не было вовсе — часть ссылок не качалась, `blob:` и `data:` терялись молча, место сохранения было непонятно.
- **Стало:** `http(s)` → `DownloadManager` в системную папку Download (с Cookie и User-Agent, уведомлением и понятным именем); `blob:` → JS-мост `ZaiBlobSaver` (fetch → base64 → запись); `data:` → прямая запись.
- На Android 10+ используется `MediaStore.Downloads` без разрешений, на Android 9 и ниже — публичная папка с runtime-запросом `WRITE_EXTERNAL_STORAGE`.
- Имя берётся из `Content-Disposition`, затем из URL, иначе `zai-file-<дата>`; расширение — по MIME.
- **Ограничение:** `blob:` идёт через base64 в памяти страницы — очень большие файлы (условно >100 МБ) могут не влезть в память; обычные ссылки качает `DownloadManager`.

### Прочее

- Автовыбор последней модели GLM при старте (один раз за запуск, отключается флагом `autoSelectLatestModel`).
- Splash-экран, диалог выхода на Material 3, тонкий прогресс-бар загрузки.
- Исправлена опечатка в манифесте `REDA_MEDIA_VIDEO` → `READ_MEDIA_VIDEO`.
- `minSdk 24`, `targetSdk 34`, `compileSdk 34`, Kotlin, JDK 17.

### Известные ограничения

- Google в WebView всё равно просит пароль (см. выше).
- Скрытие превью и автовыбор модели — эвристики по DOM: при смене разметки сайта селекторы могут потребовать правки.
- Для Google Play нужны `targetSdk 36` (требование с 31.08.2026), сборка AAB, аккаунт разработчика, листинг и политика конфиденциальности; webview-обёртки проверяют строже.

---

## Сборка

Нужны JDK 17 и Android SDK 34 (platform + build-tools 34.0.0).

```bash
# путь к SDK (если не задан ANDROID_HOME)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleDebug
# или на Windows:
.\build-debug.ps1
```

APK: `app/build/outputs/apk/debug/app-debug.apk`, копия — в `dist/`.

Установка на подключённое устройство:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Release собирается с `isMinifyEnabled = true` и подписывается, если рядом с проектом есть
`../keys/keystore.properties` (см. `keystore.properties.example`). После release-сборки её нужно
проверить на устройстве: скачивание файлов, счётчик открытых превью и pull-to-refresh —
это места, где R8 может навредить, если правила proguard изменятся.

---

## Структура проекта

```
Zai-AZ-chatApp/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/ru/archeonzero/azchat/
│       │   ├── MainActivity.kt        # WebView, мосты, вложения, файлы, назад
│       │   ├── SplashActivity.kt      # сплэш с плавным появлением
│       │   └── ZaiSwipeRefresh.kt     # pull-to-refresh с учётом скролла чата
│       └── res/                       # layout, drawable, mipmap, values, xml
├── gradle/wrapper/
├── scripts/
├── build-debug.ps1
├── settings.gradle.kts
├── keystore.properties.example
└── LICENSE, NOTICE
```

---

## Лицензия

MIT. Исходный проект — © 2026 The-JDdev, изменения и сборка 2.0.0 — © 2026 Archeon Zero. Текст лицензии — в [LICENSE](LICENSE), атрибуция — в [NOTICE](NOTICE).

## Дисклеймер

Неофициальный клиент. «Zhipu AI», «Z.AI» и связанные обозначения принадлежат их правообладателям; проект не связан с Zhipu AI и не одобрен ими. Приложение просто открывает `https://chat.z.ai` в WebView — использование сервиса регулируется его условиями.

---

## English (short)

`AZ chat` is a native Android wrapper for [chat.z.ai](https://chat.z.ai), forked from [SHSLabs/Zai-app](https://github.com/SHSLabs/Zai-app) (MIT, © The-JDdev) and rebranded as version 2.0.0: own package `ru.archeonzero.azchat`, own icon set, reworked attachment-overlay logic (auto-opened panels are hidden, user-opened previews stay), blob downloads through a JS bridge, and scroll-aware pull-to-refresh. Unofficial client, not affiliated with Zhipu AI. Licensed under MIT.
