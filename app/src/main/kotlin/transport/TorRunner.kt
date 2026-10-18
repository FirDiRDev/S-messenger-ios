// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only
//
// Запуск Tor устроен так же, как в Briar (GPLv3, https://code.briarproject.org/briar/briar):
// bramble-android/.../plugin/tor/AndroidTorPluginFactory.java и
// bramble-core/.../plugin/tor/TorPlugin.java - те же библиотеки onionwrapper и бинарники
// tor-android / lyrebird-android (obfs4, meek, snowflake) от Briar Project.

package com.stoyko.smessenger.transport

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManagerFactory
import org.briarproject.onionwrapper.AndroidLocationUtilsFactory
import org.briarproject.onionwrapper.AndroidTorWrapper
import org.briarproject.onionwrapper.CircumventionProvider
import org.briarproject.onionwrapper.CircumventionProviderFactory
import org.briarproject.onionwrapper.LocationUtils
import org.briarproject.onionwrapper.TorWrapper

private const val TAG = "TorRunner"

/**
 * Встроенный Tor: только локальный SOCKS5 (127.0.0.1) для ядра Tox - скрытые сервисы не нужны.
 * Все вызовы библиотеки - в своём потоке, главный поток не блокируется никогда.
 */
object TorRunner {
    enum class State { OFF, STARTING, CONNECTING, READY, FAILED }

    @Volatile
    var state = State.OFF
        private set

    /** Загрузка Tor, 0-100 %. */
    @Volatile
    var bootstrap = 0
        private set

    @Volatile
    var error: String? = null
        private set

    /** Порт SOCKS5 (0 - Tor не запущен). */
    @Volatile
    var socksPort = 0
        private set

    /** Какие мосты сейчас включены (для экрана состояния). */
    @Volatile
    var bridgesInfo: String = ""
        private set

    val ready: Boolean get() = state == State.READY && socksPort > 0

    // Порты не как у Briar (59050/59051): оба приложения на одном телефоне не мешают друг другу.
    const val BASE_PORT = 59180

    /** После ошибки - новая попытка не раньше чем через 30 с (а не на каждом шаге менеджера). */
    @Volatile
    private var failedAt = 0L

    private val daemon = ThreadFactory { r -> Thread(r, "tor").apply { isDaemon = true } }
    private val ctl = Executors.newSingleThreadExecutor(daemon)
    private val io: Executor = Executors.newCachedThreadPool(daemon)
    private val events: Executor = Executors.newSingleThreadExecutor(daemon)
    private val sched = Executors.newSingleThreadScheduledExecutor(daemon)

    private var tor: TorWrapper? = null
    private var circumvention: CircumventionProvider? = null
    private var location: LocationUtils? = null

    fun start(ctx: Context) {
        val app = ctx.applicationContext as? Application ?: return
        if (state == State.STARTING || state == State.CONNECTING || state == State.READY) return
        if (state == State.FAILED && android.os.SystemClock.elapsedRealtime() - failedAt < 30_000) return
        // Порты выбираем сразу: ядро Tox в режиме «Tor» настраивается на них ещё до готовности Tor
        // (и до неё просто не может подключиться - мимо Tor трафик не уходит).
        val (socks, control) = freePorts()
        socksPort = socks
        set(State.STARTING, 0, null)
        ctl.execute { runCatching { startLocked(app, socks, control) }.onFailure { fail("start", it) } }
    }

    fun stop() {
        if (state == State.OFF) return
        ctl.execute {
            val t = tor
            tor = null
            socksPort = 0
            if (t != null) runCatching { t.stop() }.onFailure { Log.w(TAG, "stop: $it") }
            set(State.OFF, 0, null)
        }
    }

    /** Применить новые настройки мостов, не перезапуская Tor. */
    fun reconfigure(ctx: Context) {
        val app = ctx.applicationContext
        ctl.execute {
            val t = tor ?: return@execute
            runCatching { applyNetwork(app, t) }.onFailure { fail("bridges", it) }
        }
    }

    private fun startLocked(app: Application, socks: Int, control: Int) {
        val arch = architecture() ?: run {
            fail("arch", IllegalStateException("Tor не поддерживается процессором ${Build.SUPPORTED_ABIS.joinToString()}"))
            return
        }
        val dir = app.getDir("tor", Context.MODE_PRIVATE)
        val wakeLocks = AndroidWakeLockManagerFactory.createAndroidWakeLockManager(app, sched)
        val t = AndroidTorWrapper(app, wakeLocks, io, events, arch, dir, socks, control)
        t.setObserver(object : TorWrapper.Observer {
            override fun onState(s: TorWrapper.TorState) {
                when (s) {
                    TorWrapper.TorState.CONNECTED -> set(State.READY, 100, null)
                    TorWrapper.TorState.CONNECTING -> if (state != State.READY) set(State.CONNECTING, bootstrap, null)
                    TorWrapper.TorState.STOPPED -> if (tor === t) set(State.OFF, 0, null)
                    else -> Unit
                }
            }

            override fun onBootstrapPercentage(percentage: Int) {
                if (state != State.READY) set(if (percentage >= 100) State.READY else State.CONNECTING, percentage, null)
            }

            override fun onHsDescriptorUpload(onion: String) = Unit

            override fun onClockSkewDetected(skewSeconds: Long) {
                // Неверное время на телефоне - частая причина, по которой Tor не подключается.
                error = "clock:$skewSeconds"
                TransportManager.changed()
            }
        })
        tor = t
        t.start()
        if (state != State.READY) set(State.CONNECTING, bootstrap, null)
        applyNetwork(app, t)
    }

