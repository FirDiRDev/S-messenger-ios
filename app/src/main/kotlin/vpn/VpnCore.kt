// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import com.stoyko.smessenger.R
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Подписка (URL со списком серверов, как в Happ/v2rayNG). */
data class VpnSubscription(val id: String, val name: String, val url: String, val updated: Long)

/** Серверы, подписки и настройки VPN. */
class VpnStore(context: Context) {
    private val appCtx = context.applicationContext

    companion object {
        private val LOCK = Any()
        @Volatile private var cachedProfiles: List<VpnProfile>? = null
        @Volatile private var cachedSubs: List<VpnSubscription>? = null
        /** Одна очередь записи: сохранения идут строго по порядку и не трогают главный поток. */
        private val IO = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vpn-store").apply { isDaemon = true } }
        const val MODE_SERVER = "server"
        const val MODE_DPI = "dpi"
        const val MODE_FRAGMENT = "fragment"
        const val MODE_WARP = "warp"

        /** Ядро WARP: встроенный AmneziaWG (главный процесс), Nova WireGuard или Nova MASQUE (процесс «:nova»). */
        const val WARP_AWG = "awg"
        const val WARP_NOVA = "nova"
        const val WARP_MASQUE = "masque"

        /** «🆓ProxyNova ❯ 🗽🕊✊ Ручной выбор Обход БС (Тест)» - бесплатная подписка по умолчанию. */
        const val BUILTIN_SUB = "builtin"

        /** Подписки с автообновлением: белые списки (БС), их зеркало, чёрные списки (ЧС), БС лайт. */
        val EXTRA_SUBS = listOf(
            "https://raw.githubusercontent.com/flaafix/AetrisVPN/refs/heads/main/AetrisVPN.txt" to "🔄 AetrisVPN · БС",
            "https://gitverse.ru/api/repos/flaafix/AetrisVPN/raw/branch/master/AetrisVPN.txt" to "🔄 AetrisVPN · БС (зеркало)",
            "https://raw.githubusercontent.com/flaafix/AetrisVPN-black-list/refs/heads/main/configs.txt" to "🔄 AetrisVPN · ЧС",
            "https://raw.githubusercontent.com/flaafix/AetrisVPN-white-list-lite/refs/heads/main/AetrisVPN.txt" to "✨ AetrisVPN · БС лайт",
        )

        /** Подписки 4.11.1. Первая - главная: умный YAML Pozor (АВТО/РУЧНОЙ, РФ-сервисы напрямую). */
        val POZOR_SUBS = listOf(
            "https://pozor.bond/sub/j12l3s0dtu" to "⭐ Pozor · Умный (АВТО)",
            "https://gitverse.ru/api/repos/Pizduk/PizdukVPN/raw/branch/master/sub.txt" to "🔄 PZD · Общая",
            "https://raw.githubusercontent.com/MrMisha4048/pizdukspecial/refs/heads/main/vpn.txt" to "🔄 PZD Special",
            "https://gitverse.ru/api/repos/Pizduk/PizdukVPN/raw/branch/master/AutoPizduk.txt" to "🔄 PZD · Авто",
            "https://gitverse.ru/api/repos/Pizduk/PizdukVPN/raw/branch/master/WlSubPiz.txt" to "🔄 PZD · БС",
            "https://gitverse.ru/api/repos/Pizduk/PizdukVPN/raw/branch/master/WLAutoPiz.txt" to "🔄 PZD · БС авто",
            "https://test.bostnet.xyz/huvg2b7juz9107gq/dkaaevox8kjmecqc" to "🔄 BostNet",
            "https://gitverse.ru/api/repos/Pizduk/PizdukVPN/raw/branch/master/Mixer" to "🔄 PZD · Микс",
        )

        /** Категории серверов: 1 - обычные, 2 - обход белых списков (БС), 3 - чёрные списки (ЧС). */
        const val CAT_NORMAL = 1
        const val CAT_WHITE = 2
        const val CAT_BLACK = 3

        fun category(p: VpnProfile, sub: VpnSubscription?): Int {
            val t = (p.name + " " + sub?.name.orEmpty() + " " + sub?.url.orEmpty()).lowercase()
            return when {
                Regex("(^|[^а-яa-z])бс([^а-яa-z]|$)").containsMatchIn(t) || "white" in t || "бел" in t -> CAT_WHITE
                Regex("(^|[^а-яa-z])чс([^а-яa-z]|$)").containsMatchIn(t) || "black" in t || "чёрн" in t || "черн" in t -> CAT_BLACK
                else -> CAT_NORMAL
            }
        }

        /** «🆓ProxyNova ❯ 🗽🕊✊ Ручной выбор» - все серверы ProxyNova (обычные, БС, торрент, YouTube). */
        const val MANUAL_SUB_URL = "https://gitverse.ru/api/repos/SlavaKat/FreeProxyNova/raw/branch/master/%F0%9F%86%93ProxyNova%20%E2%9D%AF%20%F0%9F%97%BD%F0%9F%95%8A%E2%9C%8A%20%D0%A0%D1%83%D1%87%D0%BD%D0%BE%D0%B9%20%D0%B2%D1%8B%D0%B1%D0%BE%D1%80"

        const val DEFAULT_SUB_URL = "https://gitverse.ru/api/repos/SlavaKat/FreeProxyNova/raw/branch/master/%F0%9F%86%93ProxyNova%20%E2%9D%AF%20%F0%9F%97%BD%F0%9F%95%8A%E2%9C%8A%20%D0%A0%D1%83%D1%87%D0%BD%D0%BE%D0%B9%20%D0%B2%D1%8B%D0%B1%D0%BE%D1%80%20%D0%9E%D0%B1%D1%85%D0%BE%D0%B4%20%D0%91%D0%A1%20%28%D0%A2%D0%B5%D1%81%D1%82%29"
    }

