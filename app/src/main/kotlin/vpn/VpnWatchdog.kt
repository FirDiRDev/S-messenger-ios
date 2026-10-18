// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Сторож туннеля: VPN «включён» ещё не значит «работает». Каждые 40 секунд (и сразу после
 * смены сети - Wi-Fi ↔ мобильная, потеря сигнала, точка доступа) проверяется живой запрос
 * через сам туннель. Проверяется не счётчик трафика (он помнит прошлое и после смены сети
 * показывает «всё хорошо» у мёртвого туннеля - урок проекта Nova), а настоящий ответ сайта.
 *
 * Что делается при отказе:
 *  1) две неудачи подряд (или одна сразу после смены сети) - переподключение того же сервера;
 *  2) не помогло - переход на следующий живой сервер по пингу (для WARP - на другой готовый
 *     конфиг или адрес), не чаще трёх раз за 10 минут;
 *  3) нет сети вообще - ничего не трогаем, ждём её появления.
 * Выключается в настройках VPN («Авто-переподключение»).
 */
object VpnWatchdog {
    private const val TAG = "VpnWatchdog"
    private const val PERIOD_MS = 40_000L
    private const val PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
    private const val PROBE_URL2 = "http://cp.cloudflare.com/generate_204"

    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var app: Context? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNet: Network? = null
    @Volatile private var fails = 0
    @Volatile private var reconnects = 0
    private val switches = ArrayList<Long>()
    @Volatile private var netChangedAt = 0L
    @Volatile private var startedAt = 0L

