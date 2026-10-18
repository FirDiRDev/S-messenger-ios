// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.widget.ImageView
import kotlin.math.PI
import kotlin.math.sin

/**
 * Фото и видео «под спойлером»: в чате, группе и канале картинка скрыта размытой пеленой
 * с мерцающими искрами и открывается по нажатию (пелена рассыпается и тает).
 *
 * Метка - в имени файла: «SPOILER_имя.jpg». Так спойлер одинаково виден у всех получателей,
 * в личных чатах, группах и каналах, и не нужен отдельный протокол.
 */
object MediaSpoiler {
    const val PREFIX = "SPOILER_"

    fun isSpoiler(name: String?): Boolean = name?.startsWith(PREFIX, ignoreCase = true) == true

    /** Имя файла для отправки под спойлером. */
    fun mark(name: String): String = if (isSpoiler(name)) name else PREFIX + name

    /** Имя без метки (для подписей и сохранения). */
    fun clean(name: String): String = if (isSpoiler(name)) name.substring(PREFIX.length) else name

    /** Весь текст под спойлер: каждая строка в «||…||» (искры растворяются по нажатию). */
    fun wrapText(t: String): String = t.trim().lines().joinToString("\n") { l ->
        if (l.isBlank()) l else "||" + l.replace("||", "| |") + "||"
    }

    /** Уже открытые (до перезапуска приложения). Ключ - путь или адрес файла. */
    private val opened = java.util.Collections.synchronizedSet(HashSet<String>())

    fun isHidden(name: String?, key: String?): Boolean = isSpoiler(name) && key != null && key !in opened

    /** Привязать пелену к превью (или снять, если файл не под спойлером или уже открыт). */
    fun bind(iv: ImageView, name: String?, key: String?) {
        val hidden = isHidden(name, key)
        val cur = veilOf(iv)
        if (!hidden) {
            if (cur != null) detach(iv, cur)
            return
        }
        if (cur != null && cur.key == key) {
            cur.reset()
            return
        }
        if (cur != null) detach(iv, cur)
        attach(iv, Veil(iv, key!!))
    }

    /** Открыть по нажатию. true - это был скрытый спойлер (нажатие поглощено). */
    fun reveal(iv: ImageView?, name: String?, key: String?): Boolean {
        if (!isHidden(name, key)) return false
        opened += key!!
        val v = iv?.let { veilOf(it) }
        if (v == null) {
            iv?.invalidate()
            return true
        }
        iv.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        v.startReveal { detach(iv, v) }
        return true
    }

    private fun veilOf(iv: ImageView): Veil? = iv.getTag(TAG) as? Veil

    private fun attach(iv: ImageView, v: Veil) {
        iv.setTag(TAG, v)
        if (Build.VERSION.SDK_INT >= 23) {
            iv.foreground = v
        } else {
            v.setBounds(0, 0, iv.width, iv.height)
            iv.overlay.add(v)
            iv.addOnLayoutChangeListener(v.layoutListener)
        }
        iv.invalidate()
    }

    private fun detach(iv: ImageView, v: Veil) {
        iv.setTag(TAG, null)
        v.stopped = true
        if (Build.VERSION.SDK_INT >= 23) {
            if (iv.foreground === v) iv.foreground = null
        } else {
            iv.overlay.remove(v)
            iv.removeOnLayoutChangeListener(v.layoutListener)
        }
        iv.invalidate()
    }

    private val TAG = com.stoyko.smessenger.R.id.tag_spoiler