    private val prefs = context.applicationContext.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    /**
     * Серверов из подписок бывают тысячи, и разбор/запись такого JSON на главном потоке
     * подвешивал экран на 5-6 секунд (ANR). Теперь список разбирается один раз на процесс
     * и живёт в памяти, а запись идёт по очереди в фоновом потоке.
     */
    fun profiles(): List<VpnProfile> {
        cachedProfiles?.let { return it }
        synchronized(LOCK) {
            cachedProfiles?.let { return it }
            val l = runCatching {
                val a = JSONArray(prefs.getString("profiles", "[]"))
                (0 until a.length()).mapNotNull { runCatching { VpnProfile.fromJson(a.getJSONObject(it)) }.getOrNull() }
            }.getOrDefault(emptyList())
            cachedProfiles = l
            return l
        }
    }

    fun saveProfiles(list: List<VpnProfile>) {
        val snap = list.toList()
        synchronized(LOCK) { cachedProfiles = snap }
        IO.execute {
            runCatching {
                if (cachedProfiles !== snap) return@runCatching // уже есть более свежий список - запишем его
                val a = JSONArray()
                snap.forEach { a.put(it.toJson()) }
                prefs.edit().putString("profiles", a.toString()).apply()
            }
        }
    }

    /** Добавить, пропуская дубликаты ссылок. Возвращает число добавленных. */
    fun add(list: List<VpnProfile>): Int {
        val cur = profiles()
        val links = cur.map { it.link }.toHashSet()
        val fresh = list.filter { links.add(it.link) }
        saveProfiles(cur + fresh)
        if (selectedId.isEmpty()) fresh.firstOrNull()?.let { selectedId = it.id }
        return fresh.size
    }

    fun remove(id: String) {
        saveProfiles(profiles().filter { it.id != id })
        if (selectedId == id) selectedId = profiles().firstOrNull()?.id.orEmpty()
    }

    fun update(p: VpnProfile) = saveProfiles(profiles().map { if (it.id == p.id) p else it })

    var selectedId: String
        get() = prefs.getString("selected", "").orEmpty()
        set(v) = prefs.edit().putString("selected", v).apply()

    val selected: VpnProfile? get() = profiles().let { l -> l.firstOrNull { it.id == selectedId } ?: l.firstOrNull() }

    /**
     * Режим VPN:
     *  - "server" - свой сервер (VLESS/VMess/Trojan/…), как раньше;
     *  - "dpi" - обход DPI без сервера (движок ByeDPI на телефоне);
     *  - "fragment" - обход DPI силами ядра Xray (фрагментация TLS и «шум»);
     *  - "warp" - бесплатный Cloudflare WARP через AmneziaWG.
     */
    var mode: String
        get() = prefs.getString("mode", MODE_SERVER) ?: MODE_SERVER
        set(v) = prefs.edit().putString("mode", v).apply()

    /** Выбранная стратегия обхода DPI и её параметры (можно править вручную). */
    var dpiStrategy: String
        get() = prefs.getString("dpi_strategy", DpiStrategies.list[0].id) ?: DpiStrategies.list[0].id
        set(v) = prefs.edit().putString("dpi_strategy", v).apply()

    var dpiArgs: String
        get() = prefs.getString("dpi_args", null)?.takeIf { it.isNotBlank() } ?: DpiStrategies.byId(dpiStrategy).args
        set(v) = prefs.edit().putString("dpi_args", v).apply()

    /**
     * Совместный режим: подключение к своему VPN-серверу идёт через обход DPI (ByeDPI) -
     * провайдер не узнаёт и не режет рукопожатие с сервером (для TCP-протоколов).
     */
    var serverViaDpi: Boolean
        get() = prefs.getBoolean("server_via_dpi", false)
        set(v) = prefs.edit().putBoolean("server_via_dpi", v).apply()

    /** Конфигурация WARP / AmneziaWG (.conf). */
    var warpConf: String
        get() = prefs.getString("warp_conf", "").orEmpty()
        set(v) = prefs.edit().putString("warp_conf", v).apply()

    /**
     * Ядро WARP (см. [WARP_AWG], [WARP_NOVA], [WARP_MASQUE]). Ядро Nova работает в отдельном
     * процессе, поэтому переключение с серверов Xray на него идёт без перезапуска приложения.
     */
    var warpEngine: String
        get() = prefs.getString("warp_engine", WARP_AWG).orEmpty().let { if (it == WARP_NOVA || it == WARP_MASQUE) it else WARP_AWG }
        set(v) = prefs.edit().putString("warp_engine", v).apply()

    /** WARP сейчас ведёт ядро Nova (если оно есть в сборке для этого процессора). */
    fun warpOnNova(ctx: Context): Boolean = mode == MODE_WARP && warpEngine != WARP_AWG && NovaLink.available(ctx)

    /** Ключ Cloudflare MASQUE (JSON ядра Nova) и аккаунт WARP, на который он выпущен. */
    var masqueIdentity: String
        get() = prefs.getString("masque_identity", "").orEmpty()
        set(v) = prefs.edit().putString("masque_identity", v).apply()
    var warpToken: String
        get() = prefs.getString("warp_token", "").orEmpty()
        set(v) = prefs.edit().putString("warp_token", v).apply()
    var warpDeviceId: String
        get() = prefs.getString("warp_device", "").orEmpty()
        set(v) = prefs.edit().putString("warp_device", v).apply()

    fun resetMasque() = prefs.edit().remove("masque_identity").remove("warp_token").remove("warp_device").apply()

    /** Стратегия обхода DPI уже подбиралась автоматически (для режима «Авто»). */
    var dpiPicked: Boolean
        get() = prefs.getBoolean("dpi_picked", false)
        set(v) = prefs.edit().putBoolean("dpi_picked", v).apply()

    /** После проверки пинга сразу удалять серверы, которые не ответили. */
    var autoRemoveDead: Boolean
        get() = prefs.getBoolean("auto_remove_dead", false)
        set(v) = prefs.edit().putBoolean("auto_remove_dead", v).apply()

