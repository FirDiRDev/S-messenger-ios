// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.Coins
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.CoinView
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Мини-игра «Змейка» - монеты 🪙 на подарки.
 *
 *  - Монетка +1, золотая (появляется ненадолго) +3.
 *  - Уровни: каждые 8 собранных монет - новый уровень (+1 монета бонусом): змейка быстрее,
 *    с 3-го уровня на поле появляются стены-препятствия.
 *  - Прогресс сохраняется: вышли из игры, закрыли окно или свернули приложение - игра на паузе
 *    и продолжится с того же места (змейка, монеты, уровень, стены).
 *  - Управление: свайп по полю или стрелки; быстрые нажатия запоминаются по очереди (до двух),
 *    поэтому резкий разворот «вверх-влево» не теряется и змейка не врезается сама в себя.
 *  - Ежедневная награда: день 1 - 1 монета, день 2 - 2 … день 7 - 7, потом заново;
 *    пропустили день - серия начинается с 1.
 */
object SnakeGame {
    fun show(ctx: Context, onShop: (() -> Unit)? = null) {
        GameSfx.prepare(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = com.stoyko.smessenger.ui.DialogTracker.add(Dialog(ctx, R.style.AppTheme_FullDialog))
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // Светлый яркий фон без затемнения - поле и змейку хорошо видно.
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF6366F1.toInt(), 0xFF0EA5E9.toInt(), 0xFF22D3EE.toInt()))
        }
        // ---- шапка: назад, название, кошелёк с живой монетой ----
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(14), dp(2))
        }
        bar.addView(
            ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Color.WHITE)
                background = null
                setOnClickListener { dialog.safeDismiss() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        bar.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.snake_title)
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val coin = CoinView(ctx)
        bar.addView(coin, LinearLayout.LayoutParams(dp(30), dp(30)))
        val wallet = TextView(ctx).apply {
            textSize = 17f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(6), 0, 0, 0)
        }
        bar.addView(wallet)
        root.addView(bar)

        // ---- ежедневная награда ----
        val daily = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(0x33FFFFFF)
            }
        }
        val dailyTitle = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        val days = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val dailyBtn = Btn.nice(ctx, "", intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) {}
        daily.addView(dailyTitle)
        daily.addView(days)
        daily.addView(dailyBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        root.addView(daily, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })

        // Планшет (короткая сторона от 600 dp): поле на весь экран, крупные кнопки и джойстик,
        // в альбомной ориентации - управление по бокам поля, под большими пальцами.
        val tablet = ctx.resources.configuration.smallestScreenWidthDp >= 600
        val btnH = dp(if (tablet) 56 else 42)
        val score = TextView(ctx).apply {
            textSize = if (tablet) 18f else 14f
            setTextColor(Color.WHITE)
            setShadowLayer(3 * d, 0f, d, 0x66000000)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(score)
        val board = Board(ctx)
        val leftSlot = FrameLayout(ctx)
        val rightSlot = FrameLayout(ctx)
        val mid = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isMotionEventSplittingEnabled = true
        }
        mid.addView(leftSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mid.addView(board, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) })
        mid.addView(rightSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- управление: большой джойстик по центру, слева Старт/Пауза и ⚡, справа скины и магазин ----
        // Джойстик срабатывает сразу при касании и при ведении пальцем (можно не отрывать палец).
        val screenW = ctx.resources.displayMetrics.widthPixels / d
        val screenMin = minOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels) / d
        val joySize = if (tablet) {
            dp((screenMin * 0.34f).coerceIn(230f, 340f).toInt())
        } else {
            dp(screenW.coerceIn(300f, 900f).let { (it * 0.46f).coerceIn(150f, 230f) }.toInt())
        }
        // Кнопки Старт/Пауза, Скины, Поле и Магазин - отдельной полосой сверху, подальше от
        // джойстика: раньше большим пальцем задевали «Паузу» и «Скины» во время игры.
        var rebuildControls: () -> Unit = {}
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(4))
            isMotionEventSplittingEnabled = false
        }
        val start = Btn.nice(ctx, "", intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) { board.toggle() }
        strip.addView(start, LinearLayout.LayoutParams(0, btnH, 1.3f))
        strip.addView(Btn.nice(ctx, "🎨", intArrayOf(0xFFC084FC.toInt(), 0xFF9333EA.toInt())) {
            board.pauseAndSave()
            showSkins(ctx) { board.applySkin(skinOf(ctx)) }
        }, LinearLayout.LayoutParams(0, btnH, 0.6f).apply { marginStart = dp(8) })
        strip.addView(Btn.nice(ctx, "⚙", intArrayOf(0xFF94A3B8.toInt(), 0xFF475569.toInt())) {
            board.pauseAndSave()
            boardSettings(ctx) {
                board.invalidate()
                rebuildControls()
            }
        }, LinearLayout.LayoutParams(0, btnH, 0.6f).apply { marginStart = dp(8) })
        // ❓ Правила: цель, управление, все предметы на поле - бонусы, усиления и помехи.
        strip.addView(Btn.nice(ctx, "❓", intArrayOf(0xFF60A5FA.toInt(), 0xFF2563EB.toInt())) {
            board.pauseAndSave()
            showRules(ctx)
        }, LinearLayout.LayoutParams(0, btnH, 0.6f).apply { marginStart = dp(8) })
        if (onShop != null) {
            strip.addView(Btn.nice(ctx, "🛍", intArrayOf(0xFFF472B6.toInt(), 0xFFDB2777.toInt())) {
                dialog.safeDismiss()
                onShop()
            }, LinearLayout.LayoutParams(0, btnH, 0.6f).apply { marginStart = dp(8) })
        }
        root.addView(strip, root.indexOfChild(score))
        // Управление снизу: ⚡ слева, джойстик справа - под большие пальцы, далеко друг от друга.
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(12))
            isMotionEventSplittingEnabled = true
        }
        // ⚡ Ускорение: пока кнопка зажата - змейка вдвое быстрее.
        val boost = Btn.nice(ctx, "⚡", intArrayOf(0xFFFDE047.toInt(), 0xFFF59E0B.toInt())) {}
        boost.textSize = if (tablet) 34f else 26f
        boost.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    board.boost = true
                    GameSfx.play(ctx, GameSfx.S.BOOST, 1f, 0.7f)
                    v.scaleX = 0.92f
                    v.scaleY = 0.92f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    board.boost = false
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
            }
            true
        }
        val boostBox = FrameLayout(ctx)
        val boostSize = dp(if (tablet) 128 else 92)
        boostBox.addView(boost, FrameLayout.LayoutParams(boostSize, boostSize, Gravity.CENTER))
        // Вид управления (⚙): круглый джойстик, квадратная крестовина, четыре стрелки или жесты.
        var sideMode = false
        fun buildControls() {
            controls.removeAllViews()
            leftSlot.removeAllViews()
            rightSlot.removeAllViews()
            (boostBox.parent as? ViewGroup)?.removeView(boostBox)
            val style = controlStyle(ctx)
            if (sideMode) {
                // Планшет в альбомной ориентации: ⚡ слева от поля, джойстик справа.
                controls.visibility = View.GONE
                leftSlot.visibility = View.VISIBLE
                rightSlot.visibility = View.VISIBLE
                leftSlot.addView(boostBox, FrameLayout.LayoutParams(boostSize + dp(24), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                if (style != CTRL_GESTURES) {
                    rightSlot.addView(
                        Joystick(ctx, style) { dx, dy -> board.turn(dx, dy) },
                        FrameLayout.LayoutParams(joySize, joySize, Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) },
                    )
                }
                return
            }
            controls.visibility = View.VISIBLE
            leftSlot.visibility = View.GONE
            rightSlot.visibility = View.GONE
            if (style == CTRL_GESTURES) {
                controls.addView(boostBox, LinearLayout.LayoutParams(0, dp(100), 1f))
                controls.addView(TextView(ctx).apply {
                    setText(R.string.snake_ctrl_gestures_hint)
                    textSize = 13f
                    setTextColor(0xDDFFFFFF.toInt())
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(0, dp(100), 1.4f))
            } else {
                controls.addView(boostBox, LinearLayout.LayoutParams(0, joySize, 1f))
                controls.addView(
                    Joystick(ctx, style) { dx, dy -> board.turn(dx, dy) },
                    LinearLayout.LayoutParams(joySize, joySize).apply { marginStart = dp(16) },
                )
            }
        }
        buildControls()
        rebuildControls = { buildControls() }
        root.addView(controls)
        // Поворот планшета: управление переезжает вниз или по бокам поля.
        if (tablet) {
            root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val side = v.width > v.height * 1.15f
                if (side != sideMode) {
                    sideMode = side
                    v.post { buildControls() }
                }
            }
        }
        board.applySkin(skinOf(ctx))

        fun refreshDaily() {
            val next = Coins.dailyNextDay(ctx)
            val avail = Coins.dailyAvailable(ctx)
            dailyTitle.text = ctx.getString(R.string.daily_title, if (avail) (next - 1).coerceAtLeast(0) else next)
            days.removeAllViews()
            for (i in 1..7) {
                val done = if (avail) i < next else i <= next
                val today = avail && i == next
                days.addView(
                    TextView(ctx).apply {
                        text = "$i\n+$i"
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(if (done || today) 0xFF1E1B4B.toInt() else 0xAAFFFFFF.toInt())
                        background = GradientDrawable().apply {
                            cornerRadius = 10 * d
                            setColor(
                                when {
                                    today -> 0xFFFDE047.toInt()
                                    done -> 0xFF34D399.toInt()
                                    else -> 0x22FFFFFF
                                },
                            )
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(34), 1f).apply { setMargins(dp(2), 0, dp(2), 0) },
                )
            }
            dailyBtn.text = if (avail) ctx.getString(R.string.daily_claim, next) else ctx.getString(R.string.daily_tomorrow, if (next >= 7) 1 else next + 1)
            dailyBtn.alpha = if (avail) 1f else 0.6f
            // Награду уже забрали - полоску дней прячем: полю больше места.
            days.visibility = if (avail) View.VISIBLE else View.GONE
        }
        fun refresh() {
            wallet.text = Coins.balance(ctx).toString()
            score.text = ctx.getString(R.string.snake_score_level, board.level, board.collected, Coins.best(ctx), Coins.bestLevel(ctx))
            start.text = ctx.getString(
                when {
                    board.running -> R.string.snake_pause
                    board.over -> R.string.snake_again
                    board.started -> R.string.snake_continue
                    else -> R.string.snake_start
                },
            )
        }
        dailyBtn.setOnClickListener {
            val n = Coins.claimDaily(ctx)
            if (n > 0) {
                // «Дзынь!» и монета подпрыгивает.
                CoinSound.jackpot(n)
                coin.pulse()
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.daily_got, n), android.widget.Toast.LENGTH_SHORT).show()
                refreshDaily()
                refresh()
            }
        }
        board.onChange = { refresh() }
        board.onCoin = { n ->
            Coins.add(ctx, n)
            coin.pulse()
            refresh()
        }
        board.onOver = {
            runCatching { com.stoyko.smessenger.games.stats.GameStats.snakeOver(ctx, board.collected, board.level) }
            Coins.setBest(ctx, board.collected)
            Coins.setBestLevel(ctx, board.level)
            refresh()
        }
        // Продолжить сохранённую игру.
        board.restore(Coins.loadGame(ctx))
        board.saver = { json -> Coins.saveGame(ctx, json) }
        refreshDaily()
        refresh()
        root.fitSystemBars()
        dialog.setContentView(root)
        // Сворачивание / уход в фон / закрытие из «последних» - пауза и сохранение сразу.
        val saverKey = Any()
        com.stoyko.smessenger.ui.BackgroundSaver.register(saverKey) { board.pauseAndSave() }
        dialog.setOnDismissListener {
            com.stoyko.smessenger.ui.BackgroundSaver.unregister(saverKey)
            board.pauseAndSave()
        }
        // Настоящая клавиатура/геймпад: стрелки, WASD, пробел - пауза.
        dialog.setOnKeyListener { _, code, ev ->
            if (ev.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_W -> board.turn(0, -1)
                android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_S -> board.turn(0, 1)
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_A -> board.turn(-1, 0)
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_D -> board.turn(1, 0)
                android.view.KeyEvent.KEYCODE_SPACE, android.view.KeyEvent.KEYCODE_DPAD_CENTER -> board.toggle()
                else -> return@setOnKeyListener false
            }
            true
        }
        dialog.show()
        // Первый запуск - сразу правила.
        if (!prefs(ctx).getBoolean("snake_rules_seen", false)) {
            prefs(ctx).edit().putBoolean("snake_rules_seen", true).apply()
            root.post { showRules(ctx) }
        }
    }

    /** Правила «Змейки» и описание всех предметов на поле. */
    fun showRules(ctx: Context) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(10))
        }
        fun head(res: Int) = box.addView(TextView(ctx).apply {
            setText(res)
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(0xFF4F46E5.toInt())
            setPadding(0, dp(14), 0, dp(4))
        })
        fun para(res: Int) = box.addView(TextView(ctx).apply {
            setText(res)
            textSize = 14f
            setLineSpacing(0f, 1.15f)
        })
        fun item(icon: String, title: Int, text: Int, bg: Int) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(ctx).apply {
                this.text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bg)
                }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            val t = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            t.addView(TextView(ctx).apply {
                setText(title)
                textSize = 14.5f
                setTypeface(Typeface.DEFAULT_BOLD)
            })
            t.addView(TextView(ctx).apply {
                setText(text)
                textSize = 13f
                alpha = 0.85f
            })
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row)
        }
        head(R.string.snake_rules_goal_t)
        para(R.string.snake_rules_goal)
        head(R.string.snake_rules_ctrl_t)
        para(R.string.snake_rules_ctrl)
        head(R.string.snake_rules_coins_t)
        item("🪙", R.string.snake_obj_coin, R.string.snake_obj_coin_d, 0xFFF59E0B.toInt())
        item("✨", R.string.snake_obj_gold, R.string.snake_obj_gold_d, 0xFFEAB308.toInt())
        item("💎", R.string.snake_obj_diamond, R.string.snake_obj_diamond_d, 0xFF06B6D4.toInt())
        item("🎁", R.string.snake_obj_chest, R.string.snake_obj_chest_d, 0xFFB45309.toInt())
        head(R.string.snake_rules_boosts_t)
        item("💊", R.string.snake_bonus_pill, R.string.snake_obj_pill_d, 0xFFDB2777.toInt())
        item("🛡", R.string.snake_bonus_shield, R.string.snake_obj_shield_d, 0xFF4338CA.toInt())
        item("⏳", R.string.snake_bonus_slow, R.string.snake_obj_slow_d, 0xFF0284C7.toInt())
        item("×2", R.string.snake_bonus_x2, R.string.snake_obj_x2_d, 0xFFD97706.toInt())
        item("🧲", R.string.snake_bonus_magnet, R.string.snake_obj_magnet_d, 0xFF2563EB.toInt())
        item("⚡", R.string.snake_obj_boost, R.string.snake_obj_boost_d, 0xFFF59E0B.toInt())
        head(R.string.snake_rules_debuffs_t)
        item("☠", R.string.snake_debuff_poison, R.string.snake_obj_poison_d, 0xFF7F1D1D.toInt())
        item("🌀", R.string.snake_debuff_confuse, R.string.snake_obj_confuse_d, 0xFF1C1917.toInt())
        head(R.string.snake_rules_walls_t)
        item("🪨", R.string.snake_obj_walls, R.string.snake_obj_walls_d, 0xFF57534E.toInt())
        head(R.string.snake_rules_timer_t)
        para(R.string.snake_rules_timer)
        com.stoyko.smessenger.ui.DialogTracker.add(
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.snake_rules_title)
                .setView(android.widget.ScrollView(ctx).apply { addView(box) })
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        ).show()
    }

    /** Игровое поле. */
    @SuppressLint("ViewConstructor")
    private class Board(ctx: Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        // Поле больше: 18 клеток в ширину, высота - по экрану (до 34 клеток).
        private var cols = 18
        private var rows = 24
        private val body = ArrayDeque<Pair<Int, Int>>()
        private val walls = LinkedHashSet<Pair<Int, Int>>()
        private val turns = ArrayDeque<Pair<Int, Int>>()

        // Плавность: где была змейка до последнего шага и когда был шаг - между шагами
        // она «скользит» из старых клеток в новые, а не прыгает по клеткам.
        private var prevBody: List<Pair<Int, Int>> = emptyList()
        private var lastStepAt = 0L
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private var dir = 1 to 0
        private var food = 5 to 5
        private var gold: Pair<Int, Int>? = null
        private var goldLeft = 0
        /**
         * Бонус: 0 - алмаз (+5), 1 - сундучок (+4…8), 2 - ⏳ замедление (35 шагов),
         * 3 - ×2 монеты (40 шагов), 4 - 🛡 щит (одно столкновение прощается).
         * Исчезает через 40 шагов.
         */
        private var bonus: Pair<Int, Int>? = null
        private var bonusKind = 0
        /** Сколько ещё бонус лежит на поле, мс игрового времени (на паузе не убывает). */
        private var bonusLeft = 0L

        // Действующие эффекты - в миллисекундах ИГРОВОГО времени: на паузе таймер стоит,
        // при замедлении/ускорении секунды остаются настоящими секундами (раньше счётчик
        // считал шаги змейки, и «секунды» шли то быстрее, то медленнее).
        private var slowMs = 0L
        private var doubleMs = 0L
        private var invulMs = 0L
        private var magnetMs = 0L
        private var fastMs = 0L
        private var confuseMs = 0L
        private var shield = false
        private var shieldPause = false
        private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val rnd = java.util.Random()
        private var steps = 0

        var running = false
            private set
        var over = false
            private set
        var started = false
            private set
        var collected = 0
            private set
        var level = 1
            private set

        var onChange: () -> Unit = {}
        var onCoin: (Int) -> Unit = {}
        var onOver: () -> Unit = {}
        var saver: (String?) -> Unit = {}

        private val bg = Paint()
        private val grid = Paint().apply {
            color = 0x10FFFFFF
            strokeWidth = 1f
        }
        private val snake = Paint(Paint.ANTI_ALIAS_FLAG)
        private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3730A3.toInt() }
        private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val pupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111827.toInt() }
        private val mouth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7F1D1D.toInt() }
        private val tongue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF43F5E.toInt() }
        private val sparkle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
        private var mouthOpen = 0f
        private var chompAt = 0L
        private val cellA = Paint()
        private val cellB = Paint()
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
        private var skin: Skin = SKINS[0]
        private val fx = SnakeSkinFx()

        fun applySkin(s: Skin) {
            skin = s
            head.color = s.head
            invalidate()
        }

        /** Цвет сегмента [i] из [n] по скину. */
        private fun segColor(i: Int, n: Int, t: Long): Int = skinColor(skin, i, n, t)
        private val wall = Paint(Paint.ANTI_ALIAS_FLAG)
        private val coin = Paint(Paint.ANTI_ALIAS_FLAG)
        private val coinText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7C4A03.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val rect = RectF()
        private var cell = 0f
        private var ox = 0f
        private var oy = 0f
        private var popAt = 0L
        private var popX = 0f
        private var popY = 0f
        private var popText = ""
        private var bannerAt = 0L
        private var bannerText = ""

        /** Ускорение: пока зажата кнопка ⚡ - вдвое быстрее. */
        var boost = false

        /** Скорость: шаг от 190 мс на 1-м уровне до 70 мс; с ускорением - вдвое быстрее. */
        private fun stepMs(): Long {
            val base = (190 - (level - 1) * 9 - body.size).coerceAtLeast(70)
            val v = if (boost) (base / 2).coerceAtLeast(40) else base
            // ⏳ Замедление - в полтора раза медленнее; ☠ яд - в полтора раза быстрее.
            var r = v.toLong()
            if (slowMs > 0) r = r * 3 / 2
            if (fastMs > 0) r = r * 2 / 3
            return r.coerceAtLeast(35)
        }

        // «Лопается»: при столкновении каждый сегмент разлетается искрами.
        private var deathAt = 0L
        private var shards: List<FloatArray> = emptyList()

        /**
         * Карта (поле) меняется с каждым уровнем - 8 светлых «шахматных» тем по кругу, без
         * затемнения: трава, небо, песок, сирень, персик, мята, розовая, лёд.
         */
        private val themes = arrayOf(
            intArrayOf(0xFFAAD751.toInt(), 0xFFA2D149.toInt(), 0xFF578A34.toInt()),
            intArrayOf(0xFFBAE6FD.toInt(), 0xFFA5DBF8.toInt(), 0xFF0284C7.toInt()),
            intArrayOf(0xFFFDE68A.toInt(), 0xFFFCD975.toInt(), 0xFFB45309.toInt()),
            intArrayOf(0xFFDDD6FE.toInt(), 0xFFD0C6FD.toInt(), 0xFF7C3AED.toInt()),
            intArrayOf(0xFFFED7AA.toInt(), 0xFFFDCB94.toInt(), 0xFFEA580C.toInt()),
            intArrayOf(0xFFA7F3D0.toInt(), 0xFF96EEC4.toInt(), 0xFF059669.toInt()),
            intArrayOf(0xFFFBCFE8.toInt(), 0xFFF9C0DF.toInt(), 0xFFDB2777.toInt()),
            intArrayOf(0xFFE0F2FE.toInt(), 0xFFCFEAFD.toInt(), 0xFF0369A1.toInt()),
        )

        /** Тёмные карты (Настройки змейки → «Тёмное поле»): ночные цвета с неоновой рамкой. */
        private val darkThemes = arrayOf(
            intArrayOf(0xFF1E293B.toInt(), 0xFF172033.toInt(), 0xFF22D3EE.toInt()),
            intArrayOf(0xFF1F2937.toInt(), 0xFF18212D.toInt(), 0xFF4ADE80.toInt()),
            intArrayOf(0xFF2E1065.toInt(), 0xFF270D58.toInt(), 0xFFC084FC.toInt()),
            intArrayOf(0xFF3B0A1F.toInt(), 0xFF320818.toInt(), 0xFFF472B6.toInt()),
            intArrayOf(0xFF0C2A28.toInt(), 0xFF09221F.toInt(), 0xFF2DD4BF.toInt()),
            intArrayOf(0xFF2A1A05.toInt(), 0xFF231604.toInt(), 0xFFFBBF24.toInt()),
            intArrayOf(0xFF0B1E3F.toInt(), 0xFF091933.toInt(), 0xFF60A5FA.toInt()),
            intArrayOf(0xFF262626.toInt(), 0xFF1F1F1F.toInt(), 0xFFF97316.toInt()),
        )
        private val clipPath = android.graphics.Path()
        private var bgCache: android.graphics.Bitmap? = null
        private var bgKey = ""
        private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FB7185 }
        private val smile = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = 0xFF3F1D1D.toInt()
        }
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                prevBody = body.toList()
                val now = SystemClock.uptimeMillis()
                // Сколько игрового времени прошло с прошлого шага (не больше 1 с - после паузы).
                val dt = if (lastStepAt > 0) (now - lastStepAt).coerceIn(0, 1000) else 0L
                lastStepAt = now
                tickEffects(dt)
                step()
                invalidate()
                if (running) postDelayed(this, if (shieldPause) 650L else stepMs())
                shieldPause = false
            }
        }

        init {
            reset()
        }

        private fun reset() {
            prevBody = emptyList()
            shards = emptyList()
            deathAt = 0L
            body.clear()
            walls.clear()
            turns.clear()
            for (i in 0 until 4) body.addLast((cols / 2 - i) to rows / 2)
            dir = 1 to 0
            collected = 0
            level = 1
            over = false
            started = false
            gold = null
            bonus = null
            clearEffects()
            placeFood()
        }

        /** Кнопка: Старт / Пауза / Продолжить / Ещё раз. */
        fun toggle() {
            if (running) {
                pauseAndSave()
                return
            }
            if (over) reset()
            GameSfx.prepare(context)
            GameSfx.play(context, GameSfx.S.TICK, 1.4f, 0.6f)
            started = true
            running = true
            // Таймеры эффектов идут только во время игры: пауза не «съедает» секунды.
            lastStepAt = SystemClock.uptimeMillis()
            removeCallbacks(tick)
            postDelayed(tick, stepMs())
            onChange()
            invalidate()
        }

        fun pauseAndSave() {
            if (running) {
                running = false
                removeCallbacks(tick)
                onChange()
                invalidate()
            }
            save()
        }

        fun turn(dx0: Int, dy0: Int) {
            // 🌀 Путаница: управление наоборот.
            val dx = if (confuseMs > 0) -dx0 else dx0
            val dy = if (confuseMs > 0) -dy0 else dy0
            val last = turns.lastOrNull() ?: dir
            // Тот же ход или разворот на себя - не принимаем.
            if ((dx to dy) == last || (dx == -last.first && dy == -last.second)) {
                if (!running && !over) toggle()
                return
            }
            // Очередь из двух ходов; если полна - заменяем последний (нажатие не теряется).
            if (turns.size < 2) turns.addLast(dx to dy) else turns[turns.size - 1] = dx to dy
            if (!running && !over) toggle()
        }

        private fun free(): Pair<Int, Int>? {
            val all = ArrayList<Pair<Int, Int>>()
            for (x in 0 until cols) for (y in 0 until rows) {
                val p = x to y
                if (p !in body && p != food && p != gold && p != bonus && p !in walls) all += p
            }
            return if (all.isEmpty()) null else all[rnd.nextInt(all.size)]
        }

        private fun placeFood() {
            food = free() ?: food
        }

        /** Стены нового уровня - не на пути головы (3 клетки вперёд свободны). */
        private fun addWalls(n: Int) {
            val h = body.first()
            repeat(n) {
                for (attempt in 0 until 30) {
                    val p = free() ?: return
                    val near = abs(p.first - h.first) + abs(p.second - h.second) < 4
                    if (!near) {
                        walls += p
                        break
                    }
                }
            }
        }

        private fun clearEffects() {
            slowMs = 0
            doubleMs = 0
            invulMs = 0
            magnetMs = 0
            fastMs = 0
            confuseMs = 0
            shield = false
        }

        /** Таймеры эффектов и бонуса на поле - по реально прошедшему игровому времени. */
        private fun tickEffects(dt: Long) {
            if (dt <= 0) return
            val hadInvul = invulMs > 0
            slowMs = (slowMs - dt).coerceAtLeast(0)
            doubleMs = (doubleMs - dt).coerceAtLeast(0)
            invulMs = (invulMs - dt).coerceAtLeast(0)
            magnetMs = (magnetMs - dt).coerceAtLeast(0)
            fastMs = (fastMs - dt).coerceAtLeast(0)
            confuseMs = (confuseMs - dt).coerceAtLeast(0)
            if (bonus != null) {
                bonusLeft -= dt
                if (bonusLeft <= 0) bonus = null
            }
            // Неуязвимость кончилась, а голова стоит в стене - стену убираем (иначе смерть «ни за что»).
            if (hadInvul && invulMs == 0L) {
                walls.remove(body.first())
                bannerAt = SystemClock.uptimeMillis()
                bannerText = context.getString(R.string.snake_invul_over)
            }
        }

        private fun step() {
            turns.removeFirstOrNull()?.let { dir = it }
            val h = body.first()
            var nh = (h.first + dir.first) to (h.second + dir.second)
            // 💊 Неуязвимость: края поля «насквозь» - змейка выходит с другой стороны.
            if (invulMs > 0) nh = ((nh.first + cols) % cols) to ((nh.second + rows) % rows)
            // 🧲 Магнит: монетка рядом (до 3 клеток) сама подтягивается к голове.
            if (magnetMs > 0) {
                val dxm = h.first - food.first
                val dym = h.second - food.second
                if (abs(dxm) + abs(dym) in 2..4) {
                    val step1 = if (abs(dxm) >= abs(dym)) (food.first + Integer.signum(dxm)) to food.second else food.first to (food.second + Integer.signum(dym))
                    if (step1 !in body && step1 !in walls && step1 != gold && step1 != bonus) food = step1
                }
            }
            val eats = nh == food || nh == gold || nh == bonus
            // Если змейка не растёт, хвост на этом шаге уйдёт - в его клетку можно.
            val danger = if (eats) body.toList() else body.toList().dropLast(1)
            val hit = nh.first !in 0 until cols || nh.second !in 0 until rows || nh in danger || nh in walls
            // 💊 Неуязвимость: сквозь стены и через себя - без столкновений.
            if (hit && invulMs > 0 && nh.first in 0 until cols && nh.second in 0 until rows) {
                // Проходим насквозь.
            } else if (hit) {
                // 🛡 Щит: столкновение прощается - змейка замирает на полсекунды, можно свернуть.
                if (shield) {
                    shield = false
                    bannerAt = SystemClock.uptimeMillis()
                    bannerText = context.getString(R.string.snake_shield_saved)
                    GameSfx.play(context, GameSfx.S.SPECIAL)
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    shieldPause = true
                    return
                }
                running = false
                over = true
                removeCallbacks(tick)
                // Змейка лопается: сегменты разлетаются искрами в разные стороны.
                deathAt = SystemClock.uptimeMillis()
                shards = body.toList().flatMapIndexed { i, p ->
                    val col = if (i == 0) skin.head else segColor(i, body.size, SystemClock.uptimeMillis())
                    List(5) {
                        val a = rnd.nextFloat() * 6.283f
                        val v = 1.5f + rnd.nextFloat() * 4f
                        floatArrayOf(p.first + 0.5f, p.second + 0.5f, kotlin.math.cos(a) * v, kotlin.math.sin(a) * v - 2f, col.toFloat(), 0.15f + rnd.nextFloat() * 0.25f)
                    }
                }
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                GameSfx.play(context, GameSfx.S.DIE)
                saver(null)
                onOver()
                onChange()
                return
            }
            body.addFirst(nh)
            when (nh) {
                food -> {
                    collect(1, nh)
                    GameSfx.play(context, GameSfx.S.EAT, 0.95f + (collected % 5) * 0.05f)
                    placeFood()
                    if (gold == null && rnd.nextInt(5) == 0) {
                        gold = free()
                        goldLeft = 45
                    }
                }
                gold -> {
                    collect(3, nh)
                    GameSfx.play(context, GameSfx.S.BONUS, 1.15f)
                    gold = null
                }
                bonus -> {
                    when (bonusKind) {
                        B_DIAMOND -> collect(5, nh)
                        B_CHEST -> collect(4 + rnd.nextInt(5), nh)
                        B_SLOW -> power(R.string.snake_bonus_slow, nh).also { slowMs = 8_000; fastMs = 0 }
                        B_X2 -> power(R.string.snake_bonus_x2, nh).also { doubleMs = 10_000 }
                        B_SHIELD -> power(R.string.snake_bonus_shield, nh).also { shield = true }
                        B_PILL -> power(R.string.snake_bonus_pill, nh).also { invulMs = 6_000 }
                        B_MAGNET -> power(R.string.snake_bonus_magnet, nh).also { magnetMs = 10_000 }
                        B_POISON -> power(R.string.snake_debuff_poison, nh).also { fastMs = 6_000; slowMs = 0 }
                        else -> power(R.string.snake_debuff_confuse, nh).also { confuseMs = 5_000; turns.clear() }
                    }
                    if (bonusKind == B_POISON || bonusKind == B_CONFUSE) {
                        GameSfx.play(context, GameSfx.S.BAD)
                    } else {
                        GameSfx.play(context, GameSfx.S.BONUS)
                    }
                    if (bonusKind <= B_CHEST) postDelayed({ CoinSound.play() }, 250)
                    bonus = null
                }
                else -> body.removeLast()
            }
            if (gold != null && --goldLeft <= 0) gold = null
            // Иногда появляется бонус или помеха (на поле - 9 секунд, мигает перед исчезновением).
            if (bonus == null && nh != food && rnd.nextInt(60) == 0) {
                bonus = free()
                bonusKind = pickKind()
                bonusLeft = 9_000
            }
            // Подстраховка: раз в 15 шагов сохраняем игру (если приложение закроют внезапно).
            if (++steps % 15 == 0) save()
        }

        /** Вес выпадения: монетные бонусы чаще, сильные усиления и помехи - реже; помехи - с 2-го уровня. */
        private fun pickKind(): Int {
            val weights = intArrayOf(5, 3, 2, 2, 1, 1, 2, if (level >= 2) 2 else 0, if (level >= 3) 1 else 0)
            var r = rnd.nextInt(weights.sum())
            for (i in weights.indices) {
                if (r < weights[i]) return i
                r -= weights[i]
            }
            return B_DIAMOND
        }

        /** Подобрали усиление (без монет): надпись над клеткой. */
        private fun power(text: Int, at: Pair<Int, Int>) {
            chompAt = SystemClock.uptimeMillis()
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            popAt = SystemClock.uptimeMillis()
            popX = ox + (at.first + 0.5f) * cell
            popY = oy + (at.second + 0.5f) * cell
            popText = context.getString(text)
        }

        private fun collect(n0: Int, at: Pair<Int, Int>) {
            // ×2: монеты удваиваются, пока действует бонус.
            val n = if (doubleMs > 0) n0 * 2 else n0
            chompAt = SystemClock.uptimeMillis()
            collected += n
            onCoin(n)
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            popAt = SystemClock.uptimeMillis()
            popX = ox + (at.first + 0.5f) * cell
            popY = oy + (at.second + 0.5f) * cell
            popText = "+$n"
            // Новый уровень - каждые 8 монет: быстрее, стены, +1 монета бонусом.
            val newLevel = (1 + collected / 6).coerceAtMost(50)
            if (newLevel > level) {
                level = newLevel
                if (level >= 3) addWalls(2)
                onCoin(1)
                bannerAt = SystemClock.uptimeMillis()
                bannerText = context.getString(R.string.snake_level_up, level)
                postDelayed({ GameSfx.play(context, GameSfx.S.LEVEL) }, 120)
            }
        }

        // ---------------- сохранение ----------------

        private fun save() {
            if (over || !started) {
                if (over) saver(null)
                return
            }
            val o = JSONObject()
            o.put("body", JSONArray().apply { body.forEach { put(JSONArray().put(it.first).put(it.second)) } })
            o.put("walls", JSONArray().apply { walls.forEach { put(JSONArray().put(it.first).put(it.second)) } })
            o.put("dir", JSONArray().put(dir.first).put(dir.second))
            o.put("food", JSONArray().put(food.first).put(food.second))
            o.put("collected", collected)
            o.put("level", level)
            // Эффекты продолжаются после паузы/выхода с тем же временем.
            o.put("fx", JSONObject().put("slow", slowMs).put("x2", doubleMs).put("inv", invulMs).put("mag", magnetMs)
                .put("fast", fastMs).put("conf", confuseMs).put("shield", shield))
            o.put("cols", cols)
            o.put("rows", rows)
            saver(o.toString())
        }

        fun restore(json: String?) {
            if (json.isNullOrBlank()) return
            runCatching {
                val o = JSONObject(json)
                fun pt(a: JSONArray) = a.getInt(0) to a.getInt(1)
                val b = o.getJSONArray("body")
                if (b.length() < 2) return
                cols = o.optInt("cols", 16).coerceIn(10, 40)
                rows = o.optInt("rows", 22).coerceIn(10, 60)
                body.clear()
                for (i in 0 until b.length()) body.addLast(pt(b.getJSONArray(i)))
                walls.clear()
                o.optJSONArray("walls")?.let { w -> for (i in 0 until w.length()) walls += pt(w.getJSONArray(i)) }
                dir = pt(o.getJSONArray("dir"))
                food = pt(o.getJSONArray("food"))
                collected = o.optInt("collected")
                level = o.optInt("level", 1).coerceAtLeast(1)
                clearEffects()
                o.optJSONObject("fx")?.let { f ->
                    slowMs = f.optLong("slow").coerceIn(0, 60_000)
                    doubleMs = f.optLong("x2").coerceIn(0, 60_000)
                    invulMs = f.optLong("inv").coerceIn(0, 60_000)
                    magnetMs = f.optLong("mag").coerceIn(0, 60_000)
                    fastMs = f.optLong("fast").coerceIn(0, 60_000)
                    confuseMs = f.optLong("conf").coerceIn(0, 60_000)
                    shield = f.optBoolean("shield")
                }
                started = true
                over = false
                bannerAt = SystemClock.uptimeMillis()
                bannerText = context.getString(R.string.snake_restored, level)
            }.onFailure { reset() }
        }

        // ---------------- рисование ----------------

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Новая игра: высота поля подстраивается под экран - змейке больше места.
            if (!started && w > 0) {
                if (resources.configuration.smallestScreenWidthDp >= 600) {
                    // Планшет: клетка ~44 dp, поле заполняет экран в любой ориентации.
                    val target = 44 * d
                    val c = (w / target).toInt().coerceIn(18, 40)
                    val r = (h / (w.toFloat() / c)).toInt().coerceIn(14, 40)
                    if (c != cols || r != rows) {
                        cols = c
                        rows = r
                        reset()
                    }
                } else {
                    val r = (h / (w.toFloat() / cols)).toInt().coerceIn(18, 34)
                    if (r != rows) {
                        rows = r
                        reset()
                    }
                }
            }
            cell = minOf(w.toFloat() / cols, h.toFloat() / rows)
            ox = (w - cell * cols) / 2
            oy = (h - cell * rows) / 2
            wall.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF78716C.toInt(), 0xFF44403C.toInt(), Shader.TileMode.CLAMP)
            frame.strokeWidth = 3 * d
            coinText.textSize = cell * 0.55f
            label.textSize = 18 * d
        }

        override fun onDraw(c: Canvas) {
            if (cell <= 0f) return
            val pal = if (SnakeGame.darkBoard(context)) darkThemes else themes
            val th = pal[(level - 1) % pal.size]
            cellA.color = th[0]
            cellB.color = th[1]
            frame.color = th[2]
            rect.set(ox, oy, ox + cell * cols, oy + cell * rows)
            // Оптимизация: поле «шахматкой» рисуется один раз в картинку и дальше просто
            // копируется (раньше - сотни прямоугольников в каждом кадре).
            val key = "${width}x$height:${th[0]}:${th[1]}:${th[2]}:$cols:$rows"
            var bmp = bgCache
            if (bmp == null || bgKey != key) {
                bmp?.recycle()
                bmp = android.graphics.Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                val bc = Canvas(bmp)
                bc.save()
                clipPath.reset()
                clipPath.addRoundRect(rect, 16 * d, 16 * d, android.graphics.Path.Direction.CW)
                bc.clipPath(clipPath)
                bc.drawRect(rect, cellA)
                // Клетки «шахматкой» - светлые, без тёмной заливки.
                for (x in 0 until cols) for (y in 0 until rows) {
                    if ((x + y) % 2 == 1) bc.drawRect(ox + x * cell, oy + y * cell, ox + (x + 1) * cell, oy + (y + 1) * cell, cellB)
                }
                bc.restore()
                bc.drawRoundRect(rect, 16 * d, 16 * d, frame)
                bgCache = bmp
                bgKey = key
            }
            c.drawBitmap(bmp, 0f, 0f, null)
            val t = SystemClock.uptimeMillis()
            // Препятствия: шипы и огонь (вместо серых квадратиков).
            for (w in walls) {
                when ((w.first * 7 + w.second * 13) % 5) {
                    0 -> drawSpikes(c, w)
                    1 -> drawFire(c, w, t)
                    2 -> drawRock(c, w)
                    3 -> drawIce(c, w, t)
                    else -> drawCactus(c, w)
                }
            }
            drawCoin(c, food, 0xFFFDE047.toInt(), 0xFFD97706.toInt(), 1f)
            gold?.let { g -> drawCoin(c, g, 0xFFFFF7AE.toInt(), 0xFFF59E0B.toInt(), 1f + 0.12f * kotlin.math.sin(t / 120f)) }
            bonus?.let { b -> drawBonus(c, b, bonusKind, t, bonusLeft) }
            val list = body.toList()
            // Доля пути до следующей клетки (0..1) - змейка движется плавно.
            val f = if (running && prevBody.isNotEmpty()) ((t - lastStepAt).toFloat() / stepMs()).coerceIn(0f, 1f) else 1f
            fun pos(i: Int): Pair<Float, Float> {
                val cur = list[i]
                val prev = prevBody.getOrNull(i) ?: prevBody.lastOrNull() ?: cur
                // Если клетки не соседние (после восстановления) - без скольжения.
                val near = abs(cur.first - prev.first) + abs(cur.second - prev.second) <= 1
                val k = if (near) f else 1f
                val x = prev.first + (cur.first - prev.first) * k
                val y = prev.second + (cur.second - prev.second) * k
                return (ox + (x + 0.5f) * cell) to (oy + (y + 0.5f) * cell)
            }
            val dAge = (t - deathAt) / 1000f
            if (over && deathAt > 0 && dAge < 1.2f) {
                // Искры лопнувшей змейки: летят, падают, гаснут.
                for (sh in shards) {
                    val x = ox + (sh[0] + sh[2] * dAge) * cell
                    val y = oy + (sh[1] + sh[3] * dAge + 4f * dAge * dAge) * cell
                    snake.color = sh[4].toInt()
                    snake.alpha = ((1f - dAge / 1.2f) * 255).toInt().coerceIn(0, 255)
                    c.drawCircle(x, y, cell * sh[5] * (1f - dAge / 1.4f), snake)
                }
                snake.alpha = 255
                postInvalidateOnAnimation()
            } else if (!over && list.isNotEmpty()) {
                val pts = list.indices.map { pos(it) }
                val fxLite = com.stoyko.smessenger.ui.EnergySaver.on || com.stoyko.smessenger.ui.LiteMode.on
                if (skin.fx != 0) {
                    fx.begin(pts.size)
                    for (j in pts.indices) fx.pt(j, pts[j].first, pts[j].second)
                    fx.under(c, skin, cell * 0.78f, t, fxLite)
                }
                // Тело - одна плавная линия от хвоста к голове с переливом цвета.
                for (i in pts.size - 1 downTo 1) {
                    val k = i.toFloat() / pts.size
                    bodyPaint.color = segColor(i, pts.size, t)
                    bodyPaint.strokeWidth = cell * (0.78f - 0.22f * k)
                    c.drawLine(pts[i].first, pts[i].second, pts[i - 1].first, pts[i - 1].second, bodyPaint)
                }
                // Искры на скинах с узором 3.
                if (skin.pattern == 3 || skin.pattern == 9) {
                    for (i in 2 until pts.size step 2) c.drawCircle(pts[i].first, pts[i].second, cell * 0.09f, sparkle)
                }
                // Эффект скина поверх тела (пламя, звёзды, чешуя…), голова - сверху.
                if (skin.fx != 0) fx.over(c, skin, cell * 0.78f, t, fxLite)
                val (hx, hy) = pts[0]
                val r = cell * 0.46f
                if (invulMs > 0) {
                    // 💊 Неуязвимость: радужное свечение вокруг головы, мигает в последние 1.5 с.
                    if (invulMs > 1500 || (t / 120) % 2 == 0L) {
                        sparkle.color = Color.HSVToColor(floatArrayOf((t / 4f) % 360f, 0.7f, 1f))
                        sparkle.alpha = 140
                        c.drawCircle(hx, hy, r * 1.55f, sparkle)
                        sparkle.color = 0xCCFFFFFF.toInt()
                    }
                }
                c.drawCircle(hx, hy, r, head)
                // Милое личико: большие глазки с бликами, румяные щёчки, улыбка;
                // перед монетой ротик делает «о», а при поедании - «ам».
                val target = gold?.let { g -> listOf(food, g) } ?: listOf(food)
                val headCell = list[0]
                val ahead = target.any { fp ->
                    val dx = fp.first - headCell.first
                    val dy = fp.second - headCell.second
                    val along = dx * dir.first + dy * dir.second
                    val side = abs(dx * dir.second - dy * dir.first)
                    along in 0..3 && side <= 1
                }
                mouthOpen += ((if (ahead) 1f else 0f) - mouthOpen) * 0.2f
                val chomp = ((t - chompAt) / 260f).coerceIn(0f, 1f)
                val chompK = if (chomp < 1f) kotlin.math.sin(chomp * Math.PI.toFloat()) else 0f
                val fx = dir.first.toFloat()
                val fy = dir.second.toFloat()
                val sx = -fy
                val sy = fx
                val ang = Math.toDegrees(kotlin.math.atan2(fy.toDouble(), fx.toDouble())).toFloat()
                // Щёчки.
                for (sgn in intArrayOf(1, -1)) {
                    c.drawCircle(hx + fx * r * 0.42f + sx * sgn * r * 0.62f, hy + fy * r * 0.42f + sy * sgn * r * 0.62f, r * 0.17f, cheek)
                }
                // Ротик.
                val mx = hx + fx * r * 0.66f
                val my = hy + fy * r * 0.66f
                val o = (mouthOpen * (1f - chompK) + chompK).coerceIn(0f, 1f)
                if (o > 0.15f) {
                    val mr = r * (0.08f + 0.13f * o)
                    c.drawCircle(mx, my, mr, mouth)
                    c.drawCircle(mx + fx * mr * 0.3f, my + fy * mr * 0.3f, mr * 0.5f, tongue)
                } else {
                    smile.strokeWidth = r * 0.09f
                    val sr = r * 0.24f
                    val cx0 = hx + fx * r * 0.42f
                    val cy0 = hy + fy * r * 0.42f
                    c.drawArc(cx0 - sr, cy0 - sr, cx0 + sr, cy0 + sr, ang - 55f, 110f, false, smile)
                }
                if (mouthOpen > 0.02f || chompK > 0f) postInvalidateOnAnimation()
                // Глазки: моргают раз в несколько секунд.
                val blink = (t % 3600L) < 120L
                for (sgn in intArrayOf(1, -1)) {
                    val px = hx + fx * r * 0.08f + sx * sgn * r * 0.4f
                    val py = hy + fy * r * 0.08f + sy * sgn * r * 0.4f
                    if (blink) {
                        smile.strokeWidth = r * 0.1f
                        c.drawLine(px - sx * r * 0.2f, py - sy * r * 0.2f, px + sx * r * 0.2f, py + sy * r * 0.2f, smile)
                    } else {
                        c.drawCircle(px, py, r * 0.3f, eye)
                        val qx = px + fx * r * 0.08f
                        val qy = py + fy * r * 0.08f
                        c.drawCircle(qx, qy, r * 0.19f, pupil)
                        c.drawCircle(qx - fx * r * 0.06f + sx * r * 0.06f, qy - fy * r * 0.06f + sy * r * 0.06f, r * 0.075f, shine)
                    }
                }
                if (skin.pattern == 2 || skin.pattern >= 4 || skin.fx != 0) postInvalidateOnAnimation()
            }
            val age = t - popAt
            if (age < 700) {
                label.alpha = (255 * (1 - age / 700f)).toInt()
                c.drawText(popText, popX, popY - age / 700f * cell * 2, label)
                label.alpha = 255
            }
            // Действующие усиления и помехи - значки с секундами (по игровому времени).
            val powers = StringBuilder()
            fun sec(ms: Long) = ((ms + 999) / 1000).toString() + context.getString(R.string.snake_sec)
            if (invulMs > 0) powers.append("💊").append(sec(invulMs)).append("  ")
            if (slowMs > 0) powers.append("⏳").append(sec(slowMs)).append("  ")
            if (doubleMs > 0) powers.append("×2 ").append(sec(doubleMs)).append("  ")
            if (magnetMs > 0) powers.append("🧲").append(sec(magnetMs)).append("  ")
            if (fastMs > 0) powers.append("☠").append(sec(fastMs)).append("  ")
            if (confuseMs > 0) powers.append("🌀").append(sec(confuseMs)).append("  ")
            if (shield) powers.append("🛡")
            if (powers.isNotEmpty() && started && !over) {
                val txt = powers.toString().trim()
                label.textSize = 14 * d
                val bx = ox + label.measureText(txt) / 2 + 22 * d
                drawPill(c, txt, bx, oy + 26 * d, 220)
                c.drawText(txt, bx, oy + 26 * d, label)
                label.textSize = 18 * d
            }
            val bAge = t - bannerAt
            if (bannerText.isNotEmpty() && bAge < 1800) {
                label.textSize = 24 * d
                label.alpha = (255 * (1 - (bAge / 1800f)).coerceIn(0f, 1f)).toInt()
                drawPill(c, bannerText, width / 2f, height * 0.3f, label.alpha)
                c.drawText(bannerText, width / 2f, height * 0.3f, label)
                label.alpha = 255
                label.textSize = 18 * d
            }
            if (!running) {
                label.textSize = 19 * d
                val msg = context.getString(
                    when {
                        over -> R.string.snake_over
                        started -> R.string.snake_paused
                        else -> R.string.snake_hint
                    },
                )
                drawPill(c, msg, width / 2f, height / 2f, 255)
                c.drawText(msg, width / 2f, height / 2f, label)
                label.textSize = 18 * d
            }
            if (running || age < 700 || bAge < 1800) postInvalidateOnAnimation()
        }

        private val metal = Paint(Paint.ANTI_ALIAS_FLAG)
        private val flame = Paint(Paint.ANTI_ALIAS_FLAG)
        private val obstaclePath = android.graphics.Path()

        /** Шипы: тёмная металлическая плита с тремя острыми серебряными шипами. */
        private fun drawSpikes(c: Canvas, p: Pair<Int, Int>) {
            val x0 = ox + p.first * cell
            val y0 = oy + p.second * cell
            rect.set(x0 + cell * 0.06f, y0 + cell * 0.62f, x0 + cell * 0.94f, y0 + cell * 0.94f)
            metal.shader = null
            metal.color = 0xFF334155.toInt()
            c.drawRoundRect(rect, cell * 0.12f, cell * 0.12f, metal)
            metal.shader = LinearGradient(x0, y0, x0 + cell, y0 + cell, 0xFFF1F5F9.toInt(), 0xFF64748B.toInt(), Shader.TileMode.CLAMP)
            for (k in 0 until 3) {
                val bx = x0 + cell * (0.12f + k * 0.27f)
                obstaclePath.reset()
                obstaclePath.moveTo(bx, y0 + cell * 0.66f)
                obstaclePath.lineTo(bx + cell * 0.13f, y0 + cell * 0.08f)
                obstaclePath.lineTo(bx + cell * 0.26f, y0 + cell * 0.66f)
                obstaclePath.close()
                c.drawPath(obstaclePath, metal)
            }
            metal.shader = null
        }

        /** Огонь: три языка пламени, колышутся и мерцают. */
        private fun drawFire(c: Canvas, p: Pair<Int, Int>, t: Long) {
            val x0 = ox + p.first * cell
            val y0 = oy + p.second * cell
            val ph = (p.first * 1.7f + p.second * 2.3f)
            val glowR = cell * (0.62f + 0.05f * kotlin.math.sin(t / 90f + ph))
            flame.shader = RadialGradient(x0 + cell / 2, y0 + cell * 0.7f, glowR, 0x88F97316.toInt(), 0x00F97316, Shader.TileMode.CLAMP)
            c.drawCircle(x0 + cell / 2, y0 + cell * 0.7f, glowR, flame)
            val layers = arrayOf(
                floatArrayOf(1f, 0f) to intArrayOf(0xFFDC2626.toInt(), 0xFFF97316.toInt()),
                floatArrayOf(0.72f, 0.06f) to intArrayOf(0xFFF97316.toInt(), 0xFFFDE047.toInt()),
                floatArrayOf(0.42f, 0.14f) to intArrayOf(0xFFFDE047.toInt(), 0xFFFFFBEB.toInt()),
            )
            for ((geo, cols2) in layers) {
                val sc = geo[0]
                val sway = kotlin.math.sin(t / 110f + ph + sc * 3) * cell * 0.08f
                val flick = 1f + 0.08f * kotlin.math.sin(t / 70f + ph * 2)
                val bottom = y0 + cell * 0.95f
                val top = bottom - cell * 0.9f * sc * flick
                val halfW = cell * 0.36f * sc
                val cx = x0 + cell / 2
                obstaclePath.reset()
                obstaclePath.moveTo(cx - halfW, bottom)
                obstaclePath.cubicTo(cx - halfW * 1.3f, bottom - (bottom - top) * 0.5f, cx + sway - halfW * 0.2f, top + (bottom - top) * 0.25f, cx + sway, top)
                obstaclePath.cubicTo(cx + sway + halfW * 0.2f, top + (bottom - top) * 0.25f, cx + halfW * 1.3f, bottom - (bottom - top) * 0.5f, cx + halfW, bottom)
                obstaclePath.close()
                flame.shader = LinearGradient(0f, bottom, 0f, top, cols2[0], cols2[1], Shader.TileMode.CLAMP)
                c.drawPath(obstaclePath, flame)
            }
            flame.shader = null
        }

        /** Валун: серый камень с бликом и трещинкой. */
        private fun drawRock(c: Canvas, p: Pair<Int, Int>) {
            val x0 = ox + p.first * cell
            val y0 = oy + p.second * cell
            obstaclePath.reset()
            obstaclePath.moveTo(x0 + cell * 0.1f, y0 + cell * 0.9f)
            obstaclePath.cubicTo(x0 + cell * 0.02f, y0 + cell * 0.4f, x0 + cell * 0.3f, y0 + cell * 0.12f, x0 + cell * 0.55f, y0 + cell * 0.14f)
            obstaclePath.cubicTo(x0 + cell * 0.85f, y0 + cell * 0.16f, x0 + cell * 0.98f, y0 + cell * 0.55f, x0 + cell * 0.9f, y0 + cell * 0.9f)
            obstaclePath.close()
            metal.shader = RadialGradient(x0 + cell * 0.38f, y0 + cell * 0.35f, cell * 0.8f, 0xFFD6D3D1.toInt(), 0xFF57534E.toInt(), Shader.TileMode.CLAMP)
            c.drawPath(obstaclePath, metal)
            metal.shader = null
            metal.style = Paint.Style.STROKE
            metal.strokeWidth = cell * 0.04f
            metal.color = 0xFF44403C.toInt()
            c.drawLine(x0 + cell * 0.5f, y0 + cell * 0.3f, x0 + cell * 0.6f, y0 + cell * 0.55f, metal)
            c.drawLine(x0 + cell * 0.6f, y0 + cell * 0.55f, x0 + cell * 0.52f, y0 + cell * 0.72f, metal)
            metal.style = Paint.Style.FILL
        }

        /** Ледяной кристалл: голубые грани и пробегающий блик. */
        private fun drawIce(c: Canvas, p: Pair<Int, Int>, t: Long) {
            val x0 = ox + p.first * cell
            val y0 = oy + p.second * cell
            val cx = x0 + cell / 2
            for ((k, hgt) in floatArrayOf(0.85f, 0.6f, 0.55f).withIndex()) {
                val bx = cx + (k - 1) * cell * 0.24f
                obstaclePath.reset()
                obstaclePath.moveTo(bx - cell * 0.13f, y0 + cell * 0.92f)
                obstaclePath.lineTo(bx - cell * 0.1f, y0 + cell * (0.92f - hgt * 0.75f))
                obstaclePath.lineTo(bx, y0 + cell * (0.92f - hgt))
                obstaclePath.lineTo(bx + cell * 0.1f, y0 + cell * (0.92f - hgt * 0.75f))
                obstaclePath.lineTo(bx + cell * 0.13f, y0 + cell * 0.92f)
                obstaclePath.close()
                metal.shader = LinearGradient(bx - cell * 0.13f, 0f, bx + cell * 0.13f, 0f, intArrayOf(0xFFE0F2FE.toInt(), 0xFF7DD3FC.toInt(), 0xFF0284C7.toInt()), null, Shader.TileMode.CLAMP)
                c.drawPath(obstaclePath, metal)
            }
            metal.shader = null
            val shine = ((t + p.first * 300L + p.second * 170L) % 2400L) / 2400f
            if (shine < 0.2f) {
                metal.color = 0xFFFFFFFF.toInt()
                metal.alpha = (255 * (1 - shine / 0.2f)).toInt()
                c.drawCircle(cx - cell * 0.05f, y0 + cell * 0.35f, cell * 0.06f, metal)
                metal.alpha = 255
            }
        }

        /** Кактус с колючками и цветком. */
        private fun drawCactus(c: Canvas, p: Pair<Int, Int>) {
            val x0 = ox + p.first * cell
            val y0 = oy + p.second * cell
            val cx = x0 + cell / 2
            metal.shader = LinearGradient(cx - cell * 0.15f, 0f, cx + cell * 0.15f, 0f, 0xFF4ADE80.toInt(), 0xFF15803D.toInt(), Shader.TileMode.CLAMP)
            rect.set(cx - cell * 0.13f, y0 + cell * 0.12f, cx + cell * 0.13f, y0 + cell * 0.95f)
            c.drawRoundRect(rect, cell * 0.13f, cell * 0.13f, metal)
            rect.set(cx - cell * 0.4f, y0 + cell * 0.35f, cx - cell * 0.22f, y0 + cell * 0.62f)
            c.drawRoundRect(rect, cell * 0.09f, cell * 0.09f, metal)
            rect.set(cx + cell * 0.22f, y0 + cell * 0.25f, cx + cell * 0.4f, y0 + cell * 0.52f)
            c.drawRoundRect(rect, cell * 0.09f, cell * 0.09f, metal)
            metal.shader = null
            metal.color = 0xFF15803D.toInt()
            c.drawRect(cx - cell * 0.31f, y0 + cell * 0.56f, cx - cell * 0.12f, y0 + cell * 0.62f, metal)
            c.drawRect(cx + cell * 0.12f, y0 + cell * 0.46f, cx + cell * 0.31f, y0 + cell * 0.52f, metal)
            metal.color = 0xFFFDF4FF.toInt()
            for (k in 0 until 5) c.drawCircle(cx + (if (k % 2 == 0) -1 else 1) * cell * 0.08f, y0 + cell * (0.25f + k * 0.14f), cell * 0.022f, metal)
            metal.color = 0xFFF472B6.toInt()
            c.drawCircle(cx, y0 + cell * 0.12f, cell * 0.08f, metal)
        }

        /** Бонус: алмаз (переливается) или сундучок; мигает, когда скоро исчезнет. */
        private fun drawBonus(c: Canvas, p: Pair<Int, Int>, kind: Int, t: Long, left: Long) {
            if (left < 2_500 && (t / 150) % 2 == 0L) return
            val cx = ox + (p.first + 0.5f) * cell
            val cy = oy + (p.second + 0.5f) * cell + kotlin.math.sin(t / 200f) * cell * 0.05f
            val r = cell * 0.44f
            flame.shader = RadialGradient(cx, cy, r * 1.4f, if (kind == 0) 0x8867E8F9.toInt() else 0x88FDE047.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 1.4f, flame)
            flame.shader = null
            // Сколько бонусу осталось лежать - тонкое кольцо-таймер вокруг.
            metal.shader = null
            metal.style = Paint.Style.STROKE
            metal.strokeWidth = cell * 0.07f
            metal.color = if (kind == B_POISON || kind == B_CONFUSE) 0xCCEF4444.toInt() else 0xCCFFFFFF.toInt()
            rect.set(cx - r * 1.05f, cy - r * 1.05f, cx + r * 1.05f, cy + r * 1.05f)
            c.drawArc(rect, -90f, 360f * (left / 9_000f).coerceIn(0f, 1f), false, metal)
            metal.style = Paint.Style.FILL
            if (kind >= 2) {
                // Жетон с эмодзи: усиления - светлые, помехи - тёмно-красные.
                val (light, dark) = when (kind) {
                    B_SLOW -> 0xFFBAE6FD.toInt() to 0xFF0284C7.toInt()
                    B_X2 -> 0xFFFDE68A.toInt() to 0xFFD97706.toInt()
                    B_SHIELD -> 0xFFC7D2FE.toInt() to 0xFF4338CA.toInt()
                    B_PILL -> 0xFFFBCFE8.toInt() to 0xFFDB2777.toInt()
                    B_MAGNET -> 0xFFFECACA.toInt() to 0xFF2563EB.toInt()
                    else -> 0xFF7F1D1D.toInt() to 0xFF1C1917.toInt()
                }
                metal.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.2f, light, dark, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r * 0.82f, metal)
                metal.shader = null
                emojiPaint.textSize = r * 1.05f
                val e = KIND_ICON.getOrElse(kind) { "?" }
                emojiPaint.color = 0xFFFFFFFF.toInt()
                emojiPaint.isFakeBoldText = kind == 3
                c.drawText(e, cx, cy + emojiPaint.textSize * 0.36f, emojiPaint)
                return
            }
            if (kind == 0) {
                obstaclePath.reset()
                obstaclePath.moveTo(cx - r * 0.8f, cy - r * 0.25f)
                obstaclePath.lineTo(cx - r * 0.45f, cy - r * 0.7f)
                obstaclePath.lineTo(cx + r * 0.45f, cy - r * 0.7f)
                obstaclePath.lineTo(cx + r * 0.8f, cy - r * 0.25f)
                obstaclePath.lineTo(cx, cy + r * 0.85f)
                obstaclePath.close()
                metal.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, intArrayOf(0xFFE0FFFF.toInt(), 0xFF22D3EE.toInt(), 0xFF6366F1.toInt()), null, Shader.TileMode.CLAMP)
                c.drawPath(obstaclePath, metal)
                metal.shader = null
                metal.color = 0xCCFFFFFF.toInt()
                c.drawLine(cx - r * 0.8f, cy - r * 0.25f, cx + r * 0.8f, cy - r * 0.25f, metal.apply { strokeWidth = cell * 0.03f })
                val tw = ((t / 90) % 12).toInt()
                if (tw < 3) c.drawCircle(cx - r * 0.3f, cy - r * 0.45f, cell * 0.05f, metal)
            } else {
                rect.set(cx - r * 0.75f, cy - r * 0.35f, cx + r * 0.75f, cy + r * 0.6f)
                metal.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0xFFB45309.toInt(), 0xFF78350F.toInt(), Shader.TileMode.CLAMP)
                c.drawRoundRect(rect, cell * 0.06f, cell * 0.06f, metal)
                rect.set(cx - r * 0.75f, cy - r * 0.7f, cx + r * 0.75f, cy - r * 0.25f)
                c.drawRoundRect(rect, cell * 0.12f, cell * 0.12f, metal)
                metal.shader = null
                metal.color = 0xFFFDE047.toInt()
                c.drawRect(cx - r * 0.75f, cy - r * 0.3f, cx + r * 0.75f, cy - r * 0.2f, metal)
                c.drawRect(cx - r * 0.1f, cy - r * 0.45f, cx + r * 0.1f, cy + r * 0.05f, metal)
            }
        }

        /** Тёмная «таблетка» под надписью - белый текст читается на светлом поле. */
        private fun drawPill(c: Canvas, text: String, cx: Float, baseline: Float, alpha: Int) {
            val w = label.measureText(text) / 2 + 16 * d
            val h = label.textSize
            pill.alpha = (alpha * 0.6f).toInt()
            rect.set(cx - w, baseline - h * 1.05f, cx + w, baseline + h * 0.45f)
            c.drawRoundRect(rect, h, h, pill)
        }

        private fun drawCoin(c: Canvas, p: Pair<Int, Int>, light: Int, dark: Int, scale: Float) {
            val cx = ox + (p.first + 0.5f) * cell
            val cy = oy + (p.second + 0.5f) * cell
            val r = cell * 0.38f * scale
            coin.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.3f, light, dark, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, coin)
            c.drawText("S", cx, cy + coinText.textSize * 0.36f, coinText)
        }

        private fun blend(a: Int, b: Int, k: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - k) + ((b shr s) and 0xFF) * k).toInt() shl s
            return 0xFF000000.toInt() or ch(16) or ch(8) or ch(0)
        }

        private var downX = 0f
        private var downY = 0f
        private var swiped = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            // Жесты: поворот срабатывает уже во время свайпа (не нужно отпускать палец),
            // можно вести палец дальше и поворачивать снова. Короткое касание - пауза.
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    swiped = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (maxOf(abs(dx), abs(dy)) > 22 * d) {
                        if (abs(dx) > abs(dy)) turn(if (dx > 0) 1 else -1, 0) else turn(0, if (dy > 0) 1 else -1)
                        downX = e.x
                        downY = e.y
                        swiped = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!swiped && maxOf(abs(dx), abs(dy)) < 24 * d) {
                        toggle()
                    } else if (maxOf(abs(dx), abs(dy)) >= 24 * d) {
                        if (abs(dx) > abs(dy)) turn(if (dx > 0) 1 else -1, 0) else turn(0, if (dy > 0) 1 else -1)
                    }
                }
            }
            return true
        }

        // Свернули приложение / окно потеряло фокус - пауза и сохранение.
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (!hasWindowFocus && running) pauseAndSave()
        }

        override fun onDetachedFromWindow() {
            pauseAndSave()
            super.onDetachedFromWindow()
        }
    }

    /**
     * Большой джойстик-крестовина: круг с четырьмя секторами-стрелками. Направление - по
     * углу от центра, срабатывает сразу при касании и при ведении пальцем в другой сектор,
     * так что нажатие не «теряется» даже мимо стрелки. Подсвечивает активный сектор.
     */
    @SuppressLint("ViewConstructor")
    private class Joystick(ctx: Context, private val style: Int, private val onDir: (Int, Int) -> Unit) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val base = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xCCFFFFFF.toInt()
        }
        private val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = android.graphics.Path()
        private val oval = RectF()
        private var active = -1
        private var kx = 0f
        private var ky = 0f

        init {
            isClickable = true
            contentDescription = ctx.getString(R.string.snake_joystick)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val r = minOf(w, h) / 2f
            base.shader = RadialGradient(w / 2f, h / 2f - r * 0.2f, r * 1.2f, 0xFF818CF8.toInt(), 0xFF4338CA.toInt(), Shader.TileMode.CLAMP)
            knob.shader = RadialGradient(w / 2f - r * 0.08f, h / 2f - r * 0.08f, r * 0.4f, 0xFFFFFFFF.toInt(), 0xFFC7D2FE.toInt(), Shader.TileMode.CLAMP)
            rim.strokeWidth = 3 * d
            kx = w / 2f
            ky = h / 2f
        }

        private val btn = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tmpRect = RectF()

        /** Стрелка-треугольник с центром (x, y), смотрит в сторону [a] (0 - вверх, по часовой). */
        private fun arrow(c: Canvas, x: Float, y: Float, s: Float, a: Int, on: Boolean) {
            c.save()
            c.rotate(a * 90f, x, y)
            path.reset()
            path.moveTo(x, y - s)
            path.lineTo(x + s, y + s * 0.6f)
            path.lineTo(x - s, y + s * 0.6f)
            path.close()
            glyph.alpha = if (on) 255 else 210
            c.drawPath(path, glyph)
            c.restore()
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - 3 * d
            if (style == CTRL_SQUARE) {
                // Квадратная крестовина, как на геймпаде.
                val arm = r * 0.36f
                path.reset()
                path.addRoundRect(RectF(cx - arm, cy - r, cx + arm, cy + r), arm * 0.4f, arm * 0.4f, android.graphics.Path.Direction.CW)
                path.addRoundRect(RectF(cx - r, cy - arm, cx + r, cy + arm), arm * 0.4f, arm * 0.4f, android.graphics.Path.Direction.CW)
                c.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), r * 0.22f, r * 0.22f, hot.apply { alpha = 40 })
                hot.alpha = 102
                c.drawPath(path, base)
                c.drawPath(path, rim)
                if (active >= 0) {
                    c.save()
                    c.rotate(active * 90f, cx, cy)
                    c.drawRoundRect(RectF(cx - arm, cy - r, cx + arm, cy - arm), arm * 0.4f, arm * 0.4f, hot)
                    c.restore()
                }
                for (a in 0 until 4) {
                    val ax = cx + floatArrayOf(0f, 1f, 0f, -1f)[a] * r * 0.66f
                    val ay = cy + floatArrayOf(-1f, 0f, 1f, 0f)[a] * r * 0.66f
                    arrow(c, ax, ay, r * 0.15f, a, a == active)
                }
                c.drawCircle(cx, cy, arm * 0.5f, knob)
                return
            }
            if (style == CTRL_ARROWS) {
                // Четыре отдельные круглые кнопки-стрелки ромбом.
                val br = r * 0.3f
                for (a in 0 until 4) {
                    val ax = cx + floatArrayOf(0f, 1f, 0f, -1f)[a] * (r - br)
                    val ay = cy + floatArrayOf(-1f, 0f, 1f, 0f)[a] * (r - br)
                    val on = a == active
                    btn.shader = RadialGradient(ax, ay - br * 0.3f, br * 1.3f, if (on) 0xFFA5B4FC.toInt() else 0xFF818CF8.toInt(), 0xFF4338CA.toInt(), Shader.TileMode.CLAMP)
                    c.drawCircle(ax, ay, br * (if (on) 0.94f else 1f), btn)
                    c.drawCircle(ax, ay, br * (if (on) 0.94f else 1f), rim)
                    arrow(c, ax, ay, br * 0.45f, a, on)
                }
                return
            }
            c.drawCircle(cx, cy, r, base)
            c.drawCircle(cx, cy, r, rim)
            if (active >= 0) {
                oval.set(cx - r, cy - r, cx + r, cy + r)
                // 0 - вверх, 1 - вправо, 2 - вниз, 3 - влево; сектор по 90°.
                val startAngle = floatArrayOf(225f, 315f, 45f, 135f)[active]
                c.drawArc(oval, startAngle, 90f, true, hot)
            }
            for (a in 0 until 4) {
                c.save()
                c.rotate(a * 90f, cx, cy)
                val s = r * 0.17f
                val ty = cy - r * 0.66f
                path.reset()
                path.moveTo(cx, ty - s)
                path.lineTo(cx + s, ty + s * 0.6f)
                path.lineTo(cx - s, ty + s * 0.6f)
                path.close()
                glyph.alpha = if (a == active) 255 else 200
                c.drawPath(path, glyph)
                c.restore()
            }
            c.drawCircle(kx, ky, r * 0.28f, knob)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val i = if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) e.actionIndex else 0
                    val dx = e.getX(i) - cx
                    val dy = e.getY(i) - cy
                    val dist = kotlin.math.hypot(dx, dy)
                    val lim = r * 0.5f
                    kx = cx + if (dist > lim) dx / dist * lim else dx
                    ky = cy + if (dist > lim) dy / dist * lim else dy
                    // Мёртвая зона в самом центре только для ведения пальцем; касание - всегда ход.
                    if (dist > r * 0.12f || e.actionMasked != MotionEvent.ACTION_MOVE) {
                        val dir = if (abs(dx) > abs(dy)) (if (dx > 0) 1 else 3) else (if (dy > 0) 2 else 0)
                        if (dir != active || e.actionMasked != MotionEvent.ACTION_MOVE) {
                            active = dir
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            when (dir) {
                                0 -> onDir(0, -1)
                                1 -> onDir(1, 0)
                                2 -> onDir(0, 1)
                                else -> onDir(-1, 0)
                            }
                        }
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    active = -1
                    kx = cx
                    ky = cy
                    invalidate()
                }
            }
            return true
        }
    }

    // ---------------- скины ----------------

    /**
     * Скин змейки: цвета тела (от головы к хвосту), голова, узор, цена.
     * Узоры: 0 - перелив, 1 - полосы, 2 - радуга, 3 - перелив с искрами, 4 - бегущая волна.
     */
    // Предметы на поле (бонусы и помехи).
    const val B_DIAMOND = 0
    const val B_CHEST = 1
    const val B_SLOW = 2
    const val B_X2 = 3
    const val B_SHIELD = 4
    const val B_PILL = 5
    const val B_MAGNET = 6
    const val B_POISON = 7
    const val B_CONFUSE = 8
    val KIND_ICON = listOf("💎", "🎁", "⏳", "×2", "🛡", "💊", "🧲", "☠", "🌀")

    /**
     * Скин: цвета тела ([from] → [to], третий [c3] - для трёхцветных узоров и цвета свечения),
     * узор окраски [pattern] и эффект поверх тела [fx] (см. [SnakeSkinFx]).
     */
    class Skin(val id: String, val name: Int, val from: Int, val to: Int, val head: Int, val pattern: Int, basePrice: Int, val exclusive: Boolean = false, val fx: Int = 0, val c3: Int = 0) {
        /** Цена ×2 (с 4.9.8 - все цены в монетах удвоены). */
        val price: Int = basePrice * 2
    }

    val SKINS = listOf(
        Skin("classic", R.string.snake_skin_classic, 0xFF6366F1.toInt(), 0xFF06B6D4.toInt(), 0xFF3730A3.toInt(), 0, 0),
        Skin("forest", R.string.snake_skin_forest, 0xFF16A34A.toInt(), 0xFF84CC16.toInt(), 0xFF14532D.toInt(), 0, 0),
        Skin("coral", R.string.snake_skin_coral, 0xFFFB7185.toInt(), 0xFFFDBA74.toInt(), 0xFFE11D48.toInt(), 0, 40),
        Skin("fire", R.string.snake_skin_fire, 0xFFDC2626.toInt(), 0xFFFBBF24.toInt(), 0xFF7F1D1D.toInt(), 4, 60),
        Skin("ice", R.string.snake_skin_ice, 0xFF38BDF8.toInt(), 0xFFE0F2FE.toInt(), 0xFF0369A1.toInt(), 3, 80),
        Skin("tiger", R.string.snake_skin_tiger, 0xFFF97316.toInt(), 0xFF1C1917.toInt(), 0xFFC2410C.toInt(), 1, 80),
        Skin("zebra", R.string.snake_skin_zebra, 0xFFFFFFFF.toInt(), 0xFF111827.toInt(), 0xFF374151.toInt(), 1, 80),
        Skin("python", R.string.snake_skin_python, 0xFF65A30D.toInt(), 0xFFFACC15.toInt(), 0xFF365314.toInt(), 1, 100),
        Skin("candy", R.string.snake_skin_candy, 0xFFF472B6.toInt(), 0xFFFFFFFF.toInt(), 0xFFDB2777.toInt(), 1, 100),
        Skin("neon", R.string.snake_skin_neon, 0xFFEC4899.toInt(), 0xFF22D3EE.toInt(), 0xFF9D174D.toInt(), 4, 100),
        Skin("emerald", R.string.snake_skin_emerald, 0xFF10B981.toInt(), 0xFF064E3B.toInt(), 0xFF064E3B.toInt(), 3, 120),
        Skin("ghost", R.string.snake_skin_ghost, 0xFFF8FAFC.toInt(), 0xFF94A3B8.toInt(), 0xFF64748B.toInt(), 0, 140),
        Skin("gold", R.string.snake_skin_gold, 0xFFFDE047.toInt(), 0xFFB45309.toInt(), 0xFF92400E.toInt(), 3, 160),
        Skin("galaxy", R.string.snake_skin_galaxy, 0xFF7C3AED.toInt(), 0xFF1E1B4B.toInt(), 0xFF4C1D95.toInt(), 3, 180),
        Skin("lava", R.string.snake_skin_lava, 0xFFF97316.toInt(), 0xFF450A0A.toInt(), 0xFF7C2D12.toInt(), 4, 200),
        Skin("rainbow", R.string.snake_skin_rainbow, 0, 0, 0xFF4C1D95.toInt(), 2, 240),
        // Уникальные скины с собственными узорами.
        Skin("bee", R.string.snake_skin_bee, 0xFFFACC15.toInt(), 0xFF1C1917.toInt(), 0xFF1C1917.toInt(), 1, 120),
        Skin("sakura", R.string.snake_skin_sakura, 0xFFFBCFE8.toInt(), 0xFFF9A8D4.toInt(), 0xFFDB2777.toInt(), 3, 140),
        Skin("toxic", R.string.snake_skin_toxic, 0xFFA3E635.toInt(), 0xFF7E22CE.toInt(), 0xFF3F6212.toInt(), 4, 160),
        Skin("chrome", R.string.snake_skin_chrome, 0xFFF1F5F9.toInt(), 0xFF64748B.toInt(), 0xFF334155.toInt(), 7, 220),
        Skin("cyber", R.string.snake_skin_cyber, 0xFF22D3EE.toInt(), 0xFFF0ABFC.toInt(), 0xFF0F172A.toInt(), 6, 240),
        Skin("aurora", R.string.snake_skin_aurora, 0xFF34D399.toInt(), 0xFF818CF8.toInt(), 0xFF065F46.toInt(), 5, 280),
        Skin("dragon", R.string.snake_skin_dragon, 0xFFB91C1C.toInt(), 0xFFFBBF24.toInt(), 0xFF450A0A.toInt(), 8, 320),
        Skin("cosmos", R.string.snake_skin_cosmos, 0xFF1E1B4B.toInt(), 0xFF7C3AED.toInt(), 0xFF020617.toInt(), 9, 360),
        // 4.2.0: ещё 12 скинов.
        Skin("mint", R.string.snake_skin_mint, 0xFF6EE7B7.toInt(), 0xFFCCFBF1.toInt(), 0xFF047857.toInt(), 0, 120),
        Skin("sunset", R.string.snake_skin_sunset, 0xFFF97316.toInt(), 0xFF9333EA.toInt(), 0xFF7C2D12.toInt(), 4, 200),
        Skin("ocean", R.string.snake_skin_ocean, 0xFF0EA5E9.toInt(), 0xFF1E3A8A.toInt(), 0xFF0C4A6E.toInt(), 4, 240),
        Skin("lemon", R.string.snake_skin_lemon, 0xFFFDE047.toInt(), 0xFFA3E635.toInt(), 0xFF65A30D.toInt(), 1, 160),
        Skin("berry", R.string.snake_skin_berry, 0xFFBE185D.toInt(), 0xFF7C3AED.toInt(), 0xFF500724.toInt(), 3, 280),
        Skin("midnight", R.string.snake_skin_midnight, 0xFF1E3A8A.toInt(), 0xFF020617.toInt(), 0xFF0F172A.toInt(), 9, 400),
        Skin("pharaoh", R.string.snake_skin_pharaoh, 0xFFFBBF24.toInt(), 0xFF1D4ED8.toInt(), 0xFF78350F.toInt(), 1, 440),
        Skin("peacock", R.string.snake_skin_peacock, 0xFF0D9488.toInt(), 0xFF2563EB.toInt(), 0xFF134E4A.toInt(), 5, 480),
        Skin("lightning", R.string.snake_skin_lightning, 0xFFFEF08A.toInt(), 0xFF6366F1.toInt(), 0xFF312E81.toInt(), 7, 520),
        Skin("pixel", R.string.snake_skin_pixel, 0xFF4ADE80.toInt(), 0xFFF472B6.toInt(), 0xFF14532D.toInt(), 6, 400),
        Skin("unicorn", R.string.snake_skin_unicorn, 0, 0, 0xFFF0ABFC.toInt(), 10, 600),
        Skin("obsidian", R.string.snake_skin_obsidian, 0xFF52525B.toInt(), 0xFF09090B.toInt(), 0xFF18181B.toInt(), 7, 560),
        // 4.7.0: 19 скинов с живыми эффектами (свечение, пламя, звёзды, чешуя, молнии…).
        Skin("neonglow", R.string.gamesb_skin_neonglow, 0xFF22D3EE.toInt(), 0xFFA855F7.toInt(), 0xFF0E7490.toInt(), 13, 450, fx = SnakeSkinFx.FX_GLOW, c3 = 0xFF22D3EE.toInt()),
        Skin("flametail", R.string.gamesb_skin_flametail, 0xFFFDE047.toInt(), 0xFFEA580C.toInt(), 0xFF9A3412.toInt(), 19, 520, fx = SnakeSkinFx.FX_FLAME),
        Skin("stardust", R.string.gamesb_skin_stardust, 0xFF1E3A8A.toInt(), 0xFF6D28D9.toInt(), 0xFF172554.toInt(), 9, 480, fx = SnakeSkinFx.FX_SPARKLE),
        Skin("pearl", R.string.gamesb_skin_pearl, 0xFFFCE7F3.toInt(), 0xFFE0F2FE.toInt(), 0xFFC4B5FD.toInt(), 14, 380, fx = SnakeSkinFx.FX_SHINE, c3 = 0xFFFFFFFF.toInt()),
        Skin("milkyway", R.string.gamesb_skin_milkyway, 0xFF0F172A.toInt(), 0xFF312E81.toInt(), 0xFF020617.toInt(), 9, 640, fx = SnakeSkinFx.FX_DOTS),
        Skin("basilisk", R.string.gamesb_skin_basilisk, 0xFF15803D.toInt(), 0xFF4D7C0F.toInt(), 0xFF14532D.toInt(), 0, 300, fx = SnakeSkinFx.FX_SCALES),
        Skin("festive", R.string.gamesb_skin_festive, 0xFFF43F5E.toInt(), 0xFFFFFFFF.toInt(), 0xFFBE123C.toInt(), 15, 260, c3 = 0xFF22C55E.toInt()),
        Skin("koi", R.string.gamesb_skin_koi, 0xFFFFFBF5.toInt(), 0xFFF97316.toInt(), 0xFFFED7AA.toInt(), 16, 420, fx = SnakeSkinFx.FX_SCALES, c3 = 0xFF111827.toInt()),
        Skin("matrix", R.string.gamesb_skin_matrix, 0xFF052E16.toInt(), 0xFF4ADE80.toInt(), 0xFF022C22.toInt(), 17, 500, fx = SnakeSkinFx.FX_GLOW, c3 = 0xFF22C55E.toInt()),
        Skin("sunrise", R.string.gamesb_skin_sunrise, 0xFFFDE68A.toInt(), 0xFFFB7185.toInt(), 0xFFBE185D.toInt(), 18, 340, c3 = 0xFF7C3AED.toInt()),
        Skin("ember", R.string.gamesb_skin_ember, 0xFF1C1917.toInt(), 0xFFF97316.toInt(), 0xFF0C0A09.toInt(), 19, 460, fx = SnakeSkinFx.FX_EMBERS),
        Skin("frost", R.string.gamesb_skin_frost, 0xFFE0F2FE.toInt(), 0xFF7DD3FC.toInt(), 0xFF0284C7.toInt(), 14, 420, fx = SnakeSkinFx.FX_SNOW, c3 = 0xFFFFFFFF.toInt()),
        Skin("venom", R.string.gamesb_skin_venom, 0xFF84CC16.toInt(), 0xFF14532D.toInt(), 0xFF1A2E05.toInt(), 4, 360, fx = SnakeSkinFx.FX_BUBBLES, c3 = 0xFFBEF264.toInt()),
        Skin("sapphire", R.string.gamesb_skin_sapphire, 0xFF1D4ED8.toInt(), 0xFF60A5FA.toInt(), 0xFF1E3A8A.toInt(), 7, 540, fx = SnakeSkinFx.FX_SHINE),
        Skin("electro", R.string.gamesb_skin_electro, 0xFF312E81.toInt(), 0xFF38BDF8.toInt(), 0xFF1E1B4B.toInt(), 13, 600, fx = SnakeSkinFx.FX_ZAP, c3 = 0xFF7DD3FC.toInt()),
        Skin("rosegold", R.string.gamesb_skin_rosegold, 0xFFFBCFE8.toInt(), 0xFFB76E79.toInt(), 0xFF9D4B5B.toInt(), 7, 560, fx = SnakeSkinFx.FX_SPARKLE),
        Skin("twilight", R.string.gamesb_skin_twilight, 0xFF0EA5E9.toInt(), 0xFF8B5CF6.toInt(), 0xFF4C1D95.toInt(), 18, 320, fx = SnakeSkinFx.FX_GLOW, c3 = 0xFFF472B6.toInt()),
        Skin("neonzebra", R.string.gamesb_skin_neonzebra, 0xFF0F172A.toInt(), 0xFFF0ABFC.toInt(), 0xFF0F172A.toInt(), 15, 480, fx = SnakeSkinFx.FX_GLOW, c3 = 0xFF22D3EE.toInt()),
        Skin("honeycomb", R.string.gamesb_skin_honeycomb, 0xFFFBBF24.toInt(), 0xFFB45309.toInt(), 0xFF78350F.toInt(), 0, 340, fx = SnakeSkinFx.FX_SCALES),
        // ✦ Эксклюзивы: только в акциях магазина монет, на время.
        Skin("x_phoenix", R.string.snake_skin_x_phoenix, 0xFFFDE047.toInt(), 0xFFDC2626.toInt(), 0xFF7C2D12.toInt(), 8, 2400, true),
        Skin("x_hologram", R.string.snake_skin_x_hologram, 0, 0, 0xFF64748B.toInt(), 11, 2800, true),
        Skin("x_chameleon", R.string.snake_skin_x_chameleon, 0, 0, 0xFF166534.toInt(), 12, 2200, true),
        Skin("x_demon", R.string.snake_skin_x_demon, 0xFF7F1D1D.toInt(), 0xFF020617.toInt(), 0xFF450A0A.toInt(), 4, 2600, true),
        Skin("x_angel", R.string.snake_skin_x_angel, 0xFFFFFFFF.toInt(), 0xFFFDE68A.toInt(), 0xFFFBBF24.toInt(), 3, 3000, true),
        Skin("x_jade", R.string.snake_skin_x_jade, 0xFF34D399.toInt(), 0xFF064E3B.toInt(), 0xFF022C22.toInt(), 7, 2000, true),
        Skin("x_nebula", R.string.snake_skin_x_nebula, 0xFFC026D3.toInt(), 0xFF1E1B4B.toInt(), 0xFF2E1065.toInt(), 9, 3200, true),
        Skin("x_royal", R.string.snake_skin_x_royal, 0xFF1D4ED8.toInt(), 0xFFFBBF24.toInt(), 0xFF172554.toInt(), 6, 3600, true),
    )

    private fun mix(a: Int, b: Int, k: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - k) + ((b shr s) and 0xFF) * k).toInt() shl s
        return 0xFF000000.toInt() or ch(16) or ch(8) or ch(0)
    }

    /** Цвет сегмента [i] из [n] для скина (общий для игры и предпросмотра). */
    fun skinColor(skin: Skin, i: Int, n: Int, t: Long): Int {
        val k = i.toFloat() / n.coerceAtLeast(1)
        return when (skin.pattern) {
            1 -> if ((i / 2) % 2 == 0) skin.from else skin.to
            2 -> Color.HSVToColor(floatArrayOf(((i * 22 + t / 12) % 360).toFloat(), 0.75f, 0.95f))
            4 -> mix(skin.from, skin.to, ((kotlin.math.sin(i * 0.7 - t / 140.0) + 1) / 2).toFloat())
            // Северное сияние: оттенки от зелёного до фиолетового, плывут вдоль тела.
            5 -> Color.HSVToColor(floatArrayOf((150 + 110 * ((kotlin.math.sin(i * 0.35 - t / 420.0) + 1) / 2)).toFloat(), 0.65f, 0.95f))
            // Кибер: неоновые звенья через одно, между ними - тёмные «стыки».
            6 -> when (i % 3) {
                0 -> skin.from
                1 -> skin.to
                else -> 0xFF0F172A.toInt()
            }
            // Хром: металлический блик пробегает от головы к хвосту.
            7 -> mix(skin.from, skin.to, (0.5 + 0.5 * kotlin.math.cos(i * 0.9 - t / 90.0)).toFloat())
            // Дракон: чешуя - тёмные и огненные кольца, пульсирует жаром.
            8 -> if (i % 2 == 0) mix(skin.from, 0xFF450A0A.toInt(), 0.3f) else mix(skin.from, skin.to, ((kotlin.math.sin(t / 160.0 + i) + 1) / 2).toFloat())
            // Космос: глубокий синий с фиолетовой туманностью (искры - как у звёздных скинов).
            9 -> mix(skin.from, skin.to, ((kotlin.math.sin(i * 0.5 + t / 600.0) + 1) / 2 * 0.8).toFloat())
            // ✦ Голограмма: переливы всех цветов с металлическим бликом.
            11 -> mix(Color.HSVToColor(floatArrayOf(((i * 14 + t / 9) % 360).toFloat(), 0.45f, 1f)), 0xFFE2E8F0.toInt(), (0.5 + 0.5 * kotlin.math.cos(i * 0.8 - t / 80.0)).toFloat() * 0.5f)
            // ✦ Хамелеон: вся змейка медленно меняет цвет, полоски темнее.
            12 -> Color.HSVToColor(floatArrayOf(((t / 30) % 360).toFloat(), if (i % 3 == 0) 0.85f else 0.6f, if (i % 3 == 0) 0.6f else 0.9f))
            // Единорог: пастельная радуга.
            10 -> Color.HSVToColor(floatArrayOf(((i * 26 + t / 16) % 360).toFloat(), 0.32f, 1f))
            // Неон: волна яркости бежит от головы к хвосту.
            13 -> mix(skin.from, skin.to, (0.5 + 0.5 * kotlin.math.sin(t / 260.0 - i * 0.45)).toFloat())
            // Перламутр / иней: светлая полоса-перелив скользит вдоль тела.
            14 -> {
                val pos = (t / 55 % (n + 10)).toFloat() - 5f
                val a = (1f - abs(i - pos) / 3f).coerceIn(0f, 1f)
                mix(mix(skin.from, skin.to, k), if (skin.c3 != 0) skin.c3 else Color.WHITE, a * 0.8f)
            }
            // Три цвета полосками по два сегмента.
            15 -> when ((i / 2) % 3) {
                0 -> skin.from
                1 -> skin.to
                else -> skin.c3
            }
            // Кои: белое тело с рыжими пятнами и редкими тёмными крапинками (узор не «плывёт»).
            16 -> when {
                i % 9 == 4 -> skin.c3
                kotlin.math.sin(i * 0.9) + kotlin.math.sin(i * 0.37 + 1.3) > 0.5 -> skin.to
                else -> skin.from
            }
            // Матрица: сегменты вспыхивают зелёным, как цифры на экране (9 раз в секунду).
            17 -> {
                val b = (SnakeSkinFx.hash(i * 73856093 xor (t / 110).toInt() * 19349663) and 255) / 255f
                mix(skin.from, skin.to, 0.2f + 0.8f * b * b)
            }
            // Три цвета плавным градиентом, слегка «дышит».
            18 -> {
                val q = (k + 0.08f * kotlin.math.sin(t / 900.0).toFloat()).coerceIn(0f, 1f)
                if (q < 0.5f) mix(skin.from, skin.to, q * 2) else mix(skin.to, skin.c3, (q - 0.5f) * 2)
            }
            // Угли: тёмное тело тлеет и разгорается неравномерно.
            19 -> mix(skin.from, skin.to, (0.35 + 0.65 * ((kotlin.math.sin(t / 110.0 + i * 1.7) * kotlin.math.sin(t / 170.0 + i * 0.6) + 1) / 2)).toFloat())
            else -> mix(skin.from, skin.to, k)
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("coins", Context.MODE_PRIVATE)

    /** Тёмное поле (по умолчанию - светлое). */
    fun darkBoard(ctx: Context) = prefs(ctx).getBoolean("snake_dark", false)

    const val CTRL_ROUND = 0
    const val CTRL_SQUARE = 1
    const val CTRL_ARROWS = 2
    const val CTRL_GESTURES = 3

    /** Вид управления: круглый джойстик (по умолчанию), квадратная крестовина, стрелки, жесты. */
    fun controlStyle(ctx: Context) = prefs(ctx).getInt("snake_ctrl", CTRL_ROUND).coerceIn(0, 3)

    /** Настройки: поле (светлое/тёмное) и вид управления. */
    fun boardSettings(ctx: Context, onChange: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        fun header(res: Int) = TextView(ctx).apply {
            setText(res)
            textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
        }
        fun group(items: IntArray, checked: Int, onPick: (Int) -> Unit) = android.widget.RadioGroup(ctx).apply {
            items.forEachIndexed { i, res ->
                addView(android.widget.RadioButton(ctx).apply {
                    id = View.generateViewId()
                    setText(res)
                    textSize = 16f
                    isChecked = i == checked
                    setOnClickListener {
                        onPick(i)
                        onChange()
                    }
                })
            }
        }
        box.addView(header(R.string.snake_board_title))
        box.addView(group(intArrayOf(R.string.snake_board_light, R.string.snake_board_dark), if (darkBoard(ctx)) 1 else 0) {
            prefs(ctx).edit().putBoolean("snake_dark", it == 1).apply()
        })
        box.addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
            setText(R.string.games_sound)
            textSize = 16f
            isChecked = GameSfx.enabled(ctx)
            setPadding(0, (12 * d).toInt(), 0, 0)
            setOnCheckedChangeListener { _, v -> GameSfx.setEnabled(ctx, v) }
        })
        box.addView(header(R.string.snake_ctrl_title))
        box.addView(group(intArrayOf(R.string.snake_ctrl_round, R.string.snake_ctrl_square, R.string.snake_ctrl_arrows, R.string.snake_ctrl_gestures), controlStyle(ctx)) {
            prefs(ctx).edit().putInt("snake_ctrl", it).apply()
        })
        com.stoyko.smessenger.ui.DialogTracker.add(
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.snake_settings)
                .setView(android.widget.ScrollView(ctx).apply { addView(box) })
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        ).show()
    }

    fun skinOf(ctx: Context): Skin = prefs(ctx).getString("snake_skin", "classic").let { id -> SKINS.firstOrNull { it.id == id } ?: SKINS[0] }

    private fun owned(ctx: Context, s: Skin) = s.price == 0 || s.id in prefs(ctx).getStringSet("snake_skins", emptySet()).orEmpty()

    /** Живой предпросмотр скина (для магазина). */
    fun skinPreview(ctx: Context, s: Skin): View = SkinPreview(ctx, s)

    /** Скин куплен (или бесплатный). */
    fun isOwned(ctx: Context, s: Skin) = owned(ctx, s)

    /** Открыть скин (покупка в магазине / предложение дня). */
    fun unlockSkin(ctx: Context, id: String) {
        val set = prefs(ctx).getStringSet("snake_skins", emptySet()).orEmpty().toMutableSet()
        set += id
        prefs(ctx).edit().putStringSet("snake_skins", set).apply()
    }

    /** Живой предпросмотр скина: змейка извивается, у неё открывается рот, искры/волна - как в игре. */
    @SuppressLint("ViewConstructor")
    private class SkinPreview(ctx: Context, val skin: Skin) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val start = SystemClock.uptimeMillis()
        private val fx = SnakeSkinFx()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val t = SystemClock.uptimeMillis() - start
            val n = 14
            val seg = (w * 0.78f) / n
            val pts = (0 until n).map { i ->
                val x = w * 0.86f - i * seg
                val y = h / 2 + kotlin.math.sin(i * 0.65 - t / 260.0).toFloat() * h * 0.16f
                x to y
            }
            val lite = com.stoyko.smessenger.ui.EnergySaver.on
            if (skin.fx != 0) {
                fx.begin(n)
                for (j in 0 until n) fx.pt(j, pts[j].first, pts[j].second)
                fx.under(c, skin, seg * 1.25f, t, lite)
            }
            for (i in n - 1 downTo 1) {
                body.color = skinColor(skin, i, n, t)
                body.strokeWidth = seg * (1.25f - 0.45f * i / n)
                c.drawLine(pts[i].first, pts[i].second, pts[i - 1].first, pts[i - 1].second, body)
                if ((skin.pattern == 3 || skin.pattern == 9) && i % 2 == 0) {
                    fill.color = 0xCCFFFFFF.toInt()
                    c.drawCircle(pts[i].first, pts[i].second - seg * 0.12f, seg * 0.14f, fill)
                }
            }
            if (skin.fx != 0) fx.over(c, skin, seg * 1.25f, t, lite)
            val (hx, hy) = pts[0]
            val r = seg * 0.75f
            fill.color = skin.head
            c.drawCircle(hx, hy, r, fill)
            val open = 18f + 22f * ((kotlin.math.sin(t / 220.0) + 1) / 2).toFloat()
            fill.color = 0xFF7F1D1D.toInt()
            c.drawArc(hx - r, hy - r, hx + r, hy + r, -open / 2, open, true, fill)
            fill.color = Color.WHITE
            c.drawCircle(hx - r * 0.1f, hy - r * 0.45f, r * 0.26f, fill)
            fill.color = 0xFF111827.toInt()
            c.drawCircle(hx - r * 0.02f, hy - r * 0.45f, r * 0.14f, fill)
            if (isAttachedToWindow) postInvalidateOnAnimation()
        }
    }

    /** Выбор скина: карточки с живым предпросмотром; свои - сразу, остальные покупаются за монеты. */
    fun showSkins(ctx: Context, onPick: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cols = if (ctx.resources.configuration.screenWidthDp >= 600) 3 else 2
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        fun card(sk: Skin): View {
            val cur = skinOf(ctx).id == sk.id
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = 18 * d
                    setColor(if (cur) 0x335B4BDB else 0x14000000)
                    if (cur) setStroke(dp(2), 0xFF5B4BDB.toInt())
                }
            }
            box.addView(SkinPreview(ctx, sk).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(0xFFAAD751.toInt())
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
            box.addView(TextView(ctx).apply {
                text = ctx.getString(sk.name)
                textSize = 14f
                setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
            box.addView(TextView(ctx).apply {
                text = when {
                    cur -> ctx.getString(R.string.snake_skin_current)
                    owned(ctx, sk) -> ctx.getString(R.string.snake_skin_owned)
                    else -> "🪙 ${sk.price}"
                }
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (owned(ctx, sk)) 0xFF059669.toInt() else 0xFFD97706.toInt())
            })
            box.setOnClickListener {
                if (!Btn.guard()) return@setOnClickListener
                if (!owned(ctx, sk)) {
                    if (!Coins.spend(ctx, sk.price)) {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.coins_not_enough, sk.price, Coins.balance(ctx)), android.widget.Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val set = prefs(ctx).getStringSet("snake_skins", emptySet()).orEmpty().toMutableSet()
                    set += sk.id
                    prefs(ctx).edit().putStringSet("snake_skins", set).apply()
                    CoinSound.play()
                }
                prefs(ctx).edit().putString("snake_skin", sk.id).apply()
                onPick()
                dialog?.dismiss()
            }
            return box
        }
        // Эксклюзивы видны в списке, только если уже куплены (продаются в акциях магазина).
        SKINS.filter { !it.exclusive || owned(ctx, it) }.chunked(cols).forEach { row ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, sk ->
                line.addView(card(sk), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                })
            }
            repeat(cols - row.size) { line.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.snake_skins_title, Coins.balance(ctx)))
            .setView(android.widget.ScrollView(ctx).apply { addView(grid) })
            .setNegativeButton(R.string.close, null)
            .show()
    }
}
