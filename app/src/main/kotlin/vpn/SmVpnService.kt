// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.stoyko.smessenger.App
import com.stoyko.smessenger.MainActivity
import com.stoyko.smessenger.R

/**
 * Встроенный VPN (ядро Xray): весь трафик телефона идёт через сервер пользователя
 * (VLESS/VMess/Trojan/Shadowsocks). Сам мессенджер исключён из туннеля (иначе ядро
 * заворачивало бы собственные соединения в себя) и ходит через локальный SOCKS 127.0.0.1:10808.
 * В шторке - уведомление «VPN включён» с кнопкой «Отключить», есть плитка быстрых настроек.
 */
class SmVpnService : VpnService() {
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn(startId)
                return START_NOT_STICKY
            }
            else -> {
                // Система сама перезапустила службу (intent == null), а человек VPN выключил -
                // не включаемся обратно.
                if (intent == null && !wanted(this)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startVpn(intent?.getStringExtra(EXTRA_ID))
            }
        }
        return START_STICKY
    }

    private fun startVpn(id: String?) {
        val store = VpnStore(this)
        val mode = store.mode
        val profile = if (mode == VpnStore.MODE_SERVER) (id?.let { i -> store.profiles().firstOrNull { it.id == i } } ?: store.selected) else null
        val title = if (mode == VpnStore.MODE_SERVER) profile?.name.orEmpty() else store.targetName(this)
        // startForegroundService обязывает показать уведомление сразу, даже если запуск не удастся.
        goForeground(title, connecting = true)
        // Номер запуска: «Отключить» (или новый запуск) меняет его, и устаревший запуск
        // не включает VPN обратно, даже если ядро успело подняться уже после нажатия.
        val gen = GEN.incrementAndGet()
        setWanted(this, true)
        // WARP на ядре Nova: туннель поднимает отдельный процесс «:nova» - без перезапуска приложения.
        if (store.warpOnNova(this)) {
            startOnNova(store, title)
            return
        }
        // Смена ядра (WARP ↔ Xray) в работающем процессе роняла приложение - перезапуск с нужным ядром.
        val engine = if (mode == VpnStore.MODE_WARP) GoGuard.WG else GoGuard.XRAY
        if (GoGuard.conflict(engine)) {
            VpnState.set(VpnState.Status.Connecting, title)
            android.widget.Toast.makeText(this, R.string.vpn_engine_restart, android.widget.Toast.LENGTH_LONG).show()
            // Остановка ядер - в фоне: в главном потоке она могла подвесить экран.
            teardownAsync(applicationContext, gen, finalize = false)
            GoGuard.restartAndStart(this)
            return
        }
        val problem = when {
            mode == VpnStore.MODE_SERVER && profile == null -> getString(R.string.vpn_no_server)
            mode != VpnStore.MODE_WARP && !XrayCore.supported -> getString(R.string.vpn_android7)
            mode == VpnStore.MODE_WARP && store.warpConf.isBlank() -> getString(R.string.warp_no_config)
            mode == VpnStore.MODE_WARP && !org.amnezia.awg.GoBackend.load() -> getString(R.string.warp_unavailable)
            mode == VpnStore.MODE_DPI && !DpiEngine.available(this) -> getString(R.string.dpi_unavailable)
            else -> null
        }
        if (problem != null) {
            VpnState.set(VpnState.Status.Error, title, problem)
            stopForegroundCompat()
            // Прежний режим (если работал) тоже гасим - в фоне.
            teardownAsync(applicationContext, gen, finalize = true)
            stopSelf()
            return
        }
        profile?.let { store.selectedId = it.id }
        VpnState.set(VpnState.Status.Connecting, title)
        worker?.interrupt()
        worker = Thread({
            // Запуски идут строго по очереди: сначала полностью останавливается прежний режим
            // (ядро Xray, движок DPI, туннель WARP), и только потом стартует новый - два режима
            // одновременно не работают никогда. Устаревший запрос (уже нажали другой) пропускается.
            synchronized(ENGINE_LOCK) {
            if (gen != GEN.get()) return@Thread
            try {
                // Туннель Nova (если работал) отпускает сеть раньше, чем поднимется наш.
                NovaLink.stopAndWait(this)
                stopEngines(this)
                if (gen != GEN.get()) return@Thread
                if (mode == VpnStore.MODE_WARP) {
                    startWarp(store, title)
                    // Пока подключались, нажали «Отключить» - гасим только что поднятое и выходим.
                    if (!goOn(gen, title, proxy = false)) {
                        stopEngines(this)
                        return@Thread
                    }
                    // У WARP нет локального SOCKS - мессенджер ходит напрямую (он и так вне туннеля).
                    routeTox(this, false)
                } else {
                    // Сервер + обход DPI (совместный режим) или чистый обход DPI - запускаем движок.
                    val viaDpi = mode == VpnStore.MODE_SERVER && store.serverViaDpi && DpiEngine.available(this)
                    if (mode == VpnStore.MODE_DPI || viaDpi) DpiEngine.start(this, store.dpiArgs)
                    val full = store.fullTunnel
                    val config = if (mode == VpnStore.MODE_SERVER) {
                        VpnLinks.fullConfig(profile!!.link, withTun = full, viaDpi = viaDpi, ctx = this)
                    } else {
                        VpnLinks.bypassConfig(mode, withTun = full, ctx = this)
                    }
                    var fd = 0
                    if (full) {
                        val b = Builder()
                            .setSession(title)
                            .setMtu(1500)
                            .addAddress("10.10.14.1", 30)
                            .addRoute("0.0.0.0", 0)
                            .addDnsServer("1.1.1.1")
                            .addDnsServer("8.8.8.8")
                        // Какие приложения идут через VPN (сам мессенджер - всегда мимо туннеля).
                        SplitTunnel.apply(this, b)
                        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
                        b.setConfigureIntent(openAppIntent())
                        val t = b.establish() ?: throw IllegalStateException(getString(R.string.vpn_no_permission))
                        tun = t
                        fd = t.fd
                    }
                    if (gen != GEN.get()) {
                        stopEngines(this)
                        return@Thread
                    }
                    XrayCore.start(this, config, fd)
                    if (!goOn(gen, title, proxy = true)) {
                        stopEngines(this)
                        return@Thread
                    }
                    routeTox(this, true)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "start failed", e)
                stopEngines(this)
                // Ошибку показываем, только если запуск ещё актуален (не нажали «Отключить»).
                if (gen == GEN.get()) {
                    VpnState.set(VpnState.Status.Error, title, e.message ?: e.toString())
                    stopForegroundCompat()
                    stopSelf()
                }
            } finally {
                updateTile(this)
            }
            }
        }, "vpn-start").apply { isDaemon = true }
        worker!!.start()
        updateTile(this)
    }

    /**
     * Ядро поднялось: «VPN включён», уведомление и счётчик трафика - но только если этот запуск
     * всё ещё актуален. Проверка и смена состояния идут под одной блокировкой с «Отключить»,
     * поэтому выключенный VPN больше не «включается сам» через секунду. false - запуск устарел.
     */
    private fun goOn(gen: Int, title: String, proxy: Boolean): Boolean = synchronized(STATE_LOCK) {
        if (gen != GEN.get()) return@synchronized false
        VpnState.localProxy = proxy
        VpnState.set(VpnState.Status.On, title)
        goForeground(title, connecting = false)
        startStats(title)
        // Сторож: живая проверка туннеля, переподключение после смены сети и при обрыве.
        runCatching { VpnWatchdog.start(this) }
        true
    }

    // ---------- WARP на ядре Nova (отдельный процесс) ----------

    private fun startOnNova(store: VpnStore, title: String) {
        // Ядро Nova собрано для Android 7+ (minSdk 24) - на Android 5-6 его не запускаем:
        // библиотека подключена через tools:overrideLibrary, и запуск там был бы вылетом.
        if (Build.VERSION.SDK_INT < 24) {
            VpnState.set(VpnState.Status.Error, title, getString(R.string.vpn_android7))
            stopForegroundCompat()
            stopSelf()
            return
        }
        val masque = store.warpEngine == VpnStore.WARP_MASQUE
        if (!masque && store.warpConf.isBlank()) {
            VpnState.set(VpnState.Status.Error, title, getString(R.string.warp_no_config))
            stopForegroundCompat()
            stopSelf()
            return
        }
        // Регистрация MASQUE может пойти через HTTP-прокси Xray, пока он ещё работает
        // (статус уже «подключается», поэтому смотрим на сам прокси, а не на статус).
        val proxy = if (VpnState.localProxy) "127.0.0.1:${VpnLinks.HTTP_PORT}" else ""
        VpnState.set(VpnState.Status.Connecting, title)
        val i = Intent(this, NovaVpnService::class.java)
            .setAction(NovaVpnService.ACTION_START)
            .putExtra(NovaVpnService.EXTRA_TITLE, title)
            .putExtra(NovaVpnService.EXTRA_ENGINE, store.warpEngine)
            .putExtra(NovaVpnService.EXTRA_CONF, store.warpConf)
            .putExtra(NovaVpnService.EXTRA_IDENTITY, store.masqueIdentity)
            .putExtra(NovaVpnService.EXTRA_TOKEN, store.warpToken)
            .putExtra(NovaVpnService.EXTRA_DEVICE, store.warpDeviceId)
            .putExtra(NovaVpnService.EXTRA_PROXY, proxy)
        NovaLink.active = true
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            Log.w(TAG, "nova start: $e")
            NovaLink.active = false
            VpnState.set(VpnState.Status.Error, title, e.message ?: e.toString())
            stopForegroundCompat()
            stopSelf()
            return
        }
        // Туннель и уведомление теперь у службы Nova; ядро этого процесса она попросит
        // погасить (HANDOVER) прямо перед тем, как поднять свой туннель.
        stopStats()
        stopForegroundCompat()
        stopSelf()
        updateTile(this)
    }

    // ---------- WARP (AmneziaWG) ----------

    private fun startWarp(store: VpnStore, title: String) {
        val p = Warp.parse(store.warpConf)
        val endpoint = Warp.resolveEndpoint(p.endpoint)
        val b = Builder().setSession(title).setMtu(p.mtu)
        var v6 = false
        for (a in p.addresses.ifEmpty { listOf("172.16.0.2/32") }) {
            val ip = a.substringBefore('/')
            val prefix = a.substringAfter('/', "").toIntOrNull() ?: if (':' in ip) 128 else 32
            if (runCatching { b.addAddress(ip, prefix) }.isSuccess && ':' in ip) v6 = true
        }
        b.addRoute("0.0.0.0", 0)
        if (v6) runCatching { b.addRoute("::", 0) }
        p.dns.ifEmpty { listOf("1.1.1.1", "1.0.0.1") }.forEach { runCatching { b.addDnsServer(it) } }
        // У WARP сокеты движка защищены protect(), петли нет - поэтому сам мессенджер (встроенный
        // браузер, YouTube, Pinterest) тоже идёт через туннель: у WARP нет локального прокси.
        SplitTunnel.apply(this, b, includeSelf = true)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        b.setConfigureIntent(openAppIntent())
        val pfd = b.establish() ?: throw IllegalStateException(getString(R.string.vpn_no_permission))
        // Ядро не знает новых параметров маскировки (S3/S4, I2…) - пробуем проще, а не падаем.
        // Каждой попытке - своя копия дескриптора: при ошибке ядро закрывает переданный ему fd.
        var h = -1
        try {
            for (level in 0..2) {
                val fd = pfd.dup().detachFd()
                h = org.amnezia.awg.GoBackend.awgTurnOn("awg0", fd, Warp.uapi(p, endpoint, level))
                if (h >= 0) break
                Log.w(TAG, "awg turn on failed ($h) at level $level")
            }
        } finally {
            // Своя копия больше не нужна (у ядра - своя); иначе туннель «висел» бы после отключения.
            runCatching { pfd.close() }
        }
        if (h < 0) throw IllegalStateException(getString(R.string.warp_start_failed, h))
        warpHandle = h
        runCatching { org.amnezia.awg.GoBackend.awgGetSocketV4(h).takeIf { it >= 0 }?.let { protect(it) } }
        runCatching { org.amnezia.awg.GoBackend.awgGetSocketV6(h).takeIf { it >= 0 }?.let { protect(it) } }
    }


    // ---------------- трафик в уведомлении ----------------
    // Пока VPN работает, в уведомлении видно, сколько отдано и принято и текущая скорость -
    // сразу понятно, живое ли соединение (цифры растут) или «зависло».

    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var statsName = ""
    private var baseTx = -1L
    private var baseRx = -1L
    private var lastTx = 0L
    private var lastRx = 0L
    private var lastAt = 0L
    private var connectedAt = 0L

    private fun startStats(name: String) {
        statsName = name
        connectedAt = System.currentTimeMillis()
        val uid = android.os.Process.myUid()
        baseTx = android.net.TrafficStats.getUidTxBytes(uid)
        baseRx = android.net.TrafficStats.getUidRxBytes(uid)
        lastTx = baseTx
        lastRx = baseRx
        lastAt = android.os.SystemClock.elapsedRealtime()
        statsHandler.removeCallbacks(statsTick)
        statsHandler.postDelayed(statsTick, 2000)
    }

    private fun stopStats() = statsHandler.removeCallbacks(statsTick)

    private fun fmtBytes(b: Long): String = android.text.format.Formatter.formatShortFileSize(this, b.coerceAtLeast(0))

    private val statsTick: Runnable = object : Runnable {
        override fun run() {
            if (VpnState.status == VpnState.Status.Off || VpnState.status == VpnState.Status.Error) return
            val uid = android.os.Process.myUid()
            val tx = android.net.TrafficStats.getUidTxBytes(uid)
            val rx = android.net.TrafficStats.getUidRxBytes(uid)
            if (tx < 0 || rx < 0 || baseTx < 0) return
            val now = android.os.SystemClock.elapsedRealtime()
            val sec = ((now - lastAt) / 1000f).coerceAtLeast(0.5f)
            val down = ((rx - lastRx) / sec).toLong()
            val up = ((tx - lastTx) / sec).toLong()
            lastTx = tx
            lastRx = rx
            lastAt = now
            val line = getString(R.string.vpn_traffic, fmtBytes(tx - baseTx), fmtBytes(rx - baseRx), fmtBytes(down), fmtBytes(up))
            goForeground(statsName, connecting = false, traffic = line, update = true)
            statsHandler.postDelayed(this, 2000)
        }
    }

    /**
     * «Отключить»: сразу - состояние «выключен», уведомление убрано, служба закрывается;
     * ядро/туннель гасятся в фоне (главный поток не ждёт - раньше остановка ядра в onDestroy
     * шла в главном потоке и могла подвесить экран, а запуск, который ещё подключался,
     * потом включал VPN обратно). Можно вызывать сколько угодно раз и во время подключения.
     */
    private fun stopVpn(startId: Int = -1) {
        val gen = markOff(this, user = true)
        stopStats()
        runCatching { VpnWatchdog.stop() }
        stopForegroundCompat()
        teardownAsync(applicationContext, gen, finalize = true)
        // Если уже пришёл новый запуск (нажали «Включить» снова) - служба остаётся для него.
        if (startId >= 0) stopSelfResult(startId) else stopSelf()
    }

    override fun onRevoke() {
        // Туннель забрала наша же служба Nova (процесс «:nova») - это не выключение VPN.
        if (NovaLink.active) {
            stopStats()
            stopForegroundCompat()
            stopSelf()
            return
        }
        // Пользователь включил другой VPN или отозвал разрешение.
        stopVpn()
    }

    override fun onDestroy() {
        stopStats()
        if (NovaLink.active) {
            // Работу передали ядру Nova - здесь гасить нечего (своё ядро оно погасит через HANDOVER).
            super.onDestroy()
            return
        }
        if (VpnState.status != VpnState.Status.Connecting) runCatching { VpnWatchdog.stop() }
        if (VpnState.status == VpnState.Status.On || VpnState.status == VpnState.Status.Connecting) {
            // Служба закрылась не через «Отключить» - всё равно гасим ядро, но в фоне.
            val gen = markOff(this, user = false)
            teardownAsync(applicationContext, gen, finalize = true)
        }
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        31,
        Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_VPN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun goForeground(name: String, connecting: Boolean, traffic: String? = null, update: Boolean = false) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.vpn_title), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        val stop = PendingIntent.getService(
            this,
            32,
            Intent(this, SmVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(if (connecting) R.string.vpn_connecting else R.string.vpn_on_title))
            .setContentText(if (traffic != null) "$name · $traffic" else name)
            .setStyle(if (traffic != null) NotificationCompat.BigTextStyle().bigText(name + "\n" + traffic) else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(!connecting)
            .setUsesChronometer(!connecting)
            // Время работы считается с момента подключения (обновление цифр его не сбрасывает).
            .setWhen(if (!connecting && connectedAt > 0) connectedAt else System.currentTimeMillis())
            .setContentIntent(openAppIntent())
            .addAction(R.drawable.ic_close, getString(R.string.vpn_disconnect), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (update) {
            // Обновление цифр: то же уведомление, без повторного запуска службы.
            runCatching { nm.notify(NOTIFICATION_ID, n) }
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "foreground: $e")
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private val ENGINE_LOCK = Any()

        /** Короткая блокировка «проверить номер запуска и сменить состояние» (не ждёт ядро). */
        private val STATE_LOCK = Any()

        /** Номер последнего запуска/остановки - общий для всех экземпляров службы. */
        private val GEN = java.util.concurrent.atomic.AtomicInteger(0)

        /** Туннель и WARP - общие: новый экземпляр службы гасит то, что поднял прежний. */
        @Volatile
        private var tun: ParcelFileDescriptor? = null

        @Volatile
        private var warpHandle = -1

        private const val RUN_PREFS = "vpn_run"
        private const val KEY_WANTED = "wanted_on"

        /** Человек хочет, чтобы VPN был включён (false - выключил сам; система не включает обратно). */
        private fun wanted(ctx: Context): Boolean =
            ctx.applicationContext.getSharedPreferences(RUN_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

        private fun setWanted(ctx: Context, on: Boolean) {
            ctx.applicationContext.getSharedPreferences(RUN_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, on).apply()
        }

        /**
         * Сразу «выключен»: новый номер (все идущие запуски устаревают), без локального прокси.
         * [user] - выключил человек: отменяется и отложенное включение после смены ядра.
         */
        private fun markOff(ctx: Context, user: Boolean): Int {
            if (user) {
                setWanted(ctx, false)
                GoGuard.cancelPending(ctx)
                NovaLink.stop(ctx)
            }
            return synchronized(STATE_LOCK) {
                val g = GEN.incrementAndGet()
                VpnState.localProxy = false
                VpnState.set(VpnState.Status.Off)
                g
            }
        }

        /** Остановить всё, что могло работать: ядро Xray, движок DPI, туннель WARP. */
        private fun stopEngines(ctx: Context) {
            VpnState.localProxy = false
            XrayCore.stop()
            DpiEngine.stop(ctx)
            val h = warpHandle
            if (h >= 0) {
                warpHandle = -1
                runCatching { org.amnezia.awg.GoBackend.awgTurnOff(h) }
            }
            val t = tun
            tun = null
            runCatching { t?.close() }
        }

        /**
         * Погасить ядро в фоне. Если за это время уже нажали «Включить» снова (номер сменился),
         * ничего не трогаем - новый запуск сам сначала остановит прежнее.
         * [finalize] - вернуть мессенджер в обычную сеть и убрать уведомление.
         */
        private fun teardownAsync(app: Context, gen: Int, finalize: Boolean) {
            Thread({
                synchronized(ENGINE_LOCK) {
                    if (gen == GEN.get()) runCatching { stopEngines(app) }.onFailure { Log.w(TAG, "stop: $it") }
                }
                if (finalize && gen == GEN.get()) {
                    routeTox(app, false)
                    runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) }
                }
                updateTile(app)
            }, "vpn-stop").apply { isDaemon = true }.start()
        }

        /** Хочет ли человек, чтобы VPN был включён (для туннеля Nova после перезапуска приложения). */
        fun novaWanted(ctx: Context): Boolean = wanted(ctx)

        /**
         * Nova поднимает свой туннель: погасить всё, что работает в этом процессе (Xray, DPI,
         * AmneziaWG), и вернуть мессенджер с локального прокси Xray в обычную сеть.
         */
        fun releaseForNova(ctx: Context) {
            val app = ctx.applicationContext
            synchronized(ENGINE_LOCK) {
                // Идущий запуск этого процесса (если был) устаревает.
                GEN.incrementAndGet()
                stopEngines(app)
            }
            routeTox(app, false)
            runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) }
            updateTile(app)
        }

        /** Туннель Nova заработал. */
        fun novaOn(ctx: Context) {
            routeTox(ctx.applicationContext, false)
            updateTile(ctx.applicationContext)
        }

        /** Туннель Nova выключился ([user] - кнопкой в его уведомлении) или не смог подняться. */
        fun novaStopped(ctx: Context, user: Boolean, error: Boolean) {
            val app = ctx.applicationContext
            if (user) {
                setWanted(app, false)
                GoGuard.cancelPending(app)
            }
            synchronized(STATE_LOCK) {
                VpnState.localProxy = false
                if (!error) VpnState.set(VpnState.Status.Off)
            }
            runCatching { VpnWatchdog.stop() }
            updateTile(app)
        }

        /** Перезапуск Tox, чтобы он пошёл через VPN (или вернулся к обычной сети). */
        private fun routeTox(ctx: Context, on: Boolean) {
            val want = on && VpnStore(ctx).routeTox
            if (VpnState.toxRouted == want) return
            VpnState.toxRouted = want
            runCatching { (ctx.applicationContext as App).component.toxStarter().restartAsync() }
                .onFailure { Log.w(TAG, "tox restart: $it") }
        }

        private fun updateTile(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 24) {
                runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, VpnTileService::class.java)) }
            }
        }

        private const val TAG = "SmVpn"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 7310
        const val ACTION_STOP = "com.stoyko.smessenger.vpn.STOP"
        const val ACTION_OPEN_VPN = "com.stoyko.smessenger.OPEN_VPN"
        const val EXTRA_ID = "id"

        /** Нужен ли запрос разрешения на VPN (Intent системного диалога) - только для полного туннеля. */
        fun prepareIntent(context: Context): Intent? =
            VpnStore(context).let { if (it.fullTunnel || it.mode == VpnStore.MODE_WARP) prepare(context) else null }

        fun start(context: Context, id: String? = null) {
            val i = Intent(context, SmVpnService::class.java).putExtra(EXTRA_ID, id)
            VpnState.set(VpnState.Status.Connecting, VpnStore(context).targetName(context))
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {
                VpnState.set(VpnState.Status.Error, err = e.message ?: e.toString())
            }
        }

        /**
         * Выключить VPN (кнопка на экране, плитка, список чатов). Состояние «выключен» - сразу,
         * идущее подключение и отложенное включение отменяются, сама остановка - в службе и в фоне.
         */
        fun stop(context: Context) {
            val app = context.applicationContext
            val gen = markOff(app, user = true)
            try {
                app.startService(Intent(app, SmVpnService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                // Службу не запустить (например, из фона) - гасим ядро сами, тоже в фоне.
                Log.w(TAG, "stop: $e")
                teardownAsync(app, gen, finalize = true)
            }
        }
    }
}
