// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.stoyko.smessenger.BuildConfig
import com.stoyko.smessenger.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * «О приложении».
 *
 * Шапка - живая заставка: по фону плывут цветные «сияния» и мерцают звёзды, значок приложения
 * покачивается в светящемся кольце с огоньками на орбитах, версия и подпись разработчика
 * переливаются, вокруг подписи вспыхивают искры.
 *
 * Всё остальное - объёмными кнопками-клавишами (как «Изменить профиль»): при входе они
 * по очереди «выпрыгивают» навстречу. Нажатие на раздел открывает его текст снизу.
 */
object AboutScreen {
    private class Section(val icon: String, val title: Int, val text: Int)

    private val features = listOf(
        Section("🔐", R.string.about_f_privacy_t, R.string.about_f_privacy),
        Section("💬", R.string.about_f_chats_t, R.string.about_f_chats),
        Section("👥", R.string.about_f_groups_t, R.string.about_f_groups),
        Section("🎬", R.string.about_f_media_t, R.string.about_f_media),
        Section("🛡", R.string.about_f_net_t, R.string.about_f_net),
        Section("🤖", R.string.about_f_ai_t, R.string.about_f_ai),
        Section("✨", R.string.about_f_design_t, R.string.about_f_design),
        Section("🔋", R.string.about_f_battery_t, R.string.about_f_battery),
    )

    /** «Как работает защита» - по шагам, простыми словами. */
    private val SECURITY = listOf(
        Section("🔐", R.string.sec_e2e_t, R.string.sec_e2e),
        Section("📨", R.string.sec_path_t, R.string.sec_path),
        Section("👀", R.string.sec_isp_t, R.string.sec_isp),
        Section("🌐", R.string.sec_noserver_t, R.string.sec_noserver),
        Section("📎", R.string.sec_files_t, R.string.sec_files),
        Section("🛡", R.string.sec_vpn_t, R.string.sec_vpn),
        Section("ℹ️", R.string.sec_honest_t, R.string.sec_honest),
    )

    /** Цвета клавиш (верх → низ) - разделы различаются цветом, как плитки. */
    private val KEY_COLORS = listOf(
        intArrayOf(0xFF8B7CFF.toInt(), 0xFF5B4BDB.toInt()),
        intArrayOf(0xFF2DD4BF.toInt(), 0xFF0E9488.toInt()),
        intArrayOf(0xFFFF8FB1.toInt(), 0xFFE0457B.toInt()),
        intArrayOf(0xFF60A5FA.toInt(), 0xFF2563EB.toInt()),
        intArrayOf(0xFFFBBF24.toInt(), 0xFFD97706.toInt()),
        intArrayOf(0xFF4ADE80.toInt(), 0xFF16A34A.toInt()),
        intArrayOf(0xFFC084FC.toInt(), 0xFF9333EA.toInt()),
        intArrayOf(0xFFFB923C.toInt(), 0xFFEA580C.toInt()),
    )

    fun show(ctx: Context) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cBg = ContextCompat.getColor(ctx, R.color.colorBackground)
        val cAccent = ContextCompat.getColor(ctx, R.color.settingsAccent)