    /** Название того, что будет включено (для щита, шторки и уведомления). */
    fun targetName(ctx: Context): String = when (mode) {
        MODE_DPI -> ctx.getString(R.string.vpn_mode_dpi) + " · " + DpiStrategies.byId(dpiStrategy).title
        MODE_FRAGMENT -> ctx.getString(R.string.vpn_mode_fragment)
        MODE_WARP -> ctx.getString(R.string.vpn_mode_warp) + when {
            !NovaLink.available(ctx) -> ""
            warpEngine == WARP_MASQUE -> " · MASQUE"
            warpEngine == WARP_NOVA -> " · Nova"
            else -> ""
        }
        else -> selected?.name.orEmpty()
    }

    /** Есть ли что включать в текущем режиме. */
    fun ready(): Boolean = when (mode) {
        MODE_SERVER -> selected != null
        MODE_WARP -> warpConf.isNotBlank() || (warpEngine == WARP_MASQUE && NovaLink.available(appCtx))
        else -> true
    }

    /** Пускать трафик самого мессенджера (Tox) через VPN. */
    var routeTox: Boolean
        get() = prefs.getBoolean("route_tox", true)
        set(v) = prefs.edit().putBoolean("route_tox", v).apply()

    /** Весь трафик телефона через VPN (иначе - только мессенджер через локальный SOCKS). */
    var fullTunnel: Boolean
        get() = prefs.getBoolean("full_tunnel", true)
        set(v) = prefs.edit().putBoolean("full_tunnel", v).apply()

    fun subscriptions(): List<VpnSubscription> {
        cachedSubs?.let { return it }
        synchronized(LOCK) {
            cachedSubs?.let { return it }
            val l = runCatching {
                val a = JSONArray(prefs.getString("subs", "[]"))
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    VpnSubscription(o.getString("id"), o.optString("name"), o.getString("url"), o.optLong("updated"))
                }
            }.getOrDefault(emptyList())
            cachedSubs = l
            return l
        }
    }

    fun saveSubscriptions(list: List<VpnSubscription>) {
        val snap = list.toList()
        synchronized(LOCK) { cachedSubs = snap }
        IO.execute {
            runCatching {
                if (cachedSubs !== snap) return@runCatching
                val a = JSONArray()
                snap.forEach {
                    a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("updated", it.updated))
                }
                prefs.edit().putString("subs", a.toString()).apply()
            }
        }
    }

    /**
     * Подписка по умолчанию (бесплатные серверы ProxyNova, в том числе для обхода «белых списков»).
     * Добавляется один раз; если человек её удалит - больше не появится. true - только что добавлена.
     */
    fun ensureDefaults(): Boolean {
        var fresh = false
        if (!prefs.getBoolean("default_sub_added", false)) {
            prefs.edit().putBoolean("default_sub_added", true).apply()
            if (subscriptions().none { it.url.contains("FreeProxyNova") }) {
                addSubscription(DEFAULT_SUB_URL, "🆓 ProxyNova · Обход БС (Тест)")
                fresh = true
            }
        }
        // Подписки AetrisVPN с автообновлением (белые и чёрные списки) - один раз.
        if (!prefs.getBoolean("subs_aetris_added", false)) {
            prefs.edit().putBoolean("subs_aetris_added", true).apply()
            val have = subscriptions().map { it.url }.toHashSet()
            for ((url, name) in EXTRA_SUBS) if (url !in have) {
                addSubscription(url, name)
                fresh = true
            }
        }
        // Подписка ProxyNova «Ручной выбор» (автообновление) - один раз.
        if (!prefs.getBoolean("sub_pn_manual_added", false)) {
            prefs.edit().putBoolean("sub_pn_manual_added", true).apply()
            if (subscriptions().none { it.url == MANUAL_SUB_URL }) {
                addSubscription(MANUAL_SUB_URL, "🆓 ProxyNova · Ручной выбор")
                fresh = true
            }
        }
        // Свежие серверы ProxyNova (встроены в приложение, 11.10.26) - один раз, без повторов.
        if (!prefs.getBoolean("builtin_pn_261011", false)) {
            prefs.edit().putBoolean("builtin_pn_261011", true).apply()
            val list = runCatching {
                val txt = appCtx.assets.open("vpn_proxynova.txt").bufferedReader().use { it.readText() }
                VpnLinks.parseMany(txt, BUILTIN_SUB).filter { it.host != "0.0.0.0" && it.host.isNotBlank() }
            }.getOrDefault(emptyList())
            if (list.isNotEmpty() && add(list) > 0) fresh = true
        }
        // Встроенные серверы (работают сразу, без загрузки подписок).
        if (!prefs.getBoolean("builtin_v1", false)) {
            prefs.edit().putBoolean("builtin_v1", true).apply()
            val list = runCatching { builtinServers() }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                add(list)
                fresh = true
            }
        }
        // 4.11.1: умная подписка Pozor (YAML Mihomo: Инно, Каскад, WARP, РФ-ноды) - первой в списке,
        // и подписки PZD / BostNet - один раз, без повторов.
        if (!prefs.getBoolean("subs_pozor_1018", false)) {
            prefs.edit().putBoolean("subs_pozor_1018", true).apply()
            val have = subscriptions().map { it.url.trim() }.toHashSet()
            for ((url, name) in POZOR_SUBS) if (have.add(url)) {
                addSubscription(url, name)
                fresh = true
            }
            // Pozor - на самый верх списка: обновляется первым, его серверы выбираются первыми.
            val main = POZOR_SUBS.first().first
            val all = subscriptions()
            saveSubscriptions(all.filter { it.url == main } + all.filter { it.url != main })
        }
        // Подписка MIFA (из конфига Clash/Mihomo mifa_dns_ads.yaml) - один раз.
        if (!prefs.getBoolean("sub_mifa_added", false)) {
            prefs.edit().putBoolean("sub_mifa_added", true).apply()
            if (subscriptions().none { it.url.contains("mifa.world") }) {
                addSubscription("https://mifa.world/more", "🆓 MIFA · VLESS")
                fresh = true
            }
        }
        // Обновление ProxyNova 14.10.26: 47 ссылок + 4 конфига Xray (ЧС, обход БС, Telegram, YouTube).
        if (!prefs.getBoolean("builtin_pn_261014", false)) {
            prefs.edit().putBoolean("builtin_pn_261014", true)
                // Готовые WARP-конфиги FIX / AMFITON / OMSK / SHARP - первыми по кругу.
                .putInt("warp_seed_i", -1).apply()
            val list = runCatching { builtinServers("vpn_proxynova_1014.txt", "vpn_builtin_1014.json") }.getOrDefault(emptyList())
            if (list.isNotEmpty() && add(list) > 0) fresh = true
        }
        return fresh
    }

    /** Серверы из приложения: список ссылок и конфиги Xray (БС / ЧС / торрент). */
    private fun builtinServers(txtAsset: String = "vpn_builtin.txt", jsonAsset: String = "vpn_builtin.json"): List<VpnProfile> {
        val out = ArrayList<VpnProfile>()
        val txt = appCtx.assets.open(txtAsset).bufferedReader().use { it.readText() }
        // Строки-заголовки вида vless://info@0.0.0.0 - не серверы.
        out += VpnLinks.parseMany(txt, BUILTIN_SUB).filter { it.host != "0.0.0.0" && it.host.isNotBlank() }
        val js = org.json.JSONArray(appCtx.assets.open(jsonAsset).bufferedReader().use { it.readText() })
        for (i in 0 until js.length()) {
            val cfg = js.optJSONObject(i) ?: continue
            val remark = cfg.optString("remarks").substringAfter("❯").substringBefore(" 0")
                .replace(Regex("""\d{1,2}\.\d{1,2}\.\d{2,4}(\(\d+\))?"""), "")
                .replace("🔄", "").replace("(Тест)", "").replace("Авто выбор", "").replace("🗽🕊✊", "")
                .replace(Regex("\\s+"), " ").trim().ifBlank { "Xray" }
            val obs = org.json.JSONObject().put("outbounds", cfg.optJSONArray("outbounds") ?: continue)
            VpnLinks.parseMany(obs.toString(), BUILTIN_SUB).filter { it.host.isNotBlank() && it.host != "0.0.0.0" }
                .forEachIndexed { n, p -> out += p.copy(name = "📦 $remark · ${n + 1}") }
        }
        return out.distinctBy { it.link }
    }

    fun addSubscription(url: String, name: String, first: Boolean = false): VpnSubscription {
        val s = VpnSubscription(UUID.randomUUID().toString(), name, url.trim(), 0)
        saveSubscriptions(if (first) listOf(s) + subscriptions() else subscriptions() + s)
        return s
    }

    fun removeSubscription(id: String) {
        saveSubscriptions(subscriptions().filter { it.id != id })
        saveProfiles(profiles().filter { it.subId != id })
    }
}

