// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import android.util.Base64
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import org.json.JSONObject
import kotlin.random.Random

/**
 * Бесплатный VPN Cloudflare WARP через AmneziaWG: обычный WireGuard у многих операторов
 * блокируется, AmneziaWG добавляет «мусорные» пакеты и подменяет заголовки - DPI его не
 * распознаёт. Ключи выдаёт генератор (без регистрации и аккаунта), конфигурацию можно и
 * импортировать своим .conf (AmneziaWG/WireGuard).
 */
object Warp {
    private const val TAG = "Warp"

    private val GENERATORS = listOf(
        "https://www.warp-generator.workers.dev/",
        "https://warp.sub-aggregator.workers.dev/",
        "https://warp-gen.netlify.app/",
        "https://warp-vercel-chi.vercel.app/api/warp-data",
        "https://warp-vercel-murex.vercel.app/api/warp-data",
    )

    private val PORTS = listOf(
        500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 942, 943, 945, 946, 955, 968, 987,
        988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387, 1701, 1843, 2371, 2408, 2506, 3138, 3476, 3581,
        3854, 4177, 4198, 4233, 4500, 5279, 5956, 7103, 7152, 7156, 7281, 7559, 8319, 8742, 8854, 8886,
    )

    // Диапазоны Cloudflare, на которых WARP отвечает (дополнены по проверенным конфигам Nova).
    private val PREFIXES = listOf(
        "162.159.192.", "162.159.195.", "188.114.96.", "188.114.97.", "188.114.98.", "8.6.112.", "8.34.70.", "8.47.69.",
        "8.34.146.", "8.35.211.", "8.39.125.", "8.39.204.", "8.39.214.",
    )

    /** Пакет-«маска» перед рукопожатием (AmneziaWG 1.5): выглядит как начало QUIC. */
    private const val I1 = "<b 0xce000000010897a297ecc34cd6dd000044d0ec2e2e1ea2991f467ace4222129b5a098823784694b4897b9986ae0b7280135fa85e196d9ad980b150122129ce2a9379531b0fd3e871ca5fdb883c369832f730e272d7b8b74f393f9f0fa43f11e510ecb2219a52984410c204cf875585340c62238e14ad04dff382f2c200e0ee22fe743b9c6b8b043121c5710ec289f471c91ee414fca8b8be8419ae8ce7ffc53837f6ade262891895f3f4cecd31bc93ac5599e18e4f01b472362b8056c3172b513051f8322d1062997ef4a383b01706598d08d48c221d30e74c7ce000cdad36b706b1bf9b0607c32ec4b3203a4ee21ab64df336212b9758280803fcab14933b0e7ee1e04a7becce3e2633f4852585c567894a5f9efe9706a151b615856647e8b7dba69ab357b3982f554549bef9256111b2d67afde0b496f16962d4957ff654232aa9e845b61463908309cfd9de0a6abf5f425f577d7e5f6440652aa8da5f73588e82e9470f3b21b27b28c649506ae1a7f5f15b876f56abc4615f49911549b9bb39dd804fde182bd2dcec0c33bad9b138ca07d4a4a1650a2c2686acea05727e2a78962a840ae428f55627516e73c83dd8893b02358e81b524b4d99fda6df52b3a8d7a5291326e7ac9d773c5b43b8444554ef5aea104a738ed650aa979674bbed38da58ac29d87c29d387d80b526065baeb073ce65f075ccb56e47533aef357dceaa8293a523c5f6f790be90e4731123d3c6152a70576e90b4ab5bc5ead01576c68ab633ff7d36dcde2a0b2c68897e1acfc4d6483aaaeb635dd63c96b2b6a7a2bfe042f6aed82e5363aa850aace12ee3b1a93f30d8ab9537df483152a5527faca21efc9981b304f11fc95336f5b9637b174c5a0659e2b22e159a9fed4b8e93047371175b1d6d9cc8ab745f3b2281537d1c75fb9451871864efa5d184c38c185fd203de206751b92620f7c369e031d2041e152040920ac2c5ab5340bfc9d0561176abf10a147287ea90758575ac6a9f5ac9f390d0d5b23ee12af583383d994e22c0cf42383834bcd3ada1b3825a0664d8f3fb678261d57601ddf94a8a68a7c273a18c08aa99c7ad8c6c42eab67718843597ec9930457359dfdfbce024afc2dcf9348579a57d8d3490b2fa99f278f1c37d87dad9b221acd575192ffae1784f8e60ec7cee4068b6b988f0433d96d6a1b1865f4e155e9fe020279f434f3bf1bd117b717b92f6cd1cc9bea7d45978bcc3f24bda631a36910110a6ec06da35f8966c9279d130347594f13e9e07514fa370754d1424c0a1545c5070ef9fb2acd14233e8a50bfc5978b5bdf8bc1714731f798d21e2004117c61f2989dd44f0cf027b27d4019e81ed4b5c31db347c4a3a4d85048d7093cf16753d7b0d15e078f5c7a5205dc2f87e330a1f716738dce1c6180e9d02869b5546f1c4d2748f8c90d9693cba4e0079297d22fd61402dea32ff0eb69ebd65a5d0b687d87e3a8b2c42b648aa723c7c7daf37abcc4bb85caea2ee8f55bec20e913b3324ab8f5c3304f820d42ad1b9f2ffc1a3af9927136b4419e1e579ab4c2ae3c776d293d397d575df181e6cae0a4ada5d67ecea171cca3288d57c7bbdaee3befe745fb7d634f70386d873b90c4d6c6596bb65af68f9e5121e67ebf0d89d3c909ceedfb32ce9575a7758ff080724e1ab5d5f43074ecb53a479af21ed03d7b6899c36631c0166f9d47e5e1d4528a5d3d3f744029c4b1c190cbfbad06f5f83f7ad0429fa9a2719c56ffe3783460e166de2d8>"

