// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.player.VideoPlayerActivity
import com.stoyko.smessenger.vpn.VpnState
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Сеть для «Каналов» и «Кино»: запросы через VPN приложения (если включён), а не вышло -
 * напрямую; маленький кэш логотипов и обложек.
 */
internal object MediaNet {
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    val io = Executors.newFixedThreadPool(3) { Thread(it, "media-hub").apply { isDaemon = true } }
    val main = Handler(Looper.getMainLooper())
    private val images = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()

    private fun open(url: String, direct: Boolean): HttpURLConnection {
        val c = URL(url).openConnection(if (direct) java.net.Proxy.NO_PROXY else VpnState.httpProxy()) as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 25_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    fun bytes(url: String, limit: Int = 12 * 1024 * 1024): ByteArray {
        fun via(direct: Boolean): ByteArray {
            val c = open(url, direct)
            try {
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                return c.inputStream.use { inp ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > limit) break
                    }
                    out.toByteArray()
                }
            } finally {
                c.disconnect()
            }
        }
        return try {
            via(false)
        } catch (e: java.io.IOException) {
            if (VpnState.proxyReady) via(true) else throw e
        }
    }

    fun text(url: String): String = String(bytes(url), Charsets.UTF_8)

    /** Картинка в [target] (в списке строка может переиспользоваться - сверяем метку). */
    fun image(target: ImageView, url: String?, sizePx: Int) {
        target.tag = url
        target.setImageDrawable(null)
        if (url.isNullOrBlank()) return
        images.get(url)?.let {
            target.setImageBitmap(it)
            return
        }
        if (synchronized(failed) { url in failed }) return
        io.execute {
            val bmp = runCatching {
                val data = bytes(url, 2 * 1024 * 1024)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= sizePx && o.outHeight / (s * 2) >= sizePx) s *= 2
                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
            if (bmp == null) {
                synchronized(failed) { failed += url }
                return@execute
            }
            images.put(url, bmp)
            main.post { if (target.tag == url) target.setImageBitmap(bmp) }
        }
    }
}

/**
 * Общий каркас экранов «Каналы» и «Кино»: шапка в цветах оформления, поиск, ряд
 * фильтров-«чипсов», список с картинками и индикатор загрузки.
 */