    /** Первая проверка вскоре после подключения: мёртвый сервер заменяется за секунды, а не за минуту. */
    private const val FIRST_CHECK_MS = 9_000L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("vpn", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("watchdog", true)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("watchdog", v).apply()
    fun failover(ctx: Context) = prefs(ctx).getBoolean("failover", true)
    fun setFailover(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("failover", v).apply()

    /** VPN поднялся - начать наблюдение (повторный вызов просто сбрасывает счётчики). */
    @Synchronized
    fun start(ctx: Context) {
        val a = ctx.applicationContext
        app = a
        fails = 0
        if (!enabled(a)) return
        if (thread == null) {
            thread = HandlerThread("vpn-watchdog").apply { start() }
            h = Handler(thread!!.looper)
        }
        h?.removeCallbacksAndMessages(null)
        startedAt = SystemClock.elapsedRealtime()
        h?.postDelayed(tick, FIRST_CHECK_MS)
        if (callback == null && Build.VERSION.SDK_INT >= 24) {
            val cm = (a.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val prev = lastNet
                    lastNet = network
                    // Первая сеть после запуска - не «смена»; дальше любая новая сеть - смена.
                    if (prev != null && prev != network) onNetworkChanged()
                }

                override fun onLost(network: Network) {
                    if (network == lastNet) lastNet = null
                }
            }
            // С обработчиком в своём потоке - только с Android 8; на 7.x - без него (главный поток).
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) cm.registerDefaultNetworkCallback(cb, h!!) else cm.registerDefaultNetworkCallback(cb)
            }.onSuccess { callback = cb }
        }
    }

    /** VPN выключен - перестать наблюдать. */
    @Synchronized
    fun stop() {
        h?.removeCallbacksAndMessages(null)
        val a = app
        val cb = callback
        callback = null
        lastNet = null
        if (a != null && cb != null) runCatching { (a.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb) }
        reconnects = 0
        fails = 0
    }

    private fun onNetworkChanged() {
        netChangedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "network changed - probing")
        com.stoyko.smessenger.domain.Diagnostics.crumb("VPN: смена сети, проверка туннеля")
        val hh = h ?: return
        hh.removeCallbacks(tick)
        // Дать сокетам ядра переехать на новую сеть.
        hh.postDelayed(tick, 3_500)
    }

    private val tick = Runnable { check() }

    private fun hasNetwork(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }.getOrDefault(true)

    /** Живой запрос через туннель. */
    fun probe(ctx: Context): Boolean {
        val store = VpnStore(ctx)
        val proxy = if (store.mode != VpnStore.MODE_WARP && VpnState.localProxy) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", VpnLinks.SOCKS_PORT))
        } else {
            Proxy.NO_PROXY // WARP: сам мессенджер идёт туннелем
        }
        for (u in listOf(PROBE_URL, PROBE_URL2)) {
            val ok = runCatching {
                val c = URL(u).openConnection(proxy) as HttpURLConnection
                try {
                    c.connectTimeout = 7_000
                    c.readTimeout = 7_000
                    c.instanceFollowRedirects = false
                    c.useCaches = false
                    c.responseCode in 200..399
                } finally {
                    c.disconnect()
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun check() {
        val ctx = app ?: return
        val hh = h ?: return
        if (VpnState.status != VpnState.Status.On) {
            hh.postDelayed(tick, PERIOD_MS)
            return
        }
        if (!hasNetwork(ctx)) {
            fails = 0
            hh.postDelayed(tick, PERIOD_MS)
            return
        }
        val ok = probe(ctx)
        if (ok) {
            fails = 0
            reconnects = 0
            hh.postDelayed(tick, PERIOD_MS)
            return
        }
        fails++
        val justChanged = SystemClock.elapsedRealtime() - netChangedAt < 20_000
        // Сразу после подключения сервер не ответил дважды - он не рабочий: без переподключения
        // того же, сразу следующий живой (как «Авто = самый быстрый» с запасными в YPtun).
        val fresh = SystemClock.elapsedRealtime() - startedAt < 45_000
        if (fresh && fails >= 2 && failover(ctx)) {
            fails = 0
            reconnects = 0
            com.stoyko.smessenger.domain.Diagnostics.crumb("VPN: сервер не отвечает сразу после подключения - следующий")
            switchToNext(ctx)
            hh.postDelayed(tick, FIRST_CHECK_MS)
            return
        }
        if (fresh && fails < 2) {
            hh.postDelayed(tick, 6_000)
            return
        }
        Log.w(TAG, "probe failed ($fails), net changed recently=$justChanged")
        if (fails < 2 && !justChanged) {
            // Повторная проверка чуть раньше обычного.
            hh.postDelayed(tick, 12_000)
            return
        }
        fails = 0
        if (reconnects < 1) {
            reconnects++
            com.stoyko.smessenger.domain.Diagnostics.crumb("VPN: туннель не отвечает - переподключение")
            restart(ctx)
        } else {
            reconnects = 0
            if (failover(ctx)) switchToNext(ctx) else restart(ctx)
        }
        hh.postDelayed(tick, PERIOD_MS)
    }

    /** Переход на другой путь: следующий живой сервер или (WARP) другой готовый конфиг. */
    private fun switchToNext(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        switches.removeAll { now - it > 10 * 60_000 }
        if (switches.size >= 3) {
            restart(ctx)
            return
        }
        switches += now
        val store = VpnStore(ctx)
        when (store.mode) {
            VpnStore.MODE_SERVER -> {
                val cur = store.selectedId
                val all = store.profiles()
                // В сети «белых списков» - сначала серверы «обход БС».
                val restricted = runCatching { WhiteListDetector.detect(ctx) }.getOrNull()
                val next = WhiteListDetector.pickFastest(store, restricted, exclude = cur)
                    ?: all.filter { it.id != cur && it.ping == 0L }.firstOrNull()
                if (next != null) {
                    com.stoyko.smessenger.domain.Diagnostics.crumb("VPN: сервер не отвечает - переход на «${next.name}»")
                    // Нерабочий сервер помечаем, чтобы он ушёл вниз списка.
                    store.saveProfiles(all.map { if (it.id == cur) it.copy(ping = -1, fails = it.fails + 1) else it })
                    store.selectedId = next.id
                    notifySwitch(ctx, next.name)
                    SmVpnService.start(ctx, next.id)
                } else {
                    restart(ctx)
                }
            }
            VpnStore.MODE_WARP -> {
                // MASQUE: ядро Nova само перебирает адреса и порты Cloudflare - просто переподключаем.
                if (NovaLink.active && store.warpEngine == VpnStore.WARP_MASQUE) {
                    NovaLink.reconnect(ctx)
                    return
                }
                val conf = WarpSeeds.next(ctx) ?: Warp.withNewEndpoint(store.warpConf)
                store.warpConf = conf
                notifySwitch(ctx, "WARP")
                if (NovaLink.active) NovaLink.reconnect(ctx, conf) else SmVpnService.start(ctx)
            }
            else -> restart(ctx)
        }
    }

    /**
     * Переподключить то, что работает. Туннель Nova переподключается сам по сообщению - без
     * нового запуска службы (из фона Android его может и не разрешить).
     */
    private fun restart(ctx: Context) {
        if (NovaLink.active) NovaLink.reconnect(ctx) else SmVpnService.start(ctx)
    }

    private fun notifySwitch(ctx: Context, name: String) {
        Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(
                    ctx,
                    if (java.util.Locale.getDefault().language == "ru") "VPN: связь пропала - переключаюсь на «$name»" else "VPN: connection lost - switching to “$name”",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

/**
 * Режим «белых списков» у мобильного оператора: сайты вне списка (и обычные VPN-серверы)
 * не открываются, хотя сеть есть. Признак (из проекта Nova): с сотовой сети не открывается
 * TCP-соединение даже с 1.1.1.1 / 8.8.8.8 на порт 443. Тогда автоподбор берёт серверы
 * «обход БС», а не обычные.
 */
object WhiteListDetector {
    private val targets = listOf("1.1.1.1" to 443, "1.0.0.1" to 443, "8.8.8.8" to 443)

    /** true - ограниченная сеть, false - обычная, null - не сотовая/не удалось понять. Только в фоне! */
    fun detect(ctx: Context): Boolean? {
        val cm = (ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) ?: return null
        @Suppress("DEPRECATION")
        val net = cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: return null
        // Wi-Fi тоже есть - телефон ходит через него, сотовая не важна.
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        if (wifi) return null
        val ok = targets.any { (h, p) ->
            runCatching { net.socketFactory.createSocket().use { s -> s.connect(InetSocketAddress(h, p), 900); s.isConnected } }.getOrDefault(false)
        }
        return !ok
    }

    /** Самый быстрый подходящий сервер: в ограниченной сети - из «обход БС», если такие есть. */
    fun pickFastest(store: VpnStore, restricted: Boolean?, exclude: String? = null): VpnProfile? {
        val subs = store.subscriptions().associateBy { it.id }
        val alive = store.profiles().filter { it.ping > 0 && it.id != exclude }
        if (restricted == true) {
            alive.filter { VpnStore.category(it, subs[it.subId]) == VpnStore.CAT_WHITE }.minByOrNull { it.ping }?.let { return it }
        }
        return alive.minByOrNull { it.ping }
    }
}
