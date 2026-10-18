// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.core.content.FileProvider
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.BreakIterator
import com.stoyko.smessenger.BuildConfig

/**
 * Анимированные эмодзи и встроенные наборы стикеров из открытого доступа:
 *  - Noto Animated Emoji (Google), лицензия CC BY 4.0 - https://googlefonts.github.io/noto-emoji-animation/
 *  - Twemoji (Twitter / jdecked), лицензия CC BY 4.0 - запасной статичный вариант.
 * Картинки и анимации загружаются по сети при первом показе и кэшируются.
 */
object AnimatedEmoji {
    private const val TAG = "AnimatedEmoji"
    private const val NOTO = "https://fonts.gstatic.com/s/e/notoemoji/latest/"
    private const val TWEMOJI = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@latest/assets/72x72/"

    /** Код эмодзи для адреса: «1f600», «2764_fe0f». */
    fun code(emoji: String, keepFe0f: Boolean = true): String {
        val out = ArrayList<String>()
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            i += Character.charCount(cp)
            if (!keepFe0f && cp == 0xFE0F) continue
            out += Integer.toHexString(cp)
        }
        return out.joinToString("_")
    }

    fun imageUrl(code: String) = "$NOTO$code/512.webp"
    fun twemojiUrl(code: String) = TWEMOJI + code.split('_').filter { it != "fe0f" }.joinToString("-") + ".png"

    /** Сообщение - ровно один эмодзи (для анимации). */
    fun isSingleEmoji(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 16) return false
        val it = BreakIterator.getCharacterInstance()
        it.setText(t)
        var count = 0
        while (it.next() != BreakIterator.DONE) count++
        return count == 1 && t.codePointAt(0) >= 0x2190
    }

    private val pool = Executors.newFixedThreadPool(3) { Thread(it, "emoji-anim").apply { isDaemon = true } }

    // Отдельный поток для фоновой подкачки - не мешает показу эмодзи, которые нужны прямо сейчас.
    private val prefetchPool = Executors.newSingleThreadExecutor { Thread(it, "emoji-prefetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val inFlight = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Уже скачанный файл анимации (без сети). */
    fun cached(ctx: Context, code: String): File? {
        val dir = File(ctx.cacheDir, "emoji")
        val noFe = code.split('_').filter { it != "fe0f" }.joinToString("_")
        return listOf(code, noFe).distinct().map { File(dir, "$it.webp") }.firstOrNull { it.length() > 0 }
    }

    /** Заранее скачать анимации (популярные, недавние) - потом они открываются мгновенно. */
    fun prefetch(ctx: Context, codes: List<String>) {
        val app = ctx.applicationContext
        codes.distinct().forEach { code ->
            if (cached(app, code) != null || !inFlight.add(code)) return@forEach
            prefetchPool.execute {
                try {
                    fetch(app, code)
                } finally {
                    inFlight.remove(code)
                }
            }
        }
    }

    /** Срочная подкачка (знакомство, экскурсия): в основном пуле, параллельно. */
    fun prefetchNow(ctx: Context, codes: List<String>) {
        val app = ctx.applicationContext
        codes.distinct().forEach { code ->
            if (cached(app, code) != null || !inFlight.add(code)) return@forEach
            pool.execute {
                try {
                    fetch(app, code)
                } finally {
                    inFlight.remove(code)
                }
            }
        }
    }

    /**
     * Один раз в сутки (лучше по Wi-Fi) подкачиваем все встроенные наборы и недавние эмодзи:
     * панель стикеров и анимации в чате больше не «грузятся» каждый раз.
     */
    fun warmUp(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("emoji_warmup", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("at", 0L) < 86_400_000L) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        val metered = cm?.isActiveNetworkMetered ?: true
        prefs.edit().putLong("at", now).apply()
        val recent = com.stoyko.smessenger.ui.chat.RecentEmoji.list(app).map { code(it) }
        val packs = StickerPacks.all.flatMap { it.codes }
        // На мобильном интернете - только недавние и первый набор, по Wi-Fi - все наборы.
        prefetch(app, recent + if (metered) StickerPacks.all.firstOrNull()?.codes.orEmpty() else packs)
    }
    private val main = Handler(Looper.getMainLooper())

    /** Анимированный WebP (Noto) в кэше приложения; скачивается один раз. Не в главном потоке. */
    private fun fetch(ctx: Context, code: String): File? {
        val dir = File(ctx.cacheDir, "emoji").apply { mkdirs() }
        val noFe = code.split('_').filter { it != "fe0f" }.joinToString("_")
        for (c in listOf(code, noFe).distinct()) {
            val f = File(dir, "$c.webp")
            if (f.length() > 0) return f
            val miss = File(dir, "$c.miss")
            // Эмодзи без анимации не запрашиваем снова в течение суток.
            if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < 86_400_000L) continue
            try {
                val conn = URL(imageUrl(c)).openConnection(
                    if (com.stoyko.smessenger.vpn.VpnState.proxyReady) com.stoyko.smessenger.vpn.VpnState.httpProxy() else java.net.Proxy.NO_PROXY,
                ) as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
                try {
                    if (conn.responseCode in 200..299) {
                        val tmp = File(dir, "$c.tmp")
                        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        if (tmp.length() > 0 && tmp.renameTo(f)) return f
                    } else if (conn.responseCode == 404) {
                        miss.writeText("")
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetch $c: $e")
            }
        }
        return null
    }

    private fun decode(ctx: Context, file: File, sizePx: Int): Drawable? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            // Android 9+: настоящая анимация (AnimatedImageDrawable), уменьшенная до нужного размера.
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.setTargetSize(sizePx, sizePx)
            }
        } else {
            // Старые Android: первый кадр.
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.let { BitmapDrawable(ctx.resources, it) }
        }
    } catch (e: Throwable) {
        Log.d(TAG, "decode: $e")
        null
    }

    /**
     * Проиграть анимацию эмодзи в [view] ([loop]: сколько повторов, -1 - бесконечно).
     * [onReady] - анимация загружена (можно прятать обычный эмодзи). Нет анимации - ничего не меняется.
     */
    fun play(view: ImageView, code: String, loop: Int = 2, onReady: () -> Unit) {
        view.tag = code
        val ctx = view.context.applicationContext
        val size = (view.layoutParams?.width?.takeIf { it > 0 } ?: (112 * ctx.resources.displayMetrics.density).toInt())
            .coerceIn(64, 512)
        pool.execute {
            val file = fetch(ctx, code) ?: return@execute
            val d = decode(ctx, file, size) ?: return@execute
            main.post {
                if (view.tag != code) return@post
                onReady()
                view.setImageDrawable(d)
                view.visibility = View.VISIBLE
                if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) {
                    d.repeatCount = when {
                        // Экономия энергии: один-два проигрыша вместо бесконечного.
                        com.stoyko.smessenger.ui.EnergySaver.on -> 1
                        loop < 0 -> AnimatedImageDrawable.REPEAT_INFINITE
                        else -> loop
                    }
                    d.start()
                }
            }
        }
    }

    /** Эмодзи сообщения по отдельности (графемы без пробелов), например «🔥😂» → [🔥, 😂]. */
    fun split(text: String): List<String> {
        val t = text.trim()
        val out = ArrayList<String>()
        val it = BreakIterator.getCharacterInstance()
        it.setText(t)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val g = t.substring(start, end)
            if (g.isNotBlank()) out += g
            start = end
            end = it.next()
        }
        return if (out.all { it.codePointAt(0) >= 0x2190 }) out else emptyList()
    }

    /** Размер картинки эмодзи (квадрат) для 1, 2 и 3 эмодзи в сообщении. */
    private fun cellDp(n: Int) = when (n) {
        1 -> 112
        2 -> 92
        else -> 78
    }

    /**
     * 1-3 эмодзи сообщением: каждый - своя анимация, в ряд, с поочерёдным появлением.
     * Если ни у одного нет готовой анимации - ничего не меняем (остаётся текст с движением).
     */
    fun playRow(view: ImageView, emojis: List<String>, loop: Int = 2, onReady: () -> Unit) {
        if (emojis.size == 1) {
            resize(view, 1)
            play(view, code(emojis[0]), loop, onReady)
            return
        }
        val key = "row:" + emojis.joinToString("|")
        view.tag = key
        val ctx = view.context.applicationContext
        val cell = (cellDp(emojis.size) * ctx.resources.displayMetrics.density).toInt()
        pool.execute {
            val files = emojis.map { fetch(ctx, code(it)) }
            if (files.all { it == null }) return@execute
            val parts = emojis.mapIndexed { i, e -> files[i]?.let { decode(ctx, it, cell) } ?: textDrawable(ctx, e, cell) }
            main.post {
                if (view.tag != key) return@post
                onReady()
                resize(view, emojis.size)
                val row = RowDrawable(parts, cell)
                view.setImageDrawable(row)
                view.visibility = View.VISIBLE
                parts.forEach { d ->
                    if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) {
                        d.repeatCount = when {
                            com.stoyko.smessenger.ui.EnergySaver.on -> 1
                            loop < 0 -> AnimatedImageDrawable.REPEAT_INFINITE
                            else -> loop
                        }
                        d.start()
                    }
                }
            }
        }
    }

    private fun resize(view: ImageView, n: Int) {
        val lp = view.layoutParams ?: return
        val cell = (cellDp(n) * view.resources.displayMetrics.density).toInt()
        val w = cell * n
        if (lp.width != w || lp.height != cell) {
            lp.width = w
            lp.height = cell
            view.layoutParams = lp
        }
    }

    /**
     * Несколько эмодзи в одной картинке: ячейки в ряд, каждая появляется по очереди
     * с «пружинкой». Анимации ячеек перерисовывают общий Drawable.
     */
    private class RowDrawable(private val parts: List<Drawable>, private val cell: Int) :
        Drawable(),
        Drawable.Callback {
        private val born = android.os.SystemClock.uptimeMillis()

        init {
            parts.forEach { it.callback = this }
        }

        override fun getIntrinsicWidth() = cell * parts.size
        override fun getIntrinsicHeight() = cell

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val w = b.width().toFloat() / parts.size.coerceAtLeast(1)
            val h = b.height().toFloat()
            val side = minOf(w, h)
            val age = android.os.SystemClock.uptimeMillis() - born
            var settling = false
            parts.forEachIndexed { i, d ->
                // Поочерёдное появление: 120 мс сдвиг, 420 мс «пружинка».
                val p = ((age - i * 120L) / 420f).coerceIn(0f, 1f)
                if (p < 1f) settling = true
                val s = if (p <= 0f) 0f else 1f - kotlin.math.exp(-5f * p) * kotlin.math.cos(p * 9f)
                val l = b.left + i * w + (w - side) / 2
                val t = b.top + (h - side) / 2
                d.setBounds(l.toInt(), t.toInt(), (l + side).toInt(), (t + side).toInt())
                canvas.save()
                canvas.scale(s, s, l + side / 2, t + side / 2)
                d.draw(canvas)
                canvas.restore()
            }
            if (settling) invalidateSelf()
        }

        override fun setAlpha(alpha: Int) = parts.forEach { it.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = parts.forEach { it.colorFilter = colorFilter }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

        fun stopAll() = parts.forEach { (it as? Animatable)?.stop() }

        override fun invalidateDrawable(who: Drawable) = invalidateSelf()
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
    }

    /**
     * Живой эмодзи как Drawable (например, эмодзи-статус рядом с именем). Нет анимации - рисуем
     * обычный эмодзи картинкой. [done] вызывается в главном потоке.
     */
    fun drawable(ctx: Context, emoji: String, sizePx: Int, done: (Drawable) -> Unit) {
        val app = ctx.applicationContext
        pool.execute {
            val file = fetch(app, code(emoji))
            val d = file?.let { decode(app, it, sizePx) } ?: textDrawable(app, emoji, sizePx)
            main.post {
                d.setBounds(0, 0, sizePx, sizePx)
                if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) {
                    // Несколько повторов, а не бесконечно: живой эмодзи в шапке не держит
                    // экран в постоянной перерисовке (это заметно ест батарею).
                    d.repeatCount = if (com.stoyko.smessenger.ui.EnergySaver.on) 0 else if (com.stoyko.smessenger.ui.LiteMode.on) 1 else 2
                    d.start()
                }
                done(d)
            }
        }
    }

    private fun textDrawable(ctx: Context, emoji: String, size: Int): Drawable {
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.8f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        android.graphics.Canvas(bmp).drawText(emoji, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return BitmapDrawable(ctx.resources, bmp)
    }

    /** Поставить живой эмодзи справа от текста (имя в профиле, заголовок чата). Пусто - убрать. */
    fun attachStatus(view: android.widget.TextView, emoji: String?, sizeDp: Int = 26) {
        val e = emoji?.trim().orEmpty()
        // Уже показан этот же эмодзи - не перезагружаем.
        if (view.tag == "es:$e" && (e.isEmpty() || view.compoundDrawablesRelative[2] != null)) return
        view.tag = "es:$e"
        if (e.isEmpty()) {
            view.setCompoundDrawablesRelative(null, null, null, null)
            return
        }
        val size = (sizeDp * view.resources.displayMetrics.density).toInt()
        drawable(view.context, e, size) { d ->
            if (view.tag != "es:$e") return@drawable
            view.compoundDrawablePadding = (6 * view.resources.displayMetrics.density).toInt()
            view.setCompoundDrawablesRelative(null, null, d, null)
            // AnimatedImageDrawable перерисовывает себя через callback - его ставит TextView.
        }
    }

    fun stop(view: ImageView) {
        view.tag = null
        (view.drawable as? Animatable)?.stop()
        (view.drawable as? RowDrawable)?.let { row -> row.stopAll() }
        view.setImageDrawable(null)
        view.visibility = View.GONE
    }

    /**
     * Картинка эмодзи для сетки стикеров. Из кэша - мгновенно; если файла ещё нет, сразу
     * показываем лёгкую картинку (Twemoji 72 px), а анимацию Noto скачиваем в кэш и подменяем.
     * Раньше каждая ячейка каждый раз заново качала большой файл 512 px - отсюда долгая загрузка.
     */
    fun loadImage(view: ImageView, code: String, size: Int) {
        view.tag = "img:$code"
        val ctx = view.context.applicationContext
        val file = cached(ctx, code)
        if (file != null) {
            Picasso.get().load(file).resize(size, size).centerInside().noFade().into(view)
            return
        }
        Picasso.get().load(twemojiUrl(code)).resize(size, size).centerInside().into(view)
        if (!inFlight.add(code)) return
        pool.execute {
            val f = try {
                fetch(ctx, code)
            } finally {
                inFlight.remove(code)
            }
            if (f != null) {
                main.post {
                    if (view.tag == "img:$code") Picasso.get().load(f).resize(size, size).centerInside().noFade().into(view)
                }
            }
        }
    }

    // ---------- Стикеры из встроенных наборов ----------

    private val STICKER_NAME = Regex("^sticker_noto_([0-9a-f_]+)_\\d+\\.(webp|png)$")

    /** Код эмодзи из имени файла встроенного стикера (или null - обычный стикер). */
    fun stickerCode(fileName: String): String? = STICKER_NAME.find(fileName)?.groupValues?.get(1)

    /**
     * Скачать картинку стикера и подготовить к отправке: sticker_noto_<код>_<время>.webp.
     * Получатель покажет его анимированным (по коду), даже если у него нет этого набора. Не в главном потоке.
     */
    fun prepareSticker(ctx: Context, code: String): Uri? {
        val dir = File(ctx.filesDir, "outgoing/stickers").apply { mkdirs() }
        for ((url, ext) in listOf(imageUrl(code) to "webp", twemojiUrl(code) to "png")) {
            try {
                val conn = URL(url).openConnection(if (com.stoyko.smessenger.vpn.VpnState.proxyReady) com.stoyko.smessenger.vpn.VpnState.httpProxy() else java.net.Proxy.NO_PROXY) as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                try {
                    if (conn.responseCode !in 200..299) continue
                    val out = File(dir, "sticker_noto_${code}_${System.currentTimeMillis()}.$ext")
                    conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                    if (out.length() > 0) {
                        return FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", out)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "sticker $url: $e")
            }
        }
        return null
    }
}

/** Встроенный набор стикеров: название и эмодзи (коды Noto). */
data class StickerPack(val title: String, val codes: List<String>)

object StickerPacks {
    private fun c(vararg s: String) = s.toList()

    val all = listOf(
        StickerPack(
            com.stoyko.smessenger.ai.t("😀 Эмоции", "😀 Emotions"),
            c(
                "1f600", "1f603", "1f604", "1f601", "1f606", "1f605", "1f602", "1f923", "1f60a", "1f607", "1f642", "1f643",
                "1f609", "1f60c", "1f60d", "1f970", "1f618", "1f60b", "1f61b", "1f61c", "1f92a", "1f60e", "1f929", "1f973",
                "1f60f", "1f612", "1f61e", "1f614", "1f615", "1f97a", "1f622", "1f62d", "1f624", "1f620", "1f621", "1f92c",
                "1f92f", "1f633", "1f975", "1f976", "1f631", "1f628", "1f630", "1f613", "1f917", "1f914", "1f92d", "1f92b",
                "1f636", "1f610", "1f62c", "1f644", "1f62e", "1f632", "1f971", "1f634", "1f924", "1f635", "1f974", "1f922",
                "1f92e", "1f927", "1f637", "1f911", "1f920", "1f608", "1f921", "1f4a9", "1f47b", "1f480", "1f47d", "1f916",
            ),
        ),
        StickerPack(
            com.stoyko.smessenger.ai.t("❤️ Любовь", "❤️ Love"),
            c(
                "2764_fe0f", "1f9e1", "1f49b", "1f49a", "1f499", "1f49c", "1f5a4", "1f90d", "1f494", "1f495", "1f49e", "1f493",
                "1f497", "1f496", "1f498", "1f49d", "1f48b", "1f63b", "1f491", "1f339", "1f60d", "1f970", "1f618", "2763_fe0f",
            ),
        ),
        StickerPack(
            com.stoyko.smessenger.ai.t("👍 Жесты", "👍 Gestures"),
            c(
                "1f44d", "1f44e", "1f44f", "1f64c", "1f64f", "1f91d", "1f44b", "270c_fe0f", "1f91e", "1f918", "1f919", "1f44c",
                "1f90c", "1f4aa", "1f448", "1f449", "1f446", "1f447", "1f44a", "270a", "1f91b", "1f91c", "1f450", "1f485",
            ),
        ),
        StickerPack(
            com.stoyko.smessenger.ai.t("🐱 Животные", "🐱 Animals"),
            c(
                "1f436", "1f431", "1f42d", "1f439", "1f430", "1f98a", "1f43b", "1f43c", "1f428", "1f42f", "1f981", "1f42e",
                "1f437", "1f438", "1f435", "1f648", "1f649", "1f64a", "1f414", "1f427", "1f426", "1f425", "1f986", "1f989",
                "1f987", "1f43a", "1f434", "1f984", "1f41d", "1f98b", "1f40c", "1f41e", "1f422", "1f40d", "1f996", "1f419",
                "1f980", "1f420", "1f42c", "1f433", "1f988", "1f40a",
            ),
        ),
        StickerPack(
            com.stoyko.smessenger.ai.t("🎉 Праздник", "🎉 Party"),
            c(
                "1f389", "1f38a", "1f388", "1f381", "1f382", "1f386", "1f387", "2728", "1f525", "1f4af", "1f4a5", "1f31f",
                "1f3c6", "1f947", "1f37e", "1f942", "1f37b", "1f380", "1f384", "1f383", "1f973", "1f680", "2b50", "1f308",
            ),
        ),
        StickerPack(
            com.stoyko.smessenger.ai.t("🍕 Еда", "🍕 Food"),
            c(
                "1f355", "1f354", "1f35f", "1f32d", "1f32e", "1f35c", "1f363", "1f370", "1f369", "1f36a", "1f366", "1f37f",
                "2615", "1f37a", "1f377", "1f34e", "1f34c", "1f349", "1f353", "1f352", "1f351", "1f951", "1f336_fe0f", "1f9c0",
            ),
        ),
        StickerPack(
            com.stoyko.smessenger.ai.t("🌈 Природа", "🌈 Nature"),
            c(
                "2600_fe0f", "1f31e", "1f31d", "1f319", "2b50", "1f320", "1f308", "26a1", "2744_fe0f", "1f30a", "1f32a_fe0f", "1f327_fe0f",
                "1f340", "1f337", "1f33b", "1f338", "1f339", "1f342", "1f335", "1f30b", "1f30d", "1fa90", "1f4a7", "2603_fe0f",
            ),
        ),
        StickerPack(
            com.stoyko.smessenger.ai.t("⚽ Спорт и хобби", "⚽ Sports & hobby"),
            c(
                "26bd", "1f3c0", "1f3c8", "26be", "1f3be", "1f3d0", "1f3b3", "1f94a", "1f3af", "1f3c1", "1f6b4", "1f3c3",
                "1f3ae", "1f3b2", "265f_fe0f", "1f3b8", "1f3b9", "1f941", "1f3b5", "1f3a4", "1f3ac", "1f3a8", "1f4f8", "1f3a3",
            ),
        ),
        StickerPack(
            com.stoyko.smessenger.ai.t("✈️ Путешествия", "✈️ Travel"),
            c(
                "2708_fe0f", "1f680", "1f6f8", "1f697", "1f695", "1f68c", "1f682", "1f6a2", "26f5", "1f6b2", "1f3cd_fe0f", "1f6a8",
                "1f5fa_fe0f", "1f9ed", "1f3d6_fe0f", "1f3d4_fe0f", "26fa", "1f3e0", "1f3a1", "1f3a2", "1f5fd", "1f5fc", "1f9f3", "1f4cd",
            ),
        ),
        StickerPack(
            com.stoyko.smessenger.ai.t("💼 Работа и бизнес", "💼 Work & business"),
            c(
                "1f4bc", "1f4b0", "1f4b8", "1f4b5", "1f4b3", "1f4c8", "1f4c9", "1f4ca", "1f4e2", "1f4e3", "1f514", "1f4a1",
                "1f4dd", "1f4c5", "23f0", "23f3", "1f4de", "1f4e7", "1f50d", "1f512", "1f511", "2705", "274c", "26a0_fe0f",
                "1f91d", "1f4af", "1f3af", "1f9e0", "1f4bb", "1f5a8_fe0f", "1f4e6", "1f6d2",
            ),
        ),
    )
}
