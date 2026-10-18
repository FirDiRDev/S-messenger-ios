// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.stoyko.smessenger.ai.t

/**
 * Канал разработчика (4.10.0): красивая кнопка «Подписаться» - в Настройках и «О приложении».
 * Синий градиент с бегущим бликом, бумажный самолётик слегка парит; в режиме экономии -
 * без анимации. Открывает канал в приложении Telegram, а если его нет - в браузере.
 */
object DevChannel {
    const val URL = "https://t.me/oblako_Firedie"
    const val HANDLE = "@oblako_Firedie"

    fun open(ctx: Context) {
        val tg = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=oblako_Firedie")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(tg) }.isSuccess) return
        if (runCatching { ctx.startActivity(web) }.isSuccess) return
        // Ни Telegram, ни браузера - хотя бы скопировать ссылку.
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("channel", URL))
            Toast.makeText(ctx, t("Ссылка скопирована: $URL", "Link copied: $URL"), Toast.LENGTH_LONG).show()
        }
    }

    /** Кнопка во всю ширину (высота - WRAP_CONTENT, ~64dp, на крупном шрифте растёт сама). */
    fun button(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = FrameLayout(ctx).apply {
            background = Glow(d)
            minimumHeight = dp(64)
            isClickable = true
            isFocusable = true
            contentDescription = t("Подписаться на канал разработчика", "Follow the developer's channel")
            setOnClickListener { open(ctx) }
        }
        Kinetics.squish(root, 0.95f)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        // 4.12.0: вместо значка - живое видео в круге (дождливая ночь, по кругу, без звука)
        // с пульсирующим светящимся кольцом. В режиме экономии - неподвижный кадр.
        val icon = FrameLayout(ctx)
        icon.addView(Ring(ctx, d), FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
        icon.addView(
            LoopVideoView(ctx, LoopVideoView.raw(ctx, com.stoyko.smessenger.R.raw.live_rain_icon), com.stoyko.smessenger.R.drawable.live_rain_poster, circle = true),
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER),
        )
        row.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        texts.addView(TextView(ctx).apply {
            text = t("Канал разработчика", "Developer's channel")
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(ctx).apply {
            text = t("Новости, обновления и тесты · $HANDLE", "News, updates and betas · $HANDLE")
            setTextColor(0xD9FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 2
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(ctx).apply {
            text = t("Подписаться", "Follow")
            setTextColor(0xFF1E88E5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 40 * d
                setColor(0xFFFFFFFF.toInt())
            }
        })
        root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        return root
    }

    // 4.12.1: на слабых телефонах анимация не выключается, а идёт реже (~30 кадров/с).
    private fun live() = Kinetics.enabled && !EnergySaver.on
    private fun frameMs() = if (LiteMode.on) LiteMode.liveFrameMs else 40L

    /** Фон: градиент «небо» и бегущий блик. */
    private class Glow(private val d: Float) : android.graphics.drawable.Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var lastW = -1
        private val start = SystemClock.uptimeMillis()
        private var shineShader: LinearGradient? = null
        private val shineM = android.graphics.Matrix()

        override fun draw(c: Canvas) {
            val b = bounds
            r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val rad = 20 * d
            if (lastW != b.width()) {
                lastW = b.width()
                fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                    intArrayOf(0xFF37BBFE.toInt(), 0xFF2AA3EF.toInt(), 0xFF1E88E5.toInt(), 0xFF5B5FEF.toInt()), null, Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(r, rad, rad, fill)
            if (!live()) return
            val tt = ((SystemClock.uptimeMillis() - start) % 3200L) / 3200f
            val x = r.left - r.width() * 0.3f + tt * r.width() * 1.6f
            // Градиент блика создаётся один раз и только сдвигается (без мусора на каждом кадре).
            val sh = shineShader ?: LinearGradient(-50 * d, 0f, 50 * d, 0f,
                intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP).also { shineShader = it }
            shineM.setTranslate(x, 0f)
            sh.setLocalMatrix(shineM)
            shine.shader = sh
            c.save()
            c.clipRect(r)
            c.drawRoundRect(r, rad, rad, shine)
            c.restore()
            scheduleSelf({ invalidateSelf() }, SystemClock.uptimeMillis() + frameMs())
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Светящееся кольцо вокруг видео: дышит и медленно вращает блик. */
    private class Ring(ctx: Context, private val d: Float) : View(ctx) {
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val arc = RectF()
        private val start = SystemClock.uptimeMillis()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2
            val cy = h / 2
            val tt = if (live()) ((SystemClock.uptimeMillis() - start) % 2400L) / 2400f else 0.25f
            val breathe = (0.5f + 0.5f * kotlin.math.sin(tt * 2 * Math.PI)).toFloat()
            // Мягкое свечение вокруг.
            glow.color = 0xFFFFFFFF.toInt()
            glow.alpha = (40 + 50 * breathe).toInt()
            c.drawCircle(cx, cy, minOf(w, h) / 2, glow)
            // Белое кольцо и бегущий голубой блик по нему.
            val r = minOf(w, h) / 2 - 2.5f * d
            ring.strokeWidth = 2.2f * d
            ring.color = 0xFFFFFFFF.toInt()
            ring.alpha = 230
            c.drawCircle(cx, cy, r, ring)
            ring.color = 0xFF7DD3FC.toInt()
            ring.alpha = 255
            ring.strokeCap = Paint.Cap.ROUND
            arc.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(arc, tt * 360f, 70f, false, ring)
            if (live() && isShown) postInvalidateDelayed(frameMs())
        }
    }

    /** Белый кружок с самолётиком, который чуть покачивается. */
    private class Plane(ctx: Context) : View(ctx) {
        private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val fold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCDE9FB.toInt() }
        private val path = Path()
        private val foldPath = Path()
        private val start = SystemClock.uptimeMillis()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawCircle(w / 2, h / 2, minOf(w, h) / 2, circle)
            val tt = if (live()) ((SystemClock.uptimeMillis() - start) % 2400L) / 2400f else 0f
            val dy = (kotlin.math.sin(tt * 2 * Math.PI) * h * 0.04f).toFloat()
            val rot = (kotlin.math.sin(tt * 2 * Math.PI) * 5).toFloat()
            c.save()
            c.translate(0f, dy)
            c.rotate(rot, w / 2, h / 2)
            // Самолётик: треугольник с вырезом и сгибом.
            path.reset()
            path.moveTo(w * 0.22f, h * 0.48f)
            path.lineTo(w * 0.78f, h * 0.26f)
            path.lineTo(w * 0.66f, h * 0.76f)
            path.lineTo(w * 0.50f, h * 0.60f)
            path.close()
            c.drawPath(path, p)
            foldPath.reset()
            foldPath.moveTo(w * 0.50f, h * 0.60f)
            foldPath.lineTo(w * 0.78f, h * 0.26f)
            foldPath.lineTo(w * 0.44f, h * 0.70f)
            foldPath.close()
            c.drawPath(foldPath, fold)
            c.restore()
            if (live() && isShown) postInvalidateDelayed(frameMs())
        }
    }
}
