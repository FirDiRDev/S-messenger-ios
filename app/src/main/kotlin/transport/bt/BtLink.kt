// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only
//
// Устроено по образцу Bluetooth-модуля Briar (GPLv3):
// bramble-android/.../plugin/bluetooth/AndroidBluetoothPlugin.java (незащищённый RFCOMM без
// сопряжения, свой UUID у каждого телефона, поиск устройств) и
// bramble-core/.../plugin/bluetooth/BluetoothConnectionLimiterImpl.java (не больше N соединений).
// Шифрование и проверка подлинности - свои (BtSession), не полагаемся на шифрование Bluetooth.

package com.stoyko.smessenger.transport.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.stoyko.smessenger.transport.TransportManager

private const val TAG = "BtLink"

/**
 * Bluetooth-канал без интернета: сервер RFCOMM ждёт друзей, поиск находит телефоны рядом,
 * сеанс - только с другом, чей ключ закреплён через Tox (см. [BtKeys], [BtSession]).
 */
@SuppressLint("MissingPermission")
object BtLink {
    /** Устройство рядом (для экрана «Каналы связи»). */
    data class Nearby(val name: String, val address: String, val friend: String?)

    private const val MAX_SESSIONS = 3
    private const val HANDSHAKE_TIMEOUT_MS = 20_000L

    @Volatile
    var running = false
        private set

    @Volatile
    var scanning = false
        private set

    @Volatile
    var nearby: List<Nearby> = emptyList()
        private set

    /** Активные защищённые сеансы: Tox-ключ друга → сокет. */
    private val sessions = ConcurrentHashMap<String, Pair<BluetoothSocket, BtSession>>()

    val sessionCount: Int get() = sessions.size

