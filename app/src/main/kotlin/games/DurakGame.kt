// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.LiteMode
import com.stoyko.smessenger.chat.Coins
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Колоды: по умолчанию - авторская «космическая» колода (рисунки карт - картинки из ресурсов,
 * рубашка - в том же стиле: ночное небо, золотая рамка, созвездия и эмблема «S»).
 * Ещё есть рисованная колода в стиле мессенджера: светлая лицевая сторона с фирменной каймой,
 * масти нарисованы векторно (не шрифтом - везде одинаково), картинки В/Д/К - медальон с
 * короной, туз - крупная светящаяся масть; рубашка - фиолетовый градиент с логотипом «S».
 * Готовые карты кэшируются картинками нужного размера.
 */
object CardArt {
    const val RED = 0xFFE11D48.toInt()
    const val DARK = 0xFF1E1B4B.toInt()
    private val cache = HashMap<String, Bitmap>()

    /** Картинки авторской колоды: [масть 0 ♠, 1 ♣, 2 ♦, 3 ♥][достоинство 6…A]. */
    private val ART = arrayOf(
        intArrayOf(R.drawable.card_s_6, R.drawable.card_s_7, R.drawable.card_s_8, R.drawable.card_s_9, R.drawable.card_s_10, R.drawable.card_s_j, R.drawable.card_s_q, R.drawable.card_s_k, R.drawable.card_s_a),
        intArrayOf(R.drawable.card_c_6, R.drawable.card_c_7, R.drawable.card_c_8, R.drawable.card_c_9, R.drawable.card_c_10, R.drawable.card_c_j, R.drawable.card_c_q, R.drawable.card_c_k, R.drawable.card_c_a),
        intArrayOf(R.drawable.card_d_6, R.drawable.card_d_7, R.drawable.card_d_8, R.drawable.card_d_9, R.drawable.card_d_10, R.drawable.card_d_j, R.drawable.card_d_q, R.drawable.card_d_k, R.drawable.card_d_a),
        intArrayOf(R.drawable.card_h_6, R.drawable.card_h_7, R.drawable.card_h_8, R.drawable.card_h_9, R.drawable.card_h_10, R.drawable.card_h_j, R.drawable.card_h_q, R.drawable.card_h_k, R.drawable.card_h_a),
    )

    /** Цвет «свечения» масти выбранной колоды-картинки (для живых эффектов на столе). */
    val SUIT_GLOW: IntArray get() = when (lastStyle) {
        STYLE_CYBER -> GLOW_CYBER
        STYLE_RUNES -> GLOW_RUNES
        else -> GLOW_SPACE
    }
    private val GLOW_SPACE = intArrayOf(0xFF60A5FA.toInt(), 0xFF34D399.toInt(), 0xFFFBBF24.toInt(), 0xFFF43F5E.toInt())
    private val GLOW_CYBER = intArrayOf(0xFF818CF8.toInt(), 0xFF4ADE80.toInt(), 0xFFFB923C.toInt(), 0xFFFB7185.toInt())
    private val GLOW_RUNES = intArrayOf(0xFFA78BFA.toInt(), 0xFF22C55E.toInt(), 0xFFEF4444.toInt(), 0xFFDC2626.toInt())

    // ---------- Колоды-картинки (4.10.0) ----------
    // 0 - «Космос» (ресурсы), 3 - «Кибер-неон», 4 - «Тёмные руны» (assets/cards/<колода>/<масть>_<достоинство>.webp).
    const val STYLE_ART = 0
    const val STYLE_CYBER = 3
    const val STYLE_RUNES = 4
    const val STYLE_COUNT = 5

    @Volatile
    var lastStyle = STYLE_ART
        private set

    private fun deckDir(style: Int) = when (style) {
        STYLE_CYBER -> "cyber"
        STYLE_RUNES -> "runes"
        else -> null
    }

    private val SUIT_LETTER = charArrayOf('s', 'c', 'd', 'h')
    private val RANK_NAME = arrayOf("6", "7", "8", "9", "10", "j", "q", "k", "a")

    /** Колода нарисована картинками (авторская, кибер-неон, руны) - а не векторная. */
    fun imageDeck(style: Int) = style == STYLE_ART || style == STYLE_CYBER || style == STYLE_RUNES

    private fun decodeAsset(ctx: Context, path: String): Bitmap? = runCatching {
        ctx.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /** Ширина картинок авторской колоды (пиксели): крупнее не растягиваем - рисунок не мылится. */
    const val ART_W = 320

    /** Пропорции карты: авторская колода чуть вытянутее, руны - ещё выше. */
    fun ratio(ctx: Context) = when (style(ctx)) {
        STYLE_RUNES -> 1.64f
        STYLE_ART, STYLE_CYBER -> 1.48f
        else -> 1.42f
    }

    fun isRed(suit: Int) = suit >= 2

    private fun ranks(ctx: Context): List<String> = ctx.getString(R.string.durak_ranks).split(',')

    /** Векторная масть: 0 ♠, 1 ♣, 2 ♦, 3 ♥; [s] - размер. */
    fun suitPath(p: Path, suit: Int, cx: Float, cy: Float, s: Float) {
        p.reset()
        val h = s / 2
        when (suit) {
            2 -> {
                p.moveTo(cx, cy - h)
                p.quadTo(cx + h * 0.35f, cy - h * 0.3f, cx + h * 0.78f, cy)
                p.quadTo(cx + h * 0.35f, cy + h * 0.3f, cx, cy + h)
                p.quadTo(cx - h * 0.35f, cy + h * 0.3f, cx - h * 0.78f, cy)
                p.quadTo(cx - h * 0.35f, cy - h * 0.3f, cx, cy - h)
            }
            3 -> {
                p.moveTo(cx, cy + h * 0.95f)
                p.cubicTo(cx - h * 1.25f, cy + h * 0.05f, cx - h * 0.95f, cy - h * 1.05f, cx, cy - h * 0.42f)
                p.cubicTo(cx + h * 0.95f, cy - h * 1.05f, cx + h * 1.25f, cy + h * 0.05f, cx, cy + h * 0.95f)
            }
            0 -> {
                p.moveTo(cx, cy - h)
                p.cubicTo(cx + h * 1.3f, cy - h * 0.05f, cx + h * 0.9f, cy + h * 0.85f, cx + h * 0.08f, cy + h * 0.38f)
                p.lineTo(cx + h * 0.3f, cy + h)
                p.lineTo(cx - h * 0.3f, cy + h)
                p.lineTo(cx - h * 0.08f, cy + h * 0.38f)
                p.cubicTo(cx - h * 0.9f, cy + h * 0.85f, cx - h * 1.3f, cy - h * 0.05f, cx, cy - h)
            }
            else -> {
                val r = h * 0.42f
                p.addCircle(cx, cy - h * 0.48f, r, Path.Direction.CW)
                p.addCircle(cx - h * 0.5f, cy + h * 0.08f, r, Path.Direction.CW)
                p.addCircle(cx + h * 0.5f, cy + h * 0.08f, r, Path.Direction.CW)
                p.moveTo(cx - h * 0.1f, cy)
                p.lineTo(cx + h * 0.1f, cy)
                p.lineTo(cx + h * 0.32f, cy + h)
                p.lineTo(cx - h * 0.32f, cy + h)
                p.close()
            }
        }
        p.close()
    }

    // ---------- настройки вида карт ----------

    /** Подсветка тёмных карт: фиолетовая, золотая, изумрудная, алая, ледяная. */
    val GLOWS = intArrayOf(0xFF8B7CFF.toInt(), 0xFFFBBF24.toInt(), 0xFF34D399.toInt(), 0xFFF43F5E.toInt(), 0xFF38BDF8.toInt())

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("durak_ui", Context.MODE_PRIVATE)

    /**
     * Вид колоды: 0 - авторская (по умолчанию), 1 - светлая, 2 - тёмная с подсветкой,
     * 3 - «Кибер-неон», 4 - «Тёмные руны».
     */
    fun style(ctx: Context): Int {
        val p = prefs(ctx)
        return p.getInt("style", if (p.getBoolean("dark", false)) 2 else 0).coerceIn(0, STYLE_COUNT - 1).also { lastStyle = it }
    }
    fun setStyle(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("style", v).putBoolean("dark", v == 2).apply()
        clear()
    }
    fun art(ctx: Context) = imageDeck(style(ctx))

    /** Тёмные карты с подсветкой. */
    fun dark(ctx: Context) = style(ctx) == 2
    fun setDark(ctx: Context, v: Boolean) = setStyle(ctx, if (v) 2 else 1)
    fun glow(ctx: Context) = prefs(ctx).getInt("glow", 0).coerceIn(0, GLOWS.size - 1)
    fun setGlow(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("glow", v).apply()
        cache.clear()
    }

    /** Лицо карты авторской колоды нужного размера (из картинки, с кэшем). */
    private fun artFace(ctx: Context, card: Int, w: Int, h: Int): Bitmap {
        val st = style(ctx)
        val key = "a$st:$card:$w:$h"
        synchronized(cache) { cache[key]?.let { return it } }
        val opts = android.graphics.BitmapFactory.Options().apply { inScaled = false }
        val dir = deckDir(st)
        val src = (if (dir != null) decodeAsset(ctx, "cards/$dir/${SUIT_LETTER[card / 9]}_${RANK_NAME[card % 9]}.webp") else null)
            ?: android.graphics.BitmapFactory.decodeResource(ctx.resources, ART[card / 9][card % 9], opts)
        val bmp = if (src.width == w && src.height == h) src else smoothScale(src, w, h).also { if (it !== src) src.recycle() }
        synchronized(cache) { cache[key] = bmp }
        return bmp
    }

    /**
     * Уменьшение без «лесенки» и мыла: сильное уменьшение - в несколько шагов по половине
     * (каждый шаг - честное усреднение), последний шаг - с фильтрацией.
     */
    private fun smoothScale(src: Bitmap, w: Int, h: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= w && cur.height / 2 >= h) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val pt = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        cv.drawBitmap(cur, null, android.graphics.Rect(0, 0, w, h), pt)
        if (cur !== src) cur.recycle()
        return out
    }