        val dialog = Dialog(ctx, R.style.AppTheme_FullDialog)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }

        // ---------------- шапка: живая заставка ----------------
        val header = FrameLayout(ctx)
        header.addView(LiveBackdrop(ctx), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val headCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        header.addView(headCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        headCol.addView(
            ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Color.WHITE)
                background = null
                setOnClickListener { dialog.safeDismiss() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.START },
        )
        val icon = LiveIcon(ctx)
        headCol.addView(icon, LinearLayout.LayoutParams(dp(150), dp(150)))
        val appName = TextView(ctx).apply {
            text = "S-messenger"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(8 * d, 0f, 2 * d, 0x66000000)
        }
        headCol.addView(appName)
        // Версия - светящаяся «пилюля» с переливом.
        val version = ShimmerText(ctx, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFE08A.toInt(), 0xFF8EF0E0.toInt(), 0xFFFFFFFF.toInt())).apply {
            text = "●  " + ctx.getString(R.string.version_display, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(0x2EFFFFFF)
                setStroke(dp(1), 0x66FFFFFF)
            }
        }
        headCol.addView(version, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        headCol.addView(
            TextView(ctx).apply {
                setText(R.string.intro_tagline)
                textSize = 14f
                setTextColor(0xDDFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            },
        )
        // «Разработчик: Стойко Андрей» - переливается, вокруг вспыхивают искры, рамка «дышит».
        val dev = ShimmerText(ctx).apply {
            // «Разработчики» - небольшой подписью, имена - крупно, каждое на своей строке.
            val raw = ctx.getString(R.string.about_developer)
            val firstEnd = raw.indexOf('\n').let { if (it < 0) raw.length else it }
            text = android.text.SpannableString(raw).apply {
                setSpan(android.text.style.RelativeSizeSpan(0.78f), 0, firstEnd, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (firstEnd < raw.length) setSpan(android.text.style.RelativeSizeSpan(1.12f), firstEnd, raw.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setLineSpacing(4 * d, 1f)
            letterSpacing = 0.02f
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = 22 * d
                setColor(0x26FFFFFF)
                setStroke(dp(1), 0x77FFFFFF)
            }
        }
        // Рамка не обрезает подпись, когда та «дышит» (раньше срезались углы).
        headCol.clipChildren = false
        headCol.clipToPadding = false
        val devFrame = SparkleFrame(ctx).apply {
            addView(dev, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        headCol.addView(devFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        column.addView(header)

        // ---------------- кнопки ----------------
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(28))
        }
        val keys = ArrayList<View>()
        fun heading(res: Int) = TextView(ctx).apply {
            setText(res)
            textSize = 13f
            setTextColor(cAccent)
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = true
            letterSpacing = 0.06f
            setPadding(dp(6), dp(18), dp(6), dp(10))
        }
        // Приветствие - рука машет.
        val wave = TextView(ctx).apply {
            text = "👋"
            textSize = 26f
        }
        val hello = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), 0)
            addView(wave)
            addView(
                TextView(ctx).apply {
                    setText(R.string.about_welcome)
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(10), 0, 0, 0)
                },
            )
        }
        body.addView(hello)
        // «💛 На жизнь проекта» - сбор ЮMoney.
        body.addView(
            com.stoyko.smessenger.chat.CoinShop.donateButton(ctx),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(14) },
        )
        // Канал разработчика: новости, обновления, тестовые версии.
        body.addView(
            com.stoyko.smessenger.ui.DevChannel.button(ctx),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
        )

        /** Объёмная клавиша (своя, независимая: нажимается только та, которую нажали). */
        fun key(emoji: String, title: CharSequence, colors: IntArray, tall: Boolean, click: () -> Unit): View =
            Key3D(ctx, emoji, title.toString(), colors, tall, click)

        /**
         * Сетка клавиш по две в ряд, все одной высоты и ширины. Если клавиш нечётное число,
         * последняя занимает всю строку - без пустых мест и «кривых» рядов.
         */
        fun grid(items: List<Section>, colorShift: Int, onClick: (Section) -> Unit): LinearLayout {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            items.chunked(2).forEachIndexed { r, pair ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isMotionEventSplittingEnabled = false
                }
                pair.forEachIndexed { c, s ->
                    val colors = KEY_COLORS[(r * 2 + c + colorShift) % KEY_COLORS.size]
                    val single = pair.size == 1
                    val k = key(s.icon, ctx.getString(s.title), colors, !single) { onClick(s) }
                    row.addView(
                        k,
                        LinearLayout.LayoutParams(0, dp(if (single) 62 else 96), 1f).apply {
                            if (!single) {
                                if (c == 0) marginEnd = dp(5) else marginStart = dp(5)
                            }
                        },
                    )
                    keys += k
                }
                box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
            return box
        }

        fun wide(emoji: String, title: Int, colors: IntArray, click: () -> Unit) {
            val k = key(emoji, ctx.getString(title), colors, false, click)
            body.addView(k, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)).apply { bottomMargin = dp(10) })
            keys += k
        }

        // Как пользоваться.
        body.addView(heading(R.string.about_howto))
        wide("👋", R.string.ob_replay, KEY_COLORS[0]) { Onboarding.show(ctx) }
        wide("🧭", R.string.tour_start, KEY_COLORS[1]) {
            dialog.safeDismiss()
            Tour.launch(ctx)
        }
        wide("🔄", R.string.update_check, KEY_COLORS[3]) { com.stoyko.smessenger.update.Updater.checkNow(ctx) }

        // Защита сообщений.
        body.addView(heading(R.string.about_security))
        body.addView(grid(SECURITY, 0) { s -> info(ctx, s.icon, ctx.getString(s.title), ctx.getString(s.text), false) })

        // Возможности.
        body.addView(heading(R.string.about_features))
        body.addView(grid(features, 3) { s -> info(ctx, s.icon, ctx.getString(s.title), ctx.getString(s.text), false) })

        // История версий и лицензии.
        body.addView(heading(R.string.about_history_title))
        wide("🗓", R.string.about_history_button, KEY_COLORS[6]) {
            info(ctx, "🗓", ctx.getString(R.string.about_history_button), com.stoyko.smessenger.update.Updater.history(ctx), true)
        }
        body.addView(heading(R.string.about_open_source))
        wide("📜", R.string.about_licenses_button, KEY_COLORS[4]) {
            info(ctx, "📜", ctx.getString(R.string.about_licenses_button), ctx.getString(R.string.about_credits), true)
        }

        column.addView(body)
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(cBg)
            addView(column)
        }
        scroll.fitSystemBars()
        dialog.setContentView(scroll)
        dialog.show()

        // ---------------- встреча ----------------
        if (!Kinetics.enabled) return
        icon.scaleX = 0.4f
        icon.scaleY = 0.4f
        icon.alpha = 0f
        icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(700)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        listOf<View>(appName, version, devFrame).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 16 * d
            v.animate().alpha(1f).translationY(0f).setStartDelay(220L + i * 90L).setDuration(420)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
        // Рука машет.
        wave.post {
            wave.pivotX = wave.width * 0.7f
            wave.pivotY = wave.height * 0.9f
            wave.animate().rotation(18f).setStartDelay(400).setDuration(160).withEndAction {
                wave.animate().rotation(-12f).setStartDelay(0).setDuration(160).withEndAction {
                    wave.animate().rotation(14f).setDuration(160).withEndAction {
                        wave.animate().rotation(0f).setDuration(200).start()
                    }.start()
                }.start()
            }.start()
        }
        // Клавиши «выпрыгивают» навстречу по очереди: снизу, из глубины, с пружинкой.
        keys.forEachIndexed { i, k ->
            if (i > 22) return@forEachIndexed
            k.alpha = 0f
            k.scaleX = 0.7f
            k.scaleY = 0.7f
            k.translationY = 40 * d
            k.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(300L + i * 55L).setDuration(520)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.8f)).start()
        }
    }

    /** Текст раздела - в окне снизу (крупный заголовок с эмодзи; историю и лицензии можно выделить). */
    private fun info(ctx: Context, emoji: String, title: String, text: String, selectable: Boolean) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sheet = BottomSheetDialog(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(20), dp(10), dp(20), dp(20))
        }
        box.addView(
            View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 3 * d
                    setColor(0x55888888)
                }
            },
            LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) },
        )
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val em = TextView(ctx).apply {
            this.text = emoji
            textSize = 26f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x1F5B4BDB)
            }
        }
        head.addView(em, LinearLayout.LayoutParams(dp(52), dp(52)))
        head.addView(
            TextView(ctx).apply {
                this.text = title
                textSize = 19f
                setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        box.addView(head)
        val bodyText = TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary))
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(14), 0, dp(14))
            if (selectable) setTextIsSelectable(true)
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(bodyText)
        }
        // Короткий текст - окно по размеру текста, длинный - листается внутри (кнопка остаётся видна).
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(
            Btn.key3d(ctx, ctx.getString(R.string.close)) { sheet.safeDismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)),
        )
        // Окно не выше 85% экрана.
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.85f).toInt()
        val wrap = object : FrameLayout(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
            }
        }
        wrap.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.setContentView(wrap)
        (wrap.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        sheet.setOnShowListener { sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        sheet.show()
        em.scaleX = 0.5f
        em.scaleY = 0.5f
        em.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
    }
}