    fun connectedFriends(): Set<String> = sessions.keys.toSet()

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "bt-link").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bt-timer").apply { isDaemon = true } }

    private var server: BluetoothServerSocket? = null
    private var receiver: BroadcastReceiver? = null
    private lateinit var app: Context

    @Suppress("DEPRECATION")
    private fun adapter(): BluetoothAdapter? = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    val available: Boolean get() = adapter() != null

    val enabled: Boolean get() = runCatching { adapter()?.isEnabled == true }.getOrDefault(false)

    /** Свой UUID у каждого телефона - из его ключа: чужие телефоны нас «не видят» по общему UUID. */
    fun uuidFor(pub: ByteArray): UUID = UUID.nameUUIDFromBytes(byteArrayOf(0x53, 0x4D, 0x42, 0x54) + pub)

    /** Адрес своего Bluetooth (на Android 6+ обычно недоступен - узнаётся друзьями при встрече). */
    fun myAddress(): String? = runCatching {
        @Suppress("DEPRECATION")
        adapter()?.address?.takeIf(BtKeys::validAddress)
    }.getOrNull()

    @Synchronized
    fun start(ctx: Context) {
        app = ctx.applicationContext
        BtKeys.init(app)
        BtChat.init(app)
        BtMarks.init(app)
        if (running) return
        val a = adapter() ?: return
        if (!com.stoyko.smessenger.transport.TransportPermissions.hasBluetooth(app)) return
        if (!enabled) return
        val pub = BtKeys.myPublic() ?: return
        val ss = runCatching { a.listenUsingInsecureRfcommWithServiceRecord("S-messenger", uuidFor(pub)) }
            .onFailure { Log.w(TAG, "listen: $it") }.getOrNull() ?: return
        server = ss
        running = true
        pool.execute { acceptLoop(ss) }
        TransportManager.changed()
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        sessions.values.forEach { runCatching { it.first.close() } }
        sessions.clear()
        stopScan()
        nearby = emptyList()
        TransportManager.changed()
    }

    private fun acceptLoop(ss: BluetoothServerSocket) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) Log.i(TAG, "server closed: $e")
                break
            }
            if (sessions.size >= MAX_SESSIONS) {
                runCatching { s.close() }
                continue
            }
            pool.execute { serve(s, incoming = true) }
        }
        running = false
        TransportManager.changed()
    }

    /** Рукопожатие и чтение кадров. Любая ошибка - закрываем сокет (без падений). */
    private fun serve(s: BluetoothSocket, incoming: Boolean, expect: String? = null) {
        val priv = BtKeys.myPrivate()
        if (priv == null) {
            runCatching { s.close() }
            return
        }
        val myTox = runCatching { BtKeys.hexToBytes(com.stoyko.smessenger.transport.TransportHooks.selfKey()) }.getOrNull()
        if (myTox == null) {
            runCatching { s.close() }
            return
        }
        // Если рукопожатие зависло (устройство не отвечает) - обрываем по таймеру.
        val guard = timer.schedule({ runCatching { s.close() } }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        var friend: String? = null
        try {
            val input = DataInputStream(BufferedInputStream(s.inputStream, 16 * 1024))
            val output = DataOutputStream(BufferedOutputStream(s.outputStream, 16 * 1024))
            val lookup: (ByteArray) -> ByteArray? = { tox ->
                val pk = BtKeys.bytesToToxPk(tox)
                if (expect != null && !expect.equals(pk, ignoreCase = true)) null else BtKeys.peerKey(pk)
            }
            val session = if (incoming) {
                BtSession.respond(input, output, myTox, priv, lookup)
            } else {
                BtSession.initiate(input, output, myTox, priv, lookup)
            }
            guard.cancel(false)
            val pk = BtKeys.bytesToToxPk(session.peerToxKey)
            friend = pk
            // Настоящий адрес друга виден в соединении - запоминаем для следующих встреч.
            runCatching { BtKeys.setPeerAddress(pk, s.remoteDevice.address) }
            sessions.put(pk, s to session)?.let { old -> runCatching { old.first.close() } }
            Log.i(TAG, "secure session with ${pk.take(8)}")
            TransportManager.changed()
            BtChat.flush(pk, session)
            while (true) {
                val frame = session.receive()
                BtChat.onFrame(pk, frame, session)
            }
        } catch (e: Exception) {
            Log.i(TAG, "session ended: $e")
        } finally {
            guard.cancel(false)
            runCatching { s.close() }
            if (friend != null && sessions[friend]?.first === s) sessions.remove(friend)
            TransportManager.changed()
        }
    }

    /** Новые сообщения другу - отправить, если с ним уже есть сеанс. */
    fun kick(pk: String) {
        val (_, session) = sessions[pk.uppercase()] ?: return
        pool.execute { runCatching { BtChat.flush(pk.uppercase(), session) } }
    }

    /** Отправить ожидающее всем друзьям, с кем уже есть сеанс (дёшево: только чтение базы). */
    fun flushAll() {
        if (!running) return
        sessions.forEach { (pk, v) -> pool.execute { runCatching { BtChat.flush(pk, v.second) } } }
    }

    /** Позвонить друзьям с известным адресом, которым есть что отправить. */
    fun pulse() {
        if (!running) return
        pool.execute { runCatching { dialKnown() } }
    }

    /** Друзья с известным адресом и ожидающими сообщениями - соединяемся напрямую. */
    private fun dialKnown() {
        val a = adapter() ?: return
        for (pk in BtKeys.peers()) {
            if (!running || sessions.size >= MAX_SESSIONS) return
            if (sessions.containsKey(pk)) continue
            val addr = BtKeys.peerAddress(pk) ?: continue
            if (BtChat.pendingCount(pk) == 0) continue
            dial(a, addr, pk)
        }
    }

    private fun dial(a: BluetoothAdapter, addr: String, pk: String): Boolean {
        val pub = BtKeys.peerKey(pk) ?: return false
        if (!BluetoothAdapter.checkBluetoothAddress(addr)) return false
        val s = try {
            runCatching { a.cancelDiscovery() }
            val d = a.getRemoteDevice(addr)
            d.createInsecureRfcommSocketToServiceRecord(uuidFor(pub)).also { it.connect() }
        } catch (e: Exception) {
            // У Bluetooth бывает даже NullPointerException внутри connect() - всё это просто «не вышло».
            return false
        }
        pool.execute { serve(s, incoming = false, expect = pk) }
        return true
    }

    /** Поиск телефонов рядом (≈12 с). Найденные друзья - соединяемся, если им есть что отправить. */
    @Synchronized
    fun scan(ctx: Context) {
        app = ctx.applicationContext
        val a = adapter() ?: return
        if (scanning || !enabled) return
        if (!com.stoyko.smessenger.transport.TransportPermissions.hasBluetooth(app)) return
        // Пишет приёмник (главный поток), читает таймер-страховка - поэтому потокобезопасная карта.
        val found = ConcurrentHashMap<String, Nearby>()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d: BluetoothDevice = (
                            if (Build.VERSION.SDK_INT >= 33) {
                                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                            ) ?: return
                        // Только телефоны и планшеты (наушники, часы и колонки пропускаем).
                        val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
                        if (major != null && major != BluetoothClass.Device.Major.PHONE && major != BluetoothClass.Device.Major.COMPUTER) return
                        val addr = d.address ?: return
                        val friend = BtKeys.peers().firstOrNull { BtKeys.peerAddress(it).equals(addr, ignoreCase = true) }
                        found[addr] = Nearby(runCatching { d.name }.getOrNull() ?: addr, addr, friend)
                        nearby = found.values.toList()
                        TransportManager.changed()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finishScan(found.values.toList())
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
        }.onFailure { return }
        receiver = r
        if (runCatching { a.startDiscovery() }.getOrDefault(false)) {
            scanning = true
            TransportManager.changed()
            // На некоторых прошивках «поиск окончен» не приходит - страховка.
            timer.schedule({ finishScan(found.values.toList()) }, 20, TimeUnit.SECONDS)
        } else {
            stopScan()
        }
    }

    @Synchronized
    private fun finishScan(found: List<Nearby>) {
        if (!scanning) return
        stopScan()
        if (!running) return
        pool.execute {
            val a = adapter() ?: return@execute
            dialKnown()
            // Незнакомые адреса: пробуем UUID друзей, которым есть что отправить (не больше 3 × 6).
            val waiting = BtKeys.peers().filter { !sessions.containsKey(it) && BtChat.pendingCount(it) > 0 }.take(3)
            for (dev in found.filter { it.friend == null }.take(6)) {
                for (pk in waiting) {
                    if (!running || sessions.containsKey(pk)) continue
                    if (dial(a, dev.address, pk)) break
                }
            }
        }
    }

    @Synchronized
    private fun stopScan() {
        scanning = false
        receiver?.let { r -> runCatching { app.unregisterReceiver(r) } }
        receiver = null
        runCatching { adapter()?.cancelDiscovery() }
        TransportManager.changed()
    }

    /** Системный запрос «сделать телефон видимым» на 5 минут (чтобы друг нашёл его поиском). */
    fun discoverableIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
}
