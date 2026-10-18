// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Стратегия обхода DPI (параметры движка ByeDPI). Подбираются под провайдера: одна и та же
 * стратегия у одного оператора работает, у другого - нет, поэтому есть автоподбор.
 */
data class DpiStrategy(val id: String, val name: String, val args: String, val hint: String) {
    private val en: Boolean get() = java.util.Locale.getDefault().language != "ru"

    /** Название на языке интерфейса (каталог - на русском, для английского - перевод). */
    val title: String
        get() = if (!en) name else DpiStrategyEn.map[id]?.first ?: name.replace("Из сети №", "Online #")

    /** Подсказка «для чего» на языке интерфейса. */
    val note: String
        get() = if (!en) hint else DpiStrategyEn.map[id]?.second ?: hint
}

private object DpiStrategyEn {
    val map = mapOf(
        "cascade_10" to ("Cascade split (10 steps + TLS record)" to "YouTube, websites"),
        "cascade_10_md5" to ("Cascade 10 + MD5 + fake UDP" to "TSPU, YouTube"),
        "cascade_double_auto" to ("Double cascade with auto-switch" to "YouTube 4K, home internet"),
        "multilevel_cascade" to ("Multilevel cascade (disorder + split)" to "YouTube, websites"),
        "double_cascade" to ("Double cascade" to "Tough filters"),
        "progressive_split" to ("Progressive multisplit" to "YouTube, browser"),
        "fast_tlsrec" to ("Fast (TLS record)" to "High speed, voice"),
        "oob_cascade_sack" to ("OOB cascade + SACK drop (auto)" to "Mobile carriers"),
        "fake_ttl8" to ("Fake SNI + TTL 8" to "All services"),
        "fake_tlsrec_ttl8" to ("Fake + TLS record (TTL 8)" to "TSPU, YouTube"),
        "fake_low_ttl" to ("Fake with low TTL (2)" to "Mobile carriers"),
        "fake_google_clean" to ("Fake SNI + TLS split" to "YouTube"),
        "sni_google_multisplit" to ("Fake SNI + multisplit + OOB" to "TSPU"),
        "split50_multi_ttl" to ("Multisplit up to 60 steps + fake TTL 2" to "Mobile internet"),
        "oob_fake_split11" to ("OOB + fake SNI + MD5" to "YouTube, TSPU"),
        "disoob_minimal" to ("Minimal (light)" to "Games, calls"),
        "fake_cascade_http" to ("Fake + HTTP Host + TLS record" to "HTTP and HTTPS sites"),
        "fibonacci" to ("Ultra cascade, 12 steps" to "YouTube, TSPU"),
        "fake_low_ttl_md5" to ("Fake SNI + TTL 3 + MD5" to "Home internet"),
        "oob_multisplit_10" to ("OOB + multisplit (10 steps)" to "YouTube"),
        "disorder_reverse" to ("Reverse disorder cascade" to "TSPU"),
        "classic_fake_split" to ("Classic fake + split" to "Universal"),
        "combo_oob_disorder" to ("OOB + disorder + TLS record" to "Tough blocking"),
        "mixed_split" to ("Mixed split" to "YouTube"),
        "disoob_fake_sack" to ("OOB + fake + SACK drop" to "Home internet"),
        "heavy" to ("Maximum: fake + OOB + TLS record" to "Tough blocking"),
        "split50" to ("Split 50 + TLS record" to "Voice, websites"),
        "simple" to ("Simple (minimal load)" to "High speed"),
        "disorder7" to ("Disorder 7 + split 2" to "Light blocking"),
        "classic" to ("Classic split 1 + disorder 1" to "Light blocking"),
    )
}

