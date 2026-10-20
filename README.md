# TelikTV — плеер «Живая природа» для Android TV

Максимально лёгкое приложение для ТВ-приставок (проверялось под MXQ Pro 4K).
При запуске сразу открывает и воспроизводит прямой эфир канала
«Живая природа» с https://telik.live/zhivaya-priroda.html — без меню,
кнопок, настроек и splash-экрана.

## Что делает приложение

1. Достаёт подписанную ссылку на HLS-поток (см. `AGENTS.md` про цепочку).
2. Играет её через ExoPlayer (`media3`) с аппаратным декодированием.
3. При ошибке, зависании или потере сети — сам перезапускает поток.

Ничего лишнего: одна Activity, чёрный фон, один `SurfaceView`.

## Требования

- Android 7.0+ (API 24), landscape, управление с пульта.
- Интернет.

## Сборка

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk17+
./gradlew :app:assembleRelease
```

Готовый APK: `app/build/outputs/apk/release/app-release.apk`.

`local.properties` (путь к SDK) в репозиторий не коммитится — создайте его
локально:

```properties
sdk.dir=/path/to/android-sdk
```

## Установка на приставку

```bash
adb connect <IP-приставки>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

Или скопируйте APK на флешку и установите любым файловым менеджером
(разрешите установку из неизвестных источников).

Release-APK подписан debug-ключом, чтобы его можно было поставить без
отдельной настройки подписи. Для публикации замените `signingConfig` в
`app/build.gradle.kts` на свой keystore.

## Размер и потребление

- Release APK ~1.1 МБ (R8 + shrinkResources).
- Один `classes.dex`, без нативных `.so` и тяжёлых библиотек.
- Никаких анимаций и лишних экранов — минимум RAM/CPU.

## Поведение

| Событие | Реакция |
| --- | --- |
| Ошибка плеера | Перезапуск через 2 с (задержка растёт до 30 с) |
| Зависание потока | Watchdog по позиции воспроизведения, перезапуск |
| Потеря сети | Ждём появления сети и запускаем заново |
| Истечение токена | Ссылка перезапрашивается при каждом перезапуске |
| Экран | Не гаснет (wake lock + FLAG_KEEP_SCREEN_ON) |

## Структура

```
app/src/main/java/live/telik/tv/
├── MainActivity.java     — плеер, ретраи, immersive, wake lock
└── StreamResolver.java   — получение ссылки на поток
```

