// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.stoyko.smessenger.MainActivity
import com.stoyko.smessenger.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Туннель WARP на ядре Nova - работает в ОТДЕЛЬНОМ процессе «:nova» (см. [NovaLink]).
 *
 * Два транспорта:
 *  * WireGuard / AmneziaWG - тот же конфиг WARP, что и у встроенного AmneziaWG, но ядро Nova
 *    (amneziawg-go 3.x: I1-I5, S3/S4, защита заголовков) и без перезапуска приложения при
 *    переключении с серверов Xray;
 *  * MASQUE - Cloudflare WARP поверх HTTP/3 (QUIC, как обычный сайт): у многих операторов, где
 *    WireGuard режется, MASQUE проходит. Ключ выдаётся один раз (регистрация WARP + выпуск
 *    ключа MASQUE в ядре Nova) и хранится в настройках VPN.
 *
 * Процесс «:nova» живёт только пока включён туннель: после выключения он завершается целиком
 * (рантайм Go нельзя выгрузить, а настройки при следующем включении читаются заново).
 */
class NovaVpnService : VpnService() {
    private var receiver: BroadcastReceiver? = null
    private var title = ""

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var handoverLatch: java.util.concurrent.CountDownLatch? = null
    @Volatile private var pendingConf: String? = null
    @Volatile private var lastState = VpnState.Status.Off
    @Volatile private var lastError = ""

