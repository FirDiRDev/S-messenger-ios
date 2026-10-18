// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
// SPDX-FileCopyrightText: 2026 Nova-Android contributors (поиск интерфейсов раздачи и правила зон, GPL-3.0)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import java.net.IDN
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private fun L(ru: String, en: String) = if (Locale.getDefault().language == "ru") ru else en

/**
 * Сайты мимо VPN: зоны (.ru, .su, .рф и другие кириллические) и свои домены идут напрямую,
 * остальное - через туннель. Работает в режимах ядра Xray (сервер, обход DPI, фрагментация):
 * имя сайта ядро узнаёт из TLS/HTTP (sniffing) и само выбирает путь.
 *
 * Зачем: Госуслуги, банки, маркетплейсы и стриминги с российским адресом через зарубежный
 * выход тормозят, требуют подтверждений или не открываются вовсе.
 */
object DomainBypass {
    private const val PREFS = "vpn_domains"

    /** Кириллические зоны в виде, в каком их видит ядро (punycode). */
    private val CYRILLIC = listOf(
        "xn--p1ai", // .рф
        "xn--p1acf", // .рус
        "xn--d1acj3b", // .дети
        "xn--80asehdb", // .онлайн
        "xn--80aswg", // .сайт
        "xn--c1avg", // .орг
        "xn--80adxhks", // .москва
    )