/** Каталог проверенных стратегий (на основе ByeDPI Russia, MIT). */
object DpiStrategies {
    val list = listOf(
        DpiStrategy("cascade_10", "Каскадный сплит (10 шагов + TLS-запись)", "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s", "YouTube, сайты"),
        DpiStrategy("cascade_10_md5", "Каскад 10 + MD5 + фейковый UDP", "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1", "ТСПУ, YouTube"),
        DpiStrategy("cascade_double_auto", "Двойной каскад с автопереключением", "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -a1", "YouTube 4K, домашний интернет"),
        DpiStrategy("multilevel_cascade", "Многоуровневый каскад (перестановка + сплит)", "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s", "YouTube, сайты"),
        DpiStrategy("double_cascade", "Двойной каскад", "-d1 -s1+s -d1+s -s3+s -d6+s -s12+s -d14+s -s20+s -d24+s -s30+s", "Сложные фильтры"),
        DpiStrategy("progressive_split", "Прогрессивный мультисплит", "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s", "YouTube, браузер"),
        DpiStrategy("fast_tlsrec", "Быстрый (TLS-запись)", "-s1 -d3+s -r1+s", "Высокая скорость, голос"),
        DpiStrategy("oob_cascade_sack", "OOB-каскад + сброс SACK (авто)", "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1", "Мобильные операторы"),
        DpiStrategy("fake_ttl8", "Фейковый SNI + TTL 8", "-f-1 -n www.google.com -s2+s -r3 -t4", "Все сервисы"),
        DpiStrategy("fake_tlsrec_ttl8", "Фейк + TLS-запись (TTL 8)", "-d1 -s1+s -r1+s -f-1 -t8", "ТСПУ, YouTube"),
        DpiStrategy("fake_low_ttl", "Фейк с низким TTL (2)", "-d1 -s1+s -r1+s -f-1 -t2", "Мобильные операторы"),
        DpiStrategy("fake_google_clean", "Фейковый SNI + сплит TLS", "-n www.google.com -f-1 -r1+s", "YouTube"),
        DpiStrategy("sni_google_multisplit", "Фейковый SNI + мультисплит + OOB", "-n www.google.com -f-1 -s1+s -d1 -s3+s -s5+s -q7 -a1 -As -o2 -f-1 -a1 -As -r5 -Mh -s1+s -s3+s -a1", "ТСПУ"),
        DpiStrategy("split50_multi_ttl", "Мультисплит до 60 шагов + фейк TTL 2", "-d1+s -s50+s -a1 -As -f-1 -t2 -r2+s -a1 -At -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1", "Мобильный интернет"),
        DpiStrategy("oob_fake_split11", "OOB + фейковый SNI + MD5", "-o1 -a1 -At,r,s -f-1 -a1 -At,r,s -d1+s -s11+s -S -a1 -At,r,s -n www.google.com -f-1 -d1+s -s1+s -S -a1", "YouTube, ТСПУ"),
        DpiStrategy("disoob_minimal", "Минимальный (лёгкий)", "-d1 -s1 -q1 -a1", "Игры, звонки"),
        DpiStrategy("fake_cascade_http", "Фейк + HTTP Host + TLS-запись", "-f-1 -s3+s -a1 -As -d1 -s4+s -s8+h -d6+h -a1 -At,r,s -o2 -r5 -Mh -r6+h -s2+s -s3+s -a1", "Сайты по HTTP и HTTPS"),
        DpiStrategy("fibonacci", "Ультра-каскад 12 шагов", "-d1 -s1+s -d2+s -s3+s -d5+s -s8+s -d12+s -s17+s -d23+s -s30+s -d40+s -s50+s -r1+s -a1", "YouTube, ТСПУ"),
        DpiStrategy("fake_low_ttl_md5", "Фейковый SNI + TTL 3 + MD5", "-f-1 -t3 -n www.google.com -d1 -s2+s -r1+s -S -a1", "Домашний интернет"),
        DpiStrategy("oob_multisplit_10", "OOB + мультисплит (10 шагов)", "-o1 -s1+s -s3+s -s6+s -s10+s -s15+s -s21+s -s28+s -s36+s -s45+s -s55+s -a1", "YouTube"),
        DpiStrategy("disorder_reverse", "Обратный каскад перестановки", "-d35+s -d30+s -d25+s -d20+s -d15+s -d10+s -d5+s -d1+s -s1+s -r1+s -a1", "ТСПУ"),
        DpiStrategy("classic_fake_split", "Классический фейк + сплит", "-f -1 -t 8 -s 1+s -d 3+s", "Универсально"),
        DpiStrategy("combo_oob_disorder", "OOB + перестановка + TLS-запись", "-o1 -d1 -r1+s -s1+s -d3+s", "Сложные блокировки"),
        DpiStrategy("mixed_split", "Смешанный сплит", "-d1 -s4 -d8 -s1+s -d5+s -s10+s -d20+s", "YouTube"),
        DpiStrategy("disoob_fake_sack", "OOB + фейк + сброс SACK", "-q1+s -s29+s -o5+s -f-1 -S", "Домашний интернет"),
        DpiStrategy("heavy", "Максимальный: фейк + OOB + TLS-запись", "-d1 -s1+s -r1+s -e1 -o1+s -f-1 -t2", "Сложные блокировки"),
        DpiStrategy("split50", "Сплит 50 + TLS-запись", "-d1+s -s50+s -r2+s", "Голос, сайты"),
        DpiStrategy("simple", "Простой (минимум нагрузки)", "-d1 -s3+s", "Высокая скорость"),
        DpiStrategy("disorder7", "Перестановка 7 + сплит 2", "-d7 -s2", "Лёгкие блокировки"),
        DpiStrategy("classic", "Классический сплит 1 + перестановка 1", "-s 1 -d 1", "Лёгкие блокировки"),
    )

