// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.transport.bt

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BtKeys"

/**
 * Ключи Bluetooth-канала.
 *
 * У каждого телефона - свой долговременный ключ X25519 (только для Bluetooth, не ключ Tox).
 * Друзья обмениваются открытыми ключами невидимым пакетом ВНУТРИ уже проверенного сквозного
 * канала Tox (пока оба в сети) - так ключ нельзя подменить: его подлинность гарантирует Tox.
 * Потом, без интернета, Bluetooth-сеанс принимается только с закреплённым ключом друга.
 */
object BtKeys {
    /** Номер невидимого пакета ядра (пользовательский диапазон 0xA0-0xBF, свободный). */
    const val PACKET: Byte = 0xB7.toByte()
    private const val OFFER: Byte = 1
    private const val REPLY: Byte = 2
    private const val VERSION: Byte = 1

    private const val FILE = "transport_bt"

    private var prefs: SharedPreferences? = null

    @Volatile
    private var myPriv: ByteArray? = null

    /** Друзья, которые уже прислали ответ на наш ключ (значит, наш ключ у них есть). */
    private val confirmed = ConcurrentHashMap<String, Boolean>()

    /** Последняя отправка предложения другу (не чаще раза в 10 минут). */
    private val lastOffer = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val stored = p.getString("my_priv", null)?.let(::unhex)
        myPriv = if (stored != null && stored.size == 32) {
            stored
        } else {
            X25519.newPrivateKey().also { p.edit().putString("my_priv", hex(it)).apply() }
        }
        p.getStringSet("confirmed", emptySet())?.forEach { confirmed[it] = true }
    }

    fun myPrivate(): ByteArray? = myPriv

    fun myPublic(): ByteArray? = myPriv?.let { X25519.publicKey(it) }

    /** Закреплённый ключ друга (по Tox-ключу в hex). */
    fun peerKey(toxPk: String): ByteArray? = prefs?.getString("peer_" + toxPk.uppercase(), null)?.let(::unhex)?.takeIf { it.size == 32 }

    /** Последний известный адрес Bluetooth друга (узнаём из встреч и из его предложения). */
    fun peerAddress(toxPk: String): String? = prefs?.getString("addr_" + toxPk.uppercase(), null)?.takeIf { it.isNotEmpty() }

    fun setPeerAddress(toxPk: String, addr: String) {
        if (!validAddress(addr)) return
        prefs?.edit()?.putString("addr_" + toxPk.uppercase(), addr.uppercase())?.apply()
    }

    /** Друзья с закреплёнными ключами (Tox-ключи). */
    fun peers(): List<String> = prefs?.all?.keys?.filter { it.startsWith("peer_") }?.map { it.removePrefix("peer_") }.orEmpty()

    /** Отпечаток ключа для сверки глазами (как «код безопасности»). */
    fun fingerprint(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).take(6).joinToString(" ") { "%02X".format(it) }

    /**
     * Нужно ли отправить другу наш ключ (он в сети по Tox). Пакет строит [offerPacket],
     * отправляет вызывающий (ему доступно ядро).
     */
    fun needsOffer(toxPk: String, now: Long): Boolean {
        if (myPriv == null) return false
        val pk = toxPk.uppercase()
        if (confirmed[pk] == true && peerKey(pk) != null) return false
        val last = lastOffer[pk] ?: 0L
        if (now - last < 10 * 60_000L) return false
        lastOffer[pk] = now
        return true
    }

    fun offerPacket(myAddress: String?): ByteArray? = packet(OFFER, myAddress)

    private fun packet(type: Byte, myAddress: String?): ByteArray? {
        val pub = myPublic() ?: return null
        val addr = (myAddress?.takeIf(::validAddress) ?: "").toByteArray(Charsets.US_ASCII)
        return byteArrayOf(PACKET, type, VERSION) + pub + byteArrayOf(addr.size.toByte()) + addr
    }

    /**
     * Невидимый пакет от друга (пришёл по Tox - его отправитель проверен ядром).
     * Возвращает ответ, который нужно отправить обратно (или null). [handled] - пакет наш.
     */
    fun onPacket(toxPk: String, data: ByteArray, myAddress: String?): Pair<Boolean, ByteArray?> {
        if (data.isEmpty() || data[0] != PACKET) return false to null
        if (data.size < 3 + 32 + 1 || data[2] != VERSION) return true to null
        val type = data[1]
        val pub = data.copyOfRange(3, 35)
        val addrLen = data[35].toInt() and 0xFF
        val addr = if (addrLen in 1..17 && data.size >= 36 + addrLen) String(data, 36, addrLen, Charsets.US_ASCII) else ""
        val pk = toxPk.uppercase()
        val p = prefs ?: return true to null
        // Ключ друга из «малой подгруппы» (нулевой общий секрет) не принимаем.
        val test = myPriv?.let { X25519.sharedSecret(it, pub) }
        if (test == null) {
            Log.w(TAG, "weak key from friend ignored")
            return true to null
        }
        val e = p.edit().putString("peer_$pk", hex(pub))
        if (validAddress(addr)) e.putString("addr_$pk", addr.uppercase())
        if (type == REPLY) {
            confirmed[pk] = true
            e.putStringSet("confirmed", HashSet(confirmed.keys))
        }
        e.apply()
        return true to if (type == OFFER) packet(REPLY, myAddress) else null
    }

    /** Наш ключ сменился у друга / друг удалён - забыть. */
    fun forget(toxPk: String) {
        val pk = toxPk.uppercase()
        confirmed.remove(pk)
        prefs?.edit()?.remove("peer_$pk")?.remove("addr_$pk")?.putStringSet("confirmed", HashSet(confirmed.keys))?.apply()
    }

    // Android 6+ отдаёт приложениям вместо адреса заглушку 02:00:00:00:00:00 - её не храним.
    fun validAddress(a: String?): Boolean =
        a != null && a.matches(Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")) && a != "02:00:00:00:00:00" && a != "00:00:00:00:00:00"

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray? = runCatching {
        if (s.length % 2 != 0) return@runCatching null
        ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }.getOrNull()

    fun hexToBytes(toxPk: String): ByteArray? = unhex(toxPk)?.takeIf { it.size == 32 }

    fun bytesToToxPk(b: ByteArray): String = hex(b).uppercase()
}
