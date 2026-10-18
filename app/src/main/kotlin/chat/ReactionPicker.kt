// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ai.t

/**
 * Полный выбор реакции: недавние, свои реакции (нажатие - убрать), и сотни эмодзи по разделам
 * с прокруткой. Сетка рисуется одним View на раздел (не сотни TextView) - быстро и на слабых телефонах.
 * Эмодзи только из Unicode ≤ 12, чтобы показывались и на Android 7-10.
 */
object ReactionPicker {
    private fun e(s: String) = s.trim().split(Regex("\\s+"))

    class Cat(val icon: String, val ru: String, val en: String, val items: List<String>)

    val CATS: List<Cat> = listOf(
        Cat(
            "😀", "Улыбки и эмоции", "Smileys & emotions",
            e(
                "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 " +
                    "🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 " +
                    "🧐 😕 😟 🙁 ☹️ 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 " +
                    "😈 👿 💀 ☠️ 💩 🤡 👹 👺 👻 👽 👾 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊",
            ),
        ),
        Cat(
            "👍", "Жесты и люди", "Gestures & people",
            e(
                "👍 👎 👌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 🦾 👀 🧠 " +
                    "🤦 🤷 🙋 🙆 🙅 🙇 💁 🙎 🙍 💃 🕺 👯 🏃 🧘 🏋️ 🚶  🦸 🧙 🧛 🧟 🎅 👼 👶 👵 👴 👮 👨‍🍳 👑",
            ),
        ),
        Cat(
            "❤️", "Сердца и символы", "Hearts & symbols",
            e(
                "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💋 💯 💢 💥 💫 💦 💨 💬 💭 💤 ✅ " +
                    "❌ ❓ ❗ ‼️ ⁉️ ⚠️ 🚫 ⛔ 💡 📌 🔔 🔕 🆗 🆒 🆕 🆘 ➕ ➖ ✔️ ☑️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ ♻️ 🔞",
            ),
        ),
        Cat(
            "🎉", "Праздник", "Celebration",
            e(
                "🎉 🎊 🎈 🎁 🎂 🍾 🥂 🏆 🥇 🥈 🥉 🏅 🎖️ ⭐ 🌟 ✨ ⚡ 🔥 🌈 ☀️ 🌙 💎 👑 🎯 🎮 🎲 🎵 🎶 🎤 🎧 " +
                    "📸 🎬 🚀 🛸 🎆 🎇 🧨 💰 💸",
            ),
        ),
        Cat(
            "🐶", "Животные", "Animals",
            e(
                "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦉 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🦎 🐙 🦑 🦀 " +
                    "🐬 🐳 🦈 🐊 🦖 🦕 🐲 🦥 🦦 🦩 🌸 🌹 🌻 🌷 🍀 🌵 🌴 🍁 🍄 🌍",
            ),
        ),
        Cat(
            "🍕", "Еда и напитки", "Food & drink",
            e(
                "🍕 🍔 🍟 🌭 🌮 🌯 🍿 🍩 🍪 🍰 🧁 🎂 🍫 🍬 🍭 🍦 🍨 🍉 🍓 🍒 🍑 🥑 🍌 🍎 🍋 🍇 🥥 🥝 🌶️ 🥐 " +
                    "🍣 🍜 🍝 🥟 🥗 🍳 ☕ 🍵 🧃 🥤 🍺 🍻 🥂 🍷 🍸 🍹",
            ),
        ),
        Cat(
            "⚽", "Спорт и разное", "Sport & stuff",
            e(
                "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏓 🥊 🥋 ⛸️ 🎿 🏂 🚴 🏊 🏄 🧗 🎳 ♟️ 🧩 🧸 🚗 🏎️ 🚕 ✈️ 🚂 ⛵ 🏠 🏝️ 🗻 🎡 " +
                    "📱 💻 ⌚ 📷 💾 📚 ✏️ 📎 🔑 🔒 🔓 🛠️ 💣 🧲 💊 🧪 🔮 🧿 ⏰ ⌛",
            ),
        ),
    )

    private const val PREFS = "reactions_ui"
    private const val KEY_RECENT = "recent"
    private val DEFAULT_QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🔥", "🙏", "👏", "🥰", "😁", "🤔", "🤯", "😡", "🎉", "💯", "🤝")

