// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only
//
// Идея «несколько транспортов, выбор по состоянию сети» - из Briar (GPLv3):
// bramble-core/.../plugin/PluginManagerImpl.java и PollerImpl.java (каждый транспорт - свой
// модуль со своим состоянием ACTIVE/ENABLING/INACTIVE, менеджер включает и опрашивает их).
// Здесь транспорты другие: прямой Tox, Tox поверх Tor и локальные каналы (Wi-Fi, Bluetooth).

package com.stoyko.smessenger.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import com.stoyko.smessenger.App
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.transport.bt.BtLink

private const val TAG = "Transport"

/**
 * «Каналы связи»: какой путь сейчас у сообщений и когда его менять.
 *
 * Авто: сначала прямой Tox. Нет связи N секунд, а интернет есть (сеть проверена системой или
 * мобильная) - значит, Tox блокируют: поднимаем встроенный Tor и перезапускаем ядро через него.
 * Интернета нет совсем - локальные каналы: Tox ищет друзей в общей сети Wi-Fi (роутер, точка
 * доступа, Wi-Fi Direct), а сообщения без сети уходят по Bluetooth (если включён).
 * Сменилась сеть - снова пробуем напрямую (Tor медленнее и тратит больше батареи).
 */
object TransportManager {
    data class Net(val any: Boolean, val internet: Boolean, val wifi: Boolean, val id: String)

    @Volatile
    private var attached = false

    private lateinit var app: Context

    @Volatile
    var toxStatus: ConnectionStatus = ConnectionStatus.None
        private set

    /** Путь, выбранный «Авто» (для ручных режимов не используется). */
    @Volatile
    private var autoRoute = Route.DIRECT

    @Volatile
    var net = Net(any = false, internet = false, wifi = false, id = "")
        private set

    private var offlineSince = SystemClock.elapsedRealtime()
    private var lastRestart = 0L
    private var noNetSince = 0L
    private var torCooldownUntil = 0L
    private var torStartedAt = 0L
    private var lastBtPulse = 0L
    private var localSince = 0L
    private var lastKick = 0L

    /** Перезапуск ядра по нашей просьбе: сервис остановится и запустится снова - Tor не гасим. */
    @Volatile
    private var restartingUntil = 0L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var notifyPosted = false
    private val worker: Handler by lazy { Handler(HandlerThread("transport").apply { start() }.looper) }

    // ---------- Подключение к фоновому сервису ----------

    /** ToxService.continueStart. */
    fun attach(ctx: Context) {
        app = ctx.applicationContext
        if (attached) return
        attached = true
        offlineSince = SystemClock.elapsedRealtime()
        worker.removeCallbacks(tick)
        worker.post(tick)
        changed()
    }

    /** ToxService.onDestroy. */
    fun detach() {
        if (!attached) return
        attached = false
        worker.removeCallbacks(tick)
        if (SystemClock.elapsedRealtime() < restartingUntil) return
        // Сервис остановлен совсем (выход из профиля, «Выход») - всё выключаем.
        TorRunner.stop()
        BtLink.stop()
        WifiDirect.stop()
        changed()
    }

    /** ToxService: статус своего подключения к сети Tox. */
    fun onToxStatus(s: ConnectionStatus?) {
        val st = s ?: ConnectionStatus.None
        if (st == ConnectionStatus.None && toxStatus != ConnectionStatus.None) offlineSince = SystemClock.elapsedRealtime()
        toxStatus = st
        changed()
        if (attached) worker.post(tick)
    }

    @Volatile
    private var vpnSeen: Boolean? = null
    private var lastVpnRestart = 0L

    /**
     * Включился или выключился VPN (свой или стороннего приложения) - 4.10.0. Старые соединения
     * ядра после этого мертвы (шли мимо туннеля или через него), а таймауты ядра - около минуты.
     * Поэтому через 3 с (VPN успевает подняться) ядро перезапускается: связь - за секунды.
     */
    fun onVpnChanged(active: Boolean) {
        val prev = vpnSeen
        vpnSeen = active
        if (prev == null || prev == active || !attached) return
        Log.i(TAG, "VPN ${if (active) "on" else "off"} - reconnect")
        com.stoyko.smessenger.domain.Diagnostics.crumb(if (active) "включился VPN - переподключение" else "выключился VPN - переподключение")
        worker.removeCallbacks(vpnRestart)
        worker.postDelayed(vpnRestart, 3_000)
    }

