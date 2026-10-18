// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * Большая кнопка VPN: светящийся шар с кольцом состояния.
 * Выключен - спокойный фиолетовый; подключение - бегущая дуга; включён - зелёный с волнами.
 * Анимация сама останавливается, когда кнопка не на экране или включено энергосбережение.
 */
class PowerOrb(context: Context) : View(context) {
    enum class State { Off, Connecting, On, Error }

    var state: State = State.Off
        set(v) {
            if (field == v) return
            field = v
            started = System.currentTimeMillis()
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 34 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val oval = RectF()
    private var started = System.currentTimeMillis()
    private var lastShaderKey = 0L

    private fun colors(): IntArray = when (state) {
        State.On -> intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt(), 0xFF22C55E.toInt())
        State.Connecting -> intArrayOf(0xFFFBBF24.toInt(), 0xFFD97706.toInt(), 0xFFF59E0B.toInt())
        State.Error -> intArrayOf(0xFFF87171.toInt(), 0xFFB91C1C.toInt(), 0xFFE5484D.toInt())
        State.Off -> intArrayOf(0xFF8B7CF8.toInt(), 0xFF4B3CCB.toInt(), 0xFF7B6CF6.toInt())
    }

    private val animate: Boolean
        get() = com.stoyko.smessenger.ui.Kinetics.enabled && !com.stoyko.smessenger.ui.EnergySaver.on &&
            (state == State.Connecting || state == State.On)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2
        val cy = h / 2
        val r = min(w, h) / 2
        val core = r * 0.62f
        val c = colors()
        val t = (System.currentTimeMillis() - started) / 1000f

        // Волны вокруг (включён) / мягкое свечение (остальное).
        if (state == State.On && animate) {
            for (i in 0 until 3) {
                val p = ((t / 2.4f) + i / 3f) % 1f
                wave.strokeWidth = d * 2
                wave.color = c[2]
                wave.alpha = ((1f - p) * 110).toInt()
                canvas.drawCircle(cx, cy, core + (r - core) * p, wave)
            }
        } else {
            glow.color = c[2]
            glow.alpha = 36
            canvas.drawCircle(cx, cy, r * 0.95f, glow)
        }

        // Кольцо-дорожка.
        val rr = core + (r - core) * 0.36f
        oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
        ring.strokeWidth = d * 4
        ring.shader = null
        ring.color = 0x33FFFFFF
        canvas.drawCircle(cx, cy, rr, ring)
        ring.color = c[0]
        when (state) {
            State.Connecting -> {
                val a = if (animate) (t * 300f) % 360f else 0f
                canvas.drawArc(oval, a - 90, 100f, false, ring)
                ring.alpha = 120
                canvas.drawArc(oval, a + 90, 50f, false, ring)
                ring.alpha = 255
            }
            State.On -> canvas.drawCircle(cx, cy, rr, ring)
            State.Error -> canvas.drawArc(oval, -90f, 360f, false, ring.apply { alpha = 170 })
            State.Off -> Unit
        }
        ring.alpha = 255

        // Шар с объёмным градиентом.
        val key = (state.ordinal.toLong() shl 40) or (w.toLong() shl 20) or h.toLong()
        if (key != lastShaderKey) {
            fill.shader = RadialGradient(cx - core * 0.35f, cy - core * 0.45f, core * 1.6f, c[0], c[1], Shader.TileMode.CLAMP)
            lastShaderKey = key
        }
        val breathe = if (state == State.On && animate) 1f + 0.025f * kotlin.math.sin(t * 2.6f) else 1f
        canvas.drawCircle(cx, cy, core * breathe, fill)
        // Блик сверху.
        canvas.drawOval(cx - core * 0.55f, cy - core * 0.82f, cx + core * 0.55f, cy - core * 0.15f, shine)