internal class HubScreen(private val ctx: Context, title: Int, emoji: String) {
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
    val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
    val dialog = Dialog(ctx, R.style.AppTheme_FullDialog)
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val search: EditText
    val chips = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(10), dp(8), dp(10), dp(4))
    }
    val list = ListView(ctx).apply {
        divider = null
        clipToPadding = false
        setPadding(0, dp(4), 0, dp(16))
        isFastScrollEnabled = true
    }
    val spinner = ProgressBar(ctx).apply { visibility = View.GONE }
    val empty = TextView(ctx).apply {
        textSize = 15f
        setTextColor(cSub)
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(40), dp(24), dp(24))
        visibility = View.GONE
    }
    private val accent = Seasonal.accent(ctx) ?: intArrayOf(0xFF8B7CFF.toInt(), 0xFF5B4BDB.toInt())

    init {
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, Seasonal.headerColors(Seasonal.current(ctx)) ?: intArrayOf(0xFF7B6CF6.toInt(), 0xFF3A2DAF.toInt()))
            setPadding(dp(6), dp(6), dp(12), dp(12))
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = null
            contentDescription = ctx.getString(R.string.close)
            setOnClickListener { dialog.safeDismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(TextView(ctx).apply {
            text = emoji + "  " + ctx.getString(title)
            textSize = 20f
            paint.isFakeBoldText = true
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(bar)
        search = EditText(ctx).apply {
            textSize = 15f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.WHITE)
            setHintTextColor(0xBBFFFFFF.toInt())
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            compoundDrawablesRelative[0]?.setTint(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 22 * d
                setColor(0x33FFFFFF)
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        header.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), dp(4), 0, 0) })
        root.addView(header)
        Seasonal.decorateHeader(header, garland = false)
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        val body = android.widget.FrameLayout(ctx)
        body.addView(list, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(empty, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        body.addView(spinner, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.fitSystemBars()
        dialog.setContentView(root)
    }

    private val chipViews = ArrayList<TextView>()

    fun chip(label: String, selected: Boolean, click: () -> Unit): TextView {
        val v = TextView(ctx).apply {
            text = label
            textSize = 13.5f
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener {
                chipViews.forEach { styleChip(it, it === this) }
                click()
            }
        }
        styleChip(v, selected)
        chipViews += v
        chips.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        return v
    }

    private fun styleChip(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.WHITE else cText)
        v.background = if (on) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, accent).apply { cornerRadius = 18 * d }
        } else {
            GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor((accent[1] and 0x00FFFFFF) or 0x1F000000)
            }
        }
    }

    fun onSearch(action: (String) -> Unit, live: Boolean) {
        search.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                action(search.text.toString().trim())
                true
            } else {
                false
            }
        }
        if (live) {
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = action(s?.toString()?.trim().orEmpty())
            })
        }
    }

    fun loading(on: Boolean) {
        spinner.visibility = if (on) View.VISIBLE else View.GONE
        if (on) empty.visibility = View.GONE
    }

    fun emptyText(text: String?) {
        empty.text = text.orEmpty()
        empty.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** Строка списка: картинка слева, заголовок и подзаголовок. */
    fun row(convert: View?, wide: Boolean): Triple<View, ImageView, Pair<TextView, TextView>> {
        @Suppress("UNCHECKED_CAST")
        (convert?.getTag(R.id.tag_season) as? Triple<View, ImageView, Pair<TextView, TextView>>)?.let { return it }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = ContextCompat.getDrawable(ctx, android.R.drawable.list_selector_background)
        }
        val img = ImageView(ctx).apply {
            scaleType = if (wide) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor((accent[1] and 0x00FFFFFF) or 0x22000000)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 12 * d)
            }
            if (!wide) setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        row.addView(img, if (wide) LinearLayout.LayoutParams(dp(78), dp(104)) else LinearLayout.LayoutParams(dp(56), dp(56)))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val t1 = TextView(ctx).apply {
            textSize = 16f
            paint.isFakeBoldText = true
            setTextColor(cText)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val t2 = TextView(ctx).apply {
            textSize = 13f
            setTextColor(cSub)
            maxLines = if (wide) 4 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        texts.addView(t1)
        texts.addView(t2)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(ctx).apply {
            text = "▶"
            textSize = 16f
            setTextColor(accent[1])
            setPadding(dp(8), 0, dp(4), 0)
        })
        val t = Triple<View, ImageView, Pair<TextView, TextView>>(row, img, t1 to t2)
        row.setTag(R.id.tag_season, t)
        return t
    }

    fun show() {
        DialogTracker.add(dialog)
        runCatching { dialog.show() }
    }
}

/**
 * 📡 Каналы: открытые ТВ-каналы из каталога iptv-org (github.com/iptv-org/iptv) - по стране
 * или по теме. Плейлист кэшируется на 12 часов; канал играет во встроенном плеере (HLS).
 */
object Iptv {
    class Channel(val name: String, val logo: String?, val group: String, val url: String)

    private class Source(val label: Int, val path: String)

    private val SOURCES = listOf(
        Source(R.string.tv_src_ru, "countries/ru.m3u"),
        Source(R.string.tv_src_news, "categories/news.m3u"),
        Source(R.string.tv_src_movies, "categories/movies.m3u"),
        Source(R.string.tv_src_kids, "categories/kids.m3u"),
        Source(R.string.tv_src_music, "categories/music.m3u"),
        Source(R.string.tv_src_sport, "categories/sports.m3u"),
        Source(R.string.tv_src_docs, "categories/documentary.m3u"),
        Source(R.string.tv_src_by, "countries/by.m3u"),
        Source(R.string.tv_src_kz, "countries/kz.m3u"),
        Source(R.string.tv_src_world, "index.m3u"),
    )
    private const val BASE = "https://iptv-org.github.io/iptv/"
    private const val TTL = 12L * 3600 * 1000

