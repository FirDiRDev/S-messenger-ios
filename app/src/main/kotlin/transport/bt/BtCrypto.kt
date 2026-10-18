// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.transport.bt

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var off = 0
        var i = 1
        while (off < length) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - off)
            System.arraycopy(t, 0, out, off, n)
            off += n
            i++
        }
        return out
    }

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray = expand(extract(salt, ikm), info, length)
}

/** Ошибка защищённого канала: подмена, повтор, чужой ключ. Сеанс после неё закрывается. */
class BtSecurityException(msg: String) : IOException(msg)

/**
 * Защищённый сеанс поверх любого потока (Bluetooth RFCOMM).
 *
 * Рукопожатие - взаимная проверка подлинности с прямой секретностью (как шаблон KK протокола
 * Noise): у обеих сторон есть долговременные ключи X25519 друг друга, заранее закреплённые
 * через сквозной канал Tox. Каждый сеанс - новые одноразовые ключи:
 *  1) I → R: "SMBT" v1 | Tox-ключ I | eI
 *  2) R → I: "SMBT" v1 | Tox-ключ R | eR
 *  3) ikm = DH(eI,eR) ‖ DH(sI,eR) ‖ DH(eI,sR); соль = SHA-256 всей переписки рукопожатия и обоих
 *     долговременных ключей; HKDF-SHA256 → свой ключ AES-256-GCM на каждое направление.
 *  4) Обе стороны шлют зашифрованное подтверждение: его может собрать только владелец своего
 *     закрытого ключа, так что подмена любой стороны (человек посередине) обнаруживается сразу.
 * Затем кадры: счётчик (8 байт) ‖ AES-GCM. Счётчик строго +1 - повтор, пропуск или перестановка
 * кадра обрывают сеанс; одноразовое число (nonce) никогда не повторяется.
 */