        // Значок питания.
        val ir = core * 0.36f
        icon.strokeWidth = d * 3.6f
        oval.set(cx - ir, cy - ir + ir * 0.12f, cx + ir, cy + ir + ir * 0.12f)
        canvas.drawArc(oval, -60f, 300f, false, icon)
        canvas.drawLine(cx, cy - ir * 1.05f, cx, cy - ir * 0.05f, icon)

        // На слабом телефоне - ~30 кадров/с вместо частоты экрана.
        if (animate && isShown) {
            if (com.stoyko.smessenger.ui.LiteMode.on) postInvalidateDelayed(com.stoyko.smessenger.ui.LiteMode.liveFrameMs) else postInvalidateOnAnimation()
        }
    }
}

/** Мелкие строительные блоки экрана VPN - единый стиль карточек, плиток и подписей. */
internal object VpnUi {
    const val ACCENT = 0xFF5B4BDB.toInt()
    const val GREEN = 0xFF22C55E.toInt()
    const val AMBER = 0xFFF59E0B.toInt()
    const val RED = 0xFFE5484D.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun pingColor(ms: Long): Int = when {
        ms <= 0L -> 0xFF94A3B8.toInt()
        ms < 300 -> GREEN
        ms < 800 -> AMBER
        else -> RED
    }

    /** Кодовые точки строки (String.codePoints() есть только с Android 7). */
    private fun codePoints(s: String): IntArray {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val c = Character.codePointAt(s, i)
            out += c
            i += Character.charCount(c)
        }
        return out.toIntArray()
    }

    /** Флаг страны из названия сервера (🇩🇪 …) или значок протокола. */
    fun flagOf(name: String, protocol: String = ""): String {
        val cps = codePoints(name)
        for (i in 0 until cps.size - 1) {
            if (cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF) return String(cps, i, 2)
        }
        return when (protocol.lowercase()) {
            "vless", "vmess" -> "🛰"
            "trojan" -> "🐴"
            "ss", "shadowsocks" -> "🕶"
            "hysteria2", "hy2" -> "⚡"
            else -> "🌐"
        }
    }

    /** Название сервера без флага и лишних пробелов. */
    fun cleanName(name: String): String {
        val cps = codePoints(name)
        val sb = StringBuilder()
        var i = 0
        while (i < cps.size) {
            if (i < cps.size - 1 && cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF) {
                i += 2
                continue
            }
            sb.appendCodePoint(cps[i])
            i++
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().ifBlank { name }
    }

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun pressable(view: View, bg: GradientDrawable, rippleColor: Int = 0x335B4BDB) {
        view.background = RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null)
        view.isClickable = true
        view.isFocusable = true
        com.stoyko.smessenger.ui.Kinetics.squish(view, 0.96f)
    }

    /** Подпись раздела: маленькие заглавные буквы, как в настройках системы. */
    fun section(ctx: Context, text: String, color: Int) = TextView(ctx).apply {
        this.text = text.uppercase()
        textSize = 11.5f
        letterSpacing = 0.08f
        paint.isFakeBoldText = true
        setTextColor(color)
        setPadding(dp(ctx, 4), dp(ctx, 18), dp(ctx, 4), dp(ctx, 6))
    }

    /** Значок в цветном кружке. */
    fun badge(ctx: Context, emoji: String, color: Int, size: Int = 40) = TextView(ctx).apply {
        text = emoji
        textSize = size * 0.45f
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((color and 0x00FFFFFF) or 0x26000000)
        }
        layoutParams = LinearLayout.LayoutParams(dp(ctx, size), dp(ctx, size))
    }

    /** Маленькая «таблетка» с текстом (пинг, протокол, статус). */
    fun pill(ctx: Context, text: String, color: Int, solid: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = 11.5f
        maxLines = 1
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3))
        setTextColor(if (solid) Color.WHITE else color)
        background = rounded(if (solid) color else (color and 0x00FFFFFF) or 0x24000000, dp(ctx, 10).toFloat())
    }

    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
