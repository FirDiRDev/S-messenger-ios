// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.net.Uri
import android.util.Base64
import java.net.URLDecoder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Сервер VPN (одна ссылка vless://, vmess://, trojan://, ss://). */
data class VpnProfile(
    val id: String,
    val name: String,
    val link: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val subId: String = "",
    /** Реальная задержка, мс: -1 - не отвечает, 0 - ещё не проверяли. */
    val ping: Long = 0,
    /** Сколько проверок подряд сервер не ответил (мёртвым считается после двух). */
    val fails: Int = 0,
) {
    /** Новый результат проверки: неудачи подряд копятся, ответ сбрасывает счётчик. */
    fun withPing(ms: Long): VpnProfile = when {
        ms < 0 -> copy(ping = ms, fails = fails + 1)
        ms > 0 -> copy(ping = ms, fails = 0)
        else -> copy(ping = ms)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("link", link).put("proto", protocol)
        .put("host", host).put("port", port).put("sub", subId).put("ping", ping).put("fails", fails)

    companion object {
        fun fromJson(o: JSONObject) = VpnProfile(
            o.getString("id"), o.optString("name"), o.getString("link"), o.optString("proto"),
            o.optString("host"), o.optInt("port"), o.optString("sub"), o.optLong("ping"), o.optInt("fails"),
        )
    }
}

/**
 * Разбор ссылок в формате v2rayNG/Happ/Hiddify и сборка конфигурации Xray.
 * Поддерживаются VLESS (в т.ч. REALITY и XTLS Vision), VMess, Trojan, Shadowsocks,
 * Hysteria2, WireGuard и SOCKS5,
 * транспорты tcp/raw, ws, grpc, xhttp/splithttp, httpupgrade, h2, kcp.
 */
object VpnLinks {
    const val SOCKS_PORT = 10808

    /** HTTP-прокси ядра: через него ходит встроенный браузер (сам мессенджер исключён из туннеля). */
    const val HTTP_PORT = 10809
    val SCHEMES = listOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "wireguard", "wg", "socks", "socks5", "socks5h")

    fun isLink(s: String): Boolean = SCHEMES.any { s.trim().startsWith("$it://", ignoreCase = true) }

    /**
     * Текст из буфера, файла (TXT/JSON) или подписки: ссылки где угодно в тексте (по строкам,
     * внутри JSON, через запятую), base64-подписки, конфиги Xray и sing-box (outbounds) и
     * списки SOCKS5 вида «хост:порт» или «хост:порт:логин:пароль».
     */
    fun parseMany(text: String, subId: String = "", hint: String = ""): List<VpnProfile> =
        extractLinks(text, hint).mapNotNull { runCatching { parse(it, subId) }.getOrNull() }

    private val LINK_RE = Regex("(?i)\\b(vless|vmess|trojan|ss|hysteria2|hy2|wireguard|wg|socks5h?|socks)://[^\\s\"'<>`\\\\]+")
    private val HOSTPORT_RE = Regex("^([A-Za-z0-9.-]+|\\[[0-9a-fA-F:]+]):(\\d{2,5})(?::([^:\\s]+):(\\S+))?$")

    /** Все ссылки VPN из любого текста (без повторов, socks5:// и socks4:// приводятся к socks://). */
    fun extractLinks(text: String, hint: String = "", strict: Boolean = false): List<String> {
        var body = text.trim().removePrefix("\uFEFF")
        val out = LinkedHashSet<String>()
        fun norm(l: String) = l.replace(Regex("^(?i)socks5h?://"), "socks://")
        fun scan(t: String) {
            for (line in t.lines()) {
                val whole = line.trim()
                // Строка - целиком ссылка: берём как есть (в имени сервера после # бывают пробелы и скобки).
                val sp = whole.indexOfFirst { it.isWhitespace() }
                if (isLink(whole) && whole.none { it == '"' || it == '`' } && (sp < 0 || whole.indexOf('#') in 0 until sp)) {
                    out += norm(whole)
                    continue
                }
                // Ссылки внутри текста или JSON (слэши в JSON бывают экранированы: vless:\/\/…).
                LINK_RE.findAll(line.replace("\\/", "/")).forEach { m ->
                    val l = norm(m.value.trimEnd(',', ';', ')', ']', '}', '.', '"', '\''))
                    if (isLink(l)) out += l
                }
            }
        }
        scan(body)
        // Обычное сообщение в чате: только явные ссылки.
        if (strict) return out.toList()
        if (out.isEmpty()) {
            // Подписка в base64.
            decodeBase64(body.replace("\n", "").replace("\r", ""))?.let { body = it; scan(it) }
        }
        // JSON: конфиги Xray / sing-box.
        val js = body.trimStart()
        if (out.isEmpty() && (js.startsWith("{") || js.startsWith("["))) {
            runCatching {
                val root: Any = if (js.startsWith("{")) JSONObject(js) else JSONArray(js)
                jsonLinks(root, out)
            }
        }
        // YAML Clash / Mihomo (proxies: …) - как в YPtun и Happ.
        if (out.isEmpty() && VpnYaml.looksLike(body)) {
            runCatching { out += VpnYaml.convert(body).links }
        }
        // Список SOCKS5: «хост:порт» или «хост:порт:логин:пароль» по строкам.
        if (out.isEmpty()) {
            val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            val socks = lines.mapNotNull { HOSTPORT_RE.matchEntire(it) }
            if (socks.isNotEmpty() && (socks.size == lines.size || hint.contains("socks", true))) {
                socks.forEach { m ->
                    val host = m.groupValues[1]
                    val port = m.groupValues[2]
                    val user = m.groupValues[3]
                    val pass = m.groupValues[4]
                    val auth = if (user.isNotEmpty()) enc(user) + ":" + enc(pass) + "@" else ""
                    out += "socks://$auth$host:$port#SOCKS5 $host:$port"
                }
            }
        }
        return out.toList()
    }

    /** Обход JSON: outbounds Xray (protocol) и sing-box (type) превращаются в ссылки. */
    private fun jsonLinks(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) jsonLinks(node.opt(i), out)
            is JSONObject -> {
                runCatching { xrayLink(node) ?: singLink(node) }.getOrNull()?.let { out += it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = node.opt(keys.next())
                    if (v is JSONObject || v is JSONArray) jsonLinks(v, out)
                }
            }
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun query(vararg p: Pair<String, String?>): String =
        p.filter { !it.second.isNullOrBlank() }.joinToString("&") { it.first + "=" + enc(it.second!!) }

    /** Исходящее соединение Xray → ссылка. */
    private fun xrayLink(o: JSONObject): String? {
        val proto = o.optString("protocol").lowercase()
        if (proto !in setOf("vless", "vmess", "trojan", "shadowsocks", "socks")) return null
        val st = o.optJSONObject("settings") ?: return null
        val name = o.optString("tag").ifBlank { proto }
        val ss = o.optJSONObject("streamSettings")
        val net = ss?.optString("network").orEmpty().ifBlank { "tcp" }
        val sec = ss?.optString("security").orEmpty()
        val tls = ss?.optJSONObject("tlsSettings")
        val rl = ss?.optJSONObject("realitySettings")
        val ws = ss?.optJSONObject("wsSettings")
        val grpc = ss?.optJSONObject("grpcSettings")
        val xh = ss?.optJSONObject("xhttpSettings") ?: ss?.optJSONObject("splithttpSettings")
        val path = ws?.optString("path") ?: xh?.optString("path")
        val host = ws?.optJSONObject("headers")?.optString("Host") ?: ws?.optString("host") ?: xh?.optString("host")
        val sni = rl?.optString("serverName")?.ifBlank { null } ?: tls?.optString("serverName")
        val fp = rl?.optString("fingerprint")?.ifBlank { null } ?: tls?.optString("fingerprint")
        fun streamQuery(extra: Array<Pair<String, String?>>) = query(
            "type" to net, "security" to sec.ifBlank { "none" }, "sni" to sni, "fp" to fp,
            "pbk" to rl?.optString("publicKey"), "sid" to rl?.optString("shortId"), "spx" to rl?.optString("spiderX"),
            "path" to path, "host" to host, "serviceName" to grpc?.optString("serviceName"),
            "alpn" to tls?.optJSONArray("alpn")?.let { a -> (0 until a.length()).joinToString(",") { a.optString(it) } },
            "mode" to xh?.optString("mode"), "extra" to xh?.optJSONObject("extra")?.toString(), *extra,
        )
        return when (proto) {
            "vless", "vmess" -> {
                val v = st.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                val u = v.optJSONArray("users")?.optJSONObject(0) ?: return null
                val addr = v.optString("address")
                val port = v.optInt("port")
                if (addr.isBlank() || port <= 0) return null
                if (proto == "vless") {
                    "vless://${u.optString("id")}@$addr:$port?" + streamQuery(arrayOf("encryption" to "none", "flow" to u.optString("flow"))) + "#" + enc(name)
                } else {
                    val j = JSONObject().put("v", "2").put("ps", name).put("add", addr).put("port", port.toString())
                        .put("id", u.optString("id")).put("aid", u.optInt("alterId").toString()).put("scy", u.optString("security", "auto"))
                        .put("net", net).put("type", "none").put("host", host.orEmpty()).put("path", path.orEmpty())
                        .put("tls", if (sec == "tls") "tls" else "").put("sni", sni.orEmpty())
                    "vmess://" + Base64.encodeToString(j.toString().toByteArray(), Base64.NO_WRAP)
                }
            }
            "trojan" -> {
                val sv = st.optJSONArray("servers")?.optJSONObject(0)?.takeIf { it.optString("address").isNotBlank() && it.optInt("port") > 0 } ?: return null
                "trojan://${enc(sv.optString("password"))}@${sv.optString("address")}:${sv.optInt("port")}?" + streamQuery(emptyArray()) + "#" + enc(name)
            }
            "shadowsocks" -> {
                val sv = st.optJSONArray("servers")?.optJSONObject(0)?.takeIf { it.optString("address").isNotBlank() && it.optInt("port") > 0 } ?: return null
                val ui = Base64.encodeToString("${sv.optString("method")}:${sv.optString("password")}".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                "ss://$ui@${sv.optString("address")}:${sv.optInt("port")}#" + enc(name)
            }
            else -> {
                val sv = st.optJSONArray("servers")?.optJSONObject(0)?.takeIf { it.optString("address").isNotBlank() && it.optInt("port") > 0 } ?: return null
                val usr = sv.optJSONArray("users")?.optJSONObject(0)
                val auth = usr?.let { enc(it.optString("user")) + ":" + enc(it.optString("pass")) + "@" }.orEmpty()
                "socks://$auth${sv.optString("address")}:${sv.optInt("port")}#" + enc(name)
            }
        }
    }

    /** Исходящее соединение sing-box → ссылка. */
    private fun singLink(o: JSONObject): String? {
        val type = o.optString("type").lowercase()
        if (type !in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "hysteria2")) return null
        val server = o.optString("server")
        val port = o.optInt("server_port")
        if (server.isBlank() || port <= 0) return null
        val name = o.optString("tag").ifBlank { type }
        val tls = o.optJSONObject("tls")
        val reality = tls?.optJSONObject("reality")?.takeIf { it.optBoolean("enabled") }
        val tr = o.optJSONObject("transport")
        val trType = tr?.optString("type").orEmpty()
        val net = when (trType) {
            "" -> "tcp"
            "http" -> "h2"
            else -> trType
        }
        val sec = when {
            reality != null -> "reality"
            tls?.optBoolean("enabled") == true -> "tls"
            else -> "none"
        }
        val q = query(
            "type" to net, "security" to sec, "sni" to tls?.optString("server_name"),
            "fp" to tls?.optJSONObject("utls")?.optString("fingerprint"),
            "pbk" to reality?.optString("public_key"), "sid" to reality?.optString("short_id"),
            "path" to tr?.optString("path"), "host" to tr?.optJSONObject("headers")?.optString("Host"),
            "serviceName" to tr?.optString("service_name"), "flow" to o.optString("flow"),
        )
        return when (type) {
            "vless" -> "vless://${o.optString("uuid")}@$server:$port?encryption=none&$q#${enc(name)}"
            "trojan" -> "trojan://${enc(o.optString("password"))}@$server:$port?$q#${enc(name)}"
            "hysteria2" -> "hysteria2://${enc(o.optString("password"))}@$server:$port?" +
                query("sni" to tls?.optString("server_name"), "obfs" to o.optJSONObject("obfs")?.optString("type"), "obfs-password" to o.optJSONObject("obfs")?.optString("password")) + "#" + enc(name)
            "vmess" -> {
                val j = JSONObject().put("v", "2").put("ps", name).put("add", server).put("port", port.toString())
                    .put("id", o.optString("uuid")).put("aid", o.optInt("alter_id").toString()).put("scy", o.optString("security", "auto"))
                    .put("net", net).put("type", "none").put("host", tr?.optJSONObject("headers")?.optString("Host").orEmpty())
                    .put("path", tr?.optString("path").orEmpty()).put("tls", if (sec == "tls") "tls" else "")
                    .put("sni", tls?.optString("server_name").orEmpty())
                "vmess://" + Base64.encodeToString(j.toString().toByteArray(), Base64.NO_WRAP)
            }
            "shadowsocks" -> {
                val ui = Base64.encodeToString("${o.optString("method")}:${o.optString("password")}".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                "ss://$ui@$server:$port#" + enc(name)
            }
            else -> {
                val user = o.optString("username")
                val auth = if (user.isNotBlank()) enc(user) + ":" + enc(o.optString("password")) + "@" else ""
                "socks://$auth$server:$port#" + enc(name)
            }
        }
    }

    fun parse(raw: String, subId: String = ""): VpnProfile? {
        if (!isLink(raw)) return null
        val link = raw.trim().replace(Regex("^(?i)socks5h?://"), "socks://")
        val scheme = link.substringBefore("://").lowercase()
        val id = UUID.randomUUID().toString()
        return when (scheme) {
            "vmess" -> {
                val j = vmessJson(link) ?: return null
                val host = j.optString("add")
                val port = j.optString("port").toIntOrNull() ?: return null
                VpnProfile(id, j.optString("ps").ifBlank { "$host:$port" }, link, "VMess", host, port, subId)
            }
            "ss" -> {
                val ss = parseSs(link) ?: return null
                VpnProfile(id, ss.name.ifBlank { "${ss.host}:${ss.port}" }, link, "Shadowsocks", ss.host, ss.port, subId)
            }
            "hysteria2", "hy2", "wireguard", "wg", "socks" -> {
                val u = Uri.parse(link)
                val host = u.host ?: return null
                val port = u.port.takeIf { it > 0 } ?: if (scheme.startsWith("socks")) 1080 else 443
                val name = u.fragment?.let { decode(it) }?.ifBlank { null } ?: "$host:$port"
                val proto = when (scheme) {
                    "hysteria2", "hy2" -> "Hysteria2"
                    "wireguard", "wg" -> "WireGuard"
                    else -> "SOCKS5"
                }
                VpnProfile(id, name, link, proto, host, port, subId)
            }
            else -> {
                val u = Uri.parse(link)
                val host = u.host ?: return null
                val port = u.port.takeIf { it > 0 } ?: 443
                val name = u.fragment?.let { decode(it) }?.ifBlank { null } ?: "$host:$port"
                val proto = if (scheme == "vless") "VLESS" else "Trojan"
                val sec = u.getQueryParameter("security").orEmpty()
                VpnProfile(id, name, link, if (sec == "reality") "$proto Reality" else proto, host, port, subId)
            }
        }
    }

    // ---------- Конфигурация Xray ----------

    /** Исходящее соединение "proxy" для ссылки. */
    fun outbound(link: String): JSONObject {
        val scheme = link.substringBefore("://").lowercase()
        return when (scheme) {
            "vless", "trojan" -> uriOutbound(link, scheme)
            "vmess" -> vmessOutbound(link)
            "ss" -> ssOutbound(link)
            "hysteria2", "hy2" -> hysteriaOutbound(link)
            "wireguard", "wg" -> wireguardOutbound(link)
            "socks" -> socksOutbound(link)
            else -> throw IllegalArgumentException("unsupported link")
        }.put("tag", "proxy")
    }

    private val PRIVATE = JSONArray(
        listOf(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
            "100.64.0.0/10", "224.0.0.0/4", "fc00::/7", "fe80::/10", "::1/128",
        ),
    )

    /** Полная конфигурация: SOCKS 127.0.0.1:10808 + TUN (fd передаётся ядру отдельно). */
    fun fullConfig(link: String, withTun: Boolean, viaDpi: Boolean = false, ctx: android.content.Context? = null): String {
        val sniff = JSONObject().put("enabled", true).put("destOverride", JSONArray(listOf("http", "tls", "quic")))
        val inbounds = JSONArray()
        inbounds.put(
            JSONObject().put("tag", "socks").put("listen", "127.0.0.1").put("port", SOCKS_PORT).put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("userLevel", 8))
                .put("sniffing", sniff),
        )
        inbounds.put(
            JSONObject().put("tag", "http").put("listen", "127.0.0.1").put("port", HTTP_PORT).put("protocol", "http")
                .put("settings", JSONObject().put("userLevel", 8))
                .put("sniffing", sniff),
        )
        if (withTun) {
            inbounds.put(
                JSONObject().put("tag", "tun").put("protocol", "tun")
                    .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500).put("userLevel", 8))
                    .put("sniffing", sniff),
            )
        }
        val rules = JSONArray().put(JSONObject().put("type", "field").put("ip", PRIVATE).put("outboundTag", "direct"))
        // Раздача на другие устройства и «сайты напрямую».
        if (ctx != null) VpnExtras.apply(ctx, inbounds, rules, sniff)
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", JSONObject().put("servers", VpnTweaks.dnsServers()))
            .put("inbounds", inbounds)
            .put("outbounds", baseOutbounds(link, VpnTweaks.fragment, viaDpi))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            .put(
                "policy",
                JSONObject().put(
                    "levels",
                    JSONObject().put(
                        "8",
                        JSONObject().put("handshake", 4).put("connIdle", 300).put("uplinkOnly", 1).put("downlinkOnly", 1),
                    ),
                ),
            )
            .toString()
    }

    /**
     * Режимы без своего сервера: «Обход DPI» (через движок ByeDPI на 127.0.0.1) и
     * «Фрагментация» (ядро Xray само режет TLS-приветствие). Входы те же, что у сервера:
     * SOCKS для мессенджера, HTTP для браузера и TUN для всего телефона. DNS - только
     * через HTTPS (провайдер не подменит адреса), QUIC (UDP 443) закрыт - приложения сразу
     * переходят на TCP, который движок умеет «маскировать».
     */
    fun bypassConfig(mode: String, withTun: Boolean, ctx: android.content.Context? = null): String {
        val sniff = JSONObject().put("enabled", true).put("routeOnly", true)
            .put("destOverride", JSONArray(listOf("http", "tls")))
        val inbounds = JSONArray()
        inbounds.put(
            JSONObject().put("tag", "socks").put("listen", "127.0.0.1").put("port", SOCKS_PORT).put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("userLevel", 8))
                .put("sniffing", sniff),
        )
        inbounds.put(
            JSONObject().put("tag", "http").put("listen", "127.0.0.1").put("port", HTTP_PORT).put("protocol", "http")
                .put("settings", JSONObject().put("userLevel", 8))
                .put("sniffing", sniff),
        )
        if (withTun) {
            inbounds.put(
                JSONObject().put("tag", "tun").put("protocol", "tun")
                    .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500).put("userLevel", 8))
                    .put("sniffing", sniff),
            )
        }
        val proxy = if (mode == VpnStore.MODE_DPI) {
            JSONObject().put("tag", "proxy").put("protocol", "socks").put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", DpiEngine.PORT)),
                ),
            )
        } else {
            JSONObject().put("tag", "proxy").put("protocol", "freedom").put(
                "settings",
                JSONObject()
                    .put("domainStrategy", "UseIPv4")
                    .put("fragment", JSONObject().put("packets", "tlshello").put("length", "10-30").put("interval", "5-15"))
                    .put("noises", JSONArray().put(JSONObject().put("type", "rand").put("packet", "10-20").put("delay", "10-16"))),
            ).put("streamSettings", JSONObject().put("sockopt", JSONObject().put("tcpNoDelay", true)))
        }
        val outbounds = JSONArray()
            .put(proxy)
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
            .put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        val rules = JSONArray()
        if (withTun) rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("tun")).put("port", "53").put("outboundTag", "dns-out"))
        rules.put(JSONObject().put("type", "field").put("ip", PRIVATE).put("outboundTag", "direct"))
        rules.put(JSONObject().put("type", "field").put("network", "udp").put("port", "443").put("outboundTag", "block"))
        if (ctx != null) VpnExtras.apply(ctx, inbounds, rules, sniff)
        val dns = VpnTweaks.dnsServers().let { d ->
            // В обходе DPI обычный DNS провайдер может подменить - по умолчанию DNS через HTTPS.
            if (VpnTweaks.dns == "auto") JSONArray(VpnTweaks.DNS_PRESETS.getValue("cloudflare") + VpnTweaks.DNS_PRESETS.getValue("google")) else d
        }
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", JSONObject().put("servers", dns).put("queryStrategy", "UseIPv4"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            .put(
                "policy",
                JSONObject().put(
                    "levels",
                    JSONObject().put("8", JSONObject().put("handshake", 4).put("connIdle", 300).put("uplinkOnly", 1).put("downlinkOnly", 1)),
                ),
            )
            .toString()
    }

    /** Конфигурация для проверки «реального пинга» (только исходящие). */
    fun pingConfig(link: String): String = JSONObject()
        .put("log", JSONObject().put("loglevel", "none"))
        .put("outbounds", baseOutbounds(link))
        .toString()

    private fun baseOutbounds(link: String, fragment: Boolean = false, viaDpi: Boolean = false): JSONArray {
        val main = outbound(link)
        val arr = JSONArray()
        // Фрагментация TLS (против DPI): основной прокси подключается через исходящий «fragment».
        // Для Reality не нужна (там своя маскировка) - не включаем, чтобы не ломать рукопожатие.
        val security = main.optJSONObject("streamSettings")?.optString("security").orEmpty()
        val net = main.optJSONObject("streamSettings")?.optString("network").orEmpty()
        // Фрагментация - только для TCP+TLS (Hysteria2 работает по UDP/QUIC - ей она не нужна).
        val useFragment = fragment && security == "tls" && net != "hysteria" && main.optString("protocol") != "hysteria"
        if (useFragment) {
            val stream = main.optJSONObject("streamSettings") ?: JSONObject().also { main.put("streamSettings", it) }
            val sock = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
            sock.put("dialerProxy", "fragment")
        }
        // Совместный режим «Сервер + обход DPI»: соединение с сервером идёт через движок ByeDPI
        // (локальный SOCKS). Только TCP - UDP-протоколы (Hysteria2, WireGuard) идут как обычно.
        val proto = main.optString("protocol")
        val useDpi = viaDpi && net != "hysteria" && proto != "hysteria" && proto != "wireguard"
        val frag = if (useFragment) VpnTweaks.fragmentOutbound() else null
        if (useDpi) {
            // Цепочка: сервер -> (фрагменты) -> обход DPI.
            val target = frag ?: main
            if (frag == null) {
                val stream = main.optJSONObject("streamSettings") ?: JSONObject().also { main.put("streamSettings", it) }
                val sock = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
                sock.put("dialerProxy", "dpi-out")
            } else {
                val stream = target.optJSONObject("streamSettings") ?: JSONObject().also { target.put("streamSettings", it) }
                val sock = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
                sock.put("dialerProxy", "dpi-out")
            }
        }
        arr.put(main)
        if (frag != null) arr.put(frag)
        if (useDpi) {
            arr.put(
                JSONObject().put("tag", "dpi-out").put("protocol", "socks").put(
                    "settings",
                    JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", DpiEngine.PORT))),
                ),
            )
        }
        arr.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        arr.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        return arr
    }

    private fun uriOutbound(link: String, scheme: String): JSONObject {
        val u = Uri.parse(link)
        val host = u.host ?: throw IllegalArgumentException("no host")
        val port = u.port.takeIf { it > 0 } ?: 443
        val user = decode(u.encodedUserInfo ?: throw IllegalArgumentException("no id"))
        val q = { k: String -> u.getQueryParameter(k)?.takeIf { it.isNotBlank() } }
        val flow = q("flow")
        val settings = if (scheme == "vless") {
            val usr = JSONObject().put("id", user).put("encryption", q("encryption") ?: "none").put("level", 8)
            if (flow != null) usr.put("flow", flow)
            JSONObject().put(
                "vnext",
                JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(usr))),
            )
        } else {
            val srv = JSONObject().put("address", host).put("port", port).put("password", user).put("level", 8)
            if (flow != null) srv.put("flow", flow)
            JSONObject().put("servers", JSONArray().put(srv))
        }
        val security = q("security") ?: if (scheme == "trojan") "tls" else "none"
        val stream = stream(
            network = q("type") ?: "tcp",
            security = security,
            sni = q("sni") ?: q("peer"),
            fp = q("fp"),
            alpn = q("alpn"),
            insecure = q("allowInsecure") == "1" || q("insecure") == "1",
            host = q("host"),
            path = q("path"),
            headerType = q("headerType"),
            serviceName = q("serviceName"),
            mode = q("mode"),
            authority = q("authority"),
            seed = q("seed"),
            pbk = q("pbk"),
            sid = q("sid"),
            spx = q("spx"),
            extra = q("extra"),
            defaultSni = host,
        )
        return JSONObject().put("protocol", scheme).put("settings", settings).put("streamSettings", stream)
    }

    private fun vmessJson(link: String): JSONObject? {
        val raw = link.substringAfter("://").substringBefore("#")
        return runCatching { JSONObject(decodeBase64(raw)!!) }.getOrNull()
    }

    private fun vmessOutbound(link: String): JSONObject {
        val j = vmessJson(link) ?: throw IllegalArgumentException("bad vmess")
        val host = j.optString("add")
        val port = j.optString("port").toInt()
        val usr = JSONObject().put("id", j.optString("id")).put("alterId", j.optString("aid").toIntOrNull() ?: 0)
            .put("security", j.optString("scy").ifBlank { "auto" }).put("level", 8)
        val settings = JSONObject().put(
            "vnext",
            JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(usr))),
        )
        val s = { k: String -> j.optString(k).takeIf { it.isNotBlank() } }
        val stream = stream(
            network = s("net") ?: "tcp",
            security = if (s("tls") == "tls") "tls" else "none",
            sni = s("sni"),
            fp = s("fp"),
            alpn = s("alpn"),
            insecure = false,
            host = s("host"),
            path = s("path"),
            headerType = s("type"),
            serviceName = s("path"),
            mode = s("mode"),
            authority = null,
            seed = s("path"),
            pbk = null,
            sid = null,
            spx = null,
            extra = null,
            defaultSni = s("host") ?: host,
        )
        return JSONObject().put("protocol", "vmess").put("settings", settings).put("streamSettings", stream)
    }

    private class Ss(val method: String, val password: String, val host: String, val port: Int, val name: String)

    private fun parseSs(link: String): Ss? {
        var rest = link.substringAfter("://")
        val name = rest.substringAfter("#", "").let { decode(it) }
        rest = rest.substringBefore("#").substringBefore("?").trimEnd('/')
        return if (rest.contains("@")) {
            // SIP002: ss://base64(method:password)@host:port или ss://method:password@host:port
            val userInfo = rest.substringBeforeLast("@")
            val hostPort = rest.substringAfterLast("@")
            val cred = decodeBase64(userInfo)?.takeIf { it.contains(":") } ?: decode(userInfo)
            val (host, port) = splitHostPort(hostPort) ?: return null
            Ss(cred.substringBefore(":"), cred.substringAfter(":"), host, port, name)
        } else {
            // Старый формат: ss://base64(method:password@host:port)
            val d = decodeBase64(rest) ?: return null
            val cred = d.substringBeforeLast("@")
            val (host, port) = splitHostPort(d.substringAfterLast("@")) ?: return null
            Ss(cred.substringBefore(":"), cred.substringAfter(":"), host, port, name)
        }
    }

    private fun splitHostPort(s: String): Pair<String, Int>? {
        val port = s.substringAfterLast(":").toIntOrNull() ?: return null
        val host = s.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        return host to port
    }

    private fun ssOutbound(link: String): JSONObject {
        val ss = parseSs(link) ?: throw IllegalArgumentException("bad ss")
        val srv = JSONObject().put("address", ss.host).put("port", ss.port).put("method", ss.method)
            .put("password", ss.password).put("level", 8)
        return JSONObject().put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(srv)))
            .put("streamSettings", JSONObject().put("network", "tcp"))
    }

    private fun stream(
        network: String,
        security: String,
        sni: String?,
        fp: String?,
        alpn: String?,
        insecure: Boolean,
        host: String?,
        path: String?,
        headerType: String?,
        serviceName: String?,
        mode: String?,
        authority: String?,
        seed: String?,
        pbk: String?,
        sid: String?,
        spx: String?,
        extra: String?,
        defaultSni: String,
    ): JSONObject {
        val net = when (network.lowercase()) {
            "raw" -> "tcp"
            "splithttp" -> "xhttp"
            "http" -> "h2"
            else -> network.lowercase()
        }
        val st = JSONObject().put("network", net)
        when (net) {
            "ws" -> st.put("wsSettings", JSONObject().put("path", path ?: "/").apply { host?.let { put("host", it) } })
            "grpc" -> st.put(
                "grpcSettings",
                JSONObject().put("serviceName", serviceName ?: "").put("multiMode", mode == "multi")
                    .apply { authority?.let { put("authority", it) } },
            )
            "xhttp" -> st.put(
                "xhttpSettings",
                JSONObject().put("path", path ?: "/").put("mode", mode ?: "auto").apply {
                    host?.let { put("host", it) }
                    extra?.let { e -> runCatching { put("extra", JSONObject(e)) } }
                },
            )
            "httpupgrade" -> st.put(
                "httpupgradeSettings",
                JSONObject().put("path", path ?: "/").apply { host?.let { put("host", it) } },
            )
            "h2" -> st.put(
                "httpSettings",
                JSONObject().put("path", path ?: "/").apply { host?.let { put("host", JSONArray(it.split(","))) } },
            )
            "kcp" -> st.put(
                "kcpSettings",
                JSONObject().put("header", JSONObject().put("type", headerType ?: "none")).apply { seed?.let { put("seed", it) } },
            )
            "tcp" -> if (headerType == "http") {
                val req = JSONObject().put("path", JSONArray(listOf(path ?: "/")))
                host?.let { req.put("headers", JSONObject().put("Host", JSONArray(it.split(",")))) }
                st.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", req)))
            }
        }
        when (security) {
            "tls" -> st.put("security", "tls").put(
                "tlsSettings",
                JSONObject().put("serverName", sni ?: defaultSni).put("allowInsecure", insecure).apply {
                    put("fingerprint", fp ?: "chrome")
                    alpn?.let { put("alpn", JSONArray(it.split(","))) }
                },
            )
            "reality" -> st.put("security", "reality").put(
                "realitySettings",
                JSONObject().put("serverName", sni ?: defaultSni).put("fingerprint", fp ?: "chrome")
                    .put("publicKey", pbk ?: "").put("shortId", sid ?: "").put("spiderX", spx ?: ""),
            )
            else -> st.put("security", "none")
        }
        return st
    }

    // ---------- Вспомогательные ----------

    /**
     * Hysteria2 (hy2://пароль@хост:порт?sni=…&insecure=1#имя) - быстрый протокол поверх QUIC/UDP.
     * Поддерживается ядром Xray новых версий (протокол «hysteria», версия 2).
     */
    private fun hysteriaOutbound(link: String): JSONObject {
        val u = Uri.parse(link)
        val host = u.host ?: throw IllegalArgumentException("no host")
        val port = u.port.takeIf { it > 0 } ?: 443
        val auth = decode(u.encodedUserInfo.orEmpty())
        val q = { k: String -> u.getQueryParameter(k)?.takeIf { it.isNotBlank() } }
        val tls = JSONObject()
            .put("serverName", q("sni") ?: q("peer") ?: host)
            .put("alpn", JSONArray(listOf("h3")))
            .put("allowInsecure", q("insecure") == "1" || q("allowInsecure") == "1")
        q("pinSHA256")?.let { tls.put("pinnedPeerCertificateChainSha256", JSONArray(listOf(it))) }
        val hy = JSONObject().put("version", 2).put("auth", auth)
        q("up")?.let { hy.put("up", it) }
        q("down")?.let { hy.put("down", it) }
        return JSONObject()
            .put("protocol", "hysteria")
            .put("settings", JSONObject().put("version", 2).put("address", host).put("port", port))
            .put(
                "streamSettings",
                JSONObject().put("network", "hysteria").put("hysteriaSettings", hy)
                    .put("security", "tls").put("tlsSettings", tls),
            )
    }

    /** WireGuard (wireguard://приватный_ключ@хост:порт?publickey=…&address=…&mtu=…&reserved=…). */
    private fun wireguardOutbound(link: String): JSONObject {
        val u = Uri.parse(link)
        val host = u.host ?: throw IllegalArgumentException("no host")
        val port = u.port.takeIf { it > 0 } ?: 51820
        val q = { k: String -> u.getQueryParameter(k)?.takeIf { it.isNotBlank() } }
        val secret = decode(u.encodedUserInfo ?: q("privatekey") ?: throw IllegalArgumentException("no key"))
        val peer = JSONObject()
            .put("publicKey", q("publickey") ?: q("publicKey") ?: throw IllegalArgumentException("no public key"))
            .put("endpoint", "$host:$port")
        q("presharedkey")?.let { peer.put("preSharedKey", it) }
        val settings = JSONObject()
            .put("secretKey", secret)
            .put("address", JSONArray((q("address") ?: q("ip") ?: "10.0.0.2/32").split(',').map { it.trim() }))
            .put("peers", JSONArray().put(peer))
            .put("mtu", q("mtu")?.toIntOrNull() ?: 1420)
        q("reserved")?.let { r ->
            val parts = r.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (parts.size == 3) settings.put("reserved", JSONArray(parts))
        }
        return JSONObject().put("protocol", "wireguard").put("settings", settings)
    }

    /** SOCKS5 (socks://логин:пароль@хост:порт, логин:пароль может быть в base64). */
    private fun socksOutbound(link: String): JSONObject {
        val u = Uri.parse(link)
        val host = u.host ?: throw IllegalArgumentException("no host")
        val port = u.port.takeIf { it > 0 } ?: 1080
        val server = JSONObject().put("address", host).put("port", port)
        u.encodedUserInfo?.takeIf { it.isNotBlank() }?.let { info ->
            val plain = decode(info).let { if (it.contains(':')) it else decodeBase64(it) ?: it }
            val user = plain.substringBefore(':')
            val pass = plain.substringAfter(':', "")
            server.put("users", JSONArray().put(JSONObject().put("user", user).put("pass", pass)))
        }
        return JSONObject().put("protocol", "socks").put("settings", JSONObject().put("servers", JSONArray().put(server)))
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    fun decodeBase64(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            val r = runCatching {
                val padded = t + "=".repeat((4 - t.length % 4) % 4)
                String(Base64.decode(padded, flags), Charsets.UTF_8)
            }.getOrNull()
            if (r != null && r.isNotBlank() && r.none { it == '�' }) return r
        }
        return null
    }
}
