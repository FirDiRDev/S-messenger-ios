// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.update

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R

/** Новая версия: где скачать и что нового. */
data class Release(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    val sha256: String?,
    val size: Long,
    /** Минимальная разрешённая версия: всё, что старше, обязано обновиться (из update.json). */
    val minVersionCode: Int = 0,
)

/**
 * Обновления «по воздуху»: приложение само проверяет новую версию (GitHub Releases или свой update.json),
 * показывает, что нового, скачивает Universal APK с полосой прогресса в процентах и сразу открывает установку.
 * Браузер и сторонние сайты не нужны. Скачивание идёт через встроенный VPN, если он включён.
 */
object Updater {
    private const val TAG = "Updater"
    private const val PREFS = "updater"
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "updater").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Основной источник: update.json, приложенный к ПОСЛЕДНЕМУ РЕЛИЗУ на GitHub (Assets).
     * GitHub сам перенаправляет releases/latest/download/<файл> на файл самого нового релиза,
     * поэтому ссылка постоянная. Запасные: API релизов (assets → update.json), файл в ветке
     * main/master и зеркала-CDN.
     */
    // Адреса хранятся зашифрованными (см. Obf) - в дампе строк APK их не видно.
    private val DEFAULT_REPO: String get() = com.stoyko.smessenger.Obf.x_repo()
    private val GH: String get() = com.stoyko.smessenger.Obf.x_ghRel()
    private val GH_API: String get() = com.stoyko.smessenger.Obf.x_ghApi()
    private val GH_RAW: String get() = com.stoyko.smessenger.Obf.x_ghRaw()
    private val CDN: String get() = com.stoyko.smessenger.Obf.x_jsdelivr()
    val RELEASE_JSON: String get() = GH + DEFAULT_REPO + "/releases/latest/download/update.json"
    private val DEFAULT_JSON: String get() = RELEASE_JSON

    @Suppress("UNUSED_PARAMETER")
    fun source(ctx: Context): String = BuildConfig.UPDATE_URL.ifBlank { DEFAULT_JSON }

    val currentVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private fun open(url: String, direct: Boolean = false): HttpURLConnection {
        val proxy = if (!direct && com.stoyko.smessenger.vpn.VpnState.proxyReady) com.stoyko.smessenger.vpn.VpnState.httpProxy() else java.net.Proxy.NO_PROXY
        return (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "StoykoMessenger/${BuildConfig.VERSION_NAME}")
            // Не брать устаревшую копию из кэшей по дороге (из-за этого новая версия «не находилась»).
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
        }
    }

    /** Запрос; если через VPN не вышло (сервер VPN недоступен) - ещё раз напрямую. */
    private fun get(url: String): String = try {
        getVia(url, direct = false)
    } catch (e: Exception) {
        // Через VPN приложения не вышло (сервер не пускает к GitHub, ответил ошибкой) - напрямую.
        // Ответ «404 - файла нет» одинаков по любому пути, его не повторяем.
        val notFound = e is IllegalStateException && e.message == "HTTP 404"
        if (com.stoyko.smessenger.vpn.VpnState.proxyReady && !notFound) {
            getVia(url, direct = true)
        } else {
            throw e
        }
    }

    /** Ссылка с меткой времени - CDN GitHub отдаёт свежий файл, а не копию пятиминутной давности. */
    private fun fresh(url: String): String =
        url + (if ('?' in url) "&" else "?") + "t=" + System.currentTimeMillis() / 60_000

    private fun getVia(url: String, direct: Boolean): String {
        var u = url
        repeat(5) {
            val c = open(u, direct)
            c.setRequestProperty("Accept", "application/vnd.github+json, application/json")
            try {
                val code = c.responseCode
                if (code !in 200..299) {
                    // Тело ответа-ошибки тоже закрываем: иначе распаковщик gzip остаётся открытым
                    // («A resource failed to call end» в журнале Huawei на каждый 404).
                    runCatching { c.errorStream?.close() }
                }
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IllegalStateException("HTTP $code")
                    // Относительный адрес перенаправления - от текущего (раньше ломал проверку).
                    u = java.net.URL(java.net.URL(u), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        throw IllegalStateException("слишком много перенаправлений")
    }

    /** «1.6.0» -> сравнение по частям. */
    private fun newerName(a: String, b: String): Boolean {
        val x = a.trim().removePrefix("v").split('.', '-', ' ').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.', '-', ' ').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    private fun fromGithub(repo: String): Release {
        val clean = repo.removePrefix(GH).removePrefix("github.com/").trim('/')
        val json = JSONObject(get(GH_API + "$clean/releases/latest"))
        val tag = json.optString("tag_name")
        val assets = json.optJSONArray("assets")
        var url = ""
        var size = 0L
        var fallback = ""
        var fallbackSize = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name").lowercase()
                if (!name.endsWith(".apk")) continue
                if (name.contains("universal") && url.isEmpty()) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                } else if (fallback.isEmpty()) {
                    fallback = a.optString("browser_download_url")
                    fallbackSize = a.optLong("size")
                }
            }
        }
        if (url.isEmpty()) {
            url = fallback
            size = fallbackSize
        }
        if (url.isEmpty()) throw IllegalStateException("в последнем релизе нет APK")
        // Тег «17» - это versionCode, «v1.7.0» - versionName.
        val code = tag.removePrefix("v").toIntOrNull() ?: 0
        return Release(code, tag.removePrefix("v"), url, json.optString("body"), null, size)
    }

    /** update.json: versionCode, versionName, apkUrl (или url), changeLog (или notes), sha256, size. */
    private fun fromJson(url: String): Release = parseJson(get(url))

    /** update.json из ассетов последнего релиза через API GitHub (запасной путь). */
    private fun releaseAssetJson(repo: String): String {
        val json = JSONObject(get(GH_API + "$repo/releases/latest"))
        val assets = json.optJSONArray("assets") ?: throw IllegalStateException("в релизе нет файлов")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").equals("update.json", ignoreCase = true)) return get(a.optString("browser_download_url"))
        }
        throw IllegalStateException("в последнем релизе нет update.json")
    }

    private fun repoOf(): String = BuildConfig.UPDATE_GITHUB.ifBlank { DEFAULT_REPO }
        .removePrefix(GH).removePrefix("github.com/").trim('/')

    /**
     * Текст свежего update.json (для магазина монет и т. п.): релиз → API релизов → ветки →
     * CDN. Блокирующий вызов - только из фонового потока.
     */
    fun latestJsonText(ctx: Context): String {
        val repo = repoOf()
        val tries = listOf<() -> String>(
            { get(source(ctx)) },
            { releaseAssetJson(repo) },
            { get(fresh(GH_RAW + "$repo/main/update.json")) },
            { get(fresh(GH_RAW + "$repo/master/update.json")) },
            { get(fresh(CDN + "$repo@main/update.json")) },
        )
        var last: Throwable? = null
        for (t in tries) {
            val r = runCatching { t() }
            r.onSuccess { text ->
                if (runCatching { JSONObject(text) }.isSuccess) return text
            }
            last = r.exceptionOrNull() ?: last
        }
        throw last ?: IllegalStateException("update.json недоступен")
    }

    /** update.json через API GitHub (обходит кэш raw-ссылок полностью). */
    private fun fromContentsApi(repo: String): Release {
        // Без ?ref - берётся ветка репозитория по умолчанию (main или master, как настроено на GitHub).
        val o = JSONObject(get(GH_API + "$repo/contents/update.json"))
        val b64 = o.optString("content").replace("\n", "").replace("\r", "")
        if (b64.isBlank()) throw IllegalStateException("пустой ответ API")
        return parseJson(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8))
    }

    private fun parseJson(text: String): Release {
        val o = JSONObject(text)
        val apk = o.optString("apkUrl").ifBlank { o.optString("url") }
        if (apk.isBlank()) throw IllegalStateException("в update.json нет apkUrl")
        return Release(
            o.optInt("versionCode"),
            o.optString("versionName"),
            apk,
            o.optString("changeLog").ifBlank { o.optString("notes") },
            o.optString("sha256").takeIf { it.length == 64 },
            o.optLong("size"),
            o.optInt("minVersionCode", 0),
        )
    }

    /** a новее b? */
    private fun newer(a: Release, b: Release): Boolean =
        if (a.versionCode > 0 && b.versionCode > 0) a.versionCode > b.versionCode else newerName(a.versionName, b.versionName)

    private fun isNewer(r: Release): Boolean =
        if (r.versionCode > 0) r.versionCode > BuildConfig.VERSION_CODE else newerName(r.versionName, BuildConfig.VERSION_NAME)

    /** Главные источники - спрашиваем всегда, даже после ошибки. */
    private val KEEP_SOURCES = setOf("update.json", "release asset", "releases")

    /** Проверка. Результат: новая версия или null (уже последняя). */
    fun check(ctx: Context, done: (Result<Release?>) -> Unit) {
        val src = source(ctx)
        val repo = repoOf()
        pool.execute {
            val r = runCatching {
                // Несколько источников: update.json (свежая ссылка), он же через API GitHub
                // (без кэша), и последний релиз. Берём САМУЮ НОВУЮ версию из ответивших -
                // один устаревший или недоступный источник больше не прячет обновление.
                val found = ArrayList<Release>()
                val errors = ArrayList<String>()
                fun attempt(name: String, block: () -> Release) {
                    // Сбой сети (обрыв, таймаут) - ещё одна попытка через пару секунд; «нет файла» не повторяем.
                    var r = runCatching(block)
                    val e = r.exceptionOrNull()
                    if (e is java.io.IOException) {
                        Thread.sleep(2000)
                        r = runCatching(block)
                    }
                    r.onSuccess { synchronized(found) { found += it } }.onFailure {
                        // 4.12.1: зеркало ответило «нет файла» (404) - 12 часов его не спрашиваем
                        // (в журнале каждую проверку было 4 одинаковых «HTTP 404»).
                        if (it.message.orEmpty().contains("HTTP 404") && name !in KEEP_SOURCES) {
                            prefs(ctx).edit().putLong("skip_$name", System.currentTimeMillis() + 12 * 3_600_000L).apply()
                        }
                        Log.w(TAG, "$name: $it")
                        synchronized(found) { errors += "$name: ${it.message ?: it.javaClass.simpleName}" }
                    }
                }
                // update.json из последнего релиза (основной путь). Заодно - настройки магазина монет.
                fun fromText(text: String): Release {
                    runCatching { com.stoyko.smessenger.chat.CoinShop.applyJson(ctx, text) }
                    return parseJson(text)
                }
                // 4.10.0: все источники опрашиваются ПАРАЛЛЕЛЬНО с общим сроком 30 с. Раньше они
                // шли по очереди, и при заблокированном GitHub проверка тянулась минуты (каждый
                // источник - таймаут 12 с и повтор), а окно «Проверка…» висело.
                val sources = ArrayList<Pair<String, () -> Release>>()
                sources += "update.json" to { fromText(get(src)) }
                sources += "release asset" to { fromText(releaseAssetJson(repo)) }
                sources += "update.json main" to { fromText(get(fresh(GH_RAW + "$repo/main/update.json"))) }
                sources += "releases" to { fromGithub(repo) }
                for (cdn in listOf("cdn.jsdelivr.net", "fastly.jsdelivr.net", "gcore.jsdelivr.net")) {
                    sources += cdn to { fromJson(fresh("https://$cdn/gh/$repo@main/update.json")) }
                }
                val nowMs = System.currentTimeMillis()
                sources.removeAll { (name, _) -> name !in KEEP_SOURCES && prefs(ctx).getLong("skip_$name", 0L) > nowMs }
                val par = java.util.concurrent.Executors.newFixedThreadPool(4) { r -> Thread(r, "upd-src").apply { isDaemon = true } }
                try {
                    val futures = sources.map { (name, block) -> name to par.submit(java.util.concurrent.Callable { attempt(name, block) }) }
                    val deadline = System.currentTimeMillis() + 30_000L
                    for ((name, f) in futures) {
                        val left = deadline - System.currentTimeMillis()
                        // Основные источники ответили - зеркала не ждём.
                        if (left <= 0 || (synchronized(found) { found.isNotEmpty() } && name.contains("jsdelivr"))) break
                        runCatching { f.get(left, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    }
                    // Источник по содержимому - только если ничего не ответило.
                    if (synchronized(found) { found.isEmpty() }) attempt("api") { fromContentsApi(repo) }
                } finally {
                    par.shutdownNow()
                }
                // Снимок: опоздавшие источники могут ещё дописывать списки из своих потоков.
                val got = synchronized(found) { ArrayList(found) }
                val errs = synchronized(found) { ArrayList(errors) }
                if (got.isEmpty()) {
                    prefs(ctx).edit().putLong("last_fail", System.currentTimeMillis()).apply()
                    throw IllegalStateException(errs.joinToString("\n"))
                }
                prefs(ctx).edit().putLong("last_check", System.currentTimeMillis()).apply()
                val top = got.reduce { a, b -> if (newer(b, a)) b else a }
                // 4.11.1: одна и та же версия пришла из нескольких мест (update.json и релиз GitHub с
                // пустым описанием). Раньше бралась любая - и в окне было «Исправления и улучшения.»
                // вместо списка изменений. Теперь - запись с номером сборки и описанием, а пустые
                // поля дополняются из остальных.
                val same = got.filter { !newer(it, top) && !newer(top, it) }
                val primary = same.firstOrNull { it.versionCode > 0 && it.notes.isNotBlank() } ?: same.firstOrNull { it.versionCode > 0 } ?: top
                val best = primary.copy(
                    notes = primary.notes.ifBlank { same.firstOrNull { it.notes.isNotBlank() }?.notes.orEmpty() },
                    sha256 = primary.sha256 ?: same.firstNotNullOfOrNull { it.sha256 },
                    size = if (primary.size > 0) primary.size else same.maxOf { it.size },
                )
                if (isNewer(best)) prefs(ctx).edit().putInt("found_code", best.versionCode).apply()
                // Минимальная версия с сервера: ниже неё работать нельзя.
                val minCode = got.maxOf { it.minVersionCode }
                if (minCode > 0) prefs(ctx).edit().putInt("min_code", minCode).apply()
                lastRelease = best
                best.takeIf { isNewer(it) }
            }
            // Свежий список изменений - для окна «Что нового» после обновления.
            lastRelease?.notes?.takeIf { it.isNotBlank() }?.let { prefs(ctx).edit().putString("last_changelog", it.take(60_000)).apply() }
            main.post { done(r) }
        }
    }

    /**
     * Тихая проверка в фоне при каждом входе в приложение (сеть - не чаще раза в час, чтобы не
     * тратить трафик). Если есть новая версия - окно «Установить / Напомнить позже / Пропустить».
     * «Напомнить позже» откладывает окно на 6 часов.
     */
    fun autoCheck(ctx: Context, force: Boolean = false) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (offering || inFlight) return
        // Обновление уже скачано (в фоне или раньше) - сразу «Установить», без новой загрузки.
        val act0 = ctx as? android.app.Activity
        if (act0 != null && readyApk(ctx) != null && now >= p.getLong("ready_later", 0L) && !installingNow()) {
            offerReady(act0)
            return
        }
        if (now < p.getLong("remind_after", 0L)) return
        // Проверка при каждом запуске приложения ([force]) и при каждом возвращении в него
        // (не чаще раза в 5 минут; после неудачи - снова через 2 минуты).
        // Если новая версия уже найдена (в т. ч. фоновой проверкой службы) - предлагаем сразу.
        val found = p.getInt("found_code", 0) > BuildConfig.VERSION_CODE
        val recentOk = now - p.getLong("last_check", 0L) < 5 * 60_000L
        val recentFail = now - p.getLong("last_fail", 0L) < 2 * 60_000L
        if (!force && !found && (recentOk || recentFail)) return
        inFlight = true
        check(ctx) { r ->
            inFlight = false
            val rel = r.getOrNull() ?: return@check
            val act = ctx as? android.app.Activity
            if (act != null && (act.isFinishing || act.isDestroyed)) return@check
            // Сервер объявил эту версию устаревшей - сразу обязательное обновление.
            val reason = blockReason(ctx)
            if (act != null && reason != null) {
                showBlocking(act, reason)
                return@check
            }
            // Уже скачано - предлагаем установить готовый файл.
            if (act != null && readyApk(ctx)?.second == rel.versionCode) {
                offerReady(act)
                return@check
            }
            // Уже качается в фоне - второе окно «Обновить» не показываем: дождёмся загрузки
            // и тогда предложим установить.
            if (act != null && running(rel) != null) {
                whenDownloaded(rel)
                return@check
            }
            // Приложение на экране - окно «Обновить» (с полосой загрузки или «Скачать в фоне»);
            // без экрана - фоновое обновление с прогрессом в шторке.
            if (act == null && silentUpdate(ctx, rel)) {
                runCatching { Toast.makeText(ctx, ctx.getString(R.string.update_bg_downloading, rel.versionName), Toast.LENGTH_LONG).show() }
                return@check
            }
            offering = true
            runCatching { offer(ctx, rel, auto = true) }.onFailure { offering = false }
        }
    }

    /** Экран, который сейчас открыт (чтобы сразу показать окно обновления). */
    @Volatile
    var foreground: java.lang.ref.WeakReference<android.app.Activity>? = null

    @Volatile
    private var wasOnline = false

    /**
     * Мессенджер подключился к сети - проверяем обновление СРАЗУ (раньше проверка ждала
     * входа в приложение или будильника и могла опоздать на часы). Приложение открыто -
     * сразу окно «Обновить и установить», иначе - уведомление.
     */
    fun onNetwork(ctx: Context, online: Boolean) {
        val became = online && !wasOnline
        wasOnline = online
        if (!became) return
        val app = ctx.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val act = foreground?.get()
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                autoCheck(act, force = true)
            } else {
                backgroundCheck(app)
            }
        }, 3_000)
    }

    @Volatile
    private var offering = false

    @Volatile
    private var inFlight = false

    /**
     * Фоновая проверка - из службы и по системному будильнику ([UpdateReceiver], раз в 3 часа,
     * даже если приложение закрыто). Нашлась новая версия - уведомление в шторке и на экране
     * блокировки; пока не обновитесь - напоминание раз в [REMIND_EVERY_MS] (или когда истечёт
     * «Напомнить позже» из уведомления).
     */
    fun backgroundCheck(ctx: Context, fromAlarm: Boolean = false, done: (() -> Unit)? = null) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        // Сеть - не чаще раза в 2 часа; уже найденную версию напоминаем без нового запроса.
        val known = p.getInt("found_code", 0)
        if (now - p.getLong("last_bg_check", 0L) < 2 * 3600_000L) {
            if (known > BuildConfig.VERSION_CODE) lastRelease?.let { remindIfDue(ctx, it) }
            done?.invoke()
            return
        }
        p.edit().putLong("last_bg_check", now).apply()
        check(ctx) { r ->
            val rel = r.getOrNull()
            if (rel != null) {
                p.edit().putInt("found_code", rel.versionCode).apply()
                // Разрешена установка - скачиваем и ставим сами, в фоне; иначе - уведомление.
                if (!silentUpdate(ctx, rel)) remindIfDue(ctx, rel)
            } else if (r.isSuccess) {
                // Уже последняя версия - старое уведомление убираем.
                cancelNotification(ctx)
            }
            done?.invoke()
        }
        if (fromAlarm) Log.i(TAG, "проверка по будильнику")
    }

    private const val REMIND_EVERY_MS = 6 * 3600_000L
    private const val NOTIFY_ID = 0x5E21

    /** Уведомить о новой версии: сразу, если она новая, иначе - не чаще раза в 6 часов. */
    private fun remindIfDue(ctx: Context, rel: Release) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val first = p.getInt("notified_code", 0) != rel.versionCode
        val due = now - p.getLong("notified_at", 0L) >= REMIND_EVERY_MS && now >= p.getLong("notify_snooze", 0L)
        if (!first && !due) return
        p.edit().putInt("notified_code", rel.versionCode).putLong("notified_at", now).apply()
        runCatching { notifyAvailable(ctx, rel, reminder = !first) }
    }

    /** «Напомнить позже» в уведомлении: убрать его и напомнить через 6 часов. */
    fun snoozeNotification(ctx: Context) {
        prefs(ctx).edit().putLong("notify_snooze", System.currentTimeMillis() + REMIND_EVERY_MS)
            .putLong("notified_at", System.currentTimeMillis()).apply()
        cancelNotification(ctx)
        main.post { Toast.makeText(ctx, R.string.update_snoozed, Toast.LENGTH_SHORT).show() }
    }

    fun cancelNotification(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        runCatching { nm.cancel(NOTIFY_ID) }
    }

    const val ACTION_SHOW_UPDATE = "com.stoyko.smessenger.SHOW_UPDATE"

    /**
     * Уведомление «Доступно обновление»: всплывает поверх экрана, видно в шторке и на экране
     * блокировки (с текстом «что нового»), кнопки «Установить» и «Напомнить через 6 ч».
     */
    private fun notifyAvailable(ctx: Context, rel: Release, reminder: Boolean = false) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = "updates_v2"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(channel, ctx.getString(R.string.update_channel), android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ctx.getString(R.string.update_channel_desc)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    enableLights(true)
                    lightColor = 0xFF7B6CF6.toInt()
                },
            )
            // Старый тихий канал больше не нужен.
            runCatching { nm.deleteNotificationChannel("updates") }
        }
        val open = android.content.Intent(ctx, com.stoyko.smessenger.MainActivity::class.java)
            .setAction(ACTION_SHOW_UPDATE)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = android.app.PendingIntent.getActivity(
            ctx, NOTIFY_ID, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val later = android.app.PendingIntent.getBroadcast(
            ctx, NOTIFY_ID + 2,
            android.content.Intent(ctx, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_LATER),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val version = rel.versionName.ifBlank { rel.versionCode.toString() }
        val title = ctx.getString(if (reminder) R.string.update_reminder_title else R.string.update_available, version)
        // «Что нового» - первые строки списка изменений этой версии.
        val notes = notesFor(rel).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .takeWhile { !it.startsWith("Что нового") && !it.startsWith("What's new") }
            .take(6).joinToString("\n")
        val body = ctx.getString(R.string.update_tap_to_install) + if (notes.isNotBlank()) "\n\n$notes" else ""
        val size = if (rel.size > 0) " · " + Formatter.formatShortFileSize(ctx, rel.size) else ""
        fun build(isPublic: Boolean) = androidx.core.app.NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_refresh)
            .setColor(0xFF7B6CF6.toInt())
            .setContentTitle(title)
            .setContentText(ctx.getString(R.string.update_tap_to_install) + size)
            .setSubText(currentVersion + " → " + version)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
            .apply {
                if (!isPublic) {
                    setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                    addAction(R.drawable.ic_refresh, ctx.getString(R.string.update_install_now), pi)
                }
            }
        val n = build(isPublic = false).setPublicVersion(build(isPublic = true).build()).build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }

    /** Кнопка «Проверить обновления». */
    fun checkNow(ctx: Context) {
        Toast.makeText(ctx, R.string.update_checking, Toast.LENGTH_SHORT).show()
        check(ctx) { r ->
            r.onSuccess { rel ->
                if (rel == null) {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.update_latest_title)
                        .setMessage(ctx.getString(R.string.update_latest, currentVersion))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else if (readyApk(ctx)?.second == rel.versionCode && ctx is android.app.Activity) {
                    offerReady(ctx)
                } else if (running(rel) != null) {
                    // Идёт фоновая загрузка - показываем её ход (вторая загрузка не начнётся).
                    download(ctx, rel)
                } else {
                    offer(ctx, rel, auto = false)
                }
            }.onFailure { e ->
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.update_title)
                    .setMessage(ctx.getString(R.string.update_failed, e.message ?: e.toString()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /**
     * Ошибка прошлой попытки скачать ЭТУ версию - только если она была на ЭТОЙ установленной
     * версии и недавно. Раньше показывалась ошибка многомесячной давности («у вас уже 4.9.8»
     * при установленной 4.10.3): номер сборки у релиза без update.json был 0 и совпадал всегда.
     */
    private fun lastFail(ctx: Context, rel: Release): String? {
        val p = prefs(ctx)
        if (rel.versionCode <= 0 || p.getInt("fail_code", 0) != rel.versionCode) return null
        if (p.getInt("fail_from", 0) != BuildConfig.VERSION_CODE) return null
        if (System.currentTimeMillis() - p.getLong("fail_at", 0L) > 2 * 24 * 3600_000L) return null
        return p.getString("fail_msg", null)?.takeIf { it.isNotBlank() }
    }

    /**
     * «Что нового» для окна обновления: только блоки версий новее установленной («Что нового в
     * 4.11.0:» …), пункты с «•». Раньше выводился весь журнал всех версий подряд (или ничего).
     */
    fun notesFor(rel: Release): String {
        val text = rel.notes.replace("\r", "").trim()
        if (text.isBlank()) return ""
        val head = Regex("(?im)^\\s*(?:#+\\s*)?(?:Что нового в|What's new in|Версия|Version)\\s*v?([0-9][0-9.]*)\\s*:?\\s*$")
        val marks = head.findAll(text).toList()
        fun bullets(b: String) = b.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n") { l -> if (l.startsWith("- ") || l.startsWith("* ")) "• " + l.substring(2) else l }
        if (marks.isEmpty()) return bullets(text)
        val out = ArrayList<String>()
        marks.forEachIndexed { i, m ->
            val ver = m.groupValues[1].trimEnd('.')
            if (!newerName(ver, BuildConfig.VERSION_NAME)) return@forEachIndexed
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else text.length
            val body = bullets(text.substring(m.range.last + 1, end))
            if (body.isNotBlank()) out += (if (marks.size > 1) "✦ $ver\n" else "") + body
        }
        return out.take(MAX_WHATSNEW).joinToString("\n\n").ifBlank { bullets(text.substring(marks[0].range.last + 1, marks.getOrNull(1)?.range?.first ?: text.length)) }
    }

    private fun offer(ctx: Context, rel: Release, auto: Boolean) {
        val size = if (rel.size > 0) " · " + Formatter.formatShortFileSize(ctx, rel.size) else ""
        val b = AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.update_available, rel.versionName.ifBlank { rel.versionCode.toString() }))
            .setMessage(
                ctx.getString(R.string.update_current, currentVersion) + size + "\n\n" +
                    notesFor(rel).ifBlank { ctx.getString(R.string.update_no_notes) }.take(5000) +
                    "\n\n" + ctx.getString(R.string.update_install_hint) +
                    (lastFail(ctx, rel)?.let { "\n\n⚠ " + it } ?: ""),
            )
            // «Обновить и установить» - окно с полосой загрузки; «В фоне» - качается в шторке
            // и ставится само (если разрешена установка).
            .setPositiveButton(R.string.update_install_now) { _, _ -> download(ctx, rel) }
            .setCancelable(false)
        if (autoInstall(ctx) && SessionInstaller.allowed(ctx)) {
            b.setNeutralButton(R.string.upd2_in_background) { _, _ ->
                prefs(ctx).edit().remove("fail_code").apply()
                if (silentUpdate(ctx, rel)) runCatching { Toast.makeText(ctx, ctx.getString(R.string.update_bg_downloading, rel.versionName), Toast.LENGTH_LONG).show() }
            }
        }
        b.setOnDismissListener { offering = false }
        // Окно уже закрывается - диалог не показываем (иначе «утечка окна»).
        val act = ctx as? android.app.Activity
        if (act != null && (act.isFinishing || act.isDestroyed)) {
            offering = false
            return
        }
        runCatching { b.show() }.onFailure { offering = false }
    }

    // ---------------- обязательное обновление, запрет отката, «что нового» ----------------

    /** Сколько раз можно отложить одну и ту же новую версию. */
    private const val MAX_LATER = 4

    @Volatile
    private var lastRelease: Release? = null

    private fun laterCount(ctx: Context, code: Int) = prefs(ctx).getInt("later_$code", 0)

    /**
     * «Напомнить позже»: счётчик растёт; на [MAX_LATER]-й раз версия становится обязательной -
     * приложение закрывается, а при следующем входе без обновления дальше не пустит.
     */
    private fun postpone(ctx: Context, rel: Release) {
        val n = laterCount(ctx, rel.versionCode) + 1
        val p = prefs(ctx).edit().putInt("later_${rel.versionCode}", n)
            .putLong("remind_after", System.currentTimeMillis() + 3 * 3600_000L)
        if (n >= MAX_LATER) {
            p.putInt("forced_code", rel.versionCode).commit()
            Toast.makeText(ctx, R.string.update_forced_closing, Toast.LENGTH_LONG).show()
            val a = ctx as? android.app.Activity
            main.postDelayed({
                runCatching { a?.finishAffinity() }
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 1800)
        } else {
            p.apply()
        }
    }

    /** Наибольшая версия, которая когда-либо запускалась (дублируется в отдельном файле). */
    private fun maxSeen(ctx: Context): Int {
        val fromFile = runCatching { File(ctx.filesDir, ".vguard").readText().trim().toInt() }.getOrDefault(0)
        return maxOf(prefs(ctx).getInt("max_seen", 0), fromFile)
    }

    private fun rememberVersion(ctx: Context) {
        val m = maxOf(maxSeen(ctx), BuildConfig.VERSION_CODE)
        prefs(ctx).edit().putInt("max_seen", m).apply()
        runCatching { File(ctx.filesDir, ".vguard").writeText(m.toString()) }
    }

    /** Почему нельзя работать на этой версии (null - можно). */
    private fun blockReason(ctx: Context): Int? {
        val p = prefs(ctx)
        val code = BuildConfig.VERSION_CODE
        return when {
            maxSeen(ctx) > code -> R.string.update_block_downgrade
            p.getInt("min_code", 0) > code -> R.string.update_block_min
            p.getInt("forced_code", 0) > code -> R.string.update_block_forced
            else -> null
        }
    }

    /**
     * При каждом запуске (из MainActivity): запрет отката на старую версию, обязательное
     * обновление и один раз - «Что нового в этой версии».
     */
    fun onLaunch(activity: android.app.Activity) {
        // Проверка по будильнику - раз в 3 часа, даже когда приложение закрыто.
        UpdateReceiver.schedule(activity.applicationContext)
        // Уже обновились - уведомление «Доступно обновление» больше не нужно.
        // Скачанный файл этой (или более старой) версии больше не нужен.
        if (prefs(activity).getInt("ready_code", 0) in 1..BuildConfig.VERSION_CODE) clearReady(activity)
        if (prefs(activity).getInt("found_code", 0) <= BuildConfig.VERSION_CODE) {
            cancelNotification(activity)
            runCatching { (activity.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(PROGRESS_ID) }
        }
        val prev = maxSeen(activity)
        val reason = blockReason(activity)
        if (reason != null) {
            showBlocking(activity, reason)
            return
        }
        rememberVersion(activity)
        // Обновились (а не первая установка) - один раз показываем, что нового.
        val shown = prefs(activity).getInt("whatsnew_shown", 0)
        if (prev in 1 until BuildConfig.VERSION_CODE && shown < BuildConfig.VERSION_CODE) {
            prefs(activity).edit().putInt("whatsnew_shown", BuildConfig.VERSION_CODE).apply()
            // Пропущенные версии (обновились, например, с 4.9.2 сразу на 4.9.5) - показываем все.
            val missed = (BuildConfig.VERSION_CODE - prev).coerceIn(1, MAX_WHATSNEW)
            activity.window.decorView.postDelayed({ if (!activity.isFinishing) showWhatsNew(activity, missed) }, 900)
        } else if (prev == 0) {
            prefs(activity).edit().putInt("whatsnew_shown", BuildConfig.VERSION_CODE).apply()
        }
    }

    /**
     * История версий. Лежит в assets (history_ru.txt / history_en.txt), а не в строках ресурсов:
     * строка длиннее 32 КБ в ресурсах не читается - вместо текста было «STRING_TOO_LARGE».
     */
    fun history(ctx: Context): String {
        val lang = runCatching { langOf(ctx) }.getOrDefault("ru")
        val file = if (lang == "ru" || lang == "uk" || lang == "be") "history_ru.txt" else "history_en.txt"
        return runCatching { ctx.assets.open(file).bufferedReader().use { it.readText() } }
            .recoverCatching { ctx.assets.open("history_ru.txt").bufferedReader().use { it.readText() } }
            .getOrDefault("")
    }

    @Suppress("DEPRECATION")
    private fun langOf(ctx: Context): String =
        if (android.os.Build.VERSION.SDK_INT >= 24) ctx.resources.configuration.locales[0].language else ctx.resources.configuration.locale.language

    /**
     * Текст «Что нового» именно для установленной версии: блок истории с её номером; если его
     * нет (история в APK отстала) - блок «Что нового в X:» из последнего update.json; и только
     * если нет и его - первый блок истории.
     */
    private const val MAX_WHATSNEW = 6

    /**
     * [count] версий подряд, начиная с установленной (4.9.5, 4.9.4, 4.9.3…): каждая - со своим
     * заголовком. Одна версия - как раньше, без заголовка.
     */
    private fun whatsNewText(ctx: Context, count: Int = 1): String {
        val v = BuildConfig.VERSION_NAME
        val blocks = history(ctx).split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val at = blocks.indexOfFirst { it.substringBefore('\n').trim() == v }
        if (at >= 0 && count > 1) {
            return blocks.drop(at).take(count).joinToString("\n\n") { b ->
                "✦ " + b.substringBefore('\n').trim() + "\n" + b.substringAfter('\n', "").trim()
            }
        }
        if (at >= 0) return blocks[at].substringAfter('\n', "").trim()
        val log = prefs(ctx).getString("last_changelog", null).orEmpty()
        val m = Regex("(?:Что нового в|What's new in) " + Regex.escape(v) + ":\\s*\\n([\\s\\S]*?)(?:\\n\\s*\\n|$)").find(log)
        if (m != null) return m.groupValues[1].lines().joinToString("\n") { if (it.startsWith("- ")) "• " + it.substring(2) else it }.trim()
        return blocks.firstOrNull()?.substringAfter('\n', "")?.trim().orEmpty()
    }

    /** Окно «Что нового в этой версии» - текст из истории версий, кнопка «Закрыть». */
    fun showWhatsNew(activity: android.app.Activity, count: Int = 3) {
        val body = whatsNewText(activity, count)
        val d = activity.resources.displayMetrics.density
        val text = TextView(activity).apply {
            this.text = body
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), (8 * d).toInt())
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.whatsnew_title, BuildConfig.VERSION_NAME))
            .setView(android.widget.ScrollView(activity).apply { addView(text) })
            .setPositiveButton(R.string.close, null)
            .setCancelable(true)
            .show()
    }

    /** Блокирующий экран: без обновления дальше нельзя. «Назад» закрывает приложение. */
    private fun showBlocking(activity: android.app.Activity, reason: Int) {
        if (activity.isFinishing) return
        // Без слов «обязательно/нужно»: просто новая версия и одна кнопка «Обновить и установить».
        val notes = lastRelease?.notes?.takeIf { it.isNotBlank() }?.take(5000)?.let { "\n\n$it" } ?: ""
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_new_title)
            .setMessage(activity.getString(R.string.update_current, currentVersion) + notes + "\n\n" + activity.getString(R.string.update_install_hint))
            .setCancelable(false)
            .setPositiveButton(R.string.update_install_now, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val rel = lastRelease
                if (rel != null && rel.versionCode > BuildConfig.VERSION_CODE) {
                    download(activity, rel) { ok -> if (!ok) Toast.makeText(activity, R.string.update_retry, Toast.LENGTH_LONG).show() }
                } else {
                    Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
                    check(activity) { r ->
                        val found = r.getOrNull() ?: lastRelease
                        if (found != null && found.versionCode > BuildConfig.VERSION_CODE) {
                            download(activity, found) { ok -> if (!ok) Toast.makeText(activity, R.string.update_retry, Toast.LENGTH_LONG).show() }
                        } else {
                            Toast.makeText(activity, R.string.update_retry, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
        // Сразу ищем свежую версию, чтобы «Установить» сработало с первого нажатия.
        check(activity) { }
    }

    /** Нужно ли сейчас блокировать работу (для экранов, открытых в обход окна). */
    fun isBlocked(ctx: Context) = blockReason(ctx) != null

    /** Имя универсального APK, которое делает build_apk.bat (его и выкладываем в релиз). */
    private val APK_NAMES = listOf("S-messenger-universal-release.apk", "StoykoMessenger-universal.apk", "app-universal-release.apk")

    /** Куда может лежать APK версии [rel] - по порядку. */
    private fun apkCandidates(rel: Release): List<String> {
        val repo = repoOf()
        val out = LinkedHashSet<String>()
        // Сначала - ссылка на релиз именно этой версии (тег 4.11.0): «последний» релиз может
        // оказаться старым, и тогда 100+ МБ качались впустую ради ошибки «APK старой версии».
        val vn = rel.versionName.trim().removePrefix("v")
        if (rel.url.isNotBlank() && "/latest/" !in rel.url) out += rel.url
        if (vn.isNotEmpty()) {
            for (tag in listOf(vn, "v$vn")) out += "https://github.com/$repo/releases/download/$tag/${APK_NAMES[0]}"
        }
        if (rel.url.isNotBlank()) out += rel.url
        for (n in APK_NAMES) out += "https://github.com/$repo/releases/latest/download/$n"
        // 4.10.0: GitHub недоступен - открытые прокси-зеркала GitHub. Это безопасно: перед
        // установкой APK проверяется подпись (должна совпасть с установленной) и SHA-256 из update.json.
        val direct = out.filter { it.startsWith("https://github.com/") }.take(2)
        for (m in listOf("https://ghfast.top/", "https://gh-proxy.com/")) direct.forEach { out += m + it }
        return out.toList()
    }

    /** Ссылка на APK последнего релиза по API GitHub (что бы ни было в имени файла). */
    private fun apiApkUrl(repo: String): String = fromGithub(repo).url

    /** Открыть скачивание APK (с докачкой из [part]); 404 и прочие ошибки - исключение. */
    private fun openApk(start: String, part: File, direct: Boolean): HttpURLConnection {
        var url = start
        var hops = 0
        while (true) {
            val c = open(url, direct)
            if (part.length() > 0) c.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = try { c.responseCode } catch (e: Exception) { c.disconnect(); throw e }
            if (code in 300..399 && hops++ < 8) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                url = loc?.let { java.net.URL(java.net.URL(url), it).toString() } ?: throw IllegalStateException("HTTP $code")
                continue
            }
            if (code == 416 && part.length() > 0) {
                // Докачка не подошла (файл на сервере другой) - начинаем заново.
                c.disconnect()
                part.delete()
                continue
            }
            if (code !in 200..299) {
                runCatching { c.errorStream?.close() }
                c.disconnect()
                throw IllegalStateException("HTTP $code")
            }
            val type = c.contentType.orEmpty()
            if (type.startsWith("text/html")) {
                c.disconnect()
                throw IllegalStateException("не APK ($type)")
            }
            return c
        }
    }

    /**
     * Годится ли скачанный APK: это наш пакет, его версия новее установленной и подпись та же.
     * null - годится, иначе причина (понятным текстом). Раньше без этой проверки в релизе мог
     * оказаться старый APK: «обновление» ставилось, а версия оставалась прежней, и скачивание
     * начиналось заново.
     */
    private fun apkProblem(ctx: Context, f: File): String? {
        if (!f.exists() || f.length() < 4096) return ctx.getString(R.string.upd2_apk_broken)
        val pm = ctx.packageManager
        val info = runCatching { archiveInfo(pm, f) }.getOrNull() ?: return ctx.getString(R.string.upd2_apk_broken)
        if (info.packageName != ctx.packageName) return ctx.getString(R.string.upd2_apk_other, info.packageName)
        val code = versionOf(info)
        if (code <= BuildConfig.VERSION_CODE) {
            return ctx.getString(R.string.upd2_apk_old, info.versionName ?: code.toString(), BuildConfig.VERSION_NAME)
        }
        // Подпись другим ключом - система откажет в установке; говорим об этом сразу.
        val mine = runCatching { signatureOf(pm, ctx.packageName, null) }.getOrNull()
        val theirs = runCatching { signatureOf(pm, null, f) }.getOrNull()
        if (mine != null && theirs != null && mine != theirs) return ctx.getString(R.string.upd2_apk_sign)
        return null
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: android.content.pm.PackageManager, f: File): android.content.pm.PackageInfo? =
        pm.getPackageArchiveInfo(f.path, 0)

    @Suppress("DEPRECATION")
    private fun versionOf(info: android.content.pm.PackageInfo): Int =
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode

    /** Отпечаток подписи (SHA-256 первого сертификата) установленного пакета или APK-файла. */
    @Suppress("DEPRECATION")
    private fun signatureOf(pm: android.content.pm.PackageManager, pkg: String?, apk: File?): String? {
        val info = if (pkg != null) {
            pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES)
        } else {
            val i = pm.getPackageArchiveInfo(apk!!.path, android.content.pm.PackageManager.GET_SIGNATURES) ?: return null
            // Без путей к самому файлу некоторые версии Android подпись архива не читают.
            i.applicationInfo?.sourceDir = apk.path
            i.applicationInfo?.publicSourceDir = apk.path
            i
        }
        val sig = info.signatures?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Скачать APK версии [rel] (с докачкой, если прервалось) и проверить его. Ссылки - по очереди
     * (из update.json, файл этой версии в релизе, файл последнего релиза, APK по API GitHub);
     * скачанный файл не тот (старая версия, чужой пакет, битый) - удаляем и пробуем следующую.
     * [progress] получает (скачано, всего) - в фоновом потоке.
     */
    private fun fetchApk(ctx: Context, rel: Release, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit): File {
        val dir = File(ctx.filesDir, "update").apply { mkdirs() }
        val target = File(dir, "StoykoMessenger-${rel.versionCode}-${rel.versionName.replace(Regex("[^0-9A-Za-z.]"), "")}.apk")
        val part = File(dir, target.name + ".part")
        val partUrl = File(dir, target.name + ".url")
        // Старые скачанные версии - удаляем.
        dir.listFiles()?.forEach { if (it != target && it != part && it != partUrl) it.delete() }
        if (target.exists()) {
            val bad = checksumProblem(ctx, rel, target) ?: apkProblem(ctx, target)
            if (bad == null) return target
            Log.w(TAG, "старый файл обновления не годится: $bad")
            target.delete()
        }
        val errors = ArrayList<String>()
        val tried = HashSet<String>()
        val candidates = ArrayList(apkCandidates(rel))
        var apiAdded = false
        var i = 0
        while (true) {
            if (cancelled.get()) throw InterruptedException()
            if (i >= candidates.size) {
                if (!apiAdded) {
                    apiAdded = true
                    runCatching { candidates += apiApkUrl(repoOf()) }
                }
                if (i >= candidates.size) throw IllegalStateException(errors.distinct().joinToString("\n").ifBlank { ctx.getString(R.string.upd2_apk_broken) })
            }
            val start = candidates[i++]
            if (!tried.add(start)) continue
            val name = start.substringAfterLast('/')
            // Докачиваем только тот же самый файл: недокачанный кусок другой ссылки - заново.
            if (part.exists() && runCatching { partUrl.readText() }.getOrNull() != start) part.delete()
            runCatching { partUrl.writeText(start) }
            val r = runCatching { downloadTo(start, part, rel, cancelled, progress) }
            val e = r.exceptionOrNull()
            if (e is InterruptedException) throw e
            if (e != null) {
                errors += "$name: ${e.message ?: e.javaClass.simpleName}"
                Log.w(TAG, "apk $start: $e")
                continue
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            partUrl.delete()
            val bad = checksumProblem(ctx, rel, target) ?: apkProblem(ctx, target)
            if (bad == null) return target
            errors += "$name: $bad"
            Log.w(TAG, "apk $start не годится: $bad")
            target.delete()
        }
    }

    private fun checksumProblem(ctx: Context, rel: Release, f: File): String? {
        val expected = rel.sha256 ?: return null
        return if (sha256(f).equals(expected, ignoreCase = true)) null else ctx.getString(R.string.update_bad_checksum)
    }

    /**
     * Быстрая загрузка: файл качается сразу в несколько потоков (каждый - свой кусок по Range).
     * Один TCP-поток на мобильной сети и через CDN GitHub часто упирается в окно/потери и даёт
     * 1–3 МБ/с; 4 параллельных куска обычно в 2–4 раза быстрее. Работает только для новой
     * загрузки; сервер не умеет Range или что-то пошло не так - false, и качаем по-старому.
     */
    private fun parallelTo(start: String, part: File, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit): Boolean {
        if (part.exists() && part.length() > 0) return false
        // Пробный запрос первого байта: узнаём размер, поддержку Range и итоговый адрес
        // (GitHub перенаправляет на подписанную ссылку CDN - её и делим, без лишних редиректов).
        var probe: HttpURLConnection? = null
        val finalUrl: String
        val total: Long
        try {
            probe = open(start)
            probe.setRequestProperty("Range", "bytes=0-0")
            if (probe.responseCode != 206) return false
            if (probe.contentType.orEmpty().startsWith("text/html")) return false
            total = probe.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: return false
            finalUrl = probe.url.toString()
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            return false
        } finally {
            runCatching { probe?.inputStream?.close() }
            probe?.disconnect()
        }
        if (total < 2L * 1024 * 1024) return false
        val n = when {
            total >= 24L * 1024 * 1024 -> 6
            total >= 8L * 1024 * 1024 -> 4
            else -> 2
        }
        val chunk = (total + n - 1) / n
        val done = java.util.concurrent.atomic.AtomicLong(0)
        val failed = AtomicBoolean(false)
        var lastReport = 0L
        val reportLock = Any()
        fun report(force: Boolean = false) {
            synchronized(reportLock) {
                val now = android.os.SystemClock.uptimeMillis()
                if (force || now - lastReport >= 120) {
                    lastReport = now
                    progress(done.get(), total)
                }
            }
        }
        java.io.RandomAccessFile(part, "rw").use { it.setLength(total) }
        report(true)
        val threads = (0 until n).map { k ->
            Thread({
                val from = k * chunk
                val to = minOf(total, from + chunk) - 1
                var pos = from
                var attempt = 0
                while (pos <= to && !failed.get()) {
                    if (cancelled.get()) { failed.set(true); return@Thread }
                    var c: HttpURLConnection? = null
                    try {
                        c = open(finalUrl)
                        c.instanceFollowRedirects = false
                        c.setRequestProperty("Range", "bytes=$pos-$to")
                        if (c.responseCode != 206) throw java.io.IOException("HTTP ${c.responseCode}")
                        java.io.RandomAccessFile(part, "rw").use { raf ->
                            raf.seek(pos)
                            c.inputStream.use { input ->
                                val buf = ByteArray(128 * 1024)
                                while (pos <= to) {
                                    if (cancelled.get() || failed.get()) return@Thread
                                    val want = minOf(buf.size.toLong(), to - pos + 1).toInt()
                                    val r = input.read(buf, 0, want)
                                    if (r < 0) throw java.io.IOException("обрыв куска $k")
                                    raf.write(buf, 0, r)
                                    pos += r
                                    done.addAndGet(r.toLong())
                                    report()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        attempt++
                        if (attempt >= 4) { failed.set(true); return@Thread }
                        runCatching { Thread.sleep(800L * attempt) }
                    } finally {
                        c?.disconnect()
                    }
                }
            }, "upd-part-$k").apply { start() }
        }
        threads.forEach { it.join() }
        if (cancelled.get()) {
            // Куски с дырами - докачать такой файл с места нельзя.
            part.delete()
            throw InterruptedException()
        }
        if (failed.get() || done.get() != total || part.length() != total) {
            Log.w(TAG, "параллельная загрузка не удалась (${done.get()}/$total) - качаем одним потоком")
            part.delete()
            return false
        }
        report(true)
        return true
    }

    /** Скачать [start] в [part] с докачкой; обрыв связи - до 3 попыток с того же места. */
    private fun downloadTo(start: String, part: File, rel: Release, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit) {
        if (runCatching { parallelTo(start, part, cancelled, progress) }.getOrElse { if (it is InterruptedException) throw it; part.delete(); false }) return
        var attempt = 0
        while (true) {
            attempt++
            var conn: HttpURLConnection? = null
            var last: Exception? = null
            for (direct in listOf(false, true)) {
                if (direct && !com.stoyko.smessenger.vpn.VpnState.proxyReady) break
                try {
                    conn = openApk(start, part, direct)
                    break
                } catch (e: IllegalStateException) {
                    // «Файла нет» (404) - одинаково по любому пути.
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
            val c = conn
            if (c == null) {
                if (attempt < 3 && last is java.io.IOException) {
                    Thread.sleep(1500L * attempt)
                    continue
                }
                throw last ?: IllegalStateException("нет связи")
            }
            try {
                val resumed = c.responseCode == 206
                if (!resumed) part.delete()
                val already = if (resumed) part.length() else 0L
                val length = c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                val total = length?.plus(already) ?: rel.size
                var done = already
                progress(done, total)
                c.inputStream.use { input ->
                    FileOutputStream(part, resumed).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (cancelled.get()) throw InterruptedException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress(done, total)
                        }
                        out.fd.sync()
                    }
                }
                // Сервер оборвал раньше времени - докачаем.
                if (length != null && done < already + length) throw java.io.IOException("обрыв: ${done}/${already + length}")
                return
            } catch (e: java.io.IOException) {
                if (attempt >= 3) throw e
                Thread.sleep(1500L * attempt)
            } finally {
                c.disconnect()
            }
        }
    }

    /** Ставить обновления сами (в фоне), если разрешена установка из этого источника. */
    fun autoInstall(ctx: Context) = prefs(ctx).getBoolean("auto_install", true)

    fun setAutoInstall(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("auto_install", v).apply()

    // ---------------- одна загрузка на всё приложение ----------------

    /**
     * Загрузка обновления. Она всегда одна: окно «Обновить» и фоновое обновление подключаются
     * к уже идущей (раньше две загрузки писали в один файл одновременно - APK получался битым).
     */
    private class Job(val rel: Release) {
        @Volatile var done = 0L
        @Volatile var total = 0L
        @Volatile var result: Result<File>? = null
        val cancelled = AtomicBoolean(false)
        val progress = java.util.concurrent.CopyOnWriteArrayList<(Long, Long) -> Unit>()
        val finished = java.util.concurrent.CopyOnWriteArrayList<(Result<File>) -> Unit>()
    }

    private val jobLock = Any()

    @Volatile
    private var job: Job? = null

    /** Загрузки - в своём потоке, чтобы проверка обновлений не ждала, пока скачается APK. */
    private val dl = Executors.newSingleThreadExecutor { Thread(it, "update-dl").apply { isDaemon = true } }

    private fun startJob(ctx: Context, rel: Release, onProgress: ((Long, Long) -> Unit)?, onDone: (Result<File>) -> Unit): Job {
        val app = ctx.applicationContext
        synchronized(jobLock) {
            val cur = job
            if (cur != null && cur.result == null && !cur.cancelled.get() && cur.rel.versionCode == rel.versionCode) {
                onProgress?.let { cur.progress += it }
                cur.finished += onDone
                return cur
            }
            cur?.cancelled?.set(true)
            val j = Job(rel)
            onProgress?.let { j.progress += it }
            j.finished += onDone
            job = j
            dl.execute {
                val r = runCatching {
                    fetchApk(app, rel, j.cancelled) { d, t ->
                        j.done = d
                        j.total = t
                        for (l in j.progress) runCatching { l(d, t) }
                    }
                }
                r.getOrNull()?.let { f -> markReady(app, rel, f) }
                synchronized(jobLock) {
                    j.result = r
                    if (job === j) job = null
                }
                main.post { for (l in j.finished) runCatching { l(r) } }
            }
            return j
        }
    }

    /** Уведомление с полосой загрузки фонового обновления (обновляется не чаще 2 раз в секунду). */
    private class ProgressNote(val ctx: Context, val rel: Release) {
        private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        private val channel = "updates_progress"
        private var last = 0L
        private val version = rel.versionName.ifBlank { rel.versionCode.toString() }
        private val open = android.app.PendingIntent.getActivity(
            ctx, Updater.PROGRESS_ID,
            android.content.Intent(ctx, com.stoyko.smessenger.MainActivity::class.java).setAction(Updater.ACTION_SHOW_UPDATE)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        init {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                runCatching {
                    nm.createNotificationChannel(
                        android.app.NotificationChannel(channel, ctx.getString(R.string.upd2_channel), android.app.NotificationManager.IMPORTANCE_LOW),
                    )
                }
            }
        }

        private fun base() = androidx.core.app.NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(0xFF7B6CF6.toInt())
            .setContentTitle(ctx.getString(R.string.upd2_progress_title, version))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)

        fun update(done: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (now - last < 500 && (total <= 0 || done < total)) return
            last = now
            val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
            val text = Formatter.formatShortFileSize(ctx, done) + if (total > 0) " / " + Formatter.formatShortFileSize(ctx, total) + " · $pct%" else ""
            runCatching {
                nm.notify(
                    Updater.PROGRESS_ID,
                    base().setContentText(text).setProgress(100, pct, total <= 0).setOngoing(true).build(),
                )
            }
        }

        fun installing() {
            runCatching {
                nm.notify(Updater.PROGRESS_ID, base().setContentText(ctx.getString(R.string.upd2_installing)).setProgress(0, 0, true).setOngoing(false).setTimeoutAfter(60_000).build())
            }
        }

        fun failed(msg: String) {
            runCatching {
                nm.notify(
                    Updater.PROGRESS_ID,
                    base().setSmallIcon(R.drawable.ic_refresh)
                        .setContentTitle(ctx.getString(R.string.upd2_failed_title, version))
                        .setContentText(ctx.getString(R.string.upd2_failed_tap))
                        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(msg + "\n\n" + ctx.getString(R.string.upd2_failed_tap)))
                        .setProgress(0, 0, false).setOngoing(false).setAutoCancel(true).build(),
                )
            }
        }

        fun cancel() = runCatching { nm.cancel(Updater.PROGRESS_ID) }
    }

    const val PROGRESS_ID = 0x5E25

    /** Установка обновления не удалась (сообщает [InstallStatusReceiver]). */
    fun onSelfInstallFailed(ctx: Context, msg: String) {
        val code = prefs(ctx).getInt("silent_code", 0)
        prefs(ctx).edit().putInt("fail_code", code).putInt("fail_from", BuildConfig.VERSION_CODE).putLong("fail_at", System.currentTimeMillis()).putString("fail_msg", msg)
            .putLong("silent_at", 0L).apply()
    }

    /**
     * Фоновое обновление: скачать (с полосой загрузки в шторке) и поставить через PackageInstaller.
     * Если система попросит подтверждение - покажет его сама (или уведомление «Нажмите, чтобы
     * установить»). false - фоновое обновление сейчас невозможно (нет разрешения, выключено или
     * эта версия недавно не поставилась) - тогда показываем обычное окно.
     */
    fun silentUpdate(ctx: Context, rel: Release): Boolean {
        val app = ctx.applicationContext
        if (!autoInstall(app) || !SessionInstaller.allowed(app)) return false
        val p = prefs(app)
        val now = System.currentTimeMillis()
        // Эту версию уже отдали установщику (ждёт подтверждения) - не повторяем.
        if (p.getInt("silent_code", 0) == rel.versionCode && now - p.getLong("silent_at", 0L) < 15 * 60_000L) return true
        // Недавно не скачалась/не поставилась - не крутим по кругу, а показываем окно с причиной.
        if (p.getInt("fail_code", 0) == rel.versionCode && now - p.getLong("fail_at", 0L) < 6 * 3600_000L) return false
        Log.i(TAG, "фоновое обновление до ${rel.versionName}")
        val note = ProgressNote(app, rel)
        startJob(app, rel, { d, t -> note.update(d, t) }) { r ->
            r.onSuccess {
                // Приложение открыто - не ставим молча посреди работы, а сразу предлагаем
                // «Установить» (системное окно установщика срабатывает с первого раза).
                val act = foreground?.get()
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    note.cancel()
                    offerReady(act)
                    return@onSuccess
                }
                p.edit().putInt("silent_code", rel.versionCode).putLong("silent_at", System.currentTimeMillis()).apply()
                note.installing()
                SessionInstaller.install(app, listOf(it), self = true)
            }.onFailure { e ->
                if (e is InterruptedException) {
                    note.cancel()
                    return@onFailure
                }
                Log.w(TAG, "фоновое скачивание: $e")
                p.edit().putInt("fail_code", rel.versionCode).putInt("fail_from", BuildConfig.VERSION_CODE).putLong("fail_at", System.currentTimeMillis()).putString("fail_msg", e.message ?: e.toString()).apply()
                note.failed(e.message ?: e.toString())
            }
        }
        return true
    }

    /** Ссылка на страницу последнего релиза - скачать APK вручную в браузере. */
    private fun releasePage() = "https://github.com/${repoOf()}/releases/latest"

    /**
     * Окно загрузки с полосой прогресса в процентах (ручная установка). Если обновление уже
     * качается в фоне - окно показывает его ход, второй загрузки нет. Затем - установка.
     */
    private fun download(ctx: Context, rel: Release, finished: ((Boolean) -> Unit)? = null) {
        val d = ctx.resources.displayMetrics.density
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = true
        }
        val percent = TextView(ctx).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            text = "0%"
        }
        val info = TextView(ctx).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setText(R.string.upd2_connecting)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (4 * d).toInt())
            addView(percent)
            addView(bar)
            addView(info)
        }
        var current: Job? = null
        var lastUi = 0L
        var startedAt = System.currentTimeMillis()
        var startBytes = -1L
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.update_downloading, rel.versionName.ifBlank { rel.versionCode.toString() }))
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> current?.cancelled?.set(true) }
            .show()
        fun show(done: Long, total: Long) {
            if (startBytes < 0) {
                startBytes = done
                startedAt = System.currentTimeMillis()
            }
            val p = if (total > 0) (done * 1000 / total).toInt().coerceIn(0, 1000) else -1
            if (p >= 0) {
                bar.isIndeterminate = false
                bar.progress = p
                percent.text = "${p / 10}%"
            }
            // Скорость и сколько осталось.
            val secs = (System.currentTimeMillis() - startedAt) / 1000.0
            val speed = if (secs > 0.8) ((done - startBytes) / secs).toLong() else 0L
            val eta = if (speed > 0 && total > done) ((total - done) / speed).toInt() else -1
            info.text = Formatter.formatShortFileSize(ctx, done) +
                (if (total > 0) " / " + Formatter.formatShortFileSize(ctx, total) else "") +
                (if (speed > 0) " · " + Formatter.formatShortFileSize(ctx, speed) + "/s" else "") +
                (if (eta >= 0) " · " + ctx.getString(R.string.upd2_eta, eta / 60, eta % 60) else "")
        }
        val job = startJob(ctx, rel, { done, total ->
            val now = System.currentTimeMillis()
            if (now - lastUi > 120 || (total > 0 && done >= total)) {
                lastUi = now
                main.post { show(done, total) }
            }
        }) { result ->
            runCatching { dialog.dismiss() }
            finished?.invoke(result.isSuccess)
            result.onSuccess { installNow(ctx, it) }.onFailure { e ->
                if (e is InterruptedException) return@onFailure
                Log.e(TAG, "download", e)
                // Понятная причина и выбор: повторить или скачать в браузере.
                runCatching {
                    AlertDialog.Builder(ctx)
                        .setTitle(ctx.getString(R.string.upd2_failed_title, rel.versionName.ifBlank { rel.versionCode.toString() }))
                        .setMessage(e.message ?: e.toString())
                        .setPositiveButton(R.string.upd2_retry) { _, _ -> download(ctx, rel, finished) }
                        .setNeutralButton(R.string.upd2_browser) { _, _ ->
                            runCatching {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(releasePage())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
        current = job
        if (job.done > 0) show(job.done, job.total)
    }

    // ---------------- скачанное обновление: «Установить» без новой загрузки ----------------

    private fun markReady(ctx: Context, rel: Release, f: File) {
        prefs(ctx).edit().putInt("ready_code", rel.versionCode).putString("ready_path", f.absolutePath)
            .putString("ready_name", rel.versionName.ifBlank { rel.versionCode.toString() })
            .putString("ready_notes", notesFor(rel).take(5000)).apply()
    }

    private fun clearReady(ctx: Context) {
        val p = prefs(ctx)
        p.getString("ready_path", null)?.let { runCatching { File(it).delete() } }
        p.edit().remove("ready_code").remove("ready_path").remove("ready_name").remove("ready_notes").remove("ready_later").apply()
    }

    /** Скачанный и проверенный APK новее установленного: (файл, versionCode) или null. */
    private fun readyApk(ctx: Context): Pair<File, Int>? {
        val p = prefs(ctx)
        val code = p.getInt("ready_code", 0)
        val path = p.getString("ready_path", null) ?: return null
        if (code <= BuildConfig.VERSION_CODE) return null
        val f = File(path)
        if (!f.isFile || f.length() == 0L) {
            clearReady(ctx)
            return null
        }
        return f to code
    }

    /** Идущая загрузка этой версии (или null). */
    private fun running(rel: Release): Job? = synchronized(jobLock) {
        job?.takeIf { it.result == null && !it.cancelled.get() && it.rel.versionCode == rel.versionCode }
    }

    /** Когда идущая загрузка закончится - предложить установить (если приложение открыто). */
    private fun whenDownloaded(rel: Release) {
        val j = running(rel) ?: return
        if (j.finished.any { it === readyListener }) return
        j.finished += readyListener
    }

    private val readyListener: (Result<File>) -> Unit = { r ->
        val act = foreground?.get()
        if (r.isSuccess && act != null && !act.isFinishing && !act.isDestroyed) offerReady(act)
    }

    @Volatile
    private var installStartedAt = 0L

    /** Только что открыли установщик - не предлагаем повторно (несколько секунд). */
    private fun installingNow() = System.currentTimeMillis() - installStartedAt < 20_000L

    /** Окно «Обновление скачано»: установить готовый файл (секунды) или позже. */
    fun offerReady(act: android.app.Activity) {
        if (offering || act.isFinishing || act.isDestroyed) return
        val (file, _) = readyApk(act) ?: return
        val p = prefs(act)
        val name = p.getString("ready_name", "").orEmpty()
        val notes = p.getString("ready_notes", "").orEmpty()
        offering = true
        runCatching {
            AlertDialog.Builder(act)
                .setTitle(act.getString(R.string.upd3_ready_title, name))
                .setMessage(act.getString(R.string.upd3_ready_text) + if (notes.isNotBlank()) "\n\n" + notes else "")
                .setPositiveButton(R.string.upd3_install) { _, _ -> installNow(act, file) }
                .setNegativeButton(R.string.upd3_later) { _, _ ->
                    p.edit().putLong("ready_later", System.currentTimeMillis() + 3600_000L).apply()
                }
                .setOnDismissListener { offering = false }
                .show()
        }.onFailure { offering = false }
    }

    /**
     * Установить скачанный APK по нажатию: системное окно установщика (Android сам показывает
     * «Обновить?» - срабатывает с первого раза, в отличие от фоновой сессии, которую система
     * может молча отложить). Нет разрешения - откроем настройку, после возврата окно появится снова.
     */
    fun installNow(ctx: Context, apk: File) {
        cancelNotification(ctx)
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(PROGRESS_ID) }
        if (!apk.isFile) {
            clearReady(ctx)
            Toast.makeText(ctx, R.string.upd2_apk_broken, Toast.LENGTH_LONG).show()
            return
        }
        if (!SessionInstaller.allowed(ctx)) {
            SessionInstaller.askPermission(ctx)
            return
        }
        installStartedAt = System.currentTimeMillis()
        Toast.makeText(ctx, R.string.update_ready, Toast.LENGTH_SHORT).show()
        val uri = FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
        com.stoyko.smessenger.ui.chat.FileTypes.install(ctx, uri, apk.name)
    }

    /** Пользователь закрыл системное подтверждение фоновой установки - предложим снова. */
    fun onSelfInstallAborted(ctx: Context) {
        prefs(ctx).edit().putLong("silent_at", 0L).remove("ready_later").apply()
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(PROGRESS_ID) }
        val act = foreground?.get()
        if (act != null && !act.isFinishing && !act.isDestroyed) main.post { offerReady(act) }
    }

    /** Нажали уведомление об обновлении: скачано - сразу «Установить», иначе - проверка. */
    fun showFromNotification(act: android.app.Activity) {
        if (readyApk(act) != null) offerReady(act) else checkNow(act)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Прежняя установка (сессией) - на случай вызова из старого кода. Нажатие «Установить» идёт через [installNow]. */
    @Suppress("unused")
    private fun install(ctx: Context, apk: File) {
        Toast.makeText(ctx, R.string.update_ready, Toast.LENGTH_LONG).show()
        // Разрешение есть - ставим системной сессией (на Android 12+ следующие обновления
        // пойдут без вопросов); нет - как раньше, через окно установщика.
        if (SessionInstaller.allowed(ctx)) {
            SessionInstaller.install(ctx, listOf(apk), self = true)
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
        com.stoyko.smessenger.ui.chat.FileTypes.install(ctx, uri, apk.name)
    }
}