    /** Скачанные из открытых источников (см. [DpiSources]). */
    @Volatile
    var downloaded: List<DpiStrategy> = emptyList()
        private set
    @Volatile
    private var loaded = false

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        downloaded = DpiSources.load(ctx)
    }

    internal fun setDownloaded(list: List<DpiStrategy>) {
        downloaded = list
        loaded = true
    }

    /** Все стратегии: встроенные и скачанные. */
    fun all(ctx: Context): List<DpiStrategy> {
        ensureLoaded(ctx)
        return list + downloaded
    }

    fun byId(id: String?): DpiStrategy = list.firstOrNull { it.id == id } ?: downloaded.firstOrNull { it.id == id } ?: list[0]
}

/**
 * Стратегии обхода DPI из открытых источников: списки, которые ведут сообщества ByeDPI
 * (каждая строка - параметры движка). Скачиваются по нажатию (через VPN, если он включён),
 * проверяются на опасные ключи и добавляются к встроенным - «Автоподбор» проверит и их.
 * Можно добавить свой источник - любой адрес текстового списка.
 */
object DpiSources {
    class Source(val name: String, val urls: List<String>)

    val BUILT_IN = listOf(
        Source(
            "ByeByeDPI (romanvht)",
            listOf(
                "https://raw.githubusercontent.com/romanvht/ByeByeDPI/master/app/src/main/assets/proxytest_strategies.list",
                "https://cdn.jsdelivr.net/gh/romanvht/ByeByeDPI@master/app/src/main/assets/proxytest_strategies.list",
            ),
        ),
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("dpi_sources", Context.MODE_PRIVATE)

    /** Свои источники (адреса, по одному в строке). */
    fun custom(ctx: Context): List<String> =
        prefs(ctx).getString("custom", "").orEmpty().lines().map { it.trim() }.filter { it.startsWith("http") }

    fun setCustom(ctx: Context, urls: List<String>) = prefs(ctx).edit().putString("custom", urls.joinToString("\n")).apply()

    fun lastUpdate(ctx: Context): Long = prefs(ctx).getLong("updated", 0L)

    internal fun load(ctx: Context): List<DpiStrategy> = runCatching {
        val arr = org.json.JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DpiStrategy(o.getString("id"), o.getString("n"), o.getString("a"), o.optString("h"))
        }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, list: List<DpiStrategy>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("n", it.name).put("a", it.args).put("h", it.hint)) }
        prefs(ctx).edit().putString("list", arr.toString()).putLong("updated", System.currentTimeMillis()).apply()
    }

    /** Привести строку из списка к параметрам нашего движка. null - строка не подходит. */
    fun normalize(line: String): String? {
        var s = line.trim()
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) return null
        // Шаблон «подставьте имя сайта» - подставляем нейтральный.
        s = s.replace("{sni}", "www.google.com").replace("{host}", "www.google.com")
        // Только параметры ciadpi.
        if (!s.startsWith("-")) return null
        val clean = DpiEngine.sanitize(s)
        return clean.takeIf { it.length in 3..600 }
    }

    /**
     * Скачать все источники. Возвращает (новых, всего скачанных, ошибки).
     * Вызывать в фоне.
     */
    fun download(ctx: Context): Triple<Int, Int, List<String>> {
        val errors = ArrayList<String>()
        val found = LinkedHashMap<String, String>() // параметры -> источник
        val sources = BUILT_IN + custom(ctx).map { Source(android.net.Uri.parse(it).host ?: it, listOf(it)) }
        for (src in sources) {
            var ok = false
            for (url in src.urls) {
                if (ok) break
                runCatching {
                    val conn = java.net.URL(url).openConnection(VpnState.httpProxy()) as java.net.HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("User-Agent", "S-messenger")
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    text.lines().mapNotNull { normalize(it) }.forEach { a -> if (!found.containsKey(a)) found[a] = src.name }
                    ok = true
                }.onFailure { errors += "${src.name}: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
        val builtInArgs = DpiStrategies.list.map { DpiEngine.sanitize(it.args) }.toSet()
        val old = load(ctx)
        val oldArgs = old.map { it.args }.toSet()
        val fresh = found.filterKeys { it !in builtInArgs }
        val merged = ArrayList<DpiStrategy>()
        var n = 0
        for ((args, from) in fresh) {
            n++
            val id = "dl_" + Integer.toHexString(args.hashCode())
            merged += DpiStrategy(id, "Из сети №$n · ${from.substringBefore(" (")}", args, from)
        }
        if (merged.isNotEmpty()) {
            save(ctx, merged)
            DpiStrategies.setDownloaded(merged)
        }
        return Triple(merged.count { it.args !in oldArgs }, merged.size, errors)
    }
}

/**
 * Движок обхода DPI - ByeDPI (ciadpi, MIT). Работает прямо на телефоне, без серверов:
 * режет и переставляет первые пакеты соединения (имя сайта, SNI), подмешивает фейковые
 * пакеты с коротким TTL - оборудование провайдера (DPI/ТСПУ) не узнаёт сайт и не тормозит
 * его, а сам сайт получает обычные данные. Трафик никуда не уходит, скорость - скорость
 * вашего интернета. Движок - отдельная программа в папке библиотек приложения
 * (libciadpi.so), работает как локальный SOCKS5-прокси 127.0.0.1:[PORT].
 */
object DpiEngine {
    private const val TAG = "DpiEngine"
    const val PORT = 10818

    @Volatile
    private var proc: Process? = null

    private fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libciadpi.so")
    private fun pidFile(ctx: Context, port: Int) = File(ctx.filesDir, "ciadpi-$port.pid")

    fun available(ctx: Context): Boolean = binary(ctx).exists()

    /** Разбор параметров: пробелы разделяют, всё остальное - как есть. */
    fun split(args: String): List<String> = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Проверка параметров без запуска: пустые строки и опасные ключи отбрасываем. */
    fun sanitize(args: String): String = split(args)
        .filterNot { it == "-D" || it == "--daemon" || it.startsWith("-i") || it.startsWith("-p") || it.startsWith("-w") }
        .joinToString(" ")

    /** Процесс, оставшийся от прошлого запуска (приложение закрыли без остановки VPN). */
    private fun killStale(ctx: Context, port: Int) {
        val f = pidFile(ctx, port)
        val pid = runCatching { f.readText().trim().toInt() }.getOrNull()
        if (pid != null && pid > 0) runCatching { android.os.Process.killProcess(pid) }
        f.delete()
    }

    private fun waitPort(port: Int, timeoutMs: Long): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
                return true
            } catch (_: Exception) {
                Thread.sleep(60)
            }
        }
        return false
    }

    /** Запустить движок. Бросает исключение с понятной причиной, если не получилось. */
    fun start(ctx: Context, args: String, port: Int = PORT): Process {
        if (port == PORT) stop(ctx)
        killStale(ctx, port)
        val bin = binary(ctx)
        if (!bin.exists()) throw IllegalStateException("движок обхода DPI не найден в сборке")
        val cmd = arrayListOf(bin.absolutePath, "-i", "127.0.0.1", "-p", port.toString(), "-w", pidFile(ctx, port).absolutePath)
        cmd += split(sanitize(args))
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        // Вывод движка - в журнал (ошибки параметров видны в «Журнале ошибок»).
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { Log.w(TAG, it.take(300)) }
            }
        }, "ciadpi-log").apply { isDaemon = true }.start()
        if (!waitPort(port, 2500)) {
            val alive = runCatching { p.exitValue(); false }.getOrDefault(true)
            p.destroy()
            throw IllegalStateException(if (alive) "движок обхода DPI не отвечает" else "движок обхода DPI не принял параметры стратегии")
        }
        if (port == PORT) proc = p
        Log.i(TAG, "started on $port: $args")
        return p
    }

    fun stop(ctx: Context? = null) {
        proc?.let { runCatching { it.destroy() } }
        proc = null
        ctx?.let { killStale(it, PORT) }
    }

    val running: Boolean
        get() = proc?.let { runCatching { it.exitValue(); false }.getOrDefault(true) } == true
}