    override fun onCreate() {
        super.onCreate()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    NovaLink.A_STOP -> shutdown(user = false)
                    NovaLink.A_PING -> report(lastState, lastError)
                    NovaLink.A_HANDOVER_OK -> handoverLatch?.countDown()
                    NovaLink.A_RECONNECT -> {
                        i.getStringExtra(NovaLink.X_CONF)?.takeIf { it.isNotBlank() }?.let { pendingConf = it }
                        // Остановка движка завершает текущую попытку - цикл подключится заново.
                        Thread({ runCatching { nova.Nova.stopVPN() } }, "nova-reconnect").apply { isDaemon = true }.start()
                    }
                }
            }
        }
        receiver = r
        runCatching { NovaLink.register(this, r, NovaLink.A_STOP, NovaLink.A_PING, NovaLink.A_HANDOVER_OK, NovaLink.A_RECONNECT) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown(user = true)
                return START_NOT_STICKY
            }
            ACTION_START -> start(intent)
            else -> {
                // Система перезапустила процесс без команды - включать нечего, конфиг у главного процесса.
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ запуск

    private fun start(i: Intent) {
        title = i.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.vpn_mode_warp) }
        goForeground(connecting = true)
        val gen = GEN.incrementAndGet()
        report(VpnState.Status.Connecting)
        val engine = i.getStringExtra(EXTRA_ENGINE).orEmpty()
        val conf = i.getStringExtra(EXTRA_CONF).orEmpty()
        val identity = i.getStringExtra(EXTRA_IDENTITY).orEmpty()
        val token = i.getStringExtra(EXTRA_TOKEN).orEmpty()
        val device = i.getStringExtra(EXTRA_DEVICE).orEmpty()
        val proxy = i.getStringExtra(EXTRA_PROXY).orEmpty()
        Thread({
            synchronized(ENGINE_LOCK) {
                if (gen != GEN.get()) return@Thread
                try {
                    stopEngine()
                    installProtectors()
                    if (engine == VpnStore.WARP_MASQUE) {
                        val id = prepareMasque(identity, token, device, proxy)
                        if (gen != GEN.get()) return@Thread
                        handover()
                        if (gen != GEN.get()) return@Thread
                        val ident = parseIdentity(id) ?: throw IllegalStateException(ru("Ключ MASQUE повреждён - получите новый", "MASQUE key is damaged - get a new one"))
                        establish(ident.addresses, emptyList(), 1280)
                        superviseMasque(gen, id, ident)
                    } else {
                        if (conf.isBlank()) throw IllegalStateException(getString(R.string.warp_no_config))
                        handover()
                        if (gen != GEN.get()) return@Thread
                        superviseWg(gen, conf)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "start failed", e)
                    if (gen == GEN.get()) fail(e.message ?: e.toString())
                }
            }
        }, "nova-start").apply { isDaemon = true }.start()
    }

    /** Главный процесс гасит своё ядро (Xray/AmneziaWG), прежде чем мы поднимем туннель. */
    private fun handover() {
        val l = java.util.concurrent.CountDownLatch(1)
        handoverLatch = l
        NovaLink.send(this, NovaLink.A_HANDOVER)
        // Главный процесс может быть уже закрыт системой - тогда и его ядра нет, ждём недолго.
        runCatching { l.await(6, TimeUnit.SECONDS) }
        handoverLatch = null
    }

    private fun establish(addresses: List<String>, dns: List<String>, mtu: Int) {
        val b = Builder().setSession(title).setMtu(mtu)
        var v6 = false
        for (a in addresses.ifEmpty { listOf("172.16.0.2/32") }) {
            val ip = a.substringBefore('/').trim()
            if (ip.isEmpty()) continue
            val prefix = a.substringAfter('/', "").trim().toIntOrNull() ?: if (':' in ip) 128 else 32
            if (runCatching { b.addAddress(ip, prefix) }.isSuccess && ':' in ip) v6 = true
        }
        b.addRoute("0.0.0.0", 0)
        if (v6) runCatching { b.addRoute("::", 0) }
        dns.ifEmpty { listOf("1.1.1.1", "1.0.0.1") }.forEach { runCatching { b.addDnsServer(it) } }
        // Как у WARP в главном процессе: сокеты ядра защищены, поэтому и сам мессенджер идёт туннелем.
        SplitTunnel.apply(this, b, includeSelf = true)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        b.setConfigureIntent(openAppIntent())
        val t = b.establish() ?: throw IllegalStateException(getString(R.string.vpn_no_permission))
        runCatching { tun?.close() }
        tun = t
    }

    // ------------------------------------------------------------ WireGuard / AmneziaWG

    private fun superviseWg(gen: Int, firstConf: String) {
        var conf = firstConf
        var fails = 0
        var rebuild = true
        while (gen == GEN.get()) {
            pendingConf?.let {
                pendingConf = null
                conf = it
                rebuild = true
            }
            val p = Warp.parse(conf)
            if (rebuild) {
                establish(p.addresses, p.dns, p.mtu)
                rebuild = false
            }
            val fd = tun?.fd ?: return
            runCatching { nova.Nova.setTunnelMtu(p.mtu.toLong()) }
            // Имя сервера - заранее в IP: внутри поднимаемого туннеля DNS ещё нет.
            val ep = Warp.resolveEndpoint(p.endpoint)
            val run = if (ep != null && ep != p.endpoint) {
                conf.lines().joinToString("\n") { if (it.trim().lowercase().startsWith("endpoint")) "Endpoint = $ep" else it }
            } else {
                conf
            }
            val up = attempt(gen) { nova.Nova.startVPN(fd.toLong(), run) }
            if (gen != GEN.get()) return
            if (up) {
                fails = 0
                report(VpnState.Status.Connecting)
                continue
            }
            fails++
            if (fails >= 3) {
                // Этот адрес не отвечает - пробуем другой адрес Cloudflare с тем же ключом.
                conf = Warp.withNewEndpoint(conf)
            }
            if (fails >= 8) throw IllegalStateException(ru("WARP (Nova) не подключается - смените конфиг или транспорт", "WARP (Nova) won't connect - change the config or transport"))
            SystemClock.sleep(1500)
        }
    }

    // ------------------------------------------------------------ MASQUE

    private class Ident(val addresses: List<String>, val hosts: List<String>, val ports: List<Int>)

    private fun parseIdentity(raw: String): Ident? = runCatching {
        val j = JSONObject(raw)
        val addrs = buildList {
            j.optString("ipv4").takeIf { it.isNotBlank() }?.let { add(it.substringBefore('/') + "/32") }
            j.optString("ipv6").takeIf { it.isNotBlank() }?.let { add(it.substringBefore('/') + "/128") }
        }
        fun clean(s: String) = s.trim().removePrefix("[").substringBefore("]").let { h -> if (h.count { it == ':' } == 1) h.substringBefore(':') else h }
        val hosts = LinkedHashSet<String>()
        j.optString("endpoint_v4").takeIf { it.isNotBlank() }?.let { hosts += clean(it) }
        j.optJSONArray("endpoint_v4_candidates")?.let { a -> for (k in 0 until a.length()) a.optString(k).takeIf { it.isNotBlank() }?.let { hosts += clean(it) } }
        j.optString("endpoint_v6").takeIf { it.isNotBlank() }?.let { hosts += clean(it) }
        val ports = LinkedHashSet<Int>()
        j.optJSONArray("ports")?.let { a -> for (k in 0 until a.length()) a.optInt(k, -1).takeIf { it in 1..65535 }?.let { ports += it } }
        // Порядок замерен в Nova: 500/1701/8443 отвечают чаще, 443/4500 - реже.
        ports += listOf(500, 1701, 8443, 443, 4500, 4443, 8095)
        if (addrs.isEmpty() || hosts.isEmpty()) null else Ident(addrs, hosts.toList(), ports.toList())
    }.getOrNull()

    /**
     * Ключ MASQUE: сохранённый (ядро само обновит его, если пора) или новый - регистрация WARP
     * и выпуск ключа. Регистрация идёт мимо туннеля; если api.cloudflareclient.com заблокирован,
     * ядро пробует через локальный HTTP-прокси Xray (он ещё работает - главный процесс гасит его
     * только после HANDOVER).
     */
    private fun prepareMasque(saved: String, savedToken: String, savedDevice: String, proxy: String): String {
        if (proxy.isNotBlank()) runCatching { nova.Nova.setLocalCloudflareProxyAddress(proxy) }
        var token = savedToken
        var device = savedDevice
        var wgConf = ""
        if (token.isBlank() || device.isBlank()) {
            if (saved.isNotBlank()) return saved // старый ключ без аккаунта - используем как есть
            report(VpnState.Status.Connecting, name = ru("Регистрация WARP…", "Registering WARP…"))
            val priv = nova.Nova.generatePrivateKey()
            val pub = nova.Nova.generatePublicKey(priv)
            val locale = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
            val model = Build.MODEL?.trim().takeUnless { it.isNullOrBlank() } ?: "Android"
            val raw = timed(40) { nova.Nova.registerWarp(pub, locale, model) }
                ?: run {
                    runCatching { nova.Nova.cancelRegisterWarp() }
                    throw IllegalStateException(ru("Cloudflare не ответил на регистрацию WARP. Включите VPN-сервер и попробуйте снова - регистрация пройдёт через него.", "Cloudflare didn't answer the WARP registration. Turn on a VPN server and retry - registration will go through it."))
                }
            val root = JSONObject(raw)
            val res = root.optJSONObject("result") ?: root
            token = res.optString("token", root.optString("token", ""))
            device = res.optString("id", root.optString("id", ""))
            if (token.isBlank() || device.isBlank()) throw IllegalStateException(ru("Регистрация WARP: нет токена", "WARP registration: no token"))
            wgConf = runCatching { wgFromRegistration(res, priv) }.getOrDefault("")
        }
        report(VpnState.Status.Connecting, name = ru("Ключ MASQUE…", "MASQUE key…"))
        val dev = device
        val tok = token
        val id = timed(if (saved.isBlank()) 30 else 10) { nova.Nova.ensureMasqueConfig(saved, tok, dev, "S-messenger") }
            ?.takeIf { it.isNotBlank() }
            // Ожидание истекло, но ядро могло уже выпустить ключ - забираем его, иначе он остался
            // бы на сервере без приватной половины.
            ?: runCatching { nova.Nova.lastMasqueEnrollResult(dev) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: saved.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(ru("Не удалось получить ключ MASQUE", "Couldn't get a MASQUE key"))
        if (id != saved || tok != savedToken || dev != savedDevice) {
            NovaLink.send(this, NovaLink.A_IDENTITY) {
                putExtra(NovaLink.X_IDENTITY, id)
                putExtra(NovaLink.X_TOKEN, tok)
                putExtra(NovaLink.X_DEVICE, dev)
                if (wgConf.isNotBlank()) putExtra(NovaLink.X_WG_CONF, wgConf)
            }
        }
        report(VpnState.Status.Connecting)
        return id
    }

    /** Собственный аккаунт WARP → обычный конфиг WireGuard/AmneziaWG (для транспорта WireGuard). */
    private fun wgFromRegistration(res: JSONObject, priv: String): String {
        val config = res.getJSONObject("config")
        val peer = config.getJSONArray("peers").getJSONObject(0)
        val addrs = config.getJSONObject("interface").getJSONObject("addresses")
        // client_id - три «reserved»-байта WARP: без них часть адресов Cloudflare отвечает хуже.
        val reserved = runCatching {
            val b = Base64.decode(config.optString("client_id"), Base64.DEFAULT)
            if (b.size >= 3) "${b[0].toInt() and 0xFF}, ${b[1].toInt() and 0xFF}, ${b[2].toInt() and 0xFF}" else null
        }.getOrNull()
        val base = Warp.config(
            priv,
            peer.getString("public_key"),
            addrs.getString("v4").substringBefore('/'),
            addrs.getString("v6").substringBefore('/'),
            Warp.randomEndpoint(),
        )
        return if (reserved != null) base.replaceFirst("[Interface]\n", "[Interface]\nReserved = $reserved\n") else base
    }

    private fun superviseMasque(gen: Int, identity: String, ident: Ident) {
        val attempts = ident.hosts.take(3).flatMap { h -> ident.ports.map { h to it } }
        var i = 0
        var fails = 0
        while (gen == GEN.get()) {
            val (host, port) = attempts[i % attempts.size]
            val fd = tun?.fd ?: return
            Log.i(TAG, "MASQUE $host:$port")
            val up = attempt(gen) { nova.Nova.startMasqueVPN(fd.toLong(), identity, host, port.toLong()) }
            if (gen != GEN.get()) return
            if (up) {
                // Работавшая сессия оборвалась - переподключаемся к тому же адресу.
                fails = 0
                report(VpnState.Status.Connecting)
                continue
            }
            i++
            fails++
            if (fails >= attempts.size.coerceAtMost(14)) {
                throw IllegalStateException(ru("MASQUE не подключается ни на одном адресе. Попробуйте транспорт WireGuard или получите новый ключ.", "MASQUE won't connect on any address. Try the WireGuard transport or get a new key."))
            }
            SystemClock.sleep(600)
        }
    }

    // ------------------------------------------------------------ общий цикл попытки

    /**
     * Одна попытка: движок запускается в своём потоке (вызов ядра блокируется, пока туннель
     * жив), а здесь проверяется живой запрос через туннель. Ответ пришёл - «VPN включён»,
     * дальше ждём конца сессии. Нет ответа за 15 секунд - останавливаем движок.
     * true - туннель работал (и потом оборвался), false - так и не заработал.
     */
    private fun attempt(gen: Int, run: () -> Unit): Boolean {
        var err: Throwable? = null
        val t = Thread({
            try {
                run()
            } catch (e: Throwable) {
                err = e
            }
        }, "nova-engine").apply { isDaemon = true }
        t.start()
        var up = false
        val deadline = SystemClock.elapsedRealtime() + 15_000
        SystemClock.sleep(700)
        while (t.isAlive && gen == GEN.get() && SystemClock.elapsedRealtime() < deadline) {
            if (probe()) {
                up = true
                break
            }
            SystemClock.sleep(1000)
        }
        if (up && gen == GEN.get()) {
            report(VpnState.Status.On)
            goForeground(connecting = false)
            while (t.isAlive && gen == GEN.get()) t.join(1000)
        }
        if (t.isAlive) {
            runCatching { nova.Nova.stopVPN() }
            t.join(7000)
        }
        err?.let { Log.w(TAG, "engine: $it") }
        return up
    }

    private fun probe(): Boolean {
        for (u in PROBES) {
            val ok = runCatching {
                val c = URL(u).openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    c.connectTimeout = 3000
                    c.readTimeout = 3000
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

    private fun <T> timed(seconds: Long, block: () -> T): T? {
        val ex = Executors.newSingleThreadExecutor { Thread(it, "nova-call").apply { isDaemon = true } }
        return try {
            ex.submit<T> { block() }.get(seconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        } finally {
            ex.shutdownNow()
        }
    }

    // ------------------------------------------------------------ защита сокетов ядра

    private fun installProtectors() {
        nova.Nova.setSocketProtector(object : nova.SocketProtector {
            override fun protect(socketFd: Long): Boolean = this@NovaVpnService.protect(socketFd.toInt())
        })
        // MASQUE: VpnService.protect() ломает CONNECT-IP (замер Nova) - сокет привязывается к
        // нижележащей сети напрямую; не вышло - обычный protect().
        runCatching {
            nova.Nova.setMasqueSocketProtector(object : nova.SocketProtector {
                override fun protect(socketFd: Long): Boolean = bindToUnderlying(socketFd.toInt())
            })
        }
    }

    private fun underlying(): Network? = runCatching {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .sortedByDescending { (_, c) ->
                (if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                    (if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 1 else 0)
            }
            .firstOrNull()?.first
    }.getOrNull()

    private fun bindToUnderlying(fd: Int): Boolean {
        val n = underlying() ?: return protect(fd)
        val ok = runCatching {
            ParcelFileDescriptor.fromFd(fd).use { dup -> n.bindSocket(dup.fileDescriptor) }
            true
        }.getOrDefault(false)
        return ok || protect(fd)
    }

    // ------------------------------------------------------------ остановка и состояние

    private fun stopEngine() {
        runCatching { nova.Nova.stopVPN() }
        val t = tun
        tun = null
        runCatching { t?.close() }
    }

    private fun fail(msg: String) {
        GEN.incrementAndGet()
        stopEngine()
        report(VpnState.Status.Error, msg)
        finish()
    }

    /** Выключить туннель. [user] - нажали «Отключить» в уведомлении Nova. */
    private fun shutdown(user: Boolean) {
        GEN.incrementAndGet()
        Thread({
            synchronized(STOP_LOCK) { stopEngine() }
            report(VpnState.Status.Off, user = user)
            finish()
        }, "nova-stop").apply { isDaemon = true }.start()
    }

    private fun finish() {
        android.os.Handler(mainLooper).post {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            // Рантайм Go не выгружается - процесс «:nova» завершается целиком, следующий запуск
            // начнётся с чистого листа (и свежих настроек).
            android.os.Handler(mainLooper).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 800)
        }
    }

    private fun report(st: VpnState.Status, err: String = "", user: Boolean = false, name: String? = null) {
        lastState = st
        lastError = err
        NovaLink.send(this, NovaLink.A_STATE) {
            putExtra(NovaLink.X_STATUS, st.name)
            putExtra(NovaLink.X_NAME, name ?: title)
            putExtra(NovaLink.X_ERROR, err)
            putExtra(NovaLink.X_USER, user)
        }
    }

    override fun onRevoke() {
        // Включили другой VPN (или наш же главный процесс поднял свой туннель).
        shutdown(user = false)
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        41,
        Intent(this, MainActivity::class.java).setAction(SmVpnService.ACTION_OPEN_VPN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun goForeground(connecting: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.vpn_title), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        val stop = PendingIntent.getService(
            this,
            42,
            Intent(this, NovaVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(if (connecting) R.string.vpn_connecting else R.string.vpn_on_title))
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(!connecting)
            .setUsesChronometer(!connecting)
            .setContentIntent(openAppIntent())
            .addAction(R.drawable.ic_close, getString(R.string.vpn_disconnect), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
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

    private fun ru(r: String, e: String) = if (Locale.getDefault().language == "ru") r else e

    companion object {
        private const val TAG = "NovaVpn"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 7311
        private val GEN = AtomicInteger(0)
        private val ENGINE_LOCK = Any()
        private val STOP_LOCK = Any()
        private val PROBES = listOf("http://cp.cloudflare.com/generate_204", "http://connectivitycheck.gstatic.com/generate_204")

        const val ACTION_START = "com.stoyko.smessenger.nova.START"
        const val ACTION_STOP = "com.stoyko.smessenger.nova.STOP_SERVICE"
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_CONF = "conf"
        const val EXTRA_IDENTITY = "identity"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_PROXY = "proxy"
        const val EXTRA_TITLE = "title"
    }
}