    private fun applyNetwork(ctx: Context, t: TorWrapper) {
        val cp = circumvention ?: CircumventionProviderFactory.createCircumventionProvider().also { circumvention = it }
        val loc = location ?: AndroidLocationUtilsFactory.createAndroidLocationUtils(ctx.applicationContext as Application).also { location = it }
        val country = runCatching { loc.currentCountry }.getOrDefault("")
        val mode = TransportPrefs.bridges(ctx)
        val types: List<CircumventionProvider.BridgeType> = when (mode) {
            BridgeMode.AUTO -> if (cp.shouldUseBridges(country)) cp.getSuitableBridgeTypes(country) else emptyList()
            BridgeMode.SNOWFLAKE -> typesNamed("SNOWFLAKE")
            BridgeMode.MEEK -> typesNamed("MEEK")
            BridgeMode.OBFS4 -> typesNamed("OBFS4")
            BridgeMode.CUSTOM, BridgeMode.NONE -> emptyList()
        }
        val lines = if (mode == BridgeMode.CUSTOM) {
            TransportPrefs.customBridges(ctx).map(::normalizeBridge)
        } else {
            types.flatMap { cp.getBridges(it, country) }
        }
        if (lines.isEmpty()) {
            t.disableBridges()
            bridgesInfo = ""
        } else {
            t.enableBridges(lines)
            bridgesInfo = if (mode == BridgeMode.CUSTOM) "custom:${lines.size}" else types.joinToString { it.name.lowercase() }
        }
        // Дополнительный «шум» в канале только тратит батарею и трафик - выключен.
        t.enableConnectionPadding(false)
        t.enableIpv6(false)
        t.enableNetwork(true)
        Log.i(TAG, "network on, country=${country.ifEmpty { "?" }}, bridges=$bridgesInfo")
    }

    private fun typesNamed(part: String) = CircumventionProvider.BridgeType.values().filter { it.name.contains(part) }

    /** Строка моста в формате torrc: «Bridge obfs4 …». Пользователь может вставить её и без «Bridge». */
    internal fun normalizeBridge(line: String): String {
        val l = line.trim().replace(Regex("\\s+"), " ")
        return if (l.startsWith("Bridge ", ignoreCase = true)) "Bridge " + l.substring(7) else "Bridge $l"
    }

    /** Как в Briar (AndroidTorPluginFactory.getArchitectureForTorBinary). */
    private fun architecture(): String? {
        for (abi in Build.SUPPORTED_ABIS) {
            when {
                abi.startsWith("x86_64") -> return "x86_64_pie"
                abi.startsWith("x86") -> return "x86_pie"
                abi.startsWith("arm64") -> return "arm64_pie"
                abi.startsWith("armeabi") -> return "arm_pie"
            }
        }
        return null
    }

    /** Два свободных соседних порта на 127.0.0.1 (SOCKS и управление). */
    private fun freePorts(): Pair<Int, Int> {
        val lo = InetAddress.getByName("127.0.0.1")
        for (i in 0 until 20) {
            val p = BASE_PORT + i * 2
            val ok = runCatching {
                ServerSocket(p, 1, lo).close()
                ServerSocket(p + 1, 1, lo).close()
            }.isSuccess
            if (ok) return p to p + 1
        }
        return BASE_PORT to BASE_PORT + 1
    }

    private fun set(s: State, pct: Int, err: String?) {
        val changed = s != state || pct != bootstrap || err != error
        state = s
        bootstrap = pct.coerceIn(0, 100)
        error = err
        if (changed) TransportManager.changed()
    }

    private fun fail(where: String, t: Throwable) {
        Log.w(TAG, "$where: $t")
        com.stoyko.smessenger.domain.Diagnostics.record("Tor: $where", t, fatal = false)
        val w = tor
        tor = null
        // Порт не обнуляем: ядро в режиме «Tor» продолжает смотреть на него и не уходит мимо Tor.
        if (w != null) runCatching { w.stop() }
        failedAt = android.os.SystemClock.elapsedRealtime()
        set(State.FAILED, 0, t.message ?: t.javaClass.simpleName)
    }

    /** Папка Tor занимает место (кэш консенсуса) - для «Данные и память». */
    fun dataDir(ctx: Context): File = ctx.getDir("tor", Context.MODE_PRIVATE)
}
