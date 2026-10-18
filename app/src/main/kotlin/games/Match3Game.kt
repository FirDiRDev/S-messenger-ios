// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.Coins
import com.stoyko.smessenger.ui.DialogTracker
import com.stoyko.smessenger.ui.EnergySaver
import com.stoyko.smessenger.ui.Kinetics
import com.stoyko.smessenger.ui.LiteMode
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * «Три в ряд» - сказочный лес, кристаллы, бонусы и монеты за победы.
 * Картинки кристаллов, бонусов, бустеров и деревянной рамки поля - из авторского макета.
 */
object Match3Game {
    private const val PREFS = "match3"
    /** Версия правил: при её росте старый прогресс, рекорды, сохранения и кэш удаляются (один раз). */
    private const val RULES_V = 2
    @Volatile private var migrated = false

    private fun prefs(ctx: Context): android.content.SharedPreferences {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!migrated) {
            migrated = true
            runCatching {
                if (p.getInt("rules_v", 0) < RULES_V) {
                    val had = p.all.keys.any { it.startsWith("stars_") || it == "game" || it == "unlocked" }
                    // Бустеры - это купленное за монеты, их сохраняем.
                    val keep = p.all.filterKeys { it.startsWith("boost_") || it == "boost_init" }
                    val e = p.edit().clear()
                    for ((k, v) in keep) if (v is Int) e.putInt(k, v) else if (v is Boolean) e.putBoolean(k, v)
                    e.putInt("rules_v", RULES_V).putBoolean("reset_note", had).commit()
                    val app = ctx.applicationContext
                    for (dir in listOf(app.cacheDir, app.filesDir)) {
                        dir.listFiles()?.filter { it.name.startsWith("match3") || it.name.startsWith("m3_") }?.forEach { it.deleteRecursively() }
                    }
                }
            }
        }
        return p
    }

    /** Последний открытый уровень. */
    fun unlocked(ctx: Context) = prefs(ctx).getInt("unlocked", 1)
    fun starsOf(ctx: Context, level: Int) = prefs(ctx).getInt("stars_$level", 0)
    fun totalStars(ctx: Context) = (1..unlocked(ctx)).sumOf { starsOf(ctx, it) }

    /** Незаконченный уровень (сохраняется при выходе и сворачивании). */
    fun saved(ctx: Context): JSONObject? = prefs(ctx).getString("game", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun deleteSave(ctx: Context) = prefs(ctx).edit().remove("game").commit()

    /** Лучший результат за один уровень (для профиля). */
    fun bestScore(ctx: Context): Int {
        val p = prefs(ctx)
        val stored = p.getInt("best_score", -1)
        if (stored >= 0) return stored
        // Ещё не сохранён одним числом - собираем из рекордов уровней.
        val best = p.all.filterKeys { it.startsWith("best_") }.values.maxOfOrNull { (it as? Int) ?: 0 } ?: 0
        p.edit().putInt("best_score", best).apply()
        return best
    }

    /** Рекорд уровня. */
    fun bestOf(ctx: Context, level: Int) = prefs(ctx).getInt("best_$level", 0)

    /** Записать итоговый счёт уровня (победа или поражение). true - новый рекорд уровня. */
    private fun recordScore(ctx: Context, level: Int, score: Int): Boolean {
        val p = prefs(ctx)
        val e = p.edit()
        val record = score > p.getInt("best_$level", 0)
        if (record) e.putInt("best_$level", score)
        if (score > p.getInt("best_score", 0)) e.putInt("best_score", score)
        e.commit()
        return record
    }

    /** Сбросить весь прогресс (уровни, звёзды, сохранение). Бустеры, монеты и лучший счёт остаются. */
    fun resetProgress(ctx: Context) {
        val p = prefs(ctx)
        val keep = p.all.filterKeys { it.startsWith("boost_") || it == "boost_init" || it == "best_score" || it.startsWith("intro_") }
        val e = p.edit().clear()
        for ((k, v) in keep) if (v is Int) e.putInt(k, v) else if (v is Boolean) e.putBoolean(k, v)
        e.commit()
    }

    // ---------- бустеры ----------
    const val B_HAMMER = 0
    const val B_SHUFFLE = 1
    const val B_WAND = 2
    const val B_BOMB = 3
    /** Цены бустеров в монетах (снижены вдвое: было 160, 100, 240, 200). */
    val BOOST_PRICE = intArrayOf(160, 100, 240, 200)
    private val BOOST_START = intArrayOf(3, 2, 1, 1)

    /** Цена докупки ходов после проигрыша (снижено вдвое: было 120). */
    const val MORE_MOVES_PRICE = 120

    /** Сколько ходов даётся за покупку. */
    const val MORE_MOVES = 10

    /** Сколько кристаллов собрать за уровень ради одного бустера в награду. */
    const val GEMS_PER_BOOST = 90

    fun boosts(ctx: Context, b: Int): Int {
        val p = prefs(ctx)
        if (!p.getBoolean("boost_init", false)) {
            val e = p.edit().putBoolean("boost_init", true)
            BOOST_START.forEachIndexed { i, n -> e.putInt("boost_$i", n) }
            e.commit()
        }
        return p.getInt("boost_$b", 0)
    }
    fun addBoost(ctx: Context, b: Int, n: Int) {
        val v = boosts(ctx, b) + n
        prefs(ctx).edit().putInt("boost_$b", v.coerceAtLeast(0)).commit()
    }

    /** Картинки (декодируются один раз). */
    internal class Art(private val ctx: Context) {
        private fun load(id: Int): Bitmap = BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false })
        // Порядок видов: красный, синий, зелёный, золотой, фиолетовый, пирит.
        // Кристаллы - прежние нарисованные камни (как на любимом скриншоте); «живыми» их делает
        // анимация на поле. Огранка кодом (GemArt) осталась как запасной вариант.
        val gems: Array<Bitmap> = arrayOf(
            load(R.drawable.m3_red), load(R.drawable.m3_blue), load(R.drawable.m3_green),
            load(R.drawable.m3_gold), load(R.drawable.m3_purple), load(R.drawable.m3_pyrite),
        )

        /** Белые силуэты кристаллов - для живого «блеска» на поле. */
        val glints: Array<Bitmap> = Array(6) { GemArt.silhouette(gems[it]) }
        val bomb = load(R.drawable.m3_bomb)
        val rainbow = load(R.drawable.m3_rainbow)
        val leaf = load(R.drawable.m3_leaf)
        val frame = load(R.drawable.m3_frame)
        val boosts = arrayOf(load(R.drawable.m3_hammer), load(R.drawable.m3_shuffle), load(R.drawable.m3_wand), load(R.drawable.m3_bomb_b))

        /** Значок цели «лёд»: голубой кубик с бликом (рисуется один раз). */
        val ice: Bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(8f, 8f, 88f, 88f, intArrayOf(0xFFF0F9FF.toInt(), 0xFF7DD3FC.toInt(), 0xFF0284C7.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(10f, 10f, 86f, 86f), 18f, 18f, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            p.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(RectF(10f, 10f, 86f, 86f), 18f, 18f, p)
            p.strokeWidth = 6f
            c.drawLine(24f, 40f, 40f, 24f, p)
            p.strokeWidth = 3f
            p.color = 0xCCFFFFFF.toInt()
            c.drawLine(52f, 48f, 72f, 62f, p)
            c.drawLine(52f, 48f, 46f, 72f, p)
        }

        /** Значок цели «золотые залежи»: каменная глыба с золотой жилой и самородками. */
        val gold: Bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = RectF(8f, 8f, 88f, 88f)
            p.shader = LinearGradient(0f, 8f, 0f, 88f, intArrayOf(0xFF8D7B62.toInt(), 0xFF57483A.toInt(), 0xFF2A221C.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 16f, 16f, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            p.color = 0xFFE9B949.toInt()
            c.drawRoundRect(r, 16f, 16f, p)
            p.strokeWidth = 5f
            p.color = 0xFFFACC15.toInt()
            c.drawLine(16f, 54f, 44f, 44f, p)
            c.drawLine(44f, 44f, 62f, 56f, p)
            c.drawLine(62f, 56f, 82f, 40f, p)
            p.style = Paint.Style.FILL
            for ((x, y) in listOf(30f to 28f, 66f to 30f, 48f to 70f)) {
                p.shader = RadialGradient(x - 3f, y - 3f, 11f, intArrayOf(0xFFFFF7C2.toInt(), 0xFFFACC15.toInt(), 0xFFB45309.toInt()), null, Shader.TileMode.CLAMP)
                c.drawCircle(x, y, 11f, p)
            }
        }

        /** Значок цели «очки»: золотая звезда. */
        val star: Bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val path = Path()
            for (k in 0 until 10) {
                val a = Math.PI / 2 * 3 + k * Math.PI / 5
                val rr = if (k % 2 == 0) 44.0 else 20.0
                val px = (48 + rr * cos(a)).toFloat()
                val py = (50 + rr * sin(a)).toFloat()
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            p.shader = LinearGradient(0f, 6f, 0f, 92f, 0xFFFFF59D.toInt(), 0xFFF59E0B.toInt(), Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            p.color = 0xFF92400E.toInt()
            c.drawPath(path, p)
        }
    }

    /** Цвета частиц для каждого вида кристалла. */
    internal val SPARK = intArrayOf(0xFFFF4D6D.toInt(), 0xFF4D8DFF.toInt(), 0xFF3DDC84.toInt(), 0xFFFFC94D.toInt(), 0xFFB57BFF.toInt(), 0xFFC9C28A.toInt())

    private var art: Art? = null
    internal fun art(ctx: Context): Art = art ?: Art(ctx.applicationContext).also { art = it }

    // ---------- карта уровней ----------

    fun show(ctx: Context) {
        runCatching {
            val p = prefs(ctx)
            if (p.getBoolean("reset_note", false)) {
                p.edit().remove("reset_note").apply()
                android.widget.Toast.makeText(ctx, R.string.m3v3_reset_note, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = DialogTracker.add(Dialog(ctx, R.style.AppTheme_FullDialog))
        val frame = FrameLayout(ctx)
        frame.addView(ForestView(ctx), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(12), dp(6))
        }
        bar.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { dialog.safeDismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(goldTitle(ctx, ctx.getString(R.string.m3_title), 22f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val starsTv = TextView(ctx).apply {
            textSize = 16f
            setTextColor(0xFFFDE68A.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        bar.addView(starsTv)
        bar.addView(soundButton(ctx), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        bar.addView(helpButton(ctx), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })
        GameSfx.prepare(ctx)
        // Первый запуск - сразу правила.
        if (!prefs(ctx).getBoolean("help_seen", false)) {
            prefs(ctx).edit().putBoolean("help_seen", true).apply()
            frame.post { help(ctx) }
        }
        col.addView(bar)
        val grid = android.widget.GridLayout(ctx).apply {
            columnCount = 5
            setPadding(dp(10), dp(6), dp(10), dp(24))
        }
        val scroll = ScrollView(ctx).apply { addView(grid) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val resume = saved(ctx)
        fun build() {
            grid.removeAllViews()
            val un = unlocked(ctx)
            starsTv.text = "★ " + totalStars(ctx)
            // Карта: 100 уровней сразу (и всегда +20 впереди открытого) - уровни бесконечные.
            val count = maxOf(un + 20, 100)
            val cellW = (ctx.resources.displayMetrics.widthPixels - dp(20)) / 5
            for (lv in 1..count) {
                val open = lv <= un
                val st = starsOf(ctx, lv)
                grid.addView(LevelBadge(ctx, lv, open, st, lv == un).apply {
                    if (open) {
                        setOnClickListener {
                            val s = saved(ctx)
                            if (s != null && s.optInt("level") == lv) {
                                play(ctx, lv, s) { build() }
                            } else {
                                play(ctx, lv, null) { build() }
                            }
                        }
                    }
                }, android.widget.GridLayout.LayoutParams().apply {
                    width = cellW
                    height = (cellW * 1.05f).toInt()
                })
            }
            scroll.post { scroll.smoothScrollTo(0, ((un - 1) / 5 * cellW * 1.05f).toInt() - scroll.height / 3) }
        }
        build()
        // Кнопки: продолжить незаконченный уровень, начать заново.
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(6), dp(14), dp(14))
        }
        if (resume != null) {
            row.addView(com.stoyko.smessenger.ui.Btn.nice(ctx, ctx.getString(R.string.m3_continue, resume.optInt("level")), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) {
                saved(ctx)?.let { s -> play(ctx, s.optInt("level"), s) { build() } }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        } else {
            row.addView(com.stoyko.smessenger.ui.Btn.nice(ctx, ctx.getString(R.string.m3_play_level, unlocked(ctx)), intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) {
                play(ctx, unlocked(ctx), null) { build() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        col.addView(row)
        col.fitSystemBars()
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setContentView(frame)
        dialog.show()
    }

    private fun goldTitle(ctx: Context, text: String, size: Float) = object : TextView(ctx) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFFFFF4C2.toInt(), 0xFFF6C344.toInt(), 0xFFB7791F.toInt()), null, Shader.TileMode.CLAMP)
        }
    }.apply {
        this.text = text
        textSize = size
        setTypeface(Typeface.DEFAULT_BOLD)
        setShadowLayer(4f, 0f, 2f, 0xAA3B1F0A.toInt())
    }

    /** Медальон уровня на карте: номер, звёзды, замок. */
    @SuppressLint("ViewConstructor")
    private class LevelBadge(ctx: Context, val lv: Int, val open: Boolean, val stars: Int, val current: Boolean) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val starPath = Path()
        private val start = SystemClock.uptimeMillis()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2
            val cy = h * 0.44f
            val r = min(w, h) * 0.33f
            val now = SystemClock.uptimeMillis()
            if (current) {
                val pulse = 0.5f + 0.5f * sin((now - start) / 300f)
                p.shader = RadialGradient(cx, cy, r * 1.6f, (0xFFFDE047.toInt() and 0x00FFFFFF) or ((120 * pulse).toInt() shl 24), 0x00FDE047, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r * 1.6f, p)
                if (Kinetics.enabled && !EnergySaver.on) postInvalidateDelayed(LiteMode.liveFrameMs)
            }
            p.shader = if (open) {
                LinearGradient(0f, cy - r, 0f, cy + r, intArrayOf(0xFFE9B872.toInt(), 0xFF9C5B2E.toInt(), 0xFF6B3A1C.toInt()), null, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, cy - r, 0f, cy + r, 0xFF4B5563.toInt(), 0xFF1F2937.toInt(), Shader.TileMode.CLAMP)
            }
            c.drawCircle(cx, cy, r, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.color = if (open) 0xFFF6C344.toInt() else 0xFF6B7280.toInt()
            c.drawCircle(cx, cy, r - 1.5f * d, p)
            p.style = Paint.Style.FILL
            t.textSize = r * (if (lv >= 100) 0.7f else 0.85f)
            t.color = if (open) 0xFFFFF7E0.toInt() else 0xFF9CA3AF.toInt()
            t.setShadowLayer(3f, 0f, 2f, 0x99000000.toInt())
            c.drawText(if (open) lv.toString() else "🔒", cx, cy + t.textSize * 0.35f, t)
            if (open) {
                for (k in 0 until 3) {
                    val sx = cx + (k - 1) * r * 0.62f
                    val sy = cy + r * 1.05f - (if (k == 1) r * 0.12f else 0f)
                    star(c, sx, sy, r * 0.3f, k < stars)
                }
            }
        }

        private fun star(c: Canvas, x: Float, y: Float, r: Float, on: Boolean) {
            starPath.reset()
            for (k in 0 until 10) {
                val a = Math.PI / 2 * 3 + k * Math.PI / 5
                val rr = if (k % 2 == 0) r else r * 0.45f
                val px = x + (rr * cos(a)).toFloat()
                val py = y + (rr * sin(a)).toFloat()
                if (k == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
            }
            starPath.close()
            p.color = if (on) 0xFFFDE047.toInt() else 0x66000000
            c.drawPath(starPath, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.2f * d
            p.color = if (on) 0xFFB45309.toInt() else 0x55FFFFFF
            c.drawPath(starPath, p)
            p.style = Paint.Style.FILL
        }
    }

    // ---------- уровень ----------

    fun play(ctx: Context, level: Int, saved: JSONObject?, onClose: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val engine = saved?.let { Match3Engine.fromJson(it) }?.takeIf { it.level == level } ?: Match3Engine(level)
        val dialog = DialogTracker.add(Dialog(ctx, R.style.AppTheme_FullDialog))
        val frame = FrameLayout(ctx)
        frame.addView(ForestView(ctx), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // Шапка: назад + золотая табличка уровня.
        val top = FrameLayout(ctx)
        top.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { dialog.safeDismiss() }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(LevelPlate(ctx, ctx.getString(R.string.m3_level, level)), FrameLayout.LayoutParams(dp(200), dp(46), Gravity.CENTER))
        top.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(soundButton(ctx), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(helpButton(ctx), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) })
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(6) })
        GameSfx.prepare(ctx)
        col.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        val hud = HudView(ctx, engine)
        col.addView(hud, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)).apply { setMargins(dp(8), dp(2), dp(8), 0) })
        val starBar = StarBar(ctx, engine)
        col.addView(starBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { setMargins(dp(36), dp(4), dp(36), 0) })
        val board = Match3Board(ctx, engine)
        col.addView(board, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(dp(4), dp(4), dp(4), 0) })
        // Бустеры.
        val boostRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x66311A0C, 0xCC3B2210.toInt()))
        }
        val boostViews = ArrayList<BoostButton>()
        val prefsKey = "game"
        fun persist() {
            if (engine.moves > 0 && !engine.goalsDone) {
                prefs(ctx).edit().putString(prefsKey, engine.toJson().toString()).commit()
            }
        }
        fun refreshBoosts() = boostViews.forEach { it.count = boosts(ctx, it.kind) }
        for (b in 0 until 4) {
            val bv = BoostButton(ctx, b)
            bv.count = boosts(ctx, b)
            bv.setOnClickListener {
                if (board.busy) return@setOnClickListener
                if (boosts(ctx, b) <= 0) {
                    // Купить за монеты.
                    val price = BOOST_PRICE[b]
                    DialogTracker.add(
                        AlertDialog.Builder(ctx)
                            .setTitle(ctx.getString(BOOST_NAMES[b]))
                            .setMessage(ctx.getString(R.string.m3_buy_boost, price, Coins.balance(ctx)))
                            .setPositiveButton(R.string.m3_buy) { _, _ ->
                                if (Coins.spend(ctx, price)) {
                                    GameSfx.play(ctx, GameSfx.S.BOOST)
                                    addBoost(ctx, b, 1)
                                    refreshBoosts()
                                    runCatching { CoinSound.play() }
                                } else {
                                    Toast.makeText(ctx, R.string.m3_no_coins, Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .create(),
                    ).show()
                    return@setOnClickListener
                }
                if (b == B_SHUFFLE) {
                    addBoost(ctx, b, -1)
                    refreshBoosts()
                    board.doShuffle()
                    return@setOnClickListener
                }
                val now = if (board.boostMode == b) -1 else b
                board.boostMode = now
                boostViews.forEach { it.active = it.kind == now }
                if (now >= 0) board.flash(ctx.getString(R.string.m3_pick_gem))
            }
            boostViews += bv
            boostRow.addView(bv, LinearLayout.LayoutParams(0, dp(82), 1f))
        }
        board.onBoostUsed = { b ->
            addBoost(ctx, b, -1)
            refreshBoosts()
            boostViews.forEach { it.active = false }
        }
        col.addView(boostRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.fitSystemBars()
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        board.onChanged = {
            hud.invalidate()
            starBar.invalidate()
        }
        board.onSettled = settled@{
            persist()
            when {
                engine.goalsDone -> {
                    prefs(ctx).edit().remove(prefsKey).commit()
                    // Оставшиеся ходы - в очки; итог (и звёзды) считаются уже с ними.
                    val bonus = engine.finish()
                    board.showEndBonus(bonus)
                    hud.invalidate()
                    starBar.invalidate()
                    val show = Runnable {
                        win(ctx, engine) { next ->
                            dialog.safeDismiss()
                            if (next) play(ctx, level + 1, null, onClose)
                        }
                    }
                    if (bonus > 0 && !LiteMode.on) board.postDelayed(show, 900) else show.run()
                }
                engine.moves <= 0 -> lose(ctx, engine, onMoreMoves = {
                    engine.moves += MORE_MOVES
                    engine.extraMoves += MORE_MOVES
                    hud.invalidate()
                    persist()
                    board.movesAdded()
                }, onRetry = {
                    prefs(ctx).edit().remove(prefsKey).commit()
                    dialog.safeDismiss()
                    play(ctx, level, null, onClose)
                }, onExit = {
                    prefs(ctx).edit().remove(prefsKey).commit()
                    dialog.safeDismiss()
                })
            }
        }
        val saver = Any()
        com.stoyko.smessenger.ui.BackgroundSaver.register(saver) { persist() }
        dialog.setOnDismissListener {
            com.stoyko.smessenger.ui.BackgroundSaver.unregister(saver)
            persist()
            board.stop()
            onClose()
        }
        dialog.setContentView(frame)
        dialog.show()
        board.intro()
        if (saved == null) introduce(ctx, engine)
    }

    /** Порядок показа и тексты вступлений к новым помехам. */
    private val INTROS = listOf(
        Match3Engine.F_HOLES to R.string.m3v2_intro_holes,
        Match3Engine.F_LEAF to R.string.m3v2_intro_leaf,
        Match3Engine.F_LEAF2 to R.string.m3v2_intro_leaf2,
        Match3Engine.F_ICE to R.string.m3v2_intro_ice,
        Match3Engine.F_ICE2 to R.string.m3v2_intro_ice2,
        Match3Engine.F_GOLD to R.string.m3v2_intro_gold,
        Match3Engine.F_GOLD2 to R.string.m3v2_intro_gold2,
        Match3Engine.F_SCORE to R.string.m3v2_intro_score,
    )

    /** Первая встреча с помехой - короткое объяснение (один раз для каждой). */
    private fun introduce(ctx: Context, e: Match3Engine) {
        val p = prefs(ctx)
        // Листья и лёд были и до вступлений: кто уже прошёл уровни с ними, тому не объясняем.
        val un = unlocked(ctx)
        val known = mapOf(Match3Engine.F_LEAF to 3, Match3Engine.F_LEAF2 to 8, Match3Engine.F_ICE to 6, Match3Engine.F_ICE2 to 15)
        val fresh = INTROS.filter { (f, _) -> f in e.features && !p.getBoolean("intro_$f", false) && un <= (known[f] ?: Int.MAX_VALUE) }
        if (fresh.isEmpty()) return
        val ed = p.edit()
        for ((f, _) in fresh) ed.putBoolean("intro_$f", true)
        ed.apply()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val a = art(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF8A5530.toInt(), 0xFF5B3418.toInt())).apply {
                cornerRadius = 24 * d
                setStroke(dp(3), 0xFFF6C344.toInt())
            }
        }
        box.addView(goldTitle(ctx, ctx.getString(R.string.m3v2_intro_title), 22f).apply { gravity = Gravity.CENTER })
        for ((f, text) in fresh) {
            val icon = when (f) {
                Match3Engine.F_LEAF, Match3Engine.F_LEAF2 -> a.leaf
                Match3Engine.F_ICE, Match3Engine.F_ICE2 -> a.ice
                Match3Engine.F_GOLD, Match3Engine.F_GOLD2 -> a.gold
                Match3Engine.F_SCORE -> a.star
                else -> a.frame
            }
            box.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
                addView(ImageView(ctx).apply { setImageBitmap(icon) }, LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(TextView(ctx).apply {
                    setText(text)
                    textSize = 15f
                    setTextColor(0xFFFFF4DA.toInt())
                    setPadding(dp(12), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        val dlg = DialogTracker.add(Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar))
        val wrap = FrameLayout(ctx).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { dlg.safeDismiss() }
        }
        box.addView(com.stoyko.smessenger.ui.Btn.nice(ctx, ctx.getString(R.string.m3_help_ok), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) { dlg.safeDismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(16) })
        box.isClickable = true
        wrap.addView(box, FrameLayout.LayoutParams(minOf(dp(340), ctx.resources.displayMetrics.widthPixels - dp(24)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        dlg.setContentView(wrap)
        dlg.show()
        if (!LiteMode.on) runCatching { Kinetics.springIn(box) }
    }

    /** Кнопка звука 🔊/🔇 (общая для игр). */
    internal fun soundButton(ctx: Context): TextView = TextView(ctx).apply {
        fun label() {
            text = if (GameSfx.enabled(ctx)) "🔊" else "🔇"
        }
        label()
        textSize = 20f
        gravity = Gravity.CENTER
        contentDescription = ctx.getString(R.string.games_sound)
        setOnClickListener {
            GameSfx.setEnabled(ctx, !GameSfx.enabled(ctx))
            label()
            GameSfx.play(ctx, GameSfx.S.TICK)
        }
    }

    private fun helpButton(ctx: Context): TextView = TextView(ctx).apply {
        text = "?"
        textSize = 20f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(0xFF5B2C0A.toInt())
        gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFFFE9A8.toInt(), 0xFFF0B43C.toInt())).apply {
            shape = GradientDrawable.OVAL
            setStroke((2 * ctx.resources.displayMetrics.density).toInt(), 0xFF8A4B12.toInt())
        }
        contentDescription = ctx.getString(R.string.m3_help_title)
        setOnClickListener { help(ctx) }
    }

    /** «Как играть»: правила, фигуры и бонусы с картинками кристаллов, помехи, бустеры, награды. */
    fun help(ctx: Context) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val a = art(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
        }
        box.addView(goldTitle(ctx, ctx.getString(R.string.m3_help_title), 22f).apply { gravity = Gravity.CENTER })
        fun section(title: Int) = box.addView(TextView(ctx).apply {
            setText(title)
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(0xFFFDE68A.toInt())
            setPadding(0, dp(14), 0, dp(4))
        })
        fun line(icons: List<Bitmap>, text: Int) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (b in icons) strip.addView(ImageView(ctx).apply { setImageBitmap(b) }, LinearLayout.LayoutParams(dp(26), dp(26)))
            row.addView(strip, LinearLayout.LayoutParams(dp(26) * maxOf(1, icons.size).coerceAtMost(4), dp(26)))
            row.addView(TextView(ctx).apply {
                setText(text)
                textSize = 14f
                setTextColor(0xFFFFF4DA.toInt())
                setPadding(dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row)
        }
        val g = a.gems
        box.addView(TextView(ctx).apply {
            setText(R.string.m3_help_intro)
            textSize = 14f
            setTextColor(0xFFFFF4DA.toInt())
            setPadding(0, dp(8), 0, 0)
        })
        section(R.string.m3_help_shapes)
        line(listOf(g[0], g[0], g[0]), R.string.m3_help_row3)
        line(listOf(g[1], g[1], g[1], g[1]), R.string.m3_help_row4)
        line(listOf(g[2], g[2], a.bomb), R.string.m3_help_square)
        line(listOf(g[3], g[3], a.bomb), R.string.m3_help_corner)
        line(listOf(g[4], g[4], a.rainbow), R.string.m3_help_big)
        line(listOf(g[5], g[5], a.rainbow), R.string.m3_help_row5)
        section(R.string.m3_help_bonuses)
        line(listOf(a.bomb), R.string.m3_help_use_bonus)
        line(listOf(a.bomb, a.rainbow), R.string.m3_help_combo)
        section(R.string.m3_help_obstacles)
        line(listOf(a.leaf), R.string.m3_help_leaf)
        line(listOf(a.ice), R.string.m3_help_ice)
        line(listOf(a.gold), R.string.m3v2_help_gold)
        line(listOf(a.frame), R.string.m3v2_help_holes)
        section(R.string.m3_help_boosters)
        line(listOf(a.boosts[0]), R.string.m3_help_hammer)
        line(listOf(a.boosts[1]), R.string.m3_help_shuffle)
        line(listOf(a.boosts[2]), R.string.m3_help_wand)
        line(listOf(a.boosts[3]), R.string.m3_help_bomb)
        section(R.string.m3_help_rewards)
        box.addView(TextView(ctx).apply {
            setText(R.string.m3_help_rewards_text)
            textSize = 14f
            setTextColor(0xFFFFF4DA.toInt())
        })
        val dlg = DialogTracker.add(Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar))
        val wrap = FrameLayout(ctx).apply {
            setBackgroundColor(0xAA000000.toInt())
            setOnClickListener { dlg.safeDismiss() }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF6B4423.toInt(), 0xFF3B2210.toInt())).apply {
                cornerRadius = 24 * d
                setStroke(dp(3), 0xFFF6C344.toInt())
            }
            isClickable = true
        }
        card.addView(ScrollView(ctx).apply { addView(box) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(com.stoyko.smessenger.ui.Btn.nice(ctx, ctx.getString(R.string.m3_help_ok), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) { dlg.safeDismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { setMargins(dp(16), dp(6), dp(16), dp(14)) })
        val h = (ctx.resources.displayMetrics.heightPixels * 0.82f).toInt()
        wrap.addView(card, FrameLayout.LayoutParams(minOf(dp(380), ctx.resources.displayMetrics.widthPixels - dp(24)), h, Gravity.CENTER))
        dlg.setContentView(wrap)
        dlg.show()
        runCatching { Kinetics.springIn(card) }
    }

    private val BOOST_NAMES = intArrayOf(R.string.m3_boost_hammer, R.string.m3_boost_shuffle, R.string.m3_boost_wand, R.string.m3_boost_bomb)

    /** Победа: звёзды по одной, монеты и «дальше». */
    private fun win(ctx: Context, e: Match3Engine, done: (next: Boolean) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val stars = e.stars.coerceAtLeast(1)
        val record = recordScore(ctx, e.level, e.score)
        runCatching { com.stoyko.smessenger.games.stats.GameStats.match3(ctx, true) }
        val p = prefs(ctx)
        val first = p.getInt("stars_${e.level}", 0) == 0
        val prevStars = p.getInt("stars_${e.level}", 0)
        // Монеты: за победу 10 + 5 за звезду; за первую победу на уровне +15;
        // за каждую новую звезду (лучше прежнего) +5.
        // Каждый 10-й уровень (впервые) - сундук: +50 монет.
        // Чем больше кристаллов собрано - тем больше монет (1 за каждые 20, до 40).
        val gemCoins = (e.gemsCollected / 20).coerceAtMost(40)
        val coins = 10 + stars * 5 + (if (first) 15 else 0) + (stars - prevStars).coerceAtLeast(0) * 5 +
            (if (first && e.level % 10 == 0) 50 else 0) + gemCoins
        p.edit()
            .putInt("stars_${e.level}", maxOf(prevStars, stars))
            .putInt("unlocked", maxOf(p.getInt("unlocked", 1), e.level + 1))
            .commit()
        Coins.add(ctx, coins)
        // Бонусы за победу: 3 звезды - случайный бустер; каждый 5-й уровень (впервые) - ещё один.
        val gifts = ArrayList<Int>()
        if (stars >= 3) gifts += (0..3).random()
        if (first && e.level % 5 == 0) gifts += (0..3).random()
        // За каждые 90 собранных кристаллов - бустер (до трёх): молот, бомба, палочка или перемешивание.
        repeat((e.gemsCollected / GEMS_PER_BOOST).coerceAtMost(3)) { gifts += listOf(B_HAMMER, B_BOMB, B_WAND, B_SHUFFLE, B_HAMMER, B_BOMB).random() }
        for (b in gifts) addBoost(ctx, b, 1)
        GameSfx.play(ctx, GameSfx.S.WIN)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { CoinSound.jackpot(coins / 5) } }, 900)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF8A5530.toInt(), 0xFF5B3418.toInt())).apply {
                cornerRadius = 26 * d
                setStroke(dp(3), 0xFFF6C344.toInt())
            }
        }
        box.addView(goldTitle(ctx, ctx.getString(R.string.m3_win), 26f).apply { gravity = Gravity.CENTER })
        box.addView(WinStars(ctx, stars), LinearLayout.LayoutParams(dp(240), dp(90)))
        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.m3v2_win_text, fmt(e.score), e.endBonus / Match3Engine.MOVE_BONUS, fmt(e.endBonus), coins)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFF4DA.toInt())
        })
        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.m3v3_moves_used, (e.totalMoves + e.extraMoves - e.moves).coerceAtLeast(0), e.totalMoves + e.extraMoves) +
                "\n" + ctx.getString(R.string.m3v3_gems, e.gemsCollected, gemCoins)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFF4DA.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        box.addView(TextView(ctx).apply {
            text = if (record) ctx.getString(R.string.m3v2_new_best) else ctx.getString(R.string.m3v2_best, fmt(bestOf(ctx, e.level)))
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (record) 0xFFFDE047.toInt() else 0xFFFDE68A.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        if (gifts.isNotEmpty()) {
            val names = intArrayOf(R.string.m3_boost_hammer, R.string.m3_boost_shuffle, R.string.m3_boost_wand, R.string.m3_boost_bomb)
            box.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.m3_bonus_boosters, gifts.joinToString(", ") { ctx.getString(names[it]) })
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFFFDE68A.toInt())
                setPadding(0, dp(6), 0, 0)
            })
        }
        val dlg = DialogTracker.add(Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar))
        val wrap = FrameLayout(ctx).apply { setBackgroundColor(0x99000000.toInt()) }
        val btns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        btns.addView(com.stoyko.smessenger.ui.Btn.nice(ctx, ctx.getString(R.string.m3_to_map), intArrayOf(0xFF60A5FA.toInt(), 0xFF2563EB.toInt())) {
            dlg.safeDismiss()
            done(false)
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        btns.addView(com.stoyko.smessenger.ui.Btn.nice(ctx, ctx.getString(R.string.m3_next), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) {
            dlg.safeDismiss()
            done(true)
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(10) })
        box.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.addView(box, FrameLayout.LayoutParams(dp(310), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        dlg.setContentView(wrap)
        dlg.setCancelable(false)
        dlg.show()
        if (!LiteMode.on) runCatching { Kinetics.springIn(box) }
    }

    /** Число с пробелами между разрядами: 12 345. */
    internal fun fmt(n: Int): String {
        val s = kotlin.math.abs(n).toString()
        val sb = StringBuilder()
        for ((k, ch) in s.withIndex()) {
            if (k > 0 && (s.length - k) % 3 == 0) sb.append('\u00A0')
            sb.append(ch)
        }
        return (if (n < 0) "-" else "") + sb
    }

    private fun lose(ctx: Context, e: Match3Engine, onMoreMoves: () -> Unit, onRetry: () -> Unit, onExit: () -> Unit) {
        val price = MORE_MOVES_PRICE
        recordScore(ctx, e.level, e.score)
        runCatching { com.stoyko.smessenger.games.stats.GameStats.match3(ctx, false) }
        GameSfx.play(ctx, GameSfx.S.LOSE)
        DialogTracker.add(
            AlertDialog.Builder(ctx)
                .setTitle(R.string.m3_lose)
                .setMessage(ctx.getString(R.string.m3_lose_text, price, Coins.balance(ctx)))
                .setCancelable(false)
                .setPositiveButton(ctx.getString(R.string.m3_more_moves, price)) { _, _ ->
                    if (Coins.spend(ctx, price)) onMoreMoves() else {
                        Toast.makeText(ctx, R.string.m3_no_coins, Toast.LENGTH_SHORT).show()
                        lose(ctx, e, onMoreMoves, onRetry, onExit)
                    }
                }
                .setNeutralButton(R.string.m3_retry) { _, _ -> onRetry() }
                .setNegativeButton(R.string.m3_to_map) { _, _ -> onExit() }
                .create(),
        ).show()
    }

    // ---------- элементы экрана ----------

    /** Золотая табличка «Уровень N» с завитками. */
    @SuppressLint("ViewConstructor")
    private class LevelPlate(ctx: Context, val text: String) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = RectF(h * 0.35f, 3 * d, w - h * 0.35f, h - 3 * d)
            p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFFFFE9A8.toInt(), 0xFFF0B43C.toInt(), 0xFFB7791F.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, h * 0.3f, h * 0.3f, p)
            // Ромбики по краям.
            val path = Path()
            for (sx in floatArrayOf(r.left, r.right)) {
                path.reset()
                path.moveTo(sx - h * 0.3f, h / 2)
                path.lineTo(sx, h * 0.18f)
                path.lineTo(sx + h * 0.3f, h / 2)
                path.lineTo(sx, h * 0.82f)
                path.close()
                c.drawPath(path, p)
            }
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = 0xFF8A4B12.toInt()
            c.drawRoundRect(RectF(r.left + 4 * d, r.top + 4 * d, r.right - 4 * d, r.bottom - 4 * d), h * 0.22f, h * 0.22f, p)
            p.style = Paint.Style.FILL
            t.textSize = h * 0.48f
            t.color = 0xFF5B2C0A.toInt()
            t.setShadowLayer(1.5f * d, 0f, 1f * d, 0x88FFF4C2.toInt())
            c.drawText(text, w / 2, h / 2 + t.textSize * 0.35f, t)
        }
    }

    /** Деревянная панель: цели слева, круг с ходами по центру, счёт справа. */
    @SuppressLint("ViewConstructor")
    private class HudView(ctx: Context, val e: Match3Engine) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val art = art(ctx)
        private val dst = RectF()

        // Счёт «бежит» от прежнего значения к новому за [TWEEN] мс и всегда останавливается
        // ровно на e.score (в лёгком режиме - сразу).
        private var fromScore = e.score
        private var toScore = e.score
        private var tweenAt = 0L
        private var lastMoves = e.moves
        private var movesPopAt = 0L

        private fun wood(c: Canvas, r: RectF) {
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xFFF5DEB3.toInt(), 0xFFE2BC86.toInt(), 0xFFC99A5E.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 14 * d, 14 * d, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.color = 0xFF7A4520.toInt()
            c.drawRoundRect(r, 14 * d, 14 * d, p)
            p.strokeWidth = 1.2f * d
            p.color = 0x66FFFFFF
            c.drawRoundRect(RectF(r.left + 4 * d, r.top + 4 * d, r.right - 4 * d, r.bottom - 4 * d), 10 * d, 10 * d, p)
            p.style = Paint.Style.FILL
        }

        private fun tag(c: Canvas, cx: Float, y: Float, s: String) {
            t.textSize = 11.5f * d
            val w = t.measureText(s) / 2 + 12 * d
            p.shader = LinearGradient(0f, y - 10 * d, 0f, y + 10 * d, 0xFF8A4B23.toInt(), 0xFF5B2C12.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(cx - w, y - 10 * d, cx + w, y + 10 * d), 10 * d, 10 * d, p)
            p.shader = null
            t.color = 0xFFFDE7B0.toInt()
            c.drawText(s, cx, y + 4 * d, t)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val now = SystemClock.uptimeMillis()
            val circleR = h * 0.46f
            val cx = w / 2
            val left = RectF(2 * d, h * 0.16f, cx - circleR * 0.75f, h - 4 * d)
            val right = RectF(cx + circleR * 0.75f, h * 0.16f, w - 2 * d, h - 4 * d)
            wood(c, left)
            wood(c, right)
            tag(c, left.centerX() - circleR * 0.1f, h * 0.16f, context.getString(R.string.m3_goals))
            tag(c, right.centerX() + circleR * 0.1f, h * 0.16f, context.getString(R.string.m3_score))
            // Цели: сколько ещё осталось (листья, лёд и залежи - ровно столько слоёв на поле).
            val n = e.goalKind.size.coerceAtLeast(1)
            val slotW = (left.width() - circleR * 0.3f) / n
            for (g in 0 until e.goalKind.size) {
                val gx = left.left + 6 * d + slotW * (g + 0.5f)
                val k = e.goalKind[g]
                val icon = when (k) {
                    Match3Engine.LEAF_GOAL -> art.leaf
                    Match3Engine.ICE_GOAL -> art.ice
                    Match3Engine.GOLD_GOAL -> art.gold
                    Match3Engine.SCORE_GOAL -> art.star
                    else -> art.gems[k.coerceIn(0, art.gems.size - 1)]
                }
                val s = min(slotW * 0.62f, left.height() * 0.48f)
                val gy = left.top + left.height() * 0.42f
                dst.set(gx - s / 2, gy - s / 2, gx + s / 2, gy + s / 2)
                c.drawBitmap(icon, null, dst, p)
                val leftN = e.goalLeft(g)
                val done = leftN <= 0
                val label = when {
                    done -> "✓"
                    k == Match3Engine.SCORE_GOAL -> short(leftN)
                    else -> leftN.toString()
                }
                t.textSize = min(13 * d, slotW * 0.42f)
                val tw = t.measureText(label)
                if (tw > slotW * 0.95f) t.textSize *= slotW * 0.95f / tw
                t.color = if (done) 0xFF15803D.toInt() else 0xFF4A2410.toInt()
                c.drawText(label, gx, left.bottom - 8 * d, t)
            }
            // Счёт.
            if (e.score != toScore) {
                fromScore = shownScore(now)
                toScore = e.score
                tweenAt = now
            }
            val shown = shownScore(now)
            if (shown != toScore) postInvalidateOnAnimation()
            val scoreText = fmt(shown)
            t.textSize = min(right.height() * 0.4f, right.width() / 5.2f)
            val sw = t.measureText(scoreText)
            val room = right.width() - circleR * 0.5f
            if (sw > room) t.textSize *= room / sw
            t.color = 0xFF4A2410.toInt()
            c.drawText(scoreText, right.centerX() + circleR * 0.12f, right.centerY() + t.textSize * 0.48f, t)
            // Круг с ходами: серебряное кольцо, дерево внутри.
            if (e.moves != lastMoves) {
                lastMoves = e.moves
                movesPopAt = now
            }
            val pop = ((now - movesPopAt) / 260f).coerceIn(0f, 1f)
            val sc = 1f + 0.18f * sin(pop * Math.PI.toFloat())
            if (pop < 1f) postInvalidateOnAnimation()
            val cy = h / 2
            p.shader = LinearGradient(cx - circleR, cy - circleR, cx + circleR, cy + circleR, intArrayOf(0xFFF8FAFC.toInt(), 0xFF94A3B8.toInt(), 0xFFE2E8F0.toInt(), 0xFF64748B.toInt()), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, circleR, p)
            p.shader = RadialGradient(cx, cy - circleR * 0.3f, circleR, intArrayOf(0xFFB97A45.toInt(), 0xFF7A4520.toInt(), 0xFF4A2410.toInt()), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, circleR * 0.82f, p)
            p.shader = null
            val low = e.moves <= 5
            t.textSize = circleR * 0.82f * sc
            t.color = if (low) 0xFFFF8A80.toInt() else 0xFFFFF1C9.toInt()
            t.setShadowLayer(3 * d, 0f, 2 * d, 0xAA000000.toInt())
            c.drawText(e.moves.toString(), cx, cy + circleR * 0.2f, t)
            t.clearShadowLayer()
            t.textSize = circleR * 0.24f
            t.color = 0xFFFDE7B0.toInt()
            c.drawText(context.getString(R.string.m3_moves), cx, cy + circleR * 0.58f, t)
            if (low && Kinetics.enabled && !EnergySaver.on && !LiteMode.on) postInvalidateDelayed(60)
        }

        private fun shownScore(now: Long): Int {
            if (LiteMode.on || !Kinetics.enabled) return toScore
            val k = ((now - tweenAt) / TWEEN).coerceIn(0f, 1f)
            if (k >= 1f) return toScore
            val ease = 1 - (1 - k) * (1 - k) * (1 - k)
            return fromScore + ((toScore - fromScore) * ease).toInt()
        }

        /** 12 400 → «12.4k» для цели по очкам. */
        private fun short(v: Int): String = if (v >= 10000) "${v / 1000}k" else if (v >= 1000) "%.1fk".format(java.util.Locale.US, v / 1000f) else v.toString()

        private companion object {
            const val TWEEN = 480f
        }
    }

    /** Полоса очков со звёздами-отметками (ветка с лозой). */
    @SuppressLint("ViewConstructor")
    private class StarBar(ctx: Context, val e: Match3Engine) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var shown = 0f
        private val litAt = LongArray(3)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val now = SystemClock.uptimeMillis()
            val bar = RectF(h * 0.3f, h * 0.3f, w - h * 0.3f - h * 2.6f, h * 0.7f)
            p.color = 0xFF5B3418.toInt()
            c.drawRoundRect(RectF(bar.left - 3 * d, bar.top - 3 * d, bar.right + 3 * d, bar.bottom + 3 * d), h, h, p)
            p.color = 0xFF2A160A.toInt()
            c.drawRoundRect(bar, h, h, p)
            // Полоска - запас ходов: тает с каждым ходом. Отметки - сколько ходов нужно сохранить
            // для 3-й и 2-й звезды; звёзды справа горят, пока их ещё можно получить.
            val total = (e.totalMoves).coerceAtLeast(1)
            val target = (e.moves.toFloat() / total).coerceIn(0f, 1f)
            if (LiteMode.on || shown == 0f) shown = target else shown += (target - shown) * 0.15f
            if (abs(target - shown) > 0.002f) postInvalidateOnAnimation() else shown = target
            val fillR = bar.right
            val fw = (fillR - bar.left)
            val pot = if (e.goalsDone) e.stars else e.potentialStars
            if (shown > 0.01f) {
                val cols = when (pot) {
                    3 -> intArrayOf(0xFFFDE047.toInt(), 0xFFF59E0B.toInt(), 0xFFEA580C.toInt())
                    2 -> intArrayOf(0xFFE879F9.toInt(), 0xFFA855F7.toInt(), 0xFF7C3AED.toInt())
                    else -> intArrayOf(0xFF93C5FD.toInt(), 0xFF60A5FA.toInt(), 0xFF2563EB.toInt())
                }
                p.shader = LinearGradient(bar.left, 0f, fillR, 0f, cols, null, Shader.TileMode.CLAMP)
                c.drawRoundRect(RectF(bar.left, bar.top, bar.left + fw * shown, bar.bottom), h, h, p)
                p.shader = null
                p.color = 0x55FFFFFF
                c.drawRoundRect(RectF(bar.left + 3 * d, bar.top + 2 * d, bar.left + (fw * shown - 3 * d).coerceAtLeast(3 * d), bar.top + bar.height() * 0.4f), h, h, p)
            }
            // Отметки порогов 2-й и 3-й звезды на полоске.
            p.color = 0xCCFFF4DA.toInt()
            for (f in floatArrayOf(Match3Engine.STAR2_LEFT, Match3Engine.STAR3_LEFT)) {
                val x = bar.left + fw * f
                c.drawRect(x - 1 * d, bar.top - 2 * d, x + 1 * d, bar.bottom + 2 * d, p)
            }
            for (k in 0 until 3) {
                val x = fillR + h * 0.55f + k * h * 0.8f
                val on = k < pot
                if (on != (litAt[k] > 0)) litAt[k] = if (on) now else 0L
                if (on) lostAt[k] = 0L else if (lostAt[k] == 0L && prevPot > k) lostAt[k] = now
                val age = if (litAt[k] > 0) ((now - litAt[k]) / 450f).coerceIn(0f, 1f) else 1f
                val lost = if (lostAt[k] > 0) ((now - lostAt[k]) / 500f).coerceIn(0f, 1f) else 1f
                if (age < 1f || lost < 1f) postInvalidateOnAnimation()
                val pulse = if (on) 0.4f * sin(age * Math.PI.toFloat()) else -0.35f * sin(lost * Math.PI.toFloat())
                star(c, x, h / 2, h * 0.36f * (1f + pulse), on)
            }
            prevPot = pot
        }

        private val lostAt = LongArray(3)
        private var prevPot = 3

        private fun star(c: Canvas, x: Float, y: Float, r: Float, on: Boolean) {
            path.reset()
            for (k in 0 until 10) {
                val a = Math.PI / 2 * 3 + k * Math.PI / 5
                val rr = if (k % 2 == 0) r else r * 0.46f
                val px = x + (rr * cos(a)).toFloat()
                val py = y + (rr * sin(a)).toFloat()
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            p.shader = if (on) LinearGradient(0f, y - r, 0f, y + r, 0xFFFFF59D.toInt(), 0xFFF59E0B.toInt(), Shader.TileMode.CLAMP) else null
            p.color = if (on) Color.WHITE else 0xFF6B4423.toInt()
            c.drawPath(path, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.6f * d
            p.color = if (on) 0xFF92400E.toInt() else 0xFF3B2210.toInt()
            c.drawPath(path, p)
            p.style = Paint.Style.FILL
        }
    }

    /** Кнопка бустера: картинка из макета, счётчик в красном кружке, подсветка выбора. */
    @SuppressLint("ViewConstructor")
    private class BoostButton(ctx: Context, val kind: Int) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
        }
        private val bmp = art(ctx).boosts[kind]
        private val dst = RectF()
        var count = 0
            set(v) {
                field = v
                invalidate()
            }
        var active = false
            set(v) {
                field = v
                invalidate()
            }
        private var pressAt = 0L

        init {
            isClickable = true
            contentDescription = ctx.getString(BOOST_NAMES[kind])
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                pressAt = SystemClock.uptimeMillis()
                invalidate()
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(c: Canvas) {
            val s = min(width, height) * 0.92f
            val cx = width / 2f
            val cy = height / 2f
            val now = SystemClock.uptimeMillis()
            val press = ((now - pressAt) / 220f).coerceIn(0f, 1f)
            val k = if (press < 1f) 1f - 0.12f * sin(press * Math.PI.toFloat()) else 1f
            if (press < 1f) postInvalidateOnAnimation()
            if (active) {
                val pulse = 0.5f + 0.5f * sin(now / 180f)
                p.shader = RadialGradient(cx, cy, s * 0.62f, (0xFFFDE047.toInt() and 0x00FFFFFF) or ((140 + 100 * pulse).toInt() shl 24), 0x00FDE047, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, s * 0.62f, p)
                p.shader = null
                postInvalidateDelayed(if (LiteMode.on) LiteMode.liveFrameMs else 30L)
            }
            val ss = s * k * (if (active) 1.06f else 1f)
            dst.set(cx - ss / 2, cy - ss / 2, cx + ss / 2, cy + ss / 2)
            c.drawBitmap(bmp, null, dst, p)
            // Счётчик (или «+», если пусто - можно купить).
            val br = s * 0.17f
            val bx = cx + s * 0.31f
            val by = cy + s * 0.32f
            p.shader = LinearGradient(0f, by - br, 0f, by + br, 0xFFFF6B6B.toInt(), 0xFFC81E1E.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(bx, by, br, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * d
            p.color = 0xFFFDE68A.toInt()
            c.drawCircle(bx, by, br, p)
            p.style = Paint.Style.FILL
            t.textSize = br * 1.05f
            c.drawText(if (count > 0) "×$count" else "+", bx, by + t.textSize * 0.36f, t)
        }
    }

    /** Звёзды победы: вылетают по одной с пружинкой и искрами. */
    @SuppressLint("ViewConstructor")
    private class WinStars(ctx: Context, val n: Int) : View(ctx) {
        private val starSounded = BooleanArray(3)
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val start = SystemClock.uptimeMillis()

        override fun onDraw(c: Canvas) {
            val now = SystemClock.uptimeMillis()
            val w = width.toFloat()
            val h = height.toFloat()
            var anim = false
            for (k in 0 until 3) {
                val t0 = 250L + k * 380L
                val age = ((now - start - t0) / 420f).coerceIn(0f, 1f)
                if (age < 1f) anim = true
                val on = k < n
                val x = w * (0.22f + 0.28f * k)
                val y = h * (if (k == 1) 0.42f else 0.55f)
                val base = h * (if (k == 1) 0.4f else 0.32f)
                val sc = if (!on) 1f else if (age <= 0f) 0f else OVERSHOOT(age)
                drawStar(c, x, y, base, false)
                if (on && age > 0f && !starSounded[k]) {
                    starSounded[k] = true
                    GameSfx.play(context, GameSfx.S.STAR, 1f + k * 0.15f)
                }
                if (on && sc > 0f) {
                    drawStar(c, x, y, base * sc, true)
                    if (age < 1f) {
                        p.color = 0xFFFDE68A.toInt()
                        for (s in 0 until 8) {
                            val a = s * Math.PI / 4
                            val rr = base * (0.8f + age * 0.9f)
                            p.alpha = (255 * (1 - age)).toInt()
                            c.drawCircle(x + (rr * cos(a)).toFloat(), y + (rr * sin(a)).toFloat(), 2.5f * d, p)
                        }
                        p.alpha = 255
                    }
                }
            }
            if (anim) postInvalidateOnAnimation()
        }

        private fun OVERSHOOT(t: Float): Float {
            val s = 2.2f
            val x = t - 1f
            return x * x * ((s + 1) * x + s) + 1f
        }

        private fun drawStar(c: Canvas, x: Float, y: Float, r: Float, on: Boolean) {
            path.reset()
            for (k in 0 until 10) {
                val a = Math.PI / 2 * 3 + k * Math.PI / 5
                val rr = if (k % 2 == 0) r else r * 0.46f
                val px = x + (rr * cos(a)).toFloat()
                val py = y + (rr * sin(a)).toFloat()
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            p.shader = if (on) LinearGradient(0f, y - r, 0f, y + r, 0xFFFFF59D.toInt(), 0xFFF59E0B.toInt(), Shader.TileMode.CLAMP) else null
            p.color = if (on) Color.WHITE else 0x55000000
            c.drawPath(path, p)
            p.shader = null
            if (on) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f * d
                p.color = 0xFF92400E.toInt()
                c.drawPath(path, p)
                p.style = Paint.Style.FILL
            }
        }
    }
}

/**
 * Фон - ночной сказочный лес: глубокий бирюзовый градиент, лучи света, светящиеся грибы
 * по краям, туман у земли и светлячки, которые плавают и мерцают.
 */
internal class ForestView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = SystemClock.uptimeMillis()
    private val rnd = java.util.Random(5)
    private val fx = FloatArray(34) { rnd.nextFloat() }
    private val fy = FloatArray(34) { rnd.nextFloat() }
    private val fp = FloatArray(34) { rnd.nextFloat() * 6.28f }
    private val fs = FloatArray(34) { 0.6f + rnd.nextFloat() }
    private var bg: Shader? = null
    private var fog: Shader? = null
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bg = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF0B2A3A.toInt(), 0xFF0E4A55.toInt(), 0xFF123B3A.toInt(), 0xFF0F2A1C.toInt()), floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        fog = LinearGradient(0f, h * 0.72f, 0f, h.toFloat(), 0x00A7F3D0, 0x5534D399, Shader.TileMode.CLAMP)
    }

    private fun mushroom(c: Canvas, x: Float, y: Float, s: Float, cap: Int, glowA: Float) {
        // Свечение.
        p.shader = RadialGradient(x, y - s * 0.9f, s * 1.6f, (cap and 0x00FFFFFF) or ((70 * glowA).toInt() shl 24), cap and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(x, y - s * 0.9f, s * 1.6f, p)
        // Ножка.
        p.shader = LinearGradient(x - s * 0.2f, 0f, x + s * 0.2f, 0f, 0xFFFDE8CF.toInt(), 0xFFD4A574.toInt(), Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(x - s * 0.16f, y)
        path.quadTo(x - s * 0.2f, y - s * 0.5f, x - s * 0.12f, y - s * 0.95f)
        path.lineTo(x + s * 0.12f, y - s * 0.95f)
        path.quadTo(x + s * 0.2f, y - s * 0.5f, x + s * 0.16f, y)
        path.close()
        c.drawPath(path, p)
        // Шляпка с пятнышками.
        p.shader = RadialGradient(x - s * 0.2f, y - s * 1.2f, s * 1.1f, intArrayOf(0xFFFFD6A5.toInt(), cap, (cap and 0x00FFFFFF) or 0xFF000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(x - s * 0.7f, y - s * 0.9f)
        path.cubicTo(x - s * 0.62f, y - s * 1.55f, x + s * 0.62f, y - s * 1.55f, x + s * 0.7f, y - s * 0.9f)
        path.quadTo(x, y - s * 0.78f, x - s * 0.7f, y - s * 0.9f)
        c.drawPath(path, p)
        p.shader = null
        p.color = 0xCCFFF7E6.toInt()
        c.drawCircle(x - s * 0.25f, y - s * 1.2f, s * 0.07f, p)
        c.drawCircle(x + s * 0.18f, y - s * 1.3f, s * 0.05f, p)
        c.drawCircle(x + s * 0.4f, y - s * 1.05f, s * 0.05f, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val live = Kinetics.enabled && !EnergySaver.on
        val t = if (live) (SystemClock.uptimeMillis() - start) / 1000f else 2f
        p.shader = bg
        c.drawRect(0f, 0f, w, h, p)
        // Лучи света сверху.
        for (k in 0 until 3) {
            val x = w * (0.2f + k * 0.3f) + sin(t * 0.2f + k) * 20 * d
            p.shader = LinearGradient(x, 0f, x + w * 0.1f, h * 0.7f, 0x22CFFAFE, 0x00CFFAFE, Shader.TileMode.CLAMP)
            path.reset()
            path.moveTo(x - w * 0.03f, 0f)
            path.lineTo(x + w * 0.05f, 0f)
            path.lineTo(x + w * 0.2f, h * 0.7f)
            path.lineTo(x - w * 0.02f, h * 0.7f)
            path.close()
            c.drawPath(path, p)
        }
        p.shader = null
        // Грибы по краям (дышат светом).
        val g = 0.75f + 0.25f * sin(t * 1.3f)
        mushroom(c, w * 0.06f, h * 0.36f, w * 0.13f, 0xFFF97362.toInt(), g)
        mushroom(c, w * 0.95f, h * 0.9f, w * 0.15f, 0xFFFB923C.toInt(), 1.2f - g * 0.3f)
        mushroom(c, w * 0.05f, h * 0.93f, w * 0.1f, 0xFFF472B6.toInt(), g)
        // Туман у земли.
        p.shader = fog
        c.drawRect(0f, h * 0.72f, w, h, p)
        p.shader = null
        // Светлячки.
        // В лёгком режиме - вдвое меньше светлячков.
        for (i in 0 until LiteMode.particles(fx.size)) {
            val x = (fx[i] * w + sin(t * 0.5f * fs[i] + fp[i]) * 30 * d) % w
            val y = (fy[i] * h + cos(t * 0.4f * fs[i] + fp[i]) * 24 * d) % h
            val a = ((sin(t * 2f * fs[i] + fp[i]) + 1) / 2)
            p.color = 0xFFFDF6A3.toInt()
            p.alpha = (40 * a).toInt()
            c.drawCircle(x, y, 7 * d * fs[i], p)
            p.alpha = (200 * a + 30).toInt().coerceAtMost(255)
            c.drawCircle(x, y, 1.8f * d * fs[i], p)
        }
        p.alpha = 255
        if (live && isShown) postInvalidateDelayed(LiteMode.liveFrameMs)
    }
}