/** Анимация идёт, только пока вид на экране и украшения не выключены (энергосбережение). */
private fun View.liveOn(): Boolean = Kinetics.enabled && !EnergySaver.on && isShown

/**
 * Живой фон шапки: градиент цветов мессенджера, по которому медленно плывут три «сияния»,
 * мерцают звёзды и поднимаются искорки - как живые обои.
 */
private class LiveBackdrop(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()
    private val base = Paint()
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val m = Matrix()
    private var blobs: Array<Shader> = emptyArray()
    private val blobColors = intArrayOf(0xAA9D8CFF.toInt(), 0x8833E0C8.toInt(), 0x88FF7EB3.toInt())

    // Звёзды: x, y (доли размера), размер, фаза мерцания.
    private val stars = Array(34) { i ->
        val r = java.util.Random(i * 7919L + 13)
        floatArrayOf(r.nextFloat(), r.nextFloat(), 0.8f + r.nextFloat() * 1.8f, r.nextFloat() * 6.28f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        base.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF7B6CF6.toInt(), 0xFF5B4BDB.toInt(), 0xFF22186E.toInt()), null, Shader.TileMode.CLAMP,
        )
        val r = maxOf(w, h) * 0.55f
        blobs = Array(blobColors.size) { i ->
            RadialGradient(0f, 0f, r, blobColors[i], blobColors[i] and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        canvas.drawRect(0f, 0f, w, h, base)
        // Сияния плывут по плавным петлям.
        for (i in blobs.indices) {
            val sp = 0.13f + i * 0.05f
            val x = w * (0.5f + 0.42f * sin(t * sp + i * 2.1f))
            val y = h * (0.45f + 0.38f * cos(t * sp * 1.3f + i * 1.3f))
            val sc = 0.85f + 0.2f * sin(t * 0.4f + i)
            m.setScale(sc, sc)
            m.postTranslate(x, y)
            blobs[i].setLocalMatrix(m)
            blob.shader = blobs[i]
            canvas.drawRect(0f, 0f, w, h, blob)
        }
        blob.shader = null
        // Мерцающие звёзды.
        for (s in stars) {
            val tw = (sin(t * (1.2f + s[2] * 0.5f) + s[3]) + 1f) / 2f
            star.alpha = (40 + tw * 200).toInt()
            canvas.drawCircle(s[0] * w, s[1] * h, s[2] * d * (0.6f + tw * 0.6f), star)
        }
        // Искорки поднимаются снизу вверх.
        for (i in 0 until 10) {
            val seed = (i * 0.137f) % 1f
            val p = (t * (0.06f + seed * 0.05f) + seed) % 1f
            val x = w * ((i * 0.31f + seed) % 1f) + sin(t + i) * 6 * d
            val y = h * (1.05f - p * 1.1f)
            star.alpha = ((1f - p) * 160).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, (1.2f + seed * 1.6f) * d, star)
        }
        if (liveOn()) postInvalidateOnAnimation() else if (isAttachedToWindow) postInvalidateDelayed(500)
    }
}

