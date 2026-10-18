// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import com.stoyko.smessenger.R

/**
 * Узлы сети: приложение само берёт свежий список с nodes.tox.chat (раз в 5 часов, в фоне),
 * проверяет, какие узлы отвечают, и запоминает задержку. Ядро подключается сначала
 * к самым быстрым рабочим узлам - загрузка и отправка идут быстрее.
 *
 * Кнопка «Проверить и обновить узлы» показывает отчёт: какие работают, какие нет, сколько новых добавлено.
 */
object NodeHealth {
    private const val TAG = "NodeHealth"
    private const val SOURCE = "https://nodes.tox.chat/json"
    private const val PREFS = "node_health"
    private val main = Handler(Looper.getMainLooper())

    /**
     * Сколько узлов проверять одновременно: на слабых телефонах (2-3 ГБ, 32-битные, как Galaxy J8)
     * 24 потока сразу давали рывки интерфейса и «Tox thread overran» - там 10 (4.10.0).
     */
    private fun probeThreads() = if (com.stoyko.smessenger.ui.LiteMode.weakDevice) 10 else 24

    /** Скачанный список (его читает реестр узлов вместе со встроенным). */
    fun onlineFile(ctx: Context) = File(ctx.filesDir, "online_nodes.json")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Задержка узла в мс по последней проверке; -1 - не отвечал; null - не проверяли. */
    fun ping(ctx: Context, host: String, port: Int): Long? {
        val v = prefs(ctx).getLong("p_$host:$port", Long.MIN_VALUE)
        return if (v == Long.MIN_VALUE) null else v
    }

    private fun savePing(ctx: Context, host: String, port: Int, ms: Long) =
        prefs(ctx).edit().putLong("p_$host:$port", ms).apply()

    /**
     * Откуда брать список узлов (формат nodes.tox.chat). Если официальный сайт недоступен
     * (заблокирован или лежит) - пробуем зеркала: GitHub и CDN jsDelivr (он часто открывается
     * там, где GitHub и nodes.tox.chat заблокированы). Плюс свои адреса пользователя.
     * Списки со всех доступных источников объединяются - узлов становится больше.
     */
    val SOURCES = listOf(
        "https://nodes.tox.chat/json",
        "https://cdn.jsdelivr.net/gh/TokTok/qTox@master/res/nodes.json",
        "https://cdn.jsdelivr.net/gh/qTox/qTox@master/res/nodes.json",
        "https://raw.githubusercontent.com/TokTok/qTox/master/res/nodes.json",
        "https://raw.githubusercontent.com/qTox/qTox/master/res/nodes.json",
        "https://cdn.jsdelivr.net/gh/evilcorpltd/aTox@master/atox/src/main/res/raw/nodes.json",
        "https://raw.githubusercontent.com/evilcorpltd/aTox/master/atox/src/main/res/raw/nodes.json",
        // Ещё зеркала: другие узлы CDN jsDelivr и список клиента Toxygen
        // (часть узлов старая, мёртвые отсеиваются проверкой; повторы убираются).
        "https://fastly.jsdelivr.net/gh/TokTok/qTox@master/res/nodes.json",
        "https://gcore.jsdelivr.net/gh/TokTok/qTox@master/res/nodes.json",
        // cdn.statically.io убран (4.10.0): отвечает «HTTP 301» на http-адрес, такой переход небезопасен.
        "https://raw.githubusercontent.com/toxygen-project/toxygen/master/toxygen/nodes.json",
        "https://cdn.jsdelivr.net/gh/toxygen-project/toxygen@master/toxygen/nodes.json",
    )

    /** Свои источники (адреса JSON в формате nodes.tox.chat), по одному в строке. */
    fun customSources(ctx: Context): List<String> =
        prefs(ctx).getString("custom_sources", "").orEmpty().lines().map { it.trim() }.filter { it.startsWith("http") }

    fun setCustomSources(ctx: Context, text: String) = prefs(ctx).edit().putString("custom_sources", text).apply()

    /** Ход загрузки: что сейчас делаем, доля 0..1. */
    fun interface Progress {
        fun update(text: String, fraction: Float)
    }

