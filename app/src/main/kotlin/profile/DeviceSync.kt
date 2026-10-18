// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import com.stoyko.smessenger.App
import com.stoyko.smessenger.ai.t
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.Sender

/**
 * Синхронизация своих устройств (4.10.0).
 *
 * Профиль перенесли на другой телефон (.smsave), а старым тоже пользуются - раньше переписка
 * расходилась: часть сообщений приходила на один телефон, часть на другой. Теперь оба телефона
 * с одним профилем, оказавшись в одной сети Wi-Fi (дом, работа, точка доступа с одного из них),
 * сами находят друг друга и обмениваются:
 *  - сообщениями личных чатов (без повторов, в обе стороны);
 *  - списком контактов (новый друг на одном телефоне появляется и на другом) и их именами.
 *
 * Защита: общий секрет (32 случайных байта) создаётся при экспорте профиля и переезжает только
 * внутри файла .smsave - посторонний телефон в той же сети синхронизироваться не может.
 * Телефоны доказывают друг другу знание секрета (HMAC со случайными числами обеих сторон, сам
 * секрет по сети не идёт), а все данные идут зашифрованными AES-GCM на ключе этого сеанса.
 * Серверов нет: всё напрямую между телефонами.
 */
object DeviceSync {
    private const val TAG = "DeviceSync"
    private const val PREFS = "device_sync"          // переезжает с профилем: секрет
    const val LOCAL_PREFS = "device_sync_local"      // остаётся на телефоне: id телефона, отметки
    private const val PORT = 47_321
    private const val MAGIC = "SMDS1"
    private const val BEACON_MS = 20_000L
    private const val MAX_FRAME = 8 * 1024 * 1024

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun local(ctx: Context) = ctx.applicationContext.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Секрет профиля (null - профиль ещё ни разу не переносили). */
    private fun secret(ctx: Context): ByteArray? =
        prefs(ctx).getString("secret", null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }?.takeIf { it.size == 32 }

    /** Экспорт профиля: создать секрет, если его ещё нет (записывается сразу - до снимка настроек). */
    fun ensureSecret(ctx: Context) {
        if (secret(ctx) != null) return
        val s = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit().putString("secret", Base64.encodeToString(s, Base64.NO_WRAP)).putBoolean("enabled", true).commit()
    }

