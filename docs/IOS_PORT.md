# StoykoMessenger для iOS: Kotlin Multiplatform + сборка .ipa на GitHub

## Как разложить файлы

Распакуйте архив в корень репозитория, поверх существующих файлов. Свои Android-файлы
(`app/`, `core/`, `domain/`) эти файлы не трогают.

```
StoykoMessenger/
├── settings.gradle.kts          ← include(":shared") + флаг -Psm.iosOnly=true
├── build.gradle.kts             ← плагины KMP / Compose подключены через apply false
├── gradle.properties            ← kotlin.native.ignoreDisabledTargets и др.
├── gradle/libs.versions.toml    ← kotlin, compose-multiplatform, новые плагины
├── app/ core/ domain/           ← Android, как было
├── shared/                      ← НОВЫЙ общий модуль (KMP)
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/com/stoyko/smessenger/shared/
│       │   ├── platform/Platform.kt     expect: deflate, KeyValueStore, NetworkMonitor, BackgroundWork
│       │   ├── bubbles/BubbleCodec.kt   формат пузырей + правила моста (касание, лимиты)
│       │   ├── bubbles/BubblePage.kt    CSP + KIT_CSS + KIT_JS + SIZE_JS (перенесено из HtmlBubbles.kt)
│       │   └── audio/ThemeMusic.kt      сезоны, логика «заглушек», expect LoopPlayer
│       ├── commonTest/…                 тесты общего кода
│       ├── androidMain/…                actual: java.util.zip, MediaPlayer, ConnectivityManager
│       └── iosMain/…                    actual: zlib, AVAudioPlayer, WKWebView, NWPathMonitor,
│                                        BGTaskScheduler + Compose-экран, MainViewController
├── iosApp/
│   ├── project.yml              ← XcodeGen (.xcodeproj генерируется, в git не хранится)
│   ├── ldid-entitlements.plist
│   ├── Sources/iOSApp.swift, ContentView.swift
│   └── Resources/music/         ← сюда CI кладёт .caf из app/src/main/assets/music
├── scripts/ios/convert_audio.sh ← ogg + json(loopStart) → intro.caf + loop.caf (ffmpeg)
└── .github/workflows/build_ios_ipa.yml
```

## Что переносить в commonMain дальше

Готовы сейчас: кодек и страница HTML-пузырей, правила моста, сезоны, логика музыки темы.

| Откуда (Android) | Куда | Что поменять |
|---|---|---|
| `core/vo/*` (Contact, Message, User…) | `shared/commonMain/…/vo` | убрать Room-аннотации или перейти на Room KMP (см. ниже) |
| `core/repository/*` | `commonMain` | интерфейсы в common, реализации на Room KMP |
| `core/db/*` (Room 2.7) | `commonMain` + `ksp` для всех таргетов | Room 2.7 уже умеет KMP: `androidx.room` + `sqlite-bundled`, `BundledSQLiteDriver()` на iOS |
| `domain/tox/ToxTypes, ToxIdValidator, ToxUtil, ToxConstants` | `commonMain` | чистый Kotlin, переносится как есть |
| `domain/tox/BootstrapNodeJsonParser` | `commonMain` | `org.json` → `kotlinx.serialization` |
| `domain/tox/ToxWrapper` (JNI `libjni-c-toxcore.so`) | `expect class Tox` | iOS: собрать c-toxcore под iosArm64 и подключить через cinterop (`.def` + статическая `libtoxcore.a`) |
| `app/chat/HtmlBubbles` (pack/unpack/page) | уже в `shared` | в Android: `fun pack(h) = BubbleCodec.pack(h)`, `fun page(h, p) = BubblePage.page(h, p?.toString() ?: "{}")` |
| `app/ui/ThemeMusic` | уже в `shared` (логика) | Android может остаться на своём SeasonMusic |
| UI на View/Fragment | **не переносится** | на iOS экраны пишутся заново на Compose Multiplatform (`iosMain/ui`) |

Подключить `shared` к Android-приложению: в `app/build.gradle.kts` → `implementation(project(":shared"))`,
в `Application.onCreate()` → `SharedAndroid.init(this)`.

## Ограничения iOS, о которых нужно знать заранее

1. **Фон.** Tox, Tor и прямые P2P-сокеты в фоне iOS не держит: через ~30 с после сворачивания
   приложение замораживается. Есть `BackgroundWork.finishInBackground` (доделать отправку файла)
   и `scheduleRefresh` (система будит «когда сочтёт нужным»). Мгновенные входящие в фоне —
   только через APNs: нужен сервер-ретранслятор и платный аккаунт разработчика.
