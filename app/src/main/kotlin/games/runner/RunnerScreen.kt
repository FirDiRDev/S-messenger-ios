// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.Coins
import com.stoyko.smessenger.games.GameSfx
import com.stoyko.smessenger.games.RunnerMusic
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.CoinView
import com.stoyko.smessenger.ui.LiteMode
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss
import kotlin.math.abs

/**
 * Окно «Бега по рельсам» в 3D: поверхность OpenGL, поверх - счёт ([HudView]), шапка
 * (назад, музыка, пауза, правила, кошелёк) и окна: старт, пауза, итоги (с «Продолжить за
 * монеты» один раз), облики (живой 3D-просмотр, покупка и выбор). Свайпы - сразу при
 * движении пальца (не дожидаясь отпускания), стрелки/WASD на клавиатуре.
 */
internal object RunnerScreen {
    /** Сколько монет забега дают 1 монету мессенджера. */
    private const val COINS_PER_WALLET = 10

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner", Context.MODE_PRIVATE)

    fun best(ctx: Context) = prefs(ctx).getInt("best", 0)

    @SuppressLint("ClickableViewAccessibility")
    fun show(ctx: Context) {
        // Без OpenGL ES 2.0 3D не запустить - честно сообщаем.
        val glOk = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.deviceConfigurationInfo.reqGlEsVersion >= 0x20000
        }.getOrDefault(true)
        if (!glOk) {
            Toast.makeText(ctx, R.string.runner3_no_gl, Toast.LENGTH_LONG).show()
            return
        }
        val lite = LiteMode.on
        // В игре своя музыка своего сезона - фоновая музыка темы приложения замолкает.
        com.stoyko.smessenger.ui.ThemeMusic.hold("runner")
        GameSfx.prepare(ctx)
        RunnerSfx.prepare(ctx)
        RunnerMusic.prepare()
        // Сезонная тема: зима (1 декабря - 1 февраля), весна, лето, осень - со своей музыкой.
        val seasonNow = RunnerSeason.current(ctx)
        fun applySeasonMusic(season: Int) {
            val track = RunnerSeason.track(season)
            val on = track != null && RunnerSeason.musicOn(ctx)
            if (track != null) SeasonMusic.track = track
            SeasonMusic.volume = 0.8f
            RunnerMusic.seasonal = on
            if (on) SeasonMusic.prepare(ctx)
        }
        applySeasonMusic(seasonNow)
        ModelCache.prepare(lite)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val main = Handler(Looper.getMainLooper())
        val dialog = com.stoyko.smessenger.ui.DialogTracker.add(Dialog(ctx, R.style.AppTheme_FullDialog))
        val root = FrameLayout(ctx).apply { setBackgroundColor(0xFF0B1026.toInt()) }