    fun randomEndpoint(): String = PREFIXES.random() + Random.nextInt(1, 11) + ":" + PORTS.random()

    /** Готовая конфигурация AmneziaWG для WARP (маскировка: Jc/Jmin/Jmax, H1-H4). */
    fun config(privKey: String, peerPub: String, v4: String, v6: String, endpoint: String) = """
        [Interface]
        PrivateKey = $privKey
        Address = $v4/32, $v6/128
        DNS = 1.1.1.1, 1.0.0.1
        MTU = 1280
        S1 = 0
        S2 = 0
        Jc = 4
        Jmin = 40
        Jmax = 70
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4
        I1 = $I1

        [Peer]
        PublicKey = $peerPub
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = $endpoint
        PersistentKeepalive = 25
    """.trimIndent()

    /** Получить новую конфигурацию (в фоне!). Через VPN, если он включён. */
    fun generate(): String {
        var last: Exception? = null
        for (u in GENERATORS) {
            try {
                val c = URL(u).openConnection(VpnState.httpProxy()) as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "WARP-Generator/2.0")
                c.setRequestProperty("X-Client", "WARP")
                try {
                    if (c.responseCode !in 200..299) continue
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val key = j.optString("privKey")
                    if (key.isBlank()) continue
                    return config(
                        key,
                        j.optString("peer_pub", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="),
                        j.optString("client_ipv4", "172.16.0.2").substringBefore('/'),
                        j.optString("client_ipv6", "2606:4700:110:8700::2").substringBefore('/'),
                        randomEndpoint(),
                    )
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "$u: $e")
                last = e
            }
        }
        throw IllegalStateException(last?.message ?: "генераторы WARP недоступны")
    }

    /** Другой адрес/порт Cloudflare (если текущий не отвечает у вашего оператора). */
    fun withNewEndpoint(conf: String): String =
        conf.lines().joinToString("\n") { if (it.trim().lowercase().startsWith("endpoint")) "Endpoint = " + randomEndpoint() else it }