/** Состояние VPN для экрана, шторки и плитки быстрых настроек. */
object VpnState {
    enum class Status { Off, Connecting, On, Error }

    @Volatile
    var status = Status.Off
        private set

    @Volatile
    var profileName = ""
        private set

    @Volatile
    var since = 0L
        private set

    @Volatile
    var error = ""
        private set

    /** Мессенджер сейчас ходит через локальный SOCKS-порт VPN. */
    @Volatile
    var toxRouted = false

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    val running: Boolean get() = status == Status.On

    /** Работает локальный прокси ядра (SOCKS 10808 / HTTP 10809). У WARP его нет - там весь трафик идёт туннелем. */
    @Volatile
    var localProxy = false

    /** Через локальный прокси можно ходить прямо сейчас. */
    val proxyReady: Boolean get() = running && localProxy

    fun set(s: Status, name: String = profileName, err: String = "") {
        if (s != status || err.isNotBlank()) com.stoyko.smessenger.domain.Diagnostics.crumb("VPN: ${s.name}" + (if (err.isNotBlank()) " - $err" else ""))
        status = s
        profileName = name
        error = err
        if (s == Status.On) since = System.currentTimeMillis()
        main.post { listeners.toList().forEach { runCatching { it() } } }
    }

    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }

    /** Прокси для собственных запросов приложения (подписки, S-AI), когда VPN включён. */
    fun httpProxy(): Proxy =
        if (proxyReady) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", VpnLinks.SOCKS_PORT)) else Proxy.NO_PROXY
}

/** Ядро Xray (AndroidLibXrayLite): запуск, остановка, реальный пинг. */
object XrayCore {
    private const val TAG = "XrayCore"
    const val TEST_URL = "https://www.gstatic.com/generate_204"
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 24

