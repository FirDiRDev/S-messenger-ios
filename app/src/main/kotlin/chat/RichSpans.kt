// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.DynamicDrawableSpan
import android.text.style.QuoteSpan
import android.text.style.UpdateAppearance
import android.text.style.CharacterStyle
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.stoyko.smessenger.ui.EnergySaver
import com.stoyko.smessenger.ui.LiteMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * «Живые» части оформления:
 *  - спойлер: текст скрыт мерцающими искрами (шум из частиц), по нажатию частицы разлетаются
 *    и плавно растворяются, открывая текст;
 *  - сворачиваемая цитата: видна первая строка и «▾ Развернуть»; по нажатию высота текста
 *    плавно меняется (анимация макета), «▴ Свернуть» - обратно;
 *  - живые эмодзи прямо в строке: анимированная картинка (Noto) встаёт на место символа,
 *    не ломая перенос строк и разметку вокруг.
 * [attach] подключает всё это к TextView после установки текста.
 */
object RichSpans {
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ спойлер

    /** Скрытый текст: пока не открыт - прозрачный (виден только «шум»). */
    class SpoilerSpan : CharacterStyle(), UpdateAppearance {
        /** 0 - скрыт, 1 - открыт; между - растворяется. */
        @Volatile
        var reveal = 0f
        var startedAt = 0L

        override fun updateDrawState(tp: TextPaint) {
            if (reveal >= 1f) return
            val a = (Color.alpha(tp.color) * reveal).toInt()
            tp.color = (tp.color and 0x00FFFFFF) or (a shl 24)
            tp.bgColor = if (reveal <= 0f) 0x14FFFFFF else 0
        }
    }

    private class RevealSpan(val spoiler: SpoilerSpan) : ClickableSpan() {
        override fun onClick(widget: View) {
            if (spoiler.reveal > 0f) return
            widget.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            spoiler.startedAt = SystemClock.uptimeMillis()
            spoiler.reveal = 0.001f
            widget.invalidate()
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.isUnderlineText = false
        }
    }