2. **VPN / NetworkExtension.** Фреймворк линкуется, но свой VPN (WARP, split tunnel) на iOS —
   это отдельный таргет `NEPacketTunnelProvider` (app extension) с entitlement
   `com.apple.developer.networking.networkextension`. При подписи через ldid он работает только
   в TrollStore или на джейлбрейке; при переподписи бесплатным Apple ID этот entitlement не выдаётся.
3. **Встроенные бинарники Tor/lyrebird** (`.so` для Android) на iOS не годятся: исполнять отдельные
   процессы iOS не даёт. Есть путь через Tor.framework (iCepa), встроенный как библиотека.
4. **OpenGLES** на iOS устарел (Apple советует Metal), но пока работает. Compose сам рисует через Metal.
5. **Ogg** AVFoundation не играет — отсюда конвертация в CAF/ALAC в CI.
6. **Подпись ldid** = нет настоящего сертификата. Установка: TrollStore / джейлбрейк, или
   переподпись своим Apple ID в Sideloadly/AltStore (бесплатный ID — 7 дней, до 3 приложений).

## Локально (если появится Mac)

```bash
bash scripts/ios/convert_audio.sh
./gradlew -Psm.iosOnly=true :shared:linkDebugFrameworkIosSimulatorArm64   # для симулятора
cd iosApp && xcodegen generate && open StoykoMessenger.xcodeproj
```
Для симулятора поменяйте путь фреймворка в `project.yml` на `iosSimulatorArm64/debugFramework`.

## Версии

Kotlin 2.3.21 (ближе всего к AGP 9.0.1, поддерживает Gradle до 9.3 официально, на 9.4 работает
с предупреждением), Compose Multiplatform 1.12.2. Если хотите полную официальную совместимость
с Gradle 9.4 — Kotlin 2.4.21, но для него нужен Xcode 26.4 (раннер `macos-26`).
Kotlin-плагин один на весь проект, поэтому новая версия применяется и к `app`/`core`/`domain`:
после обновления один раз соберите APK и проверьте, что всё в порядке.

## Автоперенос из Android-кода (с версии 4.13.1)

Android-проект кладётся в репозиторий как есть - iOS-часть подключается сама:

- `scripts/ios/patch_gradle.py` (шаг CI) дописывает `:shared` в `settings.gradle.kts`,
  плагины KMP в `build.gradle.kts` и `libs.versions.toml`, только если их там ещё нет;
- задача `:shared:syncPortedSources` при каждой сборке копирует в `commonMain` свежие
  `ChessEngine.kt`, `Match3Engine.kt`, `DurakEngine.kt`, `Match3Levels.kt`, `PublicKey.kt`, `ToxTypes.kt`,
  `ToxIdValidator.kt`, `ToxConstants.kt` и набор стилей HTML-пузырей из `HtmlBubbles.kt`,
  заменяя JVM-вызовы (`java.util.Random`, `System.currentTimeMillis`, `System.arraycopy`,
  `String.format`) на мультиплатформенные. Список файлов - `portedFiles` в `shared/build.gradle.kts`;
- версия iOS-приложения берётся из `update.json` (`versionName` / `versionCode`).

### Что уже работает на iOS
Вкладки «Чаты / Игры / Настройки»; шахматы против бота (3 уровня, сохранение партии,
отмена хода); «Три в ряд» со всеми уровнями Android-версии (бонусы, лёд, листья, золото,
каскады, анимация падения, подсказка, звёзды); «Дурак» (DurakEngine + DurakBot из Android,
2-4 игрока, подкидной/переводной/простой); блэкджек; змейка; иконка из Android ic_launcher;
сезонные темы (авто/выбор) с музыкой;
HTML-пузыри; проверка Tox ID.

### Следующие этапы
1. Tox: сборка c-toxcore + libsodium под iOS в CI и мост cinterop - переписка, контакты.
2. «Бег по рельсам»: World/Models/Mesh уже чистый Kotlin, нужен рендер Renderer.kt на Metal/GLES iOS.
3. Сетевые игры (GameNet/DurakNet) - после Tox.
4. VPN: вместо libv2ray.aar (Android) - LibXray для iOS в NetworkExtension (только TrollStore/платный аккаунт).