/**
 * «Живой» значок приложения: покачивается, вокруг - кольцо с бегущим бликом,
 * «дышащий» ореол и огоньки на двух орбитах.
 */
private class LiveIcon(ctx: Context) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33FFFFFF
    }
    private val m = Matrix()
    private val rect = RectF()
    private var sweep: SweepGradient? = null
    private var halo: RadialGradient? = null
    private val tilt = Math.toRadians(-20.0)
    private val image = ImageView(ctx).apply {
        setImageDrawable(ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
        elevation = 10 * resources.displayMetrics.density
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(image, LayoutParams((92 * d).toInt(), (92 * d).toInt(), Gravity.CENTER))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        sweep = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0xFF8EF0E0.toInt(), 0x00FFFFFF, 0x00FFFFFF), null,
        )
        halo = RadialGradient(
            w / 2f, h / 2f, minOf(w, h) / 2f,
            intArrayOf(0x88B9AFFF.toInt(), 0x33B9AFFF, 0x00B9AFFF), floatArrayOf(0.45f, 0.75f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val cx = w / 2
        val cy = h / 2
        val live = Kinetics.enabled
        // Ореол «дышит».
        val pulse = if (live) 0.88f + 0.12f * sin(t * 1.8f) else 1f
        halo?.let {
            m.setScale(pulse, pulse, cx, cy)
            it.setLocalMatrix(m)
            glow.shader = it
            canvas.drawCircle(cx, cy, minOf(w, h) / 2f, glow)
        }
        // Орбиты.
        val r1 = 58 * d
        val r2 = 70 * d
        orbit.strokeWidth = 1 * d
        canvas.drawCircle(cx, cy, r1, orbit)
        rect.set(cx - r2, cy - r2 * 0.42f, cx + r2, cy + r2 * 0.42f)
        canvas.save()
        canvas.rotate(-20f, cx, cy)
        canvas.drawOval(rect, orbit)
        canvas.restore()
        // Кольцо с бегущим бликом.
        sweep?.let {
            m.setRotate((t * 120f) % 360f, cx, cy)
            it.setLocalMatrix(m)
            ring.shader = it
            ring.strokeWidth = 3 * d
            canvas.drawCircle(cx, cy, 50 * d, ring)
        }
        // Значок покачивается.
        if (live) {
            image.translationY = sin(t * 1.6f) * 4 * d
            image.rotation = sin(t * 0.9f) * 3f
        }
        super.dispatchDraw(canvas)
        // Огоньки на орбитах.
        val a1 = t * 1.4f
        dot.color = 0xFFFFE08A.toInt()
        canvas.drawCircle(cx + cos(a1) * r1, cy + sin(a1) * r1, 3.2f * d, dot)
        dot.color = 0xFF8EF0E0.toInt()
        canvas.drawCircle(cx + cos(a1 + 3.14f) * r1, cy + sin(a1 + 3.14f) * r1, 2.4f * d, dot)
        val a2 = -t * 0.9f
        val ox = (cos(a2) * r2).toDouble()
        val oy = (sin(a2) * r2 * 0.42f).toDouble()
        val px = (ox * cos(tilt) - oy * sin(tilt)).toFloat()
        val py = (ox * sin(tilt) + oy * cos(tilt)).toFloat()
        dot.color = 0xFFFF9EC4.toInt()
        canvas.drawCircle(cx + px, cy + py, 2.8f * d, dot)
        if (liveOn()) postInvalidateOnAnimation()
    }
}