        val world = World(lite)
        world.season = seasonNow
        world.sideVariants = Models.sideCount(lite)
        world.skin = RunnerSkins.equipped(ctx)
        world.trail = RunnerSkins.all[world.skin].trail
        val gl = RunnerGlView(ctx, world, lite)
        val hud = HudView(ctx, world, gl.renderer)
        root.addView(gl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        /** Выполнить в потоке игры (между кадрами). */
        fun game(block: () -> Unit) = gl.queueEvent { block() }

        // ---------------------------------------------------------------- шапка
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(12), dp(6))
        }
        bar.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = null
            contentDescription = ctx.getString(R.string.runner3_back)
            setOnClickListener { dialog.safeDismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(TextView(ctx).apply {
            setText(R.string.runner_title)
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setShadowLayer(4 * d, 0f, d, 0x88000000.toInt())
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        fun syncMusic() {
            val st = world.state
            if ((st == World.RUN || st == World.COUNT) && RunnerMusic.on(ctx)) RunnerMusic.play(ctx) else RunnerMusic.pause()
        }

        val music = TextView(ctx).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = ctx.getString(R.string.runner_music)
        }
        fun musicLabel() {
            music.text = "🎵"
            music.alpha = if (RunnerMusic.on(ctx)) 1f else 0.35f
        }
        musicLabel()
        music.setOnClickListener {
            val turnOn = !RunnerMusic.on(ctx)
            RunnerMusic.setEnabled(ctx, turnOn)
            if (turnOn && !GameSfx.enabled(ctx)) GameSfx.setEnabled(ctx, true)
            musicLabel()
            syncMusic()
            Toast.makeText(ctx, if (turnOn) R.string.runner_music_on else R.string.runner_music_off, Toast.LENGTH_SHORT).show()
        }
        bar.addView(music, LinearLayout.LayoutParams(dp(42), dp(44)))
        val pauseBtn = TextView(ctx).apply {
            text = "⏸"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = ctx.getString(R.string.runner_paused)
            setOnClickListener { game { world.pause() } }
        }
        bar.addView(pauseBtn, LinearLayout.LayoutParams(dp(42), dp(44)))
        bar.addView(Btn.nice(ctx, "❓", intArrayOf(0xFF60A5FA.toInt(), 0xFF2563EB.toInt())) {
            game { world.pause() }
            showRules(ctx)
        }, LinearLayout.LayoutParams(dp(44), dp(40)).apply { marginEnd = dp(8) })
        val coin = CoinView(ctx)
        bar.addView(coin, LinearLayout.LayoutParams(dp(26), dp(26)))
        val wallet = TextView(ctx).apply {
            textSize = 16f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(6), 0, 0, 0)
        }
        bar.addView(wallet)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        bar.post { hud.topInset = bar.height.toFloat() }

        // ---------------------------------------------------------------- окно меню/паузы/итогов
        fun panelBg(c1: Int, c2: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)).apply {
            cornerRadius = 28 * d
            setStroke(dp(1), 0x66FFFFFF)
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = panelBg(0xEE1E1B4B.toInt(), 0xEE312E81.toInt())
        }
        root.addView(panel, FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // ---------------------------------------------------------------- главное меню
        // Сцена с бегуном остаётся в центре: сверху - название и сезон, снизу - большая
        // кнопка «Играть», рекорд и ряд плиток (облики, магазин, сезон, музыка, управление).
        val menuUi = FrameLayout(ctx).apply { visibility = View.GONE }
        root.addView(menuUi, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val menuTop = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(66), dp(20), 0)
        }
        val menuTitle = TextView(ctx).apply {
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD))
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            setShadowLayer(14 * d, 0f, 3 * d, 0xCC000000.toInt())
        }
        val menuChip = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(5), dp(14), dp(5))
        }
        val menuHint = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(0xE6E0E7FF.toInt())
            gravity = Gravity.CENTER
            setShadowLayer(6 * d, 0f, d, 0xAA000000.toInt())
            setPadding(dp(8), dp(8), dp(8), 0)
            maxLines = 3
        }
        menuTop.addView(menuTitle)
        menuTop.addView(menuChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        menuTop.addView(menuHint)
        menuUi.addView(menuTop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        val menuBottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(18))
        }
        val menuBest = TextView(ctx).apply {
            textSize = 15f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(8 * d, 0f, d, 0xAA000000.toInt())
        }
        menuBottom.addView(menuBest)
        val playBtn = TextView(ctx).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD))
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setShadowLayer(6 * d, 0f, 2 * d, 0x66000000)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF4ADE80.toInt(), 0xFF16A34A.toInt())).apply {
                cornerRadius = 34 * d
                setStroke(dp(3), 0xCCFFFFFF.toInt())
            }
            elevation = 10 * d
            isClickable = true
        }
        com.stoyko.smessenger.ui.Kinetics.squish(playBtn, 0.94f)
        menuBottom.addView(playBtn, LinearLayout.LayoutParams(dp(260), dp(66)).apply { topMargin = dp(10) })
        // «Дыхание» кнопки - зовёт играть.
        val playPulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            playBtn,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.045f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.045f),
        ).apply {
            duration = 900
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
        }
        val tiles = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        menuBottom.addView(tiles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        menuUi.addView(menuBottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        /** Плитка меню: значок, подпись и (для переключателей) состояние снизу. */
        fun menuTile(icon: String, label: String, state: String?, accent: Int, click: () -> Unit): View {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(8), dp(2), dp(7))
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x55312E81, 0x881E1B4B.toInt())).apply {
                    cornerRadius = 18 * d
                    setStroke(dp(1), (accent and 0x00FFFFFF) or 0x99000000.toInt())
                }
                isClickable = true
                setOnClickListener { click() }
            }
            box.addView(TextView(ctx).apply {
                text = icon
                textSize = 22f
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            box.addView(TextView(ctx).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
            if (state != null) {
                box.addView(TextView(ctx).apply {
                    text = state
                    textSize = 9.5f
                    setTextColor(accent)
                    setTypeface(Typeface.DEFAULT_BOLD)
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
            }
            com.stoyko.smessenger.ui.Kinetics.squish(box, 0.9f)
            return box
        }

        fun fillPanel(title: String, body: String, buttons: List<Triple<String, IntArray, () -> Unit>>) {
            panel.removeAllViews()
            panel.addView(TextView(ctx).apply {
                text = title
                textSize = 23f
                setTextColor(0xFFFDE047.toInt())
                setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
            })
            if (body.isNotEmpty()) {
                panel.addView(TextView(ctx).apply {
                    text = body
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(6))
                })
            }
            // Главная кнопка - крупная во всю ширину, остальные - аккуратной сеткой по две.
            buttons.firstOrNull()?.let { (label, colors, click) ->
                panel.addView(Btn.nice(ctx, label, colors) { click() }.apply { textSize = 17f }, LinearLayout.LayoutParams(dp(252), dp(54)).apply { topMargin = dp(10) })
            }
            var row: LinearLayout? = null
            for ((i, b) in buttons.drop(1).withIndex()) {
                val (label, colors, click) = b
                if (i % 2 == 0) {
                    row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    panel.addView(row, LinearLayout.LayoutParams(dp(252), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                }
                row?.addView(Btn.nice(ctx, label, colors) { click() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i % 2 == 1) marginStart = dp(8) })
            }
            panel.visibility = View.VISIBLE
            if (com.stoyko.smessenger.ui.Kinetics.enabled) {
                panel.scaleX = 0.9f
                panel.scaleY = 0.9f
                panel.animate().scaleX(1f).scaleY(1f).setDuration(240).start()
            }
        }

        // ---------------------------------------------------------------- облики
        val skins = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF01E1B4B.toInt(), 0xF0111033.toInt())).apply {
                cornerRadii = floatArrayOf(26 * d, 26 * d, 26 * d, 26 * d, 0f, 0f, 0f, 0f)
                setStroke(dp(2), 0xFFFDE047.toInt())
            }
            visibility = View.GONE
            isClickable = true
        }
        root.addView(skins, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        var skinIdx = world.skin
        var skinsOpen = false
        val skinName = TextView(ctx).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
        }
        val skinDesc = TextView(ctx).apply {
            textSize = 14f
            setTextColor(0xFFE0E7FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
            minLines = 2
        }
        val skinInfo = TextView(ctx).apply {
            textSize = 14f
            setTextColor(0xFFFDE047.toInt())
            gravity = Gravity.CENTER
        }
        lateinit var skinAction: TextView
        val refreshAll = arrayOf<() -> Unit>({})

        fun showSkin() {
            val sk = RunnerSkins.all[skinIdx]
            val owned = RunnerSkins.isOwned(ctx, skinIdx)
            val eq = RunnerSkins.equipped(ctx) == skinIdx
            skinName.text = (if (sk.legendary) "👑 " else "") + ctx.getString(sk.name)
            skinName.setTextColor(if (sk.legendary) 0xFFFDE047.toInt() else android.graphics.Color.WHITE)
            // Персонажи - со своей способностью в забеге.
            val perk = Perks.text(sk.perk)
            skinDesc.text = ctx.getString(sk.desc) + if (perk.isNotEmpty()) "\n" + com.stoyko.smessenger.ai.t("Способность: ", "Ability: ") + perk else ""
            skinInfo.text = buildString {
                append("${skinIdx + 1} / ${RunnerSkins.all.size}")
                when {
                    sk.legendary -> append("  ·  ").append(com.stoyko.smessenger.ai.t("ЛЕГЕНДАРНЫЙ", "LEGENDARY"))
                    sk.hero -> append("  ·  ").append(com.stoyko.smessenger.ai.t("персонаж", "character"))
                    sk.special -> append("  ·  ").append(ctx.getString(R.string.runner3_special))
                }
            }
            val deal = !owned && RunnerSkins.dealToday(ctx) == skinIdx
            skinAction.text = when {
                eq -> ctx.getString(R.string.runner3_equipped)
                owned -> ctx.getString(R.string.runner3_equip)
                // Эксклюзив - только в магазине мессенджера.
                sk.shopOnly -> com.stoyko.smessenger.ai.t("⚡ В магазине", "⚡ In the shop")
                deal -> "🔥 " + ctx.getString(R.string.runner3_buy, RunnerSkins.priceNow(ctx, skinIdx)) + " −${RunnerSkins.DEAL_OFF}%"
                else -> ctx.getString(R.string.runner3_buy, sk.price)
            }
            skinAction.alpha = if (eq) 0.6f else 1f
            val i = skinIdx
            game { world.previewSkin = i }
        }
        skins.addView(TextView(ctx).apply {
            setText(R.string.runner3_skins_title)
            textSize = 15f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
        })
        skins.addView(skinName)
        skins.addView(skinDesc)
        skins.addView(skinInfo)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val arrows = intArrayOf(0xFF60A5FA.toInt(), 0xFF2563EB.toInt())
        row.addView(Btn.nice(ctx, "◀", arrows) {
            skinIdx = (skinIdx - 1 + RunnerSkins.all.size) % RunnerSkins.all.size
            showSkin()
        }, LinearLayout.LayoutParams(dp(56), dp(50)))
        skinAction = Btn.nice(ctx, "", intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) {
            val i = skinIdx
            val sk = RunnerSkins.all[i]
            when {
                RunnerSkins.equipped(ctx) == i -> Unit
                RunnerSkins.isOwned(ctx, i) -> RunnerSkins.equip(ctx, i)
                sk.shopOnly -> runCatching { com.stoyko.smessenger.chat.CoinShop.show(ctx, com.stoyko.smessenger.chat.ShopExtras.RUNNER_FOCUS) }
                RunnerSkins.buy(ctx, i) -> {
                    Toast.makeText(ctx, ctx.getString(R.string.runner3_bought, ctx.getString(sk.name)), Toast.LENGTH_SHORT).show()
                    GameSfx.play(ctx, GameSfx.S.WIN)
                    coin.pulse()
                }
                // Легендарный и не хватает монет - предлагаем докупить монеты за рубли.
                sk.legendary -> RunnerShop.offerCoins(ctx, RunnerSkins.priceNow(ctx, i) - Coins.balance(ctx), ctx.getString(sk.name))
                else -> Toast.makeText(ctx, ctx.getString(R.string.runner3_no_coins, RunnerSkins.priceNow(ctx, i) - Coins.balance(ctx)), Toast.LENGTH_SHORT).show()
            }
            val eq = RunnerSkins.equipped(ctx)
            game {
                world.skin = eq
                world.trail = RunnerSkins.all[eq].trail
            }
            wallet.text = Coins.balance(ctx).toString()
            showSkin()
        }
        row.addView(skinAction, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        row.addView(Btn.nice(ctx, "▶", arrows) {
            skinIdx = (skinIdx + 1) % RunnerSkins.all.size
            showSkin()
        }, LinearLayout.LayoutParams(dp(56), dp(50)))
        skins.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        skins.addView(Btn.nice(ctx, ctx.getString(R.string.runner3_back), intArrayOf(0xFFA78BFA.toInt(), 0xFF7C3AED.toInt())) {
            skinsOpen = false
            skins.visibility = View.GONE
            game {
                world.showcase = false
                world.onState()
            }
        }, LinearLayout.LayoutParams(dp(200), dp(46)).apply { topMargin = dp(10) })

        fun openSkins() {
            skinsOpen = true
            skinIdx = RunnerSkins.equipped(ctx)
            panel.visibility = View.GONE
            menuUi.visibility = View.GONE
            playPulse.cancel()
            skins.visibility = View.VISIBLE
            showSkin()
            game {
                if (world.state == World.OVER) world.toMenu()
                world.showcase = true
            }
        }

        // ---------------------------------------------------------------- магазин
        val shopGreen = intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())
        val shopViolet = intArrayOf(0xFFC084FC.toInt(), 0xFF9333EA.toInt())
        val shop = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF01E1B4B.toInt(), 0xF0111033.toInt())).apply {
                cornerRadii = floatArrayOf(26 * d, 26 * d, 26 * d, 26 * d, 0f, 0f, 0f, 0f)
                setStroke(dp(2), 0xFFFDE047.toInt())
            }
            visibility = View.GONE
            isClickable = true
        }
        root.addView(shop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        var shopOpen = false
        var shopTab = 0
        var boardIdx = RunnerBoards.equipped(ctx)
        val shopBody = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val tabs = arrayOf(TextView(ctx), TextView(ctx))
        lateinit var renderShop: () -> Unit
        tabs.forEachIndexed { i, tv ->
            tv.text = if (i == 0) ctx.getString(R.string.runner_shop_upgrades) else ctx.getString(R.string.runner_shop_boards)
            tv.textSize = 15f
            tv.gravity = Gravity.CENTER
            tv.setTypeface(Typeface.DEFAULT_BOLD)
            tv.setOnClickListener {
                shopTab = i
                renderShop()
            }
            tabRow.addView(tv, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        shop.addView(TextView(ctx).apply {
            setText(R.string.runner_shop_title)
            textSize = 18f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })
        shop.addView(tabRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Улучшений стало 8 - список прокручивается и не выше половины экрана.
        val shopScroll = object : android.widget.ScrollView(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = (resources.displayMetrics.heightPixels * 0.52f).toInt()
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            addView(shopBody)
        }
        shop.addView(shopScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        fun levelBar(level: Int): String = "●".repeat(level) + "○".repeat(RunnerUpgrades.MAX - level)

        renderShop = {
            tabs.forEachIndexed { i, tv ->
                val on = i == shopTab
                tv.setTextColor(if (on) Color.WHITE else 0xFFC7D2FE.toInt())
                tv.background = GradientDrawable().apply {
                    cornerRadius = 20 * d
                    if (on) setColor(0xFF6D28D9.toInt()) else setStroke(dp(1), 0x66C7D2FE)
                }
            }
            shopBody.removeAllViews()
            wallet.text = Coins.balance(ctx).toString()
            if (shopTab == 0) {
                game { world.previewBoard = -1 }
                for (up in RunnerUpgrades.all) {
                    val lvl = RunnerUpgrades.level(ctx, up.power)
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(8), dp(8))
                        background = GradientDrawable().apply {
                            cornerRadius = 16 * d
                            setColor(0x33FFFFFF)
                        }
                    }
                    row.addView(TextView(ctx).apply {
                        text = up.emoji
                        textSize = 26f
                    })
                    row.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), 0, dp(6), 0)
                        addView(TextView(ctx).apply {
                            text = up.title
                            textSize = 15f
                            setTextColor(Color.WHITE)
                            setTypeface(Typeface.DEFAULT_BOLD)
                        })
                        addView(TextView(ctx).apply {
                            text = levelBar(lvl) + "  " + ctx.getString(R.string.runner_shop_secs, RunnerUpgrades.time(ctx, up.power).let { String.format(java.util.Locale.US, "%.1f", it) })
                            textSize = 13f
                            setTextColor(0xFFFDE047.toInt())
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val price = RunnerUpgrades.price(ctx, up.power)
                    row.addView(Btn.nice(ctx, if (price == null) "MAX" else "🪙 $price", if (price == null) shopViolet else shopGreen) {
                        if (price == null) return@nice
                        if (RunnerUpgrades.buy(ctx, up.power)) {
                            GameSfx.play(ctx, GameSfx.S.BONUS)
                            coin.pulse()
                            Toast.makeText(ctx, ctx.getString(R.string.runner_shop_upgraded, up.title, RunnerUpgrades.time(ctx, up.power).let { String.format(java.util.Locale.US, "%.1f", it) }), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, ctx.getString(R.string.runner3_no_coins, price - Coins.balance(ctx)), Toast.LENGTH_SHORT).show()
                        }
                        renderShop()
                    }, LinearLayout.LayoutParams(dp(96), dp(44)))
                    shopBody.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                }
            } else {
                val bs = RunnerBoards.all[boardIdx]
                val owned = RunnerBoards.isOwned(ctx, boardIdx)
                val eq = RunnerBoards.equipped(ctx) == boardIdx
                val i = boardIdx
                game {
                    world.previewBoard = i
                    world.showcase = true
                }
                shopBody.addView(TextView(ctx).apply {
                    text = bs.title
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    setTypeface(Typeface.DEFAULT_BOLD)
                    gravity = Gravity.CENTER
                })
                shopBody.addView(TextView(ctx).apply {
                    val fx = when (bs.fx) {
                        1 -> ctx.getString(R.string.runner_fx_stars)
                        2 -> ctx.getString(R.string.runner_fx_fire)
                        3 -> ctx.getString(R.string.runner_fx_frost)
                        4 -> ctx.getString(R.string.runner_fx_neon)
                        else -> ""
                    }
                    val wheels = if (bs.wheelGlow) ctx.getString(R.string.runner_wheels_glow) else ctx.getString(R.string.runner_wheels)
                    text = "${boardIdx + 1} / ${RunnerBoards.all.size}  ·  $wheels" + if (fx.isEmpty()) "" else "  ·  $fx"
                    textSize = 13.5f
                    setTextColor(0xFFFDE047.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, dp(8))
                })
                val r = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                val arrows = intArrayOf(0xFF60A5FA.toInt(), 0xFF2563EB.toInt())
                r.addView(Btn.nice(ctx, "◀", arrows) {
                    boardIdx = (boardIdx - 1 + RunnerBoards.all.size) % RunnerBoards.all.size
                    renderShop()
                }, LinearLayout.LayoutParams(dp(56), dp(50)))
                r.addView(Btn.nice(ctx, when {
                    eq -> ctx.getString(R.string.runner3_equipped)
                    owned -> ctx.getString(R.string.runner3_equip)
                    else -> ctx.getString(R.string.runner3_buy, bs.price)
                }, shopGreen) {
                    when {
                        eq -> Unit
                        owned -> RunnerBoards.equip(ctx, i)
                        RunnerBoards.buy(ctx, i) -> {
                            GameSfx.play(ctx, GameSfx.S.WIN)
                            coin.pulse()
                            Toast.makeText(ctx, ctx.getString(R.string.runner3_bought, bs.title), Toast.LENGTH_SHORT).show()
                        }
                        else -> Toast.makeText(ctx, ctx.getString(R.string.runner3_no_coins, bs.price - Coins.balance(ctx)), Toast.LENGTH_SHORT).show()
                    }
                    renderShop()
                }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
                r.addView(Btn.nice(ctx, "▶", arrows) {
                    boardIdx = (boardIdx + 1) % RunnerBoards.all.size
                    renderShop()
                }, LinearLayout.LayoutParams(dp(56), dp(50)))
                shopBody.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        shop.addView(Btn.nice(ctx, ctx.getString(R.string.runner3_back), intArrayOf(0xFFA78BFA.toInt(), 0xFF7C3AED.toInt())) {
            shopOpen = false
            shop.visibility = View.GONE
            game {
                world.previewBoard = -1
                world.showcase = false
                world.onState()
            }
        }, LinearLayout.LayoutParams(dp(200), dp(46)).apply { topMargin = dp(10) })

        fun openShop() {
            shopOpen = true
            boardIdx = RunnerBoards.equipped(ctx)
            panel.visibility = View.GONE
            menuUi.visibility = View.GONE
            playPulse.cancel()
            shop.visibility = View.VISIBLE
            renderShop()
            game {
                if (world.state == World.OVER) world.toMenu()
                world.previewSkin = world.skin
                world.showcase = shopTab == 1
            }
        }

        // ---------------------------------------------------------------- состояние
        var savedBestFor = -1
        var newBest = false
        val green = intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())
        val violet = intArrayOf(0xFFC084FC.toInt(), 0xFF9333EA.toInt())
        val orange = intArrayOf(0xFFFB923C.toInt(), 0xFFEA580C.toInt())
        val pink = intArrayOf(0xFFF472B6.toInt(), 0xFFDB2777.toInt())

        fun tapJump() = prefs(ctx).getBoolean("tap_jump", false)

        /** Новый забег с улучшениями и доской из магазина. */
        fun startRun() {
            val b = best(ctx)
            val times = RunnerUpgrades.times(ctx)
            val bi = RunnerBoards.equipped(ctx)
            game {
                times.copyInto(world.powerTime)
                world.board = bi
                world.boardFx = RunnerBoards.all[bi].fx
                world.newRun(b)
            }
        }

        fun seasonShort(): String = ctx.getString(
            when (RunnerSeason.mode(ctx)) {
                RunnerSeason.WINTER -> R.string.runner_m_winter
                RunnerSeason.SPRING -> R.string.runner_m_spring
                RunnerSeason.SUMMER -> R.string.runner_m_summer
                RunnerSeason.AUTUMN -> R.string.runner_m_autumn
                RunnerSeason.MODE_OFF -> R.string.runner_m_off
                else -> R.string.runner_m_auto
            },
        )

        fun fillMenu() {
            panel.visibility = View.GONE
            menuTitle.text = when (world.season) {
                RunnerSeason.WINTER -> ctx.getString(R.string.runner_winter_title)
                RunnerSeason.SPRING -> ctx.getString(R.string.runner_spring_title)
                RunnerSeason.SUMMER -> ctx.getString(R.string.runner_summer_title)
                RunnerSeason.AUTUMN -> ctx.getString(R.string.runner_autumn_title)
                else -> ctx.getString(R.string.runner_title)
            }
            val chipCols = when (world.season) {
                RunnerSeason.WINTER -> intArrayOf(0xFF38BDF8.toInt(), 0xFF2563EB.toInt())
                RunnerSeason.SPRING -> intArrayOf(0xFFF472B6.toInt(), 0xFFDB2777.toInt())
                RunnerSeason.SUMMER -> intArrayOf(0xFF2DD4BF.toInt(), 0xFF0D9488.toInt())
                RunnerSeason.AUTUMN -> intArrayOf(0xFFFB923C.toInt(), 0xFFEA580C.toInt())
                else -> intArrayOf(0xFF818CF8.toInt(), 0xFF4F46E5.toInt())
            }
            menuChip.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, chipCols).apply { cornerRadius = 14 * d }
            menuChip.text = when (world.season) {
                RunnerSeason.WINTER -> ctx.getString(R.string.runner_winter_intro)
                RunnerSeason.SPRING -> ctx.getString(R.string.runner_spring_intro)
                RunnerSeason.SUMMER -> ctx.getString(R.string.runner_summer_intro)
                RunnerSeason.AUTUMN -> ctx.getString(R.string.runner_autumn_intro)
                else -> ctx.getString(R.string.runner_m_classic)
            }
            menuHint.text = ctx.getString(R.string.runner_m_hint)
            menuBest.text = ctx.getString(R.string.runner_m_best, best(ctx))
            playBtn.text = ctx.getString(R.string.runner_m_play)
            playBtn.setOnClickListener {
                playPulse.cancel()
                startRun()
            }
            tiles.removeAllViews()
            val on = ctx.getString(R.string.runner_m_on)
            val off = ctx.getString(R.string.runner_m_off_state)
            val list = listOf(
                menuTile("👕", ctx.getString(R.string.runner_m_skins), null, 0xFFFB923C.toInt()) { openSkins() },
                menuTile("🛒", ctx.getString(R.string.runner_m_shop), null, 0xFFF472B6.toInt()) { openShop() },
                menuTile(
                    when (world.season) {
                        RunnerSeason.WINTER -> "❄"
                        RunnerSeason.SPRING -> "🌸"
                        RunnerSeason.SUMMER -> "☀"
                        RunnerSeason.AUTUMN -> "🍂"
                        else -> "🗓"
                    },
                    ctx.getString(R.string.runner_m_season), seasonShort(), 0xFF7DD3FC.toInt(),
                ) {
                    RunnerSeason.next(ctx)
                    val sn = RunnerSeason.current(ctx)
                    applySeasonMusic(sn)
                    game { world.season = sn }
                    world.season = sn
                    refreshAll[0]()
                },
                // Музыка события - только зимой (у других сезонов её нет).
                if (world.season == RunnerSeason.WINTER) menuTile("🎶", ctx.getString(R.string.runner_m_music), if (RunnerSeason.musicOn(ctx)) on else off, 0xFFF9A8D4.toInt()) {
                    RunnerSeason.setMusicOn(ctx, !RunnerSeason.musicOn(ctx))
                    applySeasonMusic(RunnerSeason.current(ctx))
                    refreshAll[0]()
                } else null,
                menuTile("👆", ctx.getString(R.string.runner_m_tap), if (tapJump()) on else off, 0xFFC084FC.toInt()) {
                    prefs(ctx).edit().putBoolean("tap_jump", !tapJump()).apply()
                    refreshAll[0]()
                },
            )
            for ((i, v) in list.filterNotNull().withIndex()) {
                tiles.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(6)
                })
            }
            if (menuUi.visibility != View.VISIBLE) {
                menuUi.visibility = View.VISIBLE
                if (com.stoyko.smessenger.ui.Kinetics.enabled) {
                    menuTop.alpha = 0f
                    menuTop.translationY = -30 * d
                    menuTop.animate().alpha(1f).translationY(0f).setDuration(380).start()
                    menuBottom.alpha = 0f
                    menuBottom.translationY = 50 * d
                    menuBottom.animate().alpha(1f).translationY(0f).setDuration(420).start()
                }
            }
            if (com.stoyko.smessenger.ui.Kinetics.enabled && !playPulse.isStarted) playPulse.start()
        }

        fun refresh() {
            wallet.text = Coins.balance(ctx).toString()
            if (world.state != World.MENU || skinsOpen || shopOpen) {
                menuUi.visibility = View.GONE
                playPulse.cancel()
                playBtn.scaleX = 1f
                playBtn.scaleY = 1f
            }
            val st = world.state
            pauseBtn.visibility = if (st == World.RUN || st == World.COUNT) View.VISIBLE else View.INVISIBLE
            syncMusic()
            hud.invalidate()
            if (skinsOpen || shopOpen) {
                panel.visibility = View.GONE
                return
            }
            when (st) {
                World.RUN, World.COUNT, World.DYING -> panel.visibility = View.GONE
                World.PAUSE -> fillPanel(
                    ctx.getString(R.string.runner_paused),
                    ctx.getString(R.string.runner_now, world.score, world.coins),
                    listOf(
                        Triple(ctx.getString(R.string.runner_continue), green) { game { world.resume() } },
                        Triple(ctx.getString(R.string.runner3_menu), violet) { game { world.toMenu() } },
                    ),
                )
                World.OVER -> {
                    val runId = world.runNo * 2 + (if (world.continued) 1 else 0)
                    if (savedBestFor != runId) {
                        savedBestFor = runId
                        newBest = world.score > best(ctx)
                        if (newBest) prefs(ctx).edit().putInt("best", world.score).apply()
                        runCatching { com.stoyko.smessenger.games.stats.GameStats.runnerOver(ctx, world.dist.toInt(), continued = world.continued) }
                        GameSfx.play(ctx, if (newBest) GameSfx.S.WIN else GameSfx.S.LOSE)
                    }
                    val title = ctx.getString(if (world.deathKind == 1) R.string.runner3_caught else R.string.runner3_crash)
                    var body = ctx.getString(R.string.runner3_result, world.score, world.coins, world.dist.toInt(), best(ctx))
                    if (newBest) body = ctx.getString(R.string.runner3_new_best) + "\n" + body
                    val buttons = ArrayList<Triple<String, IntArray, () -> Unit>>()
                    if (!world.continued) {
                        buttons.add(
                            Triple(ctx.getString(R.string.runner3_continue_for, World.CONTINUE_PRICE), pink) {
                                if (Coins.spend(ctx, World.CONTINUE_PRICE)) {
                                    wallet.text = Coins.balance(ctx).toString()
                                    game { world.continueRun() }
                                } else {
                                    Toast.makeText(ctx, ctx.getString(R.string.runner3_no_coins, World.CONTINUE_PRICE - Coins.balance(ctx)), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                    buttons.add(Triple(ctx.getString(R.string.runner_again), green) { startRun() })
                    buttons.add(Triple(ctx.getString(R.string.runner3_skins), orange) { openSkins() })
                    buttons.add(Triple(ctx.getString(R.string.runner_shop_btn), pink) { openShop() })
                    buttons.add(Triple(ctx.getString(R.string.runner3_menu), violet) { game { world.toMenu() } })
                    fillPanel(title, body, buttons)
                }
                else -> fillMenu()
            }
        }

        refreshAll[0] = { refresh() }

        // ---------------------------------------------------------------- события мира (поток игры)
        world.onState = { main.post { refresh() } }
        world.onCoin = {
            main.post {
                // 10 монет забега = 1 монета мессенджера. Остаток копится между забегами.
                val pr = prefs(ctx)
                val rem = pr.getInt("coin_rem", 0) + 1
                if (rem >= COINS_PER_WALLET) {
                    pr.edit().putInt("coin_rem", rem - COINS_PER_WALLET).apply()
                    Coins.add(ctx, 1)
                    coin.pulse()
                    wallet.text = Coins.balance(ctx).toString()
                } else {
                    pr.edit().putInt("coin_rem", rem).apply()
                }
            }
        }
        world.onSound = { id, rate ->
            when (id) {
                World.SND_COIN -> RunnerSfx.play(ctx, RunnerSfx.S.COIN, rate, 0.55f)
                World.SND_POWER -> GameSfx.play(ctx, GameSfx.S.BONUS)
                World.SND_TICK -> GameSfx.play(ctx, GameSfx.S.TICK, 1.3f, 0.7f)
                World.SND_GO -> GameSfx.play(ctx, GameSfx.S.BOOST, 1.1f, 0.7f)
                World.SND_CAUGHT -> GameSfx.play(ctx, GameSfx.S.DIE)
                World.SND_JUMP -> RunnerSfx.play(ctx, RunnerSfx.S.JUMP, rate)
                World.SND_ROLL -> RunnerSfx.play(ctx, RunnerSfx.S.ROLL)
                World.SND_SWITCH -> RunnerSfx.play(ctx, RunnerSfx.S.WHOOSH, 1f, 0.8f)
                World.SND_LAND -> RunnerSfx.play(ctx, RunnerSfx.S.LAND)
                World.SND_STUMBLE -> RunnerSfx.play(ctx, RunnerSfx.S.STUMBLE)
                World.SND_CRASH -> RunnerSfx.play(ctx, RunnerSfx.S.CRASH)
                World.SND_HORN -> RunnerSfx.play(ctx, RunnerSfx.S.HORN)
                World.SND_WHISTLE -> RunnerSfx.play(ctx, RunnerSfx.S.WHISTLE, rate)
                World.SND_BARK -> RunnerSfx.play(ctx, RunnerSfx.S.BARK)
                World.SND_JET -> RunnerSfx.play(ctx, RunnerSfx.S.JET)
                World.SND_SAVED -> RunnerSfx.play(ctx, RunnerSfx.S.SAVED)
                World.SND_PASS -> RunnerSfx.play(ctx, RunnerSfx.S.PASS)
            }
        }
        val powerNames = intArrayOf(R.string.runner3_p_magnet, R.string.runner3_p_jet, R.string.runner3_p_sneakers, R.string.runner3_p_x2, R.string.runner3_p_board, R.string.runner3_p_shield, R.string.runner3_p_coins, R.string.runner3_p_slow)
        world.onPop = { kind, arg ->
            main.post {
                when (kind) {
                    World.POP_SAVED -> hud.pop(ctx.getString(R.string.runner_saved), 0xFF7DD3FC.toInt())
                    World.POP_POWER -> hud.pop(ctx.getString(powerNames[arg.coerceIn(0, powerNames.size - 1)]), 0xFFFDE047.toInt())
                    World.POP_CHASER -> hud.pop(ctx.getString(R.string.runner3_chaser_warn), 0xFFFCA5A5.toInt())
                    World.POP_RECORD -> hud.pop(ctx.getString(R.string.runner3_new_best), 0xFF4ADE80.toInt())
                }
            }
        }
        gl.onFocusLost = { game { world.pause() } }

        // ---------------------------------------------------------------- свайпы
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var pointer = -1
        var swiped = false
        hud.setOnTouchListener { _, e ->
            val threshold = 22 * d
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Новый палец - новый свайп (можно быстро свайпать двумя пальцами).
                    val i = e.actionIndex
                    pointer = e.getPointerId(i)
                    downX = e.getX(i)
                    downY = e.getY(i)
                    downAt = SystemClock.uptimeMillis()
                    swiped = false
                }
                MotionEvent.ACTION_MOVE -> if (!swiped && pointer >= 0) {
                    val i = e.findPointerIndex(pointer)
                    if (i >= 0) {
                        val dx = e.getX(i) - downX
                        val dy = e.getY(i) - downY
                        if (maxOf(abs(dx), abs(dy)) > threshold) {
                            swiped = true
                            if (abs(dx) > abs(dy)) {
                                val dir = if (dx > 0) 1 else -1
                                game { world.move(dir) }
                            } else if (dy < 0) {
                                game { world.jump() }
                            } else {
                                game { world.roll() }
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!swiped && SystemClock.uptimeMillis() - downAt < 250L) {
                        val st = world.state
                        if (st == World.RUN && tapJump()) game { world.jump() }
                        if (st == World.PAUSE) game { world.resume() }
                    }
                    pointer = -1
                }
                MotionEvent.ACTION_CANCEL -> pointer = -1
            }
            true
        }

        refresh()
        root.fitSystemBars()
        dialog.setContentView(root)
        val saverKey = Any()
        com.stoyko.smessenger.ui.BackgroundSaver.register(saverKey) {
            game { world.pause() }
            RunnerMusic.pause()
        }
        dialog.setOnDismissListener {
            com.stoyko.smessenger.ui.BackgroundSaver.unregister(saverKey)
            world.onState = {}
            world.onCoin = {}
            world.onSound = { _, _ -> }
            world.onPop = { _, _ -> }
            gl.onPause()
            playPulse.cancel()
            RunnerMusic.release()
            com.stoyko.smessenger.ui.ThemeMusic.release("runner")
        }
        dialog.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK) {
                val st = world.state
                // «Назад» в магазине закрывает магазин, а не всю игру.
                val intercept = skinsOpen || shopOpen || st == World.RUN || st == World.COUNT
                if (!intercept) return@setOnKeyListener false
                if (ev.action == KeyEvent.ACTION_UP) {
                    if (shopOpen) {
                        shopOpen = false
                        shop.visibility = View.GONE
                        game {
                            world.previewBoard = -1
                            world.showcase = false
                            world.onState()
                        }
                    } else if (skinsOpen) {
                        skinsOpen = false
                        skins.visibility = View.GONE
                        game {
                            world.showcase = false
                            world.onState()
                        }
                    } else {
                        game { world.pause() }
                    }
                }
                return@setOnKeyListener true
            }
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> game { world.move(-1) }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> game { world.move(1) }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> game { world.jump() }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> game { world.roll() }
                KeyEvent.KEYCODE_SPACE -> {
                    val b = best(ctx)
                    game {
                        when (world.state) {
                            World.RUN, World.COUNT -> world.pause()
                            World.PAUSE -> world.resume()
                            World.MENU, World.OVER -> if (!world.showcase) world.newRun(b)
                        }
                    }
                }
                else -> return@setOnKeyListener false
            }
            true
        }
        dialog.show()
        if (!prefs(ctx).getBoolean("rules3_seen", false)) {
            prefs(ctx).edit().putBoolean("rules3_seen", true).apply()
            root.post { showRules(ctx) }
        }
    }

    fun showRules(ctx: Context) {
        com.stoyko.smessenger.ui.DialogTracker.add(
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.runner_rules_title)
                .setMessage(R.string.runner3_rules)
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        ).show()
    }
}
