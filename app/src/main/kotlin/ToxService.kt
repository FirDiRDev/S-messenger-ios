// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import java.util.Timer
import javax.inject.Inject
import kotlin.concurrent.schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import com.stoyko.smessenger.tox.ToxStarter
import com.stoyko.smessenger.core.repository.UserRepository
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.FriendRequest
import com.stoyko.smessenger.domain.feature.CallManager
import com.stoyko.smessenger.domain.feature.CallState
import com.stoyko.smessenger.domain.feature.FriendRequestManager
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.ToxSaveStatus

private const val TAG = "ToxService"
private const val NOTIFICATION_ID = 1984
// Нет связи - переподключаемся быстрее (было 15 с).
private const val BOOTSTRAP_INTERVAL_MS = 8_000L
private const val UDP_RETRY_MS = 180_000L

class ToxService : LifecycleService() {
    companion object {
        /** Сервис создан и ещё не уничтожен. */
        @Volatile
        var running = false
            private set
    }

    private val diag = com.stoyko.smessenger.domain.Diagnostics.coroutineHandler("фоновый сервис")
    private val channelId = "ToxService"

    private var connectionStatus: ConnectionStatus? = null

    private val notifier by lazy { NotificationManagerCompat.from(this) }
    private var bootstrapTimer = Timer()

    private val knownFriendRequests = mutableSetOf<FriendRequest>()

    // Нужен, чтобы Android не отбрасывал широковещательные пакеты поиска
    // собеседников в локальной сети (Wi-Fi без интернета, точка доступа).
    private var multicastLock: WifiManager.MulticastLock? = null

    // Во время звонка держим Wi-Fi в режиме низкой задержки и не даём CPU уснуть:
    // меньше обрывов и задержек звука при выключенном экране.
    private var callWifiLock: WifiManager.WifiLock? = null
    // Android 10-13: «низкая задержка» действует только при включённом экране, поэтому вместе с ним
    // держим и «высокую производительность» - Wi-Fi не уходит в энергосбережение при погасшем экране
    // (на Android 14+ система сама управляет этим режимом, отдельная блокировка там ничего не даёт).
    private var callWifiPerfLock: WifiManager.WifiLock? = null
    private var callWakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    private fun acquireCallLocks() {
        com.stoyko.smessenger.domain.feature.PowerHint.inCall = true
        // Звонок начинается без сети Tox - сразу переподключаемся к самым быстрым узлам.
        if (connectionStatus == null || connectionStatus == ConnectionStatus.None) tox.kickBootstrap()
        try {
            if (callWifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                callWifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
                    ?.createWifiLock(mode, "smessenger:call")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            if (callWifiPerfLock == null && Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..33) {
                callWifiPerfLock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "smessenger:call-perf")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            com.stoyko.smessenger.domain.feature.NetHint.refresh(this)
            if (callWakeLock == null) {
                callWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smessenger:call")
                    .apply {
                        setReferenceCounted(false)
                        acquire(3 * 60 * 60 * 1000L)
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire call locks: $e")
        }
    }

    // «Стабильная связь в фоне»: без этого при выключенном экране процессор засыпает,
    // ядро перестаёт отвечать релеям, и собеседник видит нас «не в сети» каждые несколько минут.
    private var keepAliveWakeLock: PowerManager.WakeLock? = null
    private var keepAliveWifiLock: WifiManager.WifiLock? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    @Suppress("DEPRECATION")
    private fun startKeepAlive() {
        val mode = settings.batteryMode
        if (mode == 2) return
        try {
            keepAliveWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smessenger:keepalive")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
            keepAliveWifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(
                    if (mode == 0) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL,
                    "smessenger:keepalive",
                )
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire keep-alive locks: $e")
        }
    }

    // Режим батареи меняется в настройках - применяем сразу, без перезапуска.
    private val batteryPrefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "battery_mode") {
            stopKeepAlive()
            startKeepAlive()
            updateSaver()
        }
    }

    private fun watchBatteryMode(on: Boolean) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (on) prefs.registerOnSharedPreferenceChangeListener(batteryPrefListener) else prefs.unregisterOnSharedPreferenceChangeListener(batteryPrefListener)
    }