/** Рамка с искрами: по краям содержимого вспыхивают и гаснут звёздочки, само содержимое «дышит». */
private class SparkleFrame(ctx: Context) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun dispatchDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val child = getChildAt(0)
        if (child != null && Kinetics.enabled) {
            val s = 1f + 0.025f * sin(t * 2f)
            child.scaleX = s
            child.scaleY = s
        }
        super.dispatchDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 0 until 8) {
            val phase = t * (0.7f + (i % 3) * 0.25f) + i * 0.83f
            val life = phase % 1f
            val k = phase.toInt() + i * 31
            // Каждая вспышка - на новом месте вдоль верхнего или нижнего края.
            val u = (k * 0.618f) % 1f
            val x = w * (0.04f + u * 0.92f)
            val y = if (k % 2 == 0) h * (0.06f + (k % 3) * 0.04f) else h * (0.94f - (k % 3) * 0.04f)
            val glowK = sin(life * 3.14f)
            val size = (2.5f + (k % 4)) * d * glowK
            if (size < 0.4f) continue
            p.color = if (i % 3 == 0) 0xFFFFE08A.toInt() else Color.WHITE
            p.alpha = (glowK * 255).toInt().coerceIn(0, 255)
            path.reset()
            path.moveTo(x, y - size * 2)
            path.quadTo(x, y, x + size * 2, y)
            path.quadTo(x, y, x, y + size * 2)
            path.quadTo(x, y, x - size * 2, y)
            path.quadTo(x, y, x, y - size * 2)
            canvas.drawPath(path, p)
        }
        if (liveOn()) postInvalidateOnAnimation()
    }
}

/**
 * Текст, переливающийся цветами мессенджера (белый → фиолетовый → бирюзовый → золотой).
 * Анимация идёт, только пока текст на экране.
 */
