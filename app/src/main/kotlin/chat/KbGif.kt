// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.ContentInfoCompat
import androidx.core.view.OnReceiveContentListener
import androidx.core.view.ViewCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Свои GIF с клавиатуры (Gboard, SwiftKey, Samsung и др. - кнопка GIF/стикеры): картинка
 * сразу уходит в личный чат, группу или канал и у всех проигрывается по кругу прямо в ленте,
 * крупно и без пузыря - как анимированный эмодзи.
 *
 * Файл называется «kbgif_<время>.gif» (или .webp/.png) - по имени его узнают все версии
 * приложения; старые версии покажут его обычной картинкой.
 */
object KbGif {
    const val PREFIX = "kbgif_"
    private val MIME = arrayOf("image/gif", "image/webp", "image/png", "image/jpeg", "image/*")

    fun isKbGif(name: String?): Boolean = name != null && name.startsWith(PREFIX) &&
        (name.endsWith(".gif", true) || name.endsWith(".webp", true) || name.endsWith(".png", true) || name.endsWith(".jpg", true))

    /** Можно ли проиграть как анимацию (GIF или анимированный WebP). */
    fun isAnimatedName(name: String?): Boolean = name != null && (name.endsWith(".gif", true) || (isKbGif(name) && name.endsWith(".webp", true)))

    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "kbgif").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Принимать картинки и GIF из клавиатуры в поле ввода. [send] получает готовый файл
     * (content://-адрес нашего провайдера) - его остаётся отправить как стикер.
     */
    fun attach(edit: EditText, send: (Uri) -> Unit) {
        val listener = OnReceiveContentListener { view, payload ->
            val parts = payload.partition { it.uri != null }
            val media = parts.first
            val rest = parts.second
            if (media != null) {
                val ctx = view.context.applicationContext
                val clip = media.clip
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                io.execute {
                    for (u in uris) {
                        val out = runCatching { copy(ctx, u) }.getOrNull()
                        main.post {
                            if (out != null) {
                                send(com.stoyko.smessenger.ui.chat.AttachLauncher.providerUri(ctx, out))
                            } else {
                                Toast.makeText(ctx, com.stoyko.smessenger.R.string.kbgif_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            rest
        }
        runCatching { ViewCompat.setOnReceiveContentListener(edit, MIME, listener) }
    }

    private fun copy(ctx: Context, u: Uri): File? {
        val cr = ctx.contentResolver
        val type = runCatching { cr.getType(u) }.getOrNull().orEmpty().lowercase()
        val head = ByteArray(12)
        val bytes = cr.openInputStream(u)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty() || bytes.size > 25 * 1024 * 1024) return null
        System.arraycopy(bytes, 0, head, 0, minOf(12, bytes.size))
        // Тип - по содержимому (клавиатуры иногда пишут неверный mime).
        val ext = when {
            head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() -> "gif"
            head[0] == 'R'.code.toByte() && head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() -> "webp"
            head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "png"
            type.endsWith("gif") -> "gif"
            type.endsWith("webp") -> "webp"
            type.endsWith("png") -> "png"
            else -> "jpg"
        }
        val dir = File(ctx.filesDir, "outgoing/gifs").apply { mkdirs() }
        val f = File(dir, "$PREFIX${System.currentTimeMillis()}.$ext")
        f.writeBytes(bytes)
        return f
    }

    // ---------------------------------------------------------------- проигрывание

    private val TAG = com.stoyko.smessenger.R.id.tag_gif

    /** Снять анимацию с картинки (строка переиспользуется для другого сообщения). */
    fun unbind(iv: ImageView) {
        if (iv.getTag(TAG) == null) return
        iv.setTag(TAG, null)
        (iv.drawable as? Animatable)?.let { runCatching { it.stop() } }
    }

    /**
     * Проиграть GIF/WebP по кругу в [iv]. [src] - путь к файлу или адрес (file://, content://).
     * [maxPx] - наибольшая сторона. Возвращает false, если это не анимация (грузите как обычно).
     */
    fun bind(iv: ImageView, src: String, name: String?, maxPx: Int): Boolean {
        if (!isAnimatedName(name)) {
            unbind(iv)
            return false
        }
        if (iv.getTag(TAG) == src && iv.drawable is Animatable) {
            if (animate()) (iv.drawable as Animatable).start()
            return true
        }
        iv.setTag(TAG, src)
        runCatching { com.squareup.picasso.Picasso.get().cancelRequest(iv) }
        val ctx = iv.context.applicationContext
        io.execute {
            val dr = runCatching { decode(ctx, src, maxPx) }.getOrNull()
            main.post {
                if (iv.getTag(TAG) != src) return@post
                if (dr == null) {
                    // Не анимация или повреждена - хотя бы первый кадр.
                    runCatching { com.squareup.picasso.Picasso.get().load(toUri(src)).resize(maxPx, maxPx).centerInside().onlyScaleDown().into(iv) }
                    return@post
                }
                iv.setImageDrawable(dr)
                if (animate()) (dr as? Animatable)?.start()
            }
        }
        return true
    }

    private fun animate(): Boolean = !com.stoyko.smessenger.ui.EnergySaver.on

    private fun toUri(src: String): Uri = if (src.startsWith("/")) Uri.fromFile(File(src)) else Uri.parse(src)

    private fun decode(ctx: Context, src: String, maxPx: Int): Drawable? {
        val uri = toUri(src)
        if (Build.VERSION.SDK_INT >= 28) {
            val source = if (uri.scheme == "file" && uri.path != null) {
                android.graphics.ImageDecoder.createSource(File(uri.path!!))
            } else {
                android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
            }
            val dr = android.graphics.ImageDecoder.decodeDrawable(source) { dec, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val k = minOf(1f, maxPx.toFloat() / maxOf(w, h).coerceAtLeast(1))
                if (k < 1f) dec.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
                dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
            if (dr is android.graphics.drawable.AnimatedImageDrawable) dr.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
            return dr
        }
        // Android 7-8: GIF через Movie (WebP-анимацию старые системы не умеют - покажется кадр).
        if (!src.endsWith(".gif", true) && !(uri.lastPathSegment ?: "").endsWith(".gif", true)) return null
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        @Suppress("DEPRECATION")
        val movie = android.graphics.Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (movie.width() <= 0 || movie.height() <= 0) return null
        return MovieDrawable(movie, minOf(1f, maxPx.toFloat() / maxOf(movie.width(), movie.height())))
    }

    @Suppress("DEPRECATION")
    private class MovieDrawable(private val movie: android.graphics.Movie, private val k: Float) : Drawable(), Animatable {
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)
        private var running = false
        private val start = SystemClock.uptimeMillis()
        override fun getIntrinsicWidth() = (movie.width() * k).toInt()
        override fun getIntrinsicHeight() = (movie.height() * k).toInt()
        override fun draw(c: Canvas) {
            val dur = movie.duration().takeIf { it > 0 } ?: 1000
            movie.setTime(((SystemClock.uptimeMillis() - start) % dur).toInt())
            val b = bounds
            val s = minOf(b.width().toFloat() / movie.width(), b.height().toFloat() / movie.height())
            c.save()
            c.translate(b.left + (b.width() - movie.width() * s) / 2, b.top + (b.height() - movie.height() * s) / 2)
            c.scale(s, s)
            movie.draw(c, 0f, 0f, p)
            c.restore()
            if (running) scheduleSelf({ invalidateSelf() }, SystemClock.uptimeMillis() + 40)
        }
        override fun start() {
            running = true
            invalidateSelf()
        }
        override fun stop() {
            running = false
        }
        override fun isRunning() = running
        override fun setAlpha(alpha: Int) {
            p.alpha = alpha
        }
        override fun setColorFilter(colorFilter: ColorFilter?) {
            p.colorFilter = colorFilter
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