    // ---------- Ускорение на время передачи файлов ----------
    // Пока идёт загрузка/отправка файла, Wi-Fi переводится в режим низкой задержки (без энергосбережения
    // радиомодуля) - скорость не падает. Без передач замок снимается и батарея не тратится зря.
    private var boostWifiLock: WifiManager.WifiLock? = null
    private val boostHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val boostTick = object : Runnable {
        override fun run() {
            val busy = com.stoyko.smessenger.domain.feature.TransferMonitor.busy
            if (busy && boostWifiLock == null) {
                boostWifiLock = runCatching {
                    @Suppress("DEPRECATION")
                    val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)?.createWifiLock(mode, "smessenger:transfer")
                        ?.apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                }.getOrNull()
            } else if (!busy && boostWifiLock != null) {
                runCatching { boostWifiLock?.takeIf { it.isHeld }?.release() }
                boostWifiLock = null
            }
            // Проверка раз в 2 с во время передачи и раз в 10 с в простое - почти без затрат энергии.
            boostHandler.postDelayed(this, if (busy) 2_000 else 10_000)
        }
    }

    private fun stopBoost() {
        boostHandler.removeCallbacks(boostTick)
        runCatching { boostWifiLock?.takeIf { it.isHeld }?.release() }
        boostWifiLock = null
    }

    private fun stopKeepAlive() {
        runCatching { keepAliveWakeLock?.takeIf { it.isHeld }?.release() }
        keepAliveWakeLock = null
        runCatching { keepAliveWifiLock?.takeIf { it.isHeld }?.release() }
        keepAliveWifiLock = null
    }

    /**
     * Узлы проверяются сами, в фоне: при запуске (если давно не проверяли), каждые 30 минут и
     * при смене сети. Живые и быстрые узлы встают в начало списка подключения, мёртвые - в конец,
     * поэтому связь находится быстрее. В режиме экономии энергии - только при смене сети.
     */
    private fun startNodeWatch() {
        lifecycleScope.launch(Dispatchers.IO + diag) {
            while (true) {
                if (!com.stoyko.smessenger.ui.EnergySaver.on) runNodeCheck(25 * 60_000L)
                kotlinx.coroutines.delay(30 * 60_000L)
            }
        }
    }

    /**
     * Сторож узлов (см. NodeWatchdog): каждые 1,5 минуты при включённом экране (5 минут - при
     * выключенном, 10 - в экономии энергии) проверяет рабочие узлы и запас; если связь тормозит -
     * заранее подставляет свежие узлы.
     */
    private fun startNodeWatchdog() {
        lifecycleScope.launch(Dispatchers.IO + diag) {
            kotlinx.coroutines.delay(20_000)
            while (true) {
                // Во время звонка не проверяем узлы: десятки соединений разом добавляют задержку
                // и «рывки» звуку; проверка продолжится после звонка.
                if (!com.stoyko.smessenger.domain.feature.PowerHint.inCall) runCatching {
                    com.stoyko.smessenger.settings.NodeWatchdog.tick(
                        this@ToxService,
                        (application as App).component.nodeRegistry(),
                        online = connectionStatus != null && connectionStatus != ConnectionStatus.None,
                    ) {
                        Log.i(TAG, "Node watchdog: switching to fresh nodes")
                        tox.kickBootstrap()
                    }
                }
                val wait = when {
                    com.stoyko.smessenger.ui.EnergySaver.on -> 10 * 60_000L
                    com.stoyko.smessenger.domain.feature.PowerHint.screenOff -> 5 * 60_000L
                    else -> 90_000L
                }
                kotlinx.coroutines.delay(wait)
            }
        }
    }

    private fun runNodeCheck(minIntervalMs: Long) {
        val alive = runCatching { com.stoyko.smessenger.settings.NodeHealth.quickCheck(this, minIntervalMs) }.getOrDefault(-1)
        Log.i(TAG, "Background node check: alive=$alive")
        // Ещё не в сети - сразу пробуем снова, уже с живыми узлами в начале списка.
        if (alive > 0 && connectionStatus == ConnectionStatus.None) tox.kickBootstrap()
    }

    /** Адреса устройства в каждой сети - чтобы заметить смену адреса без смены сети. */
    private val linkAddrs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Смена сети (Wi-Fi <-> мобильная, новый адрес) - сразу переподключаемся, а не ждём таймаутов. */
    private fun watchNetwork() {
        com.stoyko.smessenger.domain.feature.NetHint.refresh(this)
        com.stoyko.smessenger.transport.TransportManager.onVpnChanged(com.stoyko.smessenger.vpn.ForeignVpn.anyVpn(this))
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                Log.i(TAG, "Network available, re-bootstrapping")
                com.stoyko.smessenger.transport.TransportManager.onVpnChanged(com.stoyko.smessenger.vpn.ForeignVpn.anyVpn(this@ToxService))
                com.stoyko.smessenger.domain.feature.NetHint.refresh(this@ToxService)
                com.stoyko.smessenger.domain.Diagnostics.crumb("сеть появилась: " + DiagnosticsExtra.networkType(this@ToxService))
                tox.kickBootstrap()
                // Новая сеть - другие узлы могут быть доступны/заблокированы: быстро перепроверяем.
                lifecycleScope.launch(Dispatchers.IO + diag) {
                    kotlinx.coroutines.delay(1_500)
                    runNodeCheck(3 * 60_000L)
                }
            }

            override fun onLost(network: android.net.Network) {
                com.stoyko.smessenger.domain.Diagnostics.crumb("сеть пропала")
                linkAddrs.remove(network.toString())
                com.stoyko.smessenger.domain.feature.NetHint.refresh(this@ToxService)
                com.stoyko.smessenger.transport.TransportManager.onVpnChanged(com.stoyko.smessenger.vpn.ForeignVpn.anyVpn(this@ToxService))
                tox.isBootstrapNeeded = true
            }

            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                val was = com.stoyko.smessenger.domain.feature.NetHint.wifi
                com.stoyko.smessenger.domain.feature.NetHint.refresh(this@ToxService)
                if (was != com.stoyko.smessenger.domain.feature.NetHint.wifi) {
                    com.stoyko.smessenger.domain.Diagnostics.crumb("сеть: " + DiagnosticsExtra.networkType(this@ToxService))
                }
            }

            // Тот же Wi-Fi, но новый адрес (роутер перевыдал, переход между точками доступа):
            // старые соединения мертвы - переподключаемся сразу, не ждём минуту таймаутов.
            override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) {
                // Только IPv4: временные IPv6-адреса меняются сами по себе каждые несколько часов.
                val addrs = lp.linkAddresses.map { it.address }.filterIsInstance<java.net.Inet4Address>()
                    .map { it.hostAddress.orEmpty() }.sorted().joinToString(",")
                if (addrs.isEmpty()) return
                val key = network.toString()
                val prev = linkAddrs.put(key, addrs)
                if (prev != null && prev != addrs) {
                    Log.i(TAG, "Address changed on $key, re-bootstrapping")
                    com.stoyko.smessenger.domain.Diagnostics.crumb("сменился адрес в сети - переподключение")
                    tox.kickBootstrap()
                }
            }
        }
        try {
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Unable to watch network: $e")
        }
    }

    // ---------- Экономия энергии ----------
    // Экран выключен: ядро крутится реже, поиск собеседников в локальной сети (multicast -
    // Wi-Fi принимает весь широковещательный трафик) приостанавливается. Экран включился -
    // всё сразу на полную скорость и быстрая переподключка.
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    com.stoyko.smessenger.domain.feature.PowerHint.screenOff = true
                    runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    com.stoyko.smessenger.domain.feature.PowerHint.screenOff = false
                    runCatching { multicastLock?.takeIf { !it.isHeld }?.acquire() }
                    // Включили экран, а мы не в сети - переподключаемся сразу, не ждём таймера.
                    if (connectionStatus == null || connectionStatus == ConnectionStatus.None) tox.kickBootstrap()
                }
                android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> updateSaver()
            }
        }
    }

    private fun updateSaver() {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        com.stoyko.smessenger.domain.feature.PowerHint.saver =
            settings.batteryMode == 2 || pm?.isPowerSaveMode == true
    }

    private fun watchScreen(on: Boolean) {
        if (on) {
            val f = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(this, screenReceiver, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            }
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            com.stoyko.smessenger.domain.feature.PowerHint.screenOff = pm?.isInteractive == false
            updateSaver()
        } else {
            runCatching { unregisterReceiver(screenReceiver) }
            com.stoyko.smessenger.domain.feature.PowerHint.screenOff = false
        }
    }

    private fun releaseCallLocks() {
        com.stoyko.smessenger.domain.feature.PowerHint.inCall = false
        callWifiLock?.takeIf { it.isHeld }?.release()
        callWifiLock = null
        runCatching { callWifiPerfLock?.takeIf { it.isHeld }?.release() }
        callWifiPerfLock = null
        callWakeLock?.takeIf { it.isHeld }?.release()
        callWakeLock = null
    }

    @Inject
    lateinit var tox: Tox

    @Inject
    lateinit var toxStarter: ToxStarter

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var callManager: CallManager

    @Inject
    lateinit var friendRequestManager: FriendRequestManager

    @Inject
    lateinit var proximityScreenOff: ProximityScreenOff

    @Inject
    lateinit var groupCallManager: com.stoyko.smessenger.groups.GroupCallManager

    @Inject
    lateinit var settings: com.stoyko.smessenger.settings.Settings

    @Inject
    lateinit var autoDelete: com.stoyko.smessenger.chat.AutoDelete

    @Inject
    lateinit var scheduled: com.stoyko.smessenger.chat.ScheduledMessages

    @Inject
    lateinit var stories: com.stoyko.smessenger.stories.StoryStore

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.app_name))
            .build()

        notifier.createNotificationChannel(channel)
    }

    private fun subTextFor(status: ConnectionStatus) = when (status) {
        ConnectionStatus.None -> getText(R.string.atox_offline)
        ConnectionStatus.TCP -> getText(R.string.atox_connected_with_tcp)
        ConnectionStatus.UDP -> getText(R.string.atox_connected_with_udp)
    }

    private fun notificationFor(status: ConnectionStatus?): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntentCompat.getActivity(this, 0, notificationIntent, 0)
            }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ResourcesCompat.getColor(resources, R.color.colorPrimary, null))
            .setContentIntent(pendingIntent)
            .setContentTitle(getString(R.string.tox_service_running))

        if (status != null) {
            // Either we haven't received a status from Tox yet, or we don't
            // have notification permissions meaning we wouldn't be able to
            // update a status if we showed one.
            builder.setContentText(subTextFor(status))
        }

        return builder.build()
    }

    override fun onCreate() {
        running = true
        (application as App).component.inject(this)

        super.onCreate()
        try {
            com.stoyko.smessenger.domain.Diagnostics.track("старт фонового сервиса") { startSafely() }
        } catch (e: Exception) {
            // Сервис не должен ронять приложение: записываем причину и останавливаемся.
            com.stoyko.smessenger.domain.Diagnostics.record("фоновый сервис: запуск", e, fatal = false)
            stopSelf()
        }
    }

    private fun startSafely() {

        multicastLock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("smessenger-lan")
            ?.apply {
                setReferenceCounted(false)
                try {
                    acquire()
                } catch (e: SecurityException) {
                    Log.w(TAG, "No multicast permission: $e")
                }
            }

        val canPostNotifications = ActivityCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!canPostNotifications) {
            Log.w(TAG, "Notifications disallowed")
        }

        createNotificationChannel()

        // startForeground обязан прозвучать в течение 5 секунд после startForegroundService,
        // поэтому вызываем его до любой другой работы. Тип SPECIAL_USE есть только с Android 14.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notificationFor(connectionStatus),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notificationFor(connectionStatus))
        }

        if (tox.started) {
            continueStart(canPostNotifications)
        } else {
            // Сервис перезапущен системой: открываем профиль в фоне, а не в главном потоке
            // (иначе на слабых телефонах - зависание и «приложение не отвечает»).
            toxStarter.tryLoadToxAsync(null) { status ->
                if (destroyed) return@tryLoadToxAsync
                if (status == ToxSaveStatus.Ok) {
                    try {
                        continueStart(canPostNotifications)
                    } catch (e: Exception) {
                        com.stoyko.smessenger.domain.Diagnostics.record("фоновый сервис: запуск", e, fatal = false)
                        stopSelf()
                    }
                } else {
                    // Нет профиля или нужен пароль: тихо останавливаемся, НЕ трогая ядро.
                    Log.e(TAG, "Service started without an unlocked profile")
                    stopSelf()
                }
            }
        }
    }

    private var continued = false
    private var destroyed = false

    private fun continueStart(canPostNotifications: Boolean) {
        if (continued) return
        continued = true
        startKeepAlive()
        watchBatteryMode(true)
        watchScreen(true)
        boostHandler.post(boostTick)
        // Память: удаляем старые копии и файлы по настройке «Хранить файлы из чатов».
        Thread { com.stoyko.smessenger.settings.StorageCleaner.autoClean(this) }.start()
        // Обновления приложения проверяются и в фоне (раз в 6 часов) - придёт уведомление.
        startUpdateChecks()
        // Свежие узлы раз в 5 часов (в фоне): скачать, проверить скорость и переподключиться к быстрым.
        com.stoyko.smessenger.settings.NodeHealth.autoUpdate(this) {
            runCatching { (application as App).component.nodeRegistry().reset() }
            // Реестр перечитывается в фоне - даём ему время и подключаемся к самым быстрым узлам.
            lifecycleScope.launch(Dispatchers.IO + diag) {
                kotlinx.coroutines.delay(1_500)
                tox.kickBootstrap()
            }
        }
        watchNetwork()
        com.stoyko.smessenger.transport.TransportManager.attach(this) // «Каналы связи»: Tox / Tor / Wi-Fi / Bluetooth
        // Синхронизация с другим своим телефоном (после переноса профиля), по Wi-Fi.
        runCatching { com.stoyko.smessenger.profile.DeviceSync.start(this) }
        startNodeWatch()
        startNodeWatchdog()
        autoDelete.start()
        scheduled.start()
        stories.start()
        // Звонки - через динамик у уха (как обычный телефон), если это не выключено в настройках.
        callManager.preferEarpiece = settings.callEarpiece
        groupCallManager.preferEarpiece = settings.callEarpiece

        lifecycleScope.launch(Dispatchers.Default + diag) {
            userRepository.get(tox.publicKey.string())
                .filterNotNull()
                .filter { it.connectionStatus != connectionStatus }
                .flowWithLifecycle(lifecycle)
                .collect { user ->
                    connectionStatus = user.connectionStatus
                    com.stoyko.smessenger.transport.TransportManager.onToxStatus(connectionStatus)
                    DiagnosticsExtra.toxStatus = connectionStatus?.name ?: "-"
                    com.stoyko.smessenger.domain.Diagnostics.crumb("сеть Tox: ${connectionStatus?.name}")
                    if (canPostNotifications) {
                        notifier.notify(NOTIFICATION_ID, notificationFor(connectionStatus))
                    }

                    if (connectionStatus == ConnectionStatus.None) {
                        Log.i(TAG, "Gone offline, scheduling bootstrap")
                        bootstrapTimer.schedule(BOOTSTRAP_INTERVAL_MS, BOOTSTRAP_INTERVAL_MS) {
                            Log.i(TAG, "Been offline for too long, bootstrapping")
                            tox.isBootstrapNeeded = true
                        }
                    } else {
                        Log.i(TAG, "Online, cancelling bootstrap")
                        bootstrapTimer.cancel()
                        bootstrapTimer = Timer()
                        tox.onConnected()
                        if (connectionStatus == ConnectionStatus.TCP) {
                            // Только через ретранслятор (TCP): раз в 3 минуты пробуем найти прямой
                            // путь (UDP) - звонки и файлы так быстрее и стабильнее.
                            bootstrapTimer.schedule(UDP_RETRY_MS, UDP_RETRY_MS) {
                                Log.i(TAG, "TCP only, trying to reach UDP again")
                                tox.isBootstrapNeeded = true
                            }
                        }
                    }
                }
        }

        lifecycleScope.launch(Dispatchers.Default + diag) {
            friendRequestManager.getAll()
                .filterNotNull()
                .flowWithLifecycle(lifecycle)
                .collect { friendRequests ->
                    val finishedFriendRequests = knownFriendRequests.minus(friendRequests.toSet())
                    finishedFriendRequests.forEach {
                        knownFriendRequests.remove(it)
                        notifier.cancel(it.publicKey.hashCode())
                    }
                    knownFriendRequests.addAll(friendRequests)
                }
        }

        lifecycleScope.launch(diag) {
            groupCallManager.activeChat.collect { chat ->
                if (chat != null) {
                    acquireCallLocks()
                } else if (callManager.inCall.value !is CallState.InCall) {
                    releaseCallLocks()
                }
            }
        }

        lifecycleScope.launch(diag) {
            callManager.inCall.collect {
                if (it is CallState.InCall) {
                    acquireCallLocks()
                    if (!callManager.speakerphoneOn) {
                        proximityScreenOff.acquire()
                    }
                } else {
                    proximityScreenOff.release()
                    releaseCallLocks()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        updateHandler.removeCallbacks(updateTick)
        destroyed = true
        running = false
        if (continued) {
            runCatching { scheduled.stop() }
        }
        super.onDestroy()
        bootstrapTimer.cancel()
        multicastLock?.takeIf { it.isHeld }?.release()
        releaseCallLocks()
        watchBatteryMode(false)
        watchScreen(false)
        stopBoost()
        stopKeepAlive()
        networkCallback?.let { cb ->
            runCatching {
                (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        com.stoyko.smessenger.transport.TransportManager.detach()
        runCatching { com.stoyko.smessenger.profile.DeviceSync.stop() }
        groupCallManager.leave()
        if (tox.started) tox.stop()
    }

    private val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val updateTick = object : Runnable {
        override fun run() {
            runCatching { com.stoyko.smessenger.update.Updater.backgroundCheck(this@ToxService) }
            updateHandler.postDelayed(this, 3 * 3600_000L)
        }
    }

    private fun startUpdateChecks() {
        updateHandler.removeCallbacks(updateTick)
        updateHandler.postDelayed(updateTick, 60_000L)
    }

}
