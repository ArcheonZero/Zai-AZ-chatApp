# Правила R8 для release-сборки.

# Методы JS-мостов вызываются из страницы (addJavascriptInterface), а не из
# Kotlin. Без этих правил R8 считает их неиспользуемыми и может вырезать или
# переименовать: приложение соберётся, но мосты отвалятся молча — перестанет
# работать счётчик открытых превью, «Назад» начнёт уводить из чата, а
# скачивание blob-файлов сломается.
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Классы приложения не переименовываем: имена нужны в логах и при отладке.
-keepnames class ru.archeonzero.azchat.**
