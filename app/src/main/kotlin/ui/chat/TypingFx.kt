// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Editable
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.ViewGroup
import android.widget.EditText
import com.stoyko.smessenger.ui.Kinetics
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Красивый набор и удаление текста в поле ввода.
 *  - Новая буква мягко проявляется (без рывков даже на быстром наборе).
 *  - Стёртый текст «испаряется»: буквы рассыпаются на лёгкие частицы-пылинки, которые
 *    мягко разлетаются в стороны, всплывают и растворяются в воздухе.
 */
class TypingFx(private val input: EditText) : TextWatcher {
    private val d = input.resources.displayMetrics.density
    private var pendingVapor: List<Kinetics.Particle>? = null
    private var restoring = false

    fun attach() {
        input.addTextChangedListener(this)
    }

    // ---------------- появление новых букв ----------------

    /**
     * Буква мягко проявляется. Раньше она ещё и «всплывала» сдвигом базовой линии - сдвиг
     * идёт целыми пикселями, и на быстром наборе буквы заметно дёргались. Теперь только
     * плавное проявление (непрозрачность меняется без ступенек) - максимально мягко.
     */
    private class PopSpan : CharacterStyle(), UpdateAppearance {
        var t = 0f
        override fun updateDrawState(tp: TextPaint) {
            val k = t.coerceIn(0f, 1f)
            // Сглаженная кривая (smoothstep): без резкого старта и без «доводки» в конце.
            val e = k * k * (3f - 2f * k)
            tp.alpha = (tp.alpha * (0.1f + 0.9f * e)).toInt()
        }
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        if (!Kinetics.enabled || restoring || s == null) return
        // Удаление (или замена более коротким): запоминаем, где стояли стираемые буквы.
        if (count > after) {
            val from = start + after
            val to = (start + count).coerceAtMost(s.length)
            pendingVapor = runCatching { vaporFor(s, from, to) }.getOrNull()
        }
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        if (!Kinetics.enabled || restoring) return
        if (count > before && count - before <= 4) {
            val ed = input.text ?: return
            val from = start + before
            val to = (start + count).coerceAtMost(ed.length)
            if (from < to) pop(ed, from, to)
        }
    }

    override fun afterTextChanged(e: Editable?) {
        val v = pendingVapor ?: return
        pendingVapor = null
        val root = input.rootView as? ViewGroup ?: return
        if (v.isNotEmpty()) Kinetics.ParticleLayer.attach(root).add(v)
    }

    private fun pop(ed: Editable, from: Int, to: Int) {
        val span = PopSpan()
        ed.setSpan(span, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                span.t = it.animatedValue as Float
                com.stoyko.smessenger.ui.GlassFrameLayout.holdCapture(80)
                // Перерисовать только поле ввода (без перестройки разметки текста).
                input.postInvalidateOnAnimation()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    input.text?.removeSpan(span)
                    input.invalidate()
                }
            })
            start()
        }
    }

    /**
     * Стираемые буквы -> частицы. Каждая буква рисуется в маленькую картинку, из её
     * закрашенных точек получаются пылинки того же цвета, что и текст.
     */
    private fun vaporFor(s: CharSequence, from: Int, to: Int): List<Kinetics.Particle> {
        if (from >= to) return emptyList()
        val layout = input.layout ?: return emptyList()
        // Длинное удаление (выделили всё и стёрли) - берём не больше 80 видимых букв.
        val end = to.coerceAtMost(from + 80)
        val paint = TextPaint(input.paint).apply { color = input.currentTextColor }
        val color = input.currentTextColor
        val step = (2.8f * d).toInt().coerceAtLeast(2)
        val out = ArrayList<Kinetics.Particle>()
        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        input.getLocationInWindow(loc)
        input.rootView.getLocationInWindow(rootLoc)
        val baseX = loc[0] - rootLoc[0] + input.totalPaddingLeft - input.scrollX
        val baseY = loc[1] - rootLoc[1] + input.totalPaddingTop - input.scrollY
        val visibleTop = input.scrollY
        val visibleBottom = input.scrollY + input.height
        val fm = paint.fontMetrics
        val gh = (fm.descent - fm.ascent).toInt().coerceAtLeast(1)
        var i = from
        while (i < end) {
            val cp = Character.codePointAt(s, i)
            val len = Character.charCount(cp)
            val ch = String(Character.toChars(cp))
            if (!Character.isWhitespace(cp) && i < layout.text.length) {
                val line = layout.getLineForOffset(i)
                val top = layout.getLineTop(line)
                if (top in (visibleTop - gh)..visibleBottom) {
                    val gx = layout.getPrimaryHorizontal(i)
                    val baseline = layout.getLineBaseline(line)
                    val gw = paint.measureText(ch).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(gw + 2, gh + 2, Bitmap.Config.ALPHA_8)
                    Canvas(bmp).drawText(ch, 1f, 1f - fm.ascent, paint)
                    var y = 0
                    while (y < bmp.height) {
                        var x = 0
                        while (x < bmp.width) {
                            val a = (bmp.getPixel(x, y) ushr 24) and 0xFF
                            if (a > 90 && out.size < 360) {
                                // Мягкий разлёт во все стороны с уклоном вверх.
                                val ang = Random.nextDouble(0.0, Math.PI * 2)
                                val sp = (10 + Random.nextFloat() * 30) * d
                                val px = baseX + gx + x - 1
                                val py = baseY + baseline + fm.ascent + y - 1
                                out += Kinetics.Particle(
                                    px, py,
                                    (cos(ang) * sp).toFloat(), (sin(ang) * sp - 24 * d).toFloat(),
                                    (0.7f + Random.nextFloat() * 0.9f) * d,
                                    (color and 0x00FFFFFF) or ((a * 0.85f).toInt() shl 24),
                                    750 + Random.nextLong(0, 400),
                                    vapor = true,
                                    // Буквы рассыпаются не все сразу, а «волной» от конца к началу.
                                    delayMs = ((end - i) * 12L).coerceAtMost(240),
                                )
                            }
                            x += step
                        }
                        y += step
                    }
                    bmp.recycle()
                }
            }
            i += len
        }
        return out
    }
}