    /**
     * Пелена: та же картинка, ужатая до 14 точек по ширине и растянутая обратно (дешёвое и
     * красивое размытие без RenderScript), затемнение и 60 мерцающих искр, плывущих по кругу.
     */
    private class Veil(val iv: ImageView, val key: String) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private var src: Bitmap? = null
        private var small: Bitmap? = null
        private val start = SystemClock.uptimeMillis()
        private var revealAt = 0L
        private var onDone: (() -> Unit)? = null
        var stopped = false
        private val lite = runCatching { com.stoyko.smessenger.ui.LiteMode.on || !com.stoyko.smessenger.ui.AnimPrefs.on(com.stoyko.smessenger.ui.AnimPrefs.K.SPARKS) }.getOrDefault(false)
        private val rnd = java.util.Random(key.hashCode().toLong())
        private val n = if (lite) 26 else 60
        private val px = FloatArray(n) { rnd.nextFloat() }
        private val py = FloatArray(n) { rnd.nextFloat() }
        private val ph = FloatArray(n) { rnd.nextFloat() * 6.28f }
        private val sp = FloatArray(n) { 0.4f + rnd.nextFloat() * 0.9f }
        private val sz = FloatArray(n) { 0.6f + rnd.nextFloat() * 1.4f }
        val layoutListener = android.view.View.OnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> setBounds(0, 0, r - l, b - t) }

        fun reset() {
            revealAt = 0L
            stopped = false
            invalidateSelf()
        }

        fun startReveal(done: () -> Unit) {
            onDone = done
            revealAt = SystemClock.uptimeMillis()
            if (lite) {
                done()
                return
            }
            invalidateSelf()
        }

        private fun blurred(): Bitmap? {
            val b = (iv.drawable as? BitmapDrawable)?.bitmap ?: return null
            if (b.isRecycled || b.width <= 0) return null
            if (b !== src || small?.isRecycled != false) {
                src = b
                small = runCatching {
                    val w = 14
                    val h = (14f * b.height / b.width).toInt().coerceIn(4, 40)
                    Bitmap.createScaledBitmap(b, w, h, true)
                }.getOrNull()
            }
            return small
        }

        override fun draw(c: Canvas) {
            if (stopped) return
            val r = bounds
            if (r.width() <= 0) return
            val now = SystemClock.uptimeMillis()
            val k = if (revealAt > 0) ((now - revealAt) / 520f).coerceIn(0f, 1f) else 0f
            if (k >= 1f) {
                stopped = true
                onDone?.invoke()
                onDone = null
                return
            }
            val fade = 1f - k
            // Размытая картинка: при открытии растворяется кругом из центра.
            val bm = blurred()
            val save = c.save()
            if (k > 0f) {
                val path = android.graphics.Path()
                val rad = kotlin.math.hypot(r.width().toFloat(), r.height().toFloat()) * k
                path.addRect(RectF(r), android.graphics.Path.Direction.CW)
                path.addCircle(r.exactCenterX(), r.exactCenterY(), rad, android.graphics.Path.Direction.CCW)
                path.fillType = android.graphics.Path.FillType.EVEN_ODD
                c.clipPath(path)
            }
            p.alpha = 255
            if (bm != null) {
                c.drawBitmap(bm, null, r, p)
                p.color = 0x66000000
                c.drawRect(r, p)
            } else {
                p.color = 0xFF2A2540.toInt()
                c.drawRect(r, p)
            }
            c.restoreToCount(save)
            // Искры.
            val t = (now - start) / 1000f
            val d = iv.resources.displayMetrics.density
            p.color = 0xFFFFFFFF.toInt()
            for (i in 0 until n) {
                val ang = ph[i] + t * sp[i]
                var x = r.left + (((px[i] + 0.03f * sin(ang * 1.3f)) % 1f + 1f) % 1f) * r.width()
                var y = r.top + (((py[i] + 0.03f * kotlin.math.cos(ang)) % 1f + 1f) % 1f) * r.height()
                if (k > 0f) {
                    // Искры разлетаются от центра.
                    val dx = x - r.exactCenterX()
                    val dy = y - r.exactCenterY()
                    x += dx * k * 0.8f
                    y += dy * k * 0.8f
                }
                val tw = 0.5f + 0.5f * sin(ang * 2.2f)
                p.alpha = (255 * tw * fade).toInt().coerceIn(0, 255)
                c.drawCircle(x, y, sz[i] * d * (0.6f + 0.6f * tw), p)
            }
            // Подсказка в центре.
            if (k == 0f && r.height() > 60 * d) {
                text.textSize = 13f * d
                text.color = 0xFFFFFFFF.toInt()
                text.textAlign = Paint.Align.CENTER
                text.isFakeBoldText = true
                val label = SPOILER_LABEL()
                val tw = text.measureText(label)
                val cx = r.exactCenterX()
                val cy = r.exactCenterY()
                p.color = 0x88000000.toInt()
                p.alpha = 0x88
                c.drawRoundRect(RectF(cx - tw / 2 - 12 * d, cy - 14 * d, cx + tw / 2 + 12 * d, cy + 12 * d), 13 * d, 13 * d, p)
                c.drawText(label, cx, cy + 4.5f * d, text)
            }
            if (!lite || k > 0f) invalidateSelfLater()
        }

        private fun invalidateSelfLater() {
            if (stopped) return
            if (iv.isAttachedToWindow) iv.postInvalidateOnAnimation()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun SPOILER_LABEL(): String = if (java.util.Locale.getDefault().language == "ru") "✨ Спойлер" else "✨ Spoiler"
}