    /** Заранее подготовить всю колоду (в фоне) - чтобы первая раздача не подтормаживала. */
    fun prewarm(ctx: Context, w: Int, h: Int) {
        if (!art(ctx) || w <= 0) return
        val app = ctx.applicationContext
        loader.execute {
            runCatching { back(app, w, h) }
            val st = style(app)
            for (c in 0 until 36) {
                val key = "a$st:$c:$w:$h"
                val skip = synchronized(cache) { cache.containsKey(key) || !loading.add(key) }
                if (skip) continue
                runCatching { artFace(app, c, w, h) }
                synchronized(cache) { loading.remove(key) }
            }
        }
    }

    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "card-art").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val loading = HashSet<String>()

    /**
     * Лицо карты, если оно уже готово; иначе - null, а картинка готовится в фоне и потом
     * вызывается [ready]. Так стол никогда не ждёт декодирования картинок в кадре (раньше первая
     * раздача на крупных картах «дёргалась» из-за этого).
     */
    fun faceOrNull(ctx: Context, card: Int, w: Int, h: Int, ready: () -> Unit): Bitmap? {
        if (!art(ctx)) return face(ctx, card, w, h)
        val key = "a${style(ctx)}:$card:$w:$h"
        synchronized(cache) {
            cache[key]?.let { return it }
            if (!loading.add(key)) return null
        }
        val app = ctx.applicationContext
        loader.execute {
            runCatching { artFace(app, card, w, h) }
            synchronized(cache) { loading.remove(key) }
            android.os.Handler(android.os.Looper.getMainLooper()).post(ready)
        }
        return null
    }

    fun face(ctx: Context, card: Int, w: Int, h: Int): Bitmap {
        if (art(ctx)) return artFace(ctx, card, w, h)
        val dark = dark(ctx)
        val glowCol = GLOWS[glow(ctx)]
        val key = "f$card:$w:$h:$dark:$glowCol"
        synchronized(cache) { cache[key]?.let { return it } }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = ctx.resources.displayMetrics.density
        val suit = card / 9
        val rank = card % 9
        // Тёмные карты: светлые масти на тёмном фоне (красные - розово-алые, чёрные - светло-голубые).
        val col = if (dark) (if (isRed(suit)) 0xFFFB7185.toInt() else 0xFFE0E7FF.toInt()) else if (isRed(suit)) RED else DARK
        val r = w * 0.11f
        val rect = RectF(1f, 1f, w - 1f, h - 1f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = if (dark) {
            LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF2A2550.toInt(), 0xFF0F0D24.toInt(), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFFFFFFFF.toInt(), 0xFFF2EFFF.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rect, r, r, p)
        p.shader = null
        // Фирменная кайма: тонкая рамка цвета масти внутри и градиентная граница.
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (dark) 2f else 1.4f) * d
        if (dark) {
            // Неоновая подсветка по краю выбранным цветом.
            p.shader = null
            p.color = glowCol
            p.maskFilter = android.graphics.BlurMaskFilter(3f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(RectF(2f * d, 2f * d, w - 2f * d, h - 2f * d), r, r, p)
            p.maskFilter = null
            p.strokeWidth = 1.2f * d
        } else {
            p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF8B7CFF.toInt(), 0xFF38BDF8.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rect, r, r, p)
        p.shader = null
        p.color = (col and 0x00FFFFFF) or 0x30000000
        p.strokeWidth = 1f * d
        val inset = w * 0.07f
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), r * 0.6f, r * 0.6f, p)
        p.style = Paint.Style.FILL
        val label = ranks(ctx).getOrElse(rank) { "?" }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = w * (if (label.length > 1) 0.2f else 0.24f)
        }
        val path = Path()
        // Уголки: достоинство и масть (нижний - перевёрнут).
        fun corner() {
            val cx = w * 0.16f
            c.drawText(label, cx, h * 0.17f, text)
            p.color = col
            suitPath(path, suit, cx, h * 0.25f, w * 0.13f)
            c.drawPath(path, p)
        }
        corner()
        c.save()
        c.rotate(180f, w / 2f, h / 2f)
        corner()
        c.restore()
        val cx = w / 2f
        val cy = h / 2f
        when {
            rank <= 4 -> {
                // 6…10 - очки масти по классической раскладке.
                val pts = when (rank) {
                    0 -> listOf(0f to 0f, 0f to 0.5f, 0f to 1f, 1f to 0f, 1f to 0.5f, 1f to 1f)
                    1 -> listOf(0f to 0f, 0f to 0.5f, 0f to 1f, 1f to 0f, 1f to 0.5f, 1f to 1f, 0.5f to 0.25f)
                    2 -> listOf(0f to 0f, 0f to 0.5f, 0f to 1f, 1f to 0f, 1f to 0.5f, 1f to 1f, 0.5f to 0.25f, 0.5f to 0.75f)
                    3 -> listOf(0f to 0f, 0f to 1 / 3f, 0f to 2 / 3f, 0f to 1f, 1f to 0f, 1f to 1 / 3f, 1f to 2 / 3f, 1f to 1f, 0.5f to 0.5f)
                    else -> listOf(0f to 0f, 0f to 1 / 3f, 0f to 2 / 3f, 0f to 1f, 1f to 0f, 1f to 1 / 3f, 1f to 2 / 3f, 1f to 1f, 0.5f to 1 / 6f, 0.5f to 5 / 6f)
                }
                val left = w * 0.32f
                val right = w * 0.68f
                val top = h * 0.2f
                val bottom = h * 0.8f
                val s = w * 0.2f
                p.color = col
                for ((px, py) in pts) {
                    val x = left + (right - left) * px
                    val y = top + (bottom - top) * py
                    c.save()
                    if (py > 0.5f) c.rotate(180f, x, y)
                    suitPath(path, suit, x, y, s)
                    c.drawPath(path, p)
                    c.restore()
                }
            }
            rank <= 7 -> {
                // Валет, дама, король - медальон с буквой и короной.
                val box = RectF(w * 0.24f, h * 0.2f, w * 0.76f, h * 0.8f)
                p.shader = LinearGradient(
                    box.left, box.top, box.right, box.bottom,
                    if (isRed(suit)) intArrayOf(0xFFFB7185.toInt(), 0xFFBE123C.toInt()) else intArrayOf(0xFF818CF8.toInt(), 0xFF312E81.toInt()),
                    null, Shader.TileMode.CLAMP,
                )
                c.drawRoundRect(box, w * 0.12f, w * 0.12f, p)
                p.shader = null
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.6f * d
                p.color = 0xFFFDE047.toInt()
                c.drawRoundRect(RectF(box.left + 3 * d, box.top + 3 * d, box.right - 3 * d, box.bottom - 3 * d), w * 0.09f, w * 0.09f, p)
                p.style = Paint.Style.FILL
                // Корона (у валета - шапочка).
                val crownW = box.width() * (if (rank == 7) 0.62f else 0.5f)
                val crownTop = box.top + box.height() * 0.14f
                val crownBot = crownTop + box.height() * 0.17f
                path.reset()
                if (rank == 5) {
                    path.moveTo(cx - crownW / 2, crownBot)
                    path.quadTo(cx, crownTop - box.height() * 0.08f, cx + crownW / 2, crownBot)
                    path.close()
                } else {
                    path.moveTo(cx - crownW / 2, crownBot)
                    path.lineTo(cx - crownW / 2, crownTop + (crownBot - crownTop) * 0.25f)
                    path.lineTo(cx - crownW / 4, crownTop + (crownBot - crownTop) * 0.6f)
                    path.lineTo(cx, crownTop)
                    path.lineTo(cx + crownW / 4, crownTop + (crownBot - crownTop) * 0.6f)
                    path.lineTo(cx + crownW / 2, crownTop + (crownBot - crownTop) * 0.25f)
                    path.lineTo(cx + crownW / 2, crownBot)
                    path.close()
                }
                p.shader = LinearGradient(0f, crownTop, 0f, crownBot, 0xFFFEF08A.toInt(), 0xFFF59E0B.toInt(), Shader.TileMode.CLAMP)
                c.drawPath(path, p)
                p.shader = null
                val letter = Paint(text).apply {
                    color = Color.WHITE
                    textSize = box.width() * 0.62f
                    setShadowLayer(2 * d, 0f, d, 0x66000000)
                }
                c.drawText(label, cx, box.top + box.height() * 0.66f, letter)
                p.color = Color.WHITE
                suitPath(path, suit, cx, box.top + box.height() * 0.84f, box.width() * 0.22f)
                c.drawPath(path, p)
            }
            else -> {
                // Туз: крупная масть со свечением.
                p.shader = RadialGradient(cx, cy, w * 0.45f, (col and 0x00FFFFFF) or 0x40000000, col and 0x00FFFFFF, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, w * 0.45f, p)
                p.shader = LinearGradient(0f, cy - w * 0.3f, 0f, cy + w * 0.3f,
                    if (isRed(suit)) 0xFFFB7185.toInt() else 0xFF6366F1.toInt(), col, Shader.TileMode.CLAMP)
                suitPath(path, suit, cx, cy, w * 0.56f)
                c.drawPath(path, p)
                p.shader = null
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.2f * d
                p.color = 0x99FDE047.toInt()
                c.drawCircle(cx, cy, w * 0.4f, p)
                p.style = Paint.Style.FILL
            }
        }
        synchronized(cache) { cache[key] = bmp }
        return bmp
    }

    /**
     * Рубашка авторской колоды: ночное небо, золотая рамка с завитками, созвездия и
     * золотая эмблема «S» в лучах.
     */
    private fun artBack(ctx: Context, w: Int, h: Int): Bitmap {
        val key = "ab:$w:$h"
        synchronized(cache) { cache[key]?.let { return it } }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = w * 0.06f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), intArrayOf(0xFF1A1440.toInt(), 0xFF0B1230.toInt(), 0xFF140A26.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
        // Туманность в центре.
        p.shader = RadialGradient(w / 2f, h / 2f, w * 0.62f, intArrayOf(0x664338CA, 0x2A7C3AED, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // Звёзды и созвездия (одни и те же на каждой рубашке).
        val rnd = java.util.Random(42)
        p.shader = null
        for (i in 0 until (w * h / 900).coerceIn(20, 140)) {
            p.color = if (rnd.nextInt(5) == 0) 0xFFFDE68A.toInt() else 0xFFFFFFFF.toInt()
            p.alpha = 60 + rnd.nextInt(170)
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, (0.4f + rnd.nextFloat() * 1.1f) * w / 160f, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = w / 260f
        p.color = 0x88E8C46A.toInt()
        fun constellation(pts: List<Pair<Float, Float>>) {
            for (i in 1 until pts.size) c.drawLine(pts[i - 1].first * w, pts[i - 1].second * h, pts[i].first * w, pts[i].second * h, p)
            p.style = Paint.Style.FILL
            for ((x, y) in pts) c.drawCircle(x * w, y * h, w / 110f, p)
            p.style = Paint.Style.STROKE
        }
        constellation(listOf(0.2f to 0.16f, 0.3f to 0.22f, 0.42f to 0.18f, 0.5f to 0.25f, 0.66f to 0.2f))
        constellation(listOf(0.3f to 0.82f, 0.44f to 0.76f, 0.56f to 0.8f, 0.72f to 0.74f, 0.78f to 0.84f))
        // Золотая рамка: двойная линия и завитки по углам.
        val gold = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFFF8E1A0.toInt(), 0xFFC9963A.toInt(), 0xFFF3D27A.toInt(), 0xFFA8762A.toInt()), null, Shader.TileMode.CLAMP)
        p.shader = gold
        val i1 = w * 0.065f
        val i2 = w * 0.095f
        p.strokeWidth = w / 90f
        c.drawRoundRect(RectF(i1, i1, w - i1, h - i1), r * 0.8f, r * 0.8f, p)
        p.strokeWidth = w / 220f
        c.drawRoundRect(RectF(i2, i2, w - i2, h - i2), r * 0.5f, r * 0.5f, p)
        // Завитки по углам (зеркально во все четыре угла).
        val curl = Path()
        val cx0 = i2 + w * 0.04f
        curl.moveTo(cx0, cx0 + w * 0.16f)
        curl.cubicTo(cx0, cx0 + w * 0.02f, cx0 + w * 0.02f, cx0, cx0 + w * 0.16f, cx0)
        for ((mx, my) in listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f)) {
            c.save()
            c.scale(mx, my, w / 2f, h / 2f)
            c.drawPath(curl, p)
            c.drawCircle(cx0 + w * 0.045f, cx0 + w * 0.045f, w * 0.025f, p)
            c.restore()
        }
        // Эмблема: золотой круг с лучами и «S».
        val cx = w / 2f
        val cy = h / 2f
        p.strokeWidth = w / 200f
        p.shader = null
        p.color = 0x55F3D27A
        for (k in 0 until 24) {
            val a = Math.toRadians(k * 15.0)
            val r0 = w * 0.2f
            val r1 = w * (if (k % 2 == 0) 0.34f else 0.27f)
            c.drawLine(cx + (r0 * Math.cos(a)).toFloat(), cy + (r0 * Math.sin(a)).toFloat(), cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat(), p)
        }
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, w * 0.19f, intArrayOf(0xFF2A1F5C.toInt(), 0xFF120B2E.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, w * 0.18f, p)
        p.style = Paint.Style.STROKE
        p.shader = gold
        p.strokeWidth = w / 70f
        c.drawCircle(cx, cy, w * 0.18f, p)
        p.strokeWidth = w / 250f
        c.drawCircle(cx, cy, w * 0.205f, p)
        p.style = Paint.Style.FILL
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = w * 0.24f
            shader = LinearGradient(0f, cy - w * 0.12f, 0f, cy + w * 0.1f, 0xFFFFF1C1.toInt(), 0xFFC9963A.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawText("S", cx, cy + t.textSize * 0.35f, t)
        // Тонкая тёмная кромка карты.
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = w / 160f
        p.color = 0xFF07050F.toInt()
        c.drawRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), r, r, p)
        synchronized(cache) { cache[key] = bmp }
        return bmp
    }

    /** Рубашка колоды-картинки (кибер-неон, руны) - из assets. */
    private fun imageBack(ctx: Context, dir: String, w: Int, h: Int): Bitmap? {
        val key = "ib$dir:$w:$h"
        synchronized(cache) { cache[key]?.let { return it } }
        val src = decodeAsset(ctx, "cards/$dir/back.webp") ?: return null
        val bmp = if (src.width == w && src.height == h) src else smoothScale(src, w, h).also { if (it !== src) src.recycle() }
        synchronized(cache) { cache[key] = bmp }
        return bmp
    }

    fun back(ctx: Context, w: Int, h: Int): Bitmap {
        deckDir(style(ctx))?.let { dir -> imageBack(ctx, dir, w, h)?.let { return it } }
        if (art(ctx)) return artBack(ctx, w, h)
        val key = "b:$w:$h:${dark(ctx)}:${glow(ctx)}"
        synchronized(cache) { cache[key]?.let { return it } }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = ctx.resources.displayMetrics.density
        val r = w * 0.11f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val darkBack = dark(ctx)
        p.shader = if (darkBack) {
            LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF1F1B3A.toInt(), 0xFF07060F.toInt(), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF8B7CFF.toInt(), 0xFF3730A3.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), r, r, p)
        if (darkBack) {
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d
            p.color = GLOWS[glow(ctx)]
            p.maskFilter = android.graphics.BlurMaskFilter(3f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(RectF(2f * d, 2f * d, w - 2f * d, h - 2f * d), r, r, p)
            p.maskFilter = null
            p.style = Paint.Style.FILL
        }
        p.shader = null
        // Ромбическая сетка.
        p.color = 0x22FFFFFF
        p.strokeWidth = 1f * d
        val step = w / 5f
        c.save()
        c.clipRect(w * 0.08f, w * 0.08f, w * 0.92f, h - w * 0.08f)
        var x = -h.toFloat()
        while (x < w + h) {
            c.drawLine(x, 0f, x + h, h.toFloat(), p)
            c.drawLine(x + h, 0f, x, h.toFloat(), p)
            x += step
        }
        c.restore()
        p.style = Paint.Style.STROKE
        p.color = 0x88FFFFFF.toInt()
        p.strokeWidth = 1.5f * d
        c.drawRoundRect(RectF(w * 0.08f, w * 0.08f, w * 0.92f, h - w * 0.08f), r * 0.6f, r * 0.6f, p)
        p.style = Paint.Style.FILL
        // Логотип «S» в круге.
        val cx = w / 2f
        val cy = h / 2f
        p.shader = RadialGradient(cx - w * 0.06f, cy - w * 0.06f, w * 0.3f, 0xFFFFFFFF.toInt(), 0xFFE0E7FF.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, w * 0.24f, p)
        p.shader = null
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4338CA.toInt()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = w * 0.32f
        }
        c.drawText("S", cx, cy + t.textSize * 0.36f, t)
        synchronized(cache) { cache[key] = bmp }
        return bmp
    }

    fun clear() = synchronized(cache) { cache.clear() }
}