    fun looksLikeConf(text: String) = text.contains("[Interface]", true) && text.contains("PrivateKey", true)

    // ---------- Разбор .conf и формат UAPI для libwg-go ----------

    class Parsed(
        val privateKeyHex: String,
        val addresses: List<String>,
        val dns: List<String>,
        val mtu: Int,
        val iface: List<Pair<String, String>>,
        val peerKeyHex: String?,
        val pskHex: String?,
        val endpoint: String?,
        val keepalive: Int?,
        val allowed: List<String>,
    )

    private fun b64hex(s: String) = Base64.decode(s.trim(), Base64.DEFAULT).joinToString("") { "%02x".format(it) }

    private val OBF = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")

    fun parse(conf: String): Parsed {
        var section = ""
        var priv = ""
        val addr = ArrayList<String>()
        val dns = ArrayList<String>()
        var mtu = 1280
        val obf = ArrayList<Pair<String, String>>()
        var peer: String? = null
        var psk: String? = null
        var ep: String? = null
        var ka: Int? = null
        val allowed = ArrayList<String>()
        for (raw in conf.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                section = line.trim('[', ']').lowercase()
                continue
            }
            val k = line.substringBefore('=').trim().lowercase()
            val v = line.substringAfter('=', "").trim()
            if (section == "interface") {
                when (k) {
                    "privatekey" -> priv = b64hex(v)
                    "address" -> addr += v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "dns" -> dns += v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "mtu" -> mtu = v.toIntOrNull() ?: 1280
                    in OBF -> obf += k to v
                }
            } else if (section == "peer") {
                when (k) {
                    "publickey" -> peer = b64hex(v)
                    "presharedkey" -> psk = b64hex(v)
                    "endpoint" -> ep = v
                    "persistentkeepalive" -> ka = v.toIntOrNull()
                    "allowedips" -> allowed += v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
        }
        if (priv.isEmpty()) throw IllegalArgumentException("в конфигурации нет PrivateKey")
        return Parsed(priv, addr, dns, mtu, obf, peer, psk, ep, ka, allowed)
    }

    /** Адрес сервера - заранее в IP (внутри туннеля DNS ещё нет). */
    fun resolveEndpoint(ep: String?): String? {
        if (ep == null) return null
        val host = ep.substringBeforeLast(':').trim('[', ']')
        val port = ep.substringAfterLast(':')
        if (host.all { it.isDigit() || it == '.' }) return ep
        val ip = runCatching { InetAddress.getAllByName(host).firstOrNull { it is java.net.Inet4Address }?.hostAddress }.getOrNull()
            ?: "162.159.192.1"
        return "$ip:$port"
    }

    /**
     * [level] - совместимость с ядром: 0 - все параметры маскировки, 1 - без S3/S4 и I2…I5
     * (старый AmneziaWG 1.5), 2 - только Jc/Jmin/Jmax/S1/S2/H1-H4 (AmneziaWG 1.0).
     */
    fun uapi(p: Parsed, endpoint: String?, level: Int = 0): String = buildString {
        append("private_key=").append(p.privateKeyHex).append('\n')
        val skip = when (level) {
            0 -> emptySet()
            1 -> setOf("s3", "s4", "i2", "i3", "i4", "i5")
            else -> setOf("s3", "s4", "i1", "i2", "i3", "i4", "i5")
        }
        p.iface.forEach { (k, v) -> if (k !in skip) append(k).append('=').append(v).append('\n') }
        if (!p.peerKeyHex.isNullOrEmpty()) {
            append("replace_peers=true\n")
            append("public_key=").append(p.peerKeyHex).append('\n')
            p.pskHex?.let { append("preshared_key=").append(it).append('\n') }
            endpoint?.let { append("endpoint=").append(it).append('\n') }
            append("persistent_keepalive_interval=").append(p.keepalive ?: 25).append('\n')
            (p.allowed.ifEmpty { listOf("0.0.0.0/0", "::/0") }).forEach { append("allowed_ip=").append(it).append('\n') }
        }
    }
}

/**
 * Готовые конфигурации WARP (AmneziaWG) из проекта Nova (GPL-3.0): 50 проверенных ключей и
 * адресов Cloudflare с маскировкой (I1/I2, Jc/Jmin/Jmax). Работают сразу, без генераторов
 * и регистрации - если генераторы недоступны или заблокированы. Перебираются по кругу:
 * не подключился один - сторож VPN берёт следующий.
 */
object WarpSeeds {
    @Volatile private var cache: List<String>? = null
    @Volatile private var names: Map<String, String>? = null
    private const val USER = "warp_user_confs"