    @Volatile
    private var inited = false
    private var controller: libv2ray.CoreController? = null
    /**
     * Сколько серверов проверяется одновременно (как «скорость пинга» в YPtun): по числу ядер
     * процессора - 3 на слабых телефонах, до 8 на мощных. Раньше всегда было 4.
     */
    private val PARALLEL = Runtime.getRuntime().availableProcessors().coerceIn(3, 8)
    private val pingPool = Executors.newFixedThreadPool(PARALLEL) { Thread(it, "vpn-ping").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Своя блокировка для загрузки ядра: раньше init() ждал ту же блокировку, что start()/stop(),
     * и главный поток (например, номер версии в настройках) подвисал, пока ядро подключалось
     * или останавливалось.
     */
    private val initLock = Any()

    fun init(context: Context) {
        VpnTweaks.load(context)
        if (inited) return
        synchronized(initLock) {
            if (inited) return
            // В процессе уже работает ядро WARP (другой рантайм Go) - Xray грузить нельзя, это вылет.
            if (!GoGuard.claim(GoGuard.XRAY)) throw IllegalStateException("WARP engine is loaded in this process")
            go.Seq.setContext(context.applicationContext)
            libv2ray.Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
            inited = true
        }
        Log.i(TAG, "Xray " + runCatching { libv2ray.Libv2ray.checkVersionX() }.getOrDefault("?"))
    }

    fun version(context: Context): String = runCatching {
        init(context)
        libv2ray.Libv2ray.checkVersionX()
    }.getOrDefault("")

    @Synchronized
    fun start(context: Context, config: String, tunFd: Int) {
        init(context)
        stop()
        val c = libv2ray.Libv2ray.newCoreController(object : libv2ray.CoreCallbackHandler {
            override fun onEmitStatus(p0: Long, p1: String?): Long {
                Log.i(TAG, "status $p0: $p1")
                return 0
            }

            override fun shutdown(): Long = 0
            override fun startup(): Long = 0
        })
        c.startLoop(config, tunFd)
        controller = c
    }

    @Synchronized
    fun stop() {
        val c = controller ?: return
        controller = null
        runCatching { c.stopLoop() }.onFailure { Log.w(TAG, "stop: $it") }
    }

    val isRunning: Boolean get() = runCatching { controller?.isRunning == true }.getOrDefault(false)

    /** Последняя причина, по которой проверка через ядро не сработала (для журнала и экрана). */
    @Volatile
    var lastPingError: String = ""
        private set

    @Volatile
    private var pingErrorLogged = false

    /**
     * Ошибка «окружения», а не сервера: ядро не смогло найти адрес, открыть сокет или прочитать
     * сетевые настройки (так бывает на Android 11 и прошивках с урезанным DNS для Go). Тогда
     * сервер не «мёртвый» - пробуем иначе.
     */
    private fun envError(msg: String): Boolean {
        val m = msg.lowercase()
        return listOf(
            "lookup", "resolve", "dns", "no such host", "netlink", "permission denied", "operation not permitted",
            "[::1]:53", "127.0.0.1:53", "resolv.conf", "socket: ", "address family", "network is unreachable",
            "no route", "protocol not available", "x509", "certificate",
        ).any { it in m }
    }

    /**
     * Та же проверка, но без системного DNS ядра (4.10.0): адрес сервера заранее находится средствами
     * Android и подставляется числом (имя остаётся в TLS/SNI), а ядру дан собственный список DNS.
     */
    private fun pinnedConfig(link: String): String? = runCatching {
        val cfg = org.json.JSONObject(VpnLinks.pingConfig(link))
        cfg.put(
            "dns",
            org.json.JSONObject()
                .put("servers", org.json.JSONArray().put("1.1.1.1").put("8.8.8.8").put("77.88.8.8"))
                .put("queryStrategy", "UseIPv4"),
        )
        val outs = cfg.optJSONArray("outbounds") ?: return@runCatching null
        val main = outs.optJSONObject(0) ?: return@runCatching null
        val st = main.optJSONObject("settings") ?: return@runCatching null
        fun pin(o: org.json.JSONObject?) {
            o ?: return
            val a = o.optString("address")
            if (a.isBlank() || a.all { it.isDigit() || it == '.' || it == ':' }) return
            val ip = java.net.InetAddress.getAllByName(a).firstOrNull { it is java.net.Inet4Address }?.hostAddress
                ?: java.net.InetAddress.getByName(a).hostAddress ?: return
            o.put("address", ip)
            // Имя сервера для TLS остаётся прежним - иначе сертификат не сойдётся.
            val stream = main.optJSONObject("streamSettings")
            stream?.optJSONObject("tlsSettings")?.let { if (it.optString("serverName").isBlank()) it.put("serverName", a) }
        }
        st.optJSONArray("vnext")?.let { for (i in 0 until it.length()) pin(it.optJSONObject(i)) }
        st.optJSONArray("servers")?.let { for (i in 0 until it.length()) pin(it.optJSONObject(i)) }
        if (st.has("address")) pin(st)
        cfg.toString()
    }.getOrNull()

    /** Протоколы поверх UDP/QUIC: TCP-порт у них закрыт, проверка TCP ничего не говорит. */
    private fun udpOnly(link: String): Boolean {
        val l = link.lowercase()
        return l.startsWith("hysteria2://") || l.startsWith("hy2://") || l.startsWith("wireguard://") || l.startsWith("wg://") ||
            "type=kcp" in l || "type=quic" in l
    }

    /**
     * Быстрая предпроверка (идея YPtun): порт сервера не открывается за 2,5 с - ядро не запускаем,
     * сразу «не отвечает». Раньше каждый мёртвый сервер держал поток до 12 с, и длинный список
     * проверялся минутами. null - проверить не удалось (нет DNS и т.п.), решает ядро.
     */
    private fun quickTcp(link: String): Long? {
        if (udpOnly(link)) return null
        val u = runCatching { android.net.Uri.parse(link) }.getOrNull() ?: return null
        var host = u.host
        var port = u.port
        if (host.isNullOrBlank() && link.startsWith("vmess://")) {
            val j = runCatching { org.json.JSONObject(VpnLinks.decodeBase64(link.removePrefix("vmess://")) ?: "") }.getOrNull() ?: return null
            host = j.optString("add")
            port = j.optString("port").toIntOrNull() ?: -1
        }
        if (host.isNullOrBlank()) return null
        if (port <= 0) port = 443
        val addr = runCatching { java.net.InetAddress.getByName(host.trim('[', ']')) }.getOrNull() ?: return null
        val t0 = System.nanoTime()
        return try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(addr, port), 2_500) }
            ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
        } catch (e: java.net.SocketTimeoutException) {
            -1L
        } catch (e: java.net.ConnectException) {
            // «Нет маршрута»/«сеть недоступна» - беда телефона, а не сервера.
            val m = e.message.orEmpty().lowercase()
            if ("unreachable" in m || "no route" in m) null else -1L
        } catch (e: Exception) {
            null
        }
    }

    /** ICMP-пинг системной утилитой (для Hysteria2/WireGuard по UDP, когда ядро проверить не смогло). */
    private fun icmpPing(link: String): Long = runCatching {
        val host = android.net.Uri.parse(link).host?.trim('[', ']') ?: return@runCatching -1L
        val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", host).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        Regex("time[=<]([0-9.]+)").find(out)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()?.coerceAtLeast(1) ?: -1L
    }.getOrDefault(-1L)

    /** Простая проверка: TCP-соединение с сервером (если ядро не может проверить на этом телефоне). */
    private fun tcpPing(link: String): Long = if (udpOnly(link)) icmpPing(link) else runCatching {
        val u = android.net.Uri.parse(link)
        var host = u.host
        var port = u.port
        if (host.isNullOrBlank() && link.startsWith("vmess://")) {
            val j = org.json.JSONObject(VpnLinks.decodeBase64(link.removePrefix("vmess://")) ?: return@runCatching -1L)
            host = j.optString("add")
            port = j.optString("port").toIntOrNull() ?: -1
        }
        if (host.isNullOrBlank()) return@runCatching -1L
        if (port <= 0) port = 443
        val addr = java.net.InetSocketAddress(java.net.InetAddress.getByName(host), port)
        val t0 = System.nanoTime()
        java.net.Socket().use { it.connect(addr, 3_500) }
        ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
    }.getOrDefault(-1L)

    private fun noteError(msg: String) {
        lastPingError = msg.take(200)
        if (!pingErrorLogged) {
            pingErrorLogged = true
            runCatching { com.stoyko.smessenger.domain.Diagnostics.crumb("VPN пинг: ядро не смогло проверить (${Build.VERSION.SDK_INT}): ${msg.take(160)}") }
        }
    }

    /**
     * Реальная задержка (полный запрос через сервер), мс; -1 - сервер не отвечает.
     *
     * 4.10.0: на Android 11 (и части прошивок) ядро проверки падало с ошибкой поиска адресов -
     * пинг не показывался ни у одного сервера. Теперь при такой ошибке: вторая попытка с адресом,
     * найденным средствами Android, и своим DNS; не вышло и это - TCP-проверка доступности.
     */
    fun realPing(context: Context, link: String): Long {
        // Работает WARP (другое ядро) - проверить нельзя: «не проверяли», а не «мёртвый».
        if (GoGuard.conflict(GoGuard.XRAY)) return 0L
        // Порт сервера закрыт - дальше не проверяем (ядро всё равно не подключится).
        val tcp = if (VpnState.running) null else quickTcp(link)
        if (tcp != null && tcp < 0) return -1L
        val first: Throwable = try {
            init(context)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val r = libv2ray.Libv2ray.measureOutboundDelay(VpnLinks.pingConfig(link), TEST_URL)
            if (r > 0) return r
            // Порт открыт, но запрос сорвался быстро (сброс, обрыв TLS) - бывает разово:
            // вторая попытка через 150 мс, как в YPtun. Долгие отказы не повторяем.
            if (tcp != null && tcp > 0 && android.os.SystemClock.elapsedRealtime() - t0 < 4_000) {
                Thread.sleep(150)
                val r2 = libv2ray.Libv2ray.measureOutboundDelay(VpnLinks.pingConfig(link), TEST_URL)
                if (r2 > 0) return r2
            }
            return -1L
        } catch (e: Throwable) {
            e
        }
        val msg = first.message ?: first.javaClass.simpleName
        Log.w(TAG, "ping: $msg")
        if (first is UnsatisfiedLinkError || first is ExceptionInInitializerError) {
            noteError(msg)
            return tcpPing(link)
        }
        if (!envError(msg)) return -1L
        noteError(msg)
        // VPN уже работает - обходные проверки шли бы мимо туннеля и долго: только «не ответил».
        if (VpnState.running) return -1L
        pinnedConfig(link)?.let { cfg ->
            try {
                val r = libv2ray.Libv2ray.measureOutboundDelay(cfg, TEST_URL)
                if (r > 0) return r
            } catch (e: Throwable) {
                Log.w(TAG, "ping (pinned): ${e.message}")
            }
        }
        return tcpPing(link)
    }

    /**
     * Почему не ответил НИ ОДИН сервер (4.10.0). На части телефонов (журнал Tecno KF6n,
     * Android 11) проверка каждого сервера кончалась «EOF» / «TLS handshake timeout»:
     * так бывает, когда часы телефона ушли - серверы VMess и Reality отбрасывают подключение
     * с «неверным» временем; или когда сеть режет сами серверы. Возвращает понятный текст или null.
     */
    fun diagnoseAllDead(context: Context, sample: List<VpnProfile>): String? {
        val ru = java.util.Locale.getDefault().language == "ru"
        // 1. Часы: время по ответу сервера Google против часов телефона.
        val skewSec = runCatching {
            val c = (URL("https://www.google.com/generate_204").openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "HEAD"
                useCaches = false
            }
            try {
                c.responseCode
                val server = c.date
                if (server <= 0L) null else (System.currentTimeMillis() - server) / 1000
            } finally {
                c.disconnect()
            }
        }.getOrNull()
        if (skewSec != null && kotlin.math.abs(skewSec) >= 60) {
            val min = kotlin.math.abs(skewSec) / 60
            return if (ru) {
                "Часы телефона ${if (skewSec > 0) "спешат" else "отстают"} примерно на $min мин. Серверы VPN (VMess, Reality) отклоняют подключение с неверным временем - поэтому пинга нет.\n\nВключите в настройках телефона «Дата и время → Время сети» (автоматически) и проверьте снова."
            } else {
                "The phone clock is about $min min ${if (skewSec > 0) "fast" else "slow"}. VPN servers (VMess, Reality) reject connections with a wrong time - that's why there is no ping.\n\nTurn on automatic (network) date and time in phone settings and check again."
            }
        }
        // 2. Сеть: открываются ли сами серверы напрямую (TCP).
        val reach = sample.take(6).count { tcpPing(it.link) > 0 }
        return when {
            skewSec == null && reach == 0 -> if (ru) "Похоже, сеть сейчас не пускает ни к серверам, ни к Google. Проверьте интернет или попробуйте другую сеть (мобильную вместо Wi-Fi)." else "The network seems to block both the servers and Google. Check the internet or try another network."
            reach == 0 -> if (ru) "Серверы из списка недоступны из этой сети (блокировка или сервера выключены). Обновите подписку или попробуйте WARP / обход DPI." else "Servers from the list are unreachable from this network. Update the subscription or try WARP / DPI bypass."
            else -> if (ru) "Серверы доступны, но не принимают подключение: сеть режет VPN-протоколы (DPI) или серверы устарели. Включите «Фрагментация TLS» в настройках VPN, обновите подписку или попробуйте WARP." else "Servers are reachable but refuse the connection: the network filters VPN protocols (DPI) or the servers are outdated. Turn on TLS fragmentation, update the subscription or try WARP."
        }
    }

    /** Сами замеры (ядро Go нельзя прервать) - отдельно, чтобы зависший замер не занимал очередь. */
    private val measurePool = Executors.newCachedThreadPool { Thread(it, "vpn-measure").apply { isDaemon = true } }

    /** Серверы, которые проверяются прямо сейчас: повторная проверка того же не ставится в очередь. */
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Предел на одну проверку: дольше - «не ответил», а не вечные «• • •» (4.10.1). */
    private const val PING_LIMIT_MS = 12_000L

    /**
     * Пинг сервера, через который VPN работает СЕЙЧАС: настоящий запрос через локальный прокси
     * запущенного ядра (как в v2rayNG «проверка подключения»). Раньше для подключённого сервера
     * поднималась вторая копия соединения, и на части телефонов она не проходила.
     */
    private fun proxyPing(): Long = runCatching {
        val t0 = System.nanoTime()
        val c = (URL(TEST_URL).openConnection(VpnState.httpProxy()) as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            useCaches = false
            instanceFollowRedirects = false
        }
        try {
            val code = c.responseCode
            if (code !in 200..399) return@runCatching -1L
            ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
        } finally {
            c.disconnect()
        }
    }.getOrDefault(-1L)

    private fun measure(app: Context, p: VpnProfile, activeId: String?): Long {
        // Подключённый сервер - через работающий туннель; не вышло - обычной проверкой.
        if (VpnState.proxyReady && p.id == activeId) {
            val ms = proxyPing()
            if (ms > 0) return ms
        }
        val f = measurePool.submit(java.util.concurrent.Callable { realPing(app, p.link) })
        return try {
            f.get(PING_LIMIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            f.cancel(true)
            -1L
        }
    }

    fun pingAsync(context: Context, profiles: List<VpnProfile>, each: (VpnProfile, Long) -> Unit) {
        val app = context.applicationContext
        val activeId = runCatching { VpnStore(app).selectedId }.getOrNull()
        profiles.forEach { p ->
            // Уже проверяется - не ставим второй раз (раньше очередь копилась, и «• • •» мигали
            // до перезапуска приложения). Ответ первой проверки придёт и для этой строки.
            val queued = synchronized(inFlight) {
                if (inFlight.add(p.id)) {
                    false
                } else {
                    waiters.getOrPut(p.id) { java.util.concurrent.CopyOnWriteArrayList() }.add { ms -> each(p, ms) }
                    true
                }
            }
            if (queued) return@forEach
            pingPool.execute {
                val ms = try {
                    measure(app, p, activeId)
                } catch (e: Throwable) {
                    -1L
                }
                val extra = synchronized(inFlight) {
                    inFlight.remove(p.id)
                    waiters.remove(p.id)
                }
                main.post {
                    each(p, ms)
                    extra?.forEach { runCatching { it(ms) } }
                }
            }
        }
    }

    private val waiters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>>()
}