    /** Через VPN, если он включён (иначе заблокированные адреса не откроются). */
    private fun openUrl(url: String): HttpURLConnection {
        val proxy = if (com.stoyko.smessenger.vpn.VpnState.running) {
            com.stoyko.smessenger.vpn.VpnState.httpProxy()
        } else {
            java.net.Proxy.NO_PROXY
        }
        return (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "S-messenger")
        }
    }

    /** Результат загрузки: сколько новых узлов, с каких источников удалось, с каких нет. */
    data class DownloadResult(val added: Int, val ok: List<String>, val failed: List<String>, val total: Int)

    /**
     * Скачать свежие списки со всех источников и объединить. added = -1 - не удалось ни с одного.
     * Источники качаются параллельно (по 6 сразу): раньше по одному - медленный или
     * заблокированный источник задерживал всё на десятки секунд.
     */
    fun downloadAll(ctx: Context, progress: Progress? = null): DownloadResult {
        val sources = (customSources(ctx) + SOURCES).distinct()
        val merged = LinkedHashMap<String, JSONObject>()
        val ok = java.util.Collections.synchronizedList(ArrayList<String>())
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())
        val t0 = System.currentTimeMillis()
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "nodes-src").apply { isDaemon = true } }
        val futures = sources.map { url ->
            pool.submit<List<JSONObject>> {
                val host = runCatching { URL(url).host }.getOrDefault(url)
                val started = System.currentTimeMillis()
                try {
                    var c = openUrl(url)
                    var code = c.responseCode
                    // Переадресация на другой https-адрес (HttpURLConnection сам её не всегда проходит);
                    // на http не переходим - список узлов не должен идти открытым текстом.
                    var hops = 0
                    while (code in 300..399 && hops < 4) {
                        val loc = c.getHeaderField("Location") ?: break
                        val next = runCatching { URL(URL(c.url, loc).toString()) }.getOrNull() ?: break
                        c.disconnect()
                        if (next.protocol != "https") throw IllegalStateException("HTTP $code -> ${next.protocol}")
                        c = openUrl(next.toString())
                        code = c.responseCode
                        hops++
                    }
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    val total = c.contentLength.toLong()
                    val buf = java.io.ByteArrayOutputStream()
                    c.inputStream.use { input ->
                        val b = ByteArray(16 * 1024)
                        var lastUi = 0L
                        while (true) {
                            val n = input.read(b)
                            if (n < 0) break
                            buf.write(b, 0, n)
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 150) {
                                lastUi = now
                                val part = if (total > 0) buf.size().toFloat() / total else 0.5f
                                progress?.update(
                                    ctx.getString(
                                        R.string.nodes_progress_download,
                                        done.get() + 1, sources.size, host, buf.size() / 1024, (now - started) / 1000.0,
                                    ),
                                    (done.get() + part.coerceIn(0f, 1f)) / sources.size * 0.4f,
                                )
                            }
                        }
                    }
                    c.disconnect()
                    val arr = JSONObject(buf.toString("UTF-8")).getJSONArray("nodes")
                    if (arr.length() < 3) throw IllegalStateException("too few nodes")
                    ok += host
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "download $url: $e")
                    failed += host
                    emptyList()
                } finally {
                    val k = done.incrementAndGet()
                    progress?.update(
                        ctx.getString(R.string.nodes_progress_sources, ok.size, failed.size, (System.currentTimeMillis() - t0) / 1000.0),
                        k.toFloat() / sources.size * 0.4f,
                    )
                }
            }
        }
        // Порядок объединения - как у списка источников (первые надёжнее).
        for (f in futures) {
            val list = runCatching { f.get(40, TimeUnit.SECONDS) }.getOrDefault(emptyList())
            for (o in list) {
                val key = o.optString("public_key").uppercase()
                if (key.length == 64 && !merged.containsKey(key)) merged[key] = o
            }
        }
        pool.shutdownNow()
        if (merged.isEmpty()) return DownloadResult(-1, ok.toList(), failed.toList(), 0)
        val known = HashSet<String>()
        listOf(rawBuiltIn(ctx), onlineFile(ctx).takeIf { it.exists() }?.readText()).forEach { s ->
            runCatching {
                val a = JSONObject(s ?: return@forEach).getJSONArray("nodes")
                for (i in 0 until a.length()) known += a.getJSONObject(i).optString("public_key").uppercase()
            }
        }
        val added = merged.keys.count { it !in known }
        val out = JSONArray()
        merged.values.forEach { out.put(it) }
        onlineFile(ctx).writeText(JSONObject().put("nodes", out).toString())
        prefs(ctx).edit().putLong("downloaded", System.currentTimeMillis()).apply()
        return DownloadResult(added, ok.toList(), failed.toList(), merged.size)
    }

    /** Совместимость: число новых узлов или -1. */
    fun download(ctx: Context): Int = downloadAll(ctx).added

    private fun rawBuiltIn(ctx: Context): String =
        ctx.resources.openRawResource(R.raw.nodes).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Как часто узлы обновляются сами: скачиваем списки из всех источников и проверяем скорость. */
    const val AUTO_UPDATE_MS = 5 * 3600_000L

    @Volatile
    private var autoRunning = false

    /**
     * Автообновление узлов раз в 5 часов: свежие списки из всех источников, проверка всех узлов
     * (кто жив и как быстро отвечает), затем [onUpdated] - переподключение к самым быстрым.
     * Источники недоступны - всё равно перепроверяем известные узлы (порядок по скорости обновится).
     */
    fun autoUpdate(ctx: Context, force: Boolean = false, onUpdated: () -> Unit) {
        val last = prefs(ctx).getLong("downloaded", 0L)
        val tried = prefs(ctx).getLong("auto_tried", 0L)
        val now = System.currentTimeMillis()
        if (autoRunning) return
        if (!force && (now - last < AUTO_UPDATE_MS || now - tried < 20 * 60_000L)) return
        autoRunning = true
        prefs(ctx).edit().putLong("auto_tried", now).apply()
        Thread({
            try {
                val r = downloadAll(ctx)
                Log.i(TAG, "auto update: total=${r.total} new=${r.added} ok=${r.ok.size} failed=${r.failed.size}")
                checkAllBlocking(ctx)
                prefs(ctx).edit().putLong("quick_at", System.currentTimeMillis()).apply()
                onUpdated()
            } catch (e: Throwable) {
                Log.w(TAG, "auto update: $e")
            } finally {
                autoRunning = false
            }
        }, "nodes-update").start()
    }

    private data class Probe(val host: String, val port: Int, val tcp: List<Int>, val key: String)

    private fun allNodes(ctx: Context): List<Probe> {
        val out = LinkedHashMap<String, Probe>()
        listOf(rawBuiltIn(ctx), onlineFile(ctx).takeIf { it.exists() }?.readText()).forEach { s ->
            runCatching {
                val a = JSONObject(s ?: return@forEach).getJSONArray("nodes")
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val host = o.optString("ipv4").takeIf { it.isNotBlank() && it != "-" } ?: continue
                    val port = o.optInt("port", 33445)
                    val tcp = ArrayList<Int>()
                    o.optJSONArray("tcp_ports")?.let { t -> for (j in 0 until t.length()) tcp += t.getInt(j) }
                    out["$host:$port"] = Probe(host, port, tcp, o.optString("public_key"))
                }
            }
        }
        return out.values.toList()
    }

    private fun probe(p: Probe): Long {
        for (port in p.tcp.ifEmpty { listOf(p.port) }.sortedBy { if (it == 443) 0 else 1 }) {
            try {
                val start = System.nanoTime()
                Socket().use { s -> s.connect(InetSocketAddress(p.host, port), 3500) }
                return (System.nanoTime() - start) / 1_000_000
            } catch (_: Exception) {
            }
        }
        return -1
    }

    /** Узел отвечает? Быстрая проверка (для фона): короткий таймаут, первым - порт 443. */
    private fun quickProbe(p: Probe): Long {
        for (port in p.tcp.ifEmpty { listOf(p.port) }.sortedBy { if (it == 443) 0 else 1 }.take(2)) {
            try {
                val start = System.nanoTime()
                Socket().use { s -> s.connect(InetSocketAddress(p.host, port), 1600) }
                return (System.nanoTime() - start) / 1_000_000
            } catch (_: Exception) {
            }
        }
        return -1
    }

    /** Быстрая проверка одного узла (для сторожа узлов): задержка в мс или -1. */
    fun probeNode(host: String, port: Int, tcp: List<Int>): Long = quickProbe(Probe(host, port, tcp, ""))

    /** Записать результат проверки со сглаживанием (как фоновая проверка). */
    fun recordPing(ctx: Context, host: String, port: Int, ms: Long) {
        val prev = ping(ctx, host, port)
        val key = "p_$host:$port"
        when {
            ms >= 0 -> prefs(ctx).edit().putLong(key, if (prev != null && prev > 0) (prev + ms) / 2 else ms).apply()
            prev != null && prev >= 0 -> prefs(ctx).edit().remove(key).apply()
            else -> prefs(ctx).edit().putLong(key, -1L).apply()
        }
    }

    @Volatile
    private var quickRunning = false

    /** Когда узлы последний раз проверялись в фоне. */
    fun lastQuickCheck(ctx: Context): Long = prefs(ctx).getLong("quick_at", 0L)

    /**
     * Фоновая проверка узлов: все сразу (24 потока, по 1,6 с на узел - весь список за несколько
     * секунд), без окон и без скачивания списков. Результат сразу влияет на порядок подключения:
     * сначала быстрые и живые узлы, мёртвые - в конце. Не чаще [minIntervalMs].
     * Возвращает число живых узлов (или -1 - проверка не запускалась).
     */
    fun quickCheck(ctx: Context, minIntervalMs: Long = 25 * 60_000L): Int {
        val now = System.currentTimeMillis()
        if (quickRunning || now - lastQuickCheck(ctx) < minIntervalMs) return -1
        quickRunning = true
        try {
            val nodes = allNodes(ctx)
            if (nodes.isEmpty()) return 0
            val pool = Executors.newFixedThreadPool(probeThreads()) { r -> Thread(r, "node-probe").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
            val futures = nodes.map { n -> pool.submit<Long> { quickProbe(n) } }
            var alive = 0
            val edit = prefs(ctx).edit()
            nodes.forEachIndexed { i, n ->
                val ms = runCatching { futures[i].get(5, TimeUnit.SECONDS) }.getOrDefault(-1L)
                // Один неудачный ответ не «хоронит» узел, который недавно работал: пишем -1,
                // только если и прошлый раз он не отвечал; иначе - «не проверяли» (средний приоритет).
                val prev = ping(ctx, n.host, n.port)
                when {
                    ms >= 0 -> {
                        alive++
                        // Сглаживание: задержка скачет, порядок узлов не должен прыгать.
                        val v = if (prev != null && prev > 0) (prev * 2 + ms) / 3 else ms
                        edit.putLong("p_${n.host}:${n.port}", v)
                    }
                    prev != null && prev >= 0 -> edit.remove("p_${n.host}:${n.port}")
                    else -> edit.putLong("p_${n.host}:${n.port}", -1L)
                }
            }
            edit.putLong("quick_at", System.currentTimeMillis()).apply()
            pool.shutdownNow()
            return alive
        } finally {
            quickRunning = false
        }
    }

    /** Проверить все узлы параллельно; результат - (узел, задержка). */
    private fun checkAllBlocking(ctx: Context, progress: Progress? = null): List<Pair<Probe, Long>> {
        val nodes = allNodes(ctx)
        val pool = Executors.newFixedThreadPool(probeThreads()) { r -> Thread(r, "node-check").apply { isDaemon = true } }
        val futures = nodes.map { n -> pool.submit<Long> { probe(n) } }
        val t0 = System.currentTimeMillis()
        val result = nodes.mapIndexed { i, n ->
            val ms = runCatching { futures[i].get(8, TimeUnit.SECONDS) }.getOrDefault(-1L)
            savePing(ctx, n.host, n.port, ms)
            progress?.update(
                ctx.getString(R.string.nodes_progress_check, i + 1, nodes.size, n.host, (System.currentTimeMillis() - t0) / 1000.0),
                0.4f + 0.6f * (i + 1) / nodes.size.coerceAtLeast(1),
            )
            n to ms
        }
        pool.shutdownNow()
        return result
    }

    /**
     * Кнопка «Проверить и обновить узлы»: окно с полосой прогресса (откуда качаем, сколько
     * скачано и сколько прошло времени, какой узел проверяем), затем отчёт.
     */
    fun checkAndReport(ctx: Context, onUpdated: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (12 * d).toInt(), (22 * d).toInt(), (4 * d).toInt())
        }
        val bar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
        }
        val status = android.widget.TextView(ctx).apply {
            textSize = 13f
            setPadding(0, (10 * d).toInt(), 0, 0)
            setText(R.string.nodes_checking_long)
        }
        box.addView(bar)
        box.addView(status)
        val progress = AlertDialog.Builder(ctx)
            .setTitle(R.string.nodes_check_title)
            .setView(box)
            .setCancelable(false)
            .show()
        val ui = Progress { text, f ->
            main.post {
                status.text = text
                bar.progress = (f.coerceIn(0f, 1f) * 1000).toInt()
            }
        }
        Thread({
            val dl = downloadAll(ctx, ui)
            val res = checkAllBlocking(ctx, ui)
            main.post { onUpdated() }
            val ok = res.filter { it.second >= 0 }.sortedBy { it.second }
            val bad = res.filter { it.second < 0 }
            val dpi = ok.filter { 443 in it.first.tcp }
            val text = buildString {
                append(ctx.getString(R.string.nodes_report_summary, ok.size, bad.size))
                append('\n')
                append(
                    when {
                        dl.added > 0 -> ctx.getString(R.string.nodes_report_added, dl.added)
                        dl.added == 0 -> ctx.getString(R.string.nodes_report_no_new)
                        else -> ctx.getString(R.string.nodes_report_download_failed)
                    },
                )
                append('\n')
                if (dl.ok.isNotEmpty()) append(ctx.getString(R.string.nodes_report_sources_ok, dl.ok.distinct().joinToString())).append('\n')
                if (dl.failed.isNotEmpty()) append(ctx.getString(R.string.nodes_report_sources_failed, dl.failed.distinct().joinToString())).append('\n')
                append(ctx.getString(R.string.nodes_report_dpi, dpi.size)).append("\n\n")
                ok.forEach { (n, ms) -> append(if (443 in n.tcp) "🛡 " else "🟢 ").append("${n.host}:${n.port}  $ms ${ctx.getString(com.stoyko.smessenger.R.string.unit_ms)}\n") }
                bad.forEach { (n, _) -> append("🔴 ${n.host}:${n.port}\n") }
            }
            main.post {
                runCatching { progress.dismiss() }
                runCatching {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.nodes_check_title)
                        .setMessage(text)
                        // Новые и самые быстрые узлы - в работу сразу (сеть переподключается сама).
                        .setPositiveButton(R.string.apply_now) { _, _ ->
                            com.stoyko.smessenger.ui.RestartPrompt.applyNetwork(ctx)
                        }
                        .setNegativeButton(R.string.later, null)
                        .setNeutralButton(R.string.nodes_sources_title) { _, _ -> editSources(ctx) }
                        .show()
                }
            }
        }, "nodes-check").start()
    }

    /** Свои источники списков узлов (например, зеркало друга или свой сервер). */
    fun editSources(ctx: Context) {
        val input = android.widget.EditText(ctx).apply {
            setText(customSources(ctx).joinToString("\n"))
            hint = "https://example.com/nodes.json"
            minLines = 3
        }
        val box = android.widget.FrameLayout(ctx).apply {
            val p = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.nodes_sources_title)
            .setMessage(ctx.getString(R.string.nodes_sources_hint, SOURCES.joinToString("\n") { "• " + URL(it).host }))
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> setCustomSources(ctx, input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Список для ядра: встроенные + скачанные, без повторов (формат nodes.tox.chat). */
    fun mergedJson(ctx: Context): String {
        val seen = HashSet<String>()
        val out = JSONArray()
        listOf(onlineFile(ctx).takeIf { it.exists() }?.readText(), rawBuiltIn(ctx)).forEach { s ->
            runCatching {
                val a = JSONObject(s ?: return@forEach).getJSONArray("nodes")
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    if (seen.add(o.optString("public_key").uppercase())) out.put(o)
                }
            }
        }
        return JSONObject().put("nodes", out).toString()
    }
}