private class ShimmerText(
    ctx: Context,
    private val colors: IntArray = intArrayOf(0xFFFFFFFF.toInt(), 0xFFB9AFFF.toInt(), 0xFF5EEAD4.toInt(), 0xFFFFD27A.toInt(), 0xFFFFFFFF.toInt()),
) : TextView(ctx) {
    private val shiftMatrix = Matrix()
    private var shader: LinearGradient? = null
    private var shift = 0f
    private val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            shift = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        shader = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, null, Shader.TileMode.MIRROR)
        paint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        shader?.let {
            shiftMatrix.setTranslate(width * 2f * shift, 0f)
            it.setLocalMatrix(shiftMatrix)
        }
        super.onDraw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Kinetics.enabled && !EnergySaver.on) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * Объёмная клавиша «О приложении»: бортик снизу темнее, сверху блик; при нажатии крышка
 * опускается на бортик. Рисуется сама и сама следит за касанием - нажимается только она
 * (раньше в паре нажимались обе соседние плитки). В высокой - эмодзи сверху, подпись в 2 строки.
 */
private class Key3D(
    ctx: Context,
    private val emoji: String,
    private val title: String,
    private val colors: IntArray,
    private val tall: Boolean,
    private val onTap: () -> Unit,
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val depth = 5 * d
    private val radius = 16 * d
    private val side = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darker(colors.last()) }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        setShadowLayer(1.5f * d, 0f, 1f * d, 0x40000000)
    }
    private val emojiPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val box = RectF()
    private var down = false
    private var titleLayout: android.text.StaticLayout? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = title
    }

    private fun darker(c: Int): Int {
        val f = 0.68f
        return (c and 0xFF000000.toInt()) or (((c shr 16 and 0xFF) * f).toInt() shl 16) or
            (((c shr 8 and 0xFF) * f).toInt() shl 8) or ((c and 0xFF) * f).toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        face.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), colors, null, Shader.TileMode.CLAMP)
        gloss.shader = LinearGradient(0f, 0f, 0f, h * 0.5f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        text.textSize = (if (tall) 13.5f else 15.5f) * resources.displayMetrics.scaledDensity
        emojiPaint.textSize = (if (tall) 24f else 20f) * resources.displayMetrics.scaledDensity
        val avail = (w - 20 * d).toInt().coerceAtLeast(1)
        titleLayout = if (tall) makeLayout(title, avail, 2) else null
    }

    @Suppress("DEPRECATION")
    private fun makeLayout(s: String, width: Int, lines: Int): android.text.StaticLayout =
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            android.text.StaticLayout.Builder.obtain(s, 0, s.length, text, width)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(lines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
        } else {
            android.text.StaticLayout(s, text, width, android.text.Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val shift = if (down) depth - 1 * d else 0f
        // Бортик.
        box.set(0f, depth, w, h)
        canvas.drawRoundRect(box, radius, radius, side)
        // Крышка.
        box.set(0f, shift, w, h - depth + shift)
        canvas.drawRoundRect(box, radius, radius, face)
        box.set(2 * d, shift + 2 * d, w - 2 * d, shift + (h - depth) * 0.5f)
        canvas.drawRoundRect(box, radius - 2 * d, radius - 2 * d, gloss)
        val faceH = h - depth
        if (tall) {
            val l = titleLayout
            val emojiH = emojiPaint.textSize
            val total = emojiH + 6 * d + (l?.height ?: 0)
            var y = shift + (faceH - total) / 2
            val ew = emojiPaint.measureText(emoji)
            canvas.drawText(emoji, (w - ew) / 2, y - emojiPaint.ascent() * 0.92f, emojiPaint)
            y += emojiH + 6 * d
            if (l != null) {
                canvas.save()
                canvas.translate((w - l.width) / 2, y)
                l.draw(canvas)
                canvas.restore()
            }
        } else {
            val line = android.text.TextUtils.ellipsize("$emoji  $title", text, w - 24 * d, android.text.TextUtils.TruncateAt.END).toString()
            val tw = text.measureText(line)
            val baseline = shift + faceH / 2 - (text.ascent() + text.descent()) / 2
            canvas.drawText(line, (w - tw) / 2, baseline, text)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                down = true
                invalidate()
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                if (down != inside) {
                    down = inside
                    invalidate()
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                val was = down
                down = false
                invalidate()
                if (was) performClick()
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                down = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        onTap()
        return true
    }
}
