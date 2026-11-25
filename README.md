# Bookio

Android-клиент школьной библиотеки для учеников и библиотекарей. Курсовой проект РТУ МИРЭА. Сервер и контракт REST API находятся в отдельном репозитории. [Bookio Backend](https://github.com/Umor1st/Bookio-Backend)

## Быстрый старт

Для Android понадобятся Android Studio, SDK 37.2 и устройство или эмулятор с Android 8.0 (API 26) или новее. Gradle запускает daemon на JDK 25. Для локального Backend нужны Java 21 и Docker с Compose.

### 1. Поднимите Backend

В каталоге `Bookio-Backend` создайте локальный `.env` из шаблона. Если файл уже есть, проверьте, что он указывает на локальную PostgreSQL, особенно перед запуском тестов на устройстве.

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d
Get-Content .env | Where-Object { $_ -match '^([^#=]+)=(.*)$' } | ForEach-Object {
    [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
}
.\gradlew.bat run
```

Оставьте сервер запущенным. Адрес `http://localhost:8080/health` должен вернуть `200 OK`.

### 2. Установите Debug

Выполните нужный вариант из каталога `Bookio`. Для физического телефона включите отладку по USB, проверьте подключение через ADB и пробросьте порт сервера.

```powershell
adb devices
adb reverse tcp:8080 tcp:8080
.\gradlew.bat installDebug
```

Для Android Emulator используйте адрес компьютера из эмулятора.

```powershell
.\gradlew.bat installDebug "-PbookioDebugApiUrl=http://10.0.2.2:8080"
```

Если подключено несколько устройств, для `adb reverse` укажите нужное через `adb -s <serial>`.

## Варианты сборки

| Вариант | Адрес API | Настройка |
| --- | --- | --- |
| Debug | `http://127.0.0.1:8080` | Переопределяется через `-PbookioDebugApiUrl`. |
| Staging | HTTPS-адрес тестового сервера | Включается свойством `-PbookioStagingApiUrl`. |
| Release | `https://bookio.duckdns.org` | Задан в конфигурации сборки. |

В свойствах Gradle указывайте адрес сервера без `/api/v1`. HTTP разрешён только в Debug.

```powershell
.\gradlew.bat assembleStaging "-PbookioStagingApiUrl=https://staging.example.org"
```

Замените `staging.example.org` адресом своего тестового сервера.

## Проверки

Unit-тесты и сборка APK выполняются без запущенного Backend.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```

Для instrumentation-тестов запустите локальный Backend с локальной БД и настройте `adb reverse tcp:8080 tcp:8080` на тестовом устройстве. Тест авторизации требует Debug с адресом `http://127.0.0.1:8080` и оставляет тестового ученика в локальной БД.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Подключение авторизации

`AndroidAuthDependencies.get(context)` предоставляет реализацию для работы с Backend. Перед выбором стартового экрана дождитесь результата `restoreSession()`. При `RetryableFailure` токен сохраняется и восстановление можно повторить.

`AuthDependencies()` предоставляет fake для разработки, а `AuthDependencies(repository)` принимает репозиторий для тестов.