    fun spoiler(sb: SpannableStringBuilder, s: Int, e: Int) {
        if (e <= s) return
        val sp = SpoilerSpan()
        sb.setSpan(sp, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RevealSpan(sp), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Искры поверх скрытого текста; рисуется в overlay самого TextView. */
    private class SpoilerOverlay(val tv: TextView) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = tv.resources.displayMetrics.density
        private val born = SystemClock.uptimeMillis()

        override fun draw(c: Canvas) {
            val text = tv.text as? Spanned ?: return
            val layout = tv.layout ?: return
            val spans = text.getSpans(0, text.length, SpoilerSpan::class.java)
            if (spans.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            val still = EnergySaver.on
            var animating = false
            val dx = tv.totalPaddingLeft - tv.scrollX
            val dy = tv.totalPaddingTop - tv.scrollY
            val color = tv.currentTextColor
            for (sp in spans) {
                if (sp.reveal >= 1f) continue
                val prog = if (sp.reveal > 0f) ((now - sp.startedAt) / 650f).coerceIn(0f, 1f) else 0f
                if (sp.reveal > 0f) {
                    sp.reveal = prog.coerceAtLeast(0.001f)
                    if (prog >= 1f) sp.reveal = 1f
                    tv.invalidate()
                }
                val s = text.getSpanStart(sp)
                val e = text.getSpanEnd(sp)
                if (s < 0 || e <= s) continue
                val l0 = layout.getLineForOffset(s)
                val l1 = layout.getLineForOffset(e)
                var seed = s * 7919 + 13
                for (line in l0..l1) {
                    val ls = maxOf(s, layout.getLineStart(line))
                    val le = minOf(e, layout.getLineEnd(line))
                    val x0 = layout.getPrimaryHorizontal(ls)
                    val x1 = if (le >= layout.getLineEnd(line)) layout.getLineRight(line) else layout.getPrimaryHorizontal(le)
                    val left = minOf(x0, x1) + dx
                    val right = maxOf(x0, x1) + dx
                    val top = layout.getLineTop(line) + dy
                    val bottom = layout.getLineBottom(line) + dy
                    val area = (right - left) * (bottom - top)
                    val step = 3.2f * d
                    val n = LiteMode.particles((area / (step * step) * 0.45f).toInt().coerceIn(6, 260))
                    for (k in 0 until n) {
                        seed = seed * 1103515245 + 12345
                        val rx = ((seed ushr 8) and 0xFFFF) / 65535f
                        seed = seed * 1103515245 + 12345
                        val ry = ((seed ushr 8) and 0xFFFF) / 65535f
                        seed = seed * 1103515245 + 12345
                        val ph = ((seed ushr 8) and 0xFFFF) / 65535f * 6.283f
                        val t = if (still) 0f else (now - born) / 1000f
                        // Частицы «дышат» и слегка дрейфуют; при раскрытии - разлетаются и гаснут.
                        var x = left + rx * (right - left) + sin(t * 1.7f + ph) * 1.4f * d
                        var y = top + ry * (bottom - top) + cos(t * 1.3f + ph) * 1.2f * d
                        if (prog > 0f) {
                            val ang = ph + rx * 2f
                            x += cos(ang) * prog * 22f * d
                            y += sin(ang) * prog * 14f * d - prog * 6f * d
                        }
                        val tw = 0.55f + 0.45f * sin(t * 4f + ph * 3f)
                        val alpha = (255 * tw * (1f - prog)).toInt().coerceIn(0, 255)
                        p.color = (color and 0x00FFFFFF) or (alpha shl 24)
                        c.drawCircle(x, y, (0.55f + rx * 0.6f) * d, p)
                    }
                }
                animating = true
            }
            if (animating && tv.isShown && tv.isAttachedToWindow) {
                if (still) {
                    if (spans.any { it.reveal in 0.0001f..0.9999f }) tv.postInvalidateOnAnimation()
                } else if (LiteMode.on) {
                    tv.postInvalidateDelayed(LiteMode.liveFrameMs)
                } else {
                    tv.postInvalidateOnAnimation()
                }
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // ------------------------------------------------------------------ сворачиваемая цитата

    private val expanded = HashSet<String>()

    private class ToggleSpan(val key: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            val tv = widget as? TextView ?: return
            if (!expanded.add(key)) expanded.remove(key)
            val rebuild = tv.getTag(TAG_REBUILD) as? (() -> CharSequence) ?: return
            animateHeight(tv) {
                tv.text = rebuild()
                attach(tv, rebuild)
            }
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.isUnderlineText = false
            ds.color = 0xFFFFE08A.toInt()
            ds.isFakeBoldText = true
        }
    }

    /** Цитата: свёрнута - первая строка и «▾ Развернуть», раскрыта - целиком и «▴ Свернуть». */
    fun collapsible(sb: SpannableStringBuilder, key: String, text: String, inline: (String) -> Unit) {
        val open = key in expanded
        val lines = text.split('\n')
        val s = sb.length
        if (open || lines.size <= 1 && text.length <= 90) {
            inline(text)
        } else {
            val first = lines.first().let { if (it.length > 90) it.take(88) + "…" else it }
            inline(first)
        }
        sb.setSpan(QuoteSpan(0xFFFFE08A.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (lines.size > 1 || text.length > 90) {
            val ts = sb.length
            sb.append(if (open) "  ▴ " + t("Свернуть", "Collapse") else "  ▾ " + t("Развернуть", "Expand"))
            sb.setSpan(ToggleSpan(key), ts, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), ts, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Плавная смена высоты TextView при смене текста (анимация макета без рывка списка). */
    private fun animateHeight(tv: TextView, change: () -> Unit) {
        val from = tv.height
        change()
        val parentW = (tv.parent as? View)?.width ?: 0
        val wSpec = if (tv.width > 0) {
            View.MeasureSpec.makeMeasureSpec(maxOf(tv.width, 1), View.MeasureSpec.AT_MOST).let {
                if (parentW > 0) View.MeasureSpec.makeMeasureSpec(parentW, View.MeasureSpec.AT_MOST) else it
            }
        } else {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }
        tv.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val to = tv.measuredHeight
        if (from <= 0 || to <= 0 || from == to || !com.stoyko.smessenger.ui.Kinetics.enabled) return
        val lp = tv.layoutParams ?: return
        ValueAnimator.ofInt(from, to).apply {
            duration = 280
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                lp.height = it.animatedValue as Int
                tv.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    tv.layoutParams = lp
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------------ живые эмодзи в строке

    /** Эмодзи в строке: пока картинка грузится - обычный символ; потом - анимированная картинка. */
    class EmojiSpan(val emoji: String) : DynamicDrawableSpan(DynamicDrawableSpan.ALIGN_BOTTOM) {
        // Не «drawable»: свойство с таким именем конфликтует с getDrawable() родителя (JVM).
        var image: Drawable? = null
        var sizePx = 0

        override fun getDrawable(): Drawable = image ?: EMPTY

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (image == null) return paint.measureText(emoji).toInt()
            return super.getSize(paint, text, start, end, fm)
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val d = image
            if (d == null) {
                canvas.drawText(emoji, x, y.toFloat(), paint)
                return
            }
            canvas.save()
            val h = d.bounds.height()
            canvas.translate(x, (bottom - h - (bottom - y) / 3).toFloat().coerceAtLeast(top.toFloat()))
            d.draw(canvas)
            canvas.restore()
        }
    }

    private val EMPTY = object : Drawable() {
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSPARENT
    }

    fun emoji(sb: SpannableStringBuilder, s: Int, e: Int, emoji: String) {
        sb.setSpan(EmojiSpan(emoji), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ------------------------------------------------------------------ подключение к TextView

    private val TAG_OVERLAY = com.stoyko.smessenger.R.id.tag_rich_overlay
    private val TAG_REBUILD = com.stoyko.smessenger.R.id.tag_rich_rebuild

    /**
     * Подключить «живое» оформление к [tv] (после установки текста). [rebuild] - как заново
     * получить текст (для сворачиваемых цитат); null - цитаты не раскрываются на месте.
     */
    private class Attached(val overlay: Drawable, val listener: View.OnLayoutChangeListener)

    fun attach(tv: TextView, rebuild: (() -> CharSequence)? = null) {
        val text = tv.text as? Spanned
        // Строки списка переиспользуются - снимаем то, что было подключено к прежнему тексту.
        (tv.getTag(TAG_OVERLAY) as? Attached)?.let {
            tv.overlay.remove(it.overlay)
            tv.removeOnLayoutChangeListener(it.listener)
            tv.setTag(TAG_OVERLAY, null)
        }
        tv.setTag(TAG_REBUILD, rebuild)
        if (text == null) return
        // Нажатия по спойлерам и «Развернуть» - нужен обработчик ссылок.
        if (text.getSpans(0, text.length, ClickableSpan::class.java).isNotEmpty() && tv.movementMethod !is android.text.method.LinkMovementMethod) {
            tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        if (text.getSpans(0, text.length, SpoilerSpan::class.java).isNotEmpty()) {
            val o = SpoilerOverlay(tv)
            tv.post {
                o.setBounds(0, 0, tv.width.coerceAtLeast(1), tv.height.coerceAtLeast(1))
                tv.overlay.add(o)
                tv.invalidate()
            }
            val listener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> o.setBounds(0, 0, r - l, b - t) }
            tv.addOnLayoutChangeListener(listener)
            tv.setTag(TAG_OVERLAY, Attached(o, listener))
        }
        val emojis = text.getSpans(0, text.length, EmojiSpan::class.java)
        if (emojis.isNotEmpty()) {
            val size = (tv.textSize * 1.3f).toInt()
            val cb = object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) = tv.invalidate()
                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                    main.postAtTime(what, who, `when`)
                }

                override fun unscheduleDrawable(who: Drawable, what: Runnable) = main.removeCallbacks(what, who)
            }
            for (sp in emojis) {
                if (sp.image != null) continue
                sp.sizePx = size
                AnimatedEmoji.drawable(tv.context, sp.emoji, size) { d ->
                    if (tv.text !== text && tv.text?.toString() != text.toString()) return@drawable
                    d.setBounds(0, 0, size, size)
                    d.callback = cb
                    sp.image = d
                    // Ширина символа меняется - пересчитать перенос строк.
                    tv.text = tv.text
                    tv.requestLayout()
                }
            }
        }
    }

    private fun t(ru: String, en: String) = com.stoyko.smessenger.ai.t(ru, en)
}
