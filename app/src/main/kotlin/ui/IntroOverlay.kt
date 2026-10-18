// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Заставка при запуске: продолжает системный экран загрузки (тот же фон и значок) и
 * превращает его в анимацию - значок «выпрыгивает» с пружиной, вокруг расходятся светящиеся
 * кольца и искры, название появляется по буквам, затем заставка растворяется, открывая чаты.
 * Коснитесь экрана - пропустить.
 */
object IntroOverlay {
    private const val TOTAL_MS = 1900L

    fun show(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val d = activity.resources.displayMetrics.density
        // Сезонная заставка: Новый год - значок в новогодней шапочке, снег, гирлянда и ёлочки;
        // осень - оранжевая, с листопадом.
        val season = runCatching { Seasonal.current(activity) }.getOrDefault(Seasonal.Kind.NONE)
        val totalMs = if (season == Seasonal.Kind.NONE) TOTAL_MS else 2700L
        val fx = SeasonFx(season, d)
        fun dp(v: Int) = (v * d).toInt()

        val root = object : FrameLayout(activity) {
            private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
            private val start = SystemClock.uptimeMillis()

            init {
                setWillNotDraw(false)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val t = (SystemClock.uptimeMillis() - start).toFloat()
                val cx = width / 2f
                val cy = height / 2f - dp(40)
                // Два мягких кольца расходятся от значка (Pulse Badge), со сдвигом по фазе.
                for (k in 0..1) {
                    val p = ((t - 250 - k * 380) / 1100f)
                    if (p in 0f..1f) {
                        ring.strokeWidth = (3 - 2 * p) * d
                        ring.color = Color.argb((150 * (1 - p)).toInt(), 255, 255, 255)
                        canvas.drawCircle(cx, cy, dp(56) + p * dp(110), ring)
                    }
                }
                // Искры по кругу (орбита).
                val sp = ((t - 350) / 1200f).coerceIn(0f, 1f)
                if (sp > 0f && sp < 1f) {
                    for (i in 0 until 12) {
                        val a = i / 12f * Math.PI * 2 + sp * 1.4
                        val r = dp(62) + sp * dp(70)
                        dot.color = Color.argb((220 * (1 - sp)).toInt(), 255, 255, 255)
                        canvas.drawCircle(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), (2.6f - sp * 1.6f) * d, dot)
                    }
                }
                fx.draw(canvas, t, width.toFloat(), height.toFloat(), cx, cy)
                if (t < totalMs + 400) postInvalidateOnAnimation()
            }
        }
        root.background = Seasonal.headerColors(season)?.let { c ->
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, c)
        } ?: ContextCompat.getDrawable(activity, R.drawable.bg_brand_gradient)
        root.isClickable = true
        root.elevation = 100f

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val icon = ImageView(activity).apply {
            // Выбранная иконка приложения (у зимней/осенней шапка и листья рисуются поверх обычной).
            val chosen = runCatching { AppIcon.resolved(activity) }.getOrDefault("default")
            val res = AppIcon.ICONS.firstOrNull { it.id == chosen && chosen != "winter" && chosen != "autumn" }?.res ?: R.mipmap.ic_launcher
            setImageDrawable(ContextCompat.getDrawable(activity, res))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            elevation = 14 * d
            scaleX = 0.4f
            scaleY = 0.4f
            alpha = 0f
            rotation = -14f
        }
        // Значок; зимой на него «надевается» новогодняя шапочка, осенью - кленовый лист.
        // Шапка рисуется по геометрии самого значка (по дуге его верха), поэтому сидит ровно.
        val iconBox = object : FrameLayout(activity) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            var hatProgress = 0f
            init { setWillNotDraw(false) }
            override fun dispatchDraw(c: Canvas) {
                super.dispatchDraw(c)
                if (season == Seasonal.Kind.NONE || hatProgress <= 0f) return
                val r = icon.width * icon.scaleX / 2f
                val cx = icon.left + icon.width / 2f + icon.translationX
                val cy = icon.top + icon.height / 2f + icon.translationY
                c.save()
                // Опускается сверху и чуть покачивается.
                val drop = (1f - hatProgress) * -r * 1.4f
                c.rotate(-8f * (1f - hatProgress), cx, cy)
                c.translate(0f, drop)
                p.alpha = 255
                if (season == Seasonal.Kind.WINTER) {
                    SeasonArt.santaHat(c, p, cx, cy, r)
                } else if (season == Seasonal.Kind.SPRING) {
                    // Весной на значок «садится» цветок сакуры.
                    SeasonArt.flower(c, p, cx + r * 0.66f, cy - r * 0.78f, r * 0.42f, 18f)
                } else if (season == Seasonal.Kind.SUMMER) {
                    // Летом - солнышко над значком.
                    SeasonArt.sun(c, p, cx + r * 0.7f, cy - r * 0.8f, r * 0.4f, hatProgress * 40f)
                } else {
                    SeasonArt.leaf(c, p, cx + r * 0.72f, cy - r * 0.78f, r * 0.42f, 28f, 1f, 0, SeasonArt.LEAF_COLORS[1], 255)
                    SeasonArt.leaf(c, p, cx + r * 0.38f, cy - r * 0.98f, r * 0.3f, -18f, 0.8f, 1, SeasonArt.LEAF_COLORS[0], 255)
                }
                c.restore()
            }
        }
        iconBox.clipChildren = false
        iconBox.addView(icon, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(8) })
        if (season != Seasonal.Kind.NONE) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = 600
                duration = 700
                interpolator = android.view.animation.BounceInterpolator()
                addUpdateListener {
                    iconBox.hatProgress = it.animatedValue as Float
                    iconBox.invalidate()
                }
                start()
            }
        }
        column.addView(iconBox, LinearLayout.LayoutParams(dp(170), dp(176)))
        val title = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val letters = "StoykoMessenger".map { ch ->
            TextView(activity).apply {
                text = ch.toString()
                textSize = 28f
                setTextColor(Color.WHITE)
                paint.isFakeBoldText = true
                alpha = 0f
                translationY = 18 * d
            }
        }
        letters.forEach { title.addView(it) }
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(26) })
        val sub = TextView(activity).apply {
            setText(R.string.intro_tagline)
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            alpha = 0f
        }
        column.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        content.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            // Уход: заставка чуть увеличивается и растворяется, значок «улетает» вверх.
            icon.animate().translationY(-40 * d).scaleX(0.8f).scaleY(0.8f).setDuration(320).start()
            root.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(380)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { (root.parent as? ViewGroup)?.removeView(root) }
                .start()
        }
        root.setOnClickListener { finish() }

        icon.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
            .setStartDelay(60).setDuration(650).setInterpolator(OvershootInterpolator(2.4f)).start()
        letters.forEachIndexed { i, v ->
            v.animate().alpha(1f).translationY(0f).setStartDelay(420L + i * 38L).setDuration(380)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }
        sub.animate().alpha(1f).setStartDelay(1000).setDuration(400).start()
        // Лёгкое «дыхание» значка, пока идёт заставка.
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 750
            duration = 900
            addUpdateListener {
                val f = it.animatedValue as Float
                if (!finished) {
                    val s = 1f + 0.04f * sin(f * Math.PI).toFloat()
                    icon.scaleX = s
                    icon.scaleY = s
                }
            }
            start()
        }
        // Поздравление: праздник дня из календаря или одна из сезонных фраз - крупно и заметно.
        Holidays.greeting(activity, season)?.let { g ->
            sub.text = g
            sub.textSize = 19f
            sub.setTextColor(Color.WHITE)
            sub.paint.isFakeBoldText = true
            sub.setShadowLayer(6 * d, 0f, 2 * d, 0x88000000.toInt())
            sub.setPadding(dp(16), dp(8), dp(16), dp(8))
            sub.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 22 * d
                setColor(0x40000000)
            }
        }
        root.postDelayed({ finish() }, totalMs)
    }

    /** Сезонные эффекты заставки: сосульки и снег/сугробы по углам или профессиональный листопад. */
    private class SeasonFx(private val kind: Seasonal.Kind, private val d: Float) {
        private val rnd = java.util.Random(11)
        private val n = 40
        private val px = FloatArray(n) { rnd.nextFloat() }
        private val sp = FloatArray(n) { 0.10f + rnd.nextFloat() * 0.16f }
        private val sz = FloatArray(n) { 0.5f + rnd.nextFloat() }
        private val ph = FloatArray(n) { rnd.nextFloat() * 6.28f }
        private val off = FloatArray(n) { rnd.nextFloat() }
        private val kindOf = IntArray(n) { if (rnd.nextFloat() < 0.6f) 0 else 1 }
        private val col = IntArray(n) { rnd.nextInt(SeasonArt.LEAF_COLORS.size) }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bulbs = intArrayOf(0xFFEF4444.toInt(), 0xFFFDE047.toInt(), 0xFF22C55E.toInt(), 0xFF3B82F6.toInt(), 0xFFEC4899.toInt())

        fun draw(c: Canvas, tMs: Float, w: Float, h: Float, cx: Float, cy: Float) {
            if (kind == Seasonal.Kind.NONE) return
            val t = tMs / 1000f
            if (kind == Seasonal.Kind.WINTER) {
                // Снег: мелкий и крупные снежинки.
                for (i in 0 until n) {
                    val y = ((off[i] + t * sp[i]) % 1.05f) * h
                    val x = px[i] * w + sin(t * 1.3f + ph[i]) * 14 * d
                    if (i % 6 == 0) {
                        SeasonArt.snowflake(c, p, x, y, 7 * d * sz[i], t * 40f + ph[i] * 50, 220)
                    } else {
                        p.color = Color.WHITE
                        p.alpha = 210
                        c.drawCircle(x, y, 2.2f * d * sz[i], p)
                    }
                }
                p.alpha = 255
                // Гирлянда под сосульками.
                val m = 9
                for (i in 0..m) {
                    val x = w * i / m
                    val y = 44 * d + if (i % 2 == 1) 12 * d else 0f
                    val on = ((t * 4 + i) % 3) < 2
                    val bc = bulbs[(i + (t * 2).toInt()) % bulbs.size]
                    if (on) {
                        p.shader = android.graphics.RadialGradient(x, y, 15 * d, (bc and 0x00FFFFFF) or 0x99000000.toInt(), bc and 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
                        c.drawCircle(x, y, 15 * d, p)
                        p.shader = null
                    }
                    p.color = if (on) bc else (bc and 0x00FFFFFF) or 0x55000000
                    c.drawCircle(x, y, 5 * d, p)
                }
                SeasonArt.icicles(c, p, 0f, 0f, w, d, t)
                SeasonArt.cornerDrifts(c, p, w, h, d, t)
            } else if (kind == Seasonal.Kind.SPRING || kind == Seasonal.Kind.SUMMER) {
                val spring = kind == Seasonal.Kind.SPRING
                p.shader = android.graphics.RadialGradient(cx, cy, 170 * d, if (spring) 0x66F9A8D4 else 0x66FDE68A, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, 170 * d, p)
                p.shader = null
                // Весна - лепестки сакуры, лето - бабочки и солнечные искорки.
                for (i in 0 until n) {
                    val y = ((off[i] + t * sp[i] * 0.7f) % 1.1f) * h - 30 * d
                    val x = px[i] * w + sin(t * 1.1f + ph[i]) * 26 * d
                    if (spring) {
                        SeasonArt.petal(c, p, x, y, 8 * d * sz[i], sin(t * 1.4f + ph[i]) * 70f, kotlin.math.cos(t * 2f + ph[i]), SeasonArt.PETAL_COLORS[i % SeasonArt.PETAL_COLORS.size], 230)
                    } else if (i % 4 == 0) {
                        SeasonArt.butterfly(c, p, x, y, 9 * d * sz[i], sin(t + ph[i]) * 25f, 0.5f + 0.5f * sin(t * 13f + ph[i] * 3), SeasonArt.BUTTERFLY_COLORS[i % SeasonArt.BUTTERFLY_COLORS.size], 235)
                    } else {
                        SeasonArt.firefly(c, p, x, y, 2.4f * d * sz[i], 0.5f + 0.5f * sin(t * 3f + ph[i]), 220)
                    }
                }
            } else {
                // Тёплое свечение вокруг значка.
                p.shader = android.graphics.RadialGradient(cx, cy, 170 * d, 0x66FDBA74, 0x00FDBA74, android.graphics.Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, 170 * d, p)
                p.shader = null
                // Листопад: листья кружатся, переворачиваются и плавно летят вниз.
                for (i in 0 until n) {
                    val y = ((off[i] + t * sp[i] * 0.85f) % 1.1f) * h - 30 * d
                    val x = px[i] * w + sin(t * 1.1f + ph[i]) * 34 * d
                    val flip = kotlin.math.cos(t * 2.2f + ph[i])
                    SeasonArt.leaf(c, p, x, y, 12 * d * sz[i], sin(t * 1.6f + ph[i]) * 60f, flip, kindOf[i], SeasonArt.LEAF_COLORS[col[i]], 235)
                }
            }
        }
    }
}