/**
 * Игровой стол «Дурака»: соперники сверху, колода с козырем слева, сброс справа, пары
 * «атака - защита» по центру, мои карты веером снизу. Каждая карта - отдельный «спрайт»,
 * который плавно летит к своему месту: раздача из колоды, полёт на стол, в руку, в сброс;
 * когда карта открывается - она переворачивается.
 */
@SuppressLint("ViewConstructor")
class DurakTable(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density

    private class Sprite {
        var x = 0f
        var y = 0f
        var rot = 0f
        var scale = 1f
        var face = 0f
        var tx = 0f
        var ty = 0f
        var trot = 0f
        var tscale = 1f
        var tface = 0f
        var z = 0
        var zone = -1
        var delayUntil = 0L
        var shakeAt = 0L
        /** Карта двигалась в прошлом кадре - при «приземлении» на стол сыплются искры. */
        var wasMoving = false

        // Плавный полёт по времени (а не «догонялки» по кадрам): откуда, когда начать, сколько
        // лететь. Одинаково плавно при любой частоте кадров и на слабых телефонах.
        var fx = 0f
        var fy = 0f
        var frot = 0f
        var fscale = 1f
        var fface = 0f
        var t0 = 0L
        var dur = 0L
        /** Звук раздачи - ровно в момент, когда карта срывается с колоды. */
        var dealSound = false
        /** Когда карту последний раз сыграли на стол (подсветка «последний ход»). */
        var playedAt = 0L
        var playedBy = -1
        /** Полёт начался с места (плавный разгон) или карту перенаправили на лету. */
        var fromRest = true

        // Полёт по дуге (квадратичная кривая Безье): контрольная точка, закрут в полёте,
        // небольшой «перелёт» цели и мягкая посадка (пружинка масштаба + тень).
        var arc = false
        var cx = 0f
        var cy = 0f
        var spin = 0f
        var overshoot = 0f
        var land = false
        var landAt = 0L

        // Бот «присматривается»: карта приподнимается из веера и чуть покачивается.
        var peekAt = 0L
        var peekEnd = 0L
        var pox = 0f
        var poy = 0f
        var prot = 0f
        var psc = 1f
    }

    // ---------- «живые» карты: блик, искорки, свечение, искры при ходе ----------
    private val live: Boolean get() = com.stoyko.smessenger.ui.Kinetics.enabled && !com.stoyko.smessenger.ui.EnergySaver.on
    private val cardClip = Path()
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheenShader: LinearGradient? = null
    private val sheenMatrix = android.graphics.Matrix()
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloShaders = arrayOfNulls<RadialGradient>(4)
    private val fx = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparks = ArrayList<FloatArray>() // x, y, vx, vy, рождение(мс), цвет
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val faceReady: () -> Unit = { invalidate() }

    private fun burst(x: Float, y: Float, color: Int) {
        if (!live) return
        val now = SystemClock.uptimeMillis().toFloat()
        val rnd = java.util.Random()
        repeat(LiteMode.particles(14)) {
            val a = rnd.nextFloat() * 6.283f
            val sp = (0.08f + rnd.nextFloat() * 0.22f) * d
            sparks += floatArrayOf(x, y, kotlin.math.cos(a) * sp, kotlin.math.sin(a) * sp - 0.05f * d, now, (if (it % 3 == 0) 0xFFFDE68A.toInt() else color).toFloat())
        }
        if (sparks.size > 200) sparks.subList(0, sparks.size - 200).clear()
    }

    /** Эффекты поверх лица карты (в координатах карты, центр - 0,0). */
    private fun drawLife(c: Canvas, card: Int, now: Long, hand: Boolean) {
        val hw = cw / 2f
        val hh = ch / 2f
        c.save()
        // Без clipPath (он дорогой на каждом кадре для каждой карты): блик и свечение рисуются
        // скруглённым прямоугольником по форме карты.
        tmp.set(-hw, -hh, hw, hh)
        val rr = cw * 0.07f
        val suit = card / 9
        val rank = card % 9
        // Мягкое пульсирующее свечение у тузов и картинок.
        if (rank >= 5) {
            val sh = haloShaders[suit] ?: RadialGradient(0f, 0f, cw * 0.55f, (CardArt.SUIT_GLOW[suit] and 0x00FFFFFF) or 0x66000000, CardArt.SUIT_GLOW[suit] and 0x00FFFFFF, Shader.TileMode.CLAMP).also { haloShaders[suit] = it }
            halo.shader = sh
            halo.alpha = (90 + 80 * sin(now / 650f + card)).toInt().coerceIn(0, 255)
            c.drawRoundRect(tmp, rr, rr, halo)
        }
        // Блик: светлая полоса раз в несколько секунд проходит по карте наискосок.
        val period = 3800f
        val ph = ((now + card * 397L) % period.toLong()) / period
        if (ph < 0.35f) {
            val sh = sheenShader
            if (sh != null) {
                val x = -hw - cw * 0.6f + (cw * 2.2f) * (ph / 0.35f)
                sheenMatrix.setRotate(22f)
                sheenMatrix.postTranslate(x, 0f)
                sh.setLocalMatrix(sheenMatrix)
                sheen.shader = sh
                c.drawRoundRect(tmp, rr, rr, sheen)
            }
        }
        // Свои живые эффекты у новых колод (4.10.0).
        when (CardArt.lastStyle) {
            CardArt.STYLE_CYBER -> {
                // Кибер-неон: неон «моргает» как трубка (редкие короткие вспышки) и по карте
                // сверху вниз пробегает строка развёртки.
                val col = CardArt.SUIT_GLOW[suit]
                val flick = ((now / 70L + card * 13L) % 47L).toInt()
                if (flick < 2) {
                    fx.color = col
                    fx.alpha = 46
                    c.drawRoundRect(tmp, rr, rr, fx)
                }
                val sp = ((now + card * 211L) % 2600L) / 2600f
                if (sp < 0.55f) {
                    val y = -hh + ch * (sp / 0.55f)
                    fx.color = col
                    fx.alpha = 70
                    c.drawRect(-hw + cw * 0.05f, y, hw - cw * 0.05f, y + maxOf(1.5f, ch * 0.012f), fx)
                    fx.alpha = 22
                    c.drawRect(-hw + cw * 0.05f, y - ch * 0.05f, hw - cw * 0.05f, y, fx)
                }
            }
            CardArt.STYLE_RUNES -> {
                // Тёмные руны: руны «дышат» светом масти, снизу поднимаются угольки.
                val col = CardArt.SUIT_GLOW[suit]
                if (rank < 5) {
                    fx.color = col
                    fx.alpha = (18 + 16 * sin(now / 520f + card)).toInt().coerceIn(0, 255)
                    c.drawRoundRect(tmp, rr, rr, fx)
                }
                for (k in 0 until 4) {
                    val life = 2200f + k * 330f
                    val t = ((now + card * 173L + k * 557L) % life.toLong()) / life
                    val ex = (((card * 31 + k * 67) % 89) / 89f - 0.5f) * cw * 0.75f + sin(now / 300f + k) * cw * 0.03f
                    val ey = hh - ch * 0.08f - t * ch * 0.8f
                    fx.color = if (k % 2 == 0) 0xFFFFA94D.toInt() else col
                    fx.alpha = (200 * (1f - t) * minOf(1f, t * 6f)).toInt().coerceIn(0, 255)
                    c.drawCircle(ex, ey, maxOf(1.2f, cw * 0.016f * (1f - t * 0.5f)), fx)
                }
            }
        }
        // Искорки: три звёздочки на карте мерцают каждая в своём ритме.
        for (k in 0 until 3) {
            val hx = (((card * 73 + k * 151) % 97) / 97f - 0.5f) * cw * 0.7f
            val hy = (((card * 41 + k * 89) % 101) / 101f - 0.5f) * ch * 0.75f
            val a = sin(now / (420f + k * 90f) + card * 1.7f + k * 2.1f)
            if (a <= 0.35f) continue
            val tw = (a - 0.35f) / 0.65f
            val r = cw * 0.045f * tw
            star.color = if (k == 0) 0xFFFFF7D6.toInt() else 0xFFFFFFFF.toInt()
            star.alpha = (230 * tw).toInt()
            star.strokeWidth = maxOf(1f, cw * 0.012f)
            c.drawLine(hx - r, hy, hx + r, hy, star)
            c.drawLine(hx, hy - r, hx, hy + r, star)
            c.drawCircle(hx, hy, star.strokeWidth, star)
        }
        c.restore()
        if (hand && card == selected) {
            // Выбранная карта: золотое дыхание по краю.
            val pulse = 0.5f + 0.5f * sin(now / 240f)
            glow.strokeWidth = (2f + 2.5f * pulse) * d
            glow.alpha = (150 + 105 * pulse).toInt()
            tmp.set(-hw, -hh, hw, hh)
            c.drawRoundRect(tmp, cw * 0.07f, cw * 0.07f, glow)
            glow.alpha = 255
            glow.strokeWidth = 2.5f * d
        }
    }


    private val sprites = Array(36) { Sprite() }
    private val order = IntArray(36)
    private val orderKey = IntArray(36)
    private var lastDealSound = 0L
    private val lastGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFBBF24.toInt()
    }
    var engine: DurakEngine? = null
        private set
    var me = 0
    var names: List<String> = emptyList()
    var selected = -1
    var onAct: (String, Int, Int) -> Boolean = { _, _, _ -> false }
    var onHint: (String) -> Unit = {}
    private var lastFrame = 0L
    private var initialized = false
    private var cw = 0
    private var ch = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val felt = Paint()
    private val tmp = RectF()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFDE047.toInt()
    }
    var banner = ""
    private var bannerAt = 0L

    // ---------- перетаскивание карты пальцем (свайп на стол) ----------
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downCard = -1
    private var dragCard = -1
    private var dragAt = 0L
    private var dragK = 0f
    private var grabDx = 0f
    private var grabDy = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var tilt = 0f
    private var tiltTarget = 0f
    private var hoverIdx = -1
    private var dragCanTr = false
    private var dragCanAtk = false

    /** Карту бросили на стол, ход отправлен, но новое состояние ещё не пришло (онлайн). */
    private var pendingCard = -1
    private var pendingAt = 0L
    private var pendX = 0f
    private var pendY = 0f

    /** Карта, которую только что «выбросили» пальцем: летит на стол коротко, без разгона. */
    private var thrownCard = -1

    /** Игрок держит карту пальцем - боты подождут с ходом. */
    val dragging: Boolean get() = dragCard >= 0

    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peekGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFDE68A.toInt()
    }
    private val trumpPath = Path()

    // ---------- «бот думает» ----------
    private var thinkP = -1
    private var thinkAt = 0L
    private var thinkUntil = 0L

    /**
     * Бот [p] обдумывает ход [ms] мс: у его имени - «…», а выбранная карта [card] (если есть)
     * во второй половине раздумий приподнимается из веера.
     */
    fun think(p: Int, card: Int, ms: Long) {
        val now = SystemClock.uptimeMillis()
        thinkP = p
        thinkAt = now
        thinkUntil = now + ms
        if (card in 0..35 && sprites[card].zone == 1) {
            val s = sprites[card]
            s.peekAt = now + (ms * 0.42f).toLong()
            s.peekEnd = 0L
        }
        postInvalidateOnAnimation()
    }

    /** Раздумья закончились - ход сейчас прилетит. */
    fun endThinking() {
        thinkP = -1
    }

    /** Задуманный ход отменён (сходил человек, новая партия): карта плавно опускается в веер. */
    fun cancelThinking() {
        thinkP = -1
        val now = SystemClock.uptimeMillis()
        for (s in sprites) if (s.peekAt > 0L && s.peekEnd == 0L) s.peekEnd = now
        invalidate()
    }

    private val lite: Float get() = if (LiteMode.on) 0.8f else 1f

    private fun smooth01(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** easeOutBack: доезжает чуть дальше цели и мягко возвращается. */
    private fun backOut(t: Float, k: Float): Float {
        val u = t - 1f
        return 1f + (k + 1f) * u * u * u + k * u * u
    }

    fun flash(msg: String) {
        banner = msg
        bannerAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private var prevTable = 0
    private var prevDefender = -1
    private var prevOnTable = emptySet<Int>()

    /**
     * «Досмотреть отбой»: последняя карта защиты сразу закончила ход («Бито»), но сначала она
     * честно ложится на стол, и только потом стол уезжает в сброс. Здесь - пары того стола.
     */
    private val stage = IntArray(12)
    private var stageN = 0
    private var stageUntil = 0L
    private var stageToken = 0

    private fun inStage(c: Int): Boolean {
        for (i in 0 until stageN * 2) if (stage[i] == c) return true
        return false
    }

    /** Идёт анимация (боты ждут, пока карты долетят, - ход видно). */
    val busy: Boolean
        get() {
            val now = SystemClock.uptimeMillis()
            return now < stageUntil || sprites.any { it.zone >= 0 && now < it.t0 + it.dur }
        }

    /** Новая партия: раздать заново с анимацией из колоды. */
    fun newDeal() {
        initialized = false
        prevOnTable = emptySet()
        thinkP = -1
        dragCard = -1
        downCard = -1
        pendingCard = -1
        thrownCard = -1
        stageN = 0
        stageUntil = 0L
        stageToken++
    }

    fun setState(e: DurakEngine) {
        // Какие карты только что легли на стол - их подсвечиваем и подписываем, кто сходил.
        val onTable = e.table.flatMap { listOfNotNull(it[0], it[1].takeIf { c -> c >= 0 }) }.toSet()
        val now0 = SystemClock.uptimeMillis()
        for (pr in e.table) {
            if (pr[0] !in prevOnTable) {
                sprites[pr[0]].playedAt = now0
                sprites[pr[0]].playedBy = if (prevOnTable.isEmpty() || e.lastEvent == "transfer") e.attacker else -2
            }
            if (pr[1] >= 0 && pr[1] !in prevOnTable) {
                sprites[pr[1]].playedAt = now0
                sprites[pr[1]].playedBy = e.defender
            }
        }
        // Отбились последней картой - и сразу «Бито»: сначала показываем, как карта ложится
        // на стол, а потом весь стол уходит в сброс.
        stageToken++
        stageN = 0
        stageUntil = 0L
        val n = prevTable
        if (initialized && n in 1..6 && e.lastEvent == "bito" && e.table.isEmpty() && e.discard.size >= n * 2) {
            val base = e.discard.size - n * 2
            var ok = true
            var fresh = false
            for (i in 0 until n) {
                val atk = e.discard[base + i * 2]
                val def = e.discard[base + i * 2 + 1]
                if (atk !in prevOnTable) ok = false
                if (def !in prevOnTable) fresh = true
            }
            if (ok && fresh) {
                for (i in 0 until n * 2) stage[i] = e.discard[base + i]
                stageN = n
                for (i in 0 until n) {
                    val def = stage[i * 2 + 1]
                    if (def !in prevOnTable) {
                        sprites[def].playedAt = now0
                        sprites[def].playedBy = prevDefender
                    }
                }
            }
        }
        prevOnTable = onTable
        engine = e
        if (selected >= 0 && selected !in e.hands[me]) selected = -1
        // Стол опустел: «Беру» или «Бито».
        val emptied = prevTable > 0 && e.table.isEmpty() && e.lastEvent.isNotEmpty() && !e.finished
        val take = e.lastEvent == "take"
        if (emptied && stageN == 0) {
            flash(context.getString(if (take) R.string.durak_ev_take else R.string.durak_ev_bito))
            GameSfx.play(context, if (take) GameSfx.S.TAKE else GameSfx.S.BITO)
        }
        // Переводной: ход перевели - отбивается следующий.
        if (initialized && e.lastEvent == "transfer" && prevDefender >= 0 && prevDefender != e.defender) {
            flash(context.getString(R.string.durak_ev_transfer, names.getOrElse(e.defender) { "?" }))
            GameSfx.play(context, GameSfx.S.PLACE, 1.15f)
        }
        prevDefender = e.defender
        prevTable = e.table.size
        layoutTargets(animate = initialized)
        if (stageN > 0) {
            // Пока последняя карта летит и ложится, стол стоит; потом - «Бито».
            var flight = 0L
            for (i in 0 until stageN * 2) {
                val sp = sprites[stage[i]]
                if (sp.t0 + sp.dur - now0 > flight) flight = sp.t0 + sp.dur - now0
            }
            val hold = flight + (if (LiteMode.on) 320L else 420L)
            stageUntil = now0 + hold
            val token = stageToken
            postDelayed({
                if (token == stageToken && engine === e) {
                    stageN = 0
                    stageUntil = 0L
                    if (emptied) {
                        flash(context.getString(R.string.durak_ev_bito))
                        GameSfx.play(context, GameSfx.S.BITO)
                    }
                    layoutTargets(animate = true)
                    invalidate()
                }
            }, hold)
        }
        // Карта, которую бот приподнял, но так и не сыграл, - плавно опускается обратно.
        if (thinkP < 0) for (s in sprites) if (s.peekAt > 0L && s.peekEnd == 0L) s.peekEnd = now0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Карты крупные (раньше были мелкие): ширина - до четверти экрана. Картинки авторской
        // колоды - 320 пикселей в ширину: больше не растягиваем, чтобы рисунок оставался чётким.
        // Высокие карты (руны) - уже, чтобы по высоте помещались так же (4.10.0).
        var big = minOf(w / 4.4f, 124 * d, h / 4.9f * (1.48f / CardArt.ratio(context)))
        if (CardArt.art(context)) big = minOf(big, CardArt.ART_W.toFloat())
        cw = big.toInt().coerceAtLeast((30 * d).toInt())
        ch = (cw * CardArt.ratio(context)).toInt()
        CardArt.prewarm(context, cw, ch)
        cardClip.reset()
        cardClip.addRoundRect(RectF(-cw / 2f, -ch / 2f, cw / 2f, ch / 2f), cw * 0.07f, cw * 0.07f, Path.Direction.CW)
        sheenShader = LinearGradient(0f, 0f, cw * 0.5f, 0f, intArrayOf(0x00FFFFFF, 0x3DFFFFFF, 0x66FFF7D6, 0x3DFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        haloShaders.fill(null)
        felt.shader = RadialGradient(w / 2f, h * 0.42f, maxOf(w, h) * 0.75f, 0xFF4C3FB0.toInt(), 0xFF171340.toInt(), Shader.TileMode.CLAMP)
        glow.strokeWidth = 2.5f * d
        layoutTargets(animate = false)
    }

    private fun deckPos() = maxOf(width * 0.11f, cw * 0.42f + 2 * d) to (height * 0.42f)
    private fun discardPos() = minOf(width * 0.9f, width - cw * 0.42f - 2 * d) to (height * 0.42f)

    /** Масштаб карт соперников (веер рубашек сверху): чем их больше, тем мельче. */
    private fun oppScale(e: DurakEngine) = if (e.players >= 4) 0.46f else if (e.players == 3) 0.52f else 0.56f
    private fun oppCy(e: DurakEngine) = ch * oppScale(e) * 0.5f + 8 * d

    /** Масштаб карт на столе: крупно, пока карт мало; при 3 в ряд - чтобы влезли между колодой и сбросом. */
    private fun tableScale(inRow: Int): Float {
        val free = width - cw * 1.7f
        val need = inRow * cw * 1.16f + cw * 0.2f
        return (free / need).coerceIn(0.6f, 0.95f)
    }

    private var dealCounter = 0

    /** Где должна быть каждая карта по текущему состоянию. */
    private fun layoutTargets(animate: Boolean) {
        val e = engine ?: return
        if (width == 0) return
        val now = SystemClock.uptimeMillis()
        dealCounter = 0
        // Первый показ: все карты в колоде - и раздаются по одной.
        val first = !initialized
        if (first) {
            // Новая раздача - шелест тасуемой колоды.
            GameSfx.prepare(context)
            postDelayed({ GameSfx.play(context, GameSfx.S.SHUFFLE) }, 150)
            val (sx, sy) = deckPos()
            for (s in sprites) {
                s.x = sx
                s.y = sy
                s.rot = 0f
                s.scale = 0.92f
                s.face = 0f
                s.zone = 0
                s.tx = sx
                s.ty = sy
                s.t0 = 0L
                s.dur = 0L
                s.arc = false
                s.spin = 0f
                s.overshoot = 0f
                s.land = false
                s.landAt = 0L
                s.peekAt = 0L
                s.peekEnd = 0L
                s.pox = 0f
                s.poy = 0f
                s.prot = 0f
                s.psc = 1f
            }
            initialized = true
        }
        val anim = animate || first
        val lf = lite
        // Сколько карт уходит со стола («Беру»/«Бито») - они улетают по одной, а добор из
        // колоды начинается, когда стол почти расчищен.
        var sweepTotal = 0
        for (c in 0 until 36) {
            if (sprites[c].zone != 2 || stageN > 0) continue
            var still = false
            for (pr in e.table) if (pr[0] == c || pr[1] == c) still = true
            if (!still) sweepTotal++
        }
        val sweepStep = ((if (e.lastEvent == "take") 80L else 60L) * lf).toLong()
        val dealBase = if (anim && sweepTotal > 0) sweepTotal * sweepStep + (220L * lf).toLong() else 0L
        var sweepCounter = 0
        fun place(c: Int, zone: Int, x: Float, y: Float, rot: Float, scale: Float, face: Float, z: Int) {
            val s = sprites[c]
            val changed = s.tx != x || s.ty != y || s.trot != rot || s.tscale != scale || s.tface != face
            // Карту, которую держат пальцем, увели из руки (онлайн-обновление) - отпускаем.
            if (c == dragCard && zone != 3) dragCard = -1
            if (anim && changed) {
                // Карта уже летела - продолжаем без рывка (плавное торможение), иначе - плавный
                // разгон и торможение.
                val midFlight = s.dur > 0 && now < s.t0 + s.dur && now >= s.t0
                s.fromRest = !midFlight
                // Новая цель: полёт начинается с текущего положения (с учётом того, что бот
                // уже приподнял карту из веера).
                s.fx = s.x + s.pox
                s.fy = s.y + s.poy
                s.frot = s.rot + s.prot
                s.fscale = s.scale * s.psc
                s.fface = s.face
                s.peekAt = 0L
                s.peekEnd = 0L
                s.pox = 0f
                s.poy = 0f
                s.prot = 0f
                s.psc = 1f
                s.arc = false
                s.spin = 0f
                s.overshoot = 0f
                s.land = false
                s.dealSound = false
                val from = s.zone
                // Направление закрута - своё у каждой карты, но всегда одно и то же.
                val sign = if ((c * 7 + zone) % 2 == 0) 1f else -1f
                var curve = 0f
                when {
                    from == 0 && zone != 0 -> {
                        // Из колоды - раздача по одной, в ровном ритме, по лёгкой дуге; звук -
                        // когда карта взлетает.
                        val delay = dealBase + dealCounter * (140L * lf).toLong() + (if (first) 650L else 0L)
                        s.t0 = now + delay
                        s.dur = (430L * lf).toLong()
                        s.fromRest = true
                        s.dealSound = true
                        curve = 0.1f
                        s.spin = sign * 6f * lf
                        dealCounter++
                    }
                    from == 2 && zone != 2 && !midFlight -> {
                        // «Беру» - карты стола одна за другой уезжают в руку; «Бито» - в сброс.
                        s.t0 = now + sweepCounter * sweepStep
                        s.dur = ((if (zone == 4) 470L else 440L) * lf).toLong()
                        curve = if (zone == 4) 0.14f else 0.18f
                        s.spin = sign * (if (zone == 4) 16f else 9f) * lf
                        sweepCounter++
                    }
                    zone == 2 && (c == thrownCard || c == pendingCard) -> {
                        // Выброшена пальцем: короткий добег от места, где отпустили.
                        s.t0 = now
                        s.dur = (280L * lf).toLong()
                        s.fromRest = false
                        s.overshoot = 0.55f
                        s.land = true
                        curve = 0.05f
                        if (c == pendingCard) pendingCard = -1
                    }
                    zone == 2 && from != 2 && !midFlight -> {
                        // Ход бота или ход тапом: дуга с закрутом, лёгкий перелёт и посадка.
                        val bot = from == 1
                        s.t0 = now
                        s.dur = ((if (bot) 560L else 440L) * lf).toLong()
                        s.overshoot = if (bot) 0.9f else 0.7f
                        s.land = true
                        curve = if (bot) 0.22f else 0.14f
                        s.spin = sign * (if (bot) 14f else 8f) * lf
                    }
                    else -> {
                        s.t0 = now
                        s.dur = if (zone == 4) 460L else 380L
                        if (zone == 2) s.land = from != 2
                    }
                }
                if (curve > 0f) {
                    // Контрольная точка - сбоку от прямой «откуда - куда»: дуга выгибается
                    // вверх (как у брошенной карты), а у вертикального полёта - вбок.
                    val dx = x - s.fx
                    val dy = y - s.fy
                    val len = kotlin.math.hypot(dx, dy)
                    if (len > 2f * d) {
                        var nx = -dy / len
                        var ny = dx / len
                        if (abs(ny) < 0.35f) {
                            if (nx * sign < 0f) {
                                nx = -nx
                                ny = -ny
                            }
                        } else if (ny > 0f) {
                            nx = -nx
                            ny = -ny
                        }
                        s.arc = true
                        s.cx = (s.fx + x) / 2f + nx * len * curve
                        s.cy = (s.fy + y) / 2f + ny * len * curve
                    }
                }
                s.delayUntil = s.t0
            }
            s.tx = x
            s.ty = y
            s.trot = rot
            s.tscale = scale
            s.tface = face
            s.z = z
            if (!anim) {
                s.x = x
                s.y = y
                s.rot = rot
                s.scale = scale
                s.face = face
                s.dur = 0L
            }
            s.zone = zone
        }
        val (dx, dy) = deckPos()
        // Колода: козырь лежит под ней поперёк, лицом вверх.
        for ((i, c) in e.deck.withIndex()) {
            if (i == 0) place(c, 0, dx + cw * 0.3f, dy, 90f, 0.72f, 1f, 1) else place(c, 0, dx - i * 0.3f * d, dy - i * 0.3f * d, 0f, 0.78f, 0f, 2 + i)
        }
        val staging = stageN > 0
        val (xx, xy) = discardPos()
        for ((i, c) in e.discard.withIndex()) {
            if (staging && inStage(c)) continue
            val rr = ((c * 37) % 30 - 15).toFloat()
            place(c, 4, xx + ((c * 13) % 9 - 4) * d, xy + ((c * 7) % 9 - 4) * d, rr, 0.72f, 0f, 50 + i)
        }
        // Соперники сверху: веер рубашек. Пока на столе «досматривают» последний отбой, добор
        // из колоды ещё не раздаётся (такие карты пока лежат в колоде).
        val nOpp = e.players - 1
        for (j in 0 until nOpp) {
            val p = (me + j + 1) % e.players
            val cx = width * (j + 1f) / (nOpp + 1f)
            val cy = oppCy(e)
            val os = oppScale(e)
            val hand = e.hands[p]
            var n = 0
            for (c in hand) if (!(staging && sprites[c].zone == 0)) n++
            val spread = minOf(14f * d, (width / (nOpp + 1f) - cw * os) / maxOf(1, n - 1))
            var k = 0
            for (c in hand) {
                if (staging && sprites[c].zone == 0) continue
                val off = (k - (n - 1) / 2f)
                place(c, 1, cx + off * spread, cy + abs(off) * 0.6f * d, off * 4f, os, 0f, 100 + k)
                k++
            }
        }
        // Стол: до 6 пар в два ряда по три.
        val pairs = if (staging) stageN else e.table.size
        val rows = if (pairs > 3) 2 else 1
        val tcx = width * 0.5f
        val tcy = height * 0.42f
        val ts = tableScale(minOf(3, maxOf(1, pairs)))
        // Два ряда - карты чуть мельче, чтобы не наезжать на соперников и свою руку.
        val tsc = if (rows == 2) minOf(ts, (height * 0.40f) / (ch * 2.25f)) else ts
        for (i in 0 until pairs) {
            val a0 = if (staging) stage[i * 2] else e.table[i][0]
            val a1 = if (staging) stage[i * 2 + 1] else e.table[i][1]
            val row = if (rows == 2) i / 3 else 0
            val inRow = if (rows == 2) (if (row == 0) 3 else pairs - 3) else pairs
            val col = if (rows == 2) i % 3 else i
            val x = tcx + (col - (inRow - 1) / 2f) * cw * 1.16f * tsc - cw * 0.08f * tsc
            val y = tcy + (row - (rows - 1) / 2f) * ch * 1.12f * tsc
            place(a0, 2, x, y, -4f, tsc, 1f, 200 + i * 2)
            if (a1 >= 0) place(a1, 2, x + cw * 0.18f * tsc, y + ch * 0.15f * tsc, 8f, tsc, 1f, 201 + i * 2)
        }
        // Мои карты: веер снизу, выбранная приподнята.
        val hand = e.hands[me].filter { !(staging && sprites[it].zone == 0) }
            .sortedWith(compareBy({ if (it / 9 == e.trump) 1 else 0 }, { it / 9 }, { it % 9 }))
        val n = hand.size
        val maxSpan = width - cw * 1.1f
        val step = if (n > 1) minOf(cw * 0.66f, maxSpan / (n - 1)) else 0f
        val baseY = height - ch * 0.62f
        for ((k, c) in hand.withIndex()) {
            val off = k - (n - 1) / 2f
            val lift = if (c == selected) ch * 0.28f else if (playable(c)) ch * 0.06f else 0f
            place(c, 3, width / 2f + off * step, baseY + abs(off) * 1.8f * d - lift, off * 2.2f, 1f, 1f, 300 + k)
        }
        postInvalidateOnAnimation()
    }

    /** Можно ли сейчас сыграть эту карту. */
    fun playable(c: Int): Boolean {
        val e = engine ?: return false
        if (e.finished) return false
        if (me == e.defender) return e.uncovered().any { e.beats(c, e.table[it][0]) } || e.canTransfer(me, c)
        return e.canAttack(me, c)
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), felt)
        val e = engine ?: return
        if (cw == 0) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1L, 50L).toFloat()
        lastFrame = now
        var moving = false
        // Подписи соперников; пока бот думает - у имени бегут три точки.
        val nOpp = e.players - 1
        text.textSize = 12.5f * d
        for (j in 0 until nOpp) {
            val p = (me + j + 1) % e.players
            val cx = width * (j + 1f) / (nOpp + 1f)
            val y = oppCy(e) + ch * oppScale(e) * 0.5f + 16 * d
            val role = when {
                e.finished && e.durak == p -> " 🃏"
                p == e.defender -> " 🛡"
                p == e.attacker -> " ⚔"
                p in e.out -> " ✓"
                else -> ""
            }
            val label = names.getOrElse(p) { "?" }.take(12) + role + "  " + e.hands[p].size
            val w = text.measureText(label) / 2 + 10 * d
            val thinking = p == thinkP && now < thinkUntil + 400
            val extra = if (thinking) 24 * d else 0f
            chip.color = if (p == e.defender || p == e.attacker) 0x99FDE047.toInt() else 0x55000000
            tmp.set(cx - w, y - 15 * d, cx + w + extra, y + 6 * d)
            c.drawRoundRect(tmp, 11 * d, 11 * d, chip)
            c.drawText(label, cx, y, text)
            if (thinking) {
                for (k in 0 until 3) {
                    val a = sin((now - thinkAt) / 150f - k * 0.9f).coerceAtLeast(0f)
                    sparkPaint.color = Color.WHITE
                    sparkPaint.alpha = (90 + 165 * a).toInt()
                    c.drawCircle(cx + w + 5 * d + k * 7 * d, y - 4.5f * d - 2 * d * a, 2.2f * d, sparkPaint)
                }
                moving = true
            }
        }
        // Колода: счётчик.
        val (dx, dy) = deckPos()
        if (e.deck.isNotEmpty()) {
            text.textSize = 13 * d
            c.drawText(e.deck.size.toString(), dx, dy + ch * 0.52f, text)
        } else if (e.trumpCard >= 0) {
            // Колода кончилась - показываем, какой был козырь.
            chip.color = 0x55000000
            c.drawCircle(dx, dy, cw * 0.32f, chip)
            chip.color = if (CardArt.isRed(e.trump)) 0xFFFB7185.toInt() else Color.WHITE
            CardArt.suitPath(trumpPath, e.trump, dx, dy, cw * 0.36f)
            c.drawPath(trumpPath, chip)
        }
        // Тащу карту и её можно положить на стол (зайти, подкинуть, перевести) - стол мягко
        // светится; над столом - ярче.
        val dc = dragCard
        if (dc >= 0 && (if (me == e.defender) dragCanTr else dragCanAtk)) {
            val pulse = 0.5f + 0.5f * sin(now / 200f)
            val over = dragY < handLine()
            val col = if (me == e.defender) 0x60A5FA else 0xFBBF24
            val hw = (width - cw * 1.6f) / 2f
            tmp.set(width / 2f - hw, height * 0.42f - ch * 0.8f, width / 2f + hw, height * 0.42f + ch * 0.8f)
            zonePaint.color = col or ((if (over) 0x34 else 0x14 + (16 * pulse).toInt()) shl 24)
            c.drawRoundRect(tmp, 22 * d, 22 * d, zonePaint)
            dropPaint.color = col or ((if (over) 0xDD else 0x66 + (60 * pulse).toInt()) shl 24)
            dropPaint.strokeWidth = (if (over) 2.5f else 1.5f) * d
            c.drawRoundRect(tmp, 22 * d, 22 * d, dropPaint)
        }
        // Карты по слоям.
        // Порядок слоёв без лишних объектов в каждом кадре (раньше - сортировка со списками,
        // от неё при 3-4 игроках кадры «дёргались»).
        for (i in 0 until 36) {
            val sp = sprites[i]
            orderKey[i] = sp.z + when {
                i == dc -> 3000
                i == pendingCard -> 2500
                now < sp.t0 + sp.dur && now >= sp.t0 -> 1000
                sp.peekAt > 0L -> 500
                else -> 0
            }
            order[i] = i
        }
        for (i in 1 until 36) {
            val v = order[i]
            var j = i - 1
            while (j >= 0 && orderKey[order[j]] > orderKey[v]) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = v
        }
        val back = CardArt.back(context, cw, ch)
        for (card in order) {
            val s = sprites[card]
            if (s.zone < 0) continue
            var flying = false
            var fp = 0f
            if (card == dc) {
                // Карта у меня в пальцах: следует за ним, наклоняется по скорости движения.
                dragK = smooth01((now - dragAt) / 140f)
                tiltTarget *= exp(-dt / 110f)
                tilt += (tiltTarget - tilt) * (1f - exp(-dt / 50f))
                s.x = dragX
                s.y = dragY
                s.rot = tilt
                s.scale = 1f + 0.04f * dragK
                s.face = 1f
                moving = true
            } else if (card == pendingCard) {
                // Ход отправлен, ждём ответ: карта плавно подтягивается к столу.
                if (now - pendingAt > 1600) {
                    pendingCard = -1
                    springBack(card)
                } else {
                    val f = 1f - exp(-dt / 120f)
                    s.x += (pendX - s.x) * f
                    s.y += (pendY - s.y) * f
                    s.rot -= s.rot * f
                }
                moving = true
            } else if (s.dur > 0 && now < s.t0 + s.dur) {
                moving = true
                if (now >= s.t0) {
                    flying = true
                    if (s.dealSound) {
                        s.dealSound = false
                        // Шорох карты - синхронно с её вылетом (не чаще раза в 60 мс).
                        if (now - lastDealSound > 60) {
                            lastDealSound = now
                            GameSfx.play(context, GameSfx.S.DEAL, 0.9f + (card % 5) * 0.06f, 0.75f)
                        }
                    }
                    val p = ((now - s.t0).toFloat() / s.dur).coerceIn(0f, 1f)
                    fp = p
                    val base = if (s.fromRest) {
                        // easeInOutCubic: без резкого старта.
                        if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).let { it * it * it } / 2f
                    } else {
                        1f - (1f - p) * (1f - p) * (1f - p)
                    }
                    // Путь - с лёгким перелётом цели (если задан) и мягким разгоном.
                    val ep = if (s.overshoot > 0f) {
                        backOut(if (s.fromRest) 0.5f * p + 0.5f * p * p * (3f - 2f * p) else p, s.overshoot)
                    } else {
                        base
                    }
                    if (s.arc) {
                        val u = 1f - ep
                        s.x = u * u * s.fx + 2f * u * ep * s.cx + ep * ep * s.tx
                        s.y = u * u * s.fy + 2f * u * ep * s.cy + ep * ep * s.ty
                    } else {
                        s.x = s.fx + (s.tx - s.fx) * ep
                        s.y = s.fy + (s.ty - s.fy) * ep
                    }
                    // Закрут в полёте гаснет к посадке - карта ложится ровно.
                    s.rot = s.frot + (s.trot - s.frot) * base + s.spin * sin(p * PI_F)
                    s.scale = s.fscale + (s.tscale - s.fscale) * base
                    // Переворот - во второй половине полёта.
                    val pf = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    s.face = s.fface + (s.tface - s.fface) * pf
                }
            } else {
                s.x = s.tx
                s.y = s.ty
                s.rot = s.trot
                s.scale = s.tscale
                s.face = s.tface
            }
            // Бот выбрал карту: она приподнимается из веера и чуть покачивается.
            var peekA = 0f
            if (s.peekAt > 0L) {
                val age = now - s.peekAt
                peekA = if (age <= 0L) 0f else smooth01(age / (if (LiteMode.on) 170f else 240f))
                var done = false
                if (s.peekEnd > 0L) {
                    val f = smooth01((now - s.peekEnd) / 200f)
                    peekA *= 1f - f
                    done = f >= 1f
                }
                if (done || s.zone != 1) {
                    s.peekAt = 0L
                    s.peekEnd = 0L
                    peekA = 0f
                }
                s.poy = peekA * ch * s.scale * 0.45f
                s.prot = -s.rot * 0.7f * peekA + (if (LiteMode.on) 0f else sin(age / 115f) * 2.6f * peekA)
                s.psc = 1f + 0.16f * peekA
                moving = true
            } else {
                s.poy = 0f
                s.prot = 0f
                s.psc = 1f
            }
            val shake = if (now - s.shakeAt < 400) sin((now - s.shakeAt) / 25f) * 6 * d * (1 - (now - s.shakeAt) / 400f) else 0f
            if (now - s.shakeAt < 400) moving = true
            val flip = abs(1f - 2f * s.face).coerceAtLeast(0.03f)
            val ready = if (s.face >= 0.5f) CardArt.faceOrNull(context, card, cw, ch, faceReady) else null
            if (s.face >= 0.5f && ready == null) moving = true
            val bmp = ready ?: back
            // Карта в полёте (и в пальцах) приподнимается над столом: крупнее, тень дальше.
            // При посадке на стол - искры и мягкая «пружинка».
            val lift = if (flying) sin(fp * PI_F) else if (card == dc) dragK else 0f
            if (s.wasMoving && !flying && s.zone == 2) {
                burst(s.x, s.y, CardArt.SUIT_GLOW[card / 9])
                GameSfx.play(context, GameSfx.S.PLACE, 0.95f + (card % 4) * 0.05f)
                if (s.land) {
                    s.land = false
                    s.landAt = now
                }
            }
            s.wasMoving = flying
            var bounce = 0f
            if (s.landAt > 0L) {
                val la = (now - s.landAt).toFloat()
                val len = if (LiteMode.on) 220f else 300f
                if (la < len) {
                    val k = la / len
                    bounce = sin(k * 2f * PI_F) * (1f - k) * (1f - k)
                    moving = true
                } else {
                    s.landAt = 0L
                }
            }
            // Карты в руке еле заметно «дышат».
            val bob = if (live && s.zone == 3 && !flying && card != dc) sin(now / 700f + card) * 1.2f * d else 0f
            val sc = s.scale * s.psc * (1f + 0.1f * lift) * (1f - 0.06f * bounce)
            c.save()
            c.translate(s.x + shake + s.pox, s.y + s.poy + bob - lift * 6 * d)
            c.rotate(s.rot + s.prot + (if (flying && !s.arc) sin(now / 90f + card) * 2.5f * lift else 0f) + bounce * 1.5f)
            c.scale(sc * flip, sc)
            val so = (3 + 9 * lift) * d
            val spread = abs(bounce) * 3 * d
            shadow.alpha = (64 - 24 * lift + 40 * abs(bounce)).toInt().coerceIn(0, 255)
            tmp.set(-cw / 2f + so * 0.6f - spread, -ch / 2f + so - spread, cw / 2f + so * 0.6f + spread, ch / 2f + so + spread)
            c.drawRoundRect(tmp, cw * 0.09f, cw * 0.09f, shadow)
            c.drawBitmap(bmp, -cw / 2f, -ch / 2f, paint)
            if (s.face >= 0.5f && live && flip > 0.5f && !flying && CardArt.art(context) && !(LiteMode.on && s.zone != 3)) {
                drawLife(c, card, now, s.zone == 3)
            } else if (s.zone == 3 && card == selected) {
                tmp.set(-cw / 2f, -ch / 2f, cw / 2f, ch / 2f)
                c.drawRoundRect(tmp, cw * 0.11f, cw * 0.11f, glow)
            }
            if (peekA > 0.01f) {
                // Подсветка карты, которую бот вот-вот сыграет.
                peekGlow.strokeWidth = 2.2f * d
                peekGlow.alpha = (210 * peekA).toInt()
                tmp.set(-cw / 2f, -ch / 2f, cw / 2f, ch / 2f)
                c.drawRoundRect(tmp, cw * 0.11f, cw * 0.11f, peekGlow)
            }
            // Последний ход: только что сыгранная карта светится золотом 1.8 с - видно,
            // чем сходил бот или соперник.
            val pAge = now - s.playedAt
            if (s.zone == 2 && s.playedAt > 0 && pAge < 1800 && !flying) {
                val a = 1f - pAge / 1800f
                lastGlow.strokeWidth = (2.5f + 2f * sin(pAge / 120f).coerceAtLeast(0f)) * d
                lastGlow.alpha = (255 * a).toInt()
                tmp.set(-cw / 2f - 2 * d, -ch / 2f - 2 * d, cw / 2f + 2 * d, ch / 2f + 2 * d)
                c.drawRoundRect(tmp, cw * 0.12f, cw * 0.12f, lastGlow)
                moving = true
            }
            c.restore()
            if (s.zone == 2 && s.playedAt > 0 && pAge < 1800 && !flying && s.playedBy >= 0 && s.playedBy != me) {
                // Подпись «кто сходил» над картой.
                val nm = names.getOrElse(s.playedBy) { "" }.take(10)
                if (nm.isNotEmpty()) {
                    text.textSize = 11.5f * d
                    val a = (255 * (1f - pAge / 1800f)).toInt()
                    val w = text.measureText(nm) / 2 + 7 * d
                    chip.color = 0xCC1F1147.toInt()
                    chip.alpha = a
                    tmp.set(s.x - w, s.y - ch * s.scale / 2 - 18 * d, s.x + w, s.y - ch * s.scale / 2 - 2 * d)
                    c.drawRoundRect(tmp, 8 * d, 8 * d, chip)
                    text.alpha = a
                    c.drawText(nm, s.x, s.y - ch * s.scale / 2 - 6 * d, text)
                    text.alpha = 255
                    chip.alpha = 255
                }
            }
        }
        if (dc >= 0 && me == e.defender) {
            // Тащу карту, отбиваясь: зелёным - что ею можно побить, ярче - карта под пальцем.
            val pulse = 0.5f + 0.5f * sin(now / 180f)
            for (i in 0 until e.table.size) {
                if (e.table[i][1] >= 0) continue
                val s = sprites[e.table[i][0]]
                val ok = e.beats(dc, e.table[i][0])
                val hov = i == hoverIdx
                dropPaint.color = if (ok) 0xFF34D399.toInt() else if (hov && dragCanTr) 0xFF60A5FA.toInt() else 0xFFFFFFFF.toInt()
                dropPaint.alpha = if (ok || hov) (170 + 85 * pulse).toInt() else 80
                dropPaint.strokeWidth = (if (hov) 3.5f + 1.5f * pulse else 2f + pulse) * d
                val grow = if (hov) 1.06f else 1f
                c.save()
                c.translate(s.x, s.y)
                c.rotate(s.rot)
                c.scale(s.scale * grow, s.scale * grow)
                tmp.set(-cw / 2f - 2 * d, -ch / 2f - 2 * d, cw / 2f + 2 * d, ch / 2f + 2 * d)
                c.drawRoundRect(tmp, cw * 0.12f, cw * 0.12f, dropPaint)
                c.restore()
            }
        } else if (me == e.defender && selected >= 0) {
            // Подсветка карт стола, которые надо побить (я отбиваюсь и выбрал карту).
            for (i in 0 until e.table.size) {
                if (e.table[i][1] >= 0) continue
                val s = sprites[e.table[i][0]]
                val ok = e.beats(selected, e.table[i][0])
                glow.color = if (ok) 0xFF34D399.toInt() else 0x66FFFFFF
                c.save()
                c.translate(s.x, s.y)
                c.rotate(s.rot)
                c.scale(s.scale, s.scale)
                tmp.set(-cw / 2f, -ch / 2f, cw / 2f, ch / 2f)
                c.drawRoundRect(tmp, cw * 0.11f, cw * 0.11f, glow)
                c.restore()
            }
            glow.color = 0xFFFDE047.toInt()
        }
        // Искры от сыгранных карт.
        if (sparks.isNotEmpty()) {
            val it = sparks.iterator()
            while (it.hasNext()) {
                val sp = it.next()
                val age = now - sp[4]
                if (age > 700f) {
                    it.remove()
                    continue
                }
                val x = sp[0] + sp[2] * age
                val y = sp[1] + sp[3] * age + 0.00035f * d * age * age
                sparkPaint.color = sp[5].toInt()
                sparkPaint.alpha = (255 * (1f - age / 700f)).toInt()
                c.drawCircle(x, y, (2.6f - age / 400f).coerceAtLeast(0.6f) * d, sparkPaint)
            }
            moving = true
        }
        val bAge = now - bannerAt
        if (banner.isNotEmpty() && bAge < 1400) {
            text.textSize = 22 * d
            val a = (255 * (1 - bAge / 1400f)).toInt().coerceIn(0, 255)
            val w = text.measureText(banner) / 2 + 18 * d
            chip.color = Color.argb(a * 2 / 3, 0, 0, 0)
            tmp.set(width / 2f - w, height * 0.42f - 26 * d, width / 2f + w, height * 0.42f + 12 * d)
            c.drawRoundRect(tmp, 19 * d, 19 * d, chip)
            text.alpha = a
            c.drawText(banner, width / 2f, height * 0.42f, text)
            text.alpha = 255
            moving = true
        }
        if (moving) {
            postInvalidateOnAnimation()
        } else {
            lastFrame = 0L
            // Живые эффекты (блик, искорки, дыхание) - ~30 кадров/с, без них - тишина.
            if (live && CardArt.art(context) && isShown) postInvalidateDelayed(LiteMode.liveFrameMs)
        }
    }

    private fun hit(card: Int, x: Float, y: Float): Boolean {
        val s = sprites[card]
        return abs(x - s.x) < cw * s.scale / 2 && abs(y - s.y) < ch * s.scale / 2
    }

    /** Колоду сменили в настройках: пересчитать размер карт (у авторской другие пропорции). */
    fun relayout() {
        if (width > 0) onSizeChanged(width, height, width, height)
        invalidate()
    }

    fun shake(card: Int) {
        GameSfx.play(context, GameSfx.S.BAD, 1.2f, 0.7f)
        if (card in 0..35) sprites[card].shakeAt = SystemClock.uptimeMillis()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** Граница «рука - стол»: карту, отпущенную выше, кладём на стол. */
    private fun handLine() = height - ch * 1.2f

    /** Моя карта под пальцем (верхние карты веера - первыми). */
    private fun myCardAt(e: DurakEngine, x: Float, y: Float): Int {
        var best = -1
        var bestZ = Int.MIN_VALUE
        for (c in e.hands[me]) {
            val z = sprites[c].z
            if (z > bestZ && hit(c, x, y)) {
                best = c
                bestZ = z
            }
        }
        return best
    }

    /** Непобитая карта стола под перетаскиваемой картой (когда я отбиваюсь), иначе -1. */
    private fun hoverTarget(e: DurakEngine): Int {
        if (me != e.defender) return -1
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until e.table.size) {
            if (e.table[i][1] >= 0) continue
            val s = sprites[e.table[i][0]]
            val dx = dragX - s.x
            val dy = dragY - s.y
            if (abs(dx) < cw * s.scale * 0.62f && abs(dy) < ch * s.scale * 0.62f) {
                val dd = dx * dx + dy * dy
                if (dd < bestD) {
                    bestD = dd
                    best = i
                }
            }
        }
        return best
    }

    /** Карта плавно, с лёгкой пружинкой, возвращается на своё место в руке. */
    private fun springBack(card: Int) {
        val s = sprites[card]
        s.fx = s.x
        s.fy = s.y
        s.frot = s.rot
        s.fscale = s.scale
        s.fface = s.face
        s.t0 = SystemClock.uptimeMillis()
        s.dur = if (LiteMode.on) 340L else 420L
        s.fromRest = false
        s.arc = false
        s.spin = 0f
        s.overshoot = 1.1f
        s.land = false
        s.dealSound = false
        postInvalidateOnAnimation()
    }

    private fun startDrag(e: DurakEngine, card: Int) {
        val s = sprites[card]
        dragCard = card
        dragAt = SystemClock.uptimeMillis()
        dragK = 0f
        grabDx = s.x - downX
        grabDy = s.y - downY
        dragX = s.x
        dragY = s.y
        tilt = s.rot
        tiltTarget = 0f
        s.dur = 0L
        s.landAt = 0L
        if (pendingCard == card) pendingCard = -1
        hoverIdx = -1
        dragCanTr = e.canTransfer(me, card)
        dragCanAtk = me != e.defender && e.canAttack(me, card)
        parent?.requestDisallowInterceptTouchEvent(true)
        GameSfx.play(context, GameSfx.S.TICK, 1.25f, 0.5f)
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        if (selected != card) {
            selected = card
            layoutTargets(animate = true)
        }
        postInvalidateOnAnimation()
    }

    /** Отпустили карту: на стол (по месту или броском вверх) или обратно в руку. */
    private fun release(e: DurakEngine, vx: Float, vy: Float) {
        val card = dragCard
        val s = sprites[card]
        // Подъём карты «в пальцах» переносим в её положение - дальше полёт без скачка.
        s.x = dragX
        s.y = dragY - dragK * 6 * d
        s.scale *= 1f + 0.1f * dragK
        dragCard = -1
        val target = hoverIdx
        hoverIdx = -1
        val fling = vy < -700f * d && -vy > abs(vx) * 0.7f
        val toTable = fling || dragY < handLine() || target >= 0
        if (!toTable || e.finished || card !in e.hands[me]) {
            springBack(card)
            return
        }
        thrownCard = card
        val ok = dropPlay(e, card, target)
        thrownCard = -1
        if (!ok) {
            springBack(card)
            return
        }
        if (sprites[card].zone == 3) {
            // Онлайн: ход отправлен, состояние придёт чуть позже - карта уже тянется к столу.
            pendingCard = card
            pendingAt = SystemClock.uptimeMillis()
            if (target >= 0 && target < e.table.size) {
                val t = sprites[e.table[target][0]]
                pendX = t.x + cw * 0.18f * t.scale
                pendY = t.y + ch * 0.15f * t.scale
            } else {
                pendX = width / 2f
                pendY = height * 0.42f
            }
        }
        postInvalidateOnAnimation()
    }

    /** Карту бросили на стол: [target] - непобитая карта стола под ней (или -1). */
    private fun dropPlay(e: DurakEngine, card: Int, target: Int): Boolean {
        if (me == e.defender && target >= 0 && target < e.table.size && e.table[target][1] < 0) {
            if (e.beats(card, e.table[target][0])) return send("def", card, target)
            // Переводной: этой картой ничего не побить, но можно перевести - переводим.
            if (e.canTransfer(me, card) && e.uncovered().none { e.beats(card, e.table[it][0]) }) return send("tr", card, -1)
            shake(card)
            onHint(context.getString(R.string.durak_hint_cant_beat))
            return false
        }
        return play(card)
    }

    private fun send(a: String, card: Int, idx: Int): Boolean {
        if (onAct(a, card, idx)) {
            selected = -1
            return true
        }
        shake(card)
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val e = engine ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(ev) }
                downX = ev.x
                downY = ev.y
                downCard = if (e.finished || me in e.out) -1 else myCardAt(e, ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (dragCard < 0 && downCard >= 0 && kotlin.math.hypot(ev.x - downX, ev.y - downY) > slop) {
                    if (!e.finished && downCard in e.hands[me]) startDrag(e, downCard) else downCard = -1
                }
                if (dragCard >= 0) {
                    dragX = ev.x + grabDx
                    dragY = ev.y + grabDy
                    vt?.let {
                        it.computeCurrentVelocity(1000)
                        tiltTarget = (it.xVelocity / d * 0.011f).coerceIn(-15f, 15f)
                    }
                    val h = hoverTarget(e)
                    if (h != hoverIdx) {
                        hoverIdx = h
                        if (h >= 0) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(ev)
                if (dragCard >= 0) {
                    var vx = 0f
                    var vy = 0f
                    vt?.let {
                        it.computeCurrentVelocity(1000)
                        vx = it.xVelocity
                        vy = it.yVelocity
                    }
                    release(e, vx, vy)
                } else {
                    tap(e, ev.x, ev.y)
                }
                downCard = -1
                vt?.recycle()
                vt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragCard >= 0) {
                    val card = dragCard
                    sprites[card].y = dragY - dragK * 6 * d
                    sprites[card].scale *= 1f + 0.1f * dragK
                    dragCard = -1
                    hoverIdx = -1
                    springBack(card)
                }
                downCard = -1
                vt?.recycle()
                vt = null
            }
        }
        return true
    }

    /** Касание без перетаскивания: выбрать карту, второй тап - сыграть, тап по столу - отбить. */
    private fun tap(e: DurakEngine, x: Float, y: Float) {
        if (e.finished) return
        // Сначала - моя рука (верхние карты веера первыми).
        val tapped = myCardAt(e, x, y)
        if (tapped >= 0) {
            if (tapped == selected) play(tapped) else {
                selected = tapped
                GameSfx.play(context, GameSfx.S.TICK, 1.1f, 0.6f)
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                // Подсказка, если картой сейчас не сходить.
                if (!playable(tapped)) onHint(context.getString(R.string.durak_hint_cant))
                layoutTargets(animate = true)
            }
            return
        }
        // Карта на столе: отбить её выбранной картой.
        if (me == e.defender && selected >= 0) {
            val idx = e.uncovered().firstOrNull { hit(e.table[it][0], x, y) }
            if (idx != null) {
                if (onAct("def", selected, idx)) selected = -1 else shake(selected)
                return
            }
        }
        if (selected >= 0) {
            selected = -1
            layoutTargets(animate = true)
        }
    }

    /** Сыграть карту (второй тап или бросок на стол без точной цели). true - ход сделан. */
    private fun play(card: Int): Boolean {
        val e = engine ?: return false
        if (me == e.defender) {
            val options = e.uncovered().filter { e.beats(card, e.table[it][0]) }
            val canTr = e.canTransfer(me, card)
            when {
                // Переводной: этой картой только перевести - переводим сразу.
                canTr && options.isEmpty() -> return send("tr", card, -1)
                canTr -> onHint(context.getString(R.string.durak_hint_transfer_or_beat))
                options.isEmpty() -> {
                    shake(card)
                    onHint(context.getString(R.string.durak_hint_cant_beat))
                }
                options.size == 1 -> return send("def", card, options[0])
                else -> onHint(context.getString(R.string.durak_hint_pick_target))
            }
            return false
        }
        if (send("atk", card, -1)) return true
        onHint(context.getString(if (e.table.isEmpty() && me != e.attacker) R.string.durak_hint_wait else R.string.durak_hint_cant))
        return false
    }

    private companion object {
        const val PI_F = 3.1415927f
    }
}