/**
 * Автоподбор стратегии: каждая стратегия по очереди запускается на тестовом порту, через неё
 * открываются защищённые соединения с заблокированными/замедленными сайтами. Побеждает та,
 * что открыла больше всего сайтов и быстрее всех.
 */
object DpiBenchmark {
    private const val TEST_PORT = 11080
    private val main = Handler(Looper.getMainLooper())

    /** Что проверяем: сервис и его адреса. tcp = true - просто соединение (узлы Tox). */
    class Target(val host: String, val fallback: String, val port: Int = 443, val tcp: Boolean = false)
    class Service(val key: String, val titleRes: Int, val emoji: String, val targets: List<Target>)

    val SERVICES = listOf(
        Service("youtube", com.stoyko.smessenger.R.string.svc_youtube, "▶️", listOf(Target("www.youtube.com", "142.250.74.46"), Target("i.ytimg.com", "142.250.74.54"), Target("redirector.googlevideo.com", "142.250.74.78"))),
        Service("discord", com.stoyko.smessenger.R.string.svc_discord, "🎮", listOf(Target("discord.com", "162.159.137.232"), Target("gateway.discord.gg", "162.159.135.234"))),
        Service("instagram", com.stoyko.smessenger.R.string.svc_instagram, "📷", listOf(Target("www.instagram.com", "157.240.205.174"))),
        Service("ai", com.stoyko.smessenger.R.string.svc_ai, "✦", listOf(Target("chatgpt.com", "104.18.32.47"), Target("duck.ai", "52.149.246.39"))),
        Service("tox", com.stoyko.smessenger.R.string.svc_tox, "🔗", emptyList()),
    )

