// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only
//
// Создание сети Wi-Fi Direct - по образцу «точки доступа» Briar (GPLv3):
// briar-android/.../android/hotspot/HotspotManager.java (повтор при BUSY с новым каналом,
// постоянные имя и пароль сети на Android 10+, диапазон 2,4 ГГц для совместимости).

package com.stoyko.smessenger.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.security.SecureRandom

private const val TAG = "WifiDirect"

/**
 * Wi-Fi без роутера и без интернета: один телефон создаёт сеть Wi-Fi Direct, второй
 * подключается к нему (через «Найти телефоны рядом» или как к обычной сети Wi-Fi по имени и
 * паролю / QR-коду). Дальше Tox находит друг друга сам - поиском в локальной сети
 * (широковещательные пакеты UDP), трафик остаётся сквозь-зашифрованным Tox.
 */
@SuppressLint("MissingPermission")
object WifiDirect {
    enum class State { IDLE, CREATING, OWNER, SEARCHING, CONNECTING, CONNECTED, FAILED }

    @Volatile
    var state = State.IDLE
        private set

    @Volatile
    var ssid: String? = null
        private set

    @Volatile
    var passphrase: String? = null
        private set

    @Volatile
    var peers: List<WifiP2pDevice> = emptyList()
        private set

    @Volatile
    var error: Int = 0
        private set

    /** Есть общая сеть с другим телефоном (мы - владелец с участниками, или участник). */
    @Volatile
    var linked = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private lateinit var app: Context

    val supported: Boolean
        get() = ::app.isInitialized && app.packageManager.hasSystemFeature("android.hardware.wifi.direct")

    private fun ensure(ctx: Context): Boolean {
        app = ctx.applicationContext
        if (manager == null) manager = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        val m = manager ?: return false
        if (channel == null) channel = runCatching { m.initialize(app, Looper.getMainLooper(), null) }.getOrNull()
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.action) {
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnection()
                    }
                }
            }
            val f = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
                receiver = r
            }
        }
        return channel != null
    }

    /** Создать свою сеть (мы - точка доступа). [attempt] - повтор, если система занята (BUSY). */
    fun createGroup(ctx: Context, attempt: Int = 1) {
        if (!ensure(ctx)) return fail(WifiP2pManager.P2P_UNSUPPORTED)
        val m = manager ?: return
        val ch = channel ?: return
        set(State.CREATING)
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = requestGroupInfo(1)

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && attempt < 5) {
                    // Как в Briar: при BUSY канал нужно создать заново, иначе отказ будет всегда.
                    // Возможно, группа уже есть - сначала убираем её.
                    runCatching { m.removeGroup(ch, null) }
                    if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
                    channel = null
                    main.postDelayed({ createGroup(ctx, attempt + 1) }, 1000)
                } else {
                    fail(reason)
                }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val (name, pass) = savedNetwork(ctx)
                val cfg = WifiP2pConfig.Builder()
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .setNetworkName(name)
                    .setPassphrase(pass)
                    .build()
                m.createGroup(ch, cfg, listener)
            } else {
                m.createGroup(ch, listener)
            }
        }.onFailure { Log.w(TAG, "createGroup: $it"); fail(WifiP2pManager.ERROR) }
    }

    /** Найти телефоны рядом с включённым Wi-Fi Direct. */
    fun discover(ctx: Context) {
        if (!ensure(ctx)) return fail(WifiP2pManager.P2P_UNSUPPORTED)
        val m = manager ?: return
        val ch = channel ?: return
        if (state != State.OWNER && state != State.CONNECTED) set(State.SEARCHING)
        runCatching {
            m.discoverPeers(
                ch,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = fail(reason)
                },
            )
        }.onFailure { fail(WifiP2pManager.ERROR) }
    }

    /** Подключиться к телефону из списка (он станет владельцем сети или мы - как решит система). */
    fun connect(ctx: Context, device: WifiP2pDevice) {
        if (!ensure(ctx)) return
        val m = manager ?: return
        val ch = channel ?: return
        set(State.CONNECTING)
        val cfg = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Ниже желание быть владельцем - чаще владельцем становится тот, кто создал сеть.
            groupOwnerIntent = 0
        }
        runCatching {
            m.connect(
                ch,
                cfg,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = fail(reason)
                },
            )
        }.onFailure { fail(WifiP2pManager.ERROR) }
    }

    /** Выключить: убрать свою сеть / отключиться, остановить поиск. */
    fun stop() {
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            runCatching { m.stopPeerDiscovery(ch, null) }
            runCatching { m.removeGroup(ch, null) }
        }
        receiver?.let { r -> runCatching { app.unregisterReceiver(r) } }
        receiver = null
        if (Build.VERSION.SDK_INT >= 27) runCatching { ch?.close() }
        channel = null
        ssid = null
        passphrase = null
        peers = emptyList()
        linked = false
        set(State.IDLE)
    }

    private fun requestGroupInfo(attempt: Int) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.requestGroupInfo(ch) { g: WifiP2pGroup? ->
                if (g == null || g.networkName.isNullOrEmpty()) {
                    // Сразу после создания группа бывает ещё не готова - спрашиваем снова.
                    if (attempt < 6) main.postDelayed({ requestGroupInfo(attempt + 1) }, 500L * attempt) else fail(WifiP2pManager.ERROR)
                    return@requestGroupInfo
                }
                ssid = g.networkName
                passphrase = g.passphrase
                linked = g.clientList?.isNotEmpty() == true
                set(if (g.isGroupOwner) State.OWNER else State.CONNECTED)
            }
        }
    }

    private fun requestPeers() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.requestPeers(ch) { list ->
                peers = list?.deviceList?.toList().orEmpty()
                TransportManager.changed()
            }
        }
    }

    private fun requestConnection() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                val formed = info?.groupFormed == true
                if (formed) {
                    requestGroupInfo(1)
                    linked = true
                    // Общая сеть появилась - ядро Tox сразу ищет друзей в ней.
                    TransportManager.onLocalLinkUp()
                } else {
                    linked = false
                    if (state == State.CONNECTED || state == State.CONNECTING) set(State.IDLE)
                }
                TransportManager.changed()
            }
        }
    }

    /** Постоянные имя и пароль своей сети: друг сохраняет сеть один раз. */
    private fun savedNetwork(ctx: Context): Pair<String, String> {
        val p = ctx.getSharedPreferences("transport", Context.MODE_PRIVATE)
        var name = p.getString("p2p_name", null)
        var pass = p.getString("p2p_pass", null)
        if (name == null || pass == null) {
            val rnd = SecureRandom()
            val abc = "abcdefghjkmnpqrstuvwxyz23456789"
            fun word(n: Int) = String(CharArray(n) { abc[rnd.nextInt(abc.length)] })
            // Имя обязано начинаться с «DIRECT-xy» (требование Wi-Fi Direct).
            name = "DIRECT-sm-S-messenger-" + word(4)
            pass = word(12)
            p.edit().putString("p2p_name", name).putString("p2p_pass", pass).apply()
        }
        return name!! to pass!!
    }

    /** QR-строка для подключения камерой (стандартный формат Wi-Fi). */
    fun qrText(): String? {
        val s = ssid ?: return null
        val p = passphrase ?: return null
        fun esc(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        return "WIFI:T:WPA;S:${esc(s)};P:${esc(p)};;"
    }

    private fun fail(reason: Int) {
        error = reason
        set(State.FAILED)
    }

    private fun set(s: State) {
        state = s
        TransportManager.changed()
    }
}