/** Экран партии «Дурака»: с ботами на одном телефоне или онлайн с контактами. */
object DurakGame {
    private val main = Handler(Looper.getMainLooper())

    /** Игра с ботами ([bots] - 1…3). Незаконченная партия продолжается. */
    /** Режим, выбранный в прошлый раз (для новой партии с ботами). */
    fun lastMode(ctx: Context) = ctx.getSharedPreferences("durak_local", Context.MODE_PRIVATE).getInt("mode", DurakEngine.PODKIDNOY)

    fun modeName(ctx: Context, mode: Int) = ctx.getString(MODE_NAMES.getOrElse(mode) { MODE_NAMES[0] })

    val MODE_NAMES = intArrayOf(R.string.durak_mode_podkidnoy, R.string.durak_mode_perevodnoy, R.string.durak_mode_simple)
    val MODE_DESCS = intArrayOf(R.string.durak_mode_podkidnoy_d, R.string.durak_mode_perevodnoy_d, R.string.durak_mode_simple_d)

    fun showLocal(ctx: Context, bots: Int = 1, fresh: Boolean = false, mode: Int = -1) {
        val prefs = ctx.getSharedPreferences("durak_local", Context.MODE_PRIVATE)
        val saved = if (fresh) null else prefs.getString("game", null)?.let { runCatching { DurakEngine.fromJson(JSONObject(it)) }.getOrNull() }
        val m = if (mode >= 0) mode else lastMode(ctx)
        if (mode >= 0) prefs.edit().putInt("mode", mode).apply()
        val engine = if (saved != null && !saved.finished) saved else DurakEngine(bots.coerceIn(1, 3) + 1, m).apply { deal(System.nanoTime()) }
        val names = listOf(ctx.getString(R.string.durak_you)) + (1 until engine.players).map { ctx.getString(R.string.durak_bot, it) }
        var e = engine
        fun persist() = prefs.edit().putString("game", e.toJson().toString()).commit()
        var screen: Screen? = null
        // Поколение состояния: ход человека или новая партия отменяют задуманный ход бота.
        var gen = 0
        var lastActor = -1
        val rnd = java.util.Random()
        var botRun: Runnable? = null
        // Бот «додумал»: делает задуманный ход (карта уже приподнята из веера и летит на стол).
        val commit = object : Runnable {
            var p = -1
            var g = -1
            override fun run() {
                val sc = screen ?: return
                if (g != gen || e.finished) return
                // Человек держит карту пальцем - не перебиваем его.
                if (sc.table.dragging) {
                    main.postDelayed(this, 80)
                    return
                }
                sc.table.endThinking()
                if (DurakBot.act(e, p)) {
                    gen++
                    lastActor = p
                    persist()
                    sc.update(e)
                    if (e.finished) sc.finishedLocal(e) else botRun?.let { main.postDelayed(it, 140) }
                } else {
                    sc.table.cancelThinking()
                    botRun?.let { main.postDelayed(it, 120) }
                }
            }
        }
        botRun = object : Runnable {
            override fun run() {
                val sc = screen ?: return
                if (e.finished) return
                // Карты ещё летят (или человек тащит карту) - ждём: ход бота должно быть видно.
                if (sc.table.busy || sc.table.dragging) {
                    main.postDelayed(this, 60)
                    return
                }
                // Ход «примеряем» на копии партии, чтобы заранее знать карту: бот сначала
                // думает, присматривается к ней - и только потом ходит. Боты ходят по одному.
                val sim = DurakEngine.fromJson(e.toJson())
                // Сначала отвечает отбивающийся бот, потом подкидывают остальные.
                val order = listOf(e.defender) + (0 until e.players).map { (e.attacker + it) % e.players }.filter { it != e.defender }
                for (p in order) {
                    if (p == 0) continue
                    val before = ArrayList(sim.hands[p])
                    if (!DurakBot.act(sim, p)) continue
                    val card = before.firstOrNull { it !in sim.hands[p] } ?: -1
                    val took = sim.hands[p].size > before.size
                    val ms = thinkMs(e, sim, p, card, took, lastActor, rnd)
                    commit.p = p
                    commit.g = gen
                    sc.table.think(p, card, ms)
                    main.postDelayed(commit, ms)
                    return
                }
            }
        }
        val sc = Screen(
            ctx,
            title = modeName(ctx, engine.mode) + " · " + ctx.getString(R.string.durak_vs_bots, engine.players - 1),
            me = 0,
            names = names,
            chatEnabled = false,
            act = { a, c, i ->
                val ok = when (a) {
                    "atk" -> e.attack(0, c)
                    "def" -> e.defend(0, i, c)
                    "tr" -> e.transfer(0, c)
                    "take" -> e.take(0)
                    "pass" -> e.pass(0)
                    else -> false
                }
                if (ok) {
                    // Ход человека: задуманный ботом ход отменяется, бот подумает заново.
                    gen++
                    lastActor = 0
                    main.removeCallbacks(commit)
                    botRun?.let { main.removeCallbacks(it) }
                    screen?.table?.cancelThinking()
                    persist()
                    screen?.update(e)
                    if (e.finished) screen?.finishedLocal(e) else botRun?.let { main.postDelayed(it, 450) }
                }
                ok
            },
            onNew = {
                prefs.edit().remove("game").apply()
                e = DurakEngine(engine.players, engine.mode).apply { deal(System.nanoTime()) }
                gen++
                lastActor = -1
                main.removeCallbacks(commit)
                botRun?.let { main.removeCallbacks(it) }
                persist()
                screen?.table?.newDeal()
                screen?.update(e)
                botRun?.let { main.postDelayed(it, 900) }
            },
            onClose = {
                gen++
                main.removeCallbacks(commit)
                botRun?.let { main.removeCallbacks(it) }
                persist()
                com.stoyko.smessenger.ui.BackgroundSaver.unregister(prefs)
            },
        )
        com.stoyko.smessenger.ui.BackgroundSaver.register(prefs) { persist() }
        screen = sc
        sc.show()
        sc.update(e)
        if (e.finished) sc.finishedLocal(e) else botRun?.let { main.postDelayed(it, 900) }
    }

