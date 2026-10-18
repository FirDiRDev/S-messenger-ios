# Транспорты Briar: разбор и что перенесено в S-messenger

Исходники: архив `briar-master` (Briar Project, GPLv3). Все пути ниже — относительно корня архива.
Сокращения: `api/` = `bramble-api/src/main/java/org/briarproject/bramble/api`,
`core/` = `bramble-core/src/main/java/org/briarproject/bramble`,
`android/` = `bramble-android/src/main/java/org/briarproject/bramble`,
`briar-android/` = `briar-android/src/main/java/org/briarproject/briar/android`.

---

## 1. Общая схема

Briar делится на два слоя:

* **Bramble** — сетевой и криптографический «движок» (транспорты, синхронизация, ключи);
* **Briar** — приложение (чаты, форумы, блоги) поверх Bramble.

Каждый способ связи — отдельный **плагин транспорта** с идентификатором `TransportId`.
Плагины ничего не знают о сообщениях: они только открывают «трубы» (соединения) к контактам.
Поверх любой трубы Bramble запускает один и тот же протокол синхронизации, зашифрованный
**транспортными ключами** (свои для каждого транспорта и каждого контакта, с ротацией по времени).
Поэтому смена канала (Tor → Wi-Fi → Bluetooth) для пользователя незаметна: сообщения доходят
любым путём, а шифрование одинаковое.

```
 Приложение (briar-core)  ─────────────┐
 Синхронизация (core/sync)             │  одинаково для всех транспортов
 Потоки + транспортные ключи           │  (core/transport, core/crypto)
 ConnectionManager / ConnectionRegistry┘
        ▲            ▲             ▲
   TorPlugin    LanTcpPlugin   BluetoothPlugin   (+ RemovableDrive, Mailbox)
        │            │             │
   onionwrapper   TCP-сокеты    RFCOMM (Android)
   + tor-android  в LAN / AP
```

---

## 2. Плагины: интерфейсы и менеджер

| Компонент | Путь | Что делает |
|---|---|---|
| `Plugin` | `api/plugin/Plugin.java` | База любого транспорта: `getId()`, `getMaxLatency()`, `getMaxIdleTime()`, `start()/stop()`, `getState()` (`STARTING_STOPPING, DISABLED, ENABLING, ACTIVE, INACTIVE`), `getReasonsDisabled()` (битовая маска: пользователь, батарея, мобильные данные…), `shouldPoll()/getPollingInterval()/poll(...)`. |
| `DuplexPlugin` | `api/plugin/duplex/DuplexPlugin.java` | Двунаправленные транспорты (Tor, LAN, Bluetooth): `createConnection(TransportProperties)`, поддержка обмена ключами (`createKeyAgreementListener`, `createKeyAgreementConnection`) и «рандеву» (`createRendezvousEndpoint`) для добавления контактов на расстоянии. |
| `SimplexPlugin` | `api/plugin/simplex/SimplexPlugin.java` | Однонаправленные (флешка, почтовый ящик Mailbox): `createReader/createWriter`. |
| `DuplexTransportConnection` | `api/plugin/duplex/DuplexTransportConnection.java`, `AbstractDuplexTransportConnection.java` | Обёртка над сокетом: reader + writer, свойства удалённой стороны. |
| `TransportId` | `api/plugin/TransportId.java` | Строковый ID: `org.briarproject.bramble.tor` (`api/plugin/TorConstants.java`), `…lan` (`LanTcpConstants.java`), `…bluetooth` (`BluetoothConstants.java`), `…wan` (`WanTcpConstants.java`). |
| `PluginCallback` | `api/plugin/PluginCallback.java` | Обратная связь плагина с менеджером: настройки, локальные свойства (адреса), `pluginStateChanged`, `handleConnection`. |
| `PluginManagerImpl` | `core/plugin/PluginManagerImpl.java` | Создаёт плагины из фабрик (`PluginConfig`), запускает каждый в своём потоке (`PluginStarter`), останавливает (`PluginStopper`), включает/выключает по настройке (`setPluginEnabled`), рассылает события `TransportActiveEvent/TransportInactiveEvent/TransportStateEvent` (`api/plugin/event/*`). |
| `PollerImpl` | `core/plugin/PollerImpl.java` | Периодически (интервал плагина, со случайным разбросом) пытается соединиться со всеми контактами по каждому активному транспорту; при смене сети/появлении контакта — сразу. |
| `BackoffImpl` | `core/plugin/BackoffImpl.java`, `BackoffFactoryImpl.java` | Экспоненциальная пауза между попытками опроса. |
| `PluginModule` | `core/plugin/PluginModule.java` | Dagger-модуль. Набор плагинов для Android задаётся в `briar-android/.../AppModule.java` (`PluginConfig`). |

