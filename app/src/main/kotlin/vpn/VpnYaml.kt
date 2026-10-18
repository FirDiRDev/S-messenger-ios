// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.util.Base64
import org.json.JSONObject

/**
 * Конфиги Clash / Mihomo (YAML) - как в YPtun, Happ, FlClash и Clash Meta:
 *  - proxies: vless / vmess / trojan / ss / hysteria2 / socks5 → обычные серверы;
 *  - wireguard с amnezia-wg-option (WARP, AmneziaWG) → готовые конфиги WARP;
 *  - proxy-providers (type: http, url) → подписки с автообновлением;
 *  - якоря и слияние (&warp-common / <<: *warp-common) поддерживаются;
 *  - правила с REJECT рекламы - подсказка включить DNS AdGuard.
 * Разбор свой, лёгкий (без библиотек): хватает для любых конфигов клиентов Clash.
 */
object VpnYaml {
    /**
     * Провайдер серверов (proxy-providers, type: http): адрес, отбор по имени (filter /
     * exclude-filter - регулярные выражения Mihomo), приставка к имени, свой User-Agent.
     * [chained] - сервер идёт через другой (dialer-proxy, каскад): так подключать мы не умеем.
     */
    class Provider(
        val name: String,
        val url: String,
        val filter: String,
        val exclude: String,
        val prefix: String,
        val userAgent: String,
        val chained: Boolean,
    ) {
        private fun rx(s: String) = s.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() }
        private val inc by lazy { rx(filter) }
        private val exc by lazy { rx(exclude) }