    /**
     * Сколько бот «думает» перед ходом, как живой: обычно 0.4-1.1 с; дольше - когда решение
     * трудное (отбиваться от нескольких карт, брать или переводить, конец колоды), быстрее -
     * «Бито»/«Пас». Следующий бот после другого игрока - с небольшой паузой.
     */
    private fun thinkMs(e: DurakEngine, sim: DurakEngine, p: Int, card: Int, took: Boolean, lastActor: Int, rnd: java.util.Random): Long {
        var ms = if (card < 0 && !took) 300L + rnd.nextInt(400) else 400L + rnd.nextInt(701)
        if (p == e.defender) {
            ms += (minOf(3, maxOf(0, e.uncovered().size - 1)) * 130).toLong()
            if (took) ms += 320L
            if (sim.lastEvent == "transfer") ms += 200L
        }
        if (e.deck.isEmpty()) ms += 140L
        if (lastActor >= 0 && lastActor != p && e.players > 2) ms += 180L
        if (LiteMode.on) ms = ms * 4 / 5
        return ms.coerceAtMost(1800L)
    }

    /** Онлайн-партия. */
    fun showOnline(ctx: Context, id: String) {
        DurakNet.load(ctx, id) ?: return
        var screen: Screen? = null
        val listener: (DurakNet.Save) -> Unit = { s ->
            if (s.id == id) screen?.updateOnline(s)
        }
        val s0 = DurakNet.load(ctx, id)!!
        val sc = Screen(
            ctx,
            title = modeName(ctx, s0.mode) + if (s0.stake > 0) "  ·  🪙 ${s0.stake} × ${s0.players}" else "",
            me = s0.mySeat,
            names = s0.names,
            chatEnabled = true,
            act = { a, c, i ->
                val s = DurakNet.load(ctx, id)
                s != null && s.state == "playing" && DurakNet.act(ctx, s, a, c, i)
            },
            onChat = { t -> DurakNet.load(ctx, id)?.let { DurakNet.chat(ctx, it, t) } },
            // Онлайн-партия записывается в SavePlayer.save при каждом ходе и сообщении.
            onClose = { DurakNet.unlisten(listener) },
        )
        screen = sc
        DurakNet.listen(listener)
        sc.show()
        sc.updateOnline(s0)
        sc.connect()
        DurakNet.sync(ctx, s0)
        DurakNet.here(ctx, s0)
    }