/** Загрузка подписок. */
object VpnSubscriptions {
    private const val AUTO_MS = 6 * 60 * 60 * 1000L

    /**
     * Автообновление: подписки, которые не обновлялись больше 6 часов, обновляются сами в фоне
     * (при запуске приложения и открытии VPN). [done] - сколько подписок обновлено.
     */
    fun autoUpdate(context: Context, done: ((Int) -> Unit)? = null) {
        val store = VpnStore(context)
        runCatching { store.ensureDefaults() }
        runCatching { cleanupBuiltinOnce(context) }
        val now = System.currentTimeMillis()
        val due = store.subscriptions().filter { now - it.updated > AUTO_MS }
        if (due.isEmpty()) {
            done?.invoke(0)
            return
        }
        var left = due.size
        var ok = 0
        for (s in due) update(context, s) { r ->
            if (r.isSuccess) ok++
            if (--left == 0) done?.invoke(ok)
        }
    }

    private val pool = Executors.newSingleThreadExecutor { Thread(it, "vpn-sub").apply { isDaemon = true } }

    /**
     * Чистка мёртвых встроенных серверов (один раз за версию, в фоне): каждый встроенный
     * сервер проверяется дважды с паузой; не ответил оба раза - удаляется. Серверы подписок
     * не трогаем - их и так заменяет обновление подписки.
     */
    private fun cleanupBuiltinOnce(context: Context) {
        val app = context.applicationContext
        val p = app.getSharedPreferences("vpn", Context.MODE_PRIVATE)
        if (p.getBoolean("builtin_clean_499", false)) return
        if (GoGuard.conflict(GoGuard.XRAY) || !XrayCore.supported) return
        p.edit().putBoolean("builtin_clean_499", true).apply()
        Thread({
            runCatching {
                val store = VpnStore(app)
                val mine = store.profiles().filter { it.subId == VpnStore.BUILTIN_SUB }
                if (mine.isEmpty()) return@runCatching
                val dead = mine.filter { pr -> XrayCore.realPing(app, pr.link) < 0 }
                    .filter { pr ->
                        Thread.sleep(1500)
                        XrayCore.realPing(app, pr.link) < 0
                    }
                // Сеть пропала во время проверки (все «мёртвые») - ничего не удаляем.
                if (dead.isEmpty() || dead.size == mine.size) return@runCatching
                val ids = dead.map { it.id }.toHashSet()
                store.saveProfiles(store.profiles().filterNot { it.id in ids })
                if (store.selectedId in ids) store.selectedId = store.profiles().firstOrNull()?.id.orEmpty()
                android.util.Log.i("VpnCleanup", "removed ${dead.size} dead built-in servers")
            }
        }, "vpn-cleanup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Сколько обновлений подряд подписка не загрузилась (3 и больше - «мёртвая» ссылка). */
    fun failCount(context: Context, sub: VpnSubscription): Int =
        context.applicationContext.getSharedPreferences("vpn", Context.MODE_PRIVATE).getInt("subfail_" + sub.id, 0)
    private val main = Handler(Looper.getMainLooper())

    fun fetch(url: String, userAgent: String = ""): Pair<String, String> {
        val conn = URL(url).openConnection(VpnState.httpProxy()) as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", userAgent.ifBlank { "v2rayNG/1.10.0" })
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val title = conn.getHeaderField("profile-title")?.let { t ->
                if (t.startsWith("base64:")) VpnLinks.decodeBase64(t.removePrefix("base64:")) else t
            }.orEmpty()
            // Огромные агрегаторы (десятки мегабайт) читаем не целиком: предел 8 МБ - иначе
            // разбор на телефоне кончался нехваткой памяти (урок проекта Nova).
            val limit = 8 * 1024 * 1024
            val sb = StringBuilder()
            conn.inputStream.bufferedReader().use { r ->
                val buf = CharArray(16 * 1024)
                while (sb.length < limit) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
            }
            // Обрезали посередине строки - последнюю (неполную) отбрасываем.
            val body = if (sb.length >= limit) sb.substring(0, sb.lastIndexOf('\n').coerceAtLeast(0)) else sb.toString()
            return body to title
        } finally {
            conn.disconnect()
        }
    }

    /** Копии «умных» YAML-подписок в приложении: сайт недоступен - берём встроенную. */
    private val BUNDLED = mapOf("pozor.bond" to "vpn_pozor_v1156.yaml")

    private fun fetchOrBundled(context: Context, url: String): Pair<String, String> = try {
        fetch(url, if (BUNDLED.keys.any { it in url }) "clash.meta" else "")
    } catch (e: Exception) {
        val asset = BUNDLED.entries.firstOrNull { it.key in url }?.value ?: throw e
        Log.w("VpnSubs", "$url: $e - встроенная копия")
        context.assets.open(asset).bufferedReader().use { it.readText() } to ""
    }

    /**
     * Провайдеры из YAML (proxy-providers): каждый загружается (до 4 сразу, своя метка
     * User-Agent), отбор по filter/exclude-filter, приставка к имени. Каскады (dialer-proxy)
     * пропускаются - их серверы те же, что у обычных провайдеров.
     */
    private fun expandProviders(list: List<VpnYaml.Provider>, subId: String): List<VpnProfile> {
        val use = list.filter { !it.chained }.distinctBy { it.url }.take(20)
        if (use.isEmpty()) return emptyList()
        val par = Executors.newFixedThreadPool(4) { Thread(it, "vpn-prov").apply { isDaemon = true } }
        return try {
            val futures = use.map { p ->
                par.submit(java.util.concurrent.Callable {
                    runCatching {
                        val (b, _) = fetch(p.url, p.userAgent.ifBlank { "clash.meta" })
                        VpnLinks.parseMany(b, subId).filter { p.accepts(it.name) }
                            .map { if (p.prefix.isNotBlank() && !it.name.startsWith(p.prefix)) it.copy(name = p.prefix + it.name) else it }
                    }.onFailure { Log.w("VpnSubs", "provider ${p.name}: $it") }.getOrDefault(emptyList())
                })
            }
            val deadline = System.currentTimeMillis() + 60_000L
            futures.flatMap { f ->
                runCatching { f.get((deadline - System.currentTimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
            }
        } finally {
            par.shutdownNow()
        }
    }

    /** Обновить подписку: старые серверы этой подписки заменяются новыми. */
    fun update(context: Context, sub: VpnSubscription, done: (Result<Int>) -> Unit) {
        val store = VpnStore(context)
        pool.execute {
            val r = runCatching {
                val (body, title) = fetchOrBundled(context, sub.url)
                // YAML Clash/Mihomo: WireGuard/AmneziaWG из него - в готовые конфиги WARP, а
                // proxy-providers (умные конфиги вроде Pozor) - загружаются и сливаются в эту подписку.
                var warpAdded = 0
                val extra = ArrayList<VpnProfile>()
                if (VpnYaml.looksLike(body)) {
                    val y = runCatching { VpnYaml.convert(body) }.getOrNull()
                    if (y != null) {
                        warpAdded = runCatching { WarpSeeds.addUser(context, y.warp) }.getOrDefault(0)
                        extra += expandProviders(y.providers, sub.id)
                    }
                }
                // Повторы (тот же сервер под другим именем) отсеиваются по отпечатку ссылки;
                // больше 3000 серверов из одной подписки не берём - список становится неподъёмным.
                val list = (VpnLinks.parseMany(body, sub.id) + extra).distinctBy { it.link.substringBefore('#') }.take(3000)
                if (list.isEmpty() && warpAdded > 0) return@runCatching 0
                if (list.isEmpty()) throw IllegalStateException("no servers")
                val selectedLink = store.selected?.link
                val others = store.profiles().filter { it.subId != sub.id }
                store.saveProfiles(others + list)
                list.firstOrNull { it.link == selectedLink }?.let { store.selectedId = it.id }
                if (store.selected == null) list.firstOrNull()?.let { store.selectedId = it.id }
                store.saveSubscriptions(
                    store.subscriptions().map {
                        if (it.id == sub.id) it.copy(updated = System.currentTimeMillis(), name = it.name.ifBlank { title }) else it
                    },
                )
                list.size
            }
            // Подписка, которая раз за разом не загружается, помечается «не обновляется».
            val p = context.applicationContext.getSharedPreferences("vpn", android.content.Context.MODE_PRIVATE)
            if (r.isSuccess) p.edit().remove("subfail_" + sub.id).apply()
            else p.edit().putInt("subfail_" + sub.id, p.getInt("subfail_" + sub.id, 0) + 1).apply()
            main.post { done(r) }
        }
    }
}