    fun enabled(ctx: Context) = secret(ctx) != null && prefs(ctx).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", v).apply()
        if (v) start(ctx) else stop()
    }

    /** Свой id этого телефона (не переезжает с профилем). */
    private fun deviceId(ctx: Context): String {
        val p = local(ctx)
        p.getString("id", null)?.let { return it }
        val id = hex(ByteArray(8).also { SecureRandom().nextBytes(it) })
        p.edit().putString("id", id).apply()
        return id
    }

    private fun hmac(key: ByteArray, vararg parts: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { m.update(it.toByteArray()); m.update(0) }
        return m.doFinal()
    }

    /** Метка «одного профиля» в маяке - по ней не узнать ни ключ, ни секрет. */
    private fun groupTag(secret: ByteArray) = hex(hmac(secret, "group")).take(16)

    fun lastSync(ctx: Context) = local(ctx).getLong("last_sync", 0L)
    fun lastPeer(ctx: Context) = local(ctx).getString("last_peer", "").orEmpty()

    // ---------------- работа ----------------

    @Volatile
    private var running = false
    private var app: Context? = null
    @Volatile
    private var udp: DatagramSocket? = null
    @Volatile
    private var server: ServerSocket? = null
    private val busy = java.util.Collections.synchronizedSet(HashSet<String>())
    private val lastWith = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** ToxService: профиль открыт. */
    fun start(ctx: Context) {
        val a = ctx.applicationContext
        app = a
        if (running || !enabled(a)) return
        running = true
        Thread({ runCatching { serve(a) }.onFailure { Log.w(TAG, "serve: $it") } }, "devsync-tcp").apply { isDaemon = true }.start()
        Thread({ runCatching { beacon(a) }.onFailure { Log.w(TAG, "beacon: $it") } }, "devsync-udp").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { udp?.close() }
        runCatching { server?.close() }
        udp = null
        server = null
    }

    private fun serve(ctx: Context) {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(0))
        server = ss
        while (running) {
            val s = try { ss.accept() } catch (e: Exception) { break }
            Thread({ runCatching { session(ctx, s, incoming = true) }.onFailure { Log.w(TAG, "in: $it") }; runCatching { s.close() } }, "devsync-in").apply { isDaemon = true }.start()
        }
    }

    private fun beacon(ctx: Context) {
        val sock = DatagramSocket(null)
        sock.reuseAddress = true
        sock.broadcast = true
        sock.bind(InetSocketAddress(PORT))
        sock.soTimeout = 2_000
        udp = sock
        val me = deviceId(ctx)
        var lastSent = 0L
        val buf = ByteArray(512)
        while (running) {
            val sec = secret(ctx) ?: break
            val tag = groupTag(sec)
            val now = System.currentTimeMillis()
            if (now - lastSent > BEACON_MS) {
                lastSent = now
                val port = server?.localPort ?: 0
                val msg = "$MAGIC|$tag|$me|$port".toByteArray()
                runCatching { sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), PORT)) }
            }
            val p = DatagramPacket(buf, buf.size)
            try {
                sock.receive(p)
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                break
            }
            val parts = String(p.data, 0, p.length).split('|')
            if (parts.size != 4 || parts[0] != MAGIC || parts[1] != tag || parts[2] == me) continue
            val peer = parts[2]
            val port = parts[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
            // Соединяется один из двух (с меньшим id), не чаще раза в 2 минуты с тем же телефоном.
            if (me > peer) continue
            if (now - (lastWith[peer] ?: 0L) < 120_000L || !busy.add(peer)) continue
            val host = p.address
            Thread({
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), 4_000)
                        session(ctx, s, incoming = false)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "out: $e")
                } finally {
                    busy.remove(peer)
                }
            }, "devsync-out").apply { isDaemon = true }.start()
        }
        runCatching { sock.close() }
    }

    // ---------------- сеанс ----------------

    private class Channel(val inp: DataInputStream, val out: DataOutputStream, val key: ByteArray) {
        private val rnd = SecureRandom()
        fun send(o: JSONObject) {
            val iv = ByteArray(12).also { rnd.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val body = c.doFinal(o.toString().toByteArray())
            out.writeInt(iv.size + body.size)
            out.write(iv)
            out.write(body)
            out.flush()
        }

        fun recv(): JSONObject {
            val n = inp.readInt()
            if (n <= 12 || n > MAX_FRAME) throw IllegalStateException("bad frame")
            val all = ByteArray(n)
            inp.readFully(all)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
            return JSONObject(String(c.doFinal(all, 12, n - 12)))
        }
    }

    private fun session(ctx: Context, s: Socket, incoming: Boolean) {
        val sec = secret(ctx) ?: return
        s.soTimeout = 20_000
        val inp = DataInputStream(s.getInputStream().buffered())
        val out = DataOutputStream(s.getOutputStream().buffered())
        val me = deviceId(ctx)
        val myNonce = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        // 1. Привет: id и случайное число.
        out.writeUTF(JSONObject().put("v", 1).put("id", me).put("n", myNonce).toString())
        out.flush()
        val hello = JSONObject(inp.readUTF())
        val peer = hello.optString("id")
        val peerNonce = hello.optString("n")
        if (peer.isBlank() || peer == me || peerNonce.length != 32) return
        // 2. Доказательство знания секрета (сам секрет по сети не идёт).
        out.writeUTF(hex(hmac(sec, "auth", myNonce, peerNonce, me)))
        out.flush()
        val proof = inp.readUTF()
        val expect = hex(hmac(sec, "auth", peerNonce, myNonce, peer))
        if (!MessageDigest.isEqual(proof.toByteArray(), expect.toByteArray())) {
            Log.w(TAG, "peer failed auth")
            return
        }
        val nonces = listOf(myNonce, peerNonce).sorted()
        val key = hmac(sec, "key", nonces[0], nonces[1])
        val ch = Channel(inp, out, key)
        s.soTimeout = 60_000
        // 3. Обмен: каждый шлёт своё, потом читает чужое.
        val since = local(ctx).getLong("since_$peer", 0L)
        // Порядок разный у сторон: иначе при большой переписке оба телефона одновременно
        // пишут и ни один не читает - сеанс зависает на заполненном буфере.
        val theirs: JSONObject
        if (incoming) {
            theirs = ch.recv()
            ch.send(pack(ctx, since))
        } else {
            ch.send(pack(ctx, since))
            theirs = ch.recv()
        }
        val added = applyData(ctx, theirs)
        val now = System.currentTimeMillis()
        // В следующий раз - только новое (с запасом 10 минут на неточные часы).
        local(ctx).edit()
            .putLong("since_$peer", now - 10 * 60_000L)
            .putLong("last_sync", now)
            .putString("last_peer", theirs.optString("model", peer.take(6)))
            .apply()
        lastWith[peer] = now
        com.stoyko.smessenger.domain.Diagnostics.crumb("синхронизация устройств: +$added")
        Log.i(TAG, "synced with $peer, added $added (incoming=$incoming)")
    }

    private fun component(ctx: Context) = (ctx.applicationContext as App).component

    private fun pack(ctx: Context, since: Long): JSONObject {
        val c = component(ctx)
        val msgs = JSONArray()
        c.messageRepository().since(since).forEach { m ->
            if (m.type == MessageType.FileTransfer) return@forEach
            msgs.put(
                JSONObject()
                    .put("c", m.publicKey)
                    .put("m", m.message)
                    .put("s", m.sender.ordinal)
                    .put("y", m.type.ordinal)
                    .put("t", m.timestamp),
            )
        }
        val contacts = JSONArray()
        runCatching {
            kotlinx.coroutines.runBlocking { c.contactRepository().getAll().first() }
        }.getOrNull()?.forEach { ct ->
            contacts.put(JSONObject().put("pk", ct.publicKey).put("n", ct.name))
        }
        return JSONObject().put("t", "data").put("msgs", msgs).put("contacts", contacts)
            .put("model", android.os.Build.MODEL ?: "")
    }

    private fun applyData(ctx: Context, o: JSONObject): Int {
        val c = component(ctx)
        val repo = c.contactRepository()
        val tox = runCatching { c.tox() }.getOrNull()
        // Контакты: новый друг с другого телефона - добавляем и здесь (без запроса дружбы:
        // ключ тот же, друг уже подтвердил дружбу с этим профилем).
        o.optJSONArray("contacts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                val pk = j.optString("pk").uppercase()
                if (pk.length != 64 || !pk.all { it in '0'..'9' || it in 'A'..'F' }) continue
                if (!repo.exists(pk)) {
                    runCatching { tox?.takeIf { it.started }?.acceptFriendRequest(PublicKey(pk)) }
                    runCatching { repo.add(Contact(pk, name = j.optString("n"))) }
                } else {
                    val n = j.optString("n")
                    if (n.isNotBlank()) runCatching {
                        val cur = kotlinx.coroutines.runBlocking { repo.get(pk).firstOrNull() }
                        if (cur != null && cur.name.isBlank()) repo.setName(pk, n)
                    }
                }
            }
        }
        // Сообщения: только в существующие чаты, без повторов.
        var added = 0
        val mr = c.messageRepository()
        o.optJSONArray("msgs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                val pk = j.optString("c")
                if (!repo.exists(pk)) continue
                val sender = Sender.entries.getOrNull(j.optInt("s", -1)) ?: continue
                val type = MessageType.entries.getOrNull(j.optInt("y", -1)) ?: continue
                if (type == MessageType.FileTransfer) continue
                val ts = j.optLong("t", 0L)
                if (ts <= 0L) continue
                val m = Message(pk, j.optString("m"), sender, type, 0, ts)
                if (runCatching { mr.addSynced(m) }.getOrDefault(false)) added++
            }
        }
        return added
    }

    // ---------------- окно ----------------

    /** Окно «Синхронизация устройств»: состояние, включить/выключить, «сейчас». */
    fun show(ctx: Context) {
        val has = secret(ctx) != null
        val last = lastSync(ctx)
        val text = if (!has) {
            t(
                "Синхронизация включится сама после переноса профиля: «Экспорт» → файл .smsave → «Импорт» на новом телефоне.\n\nКогда оба телефона с этим профилем в одной сети Wi-Fi (или один раздаёт точку доступа другому), они сами обмениваются сообщениями и контактами - зашифрованно, напрямую, без серверов.",
                "Sync turns on by itself after you move the profile: Export → .smsave file → Import on the new phone.\n\nWhen both phones with this profile are on the same Wi-Fi (or one shares a hotspot), they exchange messages and contacts by themselves - encrypted, directly, no servers.",
            )
        } else {
            val whenText = if (last > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(last)) else t("ещё не было", "not yet")
            t(
                "Оба телефона с этим профилем сами обмениваются сообщениями и контактами, когда они в одной сети Wi-Fi или точке доступа. Данные шифруются, посторонний телефон подключиться не может.\n\nПоследняя синхронизация: $whenText",
                "Both phones with this profile exchange messages and contacts by themselves when they are on the same Wi-Fi or hotspot. Data is encrypted; other phones can't connect.\n\nLast sync: $whenText",
            )
        }
        val b = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(t("🔄 Синхронизация устройств", "🔄 Device sync"))
            .setMessage(text)
            .setNegativeButton(android.R.string.ok, null)
        if (has) {
            val on = enabled(ctx)
            b.setPositiveButton(if (on) t("Выключить", "Turn off") else t("Включить", "Turn on")) { _, _ -> setEnabled(ctx, !on) }
            if (on) b.setNeutralButton(t("Сейчас", "Sync now")) { _, _ ->
                lastWith.clear()
                local(ctx).edit().putLong("nudge", System.currentTimeMillis()).apply()
                if (!running) start(ctx)
                android.widget.Toast.makeText(ctx, t("Ищу второй телефон в этой сети…", "Looking for your other phone on this network…"), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        b.show()
    }
}