    /** Для совместимости со старыми экранами: число проверяемых сервисов. */
    val TARGETS: List<Service> get() = SERVICES

    /** Результат стратегии: сколько сервисов открылось, средняя задержка и что именно работает. */
    data class Result(val strategy: DpiStrategy, val ok: Int, val avgMs: Long, val services: Map<String, Boolean> = emptyMap())

    /** Подбор идёт (в том числе в фоне, при закрытом окне). */
    @Volatile
    var running = false
        private set

    /** Последний результат подбора (для окна, открытого заново). */
    @Volatile
    var lastBest: Result? = null
        private set

    /**
     * Соединения и процесс движка, которые сейчас идут при подборе. «Остановить» закрывает их
     * сразу - подбор не ждёт тайм-аутов (раньше остановка тянулась десятки секунд и казалось,
     * что кнопка не работает).
     */
    private val live = java.util.Collections.synchronizedSet(HashSet<Socket>())

    @Volatile
    private var testProc: Process? = null

    /** Прервать идущие проверки (вызывается в фоне). */
    private fun abort() {
        val socks = synchronized(live) { live.toList().also { live.clear() } }
        socks.forEach { runCatching { it.close() } }
        testProc?.let { runCatching { it.destroy() } }
    }

    private fun track(s: Socket): Socket = s.also { live += it }

    private fun resolve(host: String, fallback: String): String = runCatching {
        InetAddress.getAllByName(host).firstOrNull { it is java.net.Inet4Address }?.hostAddress
    }.getOrNull() ?: fallback