        /** Подходит ли сервер с таким именем под отбор провайдера. */
        fun accepts(serverName: String): Boolean =
            (inc?.containsMatchIn(serverName) ?: true) && (exc?.containsMatchIn(serverName) != true)
    }

    class Result(
        val links: List<String>,
        val warp: List<Pair<String, String>>,
        val subs: List<Pair<String, String>>,
        val adBlock: Boolean,
        val providers: List<Provider> = emptyList(),
    ) {
        val isEmpty: Boolean get() = links.isEmpty() && warp.isEmpty() && subs.isEmpty()
    }

    /** Похоже на YAML клиента Clash/Mihomo. */
    fun looksLike(text: String): Boolean {
        val t = text.trimStart()
        if (t.startsWith("{") || t.startsWith("[")) return false
        return Regex("(?m)^(proxies|proxy-providers|proxy-groups)\\s*:").containsMatchIn(t)
    }

    fun convert(text: String): Result {
        val root = runCatching { Parser(text).parse() }.getOrNull() as? Map<*, *>
            ?: return Result(emptyList(), emptyList(), emptyList(), false)
        val links = LinkedHashSet<String>()
        val warp = ArrayList<Pair<String, String>>()
        (root["proxies"] as? List<*>)?.forEach { p ->
            val m = p as? Map<*, *> ?: return@forEach
            runCatching {
                if (str(m, "type").lowercase() == "wireguard") {
                    wgConf(m)?.let { warp += str(m, "name").ifBlank { "WireGuard" } to it }
                } else {
                    link(m)?.let { links += it }
                }
            }
        }
        val subs = ArrayList<Pair<String, String>>()
        val providers = ArrayList<Provider>()
        (root["proxy-providers"] as? Map<*, *>)?.forEach { (k, v) ->
            val m = v as? Map<*, *> ?: return@forEach
            val ov = map(m, "override")
            // Встроенный список (type: inline) - серверы прямо в конфиге.
            (m["payload"] as? List<*>)?.forEach { p ->
                val pm = p as? Map<*, *> ?: return@forEach
                runCatching {
                    if (str(pm, "type").lowercase() == "wireguard") {
                        wgConf(pm)?.let { warp += str(pm, "name").ifBlank { "WireGuard" } to it }
                    } else {
                        link(pm)?.let { links += it }
                    }
                }
            }
            val url = str(m, "url")
            if (url.startsWith("http://") || url.startsWith("https://")) {
                val ua = (map(m, "header")?.get("User-Agent") as? List<*>)?.firstOrNull()?.toString()
                    ?: str(map(m, "header"), "User-Agent")
                val prov = Provider(
                    name = k.toString(),
                    url = url,
                    filter = str(m, "filter"),
                    exclude = str(m, "exclude-filter"),
                    prefix = str(ov, "additional-prefix"),
                    userAgent = ua,
                    chained = str(ov, "dialer-proxy").isNotBlank() || str(m, "dialer-proxy").isNotBlank(),
                )
                providers += prov
                if (!prov.chained) subs += url to ("📄 " + k.toString())
            }
        }
        val rules = (root["rules"] as? List<*>).orEmpty().map { it.toString().lowercase() }
        val ads = rules.any { it.endsWith(",reject") && ("ads" in it || "adblock" in it || "awavenue" in it) } ||
            (root["dns"] as? Map<*, *>)?.get("nameserver-policy")?.toString()?.contains("ads", true) == true
        return Result(links.toList(), warp, subs, ads, providers)
    }

    // ---------- Clash → ссылки ----------

    private fun str(m: Map<*, *>?, k: String): String = m?.get(k)?.toString()?.trim().orEmpty()
    private fun map(m: Map<*, *>?, k: String): Map<*, *>? = m?.get(k) as? Map<*, *>
    private fun bool(m: Map<*, *>?, k: String): Boolean = str(m, k).lowercase() in setOf("true", "yes", "on", "1")
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun q(vararg p: Pair<String, String?>): String =
        p.filter { !it.second.isNullOrBlank() }.joinToString("&") { it.first + "=" + enc(it.second!!) }

    private fun list(m: Map<*, *>?, k: String): List<String> = when (val v = m?.get(k)) {
        is List<*> -> v.map { it.toString() }
        null -> emptyList()
        else -> v.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun link(m: Map<*, *>): String? {
        val type = str(m, "type").lowercase()
        val server = str(m, "server")
        val port = str(m, "port").toIntOrNull() ?: str(m, "ports").substringBefore('-').substringBefore(',').toIntOrNull() ?: return null
        if (server.isBlank() || server == "0.0.0.0" || port <= 0) return null
        // Адреса-заглушки из документации («впиши свой VPS»: 203.0.113.x, 198.51.100.x, 192.0.2.x).
        if (server.startsWith("203.0.113.") || server.startsWith("198.51.100.") || server.startsWith("192.0.2.")) return null
        val name = str(m, "name").ifBlank { "$type $server" }
        val net = str(m, "network").lowercase().ifBlank { "tcp" }.let { if (it == "http") "tcp" else it }
        val reality = map(m, "reality-opts")
        val tls = bool(m, "tls") || reality != null
        val sni = str(m, "servername").ifBlank { str(m, "sni") }
        val fp = str(m, "client-fingerprint").ifBlank { str(m, "fingerprint") }
        val ws = map(m, "ws-opts")
        val grpc = map(m, "grpc-opts")
        val h2 = map(m, "h2-opts")
        val xh = map(m, "xhttp-opts") ?: map(m, "splithttp-opts")
        val hu = map(m, "httpupgrade-opts")
        val path = str(ws, "path").ifBlank { str(xh, "path") }.ifBlank { str(hu, "path") }.ifBlank { list(h2, "path").firstOrNull().orEmpty() }
        val host = str(map(ws, "headers"), "Host").ifBlank { str(map(ws, "headers"), "host") }
            .ifBlank { str(xh, "host") }.ifBlank { str(hu, "host") }.ifBlank { list(h2, "host").firstOrNull().orEmpty() }
        val alpn = list(m, "alpn").joinToString(",")
        val insecure = if (bool(m, "skip-cert-verify")) "1" else null
        val sec = when {
            reality != null -> "reality"
            tls -> "tls"
            else -> "none"
        }
        val netName = when (net) {
            "splithttp" -> "xhttp"
            "h2" -> "http"
            else -> net
        }
        fun stream() = q(
            "type" to netName, "security" to sec, "sni" to sni, "fp" to fp, "alpn" to alpn,
            "pbk" to str(reality, "public-key"), "sid" to str(reality, "short-id"),
            "path" to path, "host" to host, "serviceName" to str(grpc, "grpc-service-name"),
            "mode" to str(xh, "mode"), "allowInsecure" to insecure,
        )
        return when (type) {
            "vless" -> {
                val id = str(m, "uuid").ifBlank { return null }
                "vless://$id@$server:$port?" + q("encryption" to "none", "flow" to str(m, "flow")) + "&" + stream() + "#" + enc(name)
            }
            "vmess" -> {
                val id = str(m, "uuid").ifBlank { return null }
                val j = JSONObject().put("v", "2").put("ps", name).put("add", server).put("port", port.toString())
                    .put("id", id).put("aid", str(m, "alterId").ifBlank { "0" }).put("scy", str(m, "cipher").ifBlank { "auto" })
                    .put("net", netName).put("type", "none").put("host", host).put("path", path)
                    .put("tls", if (tls) "tls" else "").put("sni", sni).put("alpn", alpn).put("fp", fp)
                "vmess://" + Base64.encodeToString(j.toString().toByteArray(), Base64.NO_WRAP)
            }
            "trojan" -> {
                val pw = str(m, "password").ifBlank { return null }
                val s = q(
                    "type" to netName, "security" to if (reality != null) "reality" else "tls", "sni" to sni, "fp" to fp,
                    "alpn" to alpn, "pbk" to str(reality, "public-key"), "sid" to str(reality, "short-id"),
                    "path" to path, "host" to host, "serviceName" to str(grpc, "grpc-service-name"), "allowInsecure" to insecure,
                )
                "trojan://${enc(pw)}@$server:$port?$s#${enc(name)}"
            }
            "ss", "shadowsocks" -> {
                val method = str(m, "cipher").ifBlank { return null }
                // Плагины obfs/v2ray-plugin ядро по ссылке не поднимет - такой сервер пропускаем.
                if (str(m, "plugin").isNotBlank()) return null
                val ui = Base64.encodeToString("$method:${str(m, "password")}".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                "ss://$ui@$server:$port#" + enc(name)
            }
            "hysteria2", "hy2" -> {
                val pw = str(m, "password").ifBlank { str(m, "auth") }
                "hysteria2://${enc(pw)}@$server:$port?" + q(
                    "sni" to sni, "obfs" to str(m, "obfs"), "obfs-password" to str(m, "obfs-password"),
                    "insecure" to insecure, "alpn" to alpn,
                ) + "#" + enc(name)
            }
            "socks5", "socks" -> {
                val user = str(m, "username")
                val auth = if (user.isNotBlank()) enc(user) + ":" + enc(str(m, "password")) + "@" else ""
                "socks://$auth$server:$port#" + enc(name)
            }
            else -> null
        }
    }

    /** Ключ сервера Cloudflare WARP: у него заголовки AmneziaWG обязаны быть стандартными 1-2-3-4. */
    private const val CF_PEER = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    /** wireguard (+ amnezia-wg-option) → конфиг .conf для режима WARP. */
    private fun wgConf(m: Map<*, *>): String? {
        val priv = str(m, "private-key").ifBlank { return null }
        val server = str(m, "server").ifBlank { return null }
        val port = str(m, "port").toIntOrNull() ?: 2408
        val peerKey = str(m, "public-key")
        val ip = str(m, "ip")
        val ip6 = str(m, "ipv6")
        val addr = buildList {
            if (ip.isNotBlank()) add(if ('/' in ip) ip else "$ip/32")
            if (ip6.isNotBlank()) add(if ('/' in ip6) ip6 else "$ip6/128")
        }.ifEmpty { listOf("172.16.0.2/32") }
        val dns = list(m, "dns").ifEmpty { listOf("1.1.1.1", "1.0.0.1") }
        val mtu = str(m, "mtu").toIntOrNull() ?: 1280
        val allowed = list(m, "allowed-ips").ifEmpty { listOf("0.0.0.0/0", "::/0") }
        val awg = map(m, "amnezia-wg-option")
        val sb = StringBuilder("[Interface]\n")
        sb.append("PrivateKey = ").append(priv).append('\n')
        if (awg != null) {
            val keys = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")
            val vals = keys.associateWith { str(awg, it) }.filterValues { it.isNotBlank() }.toMutableMap()
            // Cloudflare понимает только стандартные типы пакетов: перепутанные H3/H4 (встречается в
            // готовых конфигах) ломают передачу - ставим 1-2-3-4.
            if (peerKey == CF_PEER) {
                val h = listOf("h1", "h2", "h3", "h4").mapNotNull { vals[it]?.toIntOrNull() }
                if (h.size == 4 && h.sorted() == listOf(1, 2, 3, 4)) {
                    vals["h1"] = "1"; vals["h2"] = "2"; vals["h3"] = "3"; vals["h4"] = "4"
                }
            }
            vals.forEach { (k, v) -> sb.append(k.replaceFirstChar { it.uppercase() }).append(" = ").append(v).append('\n') }
        }
        sb.append("Address = ").append(addr.joinToString(", ")).append('\n')
        sb.append("DNS = ").append(dns.joinToString(", ")).append('\n')
        sb.append("MTU = ").append(mtu).append('\n')
        sb.append("\n[Peer]\n")
        if (peerKey.isNotBlank()) sb.append("PublicKey = ").append(peerKey).append('\n')
        str(m, "pre-shared-key").takeIf { it.isNotBlank() }?.let { sb.append("PresharedKey = ").append(it).append('\n') }
        sb.append("AllowedIPs = ").append(allowed.joinToString(", ")).append('\n')
        val hostPort = if (':' in server && !server.startsWith("[")) "[$server]:$port" else "$server:$port"
        sb.append("Endpoint = ").append(hostPort).append('\n')
        sb.append("PersistentKeepalive = 25\n")
        return sb.toString()
    }

    // ---------- Лёгкий разборщик YAML ----------

    private class Line(val indent: Int, val text: String)

    private class Parser(src: String) {
        private val lines: MutableList<Line> = src.replace("\r", "").removePrefix("﻿").lines().mapNotNull { raw ->
            val t = stripComment(raw.replace("\t", "  ")).trimEnd()
            val s = t.trim()
            if (s.isEmpty() || s == "---" || s == "...") null else Line(t.length - t.trimStart().length, s)
        }.toMutableList()
        private var i = 0
        private val anchors = HashMap<String, Any?>()

        fun parse(): Any? = if (lines.isEmpty()) null else block(lines[0].indent)

        private fun isItem(t: String) = t == "-" || t.startsWith("- ")

        private fun block(ind: Int): Any? = if (isItem(lines[i].text)) list(ind) else map(ind)

        private fun list(ind: Int): List<Any?> {
            val out = ArrayList<Any?>()
            while (i < lines.size && lines[i].indent == ind && isItem(lines[i].text)) {
                val l = lines[i]
                val rest = l.text.removePrefix("-").trimStart()
                if (rest.isEmpty()) {
                    i++
                    out += if (i < lines.size && lines[i].indent > ind) block(lines[i].indent) else null
                    continue
                }
                val col = ind + (l.text.length - rest.length)
                if (!rest.startsWith("{") && !rest.startsWith("[") && keyOf(rest) != null) {
                    // «- key: value» - словарь, который начинается прямо на строке пункта.
                    lines[i] = Line(col, rest)
                    out += map(col)
                } else {
                    i++
                    out += scalar(rest)
                }
            }
            return out
        }

        private fun map(ind: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            while (i < lines.size && lines[i].indent == ind && !isItem(lines[i].text)) {
                val t = lines[i].text
                val kv = keyOf(t)
                i++
                if (kv == null) continue
                val (k, raw) = kv
                var v = raw
                var anchor: String? = null
                if (v.startsWith("&")) {
                    anchor = v.substringBefore(' ').drop(1)
                    v = v.substringAfter(' ', "").trim()
                }
                val value: Any? = when {
                    v.startsWith("|") || v.startsWith(">") -> {
                        val sb = StringBuilder()
                        while (i < lines.size && lines[i].indent > ind) {
                            if (sb.isNotEmpty()) sb.append(if (v.startsWith("|")) "\n" else " ")
                            sb.append(lines[i].text)
                            i++
                        }
                        sb.toString()
                    }
                    v.isEmpty() -> if (i < lines.size && (lines[i].indent > ind || (lines[i].indent == ind && isItem(lines[i].text)))) {
                        block(lines[i].indent)
                    } else {
                        null
                    }
                    else -> scalar(v)
                }
                if (anchor != null) anchors[anchor] = value
                if (k == "<<") {
                    val merged = when (value) {
                        is Map<*, *> -> listOf(value)
                        is List<*> -> value.filterIsInstance<Map<*, *>>()
                        else -> emptyList()
                    }
                    merged.forEach { mm -> mm.forEach { (kk, vv) -> if (!m.containsKey(kk.toString())) m[kk.toString()] = vv } }
                } else {
                    m[k] = value
                }
            }
            return m
        }

        private fun scalar(v: String): Any? {
            if (v.startsWith("*")) return anchors[v.drop(1).trim()]
            if (v.startsWith("[") || v.startsWith("{")) return Flow(v).value()
            return unquote(v)
        }

        companion object {
            /** «ключ: значение» вне кавычек; null - строка не пара. */
            fun keyOf(t: String): Pair<String, String>? {
                var quote = 0.toChar()
                for (j in t.indices) {
                    val c = t[j]
                    if (quote != 0.toChar()) {
                        if (c == quote) quote = 0.toChar()
                        continue
                    }
                    if (c == '"' || c == '\'') {
                        if (j == 0) quote = c
                        continue
                    }
                    if (c == ':' && (j == t.length - 1 || t[j + 1] == ' ')) {
                        val k = unquote(t.substring(0, j).trim())
                        if (k.isEmpty()) return null
                        return k to t.substring(j + 1).trim()
                    }
                }
                return null
            }

            fun unquote(s: String): String {
                val t = s.trim()
                if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\''))) {
                    val inner = t.substring(1, t.length - 1)
                    return if (t.first() == '"') inner.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\") else inner.replace("''", "'")
                }
                return t
            }

            fun stripComment(s: String): String {
                var quote = 0.toChar()
                for (j in s.indices) {
                    val c = s[j]
                    if (quote != 0.toChar()) {
                        if (c == quote) quote = 0.toChar()
                    } else if (c == '"' || c == '\'') {
                        if (j == 0 || s[j - 1] == ' ' || s[j - 1] == ':' || s[j - 1] == '-' || s[j - 1] == '[' || s[j - 1] == ',' || s[j - 1] == '{') quote = c
                    } else if (c == '#' && (j == 0 || s[j - 1] == ' ')) {
                        return s.substring(0, j)
                    }
                }
                return s
            }
        }
    }

    /** Строчная запись: [a, b] и {k: v, k2: [x]}. */
    private class Flow(private val s: String) {
        private var p = 0

        fun value(): Any? = runCatching { any() }.getOrNull()

        private fun ws() {
            while (p < s.length && s[p].isWhitespace()) p++
        }

        private fun any(): Any? {
            ws()
            if (p >= s.length) return null
            return when (s[p]) {
                '[' -> {
                    p++
                    val l = ArrayList<Any?>()
                    while (true) {
                        ws()
                        if (p >= s.length) break
                        if (s[p] == ']') { p++; break }
                        l += any()
                        ws()
                        if (p < s.length && s[p] == ',') p++
                    }
                    l
                }
                '{' -> {
                    p++
                    val m = LinkedHashMap<String, Any?>()
                    while (true) {
                        ws()
                        if (p >= s.length) break
                        if (s[p] == '}') { p++; break }
                        val k = atom(stopAtColon = true)
                        ws()
                        if (p < s.length && s[p] == ':') p++
                        m[k] = any()
                        ws()
                        if (p < s.length && s[p] == ',') p++
                    }
                    m
                }
                else -> atom(stopAtColon = false)
            }
        }

        private fun atom(stopAtColon: Boolean): String {
            ws()
            if (p < s.length && (s[p] == '"' || s[p] == '\'')) {
                val q = s[p++]
                val st = p
                while (p < s.length && s[p] != q) p++
                val r = s.substring(st, p.coerceAtMost(s.length))
                if (p < s.length) p++
                return r
            }
            val st = p
            while (p < s.length) {
                val c = s[p]
                if (c == ',' || c == ']' || c == '}') break
                if (stopAtColon && c == ':' && (p + 1 >= s.length || s[p + 1] == ' ')) break
                p++
            }
            return s.substring(st, p).trim()
        }
    }
}
