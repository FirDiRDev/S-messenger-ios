// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.transport

import android.content.Context
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.tox.FriendConnectionStatusHandler
import com.stoyko.smessenger.domain.tox.FriendLosslessPacketHandler
import com.stoyko.smessenger.domain.tox.ProxyType
import com.stoyko.smessenger.domain.tox.SaveOptions
import com.stoyko.smessenger.domain.tox.Tox
import com.stoyko.smessenger.domain.tox.ToxEventListener
import com.stoyko.smessenger.domain.tox.ToxMessageType
import com.stoyko.smessenger.transport.bt.BtKeys
import com.stoyko.smessenger.transport.bt.BtLink

private const val TAG = "TransportHooks"

/** Каким путём сейчас запущено ядро Tox. */
enum class Route { DIRECT, TOR, LOCAL }

/**
 * Связка «Каналов связи» с ядром Tox - всё в одном месте, чтобы в общих файлах было по одной строке:
 *  - [adjust] (ToxStarter): параметры запуска ядра под выбранный канал (SOCKS5 Tor, UDP для локальной сети);
 *  - [install] (ToxStarter): невидимые пакеты обмена ключами Bluetooth и «друг в сети».
 */
object TransportHooks {
    @Volatile
    var appliedRoute = Route.DIRECT
        private set

    @Volatile
    private var appliedSig = ""

    /** Параметры, которые ядро получило бы без «Каналов связи» (без профиля - только сеть). */
    @Volatile
    private var base: SaveOptions? = null

    @Volatile
    private var listener: ToxEventListener? = null

    @Volatile
    private var tox: Tox? = null

    private lateinit var app: Context

    /** Друзья, сейчас доступные по Tox. */
    private val online: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun onlineFriends(): Set<String> = online.toSet()

    /** Вызывается при каждом запуске ядра: подменяет параметры сети под текущий канал. */
    fun adjust(ctx: Context, opts: SaveOptions): SaveOptions = runCatching {
        app = ctx.applicationContext
        base = opts.copy(saveData = null)
        val route = TransportManager.desiredRoute(app)
        val out = optionsFor(route, opts)
        appliedRoute = if (route == Route.TOR) {
            Route.TOR
        } else if (route == Route.LOCAL) {
            Route.LOCAL
        } else {
            Route.DIRECT
        }
        appliedSig = sig(out)
        com.stoyko.smessenger.domain.feature.RouteHint.torOnly = appliedRoute == Route.TOR
        Log.i(TAG, "tox route=$appliedRoute udp=${out.udpEnabled} proxy=${out.proxyType}")
        out
    }.getOrElse {
        com.stoyko.smessenger.domain.Diagnostics.record("каналы связи: параметры ядра", it, fatal = false)
        opts
    }

    private fun optionsFor(route: Route, opts: SaveOptions): SaveOptions = when (route) {
        // Tox поверх Tor: только TCP через SOCKS5 Tor, UDP выключен - иначе IP ушёл бы мимо Tor.
        Route.TOR -> {
            if (::app.isInitialized && TransportPrefs.mode(app) == TransportMode.TOR) TorRunner.start(app)
            // Порт ещё не выбран (Tor остановлен) - всё равно только через прокси: соединений просто
            // не будет, пока Tor не поднимется, но и мимо Tor ничего не уйдёт.
            val port = TorRunner.socksPort.takeIf { it > 0 } ?: TorRunner.BASE_PORT
            opts.copy(udpEnabled = false, proxyType = ProxyType.SOCKS5, proxyAddress = "127.0.0.1", proxyPort = port)
        }
        // Без интернета: поиск друзей в локальной сети работает только по UDP и без прокси.
        Route.LOCAL -> opts.copy(udpEnabled = true, proxyType = ProxyType.None, proxyAddress = "", proxyPort = 0)
        Route.DIRECT -> opts
    }

    private fun sig(o: SaveOptions) = "${o.udpEnabled}|${o.proxyType}|${o.proxyAddress}|${o.proxyPort}"

    /** Перезапуск ядра изменил бы его сеть (иначе перезапуск не нужен). */
    fun needsRestart(route: Route): Boolean {
        val b = base ?: return false
        return sig(optionsFor(route, b)) != appliedSig
    }

    /** Ядро запущено (известен свой ключ). */
    val toxReady: Boolean get() = tox?.started == true

    fun selfKey(): String = tox?.publicKey?.string() ?: ""

    /** Текст, пришедший по Bluetooth, - через обычный обработчик входящих (как по Tox). */
    fun deliverIncoming(pk: String, text: String) {
        val l = listener ?: return
        runCatching { l.friendMessageHandler(pk, ToxMessageType.NORMAL, 0, text) }
            .onFailure { com.stoyko.smessenger.domain.Diagnostics.record("Bluetooth: входящее", it, fatal = false) }
    }

    /** Подключить обработчики (после listenerCallbacks.setUp, при каждом запуске ядра). */
    fun install(ctx: Context, l: ToxEventListener, t: Tox) {
        app = ctx.applicationContext
        listener = l
        tox = t
        online.clear()
        runCatching {
            BtKeys.init(app)
            val lossless = l.friendLosslessPacketHandler
            if (lossless !is LosslessWrap) l.friendLosslessPacketHandler = LosslessWrap(lossless)
            val status = l.friendConnectionStatusHandler
            if (status !is StatusWrap) l.friendConnectionStatusHandler = StatusWrap(status)
        }.onFailure { com.stoyko.smessenger.domain.Diagnostics.record("каналы связи: обработчики", it, fatal = false) }
    }

    private class LosslessWrap(val inner: FriendLosslessPacketHandler) : (String, ByteArray) -> Unit {
        override fun invoke(pk: String, data: ByteArray) {
            if (data.isNotEmpty() && data[0] == BtKeys.PACKET) {
                runCatching {
                    val (_, reply) = BtKeys.onPacket(pk, data, BtLink.myAddress())
                    if (reply != null) tox?.sendLosslessPacket(PublicKey(pk), reply)
                    TransportManager.changed()
                }
                return
            }
            inner(pk, data)
        }
    }

    private class StatusWrap(val inner: FriendConnectionStatusHandler) : (String, ConnectionStatus) -> Unit {
        override fun invoke(pk: String, status: ConnectionStatus) {
            inner(pk, status)
            if (status == ConnectionStatus.None) {
                online.remove(pk)
            } else {
                online.add(pk)
                offerKey(pk)
            }
        }
    }

    /** Отправить другу свой ключ Bluetooth (если Bluetooth-канал включён и ключ у него ещё не подтверждён). */
    fun offerKey(pk: String) {
        if (!::app.isInitialized || !TransportPrefs.bluetooth(app)) return
        if (!BtKeys.needsOffer(pk, System.currentTimeMillis())) return
        val p = BtKeys.offerPacket(BtLink.myAddress()) ?: return
        runCatching { tox?.sendLosslessPacket(PublicKey(pk), p) }
    }
}