    /** Разбор M3U: #EXTINF:-1 tvg-logo="…" group-title="…",Имя → следующая строка - адрес. */
    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group = ""
        val attr = Regex("([a-zA-Z-]+)=\"([^\"]*)\"")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val comma = line.lastIndexOf(',')
                name = if (comma >= 0) line.substring(comma + 1).trim() else null
                val attrs = attr.findAll(if (comma >= 0) line.substring(0, comma) else line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
                group = attrs["group-title"].orEmpty()
            } else if (!line.startsWith("#")) {
                if (name != null && (line.startsWith("http://") || line.startsWith("https://"))) {
                    out += Channel(name, logo, group.replace(";", " · "), line)
                }
                name = null
                logo = null
                group = ""
            }
        }
        return out
    }

    private fun load(ctx: Context, path: String, force: Boolean): List<Channel> {
        val f = File(ctx.cacheDir, "iptv_" + path.replace('/', '_'))
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < TTL) {
            runCatching { return parse(f.readText()) }
        }
        return try {
            val t = MediaNet.text(BASE + path)
            runCatching { f.writeText(t) }
            parse(t)
        } catch (e: Exception) {
            // Нет сети - старый кэш лучше, чем ничего.
            if (f.exists()) parse(f.readText()) else throw e
        }
    }

    fun show(ctx: Context) {
        val s = HubScreen(ctx, R.string.tv_title, "📡")
        s.search.setHint(R.string.tv_search_hint)
        var all: List<Channel> = emptyList()
        var shown: List<Channel> = emptyList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val (row, img, texts) = s.row(convert, wide = false)
                val c = shown[p]
                texts.first.text = c.name
                texts.second.text = c.group.ifBlank { ctx.getString(R.string.tv_live) }
                MediaNet.image(img, c.logo, s.dp(56))
                return row
            }
        }
        s.list.adapter = adapter
        fun applyFilter(q: String) {
            shown = if (q.isBlank()) all else all.filter { it.name.contains(q, ignoreCase = true) || it.group.contains(q, ignoreCase = true) }
            adapter.notifyDataSetChanged()
            s.emptyText(if (shown.isEmpty() && all.isNotEmpty()) ctx.getString(R.string.tv_nothing) else null)
        }
        var gen = 0
        fun openSource(src: Source, force: Boolean = false) {
            val my = ++gen
            s.loading(true)
            all = emptyList()
            applyFilter("")
            MediaNet.io.execute {
                val r = runCatching { load(ctx.applicationContext, src.path, force) }
                MediaNet.main.post {
                    if (my != gen || !s.dialog.isShowing) return@post
                    s.loading(false)
                    r.onSuccess {
                        all = it
                        applyFilter(s.search.text.toString().trim())
                        if (it.isEmpty()) s.emptyText(ctx.getString(R.string.tv_nothing))
                    }.onFailure { s.emptyText(ctx.getString(R.string.hub_net_error)) }
                }
            }
        }
        SOURCES.forEachIndexed { i, src -> s.chip(ctx.getString(src.label), i == 0) { openSource(src) } }
        s.onSearch(::applyFilter, live = true)
        s.list.setOnItemClickListener { _, _, p, _ ->
            val c = shown.getOrNull(p) ?: return@setOnItemClickListener
            // Весь показанный список - в плеер: там работают «предыдущий / следующий канал».
            val items = shown.map { VideoPlayerActivity.Item(it.name, it.url) }
            runCatching { VideoPlayerActivity.startList(ctx, items, p, Uri.parse(c.url), c.name) }
        }
        s.empty.setOnClickListener { openSource(SOURCES[0], force = true) }
        s.show()
        openSource(SOURCES[0])
    }
}

/**
 * 🎬 Кино: фильмы из открытой коллекции archive.org (feature_films). Поиск по названию,
 * для фильма выбирается MP4 (H.264 / MPEG4) наибольшего размера - и он играет во встроенном плеере.
 */
object Movies {
    class Movie(val id: String, val title: String, val description: String, val year: String)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun anyText(o: JSONObject, key: String): String {
        val a = o.optJSONArray(key)
        if (a != null) return if (a.length() > 0) a.optString(0) else ""
        return o.optString(key)
    }

    /** Шаг 1: поиск (пустой запрос - самые популярные). */
    fun searchMovies(query: String): List<Movie> {
        val q = if (query.isBlank()) {
            "mediatype:(movies) AND collection:(feature_films)"
        } else {
            "title:(${query.replace("(", " ").replace(")", " ")}) AND mediatype:(movies) AND collection:(feature_films)"
        }
        val url = "https://archive.org/advancedsearch.php?q=" + enc(q) +
            "&fl[]=identifier&fl[]=title&fl[]=description&fl[]=year" +
            "&sort[]=" + enc("downloads desc") + "&rows=" + (if (query.isBlank()) 40 else 30) + "&output=json"
        val docs = JSONObject(MediaNet.text(url)).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val out = ArrayList<Movie>()
        for (i in 0 until docs.length()) {
            val o = docs.optJSONObject(i) ?: continue
            val id = o.optString("identifier")
            if (id.isBlank()) continue
            val desc = android.text.Html.fromHtml(anyText(o, "description")).toString().replace(Regex("\\s+"), " ").trim()
            out += Movie(id, anyText(o, "title").ifBlank { id }, desc, anyText(o, "year"))
        }
        return out
    }