    /** Встроенные конфиги из приложения (assets/warp_seeds.json). */
    private fun builtin(ctx: Context): List<String> = cache ?: runCatching {
        val a = org.json.JSONArray(ctx.assets.open("warp_seeds.json").bufferedReader().use { it.readText() })
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("conf")?.takeIf { c -> Warp.looksLikeConf(c) } }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) cache = it }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    /** Свои конфиги (из YAML Clash/Mihomo, файлов .conf): имя → текст. Идут первыми в списке. */
    fun user(ctx: Context): List<Pair<String, String>> = runCatching {
        val a = org.json.JSONArray(prefs(ctx).getString(USER, "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("name") to it.optString("conf") }
            .filter { Warp.looksLikeConf(it.second) }
    }.getOrDefault(emptyList())

    /** Добавить свои конфиги (без повторов). Возвращает, сколько новых. */
    fun addUser(ctx: Context, list: List<Pair<String, String>>): Int {
        val cur = user(ctx).toMutableList()
        val have = HashSet<String>()
        cur.forEach { have += it.second.trim() }
        builtin(ctx).forEach { have += it.trim() }
        var n = 0
        list.forEach { (nm, conf) ->
            if (Warp.looksLikeConf(conf) && have.add(conf.trim())) {
                cur += nm.ifBlank { "WARP" } to conf
                n++
            }
        }
        val a = org.json.JSONArray()
        cur.takeLast(200).forEach { (nm, c) -> a.put(org.json.JSONObject().put("name", nm).put("conf", c)) }
        prefs(ctx).edit().putString(USER, a.toString()).apply()
        return n
    }

    fun removeUser(ctx: Context, conf: String) {
        val a = org.json.JSONArray()
        user(ctx).filter { it.second != conf }.forEach { (nm, c) -> a.put(org.json.JSONObject().put("name", nm).put("conf", c)) }
        prefs(ctx).edit().putString(USER, a.toString()).apply()
    }

    fun all(ctx: Context): List<String> = user(ctx).map { it.second } + builtin(ctx)

    fun count(ctx: Context) = all(ctx).size

    fun isUser(ctx: Context, conf: String) = user(ctx).any { it.second == conf }

    /** Имя готового конфига (FIX, AMFITON, MIFA…, свои), если оно задано. */
    fun name(ctx: Context, conf: String): String? {
        user(ctx).firstOrNull { it.second == conf }?.let { return "📄 " + it.first }
        val m = names ?: runCatching {
            val a = org.json.JSONArray(ctx.assets.open("warp_seeds.json").bufferedReader().use { it.readText() })
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                .filter { it.optString("name").isNotBlank() }
                .associate { it.optString("conf") to it.optString("name") }
        }.getOrDefault(emptyMap()).also { names = it }
        return m[conf]
    }

    /** Следующий конфиг по кругу (null - список пуст). */
    fun next(ctx: Context): String? {
        val l = all(ctx)
        if (l.isEmpty()) return null
        val p = prefs(ctx)
        val i = (p.getInt("warp_seed_i", -1) + 1).mod(l.size)
        p.edit().putInt("warp_seed_i", i).apply()
        return l[i]
    }

    /** Номер текущего готового конфига (1…N) или 0, если стоит свой. */
    fun currentIndex(ctx: Context, conf: String): Int = all(ctx).indexOf(conf) + 1
}