**Соединения**

| Компонент | Путь | Что делает |
|---|---|---|
| `ConnectionManagerImpl` | `core/connection/ConnectionManagerImpl.java` | Принимает входящие/исходящие трубы любого транспорта и запускает над ними `IncomingDuplexSyncConnection` / `OutgoingDuplexSyncConnection` (там же) или рукопожатие для «ожидающих» контактов (`IncomingHandshakeConnection`, `OutgoingHandshakeConnection`). |
| `ConnectionRegistryImpl` | `core/connection/ConnectionRegistryImpl.java` | Кто сейчас на связи и каким транспортом; события `ContactConnectedEvent/ContactDisconnectedEvent`; приоритет транспортов (лишние соединения закрываются). |
| `DuplexSyncConnection` | `core/connection/DuplexSyncConnection.java` | Чтение тега потока → поиск контакта по тегу → синхронизация. |

---

## 3. Tor

| Компонент | Путь | Что делает |
|---|---|---|
| `TorPlugin` | `core/plugin/tor/TorPlugin.java` | Запуск Tor (`tor.start()`), локальный `ServerSocket` на 127.0.0.1 → скрытый сервис v3 (`tor.publishHiddenService(port, 80, privKey)`), приём входящих (`acceptContactConnections`), исходящие — через SOCKS-фабрику на `.onion` контакта. Главное для нас — `updateConnectionStatus(...)`: в зависимости от сети, страны, зарядки и настроек вызывает `tor.enableBridges(...)` / `disableBridges()`, `enableConnectionPadding`, `enableIpv6`, `enableNetwork`. Для «только IPv6» использует мосты `MEEK` и `SNOWFLAKE`, иначе — `circumventionProvider.getSuitableBridgeTypes(country)`. |
| `TorPluginFactory` | `core/plugin/tor/TorPluginFactory.java` | Общая фабрика; `MAX_LATENCY`, `MAX_IDLE_TIME`, backoff. |
| `AndroidTorPluginFactory` | `android/plugin/tor/AndroidTorPluginFactory.java` | Выбор архитектуры бинарника по `Build.SUPPORTED_ABIS` (`x86_64_pie`, `x86_pie`, `arm64_pie`, `arm_pie`) и создание `new AndroidTorWrapper(app, wakeLockManager, ioExecutor, eventExecutor, architecture, torDirectory, torSocksPort, torControlPort)`. |
| `CircumventionModule` | `core/plugin/tor/CircumventionModule.java` | `CircumventionProviderFactory.createCircumventionProvider()` — встроенный список мостов и стран с цензурой. |
| `TorRendezvousCrypto*` | `core/plugin/tor/TorRendezvousCrypto*.java` | Детерминированные ключи скрытого сервиса для рандеву (добавление контакта по ссылке). |
| `TorTransportConnection` | `core/plugin/tor/TorTransportConnection.java` | Обёртка сокета. |
| Порты | `api/plugin/TorConstants.java` | `DEFAULT_SOCKS_PORT = 59050`, `DEFAULT_CONTROL_PORT = 59051` (в debug +2, см. `briar-android/.../AppModule.java`, `provideTorSocksPort/provideTorControlPort`). Папка — `app.getDir("tor", MODE_PRIVATE)` (`provideTorDirectory`). |
| SOCKS | `core/socks/SocksModule.java` | `SocksSocketFactory` (из библиотеки `org.briarproject:socks-socket`) для исходящих через Tor. |
| Состояние в настройках | `briar-android/settings/ConnectionsFragment.java`, `ConnectionsManager.java`, `TorSummaryProvider.java` | Переключатели транспортов, «Tor: мосты/мобильные данные/только при зарядке», описание состояния. |
| Сеть и батарея | `android/network/AndroidNetworkManager.java`, `android/battery/AndroidBatteryManager.java` | `NetworkStatus(connected, wifi, ipv6Only)` и события зарядки для `updateConnectionStatus`. |

### Как Tor попадает в APK (Android)