    /** Готовые наборы сайтов. */
    val PRESETS: List<Pair<String, List<String>>> = listOf(
        L("🏛 Госуслуги и налоги", "🏛 Gov services") to listOf("gosuslugi.ru", "nalog.gov.ru", "mos.ru", "pfr.gov.ru", "sfr.gov.ru", "fns.ru", "gov.ru", "esia.gosuslugi.ru"),
        L("🏦 Банки и оплата", "🏦 Banks & payments") to listOf("sberbank.ru", "online.sberbank.ru", "vtb.ru", "tbank.ru", "tinkoff.ru", "alfabank.ru", "gazprombank.ru", "raiffeisen.ru", "pochtabank.ru", "sovcombank.ru", "nspk.ru", "mironline.ru", "yoomoney.ru", "qiwi.com"),
        L("🛒 Маркетплейсы", "🛒 Marketplaces") to listOf("ozon.ru", "wildberries.ru", "wb.ru", "avito.ru", "market.yandex.ru", "megamarket.ru", "lamoda.ru", "dns-shop.ru"),
        L("📺 Видео и соцсети РФ", "📺 Russian video & social") to listOf("vk.com", "vk.ru", "vkvideo.ru", "userapi.com", "ok.ru", "mycdn.me", "rutube.ru", "kinopoisk.ru", "ivi.ru", "okko.tv", "wink.ru", "dzen.ru", "max.ru"),
        L("🔎 Яндекс", "🔎 Yandex") to listOf("yandex.ru", "yandex.net", "ya.ru", "yastatic.net", "yandex.com", "kinopoisk.ru", "music.yandex.ru"),
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", false)
    fun zones(ctx: Context): Set<String> = prefs(ctx).getStringSet("zones", setOf("ru", "su", "cyr")) ?: emptySet()
    fun custom(ctx: Context): String = prefs(ctx).getString("custom", "").orEmpty()

    /** Свои домены из текста: схема, путь, «www.» и пробелы отбрасываются. */
    fun parseDomains(raw: String): List<String> = raw.split(',', ' ', '\n', '\r', '\t', ';')
        .map { t ->
            var s = t.trim()
            val i = s.indexOf("://")
            if (i >= 0) s = s.substring(i + 3)
            s = s.substringBefore('/').substringBefore(':').lowercase().trim().trimStart('.').removePrefix("www.")
            runCatching { IDN.toASCII(s) }.getOrDefault(s)
        }
        .filter { it.contains('.') && it.length in 3..253 }
        .distinct()

    /** Правило Xray «эти сайты - напрямую» (null - выключено или пусто). */
    fun rule(ctx: Context): JSONObject? {
        if (!enabled(ctx)) return null
        val list = ArrayList<String>()
        val z = zones(ctx)
        if ("ru" in z) list += "domain:ru"
        if ("su" in z) list += "domain:su"
        if ("by" in z) list += "domain:by"
        if ("kz" in z) list += "domain:kz"
        if ("cyr" in z) list += CYRILLIC.map { "domain:$it" }
        parseDomains(custom(ctx)).forEach { list += "domain:$it" }
        if (list.isEmpty()) return null
        return JSONObject().put("type", "field").put("domain", JSONArray(list)).put("outboundTag", "direct")
    }

    fun summary(ctx: Context): String = if (!enabled(ctx)) L("выключено", "off") else {
        val z = zones(ctx).joinToString(" ") {
            when (it) {
                "cyr" -> ".рф"
                else -> ".$it"
            }
        }
        val n = parseDomains(custom(ctx)).size
        z + if (n > 0) L(" + $n сайтов", " + $n sites") else ""
    }

    fun show(ctx: Context) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(4))
        }
        val on = SwitchCompat(ctx).apply {
            text = L("Сайты мимо VPN", "Sites bypass the VPN")
            isChecked = enabled(ctx)
            textSize = 15f
        }
        box.addView(on)
        box.addView(TextView(ctx).apply {
            text = L(
                "Отмеченные зоны и сайты открываются напрямую, с вашего обычного адреса - Госуслуги, банки и маркетплейсы перестают просить подтверждения. Работает в режимах «Серверы», «Обход DPI» и «Фрагментация».",
                "Checked zones and sites open directly from your normal address - gov services, banks and marketplaces stop asking for confirmations. Works in the Servers, DPI bypass and Fragmentation modes.",
            )
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(2), 0, dp(8))
        })
        val z = zones(ctx).toMutableSet()
        fun zone(key: String, label: String) {
            box.addView(CheckBox(ctx).apply {
                text = label
                isChecked = key in z
                setOnCheckedChangeListener { _, c -> if (c) z += key else z -= key }
            })
        }
        zone("ru", L("Все сайты .ru", "All .ru sites"))
        zone("su", L("Все сайты .su", "All .su sites"))
        zone("cyr", L("Кириллические зоны (.рф, .рус, .онлайн, .москва…)", "Cyrillic zones (.рф, .рус, .онлайн…)"))
        zone("by", L("Беларусь (.by)", "Belarus (.by)"))
        zone("kz", L("Казахстан (.kz)", "Kazakhstan (.kz)"))
        val field = EditText(ctx).apply {
            hint = L("Свои сайты: через пробел или с новой строки", "Your sites: space or newline separated")
            setText(custom(ctx))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            minLines = 3
            maxLines = 8
            textSize = 13f
        }
        val presets = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(ctx).apply {
            text = L("Быстро добавить:", "Quick add:")
            textSize = 12f
            setPadding(0, dp(8), 0, dp(4))
        })
        for ((title, list) in PRESETS) {
            presets.addView(TextView(ctx).apply {
                text = "＋ $title"
                textSize = 13f
                setTextColor(0xFF5B4BDB.toInt())
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnClickListener {
                    val cur = parseDomains(field.text.toString()).toMutableList()
                    list.forEach { if (it !in cur) cur += it }
                    field.setText(cur.joinToString("\n"))
                    on.isChecked = true
                }
            })
        }
        box.addView(presets)
        box.addView(field)
        AlertDialog.Builder(ctx)
            .setTitle(L("🌐 Сайты напрямую", "🌐 Direct sites"))
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(com.stoyko.smessenger.R.string.save) { _, _ ->
                prefs(ctx).edit()
                    .putBoolean("on", on.isChecked)
                    .putStringSet("zones", HashSet(z))
                    .putString("custom", parseDomains(field.text.toString()).joinToString("\n"))
                    .apply()
                VpnExtras.reconnectIfXray(ctx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/**
 * Раздача VPN на другие устройства (ноутбук, телевизор, приставка, другой телефон) - без root.
 * Телефон работает шлюзом: SOCKS5 и HTTP-прокси на своём адресе в точке доступа, USB-модеме,
 * Bluetooth или общей сети Wi-Fi. Соединения прокси - это соединения самого ядра, поэтому они
 * идут через VPN. Вход только по логину и паролю (в чужой Wi-Fi сети шлюз без пароля - дыра).
 *
 * Прозрачно завернуть трафик клиентов точки доступа в VPN без root нельзя: Android направляет
 * в туннель только трафик, порождённый на самом телефоне (подробно - в проекте Nova).
 * Через прокси идёт TCP; UDP (игры, звонки) - мимо.
 */
object Gateway {
    private const val PREFS = "vpn_gateway"
    const val SOCKS_PORT = 10810
    const val HTTP_PORT = 10811

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", false)

    fun user(ctx: Context): String = prefs(ctx).getString("user", null) ?: "sm".also { prefs(ctx).edit().putString("user", it).apply() }

    fun pass(ctx: Context): String = prefs(ctx).getString("pass", null) ?: newPass().also { prefs(ctx).edit().putString("pass", it).apply() }

    private fun newPass(): String {
        val abc = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = java.security.SecureRandom()
        return (1..10).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    /** Входы Xray для шлюза (добавляются к конфигурации, пока раздача включена). */
    fun inbounds(ctx: Context, sniff: JSONObject): List<JSONObject> {
        if (!enabled(ctx)) return emptyList()
        val acc = JSONArray().put(JSONObject().put("user", user(ctx)).put("pass", pass(ctx)))
        return listOf(
            JSONObject().put("tag", "gw-socks").put("listen", "0.0.0.0").put("port", SOCKS_PORT).put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "password").put("accounts", acc).put("udp", false).put("userLevel", 8))
                .put("sniffing", sniff),
            JSONObject().put("tag", "gw-http").put("listen", "0.0.0.0").put("port", HTTP_PORT).put("protocol", "http")
                .put("settings", JSONObject().put("accounts", JSONArray().put(JSONObject().put("user", user(ctx)).put("pass", pass(ctx)))).put("userLevel", 8))
                .put("sniffing", sniff),
        )
    }

    // ---------------- адреса, на которых ждём клиентов ----------------

    enum class Kind(val ru: String, val en: String, val downstream: Boolean) {
        WIFI_AP("Точка доступа Wi-Fi", "Wi-Fi hotspot", true),
        USB("USB-модем", "USB tethering", true),
        BLUETOOTH("Bluetooth-модем", "Bluetooth tethering", true),
        WIFI_DIRECT("Wi-Fi Direct", "Wi-Fi Direct", true),
        ETHERNET("Раздача по кабелю", "Ethernet tethering", true),
        OTHER("Раздача", "Tethering", true),
        LAN("Общая сеть Wi-Fi", "Shared Wi-Fi network", false),
        ;

        val title get() = L(ru, en)
    }

    class Endpoint(val host: String, val iface: String, val kind: Kind)

    private val NEVER = listOf(
        "lo", "dummy", "sit", "ip6tnl", "ip_vti", "ip6_vti", "tun", "tap", "ppp", "v4-", "clat", "rmnet", "ccmni",
        "wwan", "seth", "epdg", "ims", "aware_data", "nan", "ifb", "gre",
    )

    /**
     * Интерфейсы раздачи: все интерфейсы ядра минус сети с выходом в интернет (сотовая, VPN).
     * Признак раздачи - отсутствие NET_CAPABILITY_INTERNET (USB-модем система видит как сеть,
     * но без выхода). 464XLAT (192.0.0.0/29, clat) отсекается.
     */
    fun endpoints(ctx: Context): List<Endpoint> {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lan = HashSet<String>()
        val up = HashSet<String>()
        runCatching {
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val name = runCatching { cm.getLinkProperties(n)?.interfaceName }.getOrNull()?.trim().orEmpty()
                if (name.isEmpty()) continue
                val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) lan += name else up += name
            }
        }
        val out = ArrayList<Endpoint>()
        val ifs = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (i in ifs) {
            val name = runCatching { i.name }.getOrNull()?.trim().orEmpty()
            if (name.isEmpty() || NEVER.any { name.lowercase(Locale.US).startsWith(it) }) continue
            if (!runCatching { i.isUp && !i.isLoopback }.getOrDefault(false)) continue
            val host = runCatching { i.inetAddresses.toList() }.getOrDefault(emptyList()).filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !isClat(it) }
                ?.hostAddress ?: continue
            if (name in up) continue
            out += Endpoint(host, name, if (name in lan) Kind.LAN else classify(name))
        }
        return out.sortedWith(compareBy({ if (it.kind.downstream) 0 else 1 }, { it.iface }))
    }

    private fun isClat(a: Inet4Address): Boolean {
        val b = a.address
        return b.size == 4 && (b[0].toInt() and 0xFF) == 192 && b[1].toInt() == 0 && b[2].toInt() == 0 && (b[3].toInt() and 0xFF) < 8
    }

    private fun classify(name: String): Kind {
        val n = name.lowercase(Locale.US)
        return when {
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> Kind.USB
            n.startsWith("bt-pan") || n.startsWith("bnep") -> Kind.BLUETOOTH
            n.startsWith("p2p") -> Kind.WIFI_DIRECT
            n.startsWith("ap") || n.startsWith("softap") || n.startsWith("swlan") || n.startsWith("wlan") || n.startsWith("wigig") -> Kind.WIFI_AP
            n.startsWith("eth") -> Kind.ETHERNET
            else -> Kind.OTHER
        }
    }

    private fun qr(text: String, size: Int): Bitmap? = runCatching {
        val q = io.nayuki.qrcodegen.QrCode.encodeText(text, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM)
        val quiet = 2
        val cells = q.size + quiet * 2
        val scale = (size / cells).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(cells * scale, cells * scale, Bitmap.Config.RGB_565)
        val px = IntArray(bmp.width * bmp.height) { Color.WHITE }
        for (y in 0 until q.size) for (x in 0 until q.size) {
            if (!q.getModule(x, y)) continue
            for (dy in 0 until scale) for (dx in 0 until scale) px[((y + quiet) * scale + dy) * bmp.width + (x + quiet) * scale + dx] = Color.BLACK
        }
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp
    }.getOrNull()

    fun summary(ctx: Context): String = if (enabled(ctx)) L("включена", "on") else L("выключена", "off")

    fun show(ctx: Context) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(4))
        }
        val sw = SwitchCompat(ctx).apply {
            text = L("Раздавать VPN другим устройствам", "Share the VPN with other devices")
            isChecked = enabled(ctx)
            textSize = 15f
        }
        box.addView(sw)
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(info)
        fun row(title: String, value: String) {
            info.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
                addView(TextView(ctx).apply {
                    text = title
                    textSize = 13f
                    alpha = 0.7f
                }, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(ctx).apply {
                    text = value
                    textSize = 14f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextIsSelectable(true)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        fun paint() {
            info.removeAllViews()
            info.addView(TextView(ctx).apply {
                text = L(
                    "1) Включите точку доступа (или USB-модем) и подключите к ней устройство.\n2) В настройках Wi-Fi/сети устройства укажите прокси - адрес и порт ниже, логин и пароль.\n3) Готово: сайты на устройстве идут через VPN этого телефона.\nПрокси передаёт TCP (сайты, приложения); UDP - игры и звонки - идёт мимо. В клиентах выбирайте socks5h: тогда и DNS идёт через VPN.",
                    "1) Turn on the hotspot (or USB tethering) and connect the device to it.\n2) In the device's Wi-Fi/network settings set a proxy - the address and port below, plus the login and password.\n3) Done: the device's sites go through this phone's VPN.\nThe proxy carries TCP (sites, apps); UDP - games and calls - bypasses it. In clients pick socks5h so DNS also goes through the VPN.",
                )
                textSize = 12.5f
                setPadding(0, dp(6), 0, dp(8))
            })
            val eps = endpoints(ctx)
            if (eps.isEmpty()) {
                info.addView(TextView(ctx).apply {
                    text = L("⚠ Точка доступа не найдена - включите раздачу Wi-Fi или USB-модем и откройте это окно снова.", "⚠ No hotspot found - turn on Wi-Fi hotspot or USB tethering and reopen this window.")
                    textSize = 12.5f
                    setTextColor(0xFFDC2626.toInt())
                })
            }
            eps.forEach { e -> row(e.kind.title, e.host) }
            row("SOCKS5", "$SOCKS_PORT")
            row("HTTP", "$HTTP_PORT")
            row(L("Логин", "Login"), user(ctx))
            row(L("Пароль", "Password"), pass(ctx))
            val host = eps.firstOrNull { it.kind.downstream }?.host ?: eps.firstOrNull()?.host
            if (host != null) {
                val link = "socks5h://${user(ctx)}:${pass(ctx)}@$host:$SOCKS_PORT"
                val size = (minOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) * 0.5f).toInt()
                qr(link, size)?.let { bmp ->
                    info.addView(ImageView(ctx).apply {
                        setImageBitmap(bmp)
                        adjustViewBounds = true
                    }, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
                }
                info.addView(TextView(ctx).apply {
                    text = "📋 " + L("Скопировать ссылку прокси", "Copy proxy link")
                    setTextColor(0xFF5B4BDB.toInt())
                    setTypeface(Typeface.DEFAULT_BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(4))
                    setOnClickListener {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("proxy", link))
                        Toast.makeText(ctx, com.stoyko.smessenger.R.string.copied, Toast.LENGTH_SHORT).show()
                    }
                })
            }
            info.addView(TextView(ctx).apply {
                text = "🔑 " + L("Новый пароль", "New password")
                setTextColor(0xFF5B4BDB.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(4))
                setOnClickListener {
                    prefs(ctx).edit().putString("pass", newPass()).apply()
                    paint()
                    if (enabled(ctx)) VpnExtras.reconnectIfXray(ctx)
                }
            })
            if (VpnStore(ctx).mode == VpnStore.MODE_WARP) {
                info.addView(TextView(ctx).apply {
                    text = L("Раздача работает в режимах «Серверы», «Обход DPI» и «Фрагментация» (у WARP нет своего прокси).", "Sharing works in the Servers, DPI bypass and Fragmentation modes (WARP has no proxy).")
                    textSize = 12f
                    setTextColor(0xFFD97706.toInt())
                })
            }
            info.background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(0x0F5B4BDB)
            }
            info.setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        paint()
        AlertDialog.Builder(ctx)
            .setTitle(L("📡 Раздача VPN", "📡 Share VPN"))
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(com.stoyko.smessenger.R.string.save) { _, _ ->
                val was = enabled(ctx)
                prefs(ctx).edit().putBoolean("on", sw.isChecked).apply()
                if (was != sw.isChecked) VpnExtras.reconnectIfXray(ctx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/** Общее: дополнения конфигурации Xray и переподключение после смены настроек. */
object VpnExtras {
    /** Добавить входы шлюза и правила «напрямую» в готовые списки конфигурации. */
    fun apply(ctx: Context, inbounds: JSONArray, rules: JSONArray, sniff: JSONObject) {
        runCatching { Gateway.inbounds(ctx, sniff).forEach { inbounds.put(it) } }
        runCatching { DomainBypass.rule(ctx)?.let { r -> insertFirst(rules, r) } }
    }

    private fun insertFirst(arr: JSONArray, o: JSONObject) {
        val copy = (0 until arr.length()).map { arr.get(it) }
        while (arr.length() > 0) arr.remove(arr.length() - 1)
        arr.put(o)
        copy.forEach { arr.put(it) }
    }

    /** VPN включён в режиме ядра Xray - переподключить с новыми настройками. */
    fun reconnectIfXray(ctx: Context) {
        val store = VpnStore(ctx)
        if (VpnState.status == VpnState.Status.On && store.mode != VpnStore.MODE_WARP) {
            SmVpnService.start(ctx)
            Toast.makeText(ctx, com.stoyko.smessenger.R.string.vpn_options_reconnect, Toast.LENGTH_SHORT).show()
        }
    }
}
