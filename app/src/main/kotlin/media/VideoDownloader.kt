// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.media

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.UploadBar
import com.stoyko.smessenger.vpn.VpnState
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Загрузчик видео с YouTube (в т. ч. Shorts) и Pinterest прямо в галерею телефона
 * (папка «Movies/S-messenger», фото Pinterest - «Pictures/S-messenger»).
 *
 * Как находит файл:
 *  - Pinterest: открывает страницу пина и берёт прямую ссылку на видео (лучшее качество)
 *    или оригинал картинки;
 *  - YouTube: через открытые зеркала (Invidious / Piped) - они отдают готовый файл MP4 со
 *    звуком; зеркал несколько, если одно не отвечает - пробуем следующее. Свои зеркала можно
 *    добавить в окне загрузчика.
 * Скачивание идёт через VPN приложения, если он включён.
 */
object VideoDownloader {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private val main = Handler(Looper.getMainLooper())

    private val INVIDIOUS = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be",
        "https://invidious.privacyredirect.com",
        "https://iv.melmac.space",
        "https://inv.tux.pizza",
        "https://invidious.jing.rocks",
        "https://invidious.private.coffee",
        "https://yt.artemislena.eu",
        "https://invidious.f5.si",
        "https://iv.datura.network",
        "https://invidious.protokolla.fi",
        "https://invidious.perennialte.ch",
        "https://invidious.materialio.us",
    )
    private val PIPED = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.r4fo.com",
        "https://pipedapi.leptons.xyz",
        "https://piped-api.lunar.icu",
        "https://pipedapi.drgns.space",
        "https://api.piped.yt",
        "https://pipedapi.reallyaweso.me",
    )

    /** Что скачивать: 0 - видео, 1 - только звук (M4A). */
    private fun mode(ctx: Context) = prefs(ctx).getInt("mode", 0)

    /** Предел качества видео: 0 - лучшее, иначе высота кадра (720, 480, 360). */
    private fun cap(ctx: Context) = prefs(ctx).getInt("cap", 0)

    /**
     * Живой список зеркал Invidious из официального каталога (обновляется раз в 12 часов):
     * если встроенные зеркала закрылись, находятся новые рабочие.
     */
    private fun liveMirrors(ctx: Context): List<String> {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("live_at", 0L) < 12 * 3600_000L) {
            return p.getString("live", "").orEmpty().lines().filter { it.startsWith("http") }
        }
        val list = runCatching {
            val arr = org.json.JSONArray(text("https://api.invidious.io/instances.json?sort_by=health"))
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONArray(i) ?: return@mapNotNull null
                val info = item.optJSONObject(1) ?: return@mapNotNull null
                val uri = info.optString("uri")
                if (info.optString("type") == "https" && info.optBoolean("api", false) && uri.startsWith("https")) uri.trimEnd('/') else null
            }.take(20)
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) p.edit().putLong("live_at", now).putString("live", list.joinToString("\n")).apply()
        return list
    }

    /** Последнее зеркало, которое сработало, - пробуем его первым. */
    private fun ordered(ctx: Context, base: List<String>): List<String> {
        val last = prefs(ctx).getString("last_ok", null)
        return (listOfNotNull(last?.takeIf { it in base }) + base).distinct()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("video_dl", Context.MODE_PRIVATE)
    private fun customMirrors(ctx: Context): List<String> =
        prefs(ctx).getString("mirrors", "").orEmpty().lines().map { it.trim().trimEnd('/') }.filter { it.startsWith("http") }

    fun supports(url: String?): Boolean {
        val u = url?.lowercase() ?: return false
        return YouTube.videoId(u) != null || "pinterest." in u || "pin.it/" in u
    }

    /** Окно загрузчика: ссылка (подставляется из буфера), «Скачать», свои зеркала. */
    fun show(ctx: Context, initial: String? = null) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val clip = runCatching {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        }.getOrNull()
        val field = EditText(ctx).apply {
            setHint(R.string.vdl_hint)
            setSingleLine()
            textSize = 14f
            setText(initial?.takeIf { supports(it) } ?: clip?.takeIf { supports(it) }.orEmpty())
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(TextView(ctx).apply {
                setText(R.string.vdl_text)
                textSize = 13f
                setPadding(0, 0, 0, dp(8))
            })
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Что скачивать и в каком качестве (запоминается).
            val kinds = android.widget.RadioGroup(ctx).apply {
                orientation = android.widget.RadioGroup.HORIZONTAL
                listOf(R.string.vdl_kind_video, R.string.vdl_kind_audio).forEachIndexed { i, res ->
                    addView(android.widget.RadioButton(ctx).apply { id = 100 + i; setText(res); textSize = 13f })
                }
                check(100 + mode(ctx))
                setOnCheckedChangeListener { _, id -> prefs(ctx).edit().putInt("mode", id - 100).apply() }
            }
            addView(kinds)
            val caps = intArrayOf(0, 720, 480, 360)
            val quality = android.widget.RadioGroup(ctx).apply {
                orientation = android.widget.RadioGroup.HORIZONTAL
                caps.forEachIndexed { i, c ->
                    addView(android.widget.RadioButton(ctx).apply {
                        id = 200 + i
                        text = if (c == 0) ctx.getString(R.string.vdl_best) else "${c}p"
                        textSize = 13f
                    })
                }
                check(200 + caps.indexOf(cap(ctx)).coerceAtLeast(0))
                setOnCheckedChangeListener { _, id -> prefs(ctx).edit().putInt("cap", caps[id - 200]).apply() }
            }
            addView(android.widget.HorizontalScrollView(ctx).apply { addView(quality) })
        }
        // Кнопки - внутри окна, во всю ширину: на узких экранах и с крупным шрифтом
        // три кнопки диалога не помещались, и «Скачать» обрезалась.
        lateinit var dlg: AlertDialog
        box.addView(
            com.stoyko.smessenger.ui.Btn.nice(ctx, "⬇️ " + ctx.getString(R.string.vdl_download), intArrayOf(0xFF22C55E.toInt(), 0xFF0E7490.toInt())) {
                val url = field.text.toString().trim()
                if (!supports(url)) {
                    Toast.makeText(ctx, R.string.vdl_bad_link, Toast.LENGTH_LONG).show()
                } else {
                    runCatching { dlg.dismiss() }
                    download(ctx, url)
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) },
        )
        box.addView(
            com.stoyko.smessenger.ui.Btn.row(
                ctx,
                listOf(
                    com.stoyko.smessenger.ui.Btn.tonal(ctx, ctx.getString(R.string.vdl_mirrors)) {
                        runCatching { dlg.dismiss() }
                        editMirrors(ctx)
                    },
                    com.stoyko.smessenger.ui.Btn.tonal(ctx, ctx.getString(android.R.string.cancel)) { runCatching { dlg.dismiss() } },
                ),
                height = 46,
                gap = 10,
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(14)
            },
        )
        dlg = AlertDialog.Builder(ctx)
            .setTitle(R.string.vdl_title)
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .create()
        com.stoyko.smessenger.ui.DialogTracker.add(dlg).show()
    }

    private fun editMirrors(ctx: Context) {
        val field = EditText(ctx).apply {
            setHint(R.string.vdl_mirrors_hint)
            setText(customMirrors(ctx).joinToString("\n"))
            minLines = 3
            textSize = 13f
        }
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.vdl_mirrors)
            .setView(LinearLayout(ctx).apply { setPadding(pad, 0, pad, 0); addView(field) })
            .setPositiveButton(R.string.save) { _, _ -> prefs(ctx).edit().putString("mirrors", field.text.toString()).apply() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Скачать: плашка с прогрессом снизу, в конце - «Сохранено в галерею». */
    fun download(ctx: Context, url: String) {
        val bar = UploadBar.show(ctx, ctx.getString(R.string.vdl_resolving))
        val app = ctx.applicationContext
        Thread({
            try {
                val target = resolve(app, url)
                bar?.title(app.getString(R.string.vdl_downloading, target.title.take(40)))
                val saved = save(app, target) { p -> bar?.set(p) }
                main.post {
                    val msg = app.getString(
                        when {
                            target.audio -> R.string.vdl_saved_audio
                            target.video -> R.string.vdl_saved_video
                            else -> R.string.vdl_saved_photo
                        },
                    )
                    if (bar != null) bar.done(msg) else Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                    com.stoyko.smessenger.ui.Kinetics.activityOf(ctx)?.let { offerOpen(it, saved, target.video || target.audio) }
                }
            } catch (e: Exception) {
                val msg = app.getString(R.string.vdl_failed, e.message ?: e.javaClass.simpleName)
                main.post { if (bar != null) bar.fail(msg) else Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
                com.stoyko.smessenger.domain.Diagnostics.record("загрузка видео", e, fatal = false)
            }
        }, "video-dl").start()
    }

    private fun offerOpen(ctx: Context, uri: Uri, video: Boolean) {
        runCatching {
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, if (video) "video/*" else "image/*")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val root = (ctx as? android.app.Activity)?.findViewById<android.view.View>(android.R.id.content) ?: return
            com.google.android.material.snackbar.Snackbar.make(root, R.string.vdl_saved_short, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction(R.string.vdl_open) { runCatching { ctx.startActivity(view) } }
                .show()
        }
    }

    // ---------------- поиск файла ----------------

    class Target(val url: String, val title: String, val video: Boolean, val ext: String, val audio: Boolean = false)

    private fun open(url: String, accept: String = "*/*", direct: Boolean = false): HttpURLConnection {
        val c = URL(url).openConnection(if (direct) java.net.Proxy.NO_PROXY else VpnState.httpProxy()) as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("Accept-Language", "ru,en;q=0.8")
        return c
    }

    /** Запрос; не вышло через VPN приложения - ещё раз напрямую (и наоборот помогает зеркалам). */
    private fun text(url: String): String = try {
        textVia(url, false)
    } catch (e: java.io.IOException) {
        if (VpnState.proxyReady) textVia(url, true) else throw e
    }

    private fun textVia(url: String, direct: Boolean): String {
        val c = open(url, "text/html,application/json", direct)
        try {
            // Короткие ссылки pin.it и т. п.: переходим по редиректам вручную (между доменами).
            var conn = c
            var hops = 0
            while (conn.responseCode in 300..399 && hops < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = open(URL(URL(url), loc).toString(), "text/html", direct)
                hops++
            }
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun resolve(ctx: Context, url: String): Target {
        val id = YouTube.videoId(url)
        return if (id != null) youtube(ctx, id) else pinterest(url)
    }

    private fun pinterest(url: String): Target {
        val html = text(url)
        val title = Regex("<title>([^<]{1,120})</title>").find(html)?.groupValues?.get(1)?.trim().orEmpty()
            .ifBlank { "Pinterest" }
        // Видео: прямые MP4 на v1.pinimg.com / v.pinimg.com - берём самое высокое качество.
        val mp4 = Regex("https?:\\\\?/\\\\?/v1?\\.pinimg\\.com[^\"'\\s]+?\\.mp4").findAll(html)
            .map { it.value.replace("\\/", "/").replace("\\u002F", "/") }
            .distinct()
            .sortedByDescending { u -> Regex("(\\d{3,4})p").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: if ("V_EXP" in u) 1080 else 0 }
            .firstOrNull()
        if (mp4 != null) return Target(mp4, title, true, "mp4")
        // Картинка: оригинал.
        val img = Regex("https://i\\.pinimg\\.com/originals/[^\"'\\s]+?\\.(jpg|jpeg|png|webp|gif)").find(html)?.value
            ?: Regex("https://i\\.pinimg\\.com/736x/[^\"'\\s]+?\\.(jpg|jpeg|png|webp)").find(html)?.value
            ?: throw IllegalStateException("на странице нет видео или фото")
        return Target(img, title, false, img.substringAfterLast('.').take(4))
    }

    private fun youtube(ctx: Context, id: String): Target {
        val errors = ArrayList<String>()
        val audio = mode(ctx) == 1
        val cap = cap(ctx)
        fun ok(base: String) = prefs(ctx).edit().putString("last_ok", base).apply()
        /** Лучшее качество не выше предела (если ниже предела ничего нет - самое маленькое). */
        fun pick(cands: List<Pair<Int, String>>): String? {
            if (cands.isEmpty()) return null
            val under = cands.filter { cap == 0 || it.first <= cap }
            return (under.maxByOrNull { it.first } ?: cands.minByOrNull { it.first })?.second
        }
        // Invidious: formatStreams - готовые файлы со звуком; adaptiveFormats - отдельный звук.
        val inv = (customMirrors(ctx) + ordered(ctx, INVIDIOUS) + liveMirrors(ctx)).distinct()
        for (base in inv) {
            runCatching {
                val o = JSONObject(text("$base/api/v1/videos/$id?local=true"))
                val title = o.optString("title", "YouTube")
                if (audio) {
                    val ad = o.optJSONArray("adaptiveFormats") ?: throw IllegalStateException("нет потоков")
                    val best = (0 until ad.length()).map { ad.getJSONObject(it) }
                        .filter { it.optString("type").startsWith("audio/mp4") }
                        .maxByOrNull { it.optString("bitrate").toLongOrNull() ?: 0L }
                        ?: throw IllegalStateException("нет звука M4A")
                    val u = best.optString("url")
                    ok(base)
                    return Target(if (u.startsWith("/")) base + u else u, title, false, "m4a", audio = true)
                }
                val streams = o.optJSONArray("formatStreams") ?: throw IllegalStateException("нет потоков")
                val cands = (0 until streams.length()).map { streams.getJSONObject(it) }
                    .filter { it.optString("type").contains("mp4") }
                    .map { (it.optString("qualityLabel", it.optString("resolution")).filter { c -> c.isDigit() }.toIntOrNull() ?: 0) to it.optString("url") }
                val u = pick(cands) ?: throw IllegalStateException("нет MP4")
                ok(base)
                return Target(if (u.startsWith("/")) base + u else u, title, true, "mp4")
            }.onFailure { errors += it.message.orEmpty() }
        }
        // Piped: videoStreams с videoOnly=false, audioStreams - звук.
        for (base in ordered(ctx, PIPED)) {
            runCatching {
                val o = JSONObject(text("$base/streams/$id"))
                val title = o.optString("title", "YouTube")
                if (audio) {
                    val au = o.optJSONArray("audioStreams") ?: throw IllegalStateException("нет звука")
                    val best = (0 until au.length()).map { au.getJSONObject(it) }
                        .filter { it.optString("mimeType").contains("mp4") }
                        .maxByOrNull { it.optInt("bitrate") } ?: throw IllegalStateException("нет звука M4A")
                    ok(base)
                    return Target(best.optString("url"), title, false, "m4a", audio = true)
                }
                val streams = o.optJSONArray("videoStreams") ?: throw IllegalStateException("нет потоков")
                val cands = (0 until streams.length()).map { streams.getJSONObject(it) }
                    .filter { !it.optBoolean("videoOnly", true) && it.optString("mimeType").contains("mp4") }
                    .map { (it.optString("quality").filter { c -> c.isDigit() }.toIntOrNull() ?: 0) to it.optString("url") }
                val u = pick(cands) ?: throw IllegalStateException("нет MP4 со звуком")
                ok(base)
                return Target(u, title, true, "mp4")
            }.onFailure { errors += it.message.orEmpty() }
        }
        throw IllegalStateException(ctx.getString(R.string.vdl_no_mirror))
    }

    // ---------------- сохранение ----------------

    private fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(60).ifBlank { "video" }

    private fun save(ctx: Context, t: Target, progress: (Float) -> Unit): Uri {
        val c = open(t.url)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            val name = safeName(t.title) + "_" + System.currentTimeMillis() / 1000 + "." + t.ext
            val mime = when {
                t.audio -> "audio/mp4"
                t.video -> "video/mp4"
                else -> "image/" + (if (t.ext == "jpg") "jpeg" else t.ext)
            }
            val (uri, out) = createOutput(ctx, name, mime, t.video, t.audio)
            var ok = false
            try {
                out.use { o ->
                    c.inputStream.use { input ->
                        val buf = ByteArray(128 * 1024)
                        var done = 0L
                        var last = 0f
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = done.toFloat() / total
                                if (p - last > 0.02f) {
                                    last = p
                                    progress(p)
                                }
                            }
                        }
                    }
                }
                ok = true
            } finally {
                finishOutput(ctx, uri, ok)
            }
            return uri
        } finally {
            c.disconnect()
        }
    }

    private fun createOutput(ctx: Context, name: String, mime: String, video: Boolean, audio: Boolean = false): Pair<Uri, OutputStream> {
        // Звук - в «Музыку», видео - в «Фильмы», фото - в «Картинки» (папка S-messenger).
        val dirName = when {
            audio -> Environment.DIRECTORY_MUSIC
            video -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_PICTURES
        }
        val resolver = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "$dirName/S-messenger",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (audio) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else if (video) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val uri = resolver.insert(collection, values) ?: throw IllegalStateException("галерея недоступна")
            return uri to (resolver.openOutputStream(uri) ?: throw IllegalStateException("нет доступа к галерее"))
        }
        // Android 9 и старше: без разрешения на память пишем в папку приложения на карте/памяти,
        // галерея увидит файл после сканирования.
        val dir = File(
            ctx.getExternalFilesDir(dirName) ?: ctx.filesDir,
            "S-messenger",
        ).apply { mkdirs() }
        val f = File(dir, name)
        return Uri.fromFile(f) to f.outputStream()
    }

    private fun finishOutput(ctx: Context, uri: Uri, ok: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            if (ok) {
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } else {
                runCatching { ctx.contentResolver.delete(uri, null, null) }
            }
        } else if (ok) {
            // Старые Android: сообщаем галерее о новом файле.
            uri.path?.let { android.media.MediaScannerConnection.scanFile(ctx, arrayOf(it), null, null) }
        } else {
            uri.path?.let { File(it).delete() }
        }
    }
}