* `build.gradle` (корень): `tor_version = "0.4.9.13"`, `lyrebird_version = "0.6.2"`, `onionwrapper_version = '0.1.7'`.
* `bramble-core/build.gradle`: `api "org.briarproject:onionwrapper-core:$onionwrapper_version"` (+ транзитивно `org.briarproject:jtorctl:0.5` — протокол порта управления).
* `bramble-android/build.gradle`:
  * `implementation "org.briarproject:onionwrapper-android:$onionwrapper_version"` — `AndroidTorWrapper`, `AndroidLocationUtilsFactory`;
  * `api 'org.briarproject:dont-kill-me-lib:0.2.8'` — `AndroidWakeLockManager` (нужен `AndroidTorWrapper`);
  * отдельная конфигурация `tor`: `tor "org.briarproject:tor-android:$tor_version"`, `tor "org.briarproject:lyrebird-android:$lyrebird_version"` — это jar-архивы с готовыми `<abi>/libtor.so` и `<abi>/liblyrebird.so` (obfs4, meek, snowflake);
  * задача `unpackTorBinaries` распаковывает их в `src/main/jniLibs` перед сборкой (`preBuild.dependsOn unpackTorBinaries`).
* Контрольные суммы зафиксированы в `bramble-android/witness.gradle` (gradle-witness), например
  `org.briarproject:tor-android:0.4.9.13 … af73abd5…`, `onionwrapper-android:0.1.7 … 15231f0b…`.
* Бинарники запускаются **как программы** из `nativeLibraryDir`, поэтому библиотеки должны
  распаковываться на устройство (legacy-упаковка jniLibs); `packagingOptions.jniLibs.keepDebugSymbols += ['**/*.so']`.
* Управление: порт управления (control port) через jtorctl внутри onionwrapper — `SETCONF DisableNetwork`, `UseBridges`, `Bridge …`, `ClientTransportPlugin`, события `STATUS_CLIENT` (BOOTSTRAP PROGRESS=…), `CIRC`, `HS_DESC`; наружу — `TorWrapper.Observer`: `onState(TorState)`, `onBootstrapPercentage(int)`, `onHsDescriptorUpload`, `onClockSkewDetected`.

---

## 4. Локальная сеть (LAN / точка доступа)

| Компонент | Путь | Что делает |
|---|---|---|
| `TcpPlugin` | `core/plugin/tcp/TcpPlugin.java` | Общая TCP-логика: слушающий сокет, опрос контактов по их адресам, `TcpTransportConnection`. |
| `LanTcpPlugin` | `core/plugin/tcp/LanTcpPlugin.java` | Адреса в локальной сети публикуются контактам как свойства (`PROP_IP_PORTS`, `PROP_PORT`, `PROP_IPV6` — `api/plugin/LanTcpConstants.java`); подключение только к адресам из той же подсети; обмен ключами по QR в одной сети. |
| `AndroidLanTcpPlugin` | `android/plugin/tcp/AndroidLanTcpPlugin.java` | Адрес Wi-Fi через `WifiManager`, режим «телефон — точка доступа» (адрес 192.168.43.1 и т. п.), IPv6 в той же сети, реакция на смену сети. |
| `WanTcpPlugin`, `PortMapperImpl` | `core/plugin/tcp/WanTcpPlugin.java`, `PortMapperImpl.java` | Прямой TCP через интернет с UPnP (по умолчанию выключен). |

**Wi-Fi Direct / «точка доступа» Briar** — не транспорт, а раздача APK без интернета:
`briar-android/hotspot/HotspotManager.java` (`WifiP2pManager.createGroup`, повтор с новым
каналом при `BUSY`, на Android 10+ — `WifiP2pConfig.Builder` с постоянными `setNetworkName("DIRECT-…")`,
`setPassphrase`, `GROUP_OWNER_BAND_2GHZ`; `requestGroupInfo` → имя и пароль → QR «WIFI:…»),
`HotspotViewModel.java`, `WebServer.java` (раздача APK), `ConditionManager*.java` (разрешения/Wi-Fi).
Разрешения — `briar-android/util/PermissionUtils.java`.

---

## 5. Bluetooth