    /** Защищённое соединение через прокси движка: рукопожатие TLS и первые байты ответа. */
    private fun probe(host: String, ip: String, port: Int, targetPort: Int = 443): Long? {
        val start = System.currentTimeMillis()
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            raw = track(Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))))
            raw.soTimeout = 4000
            raw.connect(InetSocketAddress(ip, targetPort), 3000)
            ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, targetPort, true) as SSLSocket
            track(ssl)
            ssl.soTimeout = 4000
            // Имя сайта (SNI) берётся из host - как в обычном браузере.
            ssl.startHandshake()
            ssl.outputStream.write("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            ssl.outputStream.flush()
            val n = ssl.inputStream.read(ByteArray(512))
            if (n > 0) System.currentTimeMillis() - start else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { ssl?.close() }
            runCatching { raw?.close() }
            ssl?.let { live -= it }
            raw?.let { live -= it }
        }
    }

    /** Узел Tox: TCP-соединение через движок (релеи Tox работают поверх TCP). */
    private fun probeTcp(ip: String, targetPort: Int, port: Int): Long? {
        val start = System.currentTimeMillis()
        var raw: Socket? = null
        return try {
            raw = track(Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))))
            raw.soTimeout = 3000
            raw.connect(InetSocketAddress(ip, targetPort), 3000)
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            null
        } finally {
            runCatching { raw?.close() }
            raw?.let { live -= it }
        }
    }

    /** Узлы Tox для проверки - из списка узлов мессенджера (TCP-порты релеев). */
    private fun toxTargets(ctx: Context): List<Target> = runCatching {
        val reg = (ctx.applicationContext as com.stoyko.smessenger.App).component.nodeRegistry()
        reg.get(12).filter { it.tcpPorts.isNotEmpty() }.take(3).map { n -> Target(n.address, n.address, n.tcpPorts.first(), tcp = true) }
    }.getOrDefault(emptyList())

    /**
     * Запуск подбора в фоне. [progress] - (номер, всего, название, сервисы текущей стратегии),
     * [done] - лучшая стратегия (или null) и все результаты. Отмена - [cancel].set(true).
     * Окно можно закрыть - подбор продолжится, в конце придёт уведомление и лучшая стратегия
     * применится сама ([applyWhenDone]).
     */
    fun run(
        ctx: Context,
        cancel: AtomicBoolean,
        progress: (Int, Int, String, Map<String, Boolean>) -> Unit,
        done: (Result?, List<Result>) -> Unit,
        list: List<DpiStrategy> = DpiStrategies.list,
    ) {
        val app = ctx.applicationContext
        running = true
        // Кнопка «Остановить» в шторке: подбор заканчивается, лучшее из найденного применяется.
        // Идущие проверки при этом прерываются сразу (сокеты закрываются, движок останавливается).
        val shadeStop = BgJobs.begin(BgJobs.DPI) { abort() }
        val title = app.getString(com.stoyko.smessenger.R.string.bg_dpi_title)
        fun stopped() = cancel.get() || shadeStop.get()
        val over = AtomicBoolean(false)
        // «Отмена» в окне ставит только флаг - следим за ним и тоже прерываем проверки сразу.
        Thread({
            while (!over.get()) {
                if (cancel.get()) {
                    abort()
                    break
                }
                runCatching { Thread.sleep(200) }
            }
        }, "dpi-benchmark-watch").apply { isDaemon = true }.start()
        Thread({
            try {
                val results = ArrayList<Result>()
                val tox = toxTargets(app)
                val services = SERVICES.map { s -> if (s.key == "tox") Service(s.key, s.titleRes, s.emoji, tox) else s }.filter { it.targets.isNotEmpty() }
                val resolved = services.associate { s -> s.key to s.targets.map { it to resolve(it.host, it.fallback) } }
                var bestSoFar: Result? = null
                for ((i, s) in list.withIndex()) {
                    if (stopped()) break
                    main.post { progress(i + 1, list.size, s.title, emptyMap()) }
                    BgJobs.progress(
                        app, BgJobs.DPI, title,
                        app.getString(com.stoyko.smessenger.R.string.bg_dpi_text, s.title, bestSoFar?.let { b -> b.strategy.title + " (" + summary(app, b) + ")" } ?: "-"),
                        i, list.size, shadeStop,
                    )
                    var p: Process? = null
                    try {
                        p = DpiEngine.start(app, s.args, TEST_PORT)
                        testProc = p
                        if (stopped()) break
                        val ok = LinkedHashMap<String, Boolean>()
                        var total = 0L
                        var n = 0
                        for (svc in services) {
                            if (stopped()) break
                            var any = false
                            for ((tg, ip) in resolved[svc.key].orEmpty()) {
                                if (stopped()) break
                                val ms = if (tg.tcp) probeTcp(ip, tg.port, TEST_PORT) else probe(tg.host, ip, TEST_PORT, tg.port)
                                if (ms != null) {
                                    any = true
                                    total += ms
                                    n++
                                    break
                                }
                            }
                            ok[svc.key] = any
                            val snapshot = HashMap(ok)
                            main.post { progress(i + 1, list.size, s.title, snapshot) }
                        }
                        // Остановили посреди стратегии - её неполный результат не считаем.
                        if (stopped()) break
                        val r = Result(s, ok.count { it.value }, if (n > 0) total / n else Long.MAX_VALUE, ok)
                        results += r
                        if (bestSoFar == null || better(r, bestSoFar!!)) bestSoFar = r
                        // Все сервисы открылись - дальше искать незачем.
                        if (r.ok == services.size) break
                    } catch (e: Exception) {
                        Log.w("DpiBenchmark", "${s.id}: $e")
                        if (!stopped()) results += Result(s, 0, Long.MAX_VALUE)
                    } finally {
                        p?.let { runCatching { it.destroy() } }
                        testProc = null
                        runCatching { File(app.filesDir, "ciadpi-$TEST_PORT.pid").delete() }
                        if (!stopped()) runCatching { Thread.sleep(120) }
                    }
                }
                val best = results.filter { it.ok > 0 }.sortedWith(compareByDescending<Result> { it.ok }.thenBy { it.avgMs }).firstOrNull()
                lastBest = best
                over.set(true)
                running = false
                if (cancel.get()) {
                    // Отменено в окне - без итогового уведомления.
                    BgJobs.clear(app, BgJobs.DPI, shadeStop)
                } else {
                    // Закончился или остановлен из шторки: итог (лучшее из найденного применит [done] -
                    // раньше при остановке из шторки стратегия применялась дважды).
                    BgJobs.finish(
                        app, BgJobs.DPI, app.getString(com.stoyko.smessenger.R.string.dpi_auto_done),
                        if (best == null) app.getString(com.stoyko.smessenger.R.string.dpi_auto_none) else best.strategy.title + "\n" + summary(app, best),
                        shadeStop,
                    )
                }
                main.post { done(best, results) }
            } catch (e: Throwable) {
                // Что бы ни случилось - подбор не должен навсегда остаться «идущим».
                Log.w("DpiBenchmark", "run: $e")
                over.set(true)
                testProc = null
                running = false
                BgJobs.clear(app, BgJobs.DPI, shadeStop)
                main.post { done(null, emptyList()) }
            }
        }, "dpi-benchmark").apply { isDaemon = true }.start()
    }

    /**
     * Подбор целиком в фоне (без окна): прогресс - в шторке, по окончании лучшая стратегия
     * применяется сама. false - уже идёт или движок недоступен.
     */
    fun runInBackground(ctx: Context, list: List<DpiStrategy> = DpiStrategies.all(ctx)): Boolean {
        if (running || !DpiEngine.available(ctx)) return false
        val app = ctx.applicationContext
        run(app, AtomicBoolean(false), { _, _, _, _ -> }, { best, _ -> applyWhenDone(app, best) }, list)
        return true
    }

    private fun better(a: Result, b: Result) = a.ok > b.ok || (a.ok == b.ok && a.avgMs < b.avgMs)

    /** Подбор закончился, а окно уже закрыто: применяем лучшую стратегию и сообщаем уведомлением. */
    fun applyWhenDone(ctx: Context, best: Result?) {
        val app = ctx.applicationContext
        val store = VpnStore(app)
        if (best != null) {
            store.dpiPicked = true
            store.dpiStrategy = best.strategy.id
            store.dpiArgs = best.strategy.args
            if (VpnState.status == VpnState.Status.On && store.mode == VpnStore.MODE_DPI) SmVpnService.start(app)
        }
        val text = if (best == null) {
            app.getString(com.stoyko.smessenger.R.string.dpi_auto_none)
        } else {
            app.getString(com.stoyko.smessenger.R.string.dpi_bg_done, best.strategy.title) + "\n" + summary(app, best)
        }
        BgJobs.finish(app, BgJobs.DPI, app.getString(com.stoyko.smessenger.R.string.dpi_auto_done), text)
    }

    /** «YouTube ✓ · Discord ✓ · Instagram ✗ …» */
    fun summary(ctx: Context, r: Result): String = SERVICES.filter { r.services.containsKey(it.key) }
        .joinToString(" · ") { s -> ctx.getString(s.titleRes) + if (r.services[s.key] == true) " ✓" else " ✗" }
}