    private val vpnRestart = Runnable {
        val now = SystemClock.elapsedRealtime()
        if (!attached || now - lastVpnRestart < 20_000) {
            runCatching { (app as App).component.tox().kickBootstrap() }
            return@Runnable
        }
        lastVpnRestart = now
        lastRestart = now
        // Новая сеть для ядра - снова сначала напрямую.
        autoRoute = Route.DIRECT
        torCooldownUntil = 0
        if (TransportHooks.toxReady) restartTox("vpn changed") else runCatching { (app as App).component.tox().kickBootstrap() }
    }

    /** Появилась общая сеть Wi-Fi Direct: попросить ядро сразу поискать друзей. */
    fun onLocalLinkUp() {
        runCatching { (app as App).component.tox().kickBootstrap() }
        if (attached) worker.post(tick)
    }

    // ---------- Выбор пути ----------

    fun mode(ctx: Context) = TransportPrefs.mode(ctx)

    fun setMode(ctx: Context, m: TransportMode) {
        app = ctx.applicationContext
        TransportPrefs.setMode(app, m)
        autoRoute = Route.DIRECT
        offlineSince = SystemClock.elapsedRealtime()
        torCooldownUntil = 0
        changed()
        if (attached) worker.post(tick)
    }

    /** Путь для ядра Tox при запуске (TransportHooks.adjust). */
    fun desiredRoute(ctx: Context): Route = when (TransportPrefs.mode(ctx)) {
        TransportMode.TOX -> Route.DIRECT
        TransportMode.TOR -> Route.TOR
        TransportMode.OFFLINE -> Route.LOCAL
        // «Авто» переводит ядро на Tor только когда Tor уже готов - без долгого простоя.
        TransportMode.AUTO -> if (autoRoute == Route.TOR && !TorRunner.ready) Route.DIRECT else autoRoute
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            runCatching { step() }.onFailure {
                Log.w(TAG, "step: $it")
                com.stoyko.smessenger.domain.Diagnostics.record("каналы связи", it, fatal = false)
            }
            worker.removeCallbacks(this)
            if (attached) worker.postDelayed(this, tickInterval())
        }
    }

    /**
     * Как часто проверять каналы: часто (3 с) - только когда «Авто» что-то решает (нет связи,
     * идёт Tor или сеть без интернета); связь в порядке или ручной режим - раз в 8 с; экран
     * выключен - раз в 10 с. Меньше пробуждений и опросов сети.
     */
    private fun tickInterval(): Long {
        if (com.stoyko.smessenger.domain.feature.PowerHint.screenOff) return 10_000L
        val online = toxStatus != ConnectionStatus.None
        val auto = runCatching { TransportPrefs.mode(app) == TransportMode.AUTO }.getOrDefault(true)
        return if (auto && (!online || autoRoute != Route.DIRECT)) 3_000L else 8_000L
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val n = readNet(app)
        val netChanged = n.id != net.id
        net = n
        val online = toxStatus != ConnectionStatus.None
        val mode = TransportPrefs.mode(app)

        if (mode == TransportMode.AUTO) {
            if (netChanged) {
                // Новая сеть - снова пробуем напрямую.
                if (autoRoute != Route.DIRECT) Log.i(TAG, "network changed, back to direct")
                autoRoute = Route.DIRECT
                offlineSince = now
                torCooldownUntil = 0
            }
            if (!n.any) {
                if (noNetSince == 0L) noNetSince = now
            } else {
                noNetSince = 0L
            }
            val waitMs = TransportPrefs.autoTorAfterSec(app) * 1000L
            when (autoRoute) {
                Route.DIRECT -> if (!online && now - offlineSince >= waitMs) {
                    autoRoute = when {
                        !n.any -> Route.LOCAL
                        n.internet && TransportPrefs.autoTorAllowed(app) && now >= torCooldownUntil -> Route.TOR
                        n.internet -> Route.DIRECT
                        else -> Route.LOCAL
                    }
                    if (autoRoute != Route.DIRECT) Log.i(TAG, "auto: tox offline ${(now - offlineSince) / 1000}s -> $autoRoute")
                    if (autoRoute == Route.TOR) torStartedAt = now
                    if (autoRoute == Route.LOCAL) localSince = now
                }
                Route.TOR -> when {
                    noNetSince != 0L && now - noNetSince > 20_000 -> {
                        autoRoute = Route.LOCAL
                        localSince = now
                    }
                    // Tor не поднялся за 4 минуты (или ошибка) - назад к прямому Tox, повтор через 10 минут.
                    TorRunner.state == TorRunner.State.FAILED || (!TorRunner.ready && now - torStartedAt > 240_000) -> {
                        autoRoute = Route.DIRECT
                        offlineSince = now
                        torCooldownUntil = now + 10 * 60_000L
                    }
                }
                Route.LOCAL -> when {
                    n.internet -> {
                        autoRoute = Route.DIRECT
                        offlineSince = now
                    }
                    // Сеть есть, но система её «не проверила» (в РФ проверка бывает заблокирована при
                    // живом интернете) и связи всё нет - пробуем Tor (с паузой 10 минут после неудачи).
                    n.any && !online && now - localSince > 2 * waitMs && TransportPrefs.autoTorAllowed(app) && now >= torCooldownUntil -> {
                        autoRoute = Route.TOR
                        torStartedAt = now
                    }
                }
            }
        }

        checkWhiteList(now, n, online)

        // Tor нужен: ручной режим «Tor» или «Авто» решил идти через Tor.
        val wantTor = mode == TransportMode.TOR || (mode == TransportMode.AUTO && autoRoute == Route.TOR)
        if (wantTor) TorRunner.start(app) else if (TorRunner.state != TorRunner.State.OFF) TorRunner.stop()

        // Перезапуск ядра, если его сеть не совпадает с нужной (не чаще раза в 20 с).
        val route = desiredRoute(app)
        if (TransportHooks.toxReady && TransportHooks.needsRestart(route) && now - lastRestart > 20_000) {
            lastRestart = now
            restartTox("route $route")
        }
        // Ручной «Tor»: Tor только что стал готов - ядро сразу подключается к узлам через него.
        if (route == Route.TOR && TorRunner.ready && TransportHooks.appliedRoute == Route.TOR && !online &&
            now - lastRestart > 5_000 && now - lastKick > 30_000
        ) {
            lastKick = now
            runCatching { (app as App).component.tox().kickBootstrap() }
        }

        // Bluetooth: включён пользователем и сейчас нет интернета (или режим «Без интернета»).
        val wantBt = TransportPrefs.bluetooth(app) && (mode == TransportMode.OFFLINE || route == Route.LOCAL || !n.any)
        if (wantBt) {
            if (!BtLink.running) BtLink.start(app)
            BtLink.flushAll()
            if (now - lastBtPulse > 30_000) {
                lastBtPulse = now
                BtLink.pulse()
            }
        } else if (BtLink.running && BtLink.sessionCount == 0 && !BtLink.scanning) {
            BtLink.stop()
        }
        // Ключи Bluetooth друзьям, которые сейчас в сети (после включения канала).
        if (TransportPrefs.bluetooth(app)) TransportHooks.onlineFriends().forEach { TransportHooks.offerKey(it) }
        changed()
    }

    /** Сеть, для которой уже проверяли «белые списки» оператора (проверка - раз на сеть). */
    private var wlCheckedNet = ""

    /**
     * 4.10.0: связи нет уже 90 с на мобильной сети без VPN - проверяем, не включил ли оператор
     * «белые списки» (тогда напрямую не пройдёт ничего, ни узлы, ни Tor). Если так - говорим
     * человеку прямо, что нужен VPN, а не оставляем вечное «подключение…».
     */
    private fun checkWhiteList(now: Long, n: Net, online: Boolean) {
        if (online || !n.internet || n.wifi || n.id == wlCheckedNet || now - offlineSince < 90_000) return
        if (com.stoyko.smessenger.vpn.ForeignVpn.anyVpn(app)) return
        wlCheckedNet = n.id
        val restricted = runCatching { com.stoyko.smessenger.vpn.WhiteListDetector.detect(app) }.getOrNull()
        com.stoyko.smessenger.domain.Diagnostics.crumb("проверка белых списков: " + (restricted?.toString() ?: "не сотовая"))
        if (restricted != true) return
        main.post {
            runCatching {
                android.widget.Toast.makeText(
                    app,
                    com.stoyko.smessenger.ai.t(
                        "Оператор включил «белые списки»: напрямую связь не пройдёт. Включите VPN (щит вверху) - встроенный или любой свой.",
                        "Your carrier restricts the internet to a whitelist: direct connection won't work. Turn on a VPN (shield at the top).",
                    ),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun restartTox(reason: String) {
        Log.i(TAG, "restarting Tox: $reason")
        restartingUntil = SystemClock.elapsedRealtime() + 30_000
        offlineSince = SystemClock.elapsedRealtime()
        runCatching { (app as App).component.toxStarter().restartAsync() }
            .onFailure { com.stoyko.smessenger.domain.Diagnostics.record("каналы связи: перезапуск", it, fatal = false) }
    }

    /** Применить изменения (режим, мосты) немедленно. */
    fun poke(ctx: Context) {
        app = ctx.applicationContext
        if (attached) worker.post(tick)
        changed()
    }

    // ---------- Сеть ----------

    @Suppress("DEPRECATION")
    private fun readNet(ctx: Context): Net = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT < 23) {
            val i = cm.activeNetworkInfo
            val up = i?.isConnected == true
            return@runCatching Net(up, up, i?.type == ConnectivityManager.TYPE_WIFI, "${i?.type}:${i?.extraInfo}")
        }
        val active = cm.activeNetwork ?: return@runCatching Net(false, false, false, "")
        val caps = cm.getNetworkCapabilities(active) ?: return@runCatching Net(true, false, false, active.toString())
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val cell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        // Проверка системы (connectivitycheck) в РФ иногда не проходит при живом интернете,
        // поэтому мобильная сеть и VPN тоже считаются «интернетом».
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (validated || cell || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        Net(true, internet, wifi, active.toString())
    }.getOrDefault(Net(false, false, false, ""))

    // ---------- Для экрана и значка ----------

    /** Чем сейчас идёт связь. */
    fun channel(): Channel {
        val online = toxStatus != ConnectionStatus.None
        if (!online && BtLink.sessionCount > 0) return Channel.BLUETOOTH
        val route = TransportHooks.appliedRoute
        if (route == Route.TOR || (!online && ::app.isInitialized && desiredRoute(app) == Route.TOR)) return Channel.TOR
        val local = route == Route.LOCAL || WifiDirect.linked || (net.any && !net.internet)
        if (online) return if (local) Channel.WIFI else Channel.TOX
        if (local && (net.wifi || WifiDirect.linked)) return Channel.WIFI
        if (::app.isInitialized && TransportPrefs.bluetooth(app) && BtLink.running) return Channel.BLUETOOTH
        return Channel.NONE
    }

    val connected: Boolean get() = toxStatus != ConnectionStatus.None || BtLink.sessionCount > 0

    fun listen(l: () -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun unlisten(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Что-то изменилось - экраны обновятся (не чаще ~4 раз в секунду). */
    fun changed() {
        if (listeners.isEmpty()) return
        synchronized(this) {
            if (notifyPosted) return
            notifyPosted = true
        }
        main.postDelayed({
            synchronized(this) { notifyPosted = false }
            listeners.forEach { runCatching { it() } }
        }, 250)
    }
}