    fun recent(ctx: Context): List<String> = runCatching {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT, "").orEmpty()
            .split('\u0001').filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** Запомнить выбранную реакцию - она встанет первой в «Недавних» и в быстрой полосе. */
    fun remember(ctx: Context, emoji: String) {
        val l = (listOf(emoji) + recent(ctx).filter { it != emoji }).take(24)
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_RECENT, l.joinToString("\u0001")).apply()
        }
    }

    /** Быстрая полоса над сообщением: недавние, затем популярные. */
    fun quick(ctx: Context, count: Int = 14): List<String> = (recent(ctx).take(6) + DEFAULT_QUICK).distinct().take(count)

    /**
     * Кнопка «+» в полосе реакций: яркий кружок с переливом, пульсирующим ореолом и
     * покачивающимся плюсом - сразу видно, что она открывает все эмодзи.
     */
    fun stylePlus(tv: TextView) {
        val d = tv.resources.displayMetrics.density
        val dr = PlusDrawable(d)
        tv.text = ""
        tv.background = dr
        tv.contentDescription = t("Все эмодзи", "All emoji")
        val size = (38 * d).toInt()
        tv.minWidth = size
        tv.minHeight = size
        tv.setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        val live = com.stoyko.smessenger.ui.Kinetics.enabled && !com.stoyko.smessenger.ui.EnergySaver.on
        if (!live) return
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                dr.phase = it.animatedValue as Float
                dr.invalidateSelf()
            }
        }
        tv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = anim.start()
            override fun onViewDetachedFromWindow(v: View) = anim.cancel()
        })
        if (tv.isAttachedToWindow) anim.start()
    }

    private class PlusDrawable(private val d: Float) : android.graphics.drawable.Drawable() {
        var phase = 0f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private var shaderKey = 0

        override fun draw(c: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val r = minOf(b.width(), b.height()) / 2f - 3 * d
            if (r <= 0) return
            // Ореол: расходящееся кольцо.
            halo.strokeWidth = 2 * d
            halo.color = 0xFFEC4899.toInt()
            halo.alpha = ((1f - phase) * 150).toInt()
            c.drawCircle(cx, cy, r * (0.85f + 0.3f * phase), halo)
            // Кружок с переливом (градиент поворачивается).
            val key = b.width() * 31 + b.height()
            if (fill.shader == null || key != shaderKey) {
                fill.shader = android.graphics.SweepGradient(
                    cx, cy,
                    intArrayOf(0xFF8B5CF6.toInt(), 0xFFEC4899.toInt(), 0xFFF59E0B.toInt(), 0xFF22D3EE.toInt(), 0xFF8B5CF6.toInt()),
                    null,
                )
                shaderKey = key
            }
            val m = android.graphics.Matrix()
            m.setRotate(phase * 360f, cx, cy)
            fill.shader.setLocalMatrix(m)
            val pulse = 1f + 0.05f * kotlin.math.sin(phase * 2 * Math.PI).toFloat()
            c.drawCircle(cx, cy, r * 0.8f * pulse, fill)
            // Плюс покачивается.
            plus.strokeWidth = 2.6f * d
            val a = r * 0.36f
            c.save()
            c.rotate(12f * kotlin.math.sin(phase * 2 * Math.PI).toFloat(), cx, cy)
            c.drawLine(cx - a, cy, cx + a, cy, plus)
            c.drawLine(cx, cy - a, cx, cy + a, plus)
            c.restore()
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(cf: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).toInt()

    @SuppressLint("SetTextI18n")
    fun show(context: Context, current: String?, onPick: (String?) -> Unit) {
        val ctx = context
        val mine = ChatExtras.split(current)
        val textPrimary = ContextCompat.getColor(ctx, R.color.textPrimary)
        val textSecondary = ContextCompat.getColor(ctx, R.color.textSecondary)
        val accent = ContextCompat.getColor(ctx, R.color.colorPrimary)
        val dialog = AlertDialog.Builder(ctx).create()
        var picked = false
        val pick: (String?) -> Unit = { v ->
            if (!picked) {
                picked = true
                dialog.dismiss()
                onPick(v)
            }
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 6f))
        }
        root.addView(
            TextView(ctx).apply {
                text = t("Реакция", "Reaction")
                textSize = 18f
                paint.isFakeBoldText = true
                setTextColor(textPrimary)
                setPadding(dp(ctx, 4f), 0, 0, dp(ctx, 6f))
            },
        )

        // Свои реакции на это сообщение: нажатие - убрать; кнопка - убрать все.
        if (mine.isNotEmpty()) {
            root.addView(label(ctx, t("Ваши реакции - нажмите, чтобы убрать", "Your reactions - tap to remove"), textSecondary))
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for (m in mine) {
                row.addView(
                    TextView(ctx).apply {
                        text = "$m  ✕"
                        textSize = 17f
                        setTextColor(textPrimary)
                        gravity = Gravity.CENTER
                        setPadding(dp(ctx, 12f), dp(ctx, 5f), dp(ctx, 12f), dp(ctx, 5f))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(ctx, 18f).toFloat()
                            setColor((accent and 0x00FFFFFF) or 0x2A000000)
                        }
                        contentDescription = t("Убрать $m", "Remove $m")
                        setOnClickListener { pick(m) }
                        com.stoyko.smessenger.ui.Kinetics.squish(this, 0.92f)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6f) },
                )
            }
            root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            if (mine.size > 1) {
                root.addView(
                    TextView(ctx).apply {
                        text = t("🗑 Убрать все мои реакции", "🗑 Remove all my reactions")
                        textSize = 14f
                        setTextColor(0xFFE5484D.toInt())
                        setPadding(dp(ctx, 4f), dp(ctx, 8f), dp(ctx, 4f), dp(ctx, 4f))
                        setOnClickListener { pick(null) }
                    },
                )
            }
        }

        // Вкладки разделов - прокрутка к разделу.
        val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tabScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
            setPadding(0, dp(ctx, 8f), 0, dp(ctx, 4f))
        }
        root.addView(tabScroll)

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            addView(list)
        }
        val h = (ctx.resources.displayMetrics.heightPixels * if (mine.isEmpty()) 0.5f else 0.42f).toInt()
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))

        val headers = ArrayList<Pair<String, View>>()
        val rec = recent(ctx)
        if (rec.isNotEmpty()) {
            val hd = label(ctx, t("Недавние", "Recent"), textSecondary)
            list.addView(hd)
            list.addView(EmojiGrid(ctx, rec.take(16), mine.toSet(), accent) { pick(it) })
            headers += "🕘" to hd
        }
        for (c in CATS) {
            val hd = label(ctx, t(c.ru, c.en), textSecondary)
            list.addView(hd)
            list.addView(EmojiGrid(ctx, c.items, mine.toSet(), accent) { pick(it) })
            headers += c.icon to hd
        }
        for ((icon, hd) in headers) {
            tabs.addView(
                TextView(ctx).apply {
                    text = icon
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 9f), dp(ctx, 4f), dp(ctx, 9f), dp(ctx, 4f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 14f).toFloat()
                        setColor(0x14808080)
                    }
                    setOnClickListener { scroll.smoothScrollTo(0, hd.top) }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 5f) },
            )
        }

        dialog.setView(root)
        com.stoyko.smessenger.ui.DialogTracker.add(dialog).show()
    }

    private fun label(ctx: Context, s: String, color: Int) = TextView(ctx).apply {
        text = s.uppercase()
        textSize = 11.5f
        letterSpacing = 0.06f
        paint.isFakeBoldText = true
        setTextColor(color)
        setPadding(dp(ctx, 4f), dp(ctx, 10f), dp(ctx, 4f), dp(ctx, 4f))
    }

    /** Сетка эмодзи одним View: рисует ячейки сама и отдаёт нажатое. */
    @SuppressLint("ViewConstructor")
    class EmojiGrid(
        ctx: Context,
        private val items: List<String>,
        private val selected: Set<String>,
        accent: Int,
        private val onTap: (String) -> Unit,
    ) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val cellMin = 46 * d
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 27 * d
            textAlign = Paint.Align.CENTER
        }
        private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0x00FFFFFF) or 0x33000000 }
        private val press = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22808080 }
        private var cols = 7
        private var cell = cellMin
        private var pressed = -1

        init {
            isClickable = true
            contentDescription = items.joinToString(" ")
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: (cellMin * 7).toInt()
            cols = (w / cellMin).toInt().coerceIn(4, 10)
            cell = w.toFloat() / cols
            val rows = (items.size + cols - 1) / cols
            setMeasuredDimension(w, (rows * cell).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val fm = paint.fontMetrics
            val dy = -(fm.ascent + fm.descent) / 2
            for ((i, e) in items.withIndex()) {
                val cx = (i % cols) * cell + cell / 2
                val cy = (i / cols) * cell + cell / 2
                if (e in selected) canvas.drawCircle(cx, cy, cell * 0.44f, sel)
                if (i == pressed) canvas.drawCircle(cx, cy, cell * 0.44f, press)
                canvas.drawText(e, cx, cy + dy, paint)
            }
        }

        private fun indexAt(x: Float, y: Float): Int {
            if (x < 0 || y < 0) return -1
            val c = (x / cell).toInt()
            val r = (y / cell).toInt()
            if (c >= cols) return -1
            val i = r * cols + c
            return if (i in items.indices) i else -1
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = indexAt(ev.x, ev.y)
                    invalidate()
                    return pressed >= 0
                }
                MotionEvent.ACTION_MOVE -> if (pressed >= 0 && indexAt(ev.x, ev.y) != pressed) {
                    pressed = -1
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val i = pressed
                    pressed = -1
                    invalidate()
                    if (i >= 0 && indexAt(ev.x, ev.y) == i) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        onTap(items[i])
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = -1
                    invalidate()
                }
            }
            return true
        }
    }
}
