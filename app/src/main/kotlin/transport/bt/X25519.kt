// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
// SPDX-FileCopyrightText: Daniel J. Bernstein, Bernard van Gastel, Wesley Janssen, Tanja Lange, Peter Schwabe, Sjaak Smetsers (TweetNaCl, public domain)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.transport.bt

import java.security.SecureRandom

/**
 * X25519 (RFC 7748) на чистом Kotlin: на Android 5-11 нет системного XDH, а тянуть
 * BouncyCastle ради одной функции - лишние мегабайты. Это дословный перенос
 * crypto_scalarmult из TweetNaCl (общественное достояние, широко проверенная реализация):
 * поле GF(2^255-19) в 16 «конечностях» по 16 бит, лестница Монтгомери с выбором без ветвлений
 * (постоянное время, без утечки ключа по времени). Проверено тестовыми векторами RFC 7748.
 */
object X25519 {
    const val KEY_SIZE = 32

    private val A24 = longArrayOf(0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val BASE = ByteArray(32).also { it[0] = 9 }
    private val rnd = SecureRandom()

    /** Новый закрытый ключ (32 случайных байта, «зажим» делается внутри [scalarMult]). */
    fun newPrivateKey(): ByteArray = ByteArray(KEY_SIZE).also { rnd.nextBytes(it) }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE)

    /**
     * Общий секрет. Возвращает null, если результат - нули (чужой ключ из «малой подгруппы»):
     * такой секрет предсказуем, сеанс с ним обрывается.
     */
    fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray? {
        if (privateKey.size != KEY_SIZE || peerPublic.size != KEY_SIZE) return null
        val s = scalarMult(privateKey, peerPublic)
        var acc = 0
        for (b in s) acc = acc or b.toInt()
        return if (acc == 0) null else s
    }

    fun scalarMult(n: ByteArray, p: ByteArray): ByteArray {
        require(n.size == 32 && p.size == 32)
        val z = n.copyOf()
        z[31] = ((n[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = LongArray(16)
        unpack(x, p)
        val a = LongArray(16)
        val b = x.copyOf()
        val c = LongArray(16)
        val d = LongArray(16)
        val e = LongArray(16)
        val f = LongArray(16)
        a[0] = 1
        d[0] = 1
        for (i in 254 downTo 0) {
            val r = ((z[i ushr 3].toInt() and 0xFF) ushr (i and 7)) and 1
            sel(a, b, r)
            sel(c, d, r)
            add(e, a, c)
            sub(a, a, c)
            add(c, b, d)
            sub(b, b, d)
            mul(d, e, e)
            mul(f, a, a)
            mul(a, c, a)
            mul(c, b, e)
            add(e, a, c)
            sub(a, a, c)
            mul(b, a, a)
            sub(c, d, f)
            mul(a, c, A24)
            add(a, a, d)
            mul(c, c, a)
            mul(a, d, f)
            mul(d, b, x)
            mul(b, e, e)
            sel(a, b, r)
            sel(c, d, r)
        }
        inv(c, c)
        mul(a, a, c)
        val out = ByteArray(32)
        pack(out, a)
        // Затираем промежуточные значения с ключом.
        z.fill(0)
        a.fill(0); b.fill(0); c.fill(0); d.fill(0); e.fill(0); f.fill(0)
        return out
    }

    private fun car(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    private fun sel(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack(o: ByteArray, n: LongArray) {
        val m = LongArray(16)
        val t = n.copyOf()
        car(t); car(t); car(t)
        for (j in 0 until 2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
    }

    private fun unpack(o: LongArray, n: ByteArray) {
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] + b[i] }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] - b[i] }

    // ThreadLocal.withInitial есть только с Android 8 - создаём буфер по-старому.
    private val tmp = object : ThreadLocal<LongArray>() {
        override fun initialValue() = LongArray(31)
    }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = tmp.get() ?: LongArray(31)
        t.fill(0)
        for (i in 0 until 16) {
            val ai = a[i]
            for (j in 0 until 16) t[i + j] += ai * b[j]
        }
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        car(o)
        car(o)
    }

    private fun inv(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            mul(c, c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        for (a in 0 until 16) o[a] = c[a]
    }
}
