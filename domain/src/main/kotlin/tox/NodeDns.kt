// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.tox

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Поиск адресов узлов без системного DNS (4.10.0).
 *
 * Раньше при плохом DNS оператора каждый узел ждал по 4 с на каждом из трёх DoH-адресов,
 * и один из них (9.9.9.9:5053) в мобильных сетях закрыт всегда - в журнале десятки
 * «SocketTimeoutException … 9.9.9.9 (port 5053)», а связь так и не появлялась.
 *
 * Теперь:
 *  - обычный DNS-запрос (UDP 53) сразу к нескольким серверам, включая Яндекс (77.88.8.8) -
 *    он открыт и в режиме «белых списков»;
 *  - DNS через HTTPS только на порт 443 (Cloudflare, Google, Quad9, AdGuard), в двух форматах:
 *    JSON и стандартный RFC 8484;
 *  - сервер, который не ответил, «отдыхает» 3 минуты - следующие узлы его не ждут;
 *  - причины неудач пишутся в журнал коротко, один раз на сервер, а не по разу на каждый узел.
 */
internal object NodeDns {
    private const val TAG = "NodeDns"
    private const val REST_MS = 3 * 60_000L

    private val udpServers = listOf("77.88.8.8", "77.88.8.1", "1.1.1.1", "8.8.8.8", "9.9.9.9", "94.140.14.140")

    /** (адрес, формат JSON?) - только порт 443: нестандартные порты в мобильных сетях закрыты. */
    private val dohServers = listOf(
        "https://1.1.1.1/dns-query" to true,
        "https://1.0.0.1/dns-query" to true,
        "https://8.8.8.8/resolve" to true,
        "https://8.8.4.4/dns-query" to false,
        "https://9.9.9.9/dns-query" to false,
        "https://94.140.14.140/dns-query" to false,
    )

    private val restUntil = ConcurrentHashMap<String, Long>()

    private fun resting(server: String) = (restUntil[server] ?: 0L) > System.currentTimeMillis()

    private fun fail(server: String, e: Throwable?, restMs: Long = REST_MS) {
        val wasResting = resting(server)
        restUntil[server] = System.currentTimeMillis() + restMs
        if (!wasResting) Log.w(TAG, "$server недоступен (${e?.javaClass?.simpleName ?: "нет ответа"}) - пропускаю ${restMs / 1000} с")
    }

    private fun ok(server: String) {
        restUntil.remove(server)
    }

    /** Первый адрес IPv4 или null. Только в фоне. */
    fun resolve(host: String): String? {
        udp(host)?.let { return it }
        return doh(host)
    }

    // ---- UDP 53: один сокет, запрос сразу на все живые серверы, берём первый ответ ----
    private fun udp(host: String): String? {
        val servers = udpServers.filterNot { resting("udp:$it") }
        if (servers.isEmpty()) return null
        val id = Random.nextInt(0, 0xFFFF)
        val query = buildQuery(host, id)
        return try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 1_800
                for (s in servers) {
                    runCatching { sock.send(DatagramPacket(query, query.size, InetSocketAddress(InetAddress.getByName(s), 53))) }
                }
                val buf = ByteArray(1500)
                val deadline = System.currentTimeMillis() + 1_800
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                    } catch (e: java.net.SocketTimeoutException) {
                        break
                    }
                    val from = p.address?.hostAddress ?: continue
                    val ip = parseA(buf.copyOf(p.length), id)
                    if (ip != null) {
                        ok("udp:$from")
                        return ip
                    }
                }
                // Никто не ответил - сеть, похоже, режет UDP 53 к чужим DNS (или ещё не поднялась
                // после переключения, как в журнале Huawei: все молчали через 2 с после появления
                // мобильной сети). Отдых короткий - 30 с, а не 3 мин: потом попробуем снова.
                servers.forEach { fail("udp:$it", null, 30_000L) }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- DNS через HTTPS ----
    private fun doh(host: String): String? {
        for ((url, json) in dohServers) {
            if (resting(url)) continue
            var conn: HttpURLConnection? = null
            try {
                val full = if (json) {
                    "$url?type=A&name=" + java.net.URLEncoder.encode(host, "UTF-8")
                } else {
                    val q = Base64.encodeToString(buildQuery(host, 0), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                    "$url?dns=$q"
                }
                conn = (URL(full).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2_500
                    readTimeout = 3_000
                    setRequestProperty("accept", if (json) "application/dns-json" else "application/dns-message")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    // Тело ошибки тоже закрываем: иначе «A resource failed to call close» в журнале.
                    runCatching { conn.errorStream?.close() }
                    fail(url, IllegalStateException("HTTP $code"))
                    continue
                }
                ok(url)
                val ip = conn.inputStream.use { input ->
                    if (json) {
                        val o = org.json.JSONObject(input.bufferedReader().readText())
                        val answers = o.optJSONArray("Answer")
                        var found: String? = null
                        if (answers != null) {
                            for (i in 0 until answers.length()) {
                                val a = answers.getJSONObject(i)
                                if (a.optInt("type") == 1) {
                                    found = a.optString("data").takeIf { it.isNotBlank() }
                                    if (found != null) break
                                }
                            }
                        }
                        found
                    } else {
                        parseA(input.readBytes(), 0)
                    }
                }
                if (ip != null) return ip
            } catch (e: Exception) {
                fail(url, e)
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    // ---- Формат DNS-сообщения (RFC 1035), только то, что нужно для записи A ----
    private fun buildQuery(host: String, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8 and 0xFF); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // RD
        out.write(0); out.write(1) // QDCOUNT
        repeat(6) { out.write(0) }
        for (label in host.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size.coerceAtMost(63))
            out.write(b, 0, b.size.coerceAtMost(63))
        }
        out.write(0)
        out.write(0); out.write(1) // A
        out.write(0); out.write(1) // IN
        return out.toByteArray()
    }

    private fun parseA(msg: ByteArray, id: Int): String? {
        if (msg.size < 12) return null
        val rid = (msg[0].toInt() and 0xFF shl 8) or (msg[1].toInt() and 0xFF)
        if (id != 0 && rid != id) return null
        if ((msg[3].toInt() and 0x0F) != 0) return null // RCODE
        val qd = (msg[4].toInt() and 0xFF shl 8) or (msg[5].toInt() and 0xFF)
        val an = (msg[6].toInt() and 0xFF shl 8) or (msg[7].toInt() and 0xFF)
        var pos = 12
        fun skipName() {
            while (pos < msg.size) {
                val len = msg[pos].toInt() and 0xFF
                if (len == 0) { pos++; return }
                if ((len and 0xC0) == 0xC0) { pos += 2; return }
                pos += len + 1
            }
        }
        repeat(qd) { skipName(); pos += 4 }
        repeat(an) {
            skipName()
            if (pos + 10 > msg.size) return null
            val type = (msg[pos].toInt() and 0xFF shl 8) or (msg[pos + 1].toInt() and 0xFF)
            val rdLen = (msg[pos + 8].toInt() and 0xFF shl 8) or (msg[pos + 9].toInt() and 0xFF)
            pos += 10
            if (type == 1 && rdLen == 4 && pos + 4 <= msg.size) {
                return "${msg[pos].toInt() and 0xFF}.${msg[pos + 1].toInt() and 0xFF}.${msg[pos + 2].toInt() and 0xFF}.${msg[pos + 3].toInt() and 0xFF}"
            }
            pos += rdLen
        }
        return null
    }
}