    /** Шаг 2: прямая ссылка на лучший .mp4 фильма (или null). */
    fun getStreamUrl(identifier: String): String? {
        val files = JSONObject(MediaNet.text("https://archive.org/metadata/${enc(identifier)}/files")).optJSONArray("result") ?: return null
        var best: String? = null
        var bestSize = -1L
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name")
            val format = f.optString("format")
            val ok = format.equals("h.264", true) || format.equals("MPEG4", true) || format.contains("h.264", true) || name.endsWith(".mp4", true)
            if (!ok) continue
            val size = f.optString("size").toLongOrNull() ?: f.optLong("size", 0L)
            if (size > bestSize) {
                bestSize = size
                best = name
            }
        }
        val name = best ?: return null
        return "https://archive.org/download/${enc(identifier)}/" + Uri.encode(name, "/")
    }

    fun show(ctx: Context) {
        val s = HubScreen(ctx, R.string.movies_title, "🎬")
        s.search.setHint(R.string.movies_search_hint)
        var shown: List<Movie> = emptyList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val (row, img, texts) = s.row(convert, wide = true)
                val m = shown[p]
                texts.first.text = if (m.year.isNotBlank()) "${m.title} (${m.year.take(4)})" else m.title
                texts.second.text = m.description.ifBlank { ctx.getString(R.string.movies_no_desc) }
                MediaNet.image(img, "https://archive.org/services/img/${m.id}", s.dp(104))
                return row
            }
        }
        s.list.adapter = adapter
        var gen = 0
        fun runSearch(q: String) {
            val my = ++gen
            s.loading(true)
            shown = emptyList()
            adapter.notifyDataSetChanged()
            MediaNet.io.execute {
                val r = runCatching { searchMovies(q) }
                MediaNet.main.post {
                    if (my != gen || !s.dialog.isShowing) return@post
                    s.loading(false)
                    r.onSuccess {
                        shown = it
                        adapter.notifyDataSetChanged()
                        s.emptyText(if (it.isEmpty()) ctx.getString(R.string.movies_nothing) else null)
                    }.onFailure { s.emptyText(ctx.getString(R.string.hub_net_error)) }
                }
            }
        }
        // Быстрые подборки.
        val presets = listOf(
            R.string.movies_popular to "",
            R.string.movies_comedy to "comedy",
            R.string.movies_horror to "horror",
            R.string.movies_western to "western",
            R.string.movies_noir to "noir",
            R.string.movies_scifi to "space",
            R.string.movies_chaplin to "chaplin",
        )
        presets.forEachIndexed { i, (label, q) -> s.chip(ctx.getString(label), i == 0) { s.search.setText(""); runSearch(q) } }
        s.onSearch(::runSearch, live = false)
        var opening = false
        s.list.setOnItemClickListener { _, _, p, _ ->
            val m = shown.getOrNull(p) ?: return@setOnItemClickListener
            if (opening) return@setOnItemClickListener
            opening = true
            s.loading(true)
            MediaNet.io.execute {
                val url = runCatching { getStreamUrl(m.id) }.getOrNull()
                MediaNet.main.post {
                    opening = false
                    s.loading(false)
                    if (url == null) {
                        Toast.makeText(ctx, R.string.movies_no_file, Toast.LENGTH_LONG).show()
                    } else {
                        // Список фильмов - в плеер (адрес следующего фильма узнаётся при переключении).
                        val items = shown.map { mv -> VideoPlayerActivity.Item(mv.title, if (mv.id == m.id) url else null) { getStreamUrl(mv.id) } }
                        runCatching { VideoPlayerActivity.startList(ctx, items, p, Uri.parse(url), m.title) }
                    }
                }
            }
        }
        s.empty.setOnClickListener { runSearch(s.search.text.toString().trim()) }
        s.show()
        runSearch("")
    }
}