    /** Общий экран. */
    private class Screen(
        val ctx: Context,
        val title: String,
        val me: Int,
        val names: List<String>,
        val chatEnabled: Boolean,
        val act: (String, Int, Int) -> Boolean,
        val onChat: ((String) -> Unit)? = null,
        val onNew: (() -> Unit)? = null,
        val onClose: () -> Unit,
    ) {
        private val d = ctx.resources.displayMetrics.density
        private fun dp(v: Int) = (v * d).toInt()
        val dialog = com.stoyko.smessenger.ui.DialogTracker.add(Dialog(ctx, R.style.AppTheme_FullDialog))
        val table = DurakTable(ctx)
        val stage = FrameLayout(ctx)
        val status = TextView(ctx)
        var takeAllowed = true
        val take = Btn.nice(ctx, ctx.getString(R.string.durak_take), intArrayOf(0xFFFB7185.toInt(), 0xFFE11D48.toInt())) {
            if (takeAllowed) act("take", -1, -1)
        }
        val pass = Btn.nice(ctx, ctx.getString(R.string.durak_pass), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) { act("pass", -1, -1) }
        private var engineNow: DurakEngine? = null

        /** Переводной: «Перевести» выбранной картой (или самой младшей подходящей). */
        val transfer = Btn.nice(ctx, ctx.getString(R.string.durak_transfer), intArrayOf(0xFF60A5FA.toInt(), 0xFF2563EB.toInt())) {
            val e = engineNow ?: return@nice
            val sel = table.selected
            val card = if (sel >= 0 && e.canTransfer(me, sel)) sel else e.hands[me].filter { e.canTransfer(me, it) }
                .minByOrNull { (if (it / 9 == e.trump) 10 else 0) + it % 9 } ?: return@nice
            if (!act("tr", card, -1)) table.shake(card)
        }
        val again = Btn.nice(ctx, ctx.getString(R.string.durak_new), Btn.PALETTE[1]) { onNew?.invoke() }
        val chatBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val chatScroll = android.widget.ScrollView(ctx).apply { addView(chatBox) }
        private var lastChat = -1

        init {
            table.me = me
            table.names = names
            table.onAct = act
            table.onHint = { msg -> status.text = msg }
        }

        fun show() {
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF171340.toInt())
            }
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(12), dp(4))
            }
            bar.addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Color.WHITE)
                background = null
                setOnClickListener { dialog.safeDismiss() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            bar.addView(TextView(ctx).apply {
                text = title
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // ⚙ Вид карт: светлые (по умолчанию) или тёмные с подсветкой.
            bar.addView(TextView(ctx).apply {
                text = "⚙"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x33FFFFFF)
                }
                setOnClickListener { cardSettings() }
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
            bar.addView(TextView(ctx).apply {
                text = "?"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x33FFFFFF)
                }
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle(R.string.durak_rules_title)
                        .setMessage(ctx.getString(R.string.durak_rules) + (engineNow?.let { "\n\n" + DurakGame.modeName(ctx, it.mode) + ": " + ctx.getString(DurakGame.MODE_DESCS[it.mode]) } ?: ""))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            root.addView(bar)
            stage.addView(table, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            status.apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(6), dp(10), dp(4))
                minLines = 1
                maxLines = 2
            }
            root.addView(status)
            if (chatEnabled) {
                root.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { setMargins(dp(12), dp(2), dp(12), 0) })
                val input = EditText(ctx).apply {
                    setHint(R.string.chess_chat_hint)
                    setSingleLine()
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setHintTextColor(0x88FFFFFF.toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 18 * d
                        setColor(0x22FFFFFF)
                    }
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                }
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(4), dp(12), 0)
                }
                row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Btn.nice(ctx, "➤", Btn.PALETTE[0]) {
                    val t = input.text.toString().trim()
                    if (t.isNotEmpty()) {
                        onChat?.invoke(t)
                        input.setText("")
                    }
                }, LinearLayout.LayoutParams(dp(56), dp(44)).apply { marginStart = dp(8) })
                root.addView(row)
            }
            val actions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(8), dp(12), dp(12))
                isMotionEventSplittingEnabled = false
            }
            actions.addView(take, LinearLayout.LayoutParams(0, dp(50), 1f))
            actions.addView(transfer, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(10) })
            actions.addView(pass, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(10) })
            actions.addView(again, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(10) })
            root.addView(actions)
            root.fitSystemBars()
            dialog.setContentView(root)
            dialog.setOnDismissListener {
                onClose()
                CardArt.clear()
            }
            dialog.show()
        }

        /** Настройки игры: тёмные карты (переключатель) и цвет подсветки. */
        private fun cardSettings() {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            val glowRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            fun refreshGlow() {
                glowRow.removeAllViews()
                glowRow.alpha = if (CardArt.dark(ctx)) 1f else 0.35f
                CardArt.GLOWS.forEachIndexed { i, col ->
                    glowRow.addView(View(ctx).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(col)
                            if (i == CardArt.glow(ctx)) setStroke(dp(3), Color.WHITE)
                        }
                        elevation = if (i == CardArt.glow(ctx)) 6 * d else 0f
                        setOnClickListener {
                            if (!CardArt.dark(ctx)) return@setOnClickListener
                            CardArt.setGlow(ctx, i)
                            refreshGlow()
                            table.invalidate()
                        }
                    }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
                }
            }
            // Колода: авторская (по умолчанию), светлая или тёмная с подсветкой.
            val styles = intArrayOf(R.string.durak_style_art, R.string.durak_style_light, R.string.durak_style_dark, R.string.durak_style_cyber, R.string.durak_style_runes)
            box.addView(android.widget.RadioGroup(ctx).apply {
                orientation = LinearLayout.VERTICAL
                styles.forEachIndexed { i, label ->
                    addView(android.widget.RadioButton(ctx).apply {
                        id = View.generateViewId()
                        setText(label)
                        textSize = 16f
                        isChecked = CardArt.style(ctx) == i
                        setOnClickListener {
                            CardArt.setStyle(ctx, i)
                            refreshGlow()
                            table.requestLayout()
                            table.relayout()
                        }
                    })
                }
            })
            box.addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
                setText(R.string.games_sound)
                textSize = 16f
                isChecked = GameSfx.enabled(ctx)
                setPadding(0, dp(10), 0, 0)
                setOnCheckedChangeListener { _, v -> GameSfx.setEnabled(ctx, v) }
            })
            box.addView(TextView(ctx).apply {
                setText(R.string.durak_glow)
                textSize = 13f
                setPadding(0, dp(12), 0, 0)
            })
            box.addView(glowRow)
            refreshGlow()
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.durak_settings)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        fun connect() = ChessGame.showConnect(stage)

        fun update(e: DurakEngine) {
            table.setState(e)
            engineNow = e
            val iDef = me == e.defender
            transfer.visibility = if (!e.finished && iDef && e.hands[me].any { e.canTransfer(me, it) }) View.VISIBLE else View.GONE
            take.visibility = if (!e.finished && iDef && e.table.isNotEmpty()) View.VISIBLE else View.GONE
            // «Беру» активна, только пока на столе есть непобитая карта: всё отбили и ждёте
            // «Бито» от соперника - кнопка неактивна (раньше можно было случайно забрать отбитое).
            val canTake = e.uncovered().isNotEmpty()
            takeAllowed = canTake
            take.isEnabled = canTake
            take.alpha = if (canTake) 1f else 0.4f
            pass.visibility = if (!e.finished && e.canPass(me) && e.uncovered().isEmpty()) View.VISIBLE else View.GONE
            again.visibility = if (e.finished && onNew != null) View.VISIBLE else View.GONE
            pass.text = ctx.getString(if (me == e.attacker) R.string.durak_bito else R.string.durak_pass)
            status.text = when {
                e.finished && e.durak == -2 -> ctx.getString(R.string.durak_draw)
                e.finished && e.durak == me -> ctx.getString(R.string.durak_you_lost)
                e.finished -> ctx.getString(R.string.durak_you_won)
                me in e.out -> ctx.getString(R.string.durak_you_out)
                iDef && e.uncovered().isNotEmpty() && e.hands[me].any { e.canTransfer(me, it) } -> ctx.getString(R.string.durak_st_defend_or_transfer)
                iDef && e.uncovered().isNotEmpty() -> ctx.getString(R.string.durak_st_defend)
                iDef -> ctx.getString(R.string.durak_st_wait_attack)
                e.table.isEmpty() && me == e.attacker -> ctx.getString(R.string.durak_st_your_move)
                e.table.isEmpty() -> ctx.getString(R.string.durak_st_their_move, names.getOrElse(e.attacker) { "?" })
                e.canPass(me) -> ctx.getString(R.string.durak_st_throw)
                else -> ctx.getString(R.string.durak_st_wait, names.getOrElse(e.defender) { "?" })
            }
        }

        fun finishedLocal(e: DurakEngine) {
            runCatching { com.stoyko.smessenger.games.stats.GameStats.durak(ctx, if (e.durak == -2) 0 else if (e.durak == me) -1 else 1) }
            if (e.durak != me && e.durak != -2) {
                Coins.add(ctx, 2)
                Coins.addDurakWin(ctx)
                GameSfx.play(ctx, GameSfx.S.WIN)
                table.postDelayed({ CoinSound.jackpot(2) }, 900)
                table.flash(ctx.getString(R.string.durak_win_banner))
            } else if (e.durak == me) {
                GameSfx.play(ctx, GameSfx.S.LOSE)
                table.flash(ctx.getString(R.string.durak_lose_banner))
            }
            update(e)
        }

        private var lastState = ""

        fun updateOnline(s: DurakNet.Save) {
            val e = s.engineOrNull()
            if (e != null) update(e)
            if (e == null || s.state != "playing" && s.state != "finished") {
                take.visibility = View.GONE
                pass.visibility = View.GONE
                transfer.visibility = View.GONE
                again.visibility = View.GONE
                status.text = when (s.state) {
                    "waiting", "incoming" -> ctx.getString(R.string.durak_waiting_players, s.accepted.size, s.players)
                    "declined", "canceled" -> ctx.getString(R.string.durak_canceled)
                    else -> status.text
                }
            }
            if (s.state == "finished" && lastState == "playing" && e != null) {
                if (e.durak == s.mySeat) {
                    GameSfx.play(ctx, GameSfx.S.LOSE)
                    table.flash(ctx.getString(R.string.durak_lose_banner))
                } else {
                    GameSfx.play(ctx, GameSfx.S.WIN)
                    CoinSound.jackpot(if (s.stake > 0) s.stake else 3)
                    table.flash(ctx.getString(R.string.durak_win_banner))
                }
            }
            lastState = s.state
            if (s.chat.size != lastChat) {
                lastChat = s.chat.size
                chatBox.removeAllViews()
                for (line in s.chat.takeLast(30)) {
                    val who = line.substringBefore(':')
                    val sys = who == "sys"
                    val seat = who.toIntOrNull()
                    chatBox.addView(TextView(ctx).apply {
                        text = if (sys) line.substringAfter(':') else (if (seat == s.mySeat) "" else s.names.getOrElse(seat ?: 0) { "?" } + ": ") + line.substringAfter(':')
                        textSize = 13f
                        setTextColor(if (sys) 0xFFFDE047.toInt() else Color.WHITE)
                        gravity = if (seat == s.mySeat) Gravity.END else Gravity.START
                        setPadding(dp(4), dp(1), dp(4), dp(1))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }
}