class BtSession private constructor(
    val peerToxKey: ByteArray,
    private val sendKey: SecretKeySpec,
    private val recvKey: SecretKeySpec,
    private val sendDir: Byte,
    private val recvDir: Byte,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) {
    private var sendCtr = 1L
    private var recvCtr = 1L

    @Synchronized
    fun send(plain: ByteArray) {
        require(plain.size <= MAX_PLAIN) { "frame too big" }
        val ctr = sendCtr++
        val ct = seal(sendKey, sendDir, ctr, plain, EMPTY)
        val frame = ByteArray(8 + ct.size)
        putLong(frame, 0, ctr)
        System.arraycopy(ct, 0, frame, 8, ct.size)
        writeFrame(output, frame)
    }

    /** Следующий кадр. Бросает [BtSecurityException] при подмене или повторе. */
    fun receive(): ByteArray {
        val frame = readFrame(input)
        if (frame.size < 8 + TAG_BYTES) throw BtSecurityException("short frame")
        val ctr = getLong(frame, 0)
        if (ctr != recvCtr) throw BtSecurityException("replay or reorder: $ctr != $recvCtr")
        val plain = open(recvKey, recvDir, ctr, frame.copyOfRange(8, frame.size), EMPTY)
            ?: throw BtSecurityException("bad tag")
        recvCtr++
        return plain
    }

    companion object {
        const val VERSION: Byte = 1
        const val MAX_PLAIN = 30_000
        private const val MAX_FRAME = 32_768
        private const val TAG_BYTES = 16
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), 'T'.code.toByte())
        private val EMPTY = ByteArray(0)
        private val LABEL = "SMBT-v1".toByteArray()
        private const val DIR_I2R: Byte = 0x49
        private const val DIR_R2I: Byte = 0x52

        /**
         * Начать сеанс (мы звоним). [lookup] по Tox-ключу ответившего возвращает его закреплённый
         * ключ Bluetooth (null - незнакомое устройство: сеанс обрывается, данные не уходят).
         */
        fun initiate(
            input: DataInputStream,
            output: DataOutputStream,
            myToxKey: ByteArray,
            myStaticPriv: ByteArray,
            lookup: (ByteArray) -> ByteArray?,
        ): BtSession {
            val ePriv = X25519.newPrivateKey()
            try {
                val ePub = X25519.publicKey(ePriv)
                val m1 = hello(myToxKey, ePub)
                writeFrame(output, m1)
                val m2 = readFrame(input)
                val (rTox, eR) = parseHello(m2)
                val peerStatic = lookup(rTox) ?: throw BtSecurityException("unknown peer")
                val sPub = X25519.publicKey(myStaticPriv)
                val th = transcript(m1, m2, sPub, peerStatic)
                val ikm = concat(
                    dh(ePriv, eR),
                    dh(myStaticPriv, eR),
                    dh(ePriv, peerStatic),
                )
                val okm = Hkdf.derive(th, ikm, "$LABEL keys".toByteArray(), 64)
                ikm.fill(0)
                val kSend = SecretKeySpec(okm, 0, 32, "AES")
                val kRecv = SecretKeySpec(okm, 32, 32, "AES")
                okm.fill(0)
                writeFrame(output, seal(kSend, DIR_I2R, 0, CONFIRM_I, th))
                val conf = readFrame(input)
                val ok = open(kRecv, DIR_R2I, 0, conf, th)
                if (ok == null || !MessageDigest.isEqual(ok, CONFIRM_R)) throw BtSecurityException("peer not authenticated")
                return BtSession(rTox, kSend, kRecv, DIR_I2R, DIR_R2I, input, output)
            } finally {
                ePriv.fill(0)
            }
        }

        /**
         * Принять сеанс. [lookup] по Tox-ключу звонящего возвращает его закреплённый ключ
         * Bluetooth (или null - незнакомец, сеанс обрывается до любых данных).
         */
        fun respond(
            input: DataInputStream,
            output: DataOutputStream,
            myToxKey: ByteArray,
            myStaticPriv: ByteArray,
            lookup: (ByteArray) -> ByteArray?,
        ): BtSession {
            val m1 = readFrame(input)
            val (iTox, eI) = parseHello(m1)
            val peerStatic = lookup(iTox) ?: throw BtSecurityException("unknown peer")
            val ePriv = X25519.newPrivateKey()
            try {
                val m2 = hello(myToxKey, X25519.publicKey(ePriv))
                writeFrame(output, m2)
                val sPub = X25519.publicKey(myStaticPriv)
                val th = transcript(m1, m2, peerStatic, sPub)
                val ikm = concat(
                    dh(ePriv, eI),
                    dh(ePriv, peerStatic),
                    dh(myStaticPriv, eI),
                )
                val okm = Hkdf.derive(th, ikm, "$LABEL keys".toByteArray(), 64)
                ikm.fill(0)
                val kRecv = SecretKeySpec(okm, 0, 32, "AES")
                val kSend = SecretKeySpec(okm, 32, 32, "AES")
                okm.fill(0)
                val conf = readFrame(input)
                val ok = open(kRecv, DIR_I2R, 0, conf, th)
                if (ok == null || !MessageDigest.isEqual(ok, CONFIRM_I)) throw BtSecurityException("peer not authenticated")
                writeFrame(output, seal(kSend, DIR_R2I, 0, CONFIRM_R, th))
                return BtSession(iTox, kSend, kRecv, DIR_R2I, DIR_I2R, input, output)
            } finally {
                ePriv.fill(0)
            }
        }

        private val CONFIRM_I = "SMBT-confirm-I".toByteArray()
        private val CONFIRM_R = "SMBT-confirm-R".toByteArray()

        private fun hello(tox: ByteArray, e: ByteArray): ByteArray {
            require(tox.size == 32 && e.size == 32)
            return concat(MAGIC, byteArrayOf(VERSION), tox, e)
        }

        private fun parseHello(m: ByteArray): Pair<ByteArray, ByteArray> {
            if (m.size != 4 + 1 + 32 + 32) throw BtSecurityException("bad hello")
            if (!m.copyOfRange(0, 4).contentEquals(MAGIC)) throw BtSecurityException("bad magic")
            if (m[4] != VERSION) throw BtSecurityException("unsupported version ${m[4]}")
            return m.copyOfRange(5, 37) to m.copyOfRange(37, 69)
        }

        private fun transcript(m1: ByteArray, m2: ByteArray, sI: ByteArray, sR: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").run {
                update(LABEL)
                update(m1)
                update(m2)
                update(sI)
                update(sR)
                digest()
            }

        private fun dh(priv: ByteArray, pub: ByteArray): ByteArray =
            X25519.sharedSecret(priv, pub) ?: throw BtSecurityException("weak key")

        private fun nonce(dir: Byte, ctr: Long): ByteArray = ByteArray(12).also {
            it[0] = dir
            putLong(it, 4, ctr)
        }

        internal fun seal(key: SecretKeySpec, dir: Byte, ctr: Long, plain: ByteArray, aad: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(dir, ctr)))
            if (aad.isNotEmpty()) c.updateAAD(aad)
            return c.doFinal(plain)
        }

        internal fun open(key: SecretKeySpec, dir: Byte, ctr: Long, ct: ByteArray, aad: ByteArray): ByteArray? = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(dir, ctr)))
            if (aad.isNotEmpty()) c.updateAAD(aad)
            c.doFinal(ct)
        } catch (e: java.security.GeneralSecurityException) {
            null
        }

        fun writeFrame(out: DataOutputStream, data: ByteArray) {
            if (data.size > MAX_FRAME) throw IOException("frame too big")
            synchronized(out) {
                out.writeShort(data.size)
                out.write(data)
                out.flush()
            }
        }

        fun readFrame(input: DataInputStream): ByteArray {
            val len = input.readUnsignedShort()
            if (len == 0 || len > MAX_FRAME) throw BtSecurityException("bad frame length $len")
            val b = ByteArray(len)
            input.readFully(b)
            return b
        }

        private fun concat(vararg parts: ByteArray): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var off = 0
            for (p in parts) {
                System.arraycopy(p, 0, out, off, p.size)
                off += p.size
            }
            return out
        }

        fun putLong(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
        }

        fun getLong(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
    }
}