| Компонент | Путь | Что делает |
|---|---|---|
| `AbstractBluetoothPlugin` | `core/plugin/bluetooth/AbstractBluetoothPlugin.java` | Общая логика: у каждого устройства **свой случайный UUID** сервиса (`PROP_UUID`) и адрес (`PROP_ADDRESS`) публикуются контактам через уже существующий защищённый канал; сервер `accept()`; опрос — прямое подключение по адресу+UUID; «отражённый» адрес (Android 6+ не отдаёт свой MAC — его сообщает контакт). |
| `AndroidBluetoothPlugin` | `android/plugin/bluetooth/AndroidBluetoothPlugin.java` | `listenUsingInsecureRfcommWithServiceRecord` / `createInsecureRfcommSocketToServiceRecord` (без сопряжения; шифрование — своё, сверху), поиск устройств (`ACTION_FOUND`, `ACTION_DISCOVERY_FINISHED`), режим видимости, NPE внутри `connect()` → обработка как `IOException`. |
| `BluetoothConnectionLimiterImpl` | `core/plugin/bluetooth/BluetoothConnectionLimiterImpl.java` | Ограничение числа одновременных соединений (Bluetooth плохо держит много). |
| `AndroidBluetoothTransportConnection` | `android/plugin/bluetooth/AndroidBluetoothTransportConnection.java` | Сокет + wake lock на время соединения. |
| Константы | `api/plugin/BluetoothConstants.java` | ID, свойства, по умолчанию выключен. |
| Манифест | `bramble-android/src/main/AndroidManifest.xml` | `BLUETOOTH` (нужен и на 12+ для части Xiaomi/Nubia), `BLUETOOTH_ADMIN maxSdk 30`, `BLUETOOTH_SCAN neverForLocation`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`. |

---

## 6. Ключи, рукопожатие, транспортные ключи

| Компонент | Путь | Что делает |
|---|---|---|
| Обмен ключами при встрече (QR) | `core/keyagreement/KeyAgreementProtocol.java`, `KeyAgreementTaskImpl.java`, `KeyAgreementConnector.java`, `ConnectionChooserImpl.java` | Обязательства (commitment) + ECDH; соединение через любой транспорт с `supportsKeyAgreement()` (LAN, Bluetooth). |
| Добавление по ссылке, рукопожатие | `core/contact/HandshakeManagerImpl.java`, `HandshakeCryptoImpl.java`, `ContactExchangeManagerImpl.java`, `ContactExchangeCryptoImpl.java` | Рукопожатие с подтверждением ключей обеих сторон, обмен свойствами транспортов (адреса, onion, UUID Bluetooth). |
| Рандеву | `core/rendezvous/RendezvousPollerImpl.java`, `RendezvousCryptoImpl.java` | Оба ждут друг друга на детерминированных onion-адресах. |
| Транспортные ключи | `core/transport/KeyManagerImpl.java`, `TransportKeyManagerImpl.java`, `MutableTransportKeys.java`, `ReorderingWindow.java` | Для каждого (контакт × транспорт): ключи тегов и заголовков, ротация по периодам времени (`ROTATE_LABEL`), окно повтора (`ReorderingWindow`) против повторного воспроизведения. |
| Согласование ключей для нового транспорта | `core/transport/agreement/TransportKeyAgreementManagerImpl.java`, `TransportKeyAgreementCryptoImpl.java` | Когда у контакта появляется новый транспорт — новые ключи согласуются через уже работающий защищённый канал. **Эту идею мы взяли для Bluetooth.** |
| Криптография потоков | `core/crypto/TransportCryptoImpl.java`, `StreamEncrypterImpl.java`, `StreamDecrypterImpl.java`, `XSalsa20Poly1305AuthenticatedCipher.java`, `api/transport/TransportConstants.java` | Метка потока (`TAG_LENGTH = 16`), заголовок с одноразовым числом, кадры XSalsa20-Poly1305; допустимое расхождение часов `MAX_CLOCK_DIFFERENCE = 24 ч`. |

---

## 7. Что подходит нам (Tox) и что нет

* **Протокол синхронизации Bramble** нам не нужен: у нас уже есть Tox — сквозное шифрование,
  доставка, звонки, файлы. Значит, «транспортами» для нас становятся **пути, по которым ходит
  само ядро Tox**: напрямую (UDP/TCP-релеи), через SOCKS5 Tor, в локальной сети без интернета.
  Ядро TRIfA (`domain/src/main/java/com/zoffcc/applications/trifa/MainActivity.java`, `init(…, udp_enabled,
  local_discovery_enabled, orbot_enabled, orbot_host, orbot_port, …)`) уже умеет SOCKS5 и поиск в LAN.
* **Tor:** берём ровно стек Briar для Android — `onionwrapper-android` 0.1.7 + `tor-android`
  0.4.9.13 + `lyrebird-android` 0.6.2 + `dont-kill-me-lib` 0.2.8, ту же распаковку бинарников и
  ту же логику мостов (`CircumventionProvider`: страна → типы мостов; в РФ мосты включаются сами).
  Скрытый сервис не нужен: Tox — клиент, ему достаточно SOCKS5 (UDP выключается, иначе IP утёк бы мимо Tor).
* **LAN:** отдельный TCP-плагин не нужен — у Tox есть свой поиск в локальной сети (широковещательный
  UDP). Нужно лишь (1) держать `MulticastLock` (уже есть в `ToxService`), (2) включать UDP в режиме
  без интернета, (3) дать двум телефонам общую сеть без роутера — Wi-Fi Direct, как в `HotspotManager`.
* **Bluetooth:** ядро Tox по Bluetooth работать не может (нет нативов для своего транспорта), поэтому
  сделан отдельный канал для текстовых сообщений по образцу `AndroidBluetoothPlugin`
  (незащищённый RFCOMM без сопряжения, свой UUID у каждого телефона, поиск, ограничение
  соединений) и `TransportKeyAgreementManagerImpl` (ключи нового транспорта — через уже
  защищённый канал, у нас — через Tox).
* **Лицензия.** Briar — GPLv3, S-messenger — GPL-3.0-only: совместимо. Код Briar дословно не
  копировался (кроме имён архитектур Tor и последовательности вызовов API onionwrapper), но
  устройство повторяет его — в заголовках новых файлов указано «по образцу Briar (GPLv3)» с путями.
  Библиотеки Briar Project подключены как зависимости Maven Central; их лицензии (GPLv3 для
  onionwrapper/dont-kill-me-lib, BSD-3 для Tor, BSD для lyrebird) нужно перечислить в «О приложении»
  / `LICENSE`-разделе сторонних компонентов: «Tor (The Tor Project, BSD-3-Clause); lyrebird (BSD-2/3);
  onionwrapper, dont-kill-me-lib — Briar Project, GPLv3».

---

## 8. Реализация в S-messenger

Пакет `app/src/main/kotlin/transport/`:

| Файл | Назначение | Аналог в Briar |
|---|---|---|
| `TransportManager.kt` | Режимы «Авто / Tox / Tor / Без интернета», состояние сети, автопереключение, перезапуск ядра под нужный путь, канал для значка | `PluginManagerImpl`, `PollerImpl`, `TorPlugin.updateConnectionStatus` |
| `TransportHooks.kt` | Параметры запуска ядра (SOCKS5 Tor / UDP для LAN), перехват невидимых пакетов обмена ключами Bluetooth и «друг в сети» | `TransportKeyAgreementManagerImpl` (идея) |
| `TorRunner.kt` | Встроенный Tor: `AndroidTorWrapper`, порты 59180/59181 (не как у Briar — оба приложения уживаются), загрузка %, мосты (Авто по стране / Snowflake / meek / obfs4 / свои / без мостов), сброс при ошибке | `AndroidTorPluginFactory`, `TorPlugin` |
| `WifiDirect.kt` | Сеть Wi-Fi Direct без роутера (создать / найти / подключиться, имя+пароль+QR) | `HotspotManager` |
| `bt/X25519.kt` | X25519 на чистом Kotlin (перенос TweetNaCl, проверен векторами RFC 7748) | — (на Android 5–11 нет XDH) |
| `bt/BtCrypto.kt` | HKDF-SHA256, защищённый сеанс: KK-подобное рукопожатие (3×DH), AES-256-GCM, счётчики против повтора | `TransportCryptoImpl`, `ReorderingWindow` |
| `bt/BtKeys.kt` | Свой ключ Bluetooth, закрепление ключей друзей через пакет Tox `0xB7` | `TransportKeyAgreementManagerImpl` |
| `bt/BtLink.kt` | RFCOMM-сервер, поиск, подключение, не больше 3 сеансов | `AndroidBluetoothPlugin`, `BluetoothConnectionLimiterImpl` |
| `bt/BtChat.kt`, `bt/BtMarks.kt` | Отправка ожидающих сообщений, подтверждения, защита от дублей, значок 🔵 у времени | — |
| `ui/ChannelsScreen.kt`, `ui/ChannelBadge.kt` | Настройки → «Каналы связи», значок канала в шапке и на аватаре в чате | `ConnectionsFragment`, `TorSummaryProvider` |

### Алгоритм «Авто»
1. Прямой Tox (как раньше).
2. Нет связи с сетью Tox N секунд (30/45/90/180, по умолчанию 45), а интернет есть (сеть проверена
   системой, мобильная или VPN) → запускается Tor; когда он загрузится на 100 %, ядро
   перезапускается через SOCKS5 Tor (UDP выключен, DNS мимо Tor не используется).
3. Tor не поднялся за 4 минуты / ошибка → назад к прямому Tox, повтор Tor через 10 минут.
4. Интернета нет вообще → «локальный» путь: ядро с UDP (поиск в LAN), Bluetooth-канал (если включён).
5. Сменилась сеть → снова прямой Tox. Ядро перезапускается не чаще раза в 20 с и только если его
   параметры сети действительно меняются.

### Bluetooth-канал — протокол
* Ключи: у каждого телефона долговременная пара X25519 (только для Bluetooth). Открытый ключ
  отправляется другу невидимым пакетом Tox `[0xB7, тип, версия, ключ(32), адрес BT]` — внутри
  проверенного сквозного канала Tox, поэтому подменить ключ нельзя. Друг отвечает своим ключом.
* UUID сервиса = `nameUUIDFromBytes("SMBT" ‖ ключ)` — у каждого свой (как у Briar), чужие телефоны
  не находят службу по общему UUID.
* Рукопожатие: `I→R: "SMBT"|v1|ToxKey_I|e_I`, `R→I: "SMBT"|v1|ToxKey_R|e_R`; обе стороны ищут
  закреплённый ключ собеседника (нет ключа — сеанс рвётся до любых данных);
  `ikm = DH(e_I,e_R) ‖ DH(s_I,e_R) ‖ DH(e_I,s_R)`, соль = SHA-256(метка ‖ оба сообщения ‖ оба
  долговременных ключа), HKDF-SHA256 → по ключу AES-256-GCM на направление; затем каждая сторона
  шлёт зашифрованное подтверждение (подмена любой стороны обнаруживается сразу). Прямая
  секретность — за счёт одноразовых ключей.
* Кадры: `счётчик(8) ‖ AES-GCM(тип ‖ id ‖ текст)`, nonce = направление ‖ счётчик; счётчик строго
  +1 — повтор, пропуск и перестановка обрывают сеанс.
* По Bluetooth уходят только сообщения, которые ядро Tox так и не приняло (`correlationId < 0`,
  «🕓»), и только пока друг не в сети Tox — дубля по Tox быть не может. Принятые сообщения
  проходят через обычный обработчик входящих (уведомления, подписи, защита от дублей);
  повторы отсекаются по `id` сообщения. Подтверждённые получают ✓ и значок 🔵.

### Проверки (JVM)
* X25519: векторы RFC 7748 §5.2 (оба), §6.1 (Alice/Bob), 1000 итераций — совпадают; ~0,8 мс на операцию.
* HKDF: RFC 5869, тест 1 — совпадает.
* Сеанс: доставка в обе стороны; неверный закреплённый ключ (любой стороны) → отказ;
  повтор кадра → отказ; изменённый бит → отказ; ~20–40 МБ/с (Bluetooth сам ~0,1–0,3 МБ/с).

### Ограничения и что проверить на устройствах
* Сборка Gradle здесь невозможна: API onionwrapper/dont-kill-me-lib взяты из исходников Briar
  (вызовы `AndroidTorWrapper`, `TorWrapper.Observer`, `CircumventionProvider`), код проверен
  компиляцией с заглушками. Формат строк своих мостов: добавляется префикс `Bridge ` (как в списках
  мостов onionwrapper) — проверить на устройстве.
* Tox через Tor ходит только по TCP-релеям; часть выходных узлов Tor не пускает на порт 33445 —
  нужны релеи на 443 (они есть в списке узлов).
* Bluetooth: текст до ~24 КБ на сообщение; файлы по Bluetooth не передаются (медленно, для них — Wi-Fi Direct).
  Для первого поиска телефон друга должен быть «видимым» (кнопка «Сделать видимым»); после первой
  встречи адрес запоминается.
* Прочие функции приложения (браузер, радио, обновления, ИИ) по-прежнему идут мимо Tor — режим
  «Tor» касается сообщений, звонков и файлов Tox.
